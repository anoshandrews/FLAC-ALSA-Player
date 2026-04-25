package dev.anosh.musicplayer.usb

import dev.anosh.musicplayer.audio.DecodedAudioFile

class UsbStreamingEngine {
    private var activeSession: UsbStreamingSession? = null

    fun prepare(
        track: DecodedAudioFile,
        devices: List<UsbAudioDeviceSummary>,
    ): UsbStreamingPreparation {
        val candidate = UsbPlaybackConfigResolver.resolve(track, devices)
            ?: return UsbStreamingPreparation.Unavailable("No matching USB streaming interface")

        val existing = activeSession
        if (existing == null) {
            return UsbStreamingPreparation.Ready(candidate, needsReconfigure = false)
        }

        val needsReconfigure =
            existing.sampleRate != candidate.sampleRate ||
                existing.channelCount != candidate.channelCount ||
                existing.bitDepth != candidate.bitDepth ||
                existing.interfaceNumber != candidate.interfaceNumber ||
                existing.alternateSetting != candidate.alternateSetting

        return UsbStreamingPreparation.Ready(candidate, needsReconfigure = needsReconfigure)
    }

    fun activate(candidate: UsbPlaybackCandidate) {
        activeSession = UsbStreamingSession(
            deviceLabel = candidate.deviceLabel,
            interfaceNumber = candidate.interfaceNumber,
            alternateSetting = candidate.alternateSetting,
            sampleRate = candidate.sampleRate,
            channelCount = candidate.channelCount,
            bitDepth = candidate.bitDepth,
        )
    }

    fun stop() {
        activeSession = null
    }

    fun activeSession(): UsbStreamingSession? = activeSession
}

sealed class UsbStreamingPreparation {
    data class Ready(
        val candidate: UsbPlaybackCandidate,
        val needsReconfigure: Boolean,
    ) : UsbStreamingPreparation()

    data class Unavailable(
        val reason: String,
    ) : UsbStreamingPreparation()
}

data class UsbStreamingSession(
    val deviceLabel: String,
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val sampleRate: Int,
    val channelCount: Int,
    val bitDepth: Int,
)
