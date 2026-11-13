package dev.orbitflags.app

import android.content.Context
import dev.orbitflags.core.ScamTracker

/**
 * Keeps long-running scam markers per conversation on this phone. What is
 * stored: a one-way hash identifying the conversation, which stages were
 * seen, and when. No message text, app name or contact name is stored.
 */
class ChatWatch(private val context: Context) {
    private val prefs get() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(key: String): ScamTracker.State = ScamTracker.decode(prefs.getString(key, null))

    fun save(key: String, state: ScamTracker.State) {
        val editor = prefs.edit().putString(key, ScamTracker.encode(state))
        // Forget expired conversations, and the oldest ones beyond the limit.
        val now = System.currentTimeMillis()
        val all = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to ScamTracker.decode(it) } }
            .filter { it.first != key }
        val expired = all.filter { now - it.second.lastActivity > ScamTracker.RETENTION_MS }
        val extra = (all - expired.toSet()).sortedBy { it.second.lastActivity }
            .take(maxOf(0, all.size - expired.size - (MAX_CONVERSATIONS - 1)))
        for ((k, _) in expired + extra) editor.remove(k)
        editor.apply()
    }

    fun count(): Int = prefs.all.size

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val FILE = "ocf_chats"
        private const val MAX_CONVERSATIONS = 300

        /** Messaging and dating apps whose chat screens are watched. */
        val CHAT_APPS = setOf(
            "com.whatsapp", "com.whatsapp.w4b",
            "org.telegram.messenger", "org.thunderdog.challegram",
            "org.thoughtcrime.securesms",
            "com.google.android.apps.messaging", "com.samsung.android.messaging",
            "com.facebook.orca", "com.facebook.mlite",
            "jp.naver.line.android", "com.tencent.mm", "com.viber.voip",
            "com.tinder", "com.bumble.app", "co.hinge.app",
        )
    }
}
