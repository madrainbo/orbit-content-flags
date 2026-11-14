package dev.orbitflags.core

/**
 * Finds the things a person can report in a piece of text: phone numbers,
 * account names and website domains. Each is reduced to one canonical form so
 * that the same number or account written differently still matches.
 */
object Entities {
    enum class Type { PHONE, ACCOUNT, DOMAIN }

    data class Entity(val type: Type, val value: String) {
        /** How the entity is shown to the user. */
        val display: String get() = if (type == Type.ACCOUNT) "@$value" else value

        fun encode(): String = type.name + ":" + value

        companion object {
            fun decode(text: String): Entity? {
                val parts = text.split(':', limit = 2)
                val type = Type.entries.firstOrNull { it.name == parts[0] } ?: return null
                val value = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
                return Entity(type, value)
            }
        }
    }

    private val ACCOUNT = Regex("(?<![\\w@./])@([A-Za-z0-9_](?:[A-Za-z0-9_.]{1,28}[A-Za-z0-9_]))")
    private val PHONE = Regex("(?<![\\w.+])(\\+?\\d[\\d\\s().-]{6,18}\\d)(?![\\w])")
    private val DATE = Regex("^\\d{1,4}[-./]\\d{1,2}[-./]\\d{1,4}$")
    private val DOMAIN = Regex(
        "(?<![\\w@.-])(?:https?://)?((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+([a-z]{2,24}))(?![\\w-])",
        RegexOption.IGNORE_CASE,
    )

    /** Endings accepted for a bare site name, so "report.pdf" or "node.js" are not taken for sites. */
    private val TLDS = setOf(
        "com", "net", "org", "io", "co", "app", "xyz", "info", "biz", "me", "ly", "gg", "tv", "cc", "ai", "dev",
        "shop", "store", "online", "site", "top", "club", "vip", "live", "link", "click", "pro", "finance",
        "exchange", "trade", "cash", "money", "bet", "win", "fun", "icu", "buzz", "rest", "work", "world",
        "uk", "us", "ca", "au", "de", "fr", "es", "it", "nl", "eu", "in", "ru", "cn", "jp", "kr", "br", "mx",
        "id", "ng", "za", "ph", "pk", "bd", "tr", "ae", "sg", "hk", "tw", "th", "vn", "pl", "se", "no", "ch",
    )

    fun extract(text: String?): List<Entity> {
        val t = text ?: return emptyList()
        val found = LinkedHashSet<Entity>()
        for (m in DOMAIN.findAll(t)) {
            val hasScheme = m.value.startsWith("http", ignoreCase = true)
            if (!hasScheme && m.groupValues[2].lowercase() !in TLDS) continue
            val domain = Domains.normalizeDomain(m.groupValues[1]) ?: continue
            found.add(Entity(Type.DOMAIN, domain))
        }
        for (m in ACCOUNT.findAll(t)) {
            val name = m.groupValues[1].lowercase().trimEnd('.')
            if (name.length >= 3 && name.any { it.isLetter() }) found.add(Entity(Type.ACCOUNT, name))
        }
        for (m in PHONE.findAll(t)) {
            val raw = m.groupValues[1].trim()
            if (DATE.matches(raw)) continue
            val digits = raw.filter { it.isDigit() }
            val international = raw.startsWith("+")
            // Without a leading +, require the length of a full national number.
            if (digits.length !in (if (international) 8..15 else 10..15)) continue
            found.add(Entity(Type.PHONE, (if (international) "+" else "") + digits))
        }
        return found.toList()
    }

    /** Reads what a user typed into a "report this" box. */
    fun parse(input: String?): Entity? {
        val s = (input ?: "").trim()
        if (s.isEmpty()) return null
        extract(s).firstOrNull()?.let { return it }
        extract("@$s").firstOrNull { it.type == Type.ACCOUNT && !s.contains(' ') && !s.contains('.') }?.let { return it }
        return Domains.normalizeDomain(s)?.let { Entity(Type.DOMAIN, it) }
    }

    /** True if [entity] is covered by [reported]: equal, or a subdomain of a reported domain. */
    fun matches(entity: Entity, reported: Entity): Boolean {
        if (entity.type != reported.type) return false
        return when (entity.type) {
            Type.DOMAIN -> Domains.hostMatches(entity.value, reported.value)
            Type.PHONE -> entity.value.trimStart('+').endsWith(reported.value.trimStart('+')) ||
                reported.value.trimStart('+').endsWith(entity.value.trimStart('+'))
            Type.ACCOUNT -> entity.value == reported.value
        }
    }

    /** The first reported entity mentioned in [text], if any. */
    fun firstReported(text: String?, reported: Collection<Entity>): Entity? {
        if (reported.isEmpty()) return null
        val here = extract(text)
        return reported.firstOrNull { r -> here.any { matches(it, r) } }
    }

    /** A short one-way fingerprint of a passage, used to remember "this was not a problem". */
    fun fingerprint(text: String): String {
        val normal = text.lowercase().filter { it.isLetterOrDigit() }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(normal.toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }
}
