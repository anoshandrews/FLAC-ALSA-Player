package dev.anosh.musicplayer.root

import android.net.LocalSocket
import android.net.LocalSocketAddress
import dev.anosh.musicplayer.audio.DecodedAudioFile
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RootAudioDaemonClient : Closeable {
    private var socket: LocalSocket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    fun connect(socketPath: String = SOCKET_PATH) {
        val localSocket = LocalSocket()
        localSocket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
        socket = localSocket
        input = DataInputStream(localSocket.inputStream)
        output = DataOutputStream(localSocket.outputStream)
    }

    fun ping(): Boolean {
        val response = send(Command.PING, ByteArray(0))
        return response.status == Status.OK
    }

    fun listCards(): String {
        val response = send(Command.LIST_CARDS, ByteArray(0))
        return response.payload.decodeToString()
    }

    fun openStream(
        card: Int,
        device: Int,
        audioFile: DecodedAudioFile,
        periodSize: Int = 1024,
        periodCount: Int = 4,
    ): DaemonResponse {
        val payload = ByteBuffer.allocate(7 * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(card)
            .putInt(device)
            .putInt(audioFile.sampleRate)
            .putInt(audioFile.channelCount)
            .putInt(periodSize)
            .putInt(periodCount)
            .putInt(audioFile.bitsPerSample.toPcmFormatCode())
            .array()
        return send(Command.OPEN_STREAM, payload)
    }

    fun writePcm(data: ByteArray): DaemonResponse {
        return send(Command.WRITE_PCM, data)
    }

    fun stop(): DaemonResponse {
        return send(Command.STOP, ByteArray(0))
    }

    override fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    private fun send(command: Command, payload: ByteArray): DaemonResponse {
        val stream = output ?: error("Root audio daemon socket is not connected")
        val responseStream = input ?: error("Root audio daemon socket is not connected")
        val header = ByteBuffer.allocate(16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(MAGIC)
            .putInt(PROTOCOL_VERSION)
            .putInt(command.code)
            .putInt(payload.size)
            .array()
        stream.write(header)
        if (payload.isNotEmpty()) {
            stream.write(payload)
        }
        stream.flush()

        val status = responseStream.readIntLe()
        val detailCode = responseStream.readIntLe()
        val responsePayload = if (detailCode > 0 && command == Command.LIST_CARDS) {
            ByteArray(detailCode).also { responseStream.readFully(it) }
        } else {
            ByteArray(0)
        }
        return DaemonResponse(Status.fromCode(status), detailCode, responsePayload)
    }

    private fun Int.toPcmFormatCode(): Int = when (this) {
        16 -> 1
        24 -> 2
        32 -> 3
        else -> 0
    }

    private fun DataInputStream.readIntLe(): Int {
        val buffer = ByteArray(4)
        readFully(buffer)
        return ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).int
    }

    companion object {
        const val SOCKET_PATH: String = "/data/local/tmp/music_player_root_audio.sock"
        private const val PROTOCOL_VERSION = 1
        private const val MAGIC = 0x4D505241
    }
}

data class DaemonResponse(
    val status: Status,
    val detailCode: Int,
    val payload: ByteArray,
)

enum class Status(val code: Int) {
    OK(0),
    ERROR(1),
    UNSUPPORTED(2);

    companion object {
        fun fromCode(code: Int): Status = entries.firstOrNull { it.code == code } ?: ERROR
    }
}

enum class Command(val code: Int) {
    PING(1),
    OPEN_STREAM(2),
    WRITE_PCM(3),
    STOP(4),
    LIST_CARDS(5),
}
