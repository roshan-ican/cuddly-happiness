package com.example.cameraremotecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun buildsChannelMappingWritePacket() {
        val packet = buildSiyiPacket(0x4A, byteArrayOf(16, 0, 9))

        assertEquals(listOf<Byte>(0x55.toByte(), 0x66.toByte(), 1, 3, 0, 0, 0, 0x4A, 16, 0, 9), packet.take(11))
    }

    @Test
    fun roverWsMapUsesAllRequestedControlsAndPreservesFlDescriptor() {
        val existing = List(RC_CHANNEL_COUNT) { SiyiRcMapping(2, it) }
        val map = roverWsHardwareMap(existing)

        assertEquals(SiyiRcMapping(2, 4), map[4])
        assertEquals(SiyiRcMapping(5, 2), map[7])
        assertEquals(SiyiRcMapping(0, 11), map[8])
        assertEquals(SiyiRcMapping(1, 1), map[12])
        assertEquals(SiyiRcMapping(1, 3), map[13])
        assertEquals(SiyiRcMapping(0, 8), map[14])
        assertEquals(SiyiRcMapping(0, 9), map[15])
        assertFalse(map.contains(SiyiRcMapping(0, 10)))
    }
}
