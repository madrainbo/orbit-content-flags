package dev.orbitflags.core

/**
 * Kotlin port of content-filter-extension/core/scoring.js. The two must give
 * the same results; shared/scoring-vectors.tsv is checked by both test suites.
 * Thresholds are documented in content-filter-extension/METHODOLOGY.md.
 */
object Scoring {
    data class Hit(val term: String, val category: String)

    data class Result(
        val tier: String,
        val score: Int,
        val tokens: Int,
        val hits: List<Hit>,
        val capsRatio: Double = 0.0,
        val exclaimRate: Double = 0.0,
    )

    data class Urgency(val id: String, val match: String)

    private val LEXICON = linkedMapOf(
        "outrage" to listOf(
            "outrag", "furious", "fury", "infuriat", "enrag", "disgrac", "shameful",
            "shameless", "scandal", "betray", "corrupt", "traitor", "treason", "vile",
            "evil", "despicabl", "disgust", "appall", "atrocious", "atrocit", "slam",
            "destroy", "humiliat", "hypocri", "liar",
        ),
        "fear" to listOf(
            "terrif", "horrif", "horror", "nightmare", "catastroph", "disaster",
            "devastat", "deadly", "panic", "threat", "danger", "crisis", "collaps",
            "chaos", "alarming", "shocking",
        ),
        "bait" to listOf(
            "unbelievabl", "insane", "jaw-dropping", "mind-blowing", "you won't believe",
            "what happened next",
        ),
    )

    private val MATCHERS = LEXICON.map { (category, stems) ->
        category to Regex(
            "\\b(?:" + stems.joinToString("|") { Regex.escape(it) } + ")[\\w'-]*",
            RegexOption.IGNORE_CASE,
        )
    }

    private val TOKEN = Regex("[A-Za-z][A-Za-z'-]*")
    private val SENTENCE_END = Regex("[.!?]+")

    private const val MIN_TOKENS = 4
    private const val DENSITY_CAP = 0.12
    private const val CAPS_CAP = 0.3
    private const val EXCLAIM_CAP = 1.0
    private const val W_DENSITY = 70
    private const val W_CAPS = 15
    private const val W_EXCLAIM = 15
    private const val MEDIUM = 25
    private const val HIGH = 55
    private const val MIN_HITS_FOR_HIGH = 2

    fun scoreEmotion(text: String?): Result {
        val t = text ?: ""
        val tokens = TOKEN.findAll(t).map { it.value }.toList()
        if (tokens.size < MIN_TOKENS) return Result("insufficient-text", 0, tokens.size, emptyList())

        val hits = ArrayList<Hit>()
        for ((category, re) in MATCHERS) {
            for (m in re.findAll(t)) hits.add(Hit(m.value.lowercase(), category))
        }
        val longWords = tokens.filter { it.length >= 4 }
        val caps = longWords.count { it == it.uppercase() }
        val capsRatio = if (longWords.isEmpty()) 0.0 else caps.toDouble() / longWords.size
        val sentences = maxOf(1, t.split(SENTENCE_END).count { it.trim().isNotEmpty() })
        val exclaimRate = t.count { it == '!' }.toDouble() / sentences
        val density = hits.size.toDouble() / tokens.size

        val score = Math.round(
            minOf(density / DENSITY_CAP, 1.0) * W_DENSITY +
                minOf(capsRatio / CAPS_CAP, 1.0) * W_CAPS +
                minOf(exclaimRate / EXCLAIM_CAP, 1.0) * W_EXCLAIM
        ).toInt()
        var tier = if (score >= HIGH) "high" else if (score >= MEDIUM) "medium" else "low"
        if (tier == "high" && hits.size < MIN_HITS_FOR_HIGH) tier = "medium"
        return Result(tier, score, tokens.size, hits, capsRatio, exclaimRate)
    }

    private val CATEGORY_NAMES = linkedMapOf("outrage" to "outrage", "fear" to "fear", "bait" to "clickbait")

    /** Plain-language reason for a flag, e.g. "fear and outrage wording". */
    fun describeCategories(hits: List<Hit>): String {
        val counts = CATEGORY_NAMES.map { (category, name) -> name to hits.count { it.category == category } }
            .filter { it.second > 0 }
        if (counts.isEmpty()) return "capitals and exclamation marks"
        // Stable sort: ties keep outrage, fear, clickbait order.
        return counts.sortedByDescending { it.second }.take(2).joinToString(" and ") { it.first } + " wording"
    }

    /** kind is "adult", "scam", "charge", "hook" or null when the passage is not flagged. */
    data class Classification(val kind: String?, val reason: String, val emotion: Result)

    private fun matchPatterns(list: List<Patterns.Entry>, t: String): Pair<Int, List<String>> {
        var weight = 0
        val matches = ArrayList<String>()
        for (entry in list) {
            val m = entry.re.find(t) ?: continue
            weight += entry.w
            matches.add(m.value.lowercase().trim())
        }
        return weight to matches
    }

    /**
     * Decides whether a passage should be flagged, and why. Adult wording wins
     * over scam-style wording, which wins over high emotional charge.
     */
    fun classify(text: String?): Classification {
        val t = text ?: ""
        val emotion = scoreEmotion(t)
        if (matchPatterns(Patterns.ADULT, t).first >= Patterns.THRESHOLD) {
            return Classification("adult", "adult-content wording", emotion)
        }
        val (weight, matches) = matchPatterns(Patterns.SCAM, t)
        if (weight >= Patterns.THRESHOLD) {
            val quoted = matches.take(2).joinToString(", ") { "\u201c" + it.take(40) + "\u201d" }
            return Classification("scam", "scam-style wording ($quoted)", emotion)
        }
        if (emotion.tier == "high") {
            return Classification("charge", "high emotional charge (" + describeCategories(emotion.hits) + ")", emotion)
        }
        val (hookWeight, hookMatches) = matchPatterns(Patterns.HOOK, t)
        if (hookWeight >= Patterns.THRESHOLD) {
            val quoted = hookMatches.take(2).joinToString(", ") { "\u201c" + it.take(40) + "\u201d" }
            return Classification("hook", "engagement-bait wording ($quoted)", emotion)
        }
        return Classification(null, "", emotion)
    }

    /**
     * True when a short piece of text is, in full, an "AI-generated" label of
     * the kind platforms display. Prose that merely mentions AI does not match.
     */
    fun isAiLabel(text: String?): Boolean {
        val t = (text ?: "").trim()
        return t.length <= 60 && Patterns.AI_LABEL.matches(t)
    }

    private val URGENCY = listOf(
        "low-stock" to Regex("\\bonly\\s+\\d+\\s+(?:left|remaining)\\b", RegexOption.IGNORE_CASE),
        "activity-notice" to Regex(
            "\\b\\d+\\s+(?:people|others|shoppers|customers)\\s+(?:are\\s+)?(?:viewing|looking|watching|bought)\\b",
            RegexOption.IGNORE_CASE,
        ),
        "deadline" to Regex(
            "\\b(?:offer|sale|deal|discount|price)\\s+ends\\s+(?:in|soon|today|tonight)\\b",
            RegexOption.IGNORE_CASE,
        ),
        "hurry" to Regex("\\b(?:hurry|act now|don't miss out|last chance|selling fast)\\b", RegexOption.IGNORE_CASE),
    )

    fun urgencySignals(text: String?): List<Urgency> {
        val t = text ?: ""
        return URGENCY.mapNotNull { (id, re) -> re.find(t)?.let { Urgency(id, it.value) } }
    }
}
