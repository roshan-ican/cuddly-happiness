package com.example.cameraremotecontroller

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiyiDirectProtocolTest {
    @Test
    fun `camera version request matches vendor wire format`() {
        val request = SiyiDirectProtocol.request(sequence = 0, command = 0x94)

        assertArrayEquals(
                byteArrayOf(
                        0x55,
                        0x66,
                        0xAA.toByte(),
                        0xBB.toByte(),
                        0x01,
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                        0x94.toByte(),
                        0x81.toByte(),
                        0x3A,
                        0x6F,
                        0x6B,
                        0xC4.toByte(),
                        0x4B,
                        0xFE.toByte(),
                        0x99.toByte(),
                ),
                request,
        )
    }

    @Test
    fun `reader recovers a framed payload after unrelated bytes`() {
        val encoded =
                SiyiDirectProtocol.request(
                        sequence = 7,
                        command = 0x83,
                        payload = byteArrayOf(1, 2, 3),
                )
        val input = ByteArrayInputStream(byteArrayOf(9, 8, 7) + encoded)

        val frame = SiyiDirectProtocol.read(input)

        assertEquals(0x83, frame.command)
        assertArrayEquals(byteArrayOf(1, 2, 3), frame.payload)
    }

    @Test
    fun `direct endpoint accepts camera and stream selection`() {
        assertEquals(
                SiyiDirectEndpoint("192.168.144.25", 37256, 1),
                parseSiyiDirectUrl("siyi://192.168.144.25:37256"),
        )
        assertEquals(
                SiyiDirectEndpoint("192.168.144.26", 37255, 2),
                parseSiyiDirectUrl("siyi://192.168.144.26:37255?stream=2"),
        )
        assertNull(parseSiyiDirectUrl("udp://192.168.144.25:37256"))
    }
}
