package com.example.cameraremotecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStreamConfigurationTest {
    @Test
    fun `only baseline AVC can bypass frame reordering`() {
        val start = byteArrayOf(0, 0, 0, 1, 0x67)
        assertTrue(hasBaselineAvcSps(start + byteArrayOf(66, 0, 31)))
        assertTrue(hasBaselineAvcSps(start + byteArrayOf(66, 0xc0.toByte(), 31)))
        assertFalse(hasBaselineAvcSps(start + byteArrayOf(77, 0, 31)))
        assertFalse(hasBaselineAvcSps(start + byteArrayOf(100, 0, 31)))
        assertFalse(hasBaselineAvcSps(byteArrayOf(0, 0, 0, 1, 0x68, 66, 0, 31)))
        assertFalse(hasBaselineAvcSps(start + byteArrayOf(66)))
        assertFalse(hasBaselineAvcSps(byteArrayOf()))
    }

    @Test
    fun `finds SPS after other parameter sets`() {
        val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xca.toByte(), 0x8f.toByte(), 0x20)
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 66, 0, 31)
        assertTrue(hasBaselineAvcSps(pps + sps))
    }

    @Test
    fun `simultaneous cameras get distinct bound RTP and RTCP pairs`() {
        val first = bindFreshRtpPair(65536)
        try {
            val second = bindFreshRtpPair(65536)
            try {
                for (pair in listOf(first, second)) {
                    assertEquals(0, pair.first.localPort % 2)
                    assertEquals(pair.first.localPort + 1, pair.second.localPort)
                    assertFalse(pair.first.isClosed)
                    assertFalse(pair.second.isClosed)
                }
                assertNotEquals(first.first.localPort, second.first.localPort)
            } finally {
                second.first.close()
                second.second.close()
            }
        } finally {
            first.first.close()
            first.second.close()
        }
    }
}

private class SpsBuilder {
    private val bits = StringBuilder()

    fun u(count: Int, value: Int) = apply {
        for (shift in count - 1 downTo 0) bits.append((value shr shift) and 1)
    }

    fun ue(value: Int) = apply {
        val code = value + 1
        val length = 32 - Integer.numberOfLeadingZeros(code)
        repeat(length - 1) { bits.append('0') }
        u(length, code)
    }

    fun profileTierLevel(maxSubLayersMinus1: Int) = apply {
        repeat(96) { bits.append('0') }
        repeat(maxSubLayersMinus1) { u(2, 0) }
        if (maxSubLayersMinus1 > 0) repeat(2 * (8 - maxSubLayersMinus1)) { bits.append('0') }
    }

    fun build(): ByteArray {
        while (bits.length % 8 != 0) bits.append('0')
        val body = ByteArray(bits.length / 8) { index ->
            bits.substring(index * 8, index * 8 + 8).toInt(2).toByte()
        }
        return byteArrayOf(0, 0, 0, 1) + body
    }
}

private fun hevcSps(reorderPerLayer: List<Int>, subLayerOrderingInfo: Boolean = true): ByteArray {
    val maxSubLayersMinus1 = reorderPerLayer.size - 1
    val builder = SpsBuilder()
            .u(16, 0x4201)
            .u(4, 0)
            .u(3, maxSubLayersMinus1)
            .u(1, 1)
            .profileTierLevel(maxSubLayersMinus1)
            .ue(0)
            .ue(1)
            .ue(1920)
            .ue(1080)
            .u(1, 0)
            .ue(0)
            .ue(0)
            .ue(4)
            .u(1, if (subLayerOrderingInfo) 1 else 0)
    val emitted = if (subLayerOrderingInfo) reorderPerLayer else listOf(reorderPerLayer.last())
    emitted.forEach { builder.ue(1).ue(it).ue(0) }
    return builder.build()
}

private fun escapeRbsp(payload: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream(payload.size)
    var zeros = 0
    for (byte in payload) {
        if (zeros >= 2 && (byte.toInt() and 0xff) <= 3) {
            out.write(3)
            zeros = 0
        }
        out.write(byte.toInt())
        zeros = if (byte == 0.toByte()) zeros + 1 else 0
    }
    return out.toByteArray()
}

class HevcReorderTest {
    @Test
    fun `reads max_num_reorder_pics from a single layer SPS`() {
        assertEquals(0, hevcMaxNumReorderPics(hevcSps(listOf(0))))
        assertEquals(2, hevcMaxNumReorderPics(hevcSps(listOf(2))))
    }

    @Test
    fun `uses the highest temporal layer`() {
        assertEquals(3, hevcMaxNumReorderPics(hevcSps(listOf(0, 1, 3))))
    }

    @Test
    fun `handles a SPS that omits per sub-layer ordering info`() {
        assertEquals(1, hevcMaxNumReorderPics(hevcSps(listOf(0, 1), subLayerOrderingInfo = false)))
    }

    @Test
    fun `survives emulation prevention bytes`() {
        val sps = hevcSps(listOf(2))
        val escaped = sps.copyOfRange(0, 4) + escapeRbsp(sps.copyOfRange(4, sps.size))
        assertNotEquals(sps.size, escaped.size)
        assertEquals(2, hevcMaxNumReorderPics(escaped))
    }

    @Test
    fun `returns null when no HEVC SPS is present`() {
        assertNull(hevcMaxNumReorderPics(byteArrayOf()))
        assertNull(hevcMaxNumReorderPics(byteArrayOf(0, 0, 0, 1, 0x40, 1, 12, 1)))
        assertNull(hevcMaxNumReorderPics(byteArrayOf(0, 0, 0, 1, 0x42, 1, 1)))
    }
}
