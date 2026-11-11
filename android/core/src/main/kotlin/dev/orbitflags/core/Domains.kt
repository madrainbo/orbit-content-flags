package dev.orbitflags.core

/** Kotlin port of content-filter-extension/core/domains.js (list handling only). */
object Domains {
    private val DOMAIN = Regex("^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
    private val SCHEME = Regex("^[a-z][a-z0-9+.-]*://")
    private val USERINFO = Regex("^[^@/]*@")
    private val PORT = Regex(":\\d+$")

    data class Parsed(val valid: List<String>, val invalid: List<String>)

    /** "https://www.Example.com/path" -> "example.com"; null if not a domain. */
    fun normalizeDomain(input: String?): String? {
        var s = (input ?: "").trim().lowercase()
        if (s.isEmpty()) return null
        s = s.replace(SCHEME, "").replace(USERINFO, "")
        s = s.split('/', '?', '#')[0].replace(PORT, "").removePrefix("www.").removeSuffix(".")
        return if (DOMAIN.matches(s)) s else null
    }

    fun parseList(text: String?): Parsed {
        val valid = LinkedHashSet<String>()
        val invalid = ArrayList<String>()
        for (line in (text ?: "").split('\n', ',')) {
            if (line.isBlank()) continue
            val d = normalizeDomain(line)
            if (d == null) invalid.add(line.trim()) else valid.add(d)
        }
        return Parsed(valid.toList(), invalid)
    }

    /** True if host is the listed domain or a subdomain of it. */
    fun hostMatches(host: String, domain: String): Boolean {
        val h = host.lowercase()
        return h == domain || h.endsWith(".$domain")
    }

    fun inList(host: String, list: List<String>): Boolean = list.any { hostMatches(host, it) }

    /** Allow beats block, as in the browser extension. */
    fun isBlocked(host: String, block: List<String>, allow: List<String>): Boolean =
        !inList(host, allow) && inList(host, block)
}
