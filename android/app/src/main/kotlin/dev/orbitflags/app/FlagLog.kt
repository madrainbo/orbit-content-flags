package dev.orbitflags.app

import dev.orbitflags.core.Entities

/**
 * What was flagged recently, for the flags screen. Kept in memory only: it is
 * never written to storage and is gone when the service stops.
 */
object FlagLog {
    class Entry(
        val key: String,
        val kind: String,
        val reason: String,
        val possible: Boolean,
        val app: String,
        val time: Long,
    ) {
        /** The flagged words, or null for a picture. */
        val text: String? get() = if (key.startsWith("i:")) null else key.drop(2)
    }

    private const val MAX = 100
    private val history = ArrayDeque<Entry>()
    private var current: List<Entry> = emptyList()
    private var currentEntities: List<Entities.Entity> = emptyList()
    private var frontPackage = ""
    private var frontName = ""

    /** The app the user was in when they opened the flags screen. */
    var shownPackage = ""
        private set
    var shownName = ""
        private set

    /** Notes which app is in front, flagged or not, so its mode can be changed from the flags screen. */
    @Synchronized
    fun inFront(appPackage: String, name: String, mode: dev.orbitflags.core.AppPolicy.Mode) {
        if (appPackage != frontPackage) {
            current = emptyList()
            currentEntities = emptyList()
        }
        frontPackage = appPackage
        frontName = name
    }

    /** What was on screen when the user last opened the flags screen. */
    var shown: List<Entry> = emptyList()
        private set
    var shownEntities: List<Entities.Entity> = emptyList()
        private set

    @Synchronized
    fun record(app: String, findings: List<Finding>, entities: List<Entities.Entity>, now: Long) {
        current = findings.map { Entry(it.key, it.kind, it.reason, it.possible, app, now) }
        currentEntities = entities
        for (e in current) {
            if (history.any { it.key == e.key }) continue
            history.addFirst(e)
            if (history.size > MAX) history.removeLast()
        }
    }

    /** Fixes the current screen's flags in place before the flags screen covers them. */
    @Synchronized
    fun freeze() {
        shown = current
        shownEntities = currentEntities
        shownPackage = frontPackage
        shownName = frontName
    }

    @Synchronized
    fun recent(): List<Entry> = history.toList()

    @Synchronized
    fun forget(key: String) {
        history.removeAll { it.key == key }
        shown = shown.filter { it.key != key }
        current = current.filter { it.key != key }
    }
}
