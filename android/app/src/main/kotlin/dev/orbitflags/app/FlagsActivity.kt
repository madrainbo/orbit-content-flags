package dev.orbitflags.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.orbitflags.core.Entities

/**
 * Opened by tapping the pill. Shows what was flagged on the screen the user
 * came from and recently, why, and lets them say a flag was wrong or report
 * a phone number, account or site.
 */
class FlagsActivity : Activity() {
    private lateinit var column: LinearLayout

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
    ).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        })
    }

    override fun onResume() {
        super.onResume()
        visible = true
        rebuild()
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    private fun heading(text: String) = column.addView(TextView(this).apply {
        this.text = text
        textSize = 18f
        setPadding(0, dp(22), 0, dp(6))
    })

    private fun body(text: String, size: Float = 14f) = TextView(this).apply {
        this.text = text
        textSize = size
    }.also { column.addView(it) }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }.also {
        column.addView(it, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
    }

    private fun ago(time: Long): String {
        val seconds = (System.currentTimeMillis() - time) / 1000
        return when {
            seconds < 60 -> "just now"
            seconds < 3600 -> "${seconds / 60} min ago"
            else -> "${seconds / 3600} h ago"
        }
    }

    private fun report(entity: Entities.Entity) {
        ReportStore.report(this, entity)
        // Reported sites are also blocked by domain blocking, if it is on.
        if (entity.type == Entities.Type.DOMAIN && DnsFilterService.running) {
            startService(Intent(this, DnsFilterService::class.java))
        }
        Toast.makeText(this, "Reported ${entity.display}. Content mentioning it will be hidden on this phone.",
            Toast.LENGTH_LONG).show()
        rebuild()
    }

    private fun entry(e: FlagLog.Entry) {
        val label = (Finding.LABELS[e.kind] ?: "Flagged") + if (e.possible) " (possible)" else ""
        body(label, 15f).apply {
            setPadding(0, dp(12), 0, 0)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        body(e.reason.replaceFirstChar { it.uppercase() } + ".")
        body("In ${e.app} · ${ago(e.time)}", 12f)
        val text = e.text
        body(if (text == null) "A picture." else "“" + text.take(EXCERPT) + (if (text.length > EXCERPT) "…" else "") + "”")
        button("Not a problem") {
            ReportStore.markOk(this, e.key)
            FlagLog.forget(e.key)
            Toast.makeText(this, "Thanks. This won't be flagged again on this phone.", Toast.LENGTH_SHORT).show()
            rebuild()
        }
        val reported = ReportStore.reported(this)
        for (entity in Entities.extract(text).filter { it !in reported }.take(3)) {
            button("Report ${entity.display}") { report(entity) }
        }
    }

    private fun rebuild() {
        column.removeAllViews()
        column.addView(TextView(this).apply {
            text = "Flags"
            textSize = 24f
        })
        body("Signals, not verdicts. This list is kept in memory only and is cleared when the service stops.")

        val shown = FlagLog.shown
        heading("On the screen you came from")
        if (shown.isEmpty()) body("Nothing was flagged there.") else shown.forEach { entry(it) }

        val earlier = FlagLog.recent().filter { r -> shown.none { it.key == r.key } }.take(MAX_RECENT)
        heading("Recently flagged")
        if (earlier.isEmpty()) body("Nothing else yet.") else earlier.forEach { entry(it) }

        val reported = ReportStore.reported(this)
        val seen = FlagLog.shownEntities.filter { it !in reported }.take(MAX_SEEN)
        heading("Report an account, number or site")
        body("Anything you report is flagged wherever it appears on this phone, and reported sites are blocked " +
            "when domain blocking is on. Reports stay on this phone.")
        if (seen.isNotEmpty()) {
            body("Seen on the screen you came from:", 12f).setPadding(0, dp(8), 0, 0)
            seen.forEach { entity -> button("Report ${entity.display}") { report(entity) } }
        }
        val input = EditText(this).apply {
            hint = "@account, phone number or site"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textSize = 14f
        }
        column.addView(input)
        button("Report") {
            val entity = Entities.parse(input.text.toString())
            if (entity == null) {
                Toast.makeText(this, "That doesn't look like an account, a phone number or a site.", Toast.LENGTH_LONG).show()
            } else {
                report(entity)
            }
        }

        heading("Reported by you")
        if (reported.isEmpty()) body("Nothing reported yet.")
        for (entity in reported) {
            body(entity.display).setPadding(0, dp(8), 0, 0)
            button("Remove") {
                ReportStore.withdraw(this, entity)
                rebuild()
            }
        }
        val wrong = ReportStore.falseFlagCount(this)
        body(if (wrong == 1) "You have marked 1 flag as not a problem." else "You have marked $wrong flags as not a problem.")
            .setPadding(0, dp(16), 0, 0)
    }

    companion object {
        private const val EXCERPT = 160
        private const val MAX_RECENT = 50
        private const val MAX_SEEN = 12

        /** True while this screen is in front, so the service does not flag its contents. */
        @Volatile
        var visible = false
            private set
    }
}
