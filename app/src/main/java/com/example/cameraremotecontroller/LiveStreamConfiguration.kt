package com.example.cameraremotecontroller

import java.net.DatagramSocket
import java.net.SocketException

// SDP parameter sets use four-byte Annex B prefixes (see SdpParser).
internal fun hasBaselineAvcSps(data: ByteArray): Boolean {
    for (offset in 0..data.size - 8) {
        if (data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
                data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte() &&
                (data[offset + 4].toInt() and 0x1f) == 7) {
            return data[offset + 5].toInt() == 66
        }
    }
    return false
}

internal fun bindFreshRtpPair(receiveBufferSize: Int): Pair<DatagramSocket, DatagramSocket> {
    // Ask the OS for a fresh port instead of repeatedly binding 40000 after restart.
    // The camera can keep sending the abandoned RTSP session until its timeout.
    repeat(100) {
        val rtp = DatagramSocket(0)
        if (rtp.localPort % 2 != 0) {
            rtp.close()
            return@repeat
        }
        var rtcp: DatagramSocket? = null
        try {
            rtcp = DatagramSocket(rtp.localPort + 1)
            rtp.receiveBufferSize = receiveBufferSize
            return rtp to rtcp
        } catch (_: SocketException) {
            rtcp?.close()
            rtp.close()
        }
    }
    error("No free RTP port pair")
}

// Returns sps_max_num_reorder_pics for the highest temporal layer, or null if unreadable.
internal fun hevcMaxNumReorderPics(data: ByteArray): Int? {
    val sps = annexBNals(data).firstOrNull { it.size > 2 && ((it[0].toInt() shr 1) and 0x3f) == 33 }
            ?: return null
    return runCatching { parseHevcMaxNumReorderPics(BitReader(unescapeRbsp(sps))) }.getOrNull()
}

private fun annexBNals(data: ByteArray): List<ByteArray> {
    val starts = mutableListOf<Int>()
    var i = 0
    while (i + 2 < data.size) {
        if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
            starts += i + 3
            i += 3
        } else {
            i++
        }
    }
    return starts.mapIndexed { index, start ->
        val end = if (index + 1 < starts.size) starts[index + 1] - 3 else data.size
        data.copyOfRange(start, maxOf(start, end))
    }
}

private fun unescapeRbsp(nal: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream(nal.size)
    var zeros = 0
    for (byte in nal) {
        if (zeros >= 2 && byte == 3.toByte()) {
            zeros = 0
            continue
        }
        out.write(byte.toInt())
        zeros = if (byte == 0.toByte()) zeros + 1 else 0
    }
    return out.toByteArray()
}

private class BitReader(private val data: ByteArray) {
    private var pos = 0

    fun u(bits: Int): Int {
        var value = 0
        repeat(bits) {
            val index = pos ushr 3
            require(index < data.size) { "SPS truncated" }
            value = (value shl 1) or ((data[index].toInt() shr (7 - (pos and 7))) and 1)
            pos++
        }
        return value
    }

    fun ue(): Int {
        var leadingZeros = 0
        while (u(1) == 0) {
            leadingZeros++
            require(leadingZeros < 32) { "Malformed exp-golomb code" }
        }
        return if (leadingZeros == 0) 0 else ((1 shl leadingZeros) - 1) + u(leadingZeros)
    }

    fun skip(bits: Int) {
        require(pos + bits <= data.size * 8) { "SPS truncated" }
        pos += bits
    }
}

private fun parseHevcMaxNumReorderPics(reader: BitReader): Int {
    reader.skip(16 + 4)
    val maxSubLayersMinus1 = reader.u(3)
    reader.skip(1)
    skipProfileTierLevel(reader, maxSubLayersMinus1)
    reader.ue()
    if (reader.ue() == 3) reader.skip(1)
    reader.ue()
    reader.ue()
    if (reader.u(1) == 1) repeat(4) { reader.ue() }
    reader.ue()
    reader.ue()
    reader.ue()
    val perSubLayer = reader.u(1) == 1
    var maxNumReorderPics = 0
    for (layer in (if (perSubLayer) 0 else maxSubLayersMinus1)..maxSubLayersMinus1) {
        reader.ue()
        maxNumReorderPics = reader.ue()
        reader.ue()
    }
    return maxNumReorderPics
}

private fun skipProfileTierLevel(reader: BitReader, maxSubLayersMinus1: Int) {
    reader.skip(96)
    val profilePresent = BooleanArray(maxSubLayersMinus1)
    val levelPresent = BooleanArray(maxSubLayersMinus1)
    for (layer in 0 until maxSubLayersMinus1) {
        profilePresent[layer] = reader.u(1) == 1
        levelPresent[layer] = reader.u(1) == 1
    }
    if (maxSubLayersMinus1 > 0) reader.skip(2 * (8 - maxSubLayersMinus1))
    for (layer in 0 until maxSubLayersMinus1) {
        if (profilePresent[layer]) reader.skip(88)
        if (levelPresent[layer]) reader.skip(8)
    }
}
