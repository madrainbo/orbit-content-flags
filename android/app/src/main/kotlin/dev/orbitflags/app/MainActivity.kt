package dev.orbitflags.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.orbitflags.core.Domains

class MainActivity : Activity() {
    private lateinit var flagsStatus: TextView
    private lateinit var flagsButton: Button
    private lateinit var dnsButton: Button
    private lateinit var blockInput: EditText
    private lateinit var allowInput: EditText
    private lateinit var listStatus: TextView

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics,
    ).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }

        fun heading(text: String) = column.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(0, dp(24), 0, dp(6))
        })
        fun body(text: String) = TextView(this).apply {
            this.text = text
            textSize = 14f
        }.also { column.addView(it) }
        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }.also {
            column.addView(it, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) })
        }
        fun listInput(hint: String) = EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 3
            gravity = Gravity.TOP
            textSize = 14f
        }.also { column.addView(it) }

        column.addView(TextView(this).apply {
            text = "Orbit Content Flags"
            textSize = 24f
        })
        body("Signals, not verdicts. Everything is scored on this device; nothing you read is stored or sent anywhere.")

        heading("On-screen flags")
        flagsStatus = body("")
        flagsButton = button("Turn on on-screen flags") { showDisclosure() }
        column.addView(android.widget.CheckBox(this).apply {
            text = "Hide flagged text until I tap it"
            isChecked = Prefs.hideFlagged(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setHideFlagged(this@MainActivity, checked) }
        })
        body("Most screens produce no flag. To check it is working, show the sample below: " +
            "three samples should each be covered by a \"Hidden\" notice within a second, and tapping a notice shows that sample again.").setPadding(0, dp(12), 0, 0)
        val samples = SAMPLES.map { text ->
            body(text).apply {
                visibility = View.GONE
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
        }
        lateinit var sampleButton: Button
        sampleButton = button("Show sample text") {
            val show = samples[0].visibility != View.VISIBLE
            samples.forEach { it.visibility = if (show) View.VISIBLE else View.GONE }
            sampleButton.text = if (show) "Hide sample text" else "Show sample text"
        }
        // Lets automated tests reveal the sample without tapping.
        if (intent.getBooleanExtra("show_sample", false)) sampleButton.performClick()

        heading("Blocked domains")
        body("One domain per line; subdomains are included. Allow beats block.")
        blockInput = listInput("Block, e.g. example.com")
        allowInput = listInput("Always allow")
        blockInput.setText(Prefs.text(this, "block"))
        allowInput.setText(Prefs.text(this, "allow"))
        button("Save lists") { saveLists() }
        listStatus = body("")

        heading("Domain blocking")
        body("Uses Android's VPN feature only to answer DNS lookups on this device. " +
            "Your traffic is not sent through any server. Android shows a key icon while it is on.")
        dnsButton = button("") { toggleDns() }

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun flagsEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val me = ComponentName(this, ContentFlagService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun refresh() {
        val on = flagsEnabled()
        flagsStatus.text = if (on && !ContentFlagService.connected) {
            "Enabled in settings but not running yet. If this does not change, turn it off and on again in accessibility settings."
        } else if (on) {
            "On. Scam-style, adult and highly charged text in other apps is covered until you tap it, and a small pill summarises what was found."
        } else {
            "Off. When on, the app reads text shown in other apps to score it."
        }
        flagsButton.text = if (on) "Open accessibility settings" else "Turn on on-screen flags"
        dnsButton.text = if (DnsFilterService.running) "Stop domain blocking" else "Start domain blocking"
    }

    /** Prominent disclosure and consent before the accessibility permission is requested. */
    private fun showDisclosure() {
        if (flagsEnabled()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Orbit will read text on your screen")
            .setMessage(
                "To flag content, Orbit Content Flags uses Android's Accessibility API to read the text " +
                    "shown on screen in other apps.\n\n" +
                    "• The text is scored on this device and then discarded.\n" +
                    "• It is never stored, shared or sent anywhere.\n" +
                    "• Password fields and text you type are skipped.\n" +
                    "• The app does not tap, type or take any action for you.\n\n" +
                    "You can turn this off at any time in Settings > Accessibility."
            )
            .setPositiveButton("Agree and continue") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun saveLists() {
        val block = Domains.parseList(blockInput.text.toString())
        val allow = Domains.parseList(allowInput.text.toString())
        val invalid = block.invalid + allow.invalid
        if (invalid.isNotEmpty()) {
            listStatus.text = "Not saved. These are not domains: " + invalid.take(5).joinToString(", ")
            return
        }
        Prefs.save(this, block.valid, allow.valid)
        blockInput.setText(block.valid.joinToString("\n"))
        allowInput.setText(allow.valid.joinToString("\n"))
        listStatus.text = "Saved."
        if (DnsFilterService.running) startService(Intent(this, DnsFilterService::class.java))
    }

    private fun toggleDns() {
        if (DnsFilterService.running) {
            startService(Intent(this, DnsFilterService::class.java).setAction(DnsFilterService.ACTION_STOP))
        } else {
            val consent = VpnService.prepare(this)
            if (consent != null) {
                @Suppress("DEPRECATION")
                startActivityForResult(consent, REQUEST_VPN)
                return
            }
            startService(Intent(this, DnsFilterService::class.java))
        }
        dnsButton.postDelayed({ refresh() }, 400)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) {
            startService(Intent(this, DnsFilterService::class.java))
            dnsButton.postDelayed({ refresh() }, 400)
        }
    }

    private companion object {
        const val REQUEST_VPN = 1
        val SAMPLES = listOf(
            "Sample: SHOCKING betrayal as corrupt officials DESTROY town in terrifying " +
                "disaster!!! Hurry! Only 3 left in stock.",
            "Sample: Congratulations! You have been selected. Click here to claim your prize.",
            "Sample: Hot singles in your area want to chat. 18+ only, uncensored live cams.",
            "AI info",
        )
    }
}
