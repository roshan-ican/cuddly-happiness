package com.example.cameraremotecontroller

import java.io.ByteArrayOutputStream

internal interface RtpVideoDepacketizer {
    fun accept(packet: ByteArray, length: Int): ByteArray?

    fun reset()
}

internal class RtpH264Depacketizer : RtpVideoDepacketizer {
    private val accessUnit = ByteArrayOutputStream(INITIAL_CAPACITY)
    private var fragment: ByteArrayOutputStream? = null
    private var expectedSequence = -1
    private var accessUnitTimestamp = -1L
    private var dropUntilNextAccessUnit = false

    override fun reset() {
        accessUnit.reset()
        fragment = null
        expectedSequence = -1
        accessUnitTimestamp = -1L
        dropUntilNextAccessUnit = false
    }

    override fun accept(packet: ByteArray, length: Int): ByteArray? {
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
        if (payload.isEmpty()) return null

        when (payload[0].toInt() and 0x1F) {
            NAL_TYPE_FU_A -> appendFragment(payload)
            NAL_TYPE_STAP_A -> appendAggregated(payload)
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
        if (payload.size < 3) return
        val fuHeader = payload[1].toInt() and 0xFF
        val start = (fuHeader and 0x80) != 0
        val end = (fuHeader and 0x40) != 0
        val nalType = fuHeader and 0x1F

        if (start) {
            val rebuilt = ByteArrayOutputStream(payload.size)
            rebuilt.write((payload[0].toInt() and 0xE0) or nalType)
            rebuilt.write(payload, 2, payload.size - 2)
            fragment = rebuilt
            return
        }

        val current = fragment ?: run {
            dropUntilNextAccessUnit = true
            return
        }
        current.write(payload, 2, payload.size - 2)

        if (end) {
            val nal = current.toByteArray()
            fragment = null
            appendNalUnit(nal, 0, nal.size)
        }
    }

    private fun appendAggregated(payload: ByteArray) {
        var offset = 1
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
        accessUnit.write(RtpH265Depacketizer.START_CODE, 0, RtpH265Depacketizer.START_CODE.size)
        accessUnit.write(source, offset, size)
    }

    private companion object {
        const val NAL_TYPE_STAP_A = 24
        const val NAL_TYPE_FU_A = 28
        const val INITIAL_CAPACITY = 256 * 1024
    }
}
