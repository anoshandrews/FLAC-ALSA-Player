package dev.anosh.musicplayer.audio

data class DecodedAudioFile(
    val displayName: String,
    val sourceFormat: String,
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val audioEncoding: Int,
    val pcmData: ByteArray,
) {
    val bytesPerFrame: Int = channelCount * (bitsPerSample / 8)
}
