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

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** True when capture shows only the app window, without this app's own covers. */
    val capturesWindowOnly: Boolean get() = Build.VERSION.SDK_INT >= 34

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 30

    /**
     * Captures and analyses once. [windowId] and [windowBounds] describe the app
     * window in front; [ignore] lists screen areas to skip (this app's own
     * overlays, when they are part of the capture). [done] runs on the worker
     * thread with null if the screen could not be captured.
     */
    fun analyse(windowId: Int, windowBounds: Rect, ignore: List<Rect>, done: (Result?) -> Unit) {
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
                    read(bitmap, offset.left, offset.top, ignore)
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

    private fun read(bitmap: Bitmap, dx: Int, dy: Int, ignore: List<Rect>): Result {
        // Recognise on a reduced copy: large enough for post and caption text, far cheaper to process.
        val longest = maxOf(bitmap.width, bitmap.height)
        val scale = if (longest > MAX_SIDE) MAX_SIDE.toFloat() / longest else 1f
        val input = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        val text = try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(input, 0)), 30, TimeUnit.SECONDS)
        } finally {
            if (input !== bitmap) input.recycle()
        }
        val findings = ArrayList<Finding>()
        var aiLabel = false
        for (block in text.textBlocks) {
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
        // Counts only: what was read is never logged.
        Log.d(TAG, "capture ${bitmap.width}x${bitmap.height}: ${text.textBlocks.size} text blocks, ${findings.size} flagged")
        return Result(findings, aiLabel)
    }

    private companion object {
        const val TAG = "OrbitVision"
        const val MAX_SIDE = 1600
    }

    fun close() {
        worker.shutdownNow()
        recognizer.close()
    }
}
