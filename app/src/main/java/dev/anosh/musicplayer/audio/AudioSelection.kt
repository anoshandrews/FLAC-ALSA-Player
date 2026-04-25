package dev.anosh.musicplayer.audio

import android.net.Uri

data class AudioSelection(
    val uri: Uri,
    val displayName: String,
    val sourceFormat: String,
    val sampleRate: Int?,
    val channelCount: Int?,
    val bitsPerSample: Int?,
    val audioEncoding: Int?,
)
