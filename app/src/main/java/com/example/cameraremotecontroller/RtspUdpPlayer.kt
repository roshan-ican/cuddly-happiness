package com.example.cameraremotecontroller

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class RtspEndpoint(
        val host: String,
        val port: Int,
        val path: String,
) {
    val url: String
        get() = "rtsp://$host:$port$path"
}

internal fun parseRtspLowLatencyUrl(value: String): RtspEndpoint? {
    val match = RTSP_LL_URL.matchEntire(value.trim()) ?: return null
    val host = match.groupValues[1]
    val port = match.groupValues[2].toIntOrNull() ?: RTSP_DEFAULT_PORT
    val path = match.groupValues[3].ifEmpty { "/" }
    if (port !in 1..65535) return null
    return RtspEndpoint(host, port, path)
}

internal data class RtspVideoDescription(
        val payloadType: Int,
        val control: String,
        val mime: String,
        val codecSpecificData: ByteArray,
        val width: Int,
        val height: Int,
) {
    override fun equals(other: Any?): Boolean =
            other is RtspVideoDescription &&
                    payloadType == other.payloadType &&
                    control == other.control &&
                    mime == other.mime &&
                    width == other.width &&
                    height == other.height &&
                    codecSpecificData.contentEquals(other.codecSpecificData)

    override fun hashCode(): Int =
            ((((payloadType * 31 + control.hashCode()) * 31 + mime.hashCode()) * 31 + width) * 31 +
                    height) * 31 + codecSpecificData.contentHashCode()
}

internal object SdpParser {
    fun parseVideo(sdp: String): RtspVideoDescription? {
        var inVideo = false
        var payloadType = -1
        var control = ""
        var width = 0
        var height = 0
        var mime = MediaFormat.MIMETYPE_VIDEO_HEVC
        val parameterSets = ByteArrayOutputStream()

        sdp.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("m=") -> {
                    inVideo = line.startsWith("m=video")
                    if (inVideo) {
                        payloadType = line.split(' ').lastOrNull()?.toIntOrNull() ?: -1
                    }
                }
                !inVideo -> Unit
                line.startsWith("a=control:") -> control = line.removePrefix("a=control:").trim()
                line.startsWith("a=rtpmap:") -> {
                    val encoding = line.substringAfter(' ', "").substringBefore('/').trim()
                    if (encoding.equals("H264", ignoreCase = true)) {
                        mime = MediaFormat.MIMETYPE_VIDEO_AVC
                    }
                }
                line.startsWith("a=x-dimensions:") -> {
                    val parts = line.removePrefix("a=x-dimensions:").split(',')
                    width = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                    height = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                }
                line.startsWith("a=fmtp:") -> {
                    PARAMETER_SET_KEYS.forEach { key ->
                        parameterSets(line, key).forEach { nal ->
                            parameterSets.write(RtpH265Depacketizer.START_CODE)
                            parameterSets.write(nal)
                        }
                    }
                }
            }
        }

        if (payloadType < 0) return null
        return RtspVideoDescription(
                payloadType = payloadType,
                control = control,
                mime = mime,
                codecSpecificData = parameterSets.toByteArray(),
                width = if (width > 0) width else DEFAULT_WIDTH,
                height = if (height > 0) height else DEFAULT_HEIGHT,
        )
    }

    private fun parameterSets(line: String, key: String): List<ByteArray> {
        val index = line.indexOf("$key=", ignoreCase = true)
        if (index < 0) return emptyList()
        val value = line.substring(index + key.length + 1).substringBefore(';').trim()
        return value.split(',').mapNotNull { encoded ->
            val trimmed = encoded.trim()
            if (trimmed.isEmpty()) {
                null
            } else {
                try {
                    Base64.getMimeDecoder().decode(trimmed)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        }
    }

    private val PARAMETER_SET_KEYS =
            listOf("sprop-vps", "sprop-sps", "sprop-pps", "sprop-parameter-sets")
    private const val DEFAULT_WIDTH = 1280
    private const val DEFAULT_HEIGHT = 720
}

internal class RtspUdpPlayer(
        private val endpoint: RtspEndpoint,
        private val surface: Surface,
        private val listener: Listener,
        private val cropToSurface: Boolean = false,
) {
    interface Listener {
        fun onPlaying()

        fun onDecoderLatency(latencyMs: Long)

        fun onDisconnected(message: String)
    }

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rtsp-udp-${endpoint.host}").apply { isDaemon = true }
    }

    @Volatile private var control: Socket? = null

    @Volatile private var rtp: DatagramSocket? = null

    @Volatile private var rtcp: DatagramSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute(::runReconnectLoop)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        closeSockets()
        executor.shutdownNow()
    }

    private fun closeSockets() {
        try {
            control?.close()
        } catch (_: Exception) {}
        rtp?.close()
        rtcp?.close()
        control = null
        rtp = null
        rtcp = null
    }

    private fun runReconnectLoop() {
        while (running.get()) {
            try {
                playSession()
            } catch (error: Exception) {
                if (!running.get()) return
                Log.w(TAG, "RTSP session ended", error)
                listener.onDisconnected(error.message ?: "RTSP stream ended")
            } finally {
                closeSockets()
            }

            if (!running.get()) return
            try {
                Thread.sleep(RECONNECT_DELAY_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun playSession() {
        val (rtpSocket, rtcpSocket) = bindRtpPair()
        rtp = rtpSocket
        rtcp = rtcpSocket

        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS)
        socket.soTimeout = CONTROL_TIMEOUT_MS
        control = socket

        val session = RtspSession(socket, endpoint.url)
        session.request("OPTIONS", endpoint.url)

        val describe = session.request("DESCRIBE", endpoint.url, mapOf("Accept" to "application/sdp"))
        val description = SdpParser.parseVideo(describe.body)
                ?: error("No video track in SDP")

        val setupUrl = resolveControlUrl(description.control)
        val setup = session.request(
                "SETUP",
                setupUrl,
                mapOf(
                        "Transport" to
                                "RTP/AVP;unicast;client_port=${rtpSocket.localPort}-${rtcpSocket.localPort}",
                ),
        )
        session.sessionId = setup.header("Session")?.substringBefore(';')?.trim()
        val keepAliveMs = setup.header("Session")
                ?.substringAfter("timeout=", "")
                ?.trim()
                ?.toIntOrNull()
                ?.times(500L)
                ?: DEFAULT_KEEPALIVE_MS

        session.request("PLAY", endpoint.url, mapOf("Range" to "npt=0.000-"))

        rtpSocket.soTimeout = READ_TIMEOUT_MS
        receiveVideo(rtpSocket, session, description, keepAliveMs)
    }

    private fun bindRtpPair(): Pair<DatagramSocket, DatagramSocket> =
            bindFreshRtpPair(RTP_RECEIVE_BUFFER)

    private fun resolveControlUrl(control: String): String =
            when {
                control.isEmpty() || control == "*" -> endpoint.url
                control.startsWith("rtsp://", ignoreCase = true) -> control
                endpoint.url.endsWith("/") -> endpoint.url + control.removePrefix("/")
                else -> endpoint.url + "/" + control.removePrefix("/")
            }

    private fun receiveVideo(
            rtpSocket: DatagramSocket,
            session: RtspSession,
            description: RtspVideoDescription,
            keepAliveMs: Long,
    ) {
        val depacketizer: RtpVideoDepacketizer =
                if (description.mime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                    RtpH264Depacketizer()
                } else {
                    RtpH265Depacketizer()
                }
        val decoder = LowLatencyVideoDecoder(description, surface, listener, cropToSurface)
        val buffer = ByteArray(MAX_DATAGRAM_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)
        var lastKeepAlive = System.currentTimeMillis()

        try {
            while (running.get()) {
                val now = System.currentTimeMillis()
                if (now - lastKeepAlive >= keepAliveMs) {
                    session.request("OPTIONS", endpoint.url)
                    lastKeepAlive = now
                }

                packet.setData(buffer, 0, buffer.size)
                try {
                    rtpSocket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }

                if (RtpPacket.payloadType(buffer) != description.payloadType) continue

                val accessUnit = depacketizer.accept(buffer, packet.length) ?: continue
                try {
                    decoder.offer(accessUnit)
                } catch (error: IllegalStateException) {
                    Log.w(TAG, "Decoder reset", error)
                    decoder.recreate()
                    depacketizer.reset()
                }
            }
        } finally {
            decoder.close()
            try {
                session.request("TEARDOWN", endpoint.url)
            } catch (_: Exception) {}
        }
    }

    private class RtspSession(socket: Socket, private val baseUrl: String) {
        private val output = socket.getOutputStream()
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        private var sequence = 0

        var sessionId: String? = null

        fun request(
                method: String,
                url: String,
                headers: Map<String, String> = emptyMap(),
        ): Response {
            val builder = StringBuilder()
            builder.append("$method $url RTSP/1.0\r\n")
            builder.append("CSeq: ${++sequence}\r\n")
            builder.append("User-Agent: camera-remote-controller\r\n")
            sessionId?.let { builder.append("Session: $it\r\n") }
            headers.forEach { (key, value) -> builder.append("$key: $value\r\n") }
            builder.append("\r\n")

            output.write(builder.toString().toByteArray(Charsets.ISO_8859_1))
            output.flush()
            return readResponse()
        }

        private fun readResponse(): Response {
            val status = reader.readLine() ?: throw java.io.EOFException("RTSP connection closed")
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
            val headers = HashMap<String, String>()

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                            line.substring(separator + 1).trim()
                }
            }

            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (length > 0) {
                val chars = CharArray(length)
                var read = 0
                while (read < length) {
                    val count = reader.read(chars, read, length - read)
                    if (count < 0) break
                    read += count
                }
                String(chars, 0, read)
            } else {
                ""
            }

            if (code !in 200..299) error("RTSP $baseUrl failed: $status")
            return Response(headers, body)
        }

        class Response(private val headers: Map<String, String>, val body: String) {
            fun header(name: String): String? = headers[name.lowercase()]
        }
    }

    private class LowLatencyVideoDecoder(
            private val description: RtspVideoDescription,
            private val surface: Surface,
            private val listener: Listener,
            private val cropToSurface: Boolean,
    ) : AutoCloseable {
        private val bufferInfo = MediaCodec.BufferInfo()
        private val renderTimingHandler = Handler(renderCallbackThread.looper)
        @Volatile private var codec: MediaCodec = createCodec()
        private var reportedPlaying = false

        private var statsWindowStart = System.currentTimeMillis()
        private var statsFrames = 0
        private var statsLatencySum = 0L
        private var statsLatencyMin = Long.MAX_VALUE
        private var statsLatencyMax = 0L
        private var droppedFrames = 0
        private var starvedInputs = 0

        private fun createCodec(tryVendorLowLatency: Boolean = true): MediaCodec {
            val format = MediaFormat.createVideoFormat(
                    description.mime,
                    description.width,
                    description.height,
            )
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_ACCESS_UNIT_SIZE)
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (description.codecSpecificData.isNotEmpty()) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(description.codecSpecificData))
            }

            val codecInfo = selectDecoder()
            val codecName = codecInfo.name
            val lowLatencySupported = advertisesLowLatency(codecInfo)
            val decoder = MediaCodec.createByCodecName(codecName)
            val vendorParameters = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { decoder.supportedVendorParameters }.getOrDefault(emptyList())
            } else emptyList()
            // Older Qualcomm OMX builds implement this extension but return an empty
            // vendor-parameter list. Retry without it if this decoder rejects it.
            val vendorLowLatency = tryVendorLowLatency && (
                    KEY_QTI_LOW_LATENCY in vendorParameters ||
                            codecName.startsWith("OMX.qcom.", ignoreCase = true))
            if (vendorLowLatency) format.setInteger(KEY_QTI_LOW_LATENCY, 1)
            // Baseline AVC has no B-frames, so decode order is also display order.
            // Do not apply this to Main/High AVC or HEVC, which may need reordering.
            val decodeOrder = vendorLowLatency && description.mime == MediaFormat.MIMETYPE_VIDEO_AVC &&
                    hasBaselineAvcSps(description.codecSpecificData)
            if (decodeOrder) format.setInteger(KEY_QTI_DECODE_ORDER, 1)
            try {
                return decoder.apply {
                    configure(format, surface, null, 0)
                    start()
                    var renderedCount = 0
                    var renderedLatencySum = 0L
                    var renderedLatencyMax = 0L
                    setOnFrameRenderedListener({ callbackCodec, ptsUs, renderedNs ->
                        if (callbackCodec !== codec) return@setOnFrameRenderedListener
                        if (ptsUs > 0 && renderedNs >= ptsUs * 1_000) {
                            val elapsedMs = (renderedNs / 1_000 - ptsUs) / 1_000
                            renderedCount++
                            renderedLatencySum += elapsedMs
                            renderedLatencyMax = maxOf(renderedLatencyMax, elapsedMs)
                            if (renderedCount >= 90) {
                                Log.i(TAG, "${description.mime} submit-to-surface avg=${renderedLatencySum / renderedCount}ms max=${renderedLatencyMax}ms frames=$renderedCount")
                                renderedCount = 0
                                renderedLatencySum = 0
                                renderedLatencyMax = 0
                            }
                        }
                    }, renderTimingHandler)
                    if (cropToSurface) setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                    Log.i(
                            TAG,
                            "RTSP decoder $codecName hardware=${codecInfo.isHardwareAccelerated()} " +
                                    "lowLatencyFeature=$lowLatencySupported sdk=${Build.VERSION.SDK_INT} " +
                                    "vendorLowLatency=$vendorLowLatency decodeOrder=$decodeOrder csd=${description.codecSpecificData.size}B $description",
                    )
                }
            } catch (error: Exception) {
                runCatching { decoder.release() }
                if (!vendorLowLatency) throw error
                Log.w(TAG, "$codecName rejected vendor low latency; retrying standard configuration", error)
                return createCodec(tryVendorLowLatency = false)
            }
        }

        fun recreate() {
            try {
                codec.release()
            } catch (_: Exception) {}
            codec = createCodec()
        }

        fun offer(accessUnit: ByteArray) {
            if (accessUnit.isEmpty()) return

            // Drain in step with arriving video. A free-running output thread can
            // submit frames faster than SurfaceFlinger presents them and build a
            // native display queue even when decoder latency itself looks low.
            renderNewestFrame()

            val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
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
            } else {
                starvedInputs++
            }

            renderNewestFrame()
        }

        private fun renderNewestFrame() {
            var newestIndex = -1
            var newestPresentationTimeUs = 0L
            while (true) {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (cropToSurface) codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                    continue
                }
                if (outputIndex < 0) break
                if (newestIndex >= 0) {
                    codec.releaseOutputBuffer(newestIndex, false)
                    droppedFrames++
                }
                newestIndex = outputIndex
                newestPresentationTimeUs = bufferInfo.presentationTimeUs
            }

            if (newestIndex < 0) return

            codec.releaseOutputBuffer(newestIndex, true)
            val latencyMs =
                    (System.nanoTime() / 1_000 - newestPresentationTimeUs)
                            .coerceAtLeast(0) / 1_000
            listener.onDecoderLatency(latencyMs)
            recordStats(latencyMs)
            if (!reportedPlaying) {
                reportedPlaying = true
                listener.onPlaying()
            }
        }

        private fun recordStats(latencyMs: Long) {
            statsFrames++
            statsLatencySum += latencyMs
            statsLatencyMin = minOf(statsLatencyMin, latencyMs)
            statsLatencyMax = maxOf(statsLatencyMax, latencyMs)

            val now = System.currentTimeMillis()
            val elapsed = now - statsWindowStart
            if (elapsed < STATS_INTERVAL_MS) return

            Log.i(
                    TAG,
                    "${description.mime} decode frames=$statsFrames fps=${statsFrames * 1000 / elapsed} " +
                            "latency min=${statsLatencyMin}ms avg=${statsLatencySum / statsFrames}ms " +
                            "max=${statsLatencyMax}ms dropped=$droppedFrames starved=$starvedInputs",
            )

            statsWindowStart = now
            statsFrames = 0
            statsLatencySum = 0
            statsLatencyMin = Long.MAX_VALUE
            statsLatencyMax = 0
            droppedFrames = 0
            starvedInputs = 0
        }

        private fun advertisesLowLatency(info: MediaCodecInfo): Boolean =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        runCatching {
                            info
                                    .getCapabilitiesForType(description.mime)
                                    .isFeatureSupported(
                                            MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency,
                                    )
                        }
                                .getOrDefault(false)

        private fun selectDecoder(): MediaCodecInfo {
            val candidates =
                    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                        !info.isEncoder &&
                                info.supportedTypes.any {
                                    it.equals(description.mime, ignoreCase = true)
                                }
                    }

            candidates.forEach { info ->
                Log.i(
                        TAG,
                        "candidate ${info.name} hardware=${info.isHardwareAccelerated()} " +
                                "lowLatency=${advertisesLowLatency(info)}",
                )
            }

            return candidates.firstOrNull {
                it.isHardwareAccelerated() && advertisesLowLatency(it)
            }
                    ?: candidates.firstOrNull { it.isHardwareAccelerated() }
                    ?: candidates.firstOrNull()
                    ?: error("No decoder for ${description.mime}")
        }

        override fun close() {
            try {
                codec.stop()
            } catch (_: Exception) {}
            codec.release()
        }
    }

    private companion object {
        val renderCallbackThread: HandlerThread by lazy {
            HandlerThread("rtsp-render-events").apply { start() }
        }
        const val TAG = "RtspUdp"
        const val CONNECT_TIMEOUT_MS = 2_000
        const val CONTROL_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 500
        const val RECONNECT_DELAY_MS = 750L
        const val DEFAULT_KEEPALIVE_MS = 25_000L
        const val MAX_DATAGRAM_SIZE = 65_536
        const val MAX_ACCESS_UNIT_SIZE = 4 * 1024 * 1024
        const val RTP_RECEIVE_BUFFER = 512 * 1024
        const val STATS_INTERVAL_MS = 3_000L
        const val INPUT_TIMEOUT_US = 4_000L
        const val KEY_QTI_LOW_LATENCY = "vendor.qti-ext-dec-low-latency.enable"
        const val KEY_QTI_DECODE_ORDER = "vendor.qti-ext-dec-picture-order.enable"
    }
}

private const val RTSP_DEFAULT_PORT = 554

private val RTSP_LL_URL = Regex("^rtspll://([^/:\\s]+)(?::(\\d+))?(/.*)?$", RegexOption.IGNORE_CASE)
