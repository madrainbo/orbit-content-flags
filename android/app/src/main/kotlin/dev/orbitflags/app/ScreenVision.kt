package dev.orbitflags.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dev.orbitflags.core.Scoring
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Captures the screen and reads the text inside images on the device. The
 * captured picture lives only in memory for the length of one analysis and is
 * then discarded; it is never written to storage or sent anywhere.
 */
class ScreenVision(private val service: AccessibilityService) {
    class Result(val findings: List<Finding>, val aiLabel: Boolean)

    /** Null if the model could not be loaded; text is still read. */
    private val classifier: ImageClassifier? = try {
        ImageClassifier(service)
    } catch (e: Exception) {
        Log.d(TAG, "image model unavailable: " + e.javaClass.simpleName)
        null
    }

    /** Fingerprints of pictures seen recently, so the same picture keeps the same identity. */
    private val knownPictures = ArrayList<Long>()

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** True when capture shows only the app window, without this app's own covers. */
    val capturesWindowOnly: Boolean get() = Build.VERSION.SDK_INT >= 34

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 30

    /**
     * Captures and analyses once. [windowId] and [windowBounds] describe the app
     * window in front; [pictures] lists where images are on screen; [ignore]
     * lists screen areas to skip (this app's own overlays, when they are part
     * of the capture). [done] runs on the worker
     * thread with null if the screen could not be captured.
     */
    fun analyse(
        windowId: Int, windowBounds: Rect, pictures: List<Rect>, ignore: List<Rect>, checkPictures: Boolean,
        done: (Result?) -> Unit,
    ) {
        if (!supported) {
            done(null)
            return
        }
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val buffer = screenshot.hardwareBuffer
                val bitmap = try {
                    Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    buffer.close()
                }
                if (bitmap == null) {
                    Log.d(TAG, "capture gave no bitmap")
                    done(null)
                    return
                }
                val offset = if (capturesWindowOnly) windowBounds else Rect()
                val result = try {
                    read(bitmap, offset.left, offset.top, if (checkPictures) pictures else emptyList(), ignore)
                } catch (e: Exception) {
                    Log.d(TAG, "analysis failed: " + e.javaClass.simpleName)
                    null
                } finally {
                    bitmap.recycle()
                }
                done(result)
            }

            override fun onFailure(errorCode: Int) {
                // Secure windows (banking, DRM video) and rate limits end up here.
                Log.d(TAG, "capture failed, code $errorCode")
                done(null)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                service.takeScreenshotOfWindow(windowId, worker, callback)
            } else if (Build.VERSION.SDK_INT >= 30) {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, worker, callback)
            }
        } catch (e: RuntimeException) {
            Log.d(TAG, "capture refused: " + e.javaClass.simpleName)
            worker.execute { done(null) }
        }
    }

    private fun read(bitmap: Bitmap, dx: Int, dy: Int, pictures: List<Rect>, ignore: List<Rect>): Result {
        // Recognise on a reduced copy: large enough for post and caption text, far cheaper to process.
        val longest = maxOf(bitmap.width, bitmap.height)
        val scale = if (longest > MAX_SIDE) MAX_SIDE.toFloat() / longest else 1f
        val input = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        // If recognition fails or is too slow, pictures are still checked below.
        val blocks = try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(input, 0)), 20, TimeUnit.SECONDS).textBlocks
        } catch (e: Exception) {
            Log.d(TAG, "text recognition failed: " + e.javaClass.simpleName)
            emptyList()
        } finally {
            if (input !== bitmap) input.recycle()
        }
        val findings = ArrayList<Finding>()
        var aiLabel = false
        for (block in blocks) {
            val box = block.boundingBox ?: continue
            val bounds = Rect(
                (box.left / scale).toInt() + dx, (box.top / scale).toInt() + dy,
                (box.right / scale).toInt() + dx, (box.bottom / scale).toInt() + dy,
            )
            if (ignore.any { Rect.intersects(it, bounds) }) continue
            if (!aiLabel && block.lines.any { Scoring.isAiLabel(it.text) }) aiLabel = true
            val passage = block.text.replace('\n', ' ').trim()
            if (passage.length < Finding.MIN_CHARS) continue
            val c = Scoring.classify(passage)
            val kind = c.kind ?: continue
            findings.add(Finding("o:$passage", bounds, kind, c.reason + ", read from an image"))
        }
        val flaggedText = findings.size
        var checked = 0
        val model = classifier
        if (model != null) {
            for (screenRect in pictures) {
                // Skip a picture only if this app's own overlays hide a good part of it.
                val area = screenRect.width().toLong() * screenRect.height()
                val hidden = ignore.sumOf { o ->
                    val i = Rect(o)
                    if (i.intersect(screenRect)) i.width().toLong() * i.height() else 0L
                }
                if (hidden * 4 >= area) continue
                val r = Rect(screenRect.left - dx, screenRect.top - dy, screenRect.right - dx, screenRect.bottom - dy)
                if (!r.intersect(0, 0, bitmap.width, bitmap.height) || r.width() < 64 || r.height() < 64) continue
                val crop = Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height())
                try {
                    checked += 1
                    if (model.isAdult(crop)) {
                        findings.add(Finding("i:" + identity(crop), screenRect, "adult", "image may show adult content"))
                    }
                } finally {
                    crop.recycle()
                }
            }
        }
        // Counts only: what was read or seen is never logged.
        val summary = "${blocks.size} text blocks read, $flaggedText flagged; " +
            "${pictures.size} pictures found, $checked checked, ${findings.size - flaggedText} flagged"
        Log.d(TAG, "capture ${bitmap.width}x${bitmap.height}: $summary")
        lastSummary = summary
        lastSummaryAt = System.currentTimeMillis()
        return Result(findings, aiLabel)
    }

    /**
     * A 64-bit fingerprint of the picture (which cells are brighter than
     * average on an 8x8 grid). A picture close to one seen before reuses that
     * one's fingerprint, so its cover stays put and it stays revealed once tapped.
     */
    private fun identity(picture: Bitmap): String {
        val small = Bitmap.createScaledBitmap(picture, 8, 8, true)
        val px = IntArray(64)
        small.getPixels(px, 0, 8, 0, 0, 8, 8)
        if (small !== picture) small.recycle()
        val grey = IntArray(64) { ((px[it] shr 16) and 0xFF) + ((px[it] shr 8) and 0xFF) + (px[it] and 0xFF) }
        val mean = grey.average()
        var hash = 0L
        for (i in 0 until 64) if (grey[i] > mean) hash = hash or (1L shl i)
        val known = knownPictures.firstOrNull { java.lang.Long.bitCount(it xor hash) <= 10 }
        if (known != null) return known.toString(16)
        if (knownPictures.size >= 200) knownPictures.removeAt(0)
        knownPictures.add(hash)
        return hash.toString(16)
    }

    companion object {
        private const val TAG = "OrbitVision"
        private const val MAX_SIDE = 1600

        /** What the last screen check did, as counts, for the settings screen. */
        @Volatile var lastSummary: String? = null
            private set
        @Volatile var lastSummaryAt = 0L
            private set
    }

    fun close() {
        worker.shutdownNow()
        recognizer.close()
        classifier?.close()
    }
}
