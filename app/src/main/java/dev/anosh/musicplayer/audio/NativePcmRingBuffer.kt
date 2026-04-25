package dev.anosh.musicplayer.audio

class NativePcmRingBuffer(
    capacityBytes: Int,
) : AutoCloseable {
    private var nativeHandle: Long = nativeCreate(capacityBytes)

    init {
        require(nativeHandle != 0L) { "Failed to create PCM ring buffer" }
    }

    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        checkOpen()
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "Invalid write bounds" }
        return nativeWrite(nativeHandle, data, offset, length)
    }

    fun read(length: Int): ByteArray {
        checkOpen()
        require(length >= 0) { "Length must be non-negative" }
        return nativeRead(nativeHandle, length)
    }

    fun availableBytes(): Int {
        checkOpen()
        return nativeAvailable(nativeHandle)
    }

    fun capacityBytes(): Int {
        checkOpen()
        return nativeCapacity(nativeHandle)
    }

    fun clear() {
        checkOpen()
        nativeClear(nativeHandle)
    }

    override fun close() {
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle)
            nativeHandle = 0L
        }
    }

    private fun checkOpen() {
        check(nativeHandle != 0L) { "PCM ring buffer is closed" }
    }

    private external fun nativeCreate(capacityBytes: Int): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeWrite(handle: Long, data: ByteArray, offset: Int, length: Int): Int
    private external fun nativeRead(handle: Long, length: Int): ByteArray
    private external fun nativeAvailable(handle: Long): Int
    private external fun nativeCapacity(handle: Long): Int
    private external fun nativeClear(handle: Long)

    companion object {
        init {
            System.loadLibrary("music_player_native")
        }
    }
}
