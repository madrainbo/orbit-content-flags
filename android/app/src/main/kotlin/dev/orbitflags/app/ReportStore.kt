package dev.orbitflags.app

import android.content.Context
import dev.orbitflags.core.Entities

/**
 * The user's own reports, kept on this phone:
 *  - phone numbers, accounts and sites they reported, so content mentioning
 *    them is flagged (and reported sites are blocked by domain blocking);
 *  - fingerprints of passages they marked "not a problem", so those are not
 *    flagged again. A fingerprint cannot be turned back into the text.
 */
object ReportStore {
    private const val FILE = "ocf_reports"
    private const val KEY_ENTITIES = "entities"
    private const val KEY_OK = "ok"
    private const val MAX_OK = 2000

    @Volatile private var entities: List<Entities.Entity>? = null
    @Volatile private var ok: Set<String>? = null

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun reported(context: Context): List<Entities.Entity> = entities ?: synchronized(this) {
        val loaded = (prefs(context).getStringSet(KEY_ENTITIES, emptySet()) ?: emptySet())
            .mapNotNull { Entities.Entity.decode(it) }.sortedBy { it.encode() }
        entities = loaded
        loaded
    }

    fun report(context: Context, entity: Entities.Entity) = synchronized(this) {
        val all = (reported(context) + entity).distinct()
        prefs(context).edit().putStringSet(KEY_ENTITIES, all.map { it.encode() }.toSet()).apply()
        entities = all.sortedBy { it.encode() }
    }

    fun withdraw(context: Context, entity: Entities.Entity) = synchronized(this) {
        val all = reported(context) - entity
        prefs(context).edit().putStringSet(KEY_ENTITIES, all.map { it.encode() }.toSet()).apply()
        entities = all
    }

    fun reportedDomains(context: Context): List<String> =
        reported(context).filter { it.type == Entities.Type.DOMAIN }.map { it.value }

    /** The identity under which a finding is remembered as "not a problem". */
    fun fingerprint(key: String): String =
        if (key.startsWith("i:")) key else Entities.fingerprint(key.drop(2))

    fun okFingerprints(context: Context): Set<String> = ok ?: synchronized(this) {
        val loaded = HashSet(prefs(context).getStringSet(KEY_OK, emptySet()) ?: emptySet())
        ok = loaded
        loaded
    }

    /** Remembers that the user said this flag was wrong. */
    fun markOk(context: Context, key: String) = synchronized(this) {
        var all = okFingerprints(context) + fingerprint(key)
        if (all.size > MAX_OK) all = all.drop(all.size - MAX_OK).toSet()
        prefs(context).edit().putStringSet(KEY_OK, all).apply()
        ok = all
    }

    fun falseFlagCount(context: Context): Int = okFingerprints(context).size
}
