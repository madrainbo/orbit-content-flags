package dev.orbitflags.core

/**
 * How flags are shown in a given app. In a feed, covering flagged content
 * protects the user from things they did not ask for. In an assistant app the
 * user asked for the content, and answers naturally discuss scams, money and
 * other trigger topics, so covering only gets in the way.
 */
object AppPolicy {
    enum class Mode(val label: String) {
        HIDE("hide flagged content"),
        PILL("show the pill only, hide nothing"),
        OFF("do nothing in this app");

        fun next(): Mode = entries[(ordinal + 1) % entries.size]
    }

    /** AI assistant and answer apps, where content is requested rather than pushed. */
    val ASSISTANTS = setOf(
        "com.anthropic.claude",
        "com.openai.chatgpt",
        "com.google.android.apps.bard",
        "ai.perplexity.app.android",
        "com.microsoft.copilot",
        "ai.x.grok",
        "com.deepseek.chat",
        "ai.mistral.chat",
        "com.poe.android",
    )

    fun defaultMode(appPackage: String): Mode = if (appPackage in ASSISTANTS) Mode.PILL else Mode.HIDE

    /** The mode for an app: the user's choice if they made one, else the default. */
    fun mode(appPackage: String, chosen: String?): Mode =
        Mode.entries.firstOrNull { it.name == chosen } ?: defaultMode(appPackage)
}
