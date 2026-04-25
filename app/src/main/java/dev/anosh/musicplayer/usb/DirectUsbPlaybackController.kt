package dev.anosh.musicplayer.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import dev.anosh.musicplayer.audio.DecodedAudioFile
import dev.anosh.musicplayer.audio.PcmBufferController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.LockSupport

class DirectUsbPlaybackController(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val tag = "musicplayer-usb"
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var producerJob: Job? = null
    private var consumerJob: Job? = null
    private var pcmBufferController: PcmBufferController? = null
    private var nativeUsbAudioEngine: NativeUsbAudioEngine? = null
    private var activeConnection: UsbDeviceConnection? = null
    private var activeInterface: UsbInterface? = null
    var usbErrorListener: ((String) -> Unit)? = null

    suspend fun play(
        audioFile: DecodedAudioFile,
        candidate: UsbPlaybackCandidate,
    ): DirectUsbStartResult {
        stop()

        val usbDevice = usbManager?.deviceList?.values
            ?.firstOrNull { it.deviceName == candidate.deviceName }
            ?: return DirectUsbStartResult.Failed("USB device is no longer connected").also {
                Log.e(tag, "USB device vanished before playback: ${candidate.deviceName}")
            }

        if (usbManager?.hasPermission(usbDevice) != true) {
            Log.w(tag, "USB permission missing for ${candidate.deviceLabel}")
            return DirectUsbStartResult.PermissionRequired("USB permission is required before exclusive output can start")
        }

        return withContext(dispatcher) {
            val connection = usbManager?.openDevice(usbDevice)
                ?: return@withContext DirectUsbStartResult.Failed("Unable to open USB device").also {
                    Log.e(tag, "openDevice returned null for ${candidate.deviceLabel}")
                }
            val usbInterface = (0 until usbDevice.interfaceCount)
                .map { usbDevice.getInterface(it) }
                .firstOrNull {
                    it.id == candidate.interfaceNumber &&
                        it.alternateSetting == candidate.alternateSetting
                }
                ?: run {
                    Log.e(tag, "Target USB interface not found if=${candidate.interfaceNumber} alt=${candidate.alternateSetting}")
                    connection.close()
                    return@withContext DirectUsbStartResult.Failed("Unable to find target USB interface")
                }
            val endpoint = (0 until usbInterface.endpointCount)
                .map { usbInterface.getEndpoint(it) }
                .firstOrNull { it.address == candidate.endpointAddress && it.direction == UsbConstants.USB_DIR_OUT }
                ?: run {
                    Log.e(tag, "Target USB endpoint not found ep=0x${candidate.endpointAddress.toString(16)}")
                    connection.close()
                    return@withContext DirectUsbStartResult.Failed("Unable to find target USB endpoint")
                }

            if (!connection.claimInterface(usbInterface, true)) {
                Log.e(tag, "claimInterface failed for if=${usbInterface.id} alt=${usbInterface.alternateSetting}")
                connection.close()
                return@withContext DirectUsbStartResult.Failed("Failed to claim USB interface")
            }

            if (!connection.setInterface(usbInterface)) {
                Log.e(tag, "setInterface failed for if=${usbInterface.id} alt=${usbInterface.alternateSetting}")
                connection.releaseInterface(usbInterface)
                connection.close()
                return@withContext DirectUsbStartResult.Failed("Failed to select alternate interface setting")
            }

            val pcmBuffer = PcmBufferController(recommendedBufferSize(audioFile))
            val nativeEngine = NativeUsbAudioEngine(
                fileDescriptor = connection.fileDescriptor,
                endpointAddress = candidate.endpointAddress,
                endpointTransferType = candidate.endpointTransferType,
                packetSize = candidate.endpointMaxPacketSize,
            )
            activeConnection = connection
            activeInterface = usbInterface
            pcmBufferController = pcmBuffer
            nativeUsbAudioEngine = nativeEngine
            producerJob = scope.launch {
                produceToBuffer(audioFile, pcmBuffer)
            }
            consumerJob = scope.launch {
                consumeToUsb(audioFile, pcmBuffer, nativeEngine, candidate) { error ->
                    usbErrorListener?.invoke(error.message ?: "Direct USB transfer failed")
                }
            }

            Log.i(
                tag,
                "Started direct USB candidate=${candidate.deviceLabel} if=${candidate.interfaceNumber} alt=${candidate.alternateSetting} ep=0x${candidate.endpointAddress.toString(16)} type=${candidate.endpointTransferType} maxPacket=${candidate.endpointMaxPacketSize} expectedInterval=${candidate.expectedBytesPerInterval} framesPerInterval=${candidate.framesPerInterval} sampleRate=${audioFile.sampleRate} bytesPerFrame=${audioFile.bytesPerFrame}",
            )
            DirectUsbStartResult.Started(candidate)
        }
    }

    suspend fun stop() {
        producerJob?.cancelAndJoin()
        producerJob = null
        consumerJob?.cancelAndJoin()
        consumerJob = null
        withContext(dispatcher) {
            pcmBufferController?.close()
            pcmBufferController = null
            nativeUsbAudioEngine?.close()
            nativeUsbAudioEngine = null
            activeInterface?.let { usbInterface ->
                activeConnection?.releaseInterface(usbInterface)
            }
            activeInterface = null
            activeConnection?.close()
            activeConnection = null
        }
    }

    private suspend fun produceToBuffer(audioFile: DecodedAudioFile, pcmBuffer: PcmBufferController) {
        val chunkSize = maxOf(audioFile.bytesPerFrame * 256, audioFile.bytesPerFrame)
        var offset = 0
        try {
            while (offset < audioFile.pcmData.size) {
                val remaining = audioFile.pcmData.size - offset
                val requested = minOf(chunkSize, remaining)
                val written = pcmBuffer.enqueuePcm(audioFile.pcmData, offset, requested)
                if (written == 0) {
                    delay(2)
                } else {
                    offset += written
                }
            }
        } catch (_: CancellationException) {
            return
        }
    }

    private suspend fun consumeToUsb(
        audioFile: DecodedAudioFile,
        pcmBuffer: PcmBufferController,
        nativeEngine: NativeUsbAudioEngine,
        candidate: UsbPlaybackCandidate,
        onError: (Throwable) -> Unit,
    ) {
        val isIsochronous = candidate.endpointTransferType == "iso"
        var nextIsoDeadline = System.nanoTime() + 2_000_000L
        var consumed = 0
        try {
            while (consumed < audioFile.pcmData.size) {
                if (isIsochronous) {
                    val waitNanos = nextIsoDeadline - System.nanoTime()
                    if (waitNanos > 0) {
                        LockSupport.parkNanos(waitNanos)
                    }
                }
                val available = pcmBuffer.availableBytes()
                val targetChunkSize = if (isIsochronous) {
                    candidate.endpointMaxPacketSize.coerceAtLeast(audioFile.bytesPerFrame)
                } else {
                    minOf(
                        candidate.endpointMaxPacketSize.takeIf { it > 0 } ?: audioFile.bytesPerFrame * 256,
                        audioFile.pcmData.size - consumed,
                    ).coerceAtLeast(audioFile.bytesPerFrame)
                }
                if (available < targetChunkSize && consumed + available < audioFile.pcmData.size) {
                    delay(2)
                    continue
                }
                val readableSize = if (isIsochronous) {
                    minOf(targetChunkSize, available - (available % audioFile.bytesPerFrame))
                } else {
                    minOf(targetChunkSize, available)
                }
                val chunk = pcmBuffer.dequeuePcm(readableSize)
                if (chunk.isEmpty()) {
                    delay(2)
                    continue
                }
                val usbChunk = if (isIsochronous && chunk.size < targetChunkSize) {
                    ByteArray(targetChunkSize).also { padded ->
                        chunk.copyInto(padded)
                    }
                } else {
                    chunk
                }
                val transferred = nativeEngine.write(usbChunk, 0, usbChunk.size)
                if (transferred < 0) {
                    val error = IllegalStateException("Native USB transfer failed chunk=${usbChunk.size}")
                    Log.e(tag, error.message ?: "Native USB transfer failed chunk=${usbChunk.size}")
                    onError(error)
                    return
                }
                consumed += chunk.size
                if (isIsochronous) {
                    nextIsoDeadline += 1_000_000L
                }
            }
            Log.i(tag, "Direct USB playback completed bytes=$consumed")
        } catch (error: CancellationException) {
            Log.i(tag, "Direct USB playback cancelled")
            return
        } catch (error: Throwable) {
            Log.e(tag, "Direct USB playback crashed", error)
            onError(error)
            return
        }
    }

    private fun recommendedBufferSize(audioFile: DecodedAudioFile): Int {
        val targetMs = 200
        val bytesPerSecond = audioFile.sampleRate * audioFile.bytesPerFrame
        return ((bytesPerSecond * targetMs) / 1000).coerceAtLeast(audioFile.bytesPerFrame * 1024)
    }
}

sealed class DirectUsbStartResult {
    data class Started(val candidate: UsbPlaybackCandidate) : DirectUsbStartResult()
    data class PermissionRequired(val reason: String) : DirectUsbStartResult()
    data class Unsupported(val reason: String) : DirectUsbStartResult()
    data class Failed(val reason: String) : DirectUsbStartResult()
}
