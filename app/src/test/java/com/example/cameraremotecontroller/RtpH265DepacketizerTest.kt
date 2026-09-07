package com.example.cameraremotecontroller

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtpH265DepacketizerTest {
    private fun rtpPacket(
            sequence: Int,
            timestamp: Long,
            marker: Boolean,
            payload: ByteArray,
    ): ByteArray {
        val packet = ByteArray(12 + payload.size)
        packet[0] = 0x80.toByte()
        packet[1] = (if (marker) 0x80 or PAYLOAD_TYPE else PAYLOAD_TYPE).toByte()
        packet[2] = ((sequence shr 8) and 0xFF).toByte()
        packet[3] = (sequence and 0xFF).toByte()
        packet[4] = ((timestamp shr 24) and 0xFF).toByte()
        packet[5] = ((timestamp shr 16) and 0xFF).toByte()
        packet[6] = ((timestamp shr 8) and 0xFF).toByte()
        packet[7] = (timestamp and 0xFF).toByte()
        payload.copyInto(packet, 12)
        return packet
    }

    private fun nalHeader(type: Int): ByteArray =
            byteArrayOf((type shl 1).toByte(), 0x01)

    private fun withStartCode(vararg nals: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        nals.forEach { nal ->
            RtpH265Depacketizer.START_CODE.forEach { out.add(it) }
            nal.forEach { out.add(it) }
        }
        return out.toByteArray()
    }

    @Test
    fun `single nal unit in one packet yields one access unit`() {
        val depacketizer = RtpH265Depacketizer()
        val nal = nalHeader(1) + byteArrayOf(0xAA.toByte(), 0xBB.toByte())

        val result = depacketizer.accept(rtpPacket(1, 1000, true, nal), 12 + nal.size)

        assertArrayEquals(withStartCode(nal), result)
    }

    @Test
    fun `fragmentation unit is reassembled with reconstructed nal header`() {
        val depacketizer = RtpH265Depacketizer()
        val body = ByteArray(20) { it.toByte() }

        val startPayload = byteArrayOf(
                (49 shl 1).toByte(),
                0x01,
                (0x80 or 19).toByte(),
        ) + body.copyOfRange(0, 10)
        val endPayload = byteArrayOf(
                (49 shl 1).toByte(),
                0x01,
                (0x40 or 19).toByte(),
        ) + body.copyOfRange(10, 20)

        assertNull(depacketizer.accept(rtpPacket(1, 1000, false, startPayload), 12 + startPayload.size))
        val result = depacketizer.accept(rtpPacket(2, 1000, true, endPayload), 12 + endPayload.size)

        assertArrayEquals(withStartCode(nalHeader(19) + body), result)
    }

    @Test
    fun `aggregation packet splits into separate nal units`() {
        val depacketizer = RtpH265Depacketizer()
        val first = nalHeader(32) + byteArrayOf(0x11)
        val second = nalHeader(33) + byteArrayOf(0x22, 0x33)

        val payload = byteArrayOf((48 shl 1).toByte(), 0x01) +
                byteArrayOf(0, first.size.toByte()) + first +
                byteArrayOf(0, second.size.toByte()) + second

        val result = depacketizer.accept(rtpPacket(1, 1000, true, payload), 12 + payload.size)

        assertArrayEquals(withStartCode(first, second), result)
    }

    @Test
    fun `access unit spanning several packets is concatenated`() {
        val depacketizer = RtpH265Depacketizer()
        val vps = nalHeader(32) + byteArrayOf(0x01)
        val sps = nalHeader(33) + byteArrayOf(0x02)
        val idr = nalHeader(19) + byteArrayOf(0x03)

        assertNull(depacketizer.accept(rtpPacket(1, 900, false, vps), 12 + vps.size))
        assertNull(depacketizer.accept(rtpPacket(2, 900, false, sps), 12 + sps.size))
        val result = depacketizer.accept(rtpPacket(3, 900, true, idr), 12 + idr.size)

        assertArrayEquals(withStartCode(vps, sps, idr), result)
    }

    @Test
    fun `dropped packet discards the damaged access unit`() {
        val depacketizer = RtpH265Depacketizer()
        val first = nalHeader(1) + byteArrayOf(0x01)
        val second = nalHeader(1) + byteArrayOf(0x02)

        assertNull(depacketizer.accept(rtpPacket(1, 900, false, first), 12 + first.size))
        val result = depacketizer.accept(rtpPacket(3, 900, true, second), 12 + second.size)

        assertNull(result)
    }

    @Test
    fun `recovers on the next access unit after a loss`() {
        val depacketizer = RtpH265Depacketizer()
        val damaged = nalHeader(1) + byteArrayOf(0x01)
        val clean = nalHeader(1) + byteArrayOf(0x09)

        depacketizer.accept(rtpPacket(1, 900, false, damaged), 12 + damaged.size)
        depacketizer.accept(rtpPacket(3, 900, true, damaged), 12 + damaged.size)
        val result = depacketizer.accept(rtpPacket(4, 1800, true, clean), 12 + clean.size)

        assertArrayEquals(withStartCode(clean), result)
    }

    @Test
    fun `new timestamp starts a fresh access unit`() {
        val depacketizer = RtpH265Depacketizer()
        val stale = nalHeader(1) + byteArrayOf(0x01)
        val fresh = nalHeader(1) + byteArrayOf(0x02)

        assertNull(depacketizer.accept(rtpPacket(1, 900, false, stale), 12 + stale.size))
        val result = depacketizer.accept(rtpPacket(2, 1800, true, fresh), 12 + fresh.size)

        assertArrayEquals(withStartCode(fresh), result)
    }

    @Test
    fun `padded packet ignores padding bytes`() {
        val depacketizer = RtpH265Depacketizer()
        val nal = nalHeader(1) + byteArrayOf(0x77)
        val packet = rtpPacket(1, 900, true, nal + byteArrayOf(0, 0, 3))
        packet[0] = (packet[0].toInt() or 0x20).toByte()

        val result = depacketizer.accept(packet, packet.size)

        assertArrayEquals(withStartCode(nal), result)
    }

    @Test
    fun `header fields are parsed`() {
        val packet = rtpPacket(0x1234, 0xDEADBEEF, true, byteArrayOf(0, 0, 0))

        assertEquals(0x1234, RtpPacket.sequence(packet))
        assertEquals(0xDEADBEEFL, RtpPacket.timestamp(packet))
        assertEquals(PAYLOAD_TYPE, RtpPacket.payloadType(packet))
    }

    private companion object {
        const val PAYLOAD_TYPE = 96
    }
}
