package dev.anosh.musicplayer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class PlaybackController(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + dispatcher)
    private var playbackJob: Job? = null
    private var producerJob: Job? = null
    private var pcmBufferController: PcmBufferController? = null
    private var audioTrack: AudioTrack? = null
    private var pcmChunkListener: ((DecodedAudioFile, ByteArray) -> Unit)? = null

    fun setPcmChunkListener(listener: ((DecodedAudioFile, ByteArray) -> Unit)?) {
        pcmChunkListener = listener
    }

    suspend fun play(audioFile: DecodedAudioFile) {
        stop()
        withContext(dispatcher) {
            val track = buildTrack(audioFile)
            val pcmBuffer = PcmBufferController(capacityBytes = recommendedBufferSize(audioFile))
            pcmBufferController = pcmBuffer
            audioTrack = track
            producerJob = scope.launch {
                produceToBuffer(audioFile, pcmBuffer)
            }
            playbackJob = scope.launch {
                track.play()
                consumeFromBuffer(audioFile, pcmBuffer, track)
            }
        }
    }

    suspend fun stop() {
        producerJob?.cancelAndJoin()
        producerJob = null
        playbackJob?.cancelAndJoin()
        playbackJob = null
        withContext(dispatcher) {
            audioTrack?.runCatching {
                pause()
                flush()
                release()
            }
            audioTrack = null
            pcmBufferController?.close()
            pcmBufferController = null
        }
    }

    fun release() {
        runBlocking {
            stop()
        }
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun produceToBuffer(
        audioFile: DecodedAudioFile,
        pcmBuffer: PcmBufferController,
    ) {
        val chunkSize = (audioFile.bytesPerFrame * 256).coerceAtLeast(audioFile.bytesPerFrame)
        var offset = 0
        try {
            while (offset < audioFile.pcmData.size) {
                val remaining = audioFile.pcmData.size - offset
                val length = minOf(chunkSize, remaining)
                val written = pcmBuffer.enqueuePcm(audioFile.pcmData, offset, length)
                if (written == 0) {
                    delay(4)
                } else {
                    offset += written
                }
            }
        } catch (_: CancellationException) {
            return
        }
    }

    private suspend fun consumeFromBuffer(
        audioFile: DecodedAudioFile,
        pcmBuffer: PcmBufferController,
        track: AudioTrack,
    ) {
        val chunkSize = (audioFile.bytesPerFrame * 256).coerceAtLeast(audioFile.bytesPerFrame)
        var consumed = 0
        try {
            while (consumed < audioFile.pcmData.size) {
                val available = pcmBuffer.availableBytes()
                if (available == 0) {
                    delay(4)
                    continue
                }
                val chunk = pcmBuffer.dequeuePcm(minOf(chunkSize, available))
                if (chunk.isEmpty()) {
                    delay(2)
                    continue
                }
                track.write(chunk, 0, chunk.size)
                pcmChunkListener?.invoke(audioFile, chunk)
                consumed += chunk.size
            }
            track.stop()
            track.flush()
        } catch (_: CancellationException) {
            track.pause()
            track.flush()
        }
    }

    private fun buildTrack(audioFile: DecodedAudioFile): AudioTrack {
        val channelMask = when (audioFile.channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> throw IllegalArgumentException("Unsupported channel count: ${audioFile.channelCount}")
        }

        val minBufferSize = AudioTrack.getMinBufferSize(audioFile.sampleRate, channelMask, audioFile.audioEncoding)
        require(minBufferSize > 0) { "Invalid AudioTrack buffer size" }

        return AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(audioFile.sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(audioFile.audioEncoding)
                .build(),
            maxOf(minBufferSize, recommendedBufferSize(audioFile)),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
    }

    private fun recommendedBufferSize(audioFile: DecodedAudioFile): Int {
        val targetMs = 200
        val bytesPerSecond = audioFile.sampleRate * audioFile.bytesPerFrame
        return ((bytesPerSecond * targetMs) / 1000).coerceAtLeast(audioFile.bytesPerFrame * 1024)
    }
}
