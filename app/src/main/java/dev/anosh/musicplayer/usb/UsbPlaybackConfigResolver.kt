package dev.anosh.musicplayer.usb

import dev.anosh.musicplayer.audio.DecodedAudioFile

object UsbPlaybackConfigResolver {
    fun resolve(
        track: DecodedAudioFile,
        devices: List<UsbAudioDeviceSummary>,
    ): UsbPlaybackCandidate? {
        val expectedBytesPerInterval = expectedBytesPerInterval(track)
        val exactBytesPerInterval = exactBytesPerInterval(track)
        val framesPerInterval = expectedFramesPerInterval(track)
        return devices.asSequence()
            .flatMap { device ->
                device.audioInterfaces.asSequence().map { summary -> device to summary }
            }
            .filter { (_, summary) ->
                summary.sampleRates.isEmpty() || summary.sampleRates.contains(track.sampleRate)
            }
            .filter { (_, summary) ->
                summary.channelCount == null || summary.channelCount == track.channelCount
            }
            .filter { (_, summary) ->
                summary.bitDepth == null || summary.bitDepth == track.bitsPerSample
            }
            .filter { (_, summary) ->
                summary.endpoints.any { endpoint ->
                    endpoint.direction == "out" &&
                        (endpoint.transferType == "bulk" || endpoint.transferType == "iso") &&
                        endpointMatchesTrack(endpoint, expectedBytesPerInterval, exactBytesPerInterval)
                }
            }
            .maxByOrNull { (_, summary) ->
                score(summary, track, expectedBytesPerInterval, exactBytesPerInterval)
            }
            ?.let { (device, summary) ->
                val endpoint = summary.endpoints.first { candidateEndpoint ->
                    candidateEndpoint.direction == "out" &&
                        (candidateEndpoint.transferType == "bulk" || candidateEndpoint.transferType == "iso") &&
                        endpointMatchesTrack(candidateEndpoint, expectedBytesPerInterval, exactBytesPerInterval)
                }
                UsbPlaybackCandidate(
                    deviceName = device.deviceName,
                    deviceLabel = device.productName ?: device.manufacturerName ?: device.deviceName,
                    interfaceNumber = summary.interfaceNumber,
                    alternateSetting = summary.alternateSetting,
                    endpointAddress = endpoint.address,
                    endpointTransferType = endpoint.transferType,
                    endpointMaxPacketSize = endpoint.maxPacketSize,
                    sampleRate = track.sampleRate,
                    channelCount = track.channelCount,
                    bitDepth = track.bitsPerSample,
                    expectedBytesPerInterval = expectedBytesPerInterval,
                    framesPerInterval = framesPerInterval,
                )
            }
            ?: fallbackCandidate(track, devices)
    }

    private fun score(
        summary: UsbAudioInterfaceSummary,
        track: DecodedAudioFile,
        expectedBytesPerInterval: Int?,
        exactBytesPerInterval: Int?,
    ): Int {
        var total = 0
        if (summary.sampleRates.contains(track.sampleRate)) total += 4
        if (summary.channelCount == track.channelCount) total += 3
        if (summary.bitDepth == track.bitsPerSample) total += 3
        if (summary.endpoints.any { it.transferType == "iso" && it.direction == "out" }) total += 3
        if (summary.endpoints.any { it.transferType == "bulk" && it.direction == "out" }) total += 2
        if (exactBytesPerInterval != null &&
            summary.endpoints.any {
                it.transferType == "iso" &&
                    it.direction == "out" &&
                    it.maxPacketSize == exactBytesPerInterval
            }
        ) {
            total += 12
        }
        val smallestViableIso = summary.endpoints
            .filter {
                it.transferType == "iso" &&
                    it.direction == "out" &&
                    exactBytesPerInterval != null &&
                    it.maxPacketSize == exactBytesPerInterval
            }
            .minOfOrNull { it.maxPacketSize }
        if (smallestViableIso != null && exactBytesPerInterval != null) {
            total += (32 - (smallestViableIso - exactBytesPerInterval)).coerceAtLeast(0)
        }
        if (summary.subclassLabel == "AudioStreaming") total += 1
        return total
    }

    private fun endpointMatchesTrack(
        endpoint: UsbAudioEndpointSummary,
        expectedBytesPerInterval: Int?,
        exactBytesPerInterval: Int?,
    ): Boolean {
        if (endpoint.direction != "out") {
            return false
        }
        return when (endpoint.transferType) {
            "bulk" -> true
            "iso" -> exactBytesPerInterval != null && endpoint.maxPacketSize == exactBytesPerInterval
            else -> false
        }
    }

    private fun expectedBytesPerInterval(track: DecodedAudioFile): Int? {
        if (track.sampleRate <= 0 || track.bytesPerFrame <= 0) {
            return null
        }
        return kotlin.math.ceil((track.sampleRate.toDouble() / 1000.0) * track.bytesPerFrame.toDouble()).toInt()
    }

    private fun expectedFramesPerInterval(track: DecodedAudioFile): Double? {
        if (track.sampleRate <= 0 || track.bytesPerFrame <= 0) {
            return null
        }
        return track.sampleRate.toDouble() / 1000.0
    }

    private fun exactBytesPerInterval(track: DecodedAudioFile): Int? {
        if (track.sampleRate <= 0 || track.bytesPerFrame <= 0 || track.sampleRate % 1000 != 0) {
            return null
        }
        return (track.sampleRate / 1000) * track.bytesPerFrame
    }

    private fun fallbackCandidate(
        track: DecodedAudioFile,
        devices: List<UsbAudioDeviceSummary>,
    ): UsbPlaybackCandidate? {
        val expectedBytes = expectedBytesPerInterval(track)
        val exactBytes = exactBytesPerInterval(track)
        return devices.asSequence()
            .flatMap { device ->
                device.audioInterfaces.asSequence().map { device to it }
            }
            .filter { (_, summary) -> summary.subclassLabel == "AudioStreaming" }
            .mapNotNull { (device, summary) ->
                val endpoint = summary.endpoints.firstOrNull {
                    when (it.transferType) {
                        "bulk" -> it.direction == "out"
                        "iso" -> it.direction == "out" && exactBytes != null && it.maxPacketSize == exactBytes
                        else -> false
                    }
                }
                    ?: return@mapNotNull null
                UsbPlaybackCandidate(
                    deviceName = device.deviceName,
                    deviceLabel = device.productName ?: device.manufacturerName ?: device.deviceName,
                    interfaceNumber = summary.interfaceNumber,
                    alternateSetting = summary.alternateSetting,
                    endpointAddress = endpoint.address,
                    endpointTransferType = endpoint.transferType,
                    endpointMaxPacketSize = endpoint.maxPacketSize,
                    sampleRate = track.sampleRate,
                    channelCount = track.channelCount,
                    bitDepth = track.bitsPerSample,
                    expectedBytesPerInterval = expectedBytes,
                    framesPerInterval = expectedFramesPerInterval(track),
                )
            }
            .maxByOrNull { candidate ->
                if (candidate.endpointTransferType == "iso" && exactBytes != null) {
                    10_000 - kotlin.math.abs(candidate.endpointMaxPacketSize - exactBytes)
                } else if (candidate.endpointTransferType == "bulk") {
                    100
                } else {
                    0
                }
            }
    }
}

data class UsbPlaybackCandidate(
    val deviceName: String,
    val deviceLabel: String,
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val endpointAddress: Int,
    val endpointTransferType: String,
    val endpointMaxPacketSize: Int,
    val sampleRate: Int,
    val channelCount: Int,
    val bitDepth: Int,
    val expectedBytesPerInterval: Int?,
    val framesPerInterval: Double?,
) {
    fun toDisplayString(): String {
        val intervalSuffix = expectedBytesPerInterval?.let { " interval<=${it}B" } ?: ""
        return "$deviceLabel -> if=$interfaceNumber alt=$alternateSetting ep=0x${endpointAddress.toString(16)} ${endpointTransferType} ${sampleRate}Hz ${channelCount}ch ${bitDepth}-bit maxPacket=$endpointMaxPacketSize$intervalSuffix"
    }
}
