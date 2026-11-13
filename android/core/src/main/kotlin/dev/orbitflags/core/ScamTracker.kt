package dev.orbitflags.core

/**
 * Follows the stages of long-running scams (investment, romance, "pig
 * butchering") across a conversation over days or weeks. Each message in such
 * a scam looks harmless; the sequence gives it away.
 *
 * Only stage markers are kept: which stages were seen and when. No message
 * text and no names are stored; a conversation is identified by a one-way hash.
 */
object ScamTracker {
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** Markers older than this are forgotten. */
    const val RETENTION_MS = 180 * DAY_MS

    /** Stages needed, including at least one that involves money, before warning. */
    const val STAGES_TO_WARN = 3

    /** When each stage was first and last seen, in epoch milliseconds. */
    data class Seen(val first: Long, val last: Long)

    data class State(val seen: Map<String, Seen> = emptyMap(), val dismissedUntil: Long = 0) {
        val lastActivity: Long get() = seen.values.maxOfOrNull { it.last } ?: 0
    }

    data class StageReport(val label: String, val daysAgo: Int)

    data class Assessment(
        /** True when the conversation matches the pattern strongly enough to warn. */
        val warn: Boolean,
        val stages: List<StageReport>,
        /** Days between the earliest and latest stage seen. */
        val spanDays: Int,
    )

    /** Stage ids whose patterns match any of the given texts. */
    fun stagesIn(texts: Collection<String>): Set<String> {
        val found = LinkedHashSet<String>()
        for (stage in Patterns.STAGES) {
            if (texts.any { t -> stage.patterns.any { it.containsMatchIn(t) } }) found.add(stage.id)
        }
        return found
    }

    /** Records the stages visible now; returns the same object if nothing changed. */
    fun observe(state: State, texts: Collection<String>, now: Long): State {
        val found = stagesIn(texts)
        if (found.isEmpty()) return state
        val seen = HashMap(state.seen)
        var changed = false
        for (id in found) {
            val old = seen[id]
            // Re-stamp at most once an hour, so reading the same screen does not rewrite storage.
            if (old == null) {
                seen[id] = Seen(now, now)
                changed = true
            } else if (now - old.last >= 60 * 60 * 1000) {
                seen[id] = old.copy(last = now)
                changed = true
            }
        }
        return if (changed) state.copy(seen = seen) else state
    }

    fun assess(state: State, now: Long): Assessment {
        val live = state.seen.filterValues { now - it.last <= RETENTION_MS }
        val ordered = Patterns.STAGES.filter { it.id in live }
        val reports = ordered.map { StageReport(it.label, ((now - live.getValue(it.id).first) / DAY_MS).toInt()) }
        val span = if (live.isEmpty()) 0 else
            ((live.values.maxOf { it.last } - live.values.minOf { it.first }) / DAY_MS).toInt()
        val warn = ordered.size >= STAGES_TO_WARN && ordered.any { it.money } && now >= state.dismissedUntil
        return Assessment(warn, reports, span)
    }

    fun dismiss(state: State, now: Long, days: Int = 7): State = state.copy(dismissedUntil = now + days * DAY_MS)

    /** One line of text for the warning. */
    fun describe(a: Assessment): String {
        val parts = a.stages.joinToString("; ") { s ->
            s.label + when (s.daysAgo) {
                0 -> " (today)"
                1 -> " (yesterday)"
                else -> " (${s.daysAgo} days ago)"
            }
        }
        return "This chat matches a long-running scam pattern: $parts."
    }

    // ---- storage format: "id=first,last;id=first,last|dismissedUntil" ----

    fun encode(state: State): String =
        state.seen.entries.joinToString(";") { "${it.key}=${it.value.first},${it.value.last}" } + "|" + state.dismissedUntil

    fun decode(text: String?): State {
        if (text.isNullOrEmpty()) return State()
        val (body, dismissed) = text.split('|', limit = 2).let { it[0] to it.getOrNull(1) }
        val seen = HashMap<String, Seen>()
        for (part in body.split(';')) {
            val kv = part.split('=', limit = 2)
            val times = kv.getOrNull(1)?.split(',') ?: continue
            val first = times.getOrNull(0)?.toLongOrNull() ?: continue
            val last = times.getOrNull(1)?.toLongOrNull() ?: continue
            seen[kv[0]] = Seen(first, last)
        }
        return State(seen, dismissed?.toLongOrNull() ?: 0)
    }

    /** A one-way identifier for a conversation, so no app or contact name is stored. */
    fun conversationKey(appPackage: String, title: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$appPackage\u0000${title.trim().lowercase()}".toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }
}
