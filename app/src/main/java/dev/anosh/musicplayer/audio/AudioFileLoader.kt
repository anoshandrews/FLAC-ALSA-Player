package dev.anosh.musicplayer.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.IOException
import java.nio.ByteBuffer

class AudioFileLoader(
    private val context: Context,
) {
    fun inspect(uri: Uri): AudioSelection {
        val extractor = MediaExtractor()
        runCatching {
            extractor.setDataSource(context, uri, emptyMap())
        }.getOrElse {
            val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
                ?: throw IOException("Unable to open asset descriptor")
            descriptor.use { afd ->
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
        }

        try {
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No supported audio track found")

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/unknown"
            val pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                null
            }
            return AudioSelection(
                uri = uri,
                displayName = displayName(uri),
                sourceFormat = mime.substringAfter('/').uppercase(),
                sampleRate = format.takeIf { it.containsKey(MediaFormat.KEY_SAMPLE_RATE) }?.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channelCount = format.takeIf { it.containsKey(MediaFormat.KEY_CHANNEL_COUNT) }?.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                bitsPerSample = pcmEncoding?.toBitsPerSample() ?: extractBitDepth(uri),
                audioEncoding = pcmEncoding,
            )
        } finally {
            extractor.release()
        }
    }

    suspend fun load(uri: Uri): DecodedAudioFile {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Unable to open selected file")

        return runCatching {
            val wav = WavParser.parse(bytes)
            wav.toDecodedFile(displayName(uri))
        }.getOrElse {
            decodeWithPlatform(uri)
        }
    }

    private fun decodeWithPlatform(uri: Uri): DecodedAudioFile {
        val extractor = MediaExtractor()
        runCatching {
            extractor.setDataSource(context, uri, emptyMap())
        }.getOrElse {
            val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
                ?: throw IOException("Unable to open asset descriptor")
            descriptor.use { afd ->
                extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
        }

        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("No supported audio track found")

        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: throw IOException("Missing MIME type")
        val codec = MediaCodec.createDecoderByType(mime)

        return try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            decodeToPcm(codec, extractor, inputFormat, mime, displayName(uri))
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
    }

    private fun decodeToPcm(
        codec: MediaCodec,
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        mime: String,
        displayName: String,
    ): DecodedAudioFile {
        val output = ArrayList<ByteArray>()
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        var outputFormat: MediaFormat? = null

        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex) ?: ByteBuffer.allocate(0)
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            sampleSize,
                            extractor.sampleTime,
                            0,
                        )
                        extractor.advance()
                    }
                }
            }

            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputFormat = codec.outputFormat
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                else -> {
                    if (outputIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                                ?: throw IOException("Missing codec output buffer")
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(chunk)
                            output.add(chunk)
                        }
                        sawOutputEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }

        val decodedBytes = ByteArray(output.sumOf { it.size })
        var writeOffset = 0
        output.forEach { chunk ->
            chunk.copyInto(decodedBytes, destinationOffset = writeOffset)
            writeOffset += chunk.size
        }

        val finalFormat = outputFormat ?: inputFormat
        val sampleRate = finalFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = finalFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val pcmEncoding = if (finalFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            finalFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        val bitsPerSample = when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 8
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            AudioFormat.ENCODING_PCM_32BIT -> 32
            AudioFormat.ENCODING_PCM_FLOAT -> 32
            else -> 16
        }

        return DecodedAudioFile(
            displayName = displayName,
            sourceFormat = mime.substringAfter('/').uppercase(),
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
            audioEncoding = pcmEncoding,
            pcmData = decodedBytes,
        )
    }

    private fun WavFile.toDecodedFile(displayName: String): DecodedAudioFile {
        val encoding = when {
            audioFormat == 1 && bitsPerSample == 8 -> AudioFormat.ENCODING_PCM_8BIT
            audioFormat == 1 && bitsPerSample == 16 -> AudioFormat.ENCODING_PCM_16BIT
            audioFormat == 1 && bitsPerSample == 24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            audioFormat == 1 && bitsPerSample == 32 -> AudioFormat.ENCODING_PCM_32BIT
            audioFormat == 3 && bitsPerSample == 32 -> AudioFormat.ENCODING_PCM_FLOAT
            else -> throw IOException("Unsupported WAV encoding")
        }
        return DecodedAudioFile(
            displayName = displayName,
            sourceFormat = "WAV",
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
            audioEncoding = encoding,
            pcmData = pcmData,
        )
    }

    private fun displayName(uri: Uri): String {
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Selected Audio"
    }

    private fun extractBitDepth(uri: Uri): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun Int.toBitsPerSample(): Int {
        return when (this) {
            AudioFormat.ENCODING_PCM_8BIT -> 8
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            AudioFormat.ENCODING_PCM_32BIT -> 32
            AudioFormat.ENCODING_PCM_FLOAT -> 32
            else -> 16
        }
    }
}
