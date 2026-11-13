package dev.orbitflags.app

import android.content.Context
import dev.orbitflags.core.Domains

/** Domain lists, kept on the device in the app's private storage. */
object Prefs {
    private const val FILE = "ocf"

    fun text(context: Context, key: String): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key, "") ?: ""

    fun list(context: Context, key: String): List<String> = Domains.parseList(text(context, key)).valid

    /** Whether flagged passages are covered until tapped (default on). */
    fun hideFlagged(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("hide_flagged", true)

    fun setHideFlagged(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("hide_flagged", value).apply()
    }

    /** Whether the screen is captured to read text inside images (default on). */
    fun readImages(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("read_images", true)

    fun setReadImages(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("read_images", value).apply()
    }

    /** Whether pictures on screen are checked for adult content (default on). */
    fun checkPictures(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("check_pictures", true)

    fun setCheckPictures(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("check_pictures", value).apply()
    }

    /** Whether chat screens are watched for long-running scam stages (default on). */
    fun watchChats(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("watch_chats", true)

    fun setWatchChats(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("watch_chats", value).apply()
    }

    fun save(context: Context, block: List<String>, allow: List<String>) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("block", block.joinToString("\n"))
            .putString("allow", allow.joinToString("\n"))
            .apply()
    }
}
