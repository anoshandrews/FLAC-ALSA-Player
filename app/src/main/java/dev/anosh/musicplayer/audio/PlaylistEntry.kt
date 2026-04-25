package dev.anosh.musicplayer.audio

import android.net.Uri

data class PlaylistEntry(
    val uri: Uri,
    val displayName: String,
)
