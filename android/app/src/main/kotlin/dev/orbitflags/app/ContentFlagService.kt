package dev.orbitflags.app

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import dev.orbitflags.core.ScamTracker
import dev.orbitflags.core.Scoring

/**
 * Checks whichever app is in front. Text is read through the accessibility
 * tree; text inside images is read by capturing the screen. Flagged passages
 * are covered by a notice until the user taps it, and a small pill summarises
 * what was found. Everything is analysed on the device; nothing is stored or sent.
 */
class ContentFlagService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var scanPending = false
    private var pill: TextView? = null
    private var banner: TextView? = null
    private var bannerKey: String? = null
    private lateinit var chats: ChatWatch
    private var pillDismissed = false
    private var noticeShowing = false

    /** Covers currently on screen, keyed by [Finding.key]. */
    private val covers = HashMap<String, TextView>()

    /** Content the user chose to see; it stays visible while the service runs. */
    private val revealed = LinkedHashSet<String>()

    private var textFindings: List<Finding> = emptyList()
    private var textAiLabel = false
    private var pressure = false

    private var vision: ScreenVision? = null
    private var visionFindings: List<Finding> = emptyList()
    private var visionAiLabel = false
    private var capturing = false
    private var screenChanged = true
    private var lastCaptureAt = 0L
    private var screen = Rect()
    private var pictures: List<Rect> = emptyList()

    /** Confirms the service is running, since most screens produce no flag. */
    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        chats = ChatWatch(this)
        vision = ScreenVision(this)
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
        screenChanged = true
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) pillDismissed = false
        // Where captures include this app's covers, what is under a cover cannot
        // be re-read, so findings from images are dropped as soon as the screen moves.
        if (vision?.capturesWindowOnly == false && visionFindings.isNotEmpty() &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            visionFindings = emptyList()
        }
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
        hideBanner()
        setCovers(emptyList())
        vision?.close()
        vision = null
        super.onDestroy()
    }

    private fun scan() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (root == null || pkg == null || pkg in SKIPPED_PACKAGES) {
            textFindings = emptyList()
            visionFindings = emptyList()
            textAiLabel = false
            visionAiLabel = false
            pressure = false
            hideBanner()
            render()
            return
        }
        screen = Rect().also { root.getBoundsInScreen(it) }
        val passages = ArrayList<Pair<String, Rect>>()
        val images = ArrayList<Rect>()
        val aiLabel = booleanArrayOf(false)
        val chat = ChatScreen()
        collect(root, passages, images, 0, intArrayOf(0), aiLabel, chat)
        watchChat(pkg, chat, passages.map { it.first })
        pictures = images.sortedByDescending { it.width().toLong() * it.height() }.take(MAX_PICTURES)

        val found = ArrayList<Finding>()
        val seen = HashSet<String>()
        for ((text, bounds) in passages) {
            if (!seen.add(text)) continue
            val c = Scoring.classify(text)
            val kind = c.kind ?: continue
            found.add(Finding("t:$text", bounds, kind, c.reason))
        }
        textFindings = found
        textAiLabel = aiLabel[0]
        pressure = Scoring.urgencySignals(passages.joinToString("\n") { it.first }).isNotEmpty()
        render()
        maybeCapture(root.windowId)
    }

    private fun collect(
        node: AccessibilityNodeInfo, out: MutableList<Pair<String, Rect>>, images: MutableList<Rect>,
        depth: Int, visited: IntArray, aiLabel: BooleanArray, chat: ChatScreen,
    ) {
        if (depth > MAX_DEPTH || visited[0] >= MAX_NODES) return
        visited[0] += 1
        // Remember where pictures and videos are, so the screen capture can check them.
        val type = node.className?.toString() ?: ""
        if (node.isVisibleToUser && PICTURE_TYPES.any { type.endsWith(it) }) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (bounds.intersect(screen) && bounds.width() >= dp(MIN_PICTURE_DP) && bounds.height() >= dp(MIN_PICTURE_DP)) {
                images.add(bounds)
            }
        }
        // A message box marks a chat screen; its contents are never read.
        if (node.isEditable) chat.hasMessageBox = true
        // Never read passwords or what the user is typing.
        if (!node.isPassword && !node.isEditable && node.isVisibleToUser) {
            // Many apps (Instagram, YouTube) put post text in the description, not the text field.
            val text = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: node.contentDescription?.toString()?.trim()
            // The first short text near the top of a chat screen is taken as its title.
            if (chat.title == null && text != null && text.length in 2..40) {
                val at = Rect().also { node.getBoundsInScreen(it) }
                if (at.top < screen.top + screen.height() * TITLE_BAND) chat.title = text
            }
            // Short badges such as "AI info" are labels the app itself displays.
            if (!aiLabel[0] && Scoring.isAiLabel(text)) aiLabel[0] = true
            if (text != null && text.length >= Finding.MIN_CHARS) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (bounds.intersect(screen)) out.add(text.take(MAX_CHARS) to bounds)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, out, images, depth + 1, visited, aiLabel, chat)
        }
    }

    private class ChatScreen {
        var hasMessageBox = false
        var title: String? = null
    }

    /**
     * Follows long-running scam stages in chat screens. Only stage markers are
     * stored, under a one-way hash of the app and chat title.
     */
    private fun watchChat(pkg: String, chat: ChatScreen, texts: List<String>) {
        val sample = pkg == packageName
        if (!Prefs.watchChats(this) || !chat.hasMessageBox || !(sample || pkg in ChatWatch.CHAT_APPS)) {
            hideBanner()
            return
        }
        val now = System.currentTimeMillis()
        val key = ScamTracker.conversationKey(pkg, chat.title ?: "")
        // This app's own sample is never stored.
        val before = if (sample) sampleState else chats.load(key)
        val after = ScamTracker.observe(before, texts, now)
        if (after !== before) {
            if (sample) sampleState = after else chats.save(key, after)
        }
        if (sample && ScamTracker.stagesIn(texts).isEmpty()) sampleState = ScamTracker.State()
        val assessment = ScamTracker.assess(if (sample) sampleState else after, now)
        if (assessment.warn) {
            showBanner(key, sample, ScamTracker.describe(assessment) +
                " Do not send money or share codes. Tap to hide this for a week.")
        } else {
            hideBanner()
        }
    }

    private var sampleState = ScamTracker.State()

    private fun showBanner(key: String, sample: Boolean, text: String) {
        val existing = banner
        if (existing != null && bannerKey == key) {
            if (existing.text.toString() != text) existing.text = text
            return
        }
        hideBanner()
        val view = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            background = GradientDrawable().apply {
                setColor(0xFFA12B1E.toInt())
                cornerRadius = dp(10f).toFloat()
            }
            setOnClickListener {
                val now = System.currentTimeMillis()
                if (sample) sampleState = ScamTracker.dismiss(sampleState, now)
                else chats.save(key, ScamTracker.dismiss(chats.load(key), now))
                hideBanner()
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = dp(64f)
            horizontalMargin = 0.03f
        }
        try {
            windowManager().addView(view, params)
            banner = view
            bannerKey = key
        } catch (e: WindowManager.BadTokenException) {
            // The service is shutting down; nothing to show.
        }
    }

    private fun hideBanner() {
        val view = banner ?: return
        banner = null
        bannerKey = null
        try {
            windowManager().removeView(view)
        } catch (e: IllegalArgumentException) {
            // Already detached.
        }
    }

    /** Captures the screen to read text inside images, when the screen has changed. */
    private fun maybeCapture(windowId: Int) {
        val v = vision ?: return
        if (!v.supported || capturing || !screenChanged || noticeShowing || !Prefs.readImages(this)) return
        val now = SystemClock.uptimeMillis()
        if (now - lastCaptureAt < CAPTURE_INTERVAL_MS) {
            // Come back once the interval has passed, so the last change is not missed.
            if (!scanPending) {
                scanPending = true
                handler.postDelayed({
                    scanPending = false
                    scan()
                }, CAPTURE_INTERVAL_MS - (now - lastCaptureAt))
            }
            return
        }
        capturing = true
        screenChanged = false
        lastCaptureAt = now
        // Where the capture includes this app's own overlays, skip those areas.
        val ignore = if (v.capturesWindowOnly) emptyList() else overlayRects()
        val keep = if (v.capturesWindowOnly) emptyList() else visionFindings.filter { it.key in covers }
        v.analyse(windowId, Rect(screen), pictures, ignore, Prefs.checkPictures(this)) { result ->
            handler.post {
                capturing = false
                if (!connected) return@post
                if (result != null) {
                    visionFindings = keep + result.findings.filter { f -> keep.none { it.key == f.key } }
                    visionAiLabel = result.aiLabel
                    render()
                }
                // The screen may have moved on while this capture was being analysed.
                if (screenChanged && !scanPending) {
                    scanPending = true
                    handler.postDelayed({
                        scanPending = false
                        scan()
                    }, SCAN_DELAY_MS)
                }
            }
        }
    }

    private fun overlayRects(): List<Rect> {
        val rects = ArrayList<Rect>()
        for (view in covers.values) (view.tag as? Rect)?.let { rects.add(it) }
        for (view in listOfNotNull(pill, banner)) {
            val at = IntArray(2)
            view.getLocationOnScreen(at)
            rects.add(Rect(at[0], at[1], at[0] + view.width, at[1] + view.height))
        }
        return rects
    }

    /** Brings covers and the pill in line with the current findings. */
    private fun render() {
        // Text read from an image that overlaps a passage already found as text is a duplicate.
        val fromImages = visionFindings.filter { v -> textFindings.none { overlapsMostly(v.bounds, it.bounds) } }
        val all = dropNested(textFindings + fromImages)
        val screenArea = screen.width().toLong() * screen.height()
        val hide = Prefs.hideFlagged(this)
        val toCover = if (!hide) emptyList() else all.filter { f ->
            // Text covers stay small; a flagged picture is covered whatever its size.
            f.key !in revealed && (f.key.startsWith("i:") ||
                f.bounds.width().toLong() * f.bounds.height() <= screenArea * MAX_COVER_SHARE)
        }.take(MAX_COVERS)
        setCovers(toCover)

        if (noticeShowing) return
        val parts = ArrayList<String>()
        for ((kind, name) in KIND_NAMES) {
            val n = all.count { it.kind == kind }
            if (n > 0) parts.add("$n $name")
        }
        if (toCover.isNotEmpty()) parts.add("${toCover.size} hidden")
        if (textAiLabel || visionAiLabel) parts.add("AI label on screen")
        if (pressure) parts.add("pressure wording")
        if (parts.isEmpty() || pillDismissed) hidePill() else showPill(parts.joinToString(" · "))
    }

    private fun overlapsMostly(a: Rect, b: Rect): Boolean {
        val i = Rect(a)
        if (!i.intersect(b)) return false
        return i.width().toLong() * i.height() * 2 >= a.width().toLong() * a.height()
    }

    /** A card and the title inside it can both be flagged; keep the outer one. */
    private fun dropNested(findings: List<Finding>): List<Finding> =
        findings.filter { f -> findings.none { it !== f && it.bounds != f.bounds && it.bounds.contains(f.bounds) } }

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
    private fun setCovers(wanted: List<Finding>) {
        val wm = windowManager()
        val keep = wanted.map { it.key }.toSet()
        for (key in covers.keys.filter { it !in keep }) removeCover(key)
        for (finding in wanted) {
            val existing = covers[finding.key]
            if (existing != null) {
                if (existing.tag != finding.bounds) {
                    existing.tag = Rect(finding.bounds)
                    try {
                        wm.updateViewLayout(existing, coverParams(finding.bounds))
                    } catch (e: IllegalArgumentException) {
                        covers.remove(finding.key)
                    }
                }
                continue
            }
            val view = TextView(this).apply {
                text = "Hidden: ${finding.reason}. Tap to show."
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
                tag = Rect(finding.bounds)
                setOnClickListener {
                    if (revealed.size >= MAX_REVEALED) revealed.remove(revealed.first())
                    revealed.add(finding.key)
                    removeCover(finding.key)
                    render()
                }
            }
            try {
                wm.addView(view, coverParams(finding.bounds))
                covers[finding.key] = view
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
        private const val CAPTURE_INTERVAL_MS = 1200L
        private const val MAX_NODES = 1500
        private const val MAX_DEPTH = 50
        private const val MAX_CHARS = 2000
        private const val MAX_COVERS = 12
        private const val MAX_REVEALED = 200
        /** A cover never takes more than this share of the screen. */
        private const val MAX_COVER_SHARE = 0.7
        /** A chat's title is looked for in this top share of the screen. */
        private const val TITLE_BAND = 0.16
        private const val MAX_PICTURES = 8
        private const val MIN_PICTURE_DP = 96f
        /** Accessibility class names used for pictures and video, in apps and in browsers. */
        private val PICTURE_TYPES = listOf("ImageView", "Image", "VideoView", "TextureView", "SurfaceView")
        private val SKIPPED_PACKAGES = setOf("com.android.systemui")
        private val KIND_NAMES = linkedMapOf(
            "adult" to "adult", "scam" to "scam-style", "charge" to "high-charge", "hook" to "engagement-bait",
        )
    }
}
