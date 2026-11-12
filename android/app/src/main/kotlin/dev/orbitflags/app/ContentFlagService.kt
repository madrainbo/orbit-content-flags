package dev.orbitflags.app

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import dev.orbitflags.core.Scoring

/**
 * Scores the text of whichever app is in front. Highly charged passages are
 * covered by a notice until the user taps it, and a small pill summarises what
 * was found. All scoring happens on the device; the text is not stored or sent.
 */
class ContentFlagService : AccessibilityService() {
    private class Passage(val text: String, val bounds: Rect)

    private val handler = Handler(Looper.getMainLooper())
    private var scanPending = false
    private var pill: TextView? = null
    private var pillDismissed = false
    private var noticeShowing = false

    /** Covers currently on screen, keyed by the passage they hide. */
    private val covers = HashMap<String, TextView>()

    /** Passages the user chose to see; they stay visible while the service runs. */
    private val revealed = LinkedHashSet<String>()

    /** Confirms the service is running, since most screens produce no flag. */
    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        noticeShowing = true
        showPill("Orbit on-screen flags are on")
        handler.postDelayed({
            if (noticeShowing) {
                noticeShowing = false
                hidePill()
                scan()
            }
        }, NOTICE_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) pillDismissed = false
        // Throttle: at most one scan per interval, however many events arrive.
        if (scanPending) return
        scanPending = true
        handler.postDelayed({
            scanPending = false
            scan()
        }, SCAN_DELAY_MS)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        connected = false
        handler.removeCallbacksAndMessages(null)
        hidePill()
        setCovers(emptyList())
        super.onDestroy()
    }

    private fun scan() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (root == null || pkg == null || pkg in SKIPPED_PACKAGES) {
            setCovers(emptyList())
            if (!noticeShowing) hidePill()
            return
        }
        val screen = Rect().also { root.getBoundsInScreen(it) }
        val passages = ArrayList<Passage>()
        val aiLabel = booleanArrayOf(false)
        collect(root, passages, screen, 0, intArrayOf(0), aiLabel)

        val flagged = ArrayList<Pair<Passage, Scoring.Classification>>()
        val seen = HashSet<String>()
        for (p in passages) {
            if (!seen.add(p.text)) continue
            val c = Scoring.classify(p.text)
            if (c.kind != null) flagged.add(p to c)
        }
        val hide = Prefs.hideFlagged(this)
        val toCover = if (hide) flagged.filter { it.first.text !in revealed }.take(MAX_COVERS) else emptyList()
        setCovers(toCover)

        if (noticeShowing) return
        val pressure = Scoring.urgencySignals(passages.joinToString("\n") { it.text }).isNotEmpty()
        val parts = ArrayList<String>()
        for ((kind, name) in KIND_NAMES) {
            val n = flagged.count { it.second.kind == kind }
            if (n > 0) parts.add("$n $name")
        }
        if (toCover.isNotEmpty()) parts.add("${toCover.size} hidden")
        if (aiLabel[0]) parts.add("AI label on screen")
        if (pressure) parts.add("pressure wording")
        if (parts.isEmpty() || pillDismissed) hidePill() else showPill(parts.joinToString(" · "))
    }

    private fun collect(
        node: AccessibilityNodeInfo, out: MutableList<Passage>, screen: Rect, depth: Int, visited: IntArray,
        aiLabel: BooleanArray,
    ) {
        if (depth > MAX_DEPTH || visited[0] >= MAX_NODES) return
        visited[0] += 1
        // Never read passwords or what the user is typing.
        if (!node.isPassword && !node.isEditable && node.isVisibleToUser) {
            val text = node.text?.toString()?.trim()
            // Short badges such as "AI info" are labels the app itself displays.
            if (!aiLabel[0] && (Scoring.isAiLabel(text) || Scoring.isAiLabel(node.contentDescription?.toString()))) {
                aiLabel[0] = true
            }
            if (text != null && text.length >= MIN_CHARS) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (bounds.intersect(screen)) out.add(Passage(text.take(MAX_CHARS), bounds))
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out, screen, depth + 1, visited, aiLabel)
        }
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun windowManager(): WindowManager = getSystemService(WindowManager::class.java)

    private fun coverParams(bounds: Rect) = WindowManager.LayoutParams(
        bounds.width(),
        bounds.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = bounds.left
        y = bounds.top
    }

    /** Makes the covers on screen match [wanted]: move, add and remove as needed. */
    private fun setCovers(wanted: List<Pair<Passage, Scoring.Classification>>) {
        val wm = windowManager()
        val keep = wanted.map { it.first.text }.toSet()
        val stale = covers.keys.filter { it !in keep }
        for (key in stale) removeCover(key)
        for ((passage, result) in wanted) {
            val existing = covers[passage.text]
            if (existing != null) {
                if (existing.tag != passage.bounds) {
                    existing.tag = Rect(passage.bounds)
                    try {
                        wm.updateViewLayout(existing, coverParams(passage.bounds))
                    } catch (e: IllegalArgumentException) {
                        covers.remove(passage.text)
                    }
                }
                continue
            }
            val view = TextView(this).apply {
                text = "Hidden: ${result.reason}. Tap to show."
                textSize = 13f
                gravity = Gravity.CENTER
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(0xFF1A1A1A.toInt())
                setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
                background = GradientDrawable().apply {
                    setColor(0xFFFFFFFF.toInt())
                    cornerRadius = dp(6f).toFloat()
                    setStroke(dp(1f), 0xFFA12B1E.toInt())
                }
                tag = Rect(passage.bounds)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setOnClickListener {
                    if (revealed.size >= MAX_REVEALED) revealed.remove(revealed.first())
                    revealed.add(passage.text)
                    removeCover(passage.text)
                }
            }
            try {
                wm.addView(view, coverParams(passage.bounds))
                covers[passage.text] = view
            } catch (e: WindowManager.BadTokenException) {
                // The service is shutting down; nothing to show.
            }
        }
    }

    private fun removeCover(key: String) {
        val view = covers.remove(key) ?: return
        try {
            windowManager().removeView(view)
        } catch (e: IllegalArgumentException) {
            // Already detached.
        }
    }

    private fun showPill(text: String) {
        val existing = pill
        if (existing != null) {
            existing.text = text
            return
        }
        val view = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF1A1A1A.toInt())
            setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
            background = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = dp(16f).toFloat()
                setStroke(dp(1f), 0xFFA12B1E.toInt())
            }
            setOnClickListener {
                pillDismissed = true
                noticeShowing = false
                hidePill()
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(16f)
            y = dp(96f)
        }
        try {
            windowManager().addView(view, params)
            pill = view
        } catch (e: WindowManager.BadTokenException) {
            // The service is shutting down; nothing to show.
        }
    }

    private fun hidePill() {
        val view = pill ?: return
        pill = null
        try {
            windowManager().removeView(view)
        } catch (e: IllegalArgumentException) {
            // Already detached.
        }
    }

    companion object {
        /** True while Android has this service bound and running. */
        @Volatile
        var connected = false
            private set

        private const val NOTICE_MS = 4000L
        private const val SCAN_DELAY_MS = 300L
        private const val MAX_NODES = 600
        private const val MAX_DEPTH = 40
        private const val MIN_CHARS = 20
        private const val MAX_CHARS = 2000
        private const val MAX_COVERS = 12
        private const val MAX_REVEALED = 200
        private val SKIPPED_PACKAGES = setOf("com.android.systemui")
        private val KIND_NAMES = linkedMapOf("adult" to "adult", "scam" to "scam-style", "charge" to "high-charge")
    }
}
