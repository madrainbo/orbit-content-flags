package dev.orbitflags.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoringTest {
    /** The same vectors are asserted against the JavaScript scorer. */
    @Test
    fun matchesSharedVectors() {
        val lines = File("../../shared/scoring-vectors.tsv").readLines().filter { it.isNotBlank() }
        assertTrue(lines.size >= 30, "expected shared vectors")
        for (line in lines) {
            val cols = line.split('\t', limit = 9)
            val (tier, score, hits, urgency, wording) = cols
            val text = cols[8]
            val r = Scoring.scoreEmotion(text)
            assertEquals(wording, Scoring.describeCategories(r.hits), text)
            val c = Scoring.classify(text)
            assertEquals(cols[5], c.kind ?: "-", text)
            assertEquals(cols[6], c.reason.ifEmpty { "-" }, text)
            assertEquals(cols[7], if (Scoring.isAiLabel(text)) "ai" else "-", text)
            assertEquals(tier, r.tier, text)
            assertEquals(score.toInt(), r.score, text)
            assertEquals(hits.toInt(), r.hits.size, text)
            assertEquals(urgency, Scoring.urgencySignals(text).joinToString(",") { it.id }, text)
        }
    }
}

class DomainsTest {
    @Test
    fun normalizes() {
        assertEquals("example.com", Domains.normalizeDomain("https://www.Example.com:8080/a?b#c"))
        assertEquals("news.example.co.uk", Domains.normalizeDomain("  news.example.co.uk  "))
        assertNull(Domains.normalizeDomain("localhost"))
        assertNull(Domains.normalizeDomain("exa mple.com"))
        assertNull(Domains.normalizeDomain("javascript:alert(1)"))
    }

    @Test
    fun parsesLists() {
        val r = Domains.parseList("example.com\nwww.example.com\nnot a domain\n\nfoo.org")
        assertEquals(listOf("example.com", "foo.org"), r.valid)
        assertEquals(listOf("not a domain"), r.invalid)
    }

    @Test
    fun allowBeatsBlock() {
        assertTrue(Domains.isBlocked("m.bad.com", listOf("bad.com"), emptyList()))
        assertFalse(Domains.isBlocked("notbad.com", listOf("bad.com"), emptyList()))
        assertFalse(Domains.isBlocked("ok.bad.com", listOf("bad.com"), listOf("ok.bad.com")))
    }
}

class DnsPacketTest {
    private fun query(name: String, proto: Int = 17, dstPort: Int = 53, flags: Int = 0x0100): ByteArray {
        val qname = name.split('.').flatMap { listOf(it.length.toByte()) + it.toByteArray().toList() } + 0.toByte()
        val dns = byteArrayOf(0x12, 0x34, (flags shr 8).toByte(), flags.toByte(), 0, 1, 0, 0, 0, 0, 0, 1) +
            qname.toByteArray() + byteArrayOf(0, 1, 0, 1) +
            byteArrayOf(0, 0, 41, 4, -48, 0, 0, 0, 0, 0, 0) // EDNS OPT record
        val p = ByteArray(28 + dns.size)
        p[0] = 0x45
        p[2] = (p.size shr 8).toByte(); p[3] = p.size.toByte()
        p[9] = proto.toByte()
        byteArrayOf(10, 111, 222.toByte(), 1).copyInto(p, 12)
        byteArrayOf(10, 111, 222.toByte(), 2).copyInto(p, 16)
        p[20] = 0xC3.toByte(); p[21] = 0x50 // source port 50000
        p[22] = (dstPort shr 8).toByte(); p[23] = dstPort.toByte()
        val udpLen = 8 + dns.size
        p[24] = (udpLen shr 8).toByte(); p[25] = udpLen.toByte()
        dns.copyInto(p, 28)
        return p
    }

    @Test
    fun parsesAQuery() {
        val p = query("Sub.Example.COM")
        val q = assertNotNull(DnsPacket.parseQuery(p.copyOf(2000), p.size))
        assertEquals("sub.example.com", q.name)
        assertEquals(50000, q.srcPort)
        assertEquals(53, q.dstPort)
        assertEquals(12 + 17 + 4, q.questionEnd)
    }

    @Test
    fun ignoresEverythingElse() {
        val ok = query("example.com")
        assertNull(DnsPacket.parseQuery(query("example.com", proto = 6), ok.size), "TCP")
        assertNull(DnsPacket.parseQuery(query("example.com", dstPort = 443), ok.size), "not DNS")
        assertNull(DnsPacket.parseQuery(query("example.com", flags = 0x8180), ok.size), "a response")
        assertNull(DnsPacket.parseQuery(ok, ok.size - 20), "truncated")
        assertNull(DnsPacket.parseQuery(ok.copyOf().also { it[6] = 0x20 }, ok.size), "fragment")
        assertNull(DnsPacket.parseQuery(ok.copyOf().also { it[0] = 0x60 }, ok.size), "IPv6")
        assertNull(DnsPacket.parseQuery(ok.copyOf().also { it[40] = 0xC0.toByte() }, ok.size), "compressed name")
        assertNull(DnsPacket.parseQuery(ByteArray(10), 10), "too short")
    }

    @Test
    fun buildsAnNxdomainReply() {
        val q = assertNotNull(DnsPacket.parseQuery(query("blocked.example"), 2000.coerceAtMost(query("blocked.example").size)))
        val dns = DnsPacket.nxdomain(q)
        assertEquals(q.questionEnd, dns.size, "additional records are dropped")
        assertEquals(0x12, dns[0].toInt()); assertEquals(0x34, dns[1].toInt())
        assertEquals(0x81, dns[2].toInt() and 0xFF)
        assertEquals(0x83, dns[3].toInt() and 0xFF)
        assertContentEquals(byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0), dns.copyOfRange(4, 12))

        val reply = DnsPacket.wrapResponse(q, dns)
        assertEquals(28 + dns.size, reply.size)
        assertContentEquals(q.dstAddr, reply.copyOfRange(12, 16))
        assertContentEquals(q.srcAddr, reply.copyOfRange(16, 20))
        assertEquals(0, DnsPacket.checksum(reply, 0, 20), "IP header checksum verifies")
        assertEquals(53, ((reply[20].toInt() and 0xFF) shl 8) or (reply[21].toInt() and 0xFF))
        assertEquals(50000, ((reply[22].toInt() and 0xFF) shl 8) or (reply[23].toInt() and 0xFF))
        assertContentEquals(dns, reply.copyOfRange(28, reply.size))
    }
}
