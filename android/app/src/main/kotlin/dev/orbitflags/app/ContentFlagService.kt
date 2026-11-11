package dev.orbitflags.app

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import dev.orbitflags.core.Scoring

/**
 * Scores the text of whichever app is in front and shows a small pill over it.
 * All scoring happens here on the device; the text is not stored or sent.
 */
class ContentFlagService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var scanPending = false
    private var pill: TextView? = null
    private var dismissedForWindow = false

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) dismissedForWindow = false
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
        handler.removeCallbacksAndMessages(null)
        hidePill()
        super.onDestroy()
    }

    private fun scan() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (root == null || pkg == null || pkg == packageName || pkg in SKIPPED_PACKAGES) {
            hidePill()
            return
        }
        val texts = LinkedHashSet<String>()
        collect(root, texts, 0, intArrayOf(0))

        val high = texts.count { Scoring.scoreEmotion(it).tier == "high" }
        val pressure = Scoring.urgencySignals(texts.joinToString("\n")).isNotEmpty()
        val parts = ArrayList<String>()
        if (high > 0) parts.add(if (high == 1) "1 high-charge passage" else "$high high-charge passages")
        if (pressure) parts.add("pressure wording")
        if (parts.isEmpty() || dismissedForWindow) hidePill() else showPill(parts.joinToString(" · "))
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableSet<String>, depth: Int, visited: IntArray) {
        if (depth > MAX_DEPTH || visited[0] >= MAX_NODES) return
        visited[0] += 1
        // Never read passwords or what the user is typing.
        if (!node.isPassword && !node.isEditable) {
            val text = node.text?.toString()?.trim()
            if (text != null && text.length >= MIN_CHARS) out.add(text.take(MAX_CHARS))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out, depth + 1, visited)
        }
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

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
            contentDescription = "Content flags: $text. Tap to hide."
            setOnClickListener {
                dismissedForWindow = true
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
            getSystemService(WindowManager::class.java).addView(view, params)
            pill = view
        } catch (e: WindowManager.BadTokenException) {
            // The service is shutting down; nothing to show.
        }
    }

    private fun hidePill() {
        val view = pill ?: return
        pill = null
        try {
            getSystemService(WindowManager::class.java).removeView(view)
        } catch (e: IllegalArgumentException) {
            // Already detached.
        }
    }

    private companion object {
        const val SCAN_DELAY_MS = 700L
        const val MAX_NODES = 600
        const val MAX_DEPTH = 40
        const val MIN_CHARS = 20
        const val MAX_CHARS = 2000
        val SKIPPED_PACKAGES = setOf("com.android.systemui")
    }
}
