package dev.orbitflags.app

import android.graphics.Rect

/** Something on screen that should be flagged, and where it is. */
class Finding(
    /**
     * Stable identity, so a cover follows its content and stays revealed once
     * tapped: "t:" + text, "o:" + text read from an image, or "i:" + picture fingerprint.
     */
    val key: String,
    val bounds: Rect,
    /** adult, scam, charge, hook or reported. */
    val kind: String,
    val reason: String,
    /** True when the text model is only fairly sure: listed, but never covered. */
    val possible: Boolean = false,
) {
    companion object {
        const val MIN_CHARS = 12

        val LABELS = linkedMapOf(
            "reported" to "Reported by you",
            "adult" to "Adult content",
            "scam" to "Scam-style",
            "charge" to "Highly charged",
            "hook" to "Engagement bait",
        )
    }
}
