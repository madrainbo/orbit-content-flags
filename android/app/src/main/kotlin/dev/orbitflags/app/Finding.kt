package dev.orbitflags.app

import android.graphics.Rect

/** Something on screen that should be flagged, and where it is. */
class Finding(
    /** Stable identity, so a cover follows its content and stays revealed once tapped. */
    val key: String,
    val bounds: Rect,
    /** adult, scam, charge or hook. */
    val kind: String,
    val reason: String,
) {
    companion object {
        const val MIN_CHARS = 12
    }
}
