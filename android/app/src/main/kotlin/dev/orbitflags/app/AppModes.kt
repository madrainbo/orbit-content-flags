package dev.orbitflags.app

import android.content.Context
import dev.orbitflags.core.AppPolicy

/** The user's per-app choices of how flags are shown, kept on this phone. */
object AppModes {
    private const val FILE = "ocf_apps"

    @Volatile private var cache: Map<String, String>? = null

    private fun all(context: Context): Map<String, String> = cache ?: synchronized(this) {
        val loaded = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).all
            .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
        cache = loaded
        loaded
    }

    fun mode(context: Context, appPackage: String): AppPolicy.Mode =
        AppPolicy.mode(appPackage, all(context)[appPackage])

    fun set(context: Context, appPackage: String, mode: AppPolicy.Mode) = synchronized(this) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        // Storing nothing for the default keeps future default changes effective.
        if (mode == AppPolicy.defaultMode(appPackage)) prefs.edit().remove(appPackage).apply()
        else prefs.edit().putString(appPackage, mode.name).apply()
        cache = null
    }

    /** Apps the user has set to something other than the default. */
    fun chosen(context: Context): List<String> = all(context).keys.sorted()

    fun appName(context: Context, appPackage: String): String = try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(appPackage, 0)).toString()
    } catch (e: Exception) {
        appPackage
    }

    fun installed(context: Context, appPackage: String): Boolean = try {
        context.packageManager.getApplicationInfo(appPackage, 0)
        true
    } catch (e: Exception) {
        false
    }
}
