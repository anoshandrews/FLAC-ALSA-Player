package dev.anosh.musicplayer.audio

class PcmBufferController(
    capacityBytes: Int,
) : AutoCloseable {
    private val nativeBuffer = NativePcmRingBuffer(capacityBytes)

    fun enqueuePcm(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        return nativeBuffer.write(data, offset, length)
    }

    fun dequeuePcm(length: Int): ByteArray {
        return nativeBuffer.read(length)
    }

    fun availableBytes(): Int {
        return nativeBuffer.availableBytes()
    }

    fun capacityBytes(): Int {
        return nativeBuffer.capacityBytes()
    }

    fun clear() {
        nativeBuffer.clear()
    }

    override fun close() {
        nativeBuffer.close()
    }
}
