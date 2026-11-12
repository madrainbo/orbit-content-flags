# Methodology (v0.6)

Published so the thresholds can be criticised. Every number here is a starting choice, not a validated benchmark. The code is in `core/scoring.js`, `core/labels.js` and `content.js`; the phrase patterns are in `shared/patterns.json`, from which the JavaScript and Kotlin tables are generated.

## Emotional charge

Scored per heading or paragraph (20 to 2,000 characters; first 600 blocks per page).

| Component | Measure | Saturates at | Weight |
|---|---|---|---|
| Charged-word density | lexicon matches / word tokens | 0.12 | 70 |
| Capitals | share of 4+ letter words in ALL CAPS | 0.30 | 15 |
| Exclamation | exclamation marks per sentence | 1.0 | 15 |

Score = sum of weighted components, 0 to 100.

| Tier | Rule |
|---|---|
| `insufficient-text` | fewer than 4 word tokens |
| `low` | score below 25 |
| `medium` | score 25 to 54, or 55+ with only one lexicon match |
| `high` | score 55+ **and** at least two lexicon matches |

The lexicon is about 50 English stems in three groups (outrage, fear, bait). It is motivated by the finding that moral-emotional words increase sharing (Brady et al., PNAS 2017, doi:10.1073/pnas.1618923114), but it is our own short list, not that paper's dictionary.

**What happens to a `high` passage:** by default it is hidden behind a notice with a Show button; settings can change this to blur or label only. `medium` passages are never hidden.

**What a tier does not mean:** that the text is false, manipulative or low quality. Accurate reporting on a disaster will often score high. Known false positives include sports ("slam dunk") and quoted speech.

## Scam-style, adult and engagement-bait wording

Each passage is checked against three lists of phrase patterns in `shared/patterns.json`. Every pattern has a weight of 1 or 2, and a passage is flagged when the weights of the distinct patterns it matches add up to **2 or more**. So one strong phrase is enough ("claim your prize", "verify your account"), while a weak one on its own is not ("congratulations", "act now", "nude").

| Kind | Patterns | Examples of weight 2 | Examples of weight 1 |
|---|---|---|---|
| Scam-style | 45 | "you've won", "account has been suspended", "double your bitcoin", "pay with gift cards" | "congratulations", "act now", "wire transfer", "work from home" |
| Adult | 23 | explicit site and content terms, the 18+ emoji | "18+", "adults only", "nude" |
| Engagement bait | 22 | "share before it gets deleted", "one weird trick", "stop scrolling" | "follow for more", "link in bio", "tag a friend" |

If a passage qualifies for more than one kind, the order is adult, scam-style, high emotional charge, engagement bait. The reason shown for scam-style passages quotes up to two matched phrases; the reason for adult passages never repeats the matched words.

**What these do not mean:** that a message is fraudulent or that content is explicit. The lists are English-only starting choices, not validated against labelled data. Legitimate security notices, real prize draws and job adverts use the same phrases; sexual-health and art writing can trigger the adult list. Images and video are never inspected.

## "AI-generated" badges

A short piece of text (60 characters or fewer) is treated as an AI label only if the **whole** text is one of a fixed set of badge wordings, such as "AI-generated", "AI info", "Made with AI", "Creator labeled as AI-generated" or "Altered or synthetic content". Prose that mentions AI-generated content does not match. In the browser, if the badge sits inside an `article` element, that post is hidden; otherwise it is only listed.

This reports a label someone else applied. It does not detect AI content, and the absence of a badge says nothing.

## Draining design

| Signal | Rule |
|---|---|
| Autoplay video | a `<video>` has the `autoplay` attribute, or is playing before any user interaction |
| Infinite scroll | page height grows by 200px+ three times while the viewport is within 1.5 screens of the bottom |
| Pressure wording | page text matches a low-stock, activity-notice, deadline or hurry pattern |
| Countdown timer | an `HH:MM:SS` value changes within 2.5s on a page that also contains offer wording |

Pressure wording and countdown timers correspond to the urgency and scarcity categories in Mathur et al., "Dark Patterns at Scale" (CSCW 2019, arXiv:1907.07032). A match means the pattern is present, not that the claim is false.

## Declared labels

These are read, not inferred. Each reports what a publisher or tool has stated about its own content.

| Label | Source | Rule |
|---|---|---|
| Declared AI-generated | IPTC Digital Source Type in image metadata | value is `trainedAlgorithmicMedia` |
| Declared partly AI-generated / composite | same | value is `compositeWithTrainedAlgorithmicMedia` or `compositeSynthetic` |
| Declared algorithmically generated | same | value is `algorithmicMedia` |
| Carries Content Credentials | C2PA manifest store in the file | a JUMBF box labelled `c2pa` is present |
| Fact-check | schema.org `ClaimReview` in the page's JSON-LD | claim, rating and reviewer are shown as written |
| Opinion / satire / advertiser content | schema.org article type in JSON-LD | `OpinionNewsArticle`, `SatiricalArticle`, `AdvertiserContentArticle` |
| Sponsored links | link markup | `rel` contains `sponsored` |

Images are checked only if at least 200x200 pixels, up to 20 per page, by searching the first 256 KB of the file for the source-type URI. No per-format parsing is done.

**What these do not mean:** none of them is verified. Metadata can be stripped, so an unlabelled image is not evidence of authenticity, and it can be forged, so a label is not proof either. Content Credentials signatures are not validated in this version; the label reports presence only. A `ClaimReview` is the site's own statement and is not checked against a list of recognised fact-checkers.

## Corrections

Clicking a label disputes and removes it; the count is kept on the device only. To challenge a threshold or lexicon entry, open an issue with an example page.
