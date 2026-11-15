package dev.orbitflags.app

import android.content.Context
import android.util.Log
import dev.orbitflags.core.TrustedSources
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Decides whether flagged content comes from a known, notable source.
 *
 * First the bundled list (tools/trusted, from Wikidata) is checked on the
 * phone. If the poster's username is not in it and live lookup is on, the
 * username alone is looked up on Wikidata: nothing else from the screen is
 * sent. Results are cached for 30 days and lookups are rate-limited.
 */
class SourceCheck(private val context: Context, onLoaded: () -> Unit) {
    @Volatile var trusted: TrustedSources? = null
        private set

    private val worker = Executors.newSingleThreadExecutor()
    private val prefs get() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val pending = HashSet<String>()
    private val recentLookups = ArrayDeque<Long>()
    /** Usernames whose lookup failed (no network, refused): not retried until this time. */
    private val failedUntil = HashMap<String, Long>()

    init {
        worker.execute {
            trusted = try {
                TrustedSources(context.assets.open(ASSET).bufferedReader().use { it.readText() })
            } catch (e: Exception) {
                Log.d(TAG, "trusted list unavailable: " + e.javaClass.simpleName)
                null
            }
            Log.d(TAG, "trusted list loaded: ${trusted?.size ?: 0} entries")
            // Anything checked before the list was ready is checked again.
            onLoaded()
        }
    }

    /**
     * A known source for [handle] in [appPackage]: from the bundled list, or
     * from a cached lookup. If neither knows and lookups are allowed, one is
     * started and [onFound] runs when it finishes.
     */
    fun account(handle: String, appPackage: String, onFound: () -> Unit): TrustedSources.Source? {
        if (handle.startsWith(TrustedSources.WEAK)) return null
        trusted?.account(handle)?.let { return it }
        val key = "$appPackage|$handle"
        val cached = prefs.getString(key, null)
        if (cached != null) {
            val parts = cached.split('\t')
            val fresh = (parts.getOrNull(0)?.toLongOrNull() ?: 0) > System.currentTimeMillis() - CACHE_MS
            if (fresh) return if (parts.size >= 4) TrustedSources.Source(parts[1], parts[2], parts[3]) else null
        }
        if (!Prefs.liveLookup(context)) return null
        synchronized(pending) {
            if ((failedUntil[key] ?: 0) > System.currentTimeMillis()) return null
            if (!pending.add(key)) return null
        }
        if (worker.isShutdown) return null
        worker.execute {
            try {
                if (allowRequest()) {
                    val found = lookUp(handle, appPackage)
                    val stamp = System.currentTimeMillis().toString()
                    prefs.edit().putString(key, if (found == null) stamp
                        else listOf(stamp, found.label, found.category, found.wikidata).joinToString("\t")).apply()
                    if (found != null) onFound()
                }
            } catch (e: Exception) {
                Log.d(TAG, "lookup failed: " + e.javaClass.simpleName)
                synchronized(pending) { failedUntil[key] = System.currentTimeMillis() + RETRY_AFTER_MS }
            } finally {
                synchronized(pending) { pending.remove(key) }
            }
        }
        return null
    }

    private fun allowRequest(): Boolean {
        val now = System.currentTimeMillis()
        while (recentLookups.isNotEmpty() && recentLookups.first() < now - 60 * 60 * 1000) recentLookups.removeFirst()
        if (recentLookups.size >= MAX_LOOKUPS_PER_HOUR) return false
        recentLookups.addLast(now)
        return true
    }

    private fun get(params: Map<String, String>): JSONObject {
        val query = params.entries.joinToString("&") { it.key + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val conn = URL("$API?$query").openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", AGENT)
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        try {
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** Looks the username up on Wikidata. Only the username is sent. */
    private fun lookUp(handle: String, appPackage: String): TrustedSources.Source? {
        val props = PROPERTY_BY_APP[appPackage] ?: listOf("P2003", "P2002", "P7085", "P11245")
        val search = get(mapOf(
            "action" to "query", "list" to "search", "format" to "json", "srlimit" to "3", "srprop" to "",
            "srsearch" to "haswbstatement:" + props.joinToString("|") { "$it=$handle" },
        )).optJSONObject("query")?.optJSONArray("search") ?: return null
        val ids = (0 until search.length()).map { search.getJSONObject(it).getString("title") }
        if (ids.isEmpty()) return null
        val entities = get(mapOf(
            "action" to "wbgetentities", "format" to "json", "ids" to ids.joinToString("|"),
            "props" to "labels|claims|sitelinks", "languages" to "en",
        )).optJSONObject("entities") ?: return null
        for (id in ids) {
            val e = entities.optJSONObject(id) ?: continue
            val claims = e.optJSONObject("claims") ?: continue
            // A notable organisation or work: many Wikipedia articles, not a person, still running.
            if ((e.optJSONObject("sitelinks")?.length() ?: 0) < MIN_SITELINKS) continue
            if (values(claims, "P31").contains("Q5")) continue
            if (claims.has("P576")) continue
            // The username must be a current (not deprecated) statement on the item.
            val matches = props.any { p ->
                val list = claims.optJSONArray(p) ?: return@any false
                (0 until list.length()).any { i ->
                    val c = list.getJSONObject(i)
                    c.optString("rank") != "deprecated" &&
                        c.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value")
                            ?.removePrefix("@")?.equals(handle, ignoreCase = true) == true
                }
            }
            if (!matches) continue
            val label = e.optJSONObject("labels")?.optJSONObject("en")?.optString("value") ?: id
            return TrustedSources.Source(label, "notable organisation or work", id)
        }
        return null
    }

    private fun values(claims: JSONObject, prop: String): List<String> {
        val list = claims.optJSONArray(prop) ?: return emptyList()
        return (0 until list.length()).mapNotNull {
            list.getJSONObject(it).optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("id")
        }
    }

    fun close() = worker.shutdown()

    companion object {
        private const val TAG = "OrbitSources"
        private const val ASSET = "trusted.tsv"
        private const val FILE = "ocf_sources"
        private const val API = "https://www.wikidata.org/w/api.php"
        private const val AGENT = "OrbitContentFlags/0.11 (Android; on-device source check)"
        private const val CACHE_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_LOOKUPS_PER_HOUR = 20
        private const val MIN_SITELINKS = 10
        private const val RETRY_AFTER_MS = 15L * 60 * 1000

        /** The Wikidata property holding a username on each platform. */
        private val PROPERTY_BY_APP = mapOf(
            "com.instagram.android" to listOf("P2003"),
            "com.twitter.android" to listOf("P2002"),
            "com.zhiliaoapp.musically" to listOf("P7085"),
            "com.ss.android.ugc.trill" to listOf("P7085"),
            "com.google.android.youtube" to listOf("P11245"),
        )

        /** Video apps, where a one-word TV title on screen is meaningful. */
        val VIDEO_APPS = setOf(
            "com.google.android.youtube", "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient",
            "com.disney.disneyplus", "com.hulu.plus", "com.wbd.stream", "com.hbo.hbonow", "in.startv.hotstar",
            "com.jio.media.ondemand", "com.apple.atve.androidtv.appletv", "com.peacocktv.peacockandroid",
            "com.cbs.app", "com.google.android.videos", "com.bbc.iplayer.android", "com.itv.loveislandapp",
        )

        /** Address-bar elements of common browsers, used to know which site a page is on. */
        val URL_BAR_IDS = setOf(
            "com.android.chrome:id/url_bar",
            "com.chrome.beta:id/url_bar",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.microsoft.emmx:id/url_bar",
            "com.brave.browser:id/url_bar",
            "com.opera.browser:id/url_field",
        )
    }
}
