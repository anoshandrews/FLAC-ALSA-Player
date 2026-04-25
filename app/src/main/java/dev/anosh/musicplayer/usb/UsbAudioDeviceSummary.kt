package dev.anosh.musicplayer.usb

data class UsbAudioDeviceSummary(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String?,
    val productName: String?,
    val audioInterfaces: List<UsbAudioInterfaceSummary>,
) {
    fun toDisplayString(): String = buildString {
        append(productName ?: manufacturerName ?: deviceName.substringAfterLast('/'))
        appendLine()
        appendLine("VID:PID ${vendorId.toString(16).padStart(4, '0')}:${productId.toString(16).padStart(4, '0')}")
        if (!manufacturerName.isNullOrBlank()) {
            appendLine("Manufacturer: $manufacturerName")
        }
        if (!productName.isNullOrBlank()) {
            appendLine("Product: $productName")
        }
        audioInterfaces.forEachIndexed { index, summary ->
            appendLine()
            appendLine(
                "Interface ${index + 1}: #${summary.interfaceNumber} alt=${summary.alternateSetting} ${summary.subclassLabel}",
            )
            summary.channelCount?.let { appendLine("Channels: $it") }
            summary.bitDepth?.let { appendLine("Bit depth: $it") }
            summary.sampleRates?.takeIf { it.isNotEmpty() }?.let { rates ->
                appendLine("Sample rates: ${rates.joinToString { "${it}Hz" }}")
            }
            summary.formatType?.let { appendLine("Format: $it") }
            summary.terminalLink?.let { appendLine("Terminal link: $it") }
            if (summary.endpoints.isNotEmpty()) {
                appendLine("Endpoints: ${summary.endpoints.joinToString { it.toDisplayString() }}")
            }
            if (summary.notes.isNotEmpty()) {
                appendLine("Notes: ${summary.notes.joinToString()}")
            }
        }
    }.trim()
}

data class UsbAudioInterfaceSummary(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val subclassLabel: String,
    val terminalLink: Int?,
    val formatType: String?,
    val channelCount: Int?,
    val bitDepth: Int?,
    val sampleRates: List<Int>,
    val endpoints: List<UsbAudioEndpointSummary>,
    val notes: List<String>,
)

data class UsbAudioEndpointSummary(
    val address: Int,
    val transferType: String,
    val direction: String,
    val maxPacketSize: Int,
) {
    fun toDisplayString(): String {
        return "0x${address.toString(16)} $transferType $direction max=$maxPacketSize"
    }
}
