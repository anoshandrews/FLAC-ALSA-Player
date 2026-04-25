package dev.anosh.musicplayer.audio

data class WavFile(
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val audioFormat: Int,
    val pcmData: ByteArray,
) {
    val bytesPerFrame: Int = channelCount * (bitsPerSample / 8)
}
