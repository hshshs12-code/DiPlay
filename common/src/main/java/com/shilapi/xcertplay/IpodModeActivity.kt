// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** iPod audio over USB: audio without CarPlay. */
class IpodModeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var meter: ProgressBar
    private lateinit var peakView: TextView
    private lateinit var logView: TextView
    private lateinit var startStop: Button
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 100) }
    }
    private val engineListener: () -> Unit = { handler.post { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG; window.navigationBarColor = BG
        val root = ScrollView(this).apply { setBackgroundColor(BG) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(24)) }
        root.addView(content)
        setContentView(root)

        content.addView(label("iPod audio over USB", 30, TEXT, true))
        content.addView(label("Music, podcasts and any app's audio from the iPhone, without CarPlay. Play on the phone; DiPlay plays it through the car with your EQ.", 14, MUTED).apply { setPadding(0, dp(6), 0, dp(14)) })

        val card = column().apply { background = rounded(SURFACE, BORDER); setPadding(dp(20), dp(18), dp(20), dp(18)) }
        statusView = label("Idle", 22, TEXT, true)
        detailView = label("", 13, MUTED).apply { setPadding(0, dp(4), 0, dp(12)) }
        card.addView(statusView); card.addView(detailView)
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = 0; progressTintList = android.content.res.ColorStateList.valueOf(ACCENT) }
        card.addView(meter, LinearLayout.LayoutParams(-1, dp(14)))
        peakView = label("", 12, MUTED).apply { setPadding(0, dp(4), 0, dp(12)) }
        card.addView(peakView)
        startStop = button("Start iPod audio", true) { toggleEngine() }
        card.addView(startStop, LinearLayout.LayoutParams(-1, dp(60)))
        content.addView(card)

        val controls = column().apply { background = rounded(SURFACE, BORDER); setPadding(dp(20), dp(14), dp(20), dp(14)); (layoutParams as? LinearLayout.LayoutParams) }
        controls.addView(label("Volume", 16, MUTED))
        val volume = SeekBar(this).apply {
            max = 100; progress = (IpodSettings.volume(this@IpodModeActivity) * 100).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT); thumbTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) { IpodAudioEngine.volume = p / 100f; if (fromUser) IpodSettings.saveVolume(this@IpodModeActivity, p / 100f) }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit; override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        controls.addView(volume, LinearLayout.LayoutParams(-1, dp(44)))
        controls.addView(switchRow("Gain boost (+6 dB)", "For quiet phones; the soft clipper catches peaks when it is on.", IpodSettings.gainBoost(this)) {
            IpodAudioEngine.gainBoost = it; IpodSettings.saveGainBoost(this, it)
        })
        controls.addView(switchRow("Mute", "", IpodAudioEngine.muted) { IpodAudioEngine.muted = it })
        controls.addView(switchRow("Start iPod audio on plug-in instead of CarPlay", "When Android hands DiPlay the iPhone, go straight to iPod audio.", IpodSettings.autoStartOnPlugIn(this)) {
            IpodSettings.saveAutoStartOnPlugIn(this, it)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Equalizer", false) { startActivity(Intent(this, DiPlayActivity::class.java).putExtra("page", "eq")) }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { rightMargin = dp(8) })
        row.addView(button("Switch to CarPlay", false) {
            stopEngine()
            startActivity(Intent(this, DiPlayActivity::class.java).putExtra("connect_usb", true))
            finish()
        }, LinearLayout.LayoutParams(0, dp(54), 1f))
        controls.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        content.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        content.addView(label("Track controls and song info need Apple's accessory protocol over the USB HID interface, which this build records in the diagnostic report (Settings → Diagnostics → Upload) for the next step. For now use the phone, steering-wheel keys over Bluetooth, or Siri on the phone.", 13, MUTED).apply { setPadding(0, dp(14), 0, dp(6)) })
        logView = label("", 12, MUTED).apply { typeface = Typeface.MONOSPACE }
        content.addView(logView)
    }

    override fun onStart() {
        super.onStart(); IpodAudioEngine.listeners.add(engineListener); handler.post(refresh)
        if (intent.getBooleanExtra("auto_start", false)) { intent.removeExtra("auto_start"); if (!IpodAudioEngine.isRunning()) startEngine() }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent) }
    override fun onStop() { IpodAudioEngine.listeners.remove(engineListener); handler.removeCallbacks(refresh); super.onStop() }

    private fun toggleEngine() { if (IpodAudioEngine.isRunning()) stopEngine() else startEngine() }

    private fun startEngine() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 41); return
        }
        val usb = getSystemService(UsbManager::class.java)
        val iphone = IpodAudioEngine.findIphone(this)
        if (usb == null || iphone == null) { Toast.makeText(this, "Plug the iPhone in with a data cable first.", Toast.LENGTH_LONG).show(); return }
        if (!usb.hasPermission(iphone)) {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usb.requestPermission(iphone, PendingIntent.getBroadcast(this, 8, Intent("$packageName.IPOD_USB_PERMISSION").setPackage(packageName), flags))
            Toast.makeText(this, "Allow USB access, then tap Start again.", Toast.LENGTH_LONG).show(); return
        }
        if (CarPlayBackgroundSession.hasSession()) {
            CarPlayBackgroundSession.stop { runOnUiThread { startService(Intent(this, IpodAudioService::class.java)) } }
        } else startService(Intent(this, IpodAudioService::class.java))
    }

    private fun stopEngine() { startService(Intent(this, IpodAudioService::class.java).setAction(IpodAudioService.ACTION_STOP)) }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startEngine()
    }

    private fun render() {
        val running = IpodAudioEngine.isRunning()
        statusView.text = IpodAudioEngine.status
        detailView.text = if (running) "Input: ${IpodAudioEngine.inputDevice}\nPlayed: ${IpodAudioEngine.framesPlayed / maxOf(1, IpodAudioEngine.sampleRate)} s · underruns ${IpodAudioEngine.underruns}" else "Plug the iPhone in, unlock it, then Start."
        meter.progress = (IpodAudioEngine.level * 2500).toInt().coerceIn(0, 1000)
        peakView.text = if (running) "level ${"%.0f".format(IpodAudioEngine.level * 100)} % · peak ${"%.0f".format(IpodAudioEngine.peak * 100)} %" else ""
        startStop.text = if (running) "Stop" else "Start iPod audio"
        logView.text = IpodAudioEngine.recentLog.takeLast(12).joinToString("\n")
    }

    // ---- small view helpers (same look as the main screen) ----
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply { this.text = text; textSize = size.toFloat(); setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD) }
    private fun button(title: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 17f; setTextColor(if (primary) BG else TEXT)
        background = rounded(if (primary) ACCENT else SURFACE, BORDER); setOnClickListener { click() }
    }
    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(16).toFloat(); setStroke(dp(1), stroke) }
    private fun switchRow(title: String, description: String, checked: Boolean, onChanged: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
        val text = column().apply { addView(label(title, 16, TEXT)); if (description.isNotEmpty()) addView(label(description, 12, MUTED)) }
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Switch(this).apply { isChecked = checked; setOnCheckedChangeListener { _, v -> onChanged(v) } })
        return row
    }

    companion object {
        private val BG = Color.rgb(12, 17, 27); private val SURFACE = Color.rgb(21, 30, 44); private val BORDER = Color.rgb(42, 56, 75)
        private val ACCENT = Color.rgb(166, 200, 255); private val TEXT = Color.rgb(241, 245, 252); private val MUTED = Color.rgb(168, 182, 202)
    }
}
