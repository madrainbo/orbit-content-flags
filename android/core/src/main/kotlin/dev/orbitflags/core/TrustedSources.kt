package dev.orbitflags.core

/**
 * Known, notable sources: official accounts and websites of news
 * organisations, broadcasters, public bodies and universities, and titles of
 * TV series, built from Wikidata by tools/trusted/build.py.
 *
 * Trust applies only to who posted or hosts content, never to who is
 * mentioned: "BBC reports you can double your bitcoin" in a stranger's post
 * is a classic scam and must still be flagged.
 */
class TrustedSources(tsv: String) {
    data class Source(val label: String, val category: String, val wikidata: String) {
        val description: String get() = "$label ($category, per Wikidata)"
    }

    private val accounts = HashMap<String, Source>()
    private val domains = HashMap<String, Source>()
    private val titles = HashMap<String, Source>()

    init {
        for (line in tsv.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val f = line.split('\t')
            if (f.size < 5) continue
            val source = Source(f[2], f[3], f[4])
            when (f[0]) {
                "ACCOUNT" -> accounts.putIfAbsent(f[1], source)
                "DOMAIN" -> domains.putIfAbsent(f[1], source)
                "TITLE" -> titles.putIfAbsent(f[1], source)
            }
        }
    }

    val size: Int get() = accounts.size + domains.size + titles.size

    fun account(handle: String?): Source? {
        if (handle == null || handle.startsWith(WEAK)) return null
        return accounts[handle.trim().removePrefix("@").lowercase()]
    }

    /** The source for a host or any parent domain, e.g. "news.bbc.co.uk" -> "bbc.co.uk". */
    fun domain(host: String?): Source? {
        var h = Domains.normalizeDomain(host) ?: return null
        while (true) {
            domains[h]?.let { return it }
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return null
            h = h.substring(dot + 1)
        }
    }

    /**
     * A screen element whose whole text is a known TV series title. Titles
     * that are a single word ("House", "Dark") only count in video apps.
     */
    fun title(text: String?, inVideoApp: Boolean): Source? {
        val t = text?.trim()?.lowercase() ?: return null
        if (t.length < 4) return null
        if (!t.contains(' ') && !inVideoApp) return null
        return titles[t]
    }

    companion object {
        private val HANDLE = Regex("^@?([A-Za-z0-9_](?:[A-Za-z0-9_.]{1,28}[A-Za-z0-9_]))$")

        /**
         * If a screen element's whole text is a username, the username. Apps show
         * usernames either with "@" (X, YouTube) or in lower case (Instagram,
         * TikTok); capitalised words such as "People" or "Time" are interface
         * text and must not be taken for the accounts of People or TIME.
         */
        fun handleIn(text: String?): String? {
            val t = text?.trim() ?: return null
            if (!t.startsWith("@") && t.any { it.isUpperCase() }) return null
            val m = HANDLE.matchEntire(t) ?: return null
            val h = m.groupValues[1].lowercase()
            return if (h.any { it.isLetter() } && !UiChrome.isControlLabel(t)) h else null
        }

        /** Marks a username that cannot be trusted on its own (see [posterKey]). */
        const val WEAK = "?"

        /**
         * The username in a screen element, for working out who posted what. A
         * short plain word shown without "@" ("evil", "heroes", "forbes") could
         * just as well be a one-word comment, so it still marks where a new
         * poster starts but is prefixed with [WEAK] and never matches a source.
         */
        fun posterKey(text: String?): String? {
            val h = handleIn(text) ?: return null
            val plainWord = h.all { it in 'a'..'z' } && h.length < 7
            return if (plainWord && text?.trim()?.startsWith("@") != true) WEAK + h else h
        }

        /**
         * Which username posted each passage: the nearest username element at or
         * above the passage. [handles] and [passageTops] are screen y positions.
         */
        fun authors(passageTops: List<Int>, handles: List<Pair<String, Int>>): List<String?> {
            val sorted = handles.sortedBy { it.second }
            return passageTops.map { top -> sorted.lastOrNull { it.second <= top }?.first }
        }
    }
}
