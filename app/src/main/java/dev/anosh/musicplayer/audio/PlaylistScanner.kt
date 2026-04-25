package dev.anosh.musicplayer.audio

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

class PlaylistScanner(
    private val context: Context,
) {
    fun scanTree(treeUri: Uri): List<PlaylistEntry> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        return buildList {
            collectAudioFiles(root, this)
        }.sortedBy { it.displayName.lowercase() }
    }

    private fun collectAudioFiles(
        node: DocumentFile,
        output: MutableList<PlaylistEntry>,
    ) {
        if (node.isFile && isSupportedAudio(node)) {
            output += PlaylistEntry(
                uri = node.uri,
                displayName = node.name ?: "Unknown audio file",
            )
            return
        }
        if (!node.isDirectory) {
            return
        }
        node.listFiles().forEach { child ->
            collectAudioFiles(child, output)
        }
    }

    private fun isSupportedAudio(file: DocumentFile): Boolean {
        val name = file.name?.lowercase().orEmpty()
        val mimeType = file.type?.lowercase().orEmpty()
        return name.endsWith(".wav") ||
            name.endsWith(".flac") ||
            mimeType == "audio/flac" ||
            mimeType == "audio/x-flac" ||
            mimeType == "audio/wav" ||
            mimeType == "audio/x-wav"
    }
}
