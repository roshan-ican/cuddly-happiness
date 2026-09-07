package com.example.cameraremotecontroller

import java.io.ByteArrayOutputStream

internal object RtpPacket {
    const val MIN_HEADER_SIZE = 12

    fun payloadType(packet: ByteArray): Int = packet[1].toInt() and 0x7F

    fun marker(packet: ByteArray): Boolean = (packet[1].toInt() and 0x80) != 0

    fun sequence(packet: ByteArray): Int =
            ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)

    fun timestamp(packet: ByteArray): Long =
            ((packet[4].toLong() and 0xFF) shl 24) or
                    ((packet[5].toLong() and 0xFF) shl 16) or
                    ((packet[6].toLong() and 0xFF) shl 8) or
                    (packet[7].toLong() and 0xFF)

    fun payloadRange(packet: ByteArray, length: Int): IntRange? {
        if (length < MIN_HEADER_SIZE) return null
        if ((packet[0].toInt() and 0xC0) shr 6 != 2) return null

        val csrcCount = packet[0].toInt() and 0x0F
        var start = MIN_HEADER_SIZE + csrcCount * 4
        if ((packet[0].toInt() and 0x10) != 0) {
            if (length < start + 4) return null
            val extensionWords = ((packet[start + 2].toInt() and 0xFF) shl 8) or
                    (packet[start + 3].toInt() and 0xFF)
            start += 4 + extensionWords * 4
        }

        var end = length
        if ((packet[0].toInt() and 0x20) != 0) {
            val padding = packet[length - 1].toInt() and 0xFF
            if (padding <= 0 || padding > length - start) return null
            end -= padding
        }

        return if (start >= end) null else start until end
    }
}

internal class RtpH265Depacketizer {
    private val accessUnit = ByteArrayOutputStream(INITIAL_CAPACITY)
    private var fragment: ByteArrayOutputStream? = null
    private var expectedSequence = -1
    private var accessUnitTimestamp = -1L
    private var dropUntilNextAccessUnit = false

    fun reset() {
        accessUnit.reset()
        fragment = null
        expectedSequence = -1
        accessUnitTimestamp = -1L
        dropUntilNextAccessUnit = false
    }

    fun accept(packet: ByteArray, length: Int): ByteArray? {
        val range = RtpPacket.payloadRange(packet, length) ?: return null
        val sequence = RtpPacket.sequence(packet)
        val timestamp = RtpPacket.timestamp(packet)

        if (expectedSequence >= 0 && sequence != expectedSequence) {
            dropUntilNextAccessUnit = true
            fragment = null
        }
        expectedSequence = (sequence + 1) and 0xFFFF

        if (accessUnitTimestamp >= 0 && timestamp != accessUnitTimestamp) {
            accessUnit.reset()
            fragment = null
            dropUntilNextAccessUnit = false
        }
        accessUnitTimestamp = timestamp

        val payload = packet.copyOfRange(range.first, range.last + 1)
        if (payload.size < 3) return null

        when ((payload[0].toInt() shr 1) and 0x3F) {
            NAL_TYPE_FU -> appendFragment(payload)
            NAL_TYPE_AP -> appendAggregated(payload)
            else -> appendNalUnit(payload, 0, payload.size)
        }

        if (!RtpPacket.marker(packet)) return null

        val complete = !dropUntilNextAccessUnit && accessUnit.size() > 0
        val result = if (complete) accessUnit.toByteArray() else null
        accessUnit.reset()
        fragment = null
        dropUntilNextAccessUnit = false
        accessUnitTimestamp = -1L
        return result
    }

    private fun appendFragment(payload: ByteArray) {
        if (payload.size < 4) return
        val fuHeader = payload[2].toInt() and 0xFF
        val start = (fuHeader and 0x80) != 0
        val end = (fuHeader and 0x40) != 0
        val nalType = fuHeader and 0x3F

        if (start) {
            val header = ByteArrayOutputStream(payload.size)
            header.write((payload[0].toInt() and 0x81) or (nalType shl 1))
            header.write(payload[1].toInt() and 0xFF)
            header.write(payload, 3, payload.size - 3)
            fragment = header
            return
        }

        val current = fragment ?: run {
            dropUntilNextAccessUnit = true
            return
        }
        current.write(payload, 3, payload.size - 3)

        if (end) {
            val nal = current.toByteArray()
            fragment = null
            appendNalUnit(nal, 0, nal.size)
        }
    }

    private fun appendAggregated(payload: ByteArray) {
        var offset = 2
        while (offset + 2 <= payload.size) {
            val size = ((payload[offset].toInt() and 0xFF) shl 8) or
                    (payload[offset + 1].toInt() and 0xFF)
            offset += 2
            if (size <= 0 || offset + size > payload.size) return
            appendNalUnit(payload, offset, size)
            offset += size
        }
    }

    private fun appendNalUnit(source: ByteArray, offset: Int, size: Int) {
        accessUnit.write(START_CODE, 0, START_CODE.size)
        accessUnit.write(source, offset, size)
    }

    companion object {
        val START_CODE = byteArrayOf(0, 0, 0, 1)
        private const val NAL_TYPE_AP = 48
        private const val NAL_TYPE_FU = 49
        private const val INITIAL_CAPACITY = 256 * 1024
    }
}
