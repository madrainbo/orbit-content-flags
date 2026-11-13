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

class ScamTrackerTest {
    private val day = ScamTracker.DAY_MS
    private val t0 = 1_800_000_000_000L

    @Test
    fun findsStagesInChatText() {
        assertEquals(setOf("contact"), ScamTracker.stagesIn(listOf("Sorry, I think I have the wrong number. Is this Mr Chen?")))
        assertEquals(setOf("move"), ScamTracker.stagesIn(listOf("This app is not convenient, add me on Telegram")))
        assertEquals(setOf("invest"), ScamTracker.stagesIn(listOf("My uncle is a senior analyst and gives me insider tips")))
        assertEquals(setOf("deposit"), ScamTracker.stagesIn(listOf("The minimum deposit is 500, send the USDT to this wallet address")))
        assertEquals(setOf("fee"), ScamTracker.stagesIn(listOf("Your account is frozen. You must pay the tax to withdraw.")))
        assertEquals(setOf("plea"), ScamTracker.stagesIn(listOf("I am stuck at customs, can you send me some money? I will pay you back")))
        assertEquals(setOf("secrecy"), ScamTracker.stagesIn(listOf("Don't tell your family about this yet")))
        assertEquals(emptySet(), ScamTracker.stagesIn(listOf("Are we still on for dinner on Friday? I booked the table for eight.")))
        assertEquals(emptySet(), ScamTracker.stagesIn(listOf("Can you pick up milk on the way home?", "Running ten minutes late")))
    }

    @Test
    fun warnsOnlyWhenSeveralStagesIncludeMoney() {
        var s = ScamTracker.State()
        s = ScamTracker.observe(s, listOf("Nice to meet you, I found your profile"), t0)
        s = ScamTracker.observe(s, listOf("Good morning my dear, have you eaten?"), t0 + 2 * day)
        assertFalse(ScamTracker.assess(s, t0 + 2 * day).warn, "two stages")
        s = ScamTracker.observe(s, listOf("Let's continue on WhatsApp"), t0 + 5 * day)
        assertFalse(ScamTracker.assess(s, t0 + 5 * day).warn, "three stages but no money involved")
        s = ScamTracker.observe(s, listOf("I can teach you to trade, the returns are guaranteed returns every day"), t0 + 9 * day)
        val a = ScamTracker.assess(s, t0 + 9 * day)
        assertTrue(a.warn)
        assertEquals(9, a.spanDays)
        assertEquals(listOf(9, 7, 4, 0), a.stages.map { it.daysAgo })
        assertTrue(ScamTracker.describe(a).contains("investment or crypto talk (today)"))
        assertTrue(ScamTracker.describe(a).contains("contact from a stranger (9 days ago)"))
    }

    @Test
    fun ordinaryMoneyTalkAloneDoesNotWarn() {
        var s = ScamTracker.State()
        s = ScamTracker.observe(s, listOf("I did a bank transfer for the rent", "Thinking about crypto trading lately"), t0)
        val a = ScamTracker.assess(s, t0)
        assertEquals(2, a.stages.size)
        assertFalse(a.warn)
    }

    @Test
    fun dismissQuietensThenExpires() {
        var s = ScamTracker.observe(ScamTracker.State(),
            listOf("add me on telegram", "crypto trading platform", "minimum deposit"), t0)
        assertTrue(ScamTracker.assess(s, t0).warn)
        s = ScamTracker.dismiss(s, t0)
        assertFalse(ScamTracker.assess(s, t0 + 6 * day).warn)
        assertTrue(ScamTracker.assess(s, t0 + 8 * day).warn)
    }

    @Test
    fun forgetsOldMarkersAndRoundTripsStorage() {
        val s = ScamTracker.observe(ScamTracker.State(),
            listOf("add me on telegram", "crypto trading platform", "minimum deposit"), t0)
        assertFalse(ScamTracker.assess(s, t0 + ScamTracker.RETENTION_MS + day).warn)
        val text = ScamTracker.encode(ScamTracker.dismiss(s, t0))
        assertFalse(text.contains("telegram", ignoreCase = true), "no message text is stored")
        assertEquals(ScamTracker.dismiss(s, t0), ScamTracker.decode(text))
        assertEquals(ScamTracker.State(), ScamTracker.decode(""))
        assertEquals(ScamTracker.State(), ScamTracker.decode("garbage"))
    }

    @Test
    fun rereadingTheSameScreenDoesNotRewrite() {
        val s = ScamTracker.observe(ScamTracker.State(), listOf("minimum deposit"), t0)
        assertTrue(ScamTracker.observe(s, listOf("minimum deposit"), t0 + 60_000) === s)
        assertTrue(ScamTracker.observe(s, listOf("hello there"), t0 + 2 * day) === s)
    }

    @Test
    fun conversationKeyHidesNames() {
        val k = ScamTracker.conversationKey("com.whatsapp", "Alex Example")
        assertEquals(24, k.length)
        assertFalse(k.contains("alex", ignoreCase = true))
        assertEquals(k, ScamTracker.conversationKey("com.whatsapp", " alex example "))
        assertTrue(k != ScamTracker.conversationKey("org.telegram.messenger", "Alex Example"))
    }
}
