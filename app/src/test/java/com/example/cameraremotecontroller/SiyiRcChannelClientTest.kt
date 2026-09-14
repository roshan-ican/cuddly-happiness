package com.example.cameraremotecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiyiRcChannelClientTest {
    @Test
    fun buildsDocumentedFourHertzRequest() {
        val expected = byteArrayOf(0x55, 0x66, 0x01, 0x01, 0x00, 0x00, 0x00, 0x42, 0x02, 0xB5.toByte(), 0xC0.toByte())

        assertEquals(expected.toList(), buildSiyiRcChannelRequest(2).toList())
    }

    @Test
    fun parsesSixteenLittleEndianChannels() {
        val values = listOf(1500, 1500, 1050, 1950, 1000, 1500, 1500, 1950, 1950, 1500, 1500, 1050, 1050, 1050, 1050, 1050)
        val packet = ByteArray(42)
        packet[0] = 0x55
        packet[1] = 0x66
        packet[3] = 32
        packet[7] = 0x42
        values.forEachIndexed { index, value ->
            packet[8 + index * 2] = (value and 0xFF).toByte()
            packet[9 + index * 2] = (value shr 8).toByte()
        }

        assertEquals(values, parseSiyiRcChannels(packet, packet.size))
    }

    @Test
    fun ignoresAcknowledgementWithoutChannelPayload() {
        val acknowledgement = byteArrayOf(0x55, 0x66, 0x02, 0x01, 0x00, 0x01, 0x00, 0x42, 0x01, 0xE0.toByte(), 0x5E)

        assertNull(parseSiyiRcChannels(acknowledgement, acknowledgement.size))
    }
}
