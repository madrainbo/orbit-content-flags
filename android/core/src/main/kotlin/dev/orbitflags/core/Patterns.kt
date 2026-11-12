// GENERATED from shared/patterns.json by content-filter-extension/test/gen-patterns.js. Do not edit.
package dev.orbitflags.core

object Patterns {
    class Entry(val re: Regex, val w: Int)

    private fun P(re: String, w: Int) = Entry(Regex(re, RegexOption.IGNORE_CASE), w)

    const val THRESHOLD = 2

    val SCAM = listOf(
        P("""\byou(?:'ve| have)? (?:just )?won\b""", 2),
        P("""\bclaim (?:your|the|my) (?:prize|reward|gift|winnings|bonus|refund)\b""", 2),
        P("""\b(?:you have|you've|you were|you are) been (?:selected|chosen)\b""", 1),
        P("""\bcongratulations\b""", 1),
        P("""\b(?:verify|confirm|update|validate) your (?:account|identity|payment|billing|bank|card|password|details)\b""", 2),
        P("""\baccount (?:has been|is|was|will be) (?:suspended|locked|blocked|disabled|compromised|restricted|closed)\b""", 2),
        P("""\b(?:unusual|suspicious) (?:activity|sign-in|login|transaction)\b""", 1),
        P("""\b(?:urgent|immediate) action (?:is )?(?:required|needed)\b""", 1),
        P("""\bfinal (?:notice|warning|reminder)\b""", 1),
        P("""\bclick (?:here|the link|this link|below) to (?:claim|verify|confirm|unlock|receive|restore)\b""", 2),
        P("""\b(?:guaranteed|risk[- ]free) (?:returns?|profits?|income|earnings|investment|winnings)\b""", 2),
        P("""\bdouble your (?:money|bitcoin|crypto|investment|deposit)\b""", 2),
        P("""\b(?:earn|make) (?:up to )?[${'$'}£€]\s?\d[\d,]*k? ?(?:per|a|an|every|each|/) ?(?:day|week|hour|month)\b""", 2),
        P("""\b(?:crypto|bitcoin|btc|eth|usdt) giveaway\b""", 2),
        P("""\bsend (?:me |us )?(?:\d[\d.,]* )?(?:btc|bitcoin|eth|usdt|crypto)\b""", 2),
        P("""\b(?:pay|payment) (?:with|by|in|using|via) gift cards?\b""", 2),
        P("""\b(?:buy|purchase|send) (?:\w+ )?gift cards?\b""", 1),
        P("""\bwire transfer\b""", 1),
        P("""\bfree (?:iphone|gift card|money|cash|crypto|bitcoin)\b""", 1),
        P("""\bno experience (?:needed|required|necessary)\b""", 1),
        P("""\bwork from home\b""", 1),
        P("""\b100% (?:free|guaranteed|safe|legit)\b""", 1),
        P("""\bact (?:now|fast|immediately)\b""", 1),
        P("""\b(?:limited|exclusive)[- ](?:time )?(?:offer|deal|slots?|spots?)\b""", 1),
        P("""\b(?:dm|message|text|whatsapp|telegram) (?:me|us) (?:now |today )?(?:to|for) \w+""", 1),
        P("""\b(?:loan|credit) (?:is |has been )?(?:pre-?approved|approved instantly)\b""", 1),
        P("""\b(?:parcel|package|delivery) (?:is |has been )?(?:on hold|held|pending|undeliverable|could not be delivered)\b""", 1),
        P("""\b(?:small|customs|redelivery|processing|release) fee\b""", 1),
        P("""\binvestment opportunity\b""", 1),
        P("""\bget rich\b""", 1),
    )

    val ADULT = listOf(
        P("""\bporn\w*""", 2),
        P("""\bxxx\b""", 2),
        P("""\bnsfw\b""", 2),
        P("""\bonlyfans\b""", 2),
        P("""\bhentai\b""", 2),
        P("""\bnudes\b""", 2),
        P("""\b(?:sex|adult|nude|naked|erotic) (?:video|videos|tape|chat|cam|cams|pics|photos|movies|content|clips)\b""", 2),
        P("""\b(?:cam ?girls?|live cams?|escorts?)\b""", 2),
        P("""\bhot (?:singles|girls|babes|milfs?)\b""", 2),
        P("""\bexplicit (?:content|photos|videos|images)\b""", 2),
        P("""\b(?:nude|naked|topless)\b""", 1),
        P("""\b(?:18\+|adults only|over 18 only)""", 1),
        P("""\b(?:sexy|horny|hookups?|sexting|uncensored)\b""", 1),
        P("""\berotic\w*""", 1),
    )

    val AI_LABEL = Regex("""^(?:ai[- ]generated|ai[- ]generated content|ai info|made with ai|created with ai|generated (?:with|by) ai|imagined with ai|creator labell?ed as ai-generated|altered or synthetic content|contains ai-generated (?:content|media)|synthetic media|ai-generated image|ai-generated video)\.?${'$'}""", RegexOption.IGNORE_CASE)
}
