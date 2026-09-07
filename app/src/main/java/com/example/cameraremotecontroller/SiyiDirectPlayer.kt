package com.example.cameraremotecontroller

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class SiyiDirectEndpoint(
        val host: String,
        val port: Int,
        val streamType: Int = 1,
)

internal fun parseSiyiDirectUrl(value: String): SiyiDirectEndpoint? {
    val match = SIYI_URL.matchEntire(value.trim()) ?: return null
    val host = match.groupValues[1]
    val port = match.groupValues[2].toIntOrNull() ?: SIYI_VIDEO_PORT
    val streamType = match.groupValues[3].toIntOrNull() ?: 1
    if (port !in 1..65535 || streamType !in 0..2) return null
    return SiyiDirectEndpoint(host, port, streamType)
}

internal object SiyiDirectProtocol {
    private val magic = byteArrayOf(0x55, 0x66, 0xAA.toByte(), 0xBB.toByte())

    fun request(sequence: Int, command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val result = ByteArray(PROTOCOL_OVERHEAD + payload.size)
        magic.copyInto(result)
        result[4] = 0x01 // Request an acknowledgement.
        writeLe32(result, 5, payload.size)
        writeLe16(result, 9, sequence)
        result[11] = command.toByte()
        writeLe32(result, 12, crc32Mpeg2(result, 12))
        payload.copyInto(result, 16)
        writeLe32(result, result.size - 4, crc32Mpeg2(result, result.size - 4))
        return result
    }

    fun read(input: InputStream): Frame {
        findMagic(input)
        val frameHeader = ByteArray(9)
        magic.copyInto(frameHeader)
        input.readFully(frameHeader, 4, 5)
        val payloadSize = readLe32(frameHeader, 5)
        require(payloadSize in 0..MAX_PAYLOAD_SIZE) { "Invalid SIYI payload size: $payloadSize" }

        val frame = ByteArray(PROTOCOL_OVERHEAD + payloadSize)
        frameHeader.copyInto(frame)
        input.readFully(frame, 9, frame.size - 9)
        require(crc32Mpeg2(frame, 12) == readLe32(frame, 12)) { "Invalid SIYI header CRC" }
        require(crc32Mpeg2(frame, frame.size - 4) == readLe32(frame, frame.size - 4)) {
            "Invalid SIYI frame CRC"
        }

        return Frame(
                command = frame[11].toInt() and 0xFF,
                payload = frame.copyOfRange(16, 16 + payloadSize),
        )
    }

    data class Frame(val command: Int, val payload: ByteArray)

    private fun findMagic(input: InputStream) {
        var matched = 0
        while (matched < magic.size) {
            val value = input.read()
            if (value < 0) throw EOFException("SIYI stream ended")
            val expected = magic[matched].toInt() and 0xFF
            matched =
                    when {
                        value == expected -> matched + 1
                        value == (magic[0].toInt() and 0xFF) -> 1
                        else -> 0
                    }
        }
    }

    private fun InputStream.readFully(target: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val count = read(target, offset + read, length - read)
            if (count < 0) throw EOFException("SIYI stream ended")
            read += count
        }
    }

    private fun readLe32(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeLe16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeLe32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    // The private SIYI video protocol uses CRC-32/MPEG-2: polynomial 0x04C11DB7,
    // initial value 0, no reflection and no final xor.
    private fun crc32Mpeg2(bytes: ByteArray, length: Int): Int {
        var crc = 0
        repeat(length) { index ->
            crc = crc xor ((bytes[index].toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc
    }

    private const val PROTOCOL_OVERHEAD = 20
    private const val MAX_PAYLOAD_SIZE = 8 * 1024 * 1024
}

internal class SiyiDirectPlayer(
        private val endpoint: SiyiDirectEndpoint,
        private val surface: Surface,
        private val listener: Listener,
) {
    interface Listener {
        fun onPlaying()

        fun onDecoderLatency(latencyMs: Long)

        fun onDisconnected(message: String)
    }

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "siyi-direct-${endpoint.host}").apply { isDaemon = true }
    }

    @Volatile private var socket: Socket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute(::runReconnectLoop)
    }

    fun stop() {
        running.set(false)
        try {
            socket?.close()
        } catch (_: Exception) {}
        executor.shutdownNow()
    }

    private fun runReconnectLoop() {
        while (running.get()) {
            try {
                playSession()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                if (running.get()) {
                    Log.w(TAG, "Direct stream disconnected", e)
                    listener.onDisconnected(e.message ?: "Direct stream disconnected")
                }
            }

            if (running.get()) {
                try {
                    Thread.sleep(RECONNECT_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    private fun playSession() {
        val freshSocket = Socket()
        socket = freshSocket
        freshSocket.tcpNoDelay = true
        freshSocket.receiveBufferSize = SOCKET_BUFFER_SIZE
        freshSocket.soTimeout = READ_TIMEOUT_MS
        freshSocket.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS)

        val input = BufferedInputStream(freshSocket.getInputStream(), SOCKET_BUFFER_SIZE)
        val output = BufferedOutputStream(freshSocket.getOutputStream())
        var sequence = 0

        fun send(command: Int, payload: ByteArray = byteArrayOf()) {
            output.write(SiyiDirectProtocol.request(sequence++, command, payload))
            output.flush()
        }

        // This mirrors the minimum startup performed by SIYI FPV: identify the
        // camera, enable its selected stream, and request codec/dimension data.
        send(COMMAND_CAMERA_VERSION)
        send(COMMAND_VIDEO, byteArrayOf(1))
        send(COMMAND_CAMERA_INFO, byteArrayOf(endpoint.streamType.toByte()))

        val assembler = AccessUnitAssembler()
        var decoder: LatestFrameDecoder? = null
        var lastInfoRequest = System.currentTimeMillis()
        var lastKeepAlive = System.currentTimeMillis()

        try {
            while (running.get()) {
                val keepAliveNow = System.currentTimeMillis()
                if (keepAliveNow - lastKeepAlive >= KEEPALIVE_INTERVAL_MS) {
                    send(COMMAND_CAMERA_VERSION)
                    lastKeepAlive = keepAliveNow
                }

                val frame =
                        try {
                            SiyiDirectProtocol.read(input)
                        } catch (_: SocketTimeoutException) {
                            val now = System.currentTimeMillis()
                            if (decoder == null && now - lastInfoRequest >= INFO_RETRY_MS) {
                                send(
                                        COMMAND_CAMERA_INFO,
                                        byteArrayOf(endpoint.streamType.toByte()),
                                )
                                lastInfoRequest = now
                            }
                            continue
                        }

                when (frame.command) {
                    COMMAND_CAMERA_INFO -> {
                        val info = VideoInfo.from(frame.payload, endpoint.streamType) ?: continue
                        if (decoder?.matches(info) != true) {
                            decoder?.close()
                            decoder = LatestFrameDecoder(info, surface, listener)
                        }
                    }
                    COMMAND_VIDEO -> {
                        val accessUnit = assembler.accept(frame.payload) ?: continue
                        val active = decoder ?: continue
                        try {
                            active.offer(accessUnit)
                        } catch (error: IllegalStateException) {
                            Log.w(TAG, "Decoder reset", error)
                            active.close()
                            decoder = null
                            send(COMMAND_CAMERA_INFO, byteArrayOf(endpoint.streamType.toByte()))
                            lastInfoRequest = System.currentTimeMillis()
                        }
                    }
                }
            }
        } finally {
            decoder?.close()
            try {
                freshSocket.close()
            } catch (_: Exception) {}
            if (socket === freshSocket) socket = null
        }
    }

    private data class VideoInfo(
            val mime: String,
            val width: Int,
            val height: Int,
            val frameRate: Int,
    ) {
        companion object {
            fun from(payload: ByteArray, expectedStreamType: Int): VideoInfo? {
                if (payload.size < 9 || (payload[0].toInt() and 0xFF) != expectedStreamType) {
                    return null
                }
                val mime =
                        when (payload[1].toInt() and 0xFF) {
                            1 -> MediaFormat.MIMETYPE_VIDEO_AVC
                            2 -> MediaFormat.MIMETYPE_VIDEO_HEVC
                            else -> return null
                        }
                val width = readLe16(payload, 2)
                val height = readLe16(payload, 4)
                val frameRate = readLe16(payload, 6).coerceAtLeast(1)
                if (width <= 0 || height <= 0) return null
                return VideoInfo(mime, width, height, frameRate)
            }

            private fun readLe16(bytes: ByteArray, offset: Int): Int =
                    (bytes[offset].toInt() and 0xFF) or
                            ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        }
    }

    private class AccessUnitAssembler {
        private var sequence = -1L
        private var expectedPackets = 0
        private var nextPacket = 0
        private var broken = false
        private var bytes = ByteArrayOutputStream()

        fun accept(payload: ByteArray): ByteArray? {
            if (payload.size < VIDEO_PREFIX_SIZE) return null
            val frameSequence = readLe32Unsigned(payload, 0)
            val packetCount = payload[4].toInt() and 0xFF
            val packetIndex = payload[5].toInt() and 0xFF
            if (packetCount <= 0 || packetIndex >= packetCount) return null

            if (packetCount == 1) return payload.copyOfRange(VIDEO_PREFIX_SIZE, payload.size)

            if (frameSequence != sequence || packetIndex == 0) {
                sequence = frameSequence
                expectedPackets = packetCount
                nextPacket = 0
                broken = false
                bytes = ByteArrayOutputStream(payload.size * packetCount)
            }

            if (packetCount != expectedPackets || packetIndex != nextPacket) broken = true
            nextPacket = packetIndex + 1
            if (!broken) bytes.write(payload, VIDEO_PREFIX_SIZE, payload.size - VIDEO_PREFIX_SIZE)

            if (packetIndex + 1 != packetCount) return null
            return if (broken) null else bytes.toByteArray()
        }

        private fun readLe32Unsigned(bytes: ByteArray, offset: Int): Long =
                ((bytes[offset].toLong() and 0xFF) or
                                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                                ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                                ((bytes[offset + 3].toLong() and 0xFF) shl 24)) and
                        0xFFFF_FFFFL
    }

    private class LatestFrameDecoder(
            private val info: VideoInfo,
            surface: Surface,
            private val listener: Listener,
    ) : AutoCloseable {
        private val codec: MediaCodec
        private val bufferInfo = MediaCodec.BufferInfo()
        private var reportedPlaying = false

        init {
            val format = MediaFormat.createVideoFormat(info.mime, info.width, info.height)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_ACCESS_UNIT_SIZE)
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            val operatingRate = info.frameRate.takeIf { it in 1..120 } ?: DEFAULT_FRAME_RATE
            format.setFloat(MediaFormat.KEY_OPERATING_RATE, operatingRate.toFloat())

            val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
                    ?: error("No hardware decoder for ${info.mime}")
            val codecInfo =
                    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first {
                        it.name == codecName
                    }
            if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                            codecInfo
                                    .getCapabilitiesForType(info.mime)
                                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            ) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }

            codec = MediaCodec.createByCodecName(codecName)
            codec.configure(format, surface, null, 0)
            codec.start()
            Log.i(TAG, "Direct decoder $codecName for $info")
        }

        fun matches(candidate: VideoInfo): Boolean = candidate == info

        fun offer(accessUnit: ByteArray) {
            if (accessUnit.isEmpty()) return
            val inputIndex = codec.dequeueInputBuffer(0)
            if (inputIndex >= 0) {
                val input = codec.getInputBuffer(inputIndex)
                if (input != null && accessUnit.size <= input.capacity()) {
                    input.clear()
                    input.put(accessUnit)
                    codec.queueInputBuffer(
                            inputIndex,
                            0,
                            accessUnit.size,
                            System.nanoTime() / 1_000,
                            0,
                    )
                } else {
                    codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                }
            }

            var newestIndex = -1
            while (true) {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                if (outputIndex < 0) break
                if (newestIndex >= 0) codec.releaseOutputBuffer(newestIndex, false)
                newestIndex = outputIndex
            }

            if (newestIndex >= 0) {
                codec.releaseOutputBuffer(newestIndex, true)
                listener.onDecoderLatency(
                        (System.nanoTime() / 1_000 - bufferInfo.presentationTimeUs)
                                .coerceAtLeast(0) / 1_000
                )
                if (!reportedPlaying) {
                    reportedPlaying = true
                    listener.onPlaying()
                }
            }
        }

        override fun close() {
            try {
                codec.stop()
            } catch (_: Exception) {}
            codec.release()
        }
    }

    companion object {
        private const val TAG = "SiyiDirect"
        private const val SIYI_VIDEO_PORT = 37256
        private val SIYI_URL =
                Regex("""siyi://([^/:?#]+)(?::(\d+))?(?:\?stream=([0-2]))?""", RegexOption.IGNORE_CASE)
        private const val CONNECT_TIMEOUT_MS = 1_000
        private const val READ_TIMEOUT_MS = 500
        private const val INFO_RETRY_MS = 1_000L
        private const val KEEPALIVE_INTERVAL_MS = 3_000L
        private const val RECONNECT_DELAY_MS = 750L
        private const val SOCKET_BUFFER_SIZE = 64 * 1024
        private const val MAX_ACCESS_UNIT_SIZE = 4 * 1024 * 1024
        private const val VIDEO_PREFIX_SIZE = 6
        private const val DEFAULT_FRAME_RATE = 30
        private const val COMMAND_CAMERA_INFO = 0x83
        private const val COMMAND_VIDEO = 0x90
        private const val COMMAND_CAMERA_VERSION = 0x94
    }
}

private const val SIYI_VIDEO_PORT = 37256
private val SIYI_URL =
        Regex("""siyi://([^/:?#]+)(?::(\d+))?(?:\?stream=([0-2]))?""", RegexOption.IGNORE_CASE)
