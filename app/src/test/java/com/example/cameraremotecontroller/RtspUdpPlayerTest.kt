package com.example.cameraremotecontroller

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtspUdpPlayerTest {
    @Test
    fun `parses host port and path`() {
        val endpoint = parseRtspLowLatencyUrl("rtspll://192.168.144.25:8554/main.264")

        assertEquals(RtspEndpoint("192.168.144.25", 8554, "/main.264"), endpoint)
        assertEquals("rtsp://192.168.144.25:8554/main.264", endpoint?.url)
    }

    @Test
    fun `defaults to the standard rtsp port`() {
        assertEquals(554, parseRtspLowLatencyUrl("rtspll://10.0.0.5/stream")?.port)
    }

    @Test
    fun `rejects other schemes`() {
        assertNull(parseRtspLowLatencyUrl("rtsp://192.168.144.25:8554/main.264"))
        assertNull(parseRtspLowLatencyUrl("siyi://192.168.144.25:37256"))
        assertNull(parseRtspLowLatencyUrl(""))
    }

    @Test
    fun `rejects an out of range port`() {
        assertNull(parseRtspLowLatencyUrl("rtspll://192.168.144.25:99999/main.264"))
    }

    @Test
    fun `reads payload type control and parameter sets from sdp`() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C)
        val sps = byteArrayOf(0x42, 0x01, 0x01)
        val pps = byteArrayOf(0x44, 0x01, 0xC0.toByte())
        val encoder = Base64.getEncoder()

        val sdp = buildString {
            appendLine("v=0")
            appendLine("o=- 0 0 IN IP4 192.168.144.25")
            appendLine("s=SIYI")
            appendLine("m=video 0 RTP/AVP 96")
            appendLine("a=control:track1")
            appendLine("a=rtpmap:96 H265/90000")
            append("a=fmtp:96 sprop-vps=${encoder.encodeToString(vps)};")
            append("sprop-sps=${encoder.encodeToString(sps)};")
            appendLine("sprop-pps=${encoder.encodeToString(pps)}")
        }

        val description = SdpParser.parseVideo(sdp)!!

        assertEquals(96, description.payloadType)
        assertEquals("track1", description.control)
        assertArrayEquals(
                RtpH265Depacketizer.START_CODE + vps +
                        RtpH265Depacketizer.START_CODE + sps +
                        RtpH265Depacketizer.START_CODE + pps,
                description.codecSpecificData,
        )
    }

    @Test
    fun `falls back to 720p when dimensions are absent`() {
        val description = SdpParser.parseVideo("m=video 0 RTP/AVP 96\na=control:track1")!!

        assertEquals(1280, description.width)
        assertEquals(720, description.height)
    }

    @Test
    fun `reads explicit dimensions`() {
        val sdp = "m=video 0 RTP/AVP 96\na=x-dimensions:1920,1080"

        val description = SdpParser.parseVideo(sdp)!!

        assertEquals(1920, description.width)
        assertEquals(1080, description.height)
    }

    @Test
    fun `ignores attributes belonging to other media sections`() {
        val sdp = buildString {
            appendLine("m=audio 0 RTP/AVP 8")
            appendLine("a=control:audiotrack")
            appendLine("m=video 0 RTP/AVP 97")
            appendLine("a=control:videotrack")
        }

        val description = SdpParser.parseVideo(sdp)!!

        assertEquals(97, description.payloadType)
        assertEquals("videotrack", description.control)
    }

    @Test
    fun `returns null when there is no video track`() {
        assertNull(SdpParser.parseVideo("m=audio 0 RTP/AVP 8\na=control:audiotrack"))
    }
}
