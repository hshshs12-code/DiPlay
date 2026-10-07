package com.shilapi.xcertplay.network

import java.net.Inet6Address

/**
 * Answers ICMPv6 Neighbor Solicitations for the host's link-local address directly on the NCM
 * link. The Android kernel normally does this for the tun, but a head-unit kernel that does not
 * leaves the iPhone unable to resolve the host and CarPlay never connects.
 */
internal object NdpResponder {
    private const val IPV6_HEADER = 40
    private const val ICMPV6 = 58
    private const val NEIGHBOR_SOLICITATION = 135
    private const val NEIGHBOR_ADVERTISEMENT = 136

    /** Returns the solicited target if [packet] (an IPv6 packet) is an NS for [host]. */
    fun solicitationFor(packet: ByteArray, offset: Int, length: Int, host: Inet6Address): Boolean {
        if (length < IPV6_HEADER + 24) return false
        if ((packet[offset].toInt() shr 4) and 0xf != 6) return false
        if (packet[offset + 6].toInt() and 0xff != ICMPV6) return false
        if (packet[offset + IPV6_HEADER].toInt() and 0xff != NEIGHBOR_SOLICITATION) return false
        val target = packet.copyOfRange(offset + IPV6_HEADER + 8, offset + IPV6_HEADER + 24)
        return target.contentEquals(host.address)
    }

    /** Builds the IPv6 Neighbor Advertisement replying to the NS at [offset] in [packet]. */
    fun advertisement(packet: ByteArray, offset: Int, host: Inet6Address, hostMac: ByteArray): ByteArray {
        val source = packet.copyOfRange(offset + 8, offset + 24)
        val unspecified = source.all { it.toInt() == 0 }
        val destination = if (unspecified) byteArrayOf(0xff.toByte(), 0x02, 0,0,0,0,0,0,0,0,0,0,0,0,0,1) else source
        val icmpLength = 24 + 8
        val out = ByteArray(IPV6_HEADER + icmpLength)
        out[0] = 0x60
        out[4] = (icmpLength shr 8).toByte(); out[5] = icmpLength.toByte()
        out[6] = ICMPV6.toByte()
        out[7] = 255.toByte()
        host.address.copyInto(out, 8)
        destination.copyInto(out, 24)
        val icmp = IPV6_HEADER
        out[icmp] = NEIGHBOR_ADVERTISEMENT.toByte()
        out[icmp + 1] = 0
        // Flags: Solicited (unless answering an unspecified source) | Override.
        out[icmp + 4] = (if (unspecified) 0x20 else 0x60).toByte()
        host.address.copyInto(out, icmp + 8)
        out[icmp + 24] = 2 // target link-layer address option
        out[icmp + 25] = 1 // length in 8-byte units
        hostMac.copyInto(out, icmp + 26)
        val checksum = icmpv6Checksum(out, icmpLength)
        out[icmp + 2] = (checksum shr 8).toByte(); out[icmp + 3] = checksum.toByte()
        return out
    }

    private fun icmpv6Checksum(packet: ByteArray, icmpLength: Int): Int {
        var sum = 0L
        fun add(start: Int, count: Int) {
            var i = start
            val end = start + count
            while (i + 1 < end) { sum += ((packet[i].toInt() and 0xff) shl 8) or (packet[i + 1].toInt() and 0xff); i += 2 }
            if (i < end) sum += (packet[i].toInt() and 0xff) shl 8
        }
        add(8, 32)                       // source + destination
        sum += icmpLength.toLong()       // upper-layer length
        sum += ICMPV6.toLong()           // next header
        add(IPV6_HEADER, icmpLength)
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        return (sum.inv() and 0xffff).toInt()
    }
}
