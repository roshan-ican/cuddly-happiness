package com.example.cameraremotecontroller

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtpH264DepacketizerTest {
    private fun rtpPacket(
            sequence: Int,
            timestamp: Long,
            marker: Boolean,
            payload: ByteArray,
    ): ByteArray {
        val packet = ByteArray(12 + payload.size)
        packet[0] = 0x80.toByte()
        packet[1] = (if (marker) 0x80 or 96 else 96).toByte()
        packet[2] = ((sequence shr 8) and 0xFF).toByte()
        packet[3] = (sequence and 0xFF).toByte()
        packet[4] = ((timestamp shr 24) and 0xFF).toByte()
        packet[5] = ((timestamp shr 16) and 0xFF).toByte()
        packet[6] = ((timestamp shr 8) and 0xFF).toByte()
        packet[7] = (timestamp and 0xFF).toByte()
        payload.copyInto(packet, 12)
        return packet
    }

    private fun withStartCode(vararg nals: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        nals.forEach { nal ->
            RtpH265Depacketizer.START_CODE.forEach { out.add(it) }
            nal.forEach { out.add(it) }
        }
        return out.toByteArray()
    }

    @Test
    fun `single nal unit passes through`() {
        val depacketizer = RtpH264Depacketizer()
        val nal = byteArrayOf(0x65, 0x11, 0x22)

        val result = depacketizer.accept(rtpPacket(1, 900, true, nal), 12 + nal.size)

        assertArrayEquals(withStartCode(nal), result)
    }

    @Test
    fun `fu-a fragments rebuild the original nal header`() {
        val depacketizer = RtpH264Depacketizer()
        val body = ByteArray(16) { (it + 1).toByte() }

        val start = byteArrayOf(0x7C, (0x80 or 5).toByte()) + body.copyOfRange(0, 8)
        val end = byteArrayOf(0x7C, (0x40 or 5).toByte()) + body.copyOfRange(8, 16)

        assertNull(depacketizer.accept(rtpPacket(1, 900, false, start), 12 + start.size))
        val result = depacketizer.accept(rtpPacket(2, 900, true, end), 12 + end.size)

        assertArrayEquals(withStartCode(byteArrayOf(0x65) + body), result)
    }

    @Test
    fun `stap-a splits into separate nal units`() {
        val depacketizer = RtpH264Depacketizer()
        val sps = byteArrayOf(0x67, 0x42, 0x00)
        val pps = byteArrayOf(0x68, 0xCE.toByte())

        val payload = byteArrayOf(0x78) +
                byteArrayOf(0, sps.size.toByte()) + sps +
                byteArrayOf(0, pps.size.toByte()) + pps

        val result = depacketizer.accept(rtpPacket(1, 900, true, payload), 12 + payload.size)

        assertArrayEquals(withStartCode(sps, pps), result)
    }

    @Test
    fun `packet loss discards the damaged access unit`() {
        val depacketizer = RtpH264Depacketizer()
        val nal = byteArrayOf(0x41, 0x01)

        assertNull(depacketizer.accept(rtpPacket(1, 900, false, nal), 12 + nal.size))
        assertNull(depacketizer.accept(rtpPacket(3, 900, true, nal), 12 + nal.size))
    }

    @Test
    fun `recovers on the next access unit`() {
        val depacketizer = RtpH264Depacketizer()
        val damaged = byteArrayOf(0x41, 0x01)
        val clean = byteArrayOf(0x41, 0x09)

        depacketizer.accept(rtpPacket(1, 900, false, damaged), 12 + damaged.size)
        depacketizer.accept(rtpPacket(3, 900, true, damaged), 12 + damaged.size)
        val result = depacketizer.accept(rtpPacket(4, 1800, true, clean), 12 + clean.size)

        assertArrayEquals(withStartCode(clean), result)
    }
}
