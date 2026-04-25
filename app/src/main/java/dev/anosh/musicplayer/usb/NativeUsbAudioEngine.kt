package dev.anosh.musicplayer.usb

class NativeUsbAudioEngine(
    fileDescriptor: Int,
    endpointAddress: Int,
    endpointTransferType: String,
    packetSize: Int,
) : AutoCloseable {
    private var nativeHandle: Long = nativeCreate(
        fileDescriptor,
        endpointAddress,
        endpointTransferType == "iso",
        packetSize,
    )

    init {
        require(nativeHandle != 0L) { "Failed to create native USB audio engine" }
    }

    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        check(nativeHandle != 0L) { "USB audio engine is closed" }
        return nativeWrite(nativeHandle, data, offset, length)
    }

    override fun close() {
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun nativeCreate(
        fileDescriptor: Int,
        endpointAddress: Int,
        isIsochronous: Boolean,
        packetSize: Int,
    ): Long

    private external fun nativeRelease(handle: Long)
    private external fun nativeWrite(handle: Long, data: ByteArray, offset: Int, length: Int): Int

    companion object {
        init {
            System.loadLibrary("music_player_native")
        }
    }
}
