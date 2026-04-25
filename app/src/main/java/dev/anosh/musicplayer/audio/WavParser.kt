package dev.anosh.musicplayer.audio

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavParser {
    private const val HEADER_RIFF = "RIFF"
    private const val HEADER_WAVE = "WAVE"
    private const val CHUNK_FMT = "fmt "
    private const val CHUNK_DATA = "data"

    fun parse(bytes: ByteArray): WavFile {
        require(bytes.size >= 44) { "File is too small to be a WAV container" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val riff = readFourCc(buffer)
        if (riff != HEADER_RIFF) {
            throw IOException("Unsupported file header: $riff")
        }

        buffer.int
        val wave = readFourCc(buffer)
        if (wave != HEADER_WAVE) {
            throw IOException("Unsupported container type: $wave")
        }

        var sampleRate = 0
        var channelCount = 0
        var bitsPerSample = 0
        var audioFormat = 0
        var pcmData: ByteArray? = null

        while (buffer.remaining() >= 8) {
            val chunkId = readFourCc(buffer)
            val chunkSize = buffer.int
            if (chunkSize < 0 || chunkSize > buffer.remaining()) {
                throw IOException("Invalid WAV chunk size for $chunkId")
            }

            when (chunkId) {
                CHUNK_FMT -> {
                    if (chunkSize < 16) {
                        throw IOException("Invalid fmt chunk")
                    }
                    audioFormat = buffer.short.toInt() and 0xFFFF
                    channelCount = buffer.short.toInt() and 0xFFFF
                    sampleRate = buffer.int
                    buffer.int
                    buffer.short
                    bitsPerSample = buffer.short.toInt() and 0xFFFF

                    val remainingFmtBytes = chunkSize - 16
                    if (remainingFmtBytes > 0) {
                        buffer.position(buffer.position() + remainingFmtBytes)
                    }
                }

                CHUNK_DATA -> {
                    pcmData = ByteArray(chunkSize)
                    buffer.get(pcmData)
                }

                else -> {
                    buffer.position(buffer.position() + chunkSize)
                }
            }

            if (chunkSize % 2 == 1 && buffer.hasRemaining()) {
                buffer.get()
            }
        }

        if (audioFormat != 1 && audioFormat != 3) {
            throw IOException("Only PCM and IEEE float WAV files are supported")
        }
        if (sampleRate <= 0 || channelCount <= 0 || bitsPerSample <= 0 || pcmData == null) {
            throw IOException("Missing WAV metadata or PCM payload")
        }

        return WavFile(
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
            audioFormat = audioFormat,
            pcmData = pcmData,
        )
    }

    private fun readFourCc(buffer: ByteBuffer): String {
        val chars = CharArray(4)
        repeat(4) { index ->
            chars[index] = (buffer.get().toInt() and 0xFF).toChar()
        }
        return String(chars)
    }
}
