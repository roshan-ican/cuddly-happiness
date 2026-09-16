package com.example.cameraremotecontroller

import android.os.Handler
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal data class SiyiRcChannelState(
        val connected: Boolean = false,
        val channels: List<Int> = List(CHANNEL_COUNT) { 0 },
        val packetCount: Long = 0,
        val error: String? = null,
)

internal class SiyiRcChannelClient(
        private val onState: (SiyiRcChannelState) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker =
                Thread(
                                {
                                    var packetCount = 0L
                                    try {
                                        val remote =
                                                InetSocketAddress(
                                                        InetAddress.getByName(SIYI_RC_HOST),
                                                        SIYI_RC_PORT,
                                                )
                                        val udp = DatagramSocket(0)
                                        socket = udp
                                        udp.soTimeout = RECEIVE_TIMEOUT_MS
                                        val enable = buildSiyiRcChannelRequest(5)
                                        repeat(3) { udp.send(DatagramPacket(enable, enable.size, remote)) }

                                        val buffer = ByteArray(256)
                                        while (running.get()) {
                                            try {
                                                val packet = DatagramPacket(buffer, buffer.size)
                                                udp.receive(packet)
                                                val channels = parseSiyiRcChannels(packet.data, packet.length) ?: continue
                                                packetCount += 1
                                                publish(
                                                        SiyiRcChannelState(
                                                                connected = true,
                                                                channels = channels,
                                                                packetCount = packetCount,
                                                        )
                                                )
                                            } catch (_: SocketTimeoutException) {
                                                publish(
                                                        SiyiRcChannelState(
                                                                packetCount = packetCount,
                                                                error = "No channel data received",
                                                        )
                                                )
                                                repeat(3) {
                                                    udp.send(
                                                            DatagramPacket(
                                                                    enable,
                                                                    enable.size,
                                                                    remote,
                                                            )
                                                    )
                                                }
                                            }
                                        }
                                    } catch (error: Exception) {
                                        if (running.get()) {
                                            publish(
                                                    SiyiRcChannelState(
                                                            packetCount = packetCount,
                                                            error = error.message ?: "Unable to connect",
                                                    )
                                            )
                                        }
                                    } finally {
                                        socket?.close()
                                        socket = null
                                    }
                                },
                                "siyi-rc-channels",
                        )
                        .apply {
                            isDaemon = true
                            start()
                        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val udp = socket
        if (udp != null && !udp.isClosed) {
            runCatching {
                val remote = InetSocketAddress(InetAddress.getByName(SIYI_RC_HOST), SIYI_RC_PORT)
                val disable = buildSiyiRcChannelRequest(0)
                repeat(3) { udp.send(DatagramPacket(disable, disable.size, remote)) }
            }
        }
        udp?.close()
        worker?.interrupt()
        worker = null
    }

    private fun publish(state: SiyiRcChannelState) {
        mainHandler.post { onState(state) }
    }

    private companion object {
        const val SIYI_RC_HOST = "192.168.144.20"
        const val SIYI_RC_PORT = 19856
        const val RECEIVE_TIMEOUT_MS = 3000
    }
}

internal fun buildSiyiRcChannelRequest(frequency: Int): ByteArray {
    require(frequency in 0..7)
    val packet = byteArrayOf(0x55, 0x66, 0x01, 0x01, 0x00, 0x00, 0x00, 0x42, frequency.toByte(), 0x00, 0x00)
    var crc = 0
    repeat(9) { index ->
        crc = crc xor ((packet[index].toInt() and 0xFF) shl 8)
        repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
        crc = crc and 0xFFFF
    }
    packet[9] = (crc and 0xFF).toByte()
    packet[10] = (crc shr 8).toByte()
    return packet
}

internal fun parseSiyiRcChannels(data: ByteArray, length: Int): List<Int>? {
    if (length < 42 || data[0] != 0x55.toByte() || data[1] != 0x66.toByte() || data[7] != 0x42.toByte()) {
        return null
    }
    val payloadLength = (data[3].toInt() and 0xFF) or ((data[4].toInt() and 0xFF) shl 8)
    if (payloadLength != CHANNEL_COUNT * 2 || length < 8 + payloadLength + 2) return null
    return List(CHANNEL_COUNT) { index ->
        val offset = 8 + index * 2
        val raw = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
        raw.toShort().toInt()
    }
}

internal data class SiyiRcMapping(val type: Int, val entityId: Int)

internal data class SiyiRcMapApplyResult(
    val before: List<SiyiRcMapping>,
    val after: List<SiyiRcMapping>?,
    val error: String? = null,
) {
    val verified: Boolean get() = error == null && after != null
}

internal fun roverWsHardwareMap(existing: List<SiyiRcMapping>): List<SiyiRcMapping> {
    require(existing.size == RC_CHANNEL_COUNT)
    val fl = existing[4]
    return listOf(
        SiyiRcMapping(0, 0), SiyiRcMapping(0, 1), SiyiRcMapping(0, 2), SiyiRcMapping(0, 3),
        fl,
        SiyiRcMapping(5, 0), SiyiRcMapping(5, 1), SiyiRcMapping(5, 2), SiyiRcMapping(0, 11),
        SiyiRcMapping(5, 4), SiyiRcMapping(5, 5), SiyiRcMapping(1, 0), SiyiRcMapping(1, 1),
        SiyiRcMapping(1, 3), SiyiRcMapping(0, 8), SiyiRcMapping(0, 9),
    )
}

internal fun buildSiyiPacket(command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
    val packet = ByteArray(10 + payload.size)
    packet[0] = 0x55
    packet[1] = 0x66
    packet[2] = 0x01
    packet[3] = (payload.size and 0xFF).toByte()
    packet[4] = ((payload.size shr 8) and 0xFF).toByte()
    packet[7] = command.toByte()
    payload.copyInto(packet, destinationOffset = 8)
    val crc = siyiCrc16(packet, packet.size - 2)
    packet[packet.size - 2] = (crc and 0xFF).toByte()
    packet[packet.size - 1] = (crc shr 8).toByte()
    return packet
}

internal fun parseSiyiMappingResponse(data: ByteArray, length: Int): List<SiyiRcMapping>? {
    val payload = parseSiyiResponse(data, length, 0x48) ?: return null
    return parseSiyiMappingPayload(payload)
}

private fun parseSiyiMappingPayload(payload: ByteArray): List<SiyiRcMapping>? {
    if (payload.size != RC_CHANNEL_COUNT * 2) return null
    return payload.asList().chunked(2).map { SiyiRcMapping(it[0].toInt() and 0xFF, it[1].toInt() and 0xFF) }
}

private fun parseSiyiResponse(data: ByteArray, length: Int, command: Int): ByteArray? {
    if (length < 10 || data[0] != 0x55.toByte() || data[1] != 0x66.toByte() || (data[7].toInt() and 0xFF) != command) return null
    val payloadLength = (data[3].toInt() and 0xFF) or ((data[4].toInt() and 0xFF) shl 8)
    if (length != payloadLength + 10 || siyiCrc16(data, length - 2) != ((data[length - 2].toInt() and 0xFF) or ((data[length - 1].toInt() and 0xFF) shl 8))) return null
    return data.copyOfRange(8, 8 + payloadLength)
}

private fun siyiCrc16(data: ByteArray, count: Int): Int {
    var crc = 0
    repeat(count) { index ->
        crc = crc xor ((data[index].toInt() and 0xFF) shl 8)
        repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
        crc = crc and 0xFFFF
    }
    return crc
}

internal class SiyiRcMappingClient {
    fun applyRoverWsProfile(): SiyiRcMapApplyResult {
        DatagramSocket().use { socket ->
            socket.soTimeout = 800
            val before = readAll(socket) ?: return SiyiRcMapApplyResult(emptyList(), after = null, error = "Unable to read controller mapping")
            val expected = roverWsHardwareMap(before)
            expected.forEachIndexed { index, mapping ->
                val channel = index + 1
                val acknowledgement = exchange(socket, 0x4A, byteArrayOf(channel.toByte(), mapping.type.toByte(), mapping.entityId.toByte()))
                if (acknowledgement == null || acknowledgement.size != 2 || acknowledgement[0].toInt() and 0xFF != channel || acknowledgement[1] != 1.toByte()) {
                    return SiyiRcMapApplyResult(before, after = null, error = "Controller rejected CH$channel")
                }
            }
            val after = readAll(socket)
            return if (after == expected) SiyiRcMapApplyResult(before, after) else SiyiRcMapApplyResult(before, after, "Controller read-back does not match the Rover / WS profile")
        }
    }

    private fun readAll(socket: DatagramSocket): List<SiyiRcMapping>? = exchange(socket, 0x48)?.let(::parseSiyiMappingPayload)

    private fun exchange(socket: DatagramSocket, command: Int, payload: ByteArray = byteArrayOf()): ByteArray? {
        val remote = InetSocketAddress(InetAddress.getByName(SIYI_RC_HOST), SIYI_RC_PORT)
        val request = buildSiyiPacket(command, payload)
        repeat(3) {
            socket.send(DatagramPacket(request, request.size, remote))
            repeat(2) {
                val data = ByteArray(256)
                try {
                    val response = DatagramPacket(data, data.size)
                    socket.receive(response)
                    parseSiyiResponse(data, response.length, command)?.let { return it }
                } catch (_: SocketTimeoutException) {
                    return@repeat
                }
            }
        }
        return null
    }

    private companion object {
        const val SIYI_RC_HOST = "192.168.144.20"
        const val SIYI_RC_PORT = 19856
    }
}

private const val CHANNEL_COUNT = 16
