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

private const val CHANNEL_COUNT = 16
