package dev.anosh.musicplayer.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface

object UsbAudioDescriptorParser {
    private const val USB_DT_INTERFACE = 0x04
    private const val CS_INTERFACE = 0x24
    private const val CS_ENDPOINT = 0x25
    private const val AS_GENERAL = 0x01
    private const val FORMAT_TYPE = 0x02
    private const val FORMAT_TYPE_I = 0x01
    private const val AUDIO_SUBCLASS_CONTROL = 0x01
    private const val AUDIO_SUBCLASS_STREAMING = 0x02

    fun parse(
        device: UsbDevice,
        rawDescriptors: ByteArray = ByteArray(0),
    ): UsbAudioDeviceSummary {
        val parsedFormats = parseRawAudioStreamingDescriptors(rawDescriptors)
        val audioInterfaces = buildList {
            repeat(device.interfaceCount) { index ->
                val usbInterface = device.getInterface(index)
                if (usbInterface.interfaceClass != UsbConstants.USB_CLASS_AUDIO) {
                    return@repeat
                }
                val interfaceKey = InterfaceKey(
                    number = usbInterface.id,
                    alternateSetting = usbInterface.alternateSetting,
                )
                val descriptorInfo = parsedFormats[interfaceKey]
                add(
                    UsbAudioInterfaceSummary(
                        interfaceNumber = usbInterface.id,
                        alternateSetting = usbInterface.alternateSetting,
                        subclassLabel = subclassLabel(usbInterface.interfaceSubclass),
                        terminalLink = descriptorInfo?.terminalLink,
                        formatType = descriptorInfo?.formatType,
                        channelCount = descriptorInfo?.channelCount,
                        bitDepth = descriptorInfo?.bitDepth,
                        sampleRates = descriptorInfo?.sampleRates.orEmpty(),
                        endpoints = endpointStrings(usbInterface),
                        notes = descriptorInfo?.notes.orEmpty(),
                    ),
                )
            }
        }

        return UsbAudioDeviceSummary(
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            manufacturerName = device.manufacturerName,
            productName = device.productName,
            audioInterfaces = audioInterfaces,
        )
    }

    private fun endpointStrings(usbInterface: UsbInterface): List<UsbAudioEndpointSummary> = buildList {
        repeat(usbInterface.endpointCount) { endpointIndex ->
            val endpoint = usbInterface.getEndpoint(endpointIndex)
            val type = when (endpoint.type) {
                UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
                UsbConstants.USB_ENDPOINT_XFER_ISOC -> "iso"
                UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                else -> "unknown"
            }
            val direction = when (endpoint.direction) {
                UsbConstants.USB_DIR_IN -> "in"
                UsbConstants.USB_DIR_OUT -> "out"
                else -> "unknown"
            }
            add(
                UsbAudioEndpointSummary(
                    address = endpoint.address,
                    transferType = type,
                    direction = direction,
                    maxPacketSize = endpoint.maxPacketSize,
                ),
            )
        }
    }

    private fun subclassLabel(subclass: Int): String = when (subclass) {
        AUDIO_SUBCLASS_CONTROL -> "AudioControl"
        AUDIO_SUBCLASS_STREAMING -> "AudioStreaming"
        else -> "AudioSubclass($subclass)"
    }

    private fun parseRawAudioStreamingDescriptors(raw: ByteArray): Map<InterfaceKey, ParsedStreamingInterface> {
        if (raw.isEmpty()) {
            return emptyMap()
        }

        val result = linkedMapOf<InterfaceKey, ParsedStreamingInterface>()
        var cursor = 0
        var currentInterface: InterfaceKey? = null

        while (cursor + 1 < raw.size) {
            val length = raw[cursor].toUByte().toInt()
            if (length <= 0 || cursor + length > raw.size) {
                break
            }
            val descriptorType = raw[cursor + 1].toUByte().toInt()

            when (descriptorType) {
                USB_DT_INTERFACE -> {
                    if (length >= 9) {
                        val number = raw[cursor + 2].toUByte().toInt()
                        val alternateSetting = raw[cursor + 3].toUByte().toInt()
                        val interfaceClass = raw[cursor + 5].toUByte().toInt()
                        val interfaceSubclass = raw[cursor + 6].toUByte().toInt()
                        currentInterface =
                            if (interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                                interfaceSubclass == AUDIO_SUBCLASS_STREAMING
                            ) {
                                InterfaceKey(number, alternateSetting)
                            } else {
                                null
                            }
                    }
                }

                CS_INTERFACE -> {
                    currentInterface?.let { key ->
                        val target = result.getOrPut(key) { ParsedStreamingInterface() }
                        val subtype = raw[cursor + 2].toUByte().toInt()
                        when (subtype) {
                            AS_GENERAL -> parseAsGeneral(raw, cursor, length, target)
                            FORMAT_TYPE -> parseFormatType(raw, cursor, length, target)
                        }
                    }
                }

                CS_ENDPOINT -> {
                    currentInterface?.let { key ->
                        result.getOrPut(key) { ParsedStreamingInterface() }
                            .notes
                            .add("Class-specific endpoint present")
                    }
                }
            }

            cursor += length
        }

        return result
    }

    private fun parseAsGeneral(
        raw: ByteArray,
        offset: Int,
        length: Int,
        target: ParsedStreamingInterface,
    ) {
        if (length >= 4) {
            target.terminalLink = raw[offset + 3].toUByte().toInt()
        }
        if (length >= 8) {
            val formatTag = readUnsignedShort(raw, offset + 6)
            if (formatTag != 0) {
                target.notes.add("Format tag 0x${formatTag.toString(16)}")
            }
        }
        if (length in 5..6) {
            target.notes.add("Likely UAC2/UAC3 AS general descriptor")
        }
    }

    private fun parseFormatType(
        raw: ByteArray,
        offset: Int,
        length: Int,
        target: ParsedStreamingInterface,
    ) {
        if (length < 4) {
            return
        }
        val formatTypeCode = raw[offset + 3].toUByte().toInt()
        target.formatType = when (formatTypeCode) {
            FORMAT_TYPE_I -> "Type I"
            else -> "Type $formatTypeCode"
        }

        if (formatTypeCode != FORMAT_TYPE_I) {
            target.notes.add("Unsupported non-Type-I format descriptor")
            return
        }

        when {
            length >= 8 -> {
                val channelCount = raw[offset + 4].toUByte().toInt()
                val subslotSize = raw[offset + 5].toUByte().toInt()
                val bitResolution = raw[offset + 6].toUByte().toInt()
                val sampleFreqType = raw[offset + 7].toUByte().toInt()
                target.channelCount = channelCount
                target.bitDepth = bitResolution

                if (sampleFreqType == 0 && length >= 14) {
                    val minRate = read24(raw, offset + 8)
                    val maxRate = read24(raw, offset + 11)
                    target.notes.add("Continuous sample range ${minRate}Hz-${maxRate}Hz")
                } else {
                    var sampleCursor = offset + 8
                    repeat(sampleFreqType) {
                        if (sampleCursor + 2 < offset + length) {
                            target.sampleRates.add(read24(raw, sampleCursor))
                            sampleCursor += 3
                        }
                    }
                }

                if (subslotSize > 0 && bitResolution == 0) {
                    target.bitDepth = subslotSize * 8
                }
            }

            length >= 6 -> {
                target.bitDepth = raw[offset + 5].toUByte().toInt()
                target.notes.add("Short format descriptor; sample rates unavailable")
            }

            else -> {
                target.notes.add("Descriptor too short to parse format capabilities")
            }
        }
    }

    private fun readUnsignedShort(raw: ByteArray, offset: Int): Int {
        if (offset + 1 >= raw.size) {
            return 0
        }
        val low = raw[offset].toUByte().toInt()
        val high = raw[offset + 1].toUByte().toInt()
        return low or (high shl 8)
    }

    private fun read24(raw: ByteArray, offset: Int): Int {
        if (offset + 2 >= raw.size) {
            return 0
        }
        val b0 = raw[offset].toUByte().toInt()
        val b1 = raw[offset + 1].toUByte().toInt()
        val b2 = raw[offset + 2].toUByte().toInt()
        return b0 or (b1 shl 8) or (b2 shl 16)
    }

    private data class InterfaceKey(
        val number: Int,
        val alternateSetting: Int,
    )

    private class ParsedStreamingInterface {
        var terminalLink: Int? = null
        var formatType: String? = null
        var channelCount: Int? = null
        var bitDepth: Int? = null
        val sampleRates = mutableListOf<Int>()
        val notes = mutableListOf<String>()
    }
}
