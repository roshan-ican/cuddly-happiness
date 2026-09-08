package com.example.cameraremotecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
