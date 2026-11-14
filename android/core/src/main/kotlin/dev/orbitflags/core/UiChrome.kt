package dev.orbitflags.core

/**
 * Recognises the labels an app gives its own controls ("Send post. Button.
 * Double tap to…", "Profile picture of…", "1,244 likes"). These are read out
 * by screen readers and reach this app the same way, but they are part of the
 * interface, not content, and must never be flagged.
 */
object UiChrome {
    private val PATTERNS = listOf(
        "\\bdouble[- ]tap to\\b",
        "[.,;] button\\b",
        "^button\\b",
        "\\b(?:tab|page|item|photo|image|slide),? \\d+ of \\d+\\b",
        "\\b(?:selected|not selected|checked|unchecked|collapsed|expanded|disabled)\\.?$",
        "^profile (?:picture|photo|image) of\\b",
        "^(?:sponsored )?(?:photo|video|reel|carousel|image|post|story|clip|short) (?:by|from|of)\\b",
        "^more (?:actions|options)\\b",
        "^(?:navigate up|open navigation|go to channel|action menu|close|back|dismiss|cancel|search|menu|settings)\\b.{0,30}$",
        "^(?:play|pause|mute|unmute|skip|replay|rewind|fast forward|expand|collapse|minimi[sz]e|enter full ?screen)\\b.{0,30}$",
        "^(?:send|share|save|like|unlike|comment on|reply to|repost|report) (?:this )?(?:post|reel|video|story|comment|photo|message)\\.?$",
        "^\\d[\\d,.]*[km]? (?:likes?|comments?|shares?|views?|followers?|following|posts?|reposts?|replies|reactions|subscribers)\\b",
        "\\b\\d[\\d,.]*[km]? (?:likes?|comments?|shares?|views?|reposts?)\\.?$",
        "^(?:liked by|view all \\d|see translation|suggested for you|add a comment|write a comment|view replies|view \\d+ repl)",
        "^(?:sponsored|follow|following|message|subscribe|subscribed|join|share|save|like|comment|send)$",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    fun isControlLabel(text: String?): Boolean {
        val t = (text ?: "").trim()
        return t.isNotEmpty() && PATTERNS.any { it.containsMatchIn(t) }
    }
}
