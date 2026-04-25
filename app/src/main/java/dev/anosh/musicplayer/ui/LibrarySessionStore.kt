package dev.anosh.musicplayer.ui

import dev.anosh.musicplayer.audio.PlaylistEntry

object LibrarySessionStore {
    var entries: List<PlaylistEntry> = emptyList()
    var selectedIndex: Int = -1
}
