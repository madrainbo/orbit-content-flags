package dev.orbitflags.core

/**
 * Minimal IPv4/UDP DNS handling for the on-device DNS filter. Only plain
 * single-question queries to port 53 are recognised; everything else is ignored.
 */
object DnsPacket {
    class Query(
        val srcAddr: ByteArray,
        val dstAddr: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        /** The DNS message (UDP payload). */
        val dns: ByteArray,
        /** Queried name, lower-case, without a trailing dot. */
        val name: String,
        /** Offset in [dns] just past the question section. */
        val questionEnd: Int,
    )

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v shr 8).toByte()
        b[i + 1] = v.toByte()
    }

    /** Parses an IP packet read from the tunnel. Returns null unless it is a DNS query. */
    fun parseQuery(packet: ByteArray, length: Int): Query? {
        if (length < 28 || length > packet.size) return null
        if (u8(packet, 0) shr 4 != 4) return null
        val ihl = (u8(packet, 0) and 0x0F) * 4
        if (ihl < 20 || u8(packet, 9) != 17) return null
        if (u16(packet, 6) and 0x3FFF != 0) return null // fragmented
        val total = u16(packet, 2)
        if (total > length || total < ihl + 8) return null
        val udpLen = u16(packet, ihl + 4)
        if (udpLen < 8 || ihl + udpLen > total) return null
        val dstPort = u16(packet, ihl + 2)
        if (dstPort != 53) return null

        val dns = packet.copyOfRange(ihl + 8, ihl + udpLen)
        if (dns.size < 17) return null
        if (u8(dns, 2) and 0xF8 != 0) return null // a response, or not a standard query
        if (u16(dns, 4) != 1) return null

        val name = StringBuilder()
        var pos = 12
        while (true) {
            if (pos >= dns.size) return null
            val len = u8(dns, pos)
            pos += 1
            if (len == 0) break
            if (len and 0xC0 != 0 || pos + len > dns.size) return null
            if (name.isNotEmpty()) name.append('.')
            for (i in 0 until len) name.append((u8(dns, pos + i)).toChar())
            pos += len
            if (name.length > 253) return null
        }
        if (pos + 4 > dns.size) return null
        return Query(
            srcAddr = packet.copyOfRange(12, 16),
            dstAddr = packet.copyOfRange(16, 20),
            srcPort = u16(packet, ihl),
            dstPort = dstPort,
            dns = dns,
            name = name.toString().lowercase(),
            questionEnd = pos + 4,
        )
    }

    /** A "no such domain" answer for the query. */
    fun nxdomain(q: Query): ByteArray {
        val out = q.dns.copyOf(q.questionEnd)
        out[2] = (0x80 or (u8(q.dns, 2) and 0x01)).toByte() // response, keep "recursion desired"
        out[3] = 0x83.toByte()                               // recursion available, NXDOMAIN
        for (i in 6..11) out[i] = 0                          // no answer, authority or additional records
        return out
    }

    /** Wraps a DNS message as the IPv4/UDP reply to [q]. */
    fun wrapResponse(q: Query, dns: ByteArray): ByteArray {
        val total = 28 + dns.size
        require(total <= 0xFFFF) { "response too large" }
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        p[6] = 0x40 // don't fragment
        p[8] = 64   // TTL
        p[9] = 17   // UDP
        q.dstAddr.copyInto(p, 12)
        q.srcAddr.copyInto(p, 16)
        put16(p, 10, checksum(p, 0, 20))
        put16(p, 20, q.dstPort)
        put16(p, 22, q.srcPort)
        put16(p, 24, 8 + dns.size)
        // UDP checksum left at 0, which IPv4 permits.
        dns.copyInto(p, 28)
        return p
    }

    fun checksum(b: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += u16(b, i)
            i += 2
        }
        if ((length and 1) == 1) sum += u8(b, offset + length - 1) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }
}
