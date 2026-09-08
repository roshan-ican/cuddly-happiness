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
