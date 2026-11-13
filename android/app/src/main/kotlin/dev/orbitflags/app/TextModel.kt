package dev.orbitflags.app

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder.TextEmbedderOptions
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.exp

/**
 * Judges a passage as a whole, on the device, so that scam-style, adult,
 * engagement-bait and charged posts are caught even when they avoid the exact
 * phrases in the rules. A small sentence-embedding model (Universal Sentence
 * Encoder, bundled) turns the passage into numbers, and a classifier trained
 * by tools/textmodel/train.py scores it.
 *
 * Two confidence levels: at [hide] or above the passage is treated like a
 * rule match; between [label] and [hide] it is only mentioned in the pill.
 */
class TextModel(context: Context) {
    class Verdict(val kind: String, val confidence: Float, val hide: Boolean)

    private val embedder: TextEmbedder
    private val kinds: List<String>
    private val weights: Array<FloatArray>
    private val bias: FloatArray
    private val hide: Float
    private val label: Float

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val cache = object : LinkedHashMap<String, Verdict?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Verdict?>) = size > CACHE_SIZE
    }
    private val pending = HashSet<String>()

    init {
        val head = JSONObject(context.assets.open(HEAD).bufferedReader().use { it.readText() })
        val classes = head.getJSONArray("classes")
        kinds = List(classes.length()) { KIND_OF[classes.getString(it)] ?: "" }
        val w = head.getJSONArray("weights")
        weights = Array(w.length()) { r -> w.getJSONArray(r).let { row -> FloatArray(row.length()) { row.getDouble(it).toFloat() } } }
        val b = head.getJSONArray("bias")
        bias = FloatArray(b.length()) { b.getDouble(it).toFloat() }
        hide = head.getDouble("hide").toFloat()
        label = head.getDouble("label").toFloat()
        embedder = TextEmbedder.createFromOptions(
            context,
            TextEmbedderOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(EMBEDDER).build())
                .setL2Normalize(true)
                .build(),
        )
    }

    /** Scores one passage now. Safe to call from any thread. */
    @Synchronized
    fun judge(text: String): Verdict? {
        if (text.length < MIN_CHARS || text.count { it == ' ' } < 3) return null
        val x = embedder.embed(text.take(MAX_CHARS)).embeddingResult().embeddings()[0].floatEmbedding()
        val z = FloatArray(weights.size) { k ->
            var sum = bias[k]
            val row = weights[k]
            for (i in x.indices) sum += row[i] * x[i]
            sum
        }
        val top = z.max()
        val e = FloatArray(z.size) { exp((z[it] - top).toDouble()).toFloat() }
        val total = e.sum()
        var best = -1
        for (k in e.indices) if (kinds[k].isNotEmpty() && (best < 0 || e[k] > e[best])) best = k
        val p = e[best] / total
        return if (p >= label) Verdict(kinds[best], p, p >= hide) else null
    }

    /**
     * The cached verdict for a passage, if it has been scored. Unscored
     * passages are queued; [onReady] runs on the worker when new verdicts land.
     */
    fun lookup(texts: Collection<String>, onReady: () -> Unit): Map<String, Verdict?> {
        val known = HashMap<String, Verdict?>()
        val todo = ArrayList<String>()
        synchronized(cache) {
            for (t in texts) {
                if (cache.containsKey(t)) known[t] = cache[t]
                else if (pending.size < MAX_PENDING && pending.add(t)) todo.add(t)
            }
        }
        if (todo.isNotEmpty()) {
            worker.execute {
                var scored = 0
                for (t in todo) {
                    val v = try {
                        judge(t)
                    } catch (e: Exception) {
                        Log.d(TAG, "text model failed: " + e.javaClass.simpleName)
                        null
                    }
                    synchronized(cache) {
                        cache[t] = v
                        pending.remove(t)
                    }
                    scored += 1
                }
                // Counts only: passages are never logged.
                Log.d(TAG, "scored $scored passages")
                onReady()
            }
        }
        return known
    }

    fun close() {
        worker.shutdownNow()
        embedder.close()
    }

    companion object {
        private const val TAG = "OrbitTextModel"
        private const val EMBEDDER = "text_embedder.tflite"
        private const val HEAD = "text_head.json"
        private const val MIN_CHARS = 20
        private const val MAX_CHARS = 400
        private const val CACHE_SIZE = 600
        private const val MAX_PENDING = 80
        /** Training class name to the kind used elsewhere in the app. */
        private val KIND_OF = mapOf("scam" to "scam", "adult" to "adult", "bait" to "hook", "charge" to "charge")

        val REASONS = mapOf(
            "scam" to "reads like a scam",
            "adult" to "reads like adult content",
            "hook" to "reads like engagement bait",
            "charge" to "reads as highly charged",
        )
    }
}
