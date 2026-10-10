// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** iPod audio over USB: the iPhone as a native USB music source, with Now Playing and controls. */
class IpodModeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var meter: ProgressBar
    private lateinit var startStop: Button
    private lateinit var artworkView: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var albumView: TextView
    private lateinit var appView: TextView
    private lateinit var progress: SeekBar
    private lateinit var elapsedView: TextView
    private lateinit var durationView: TextView
    private lateinit var playPause: Button
    private lateinit var shuffleButton: Button
    private lateinit var repeatButton: Button
    private lateinit var batteryView: TextView
    private lateinit var logView: TextView
    private var seeking = false
    private var shownArtwork: Any? = null
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 250) }
    }
    private val engineListener: () -> Unit = { handler.post { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG; window.navigationBarColor = BG
        val root = ScrollView(this).apply { setBackgroundColor(BG) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(20)) }
        root.addView(content)
        setContentView(root)
        val wide = resources.configuration.screenWidthDp >= 700

        // Header: title + live status.
        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("iPod audio over USB", 24, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        statusView = label("Idle", 14, MUTED).apply { gravity = Gravity.END }
        header.addView(statusView, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(header)

        // Now playing card.
        val playing = card()
        val body = if (wide) row() else column()
        artworkView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Color.rgb(30, 41, 59), BORDER)
            setImageResource(com.shilapi.xcertplay.host.R.drawable.art_now_playing_placeholder)
            clipToOutline = true
        }
        val artSize = if (wide) dp(210) else dp(160)
        body.addView(artworkView, LinearLayout.LayoutParams(artSize, artSize).apply { if (wide) rightMargin = dp(20) else bottomMargin = dp(12) })
        val text = column()
        titleView = label("Nothing playing", 26, TEXT, true).apply { maxLines = 2 }
        artistView = label("Play something on the iPhone; it shows up here.", 18, TEXT).apply { maxLines = 1 }
        albumView = label("", 14, MUTED).apply { maxLines = 1 }
        appView = label("", 12, MUTED)
        text.addView(titleView); text.addView(artistView); text.addView(albumView); text.addView(appView.apply { setPadding(0, dp(2), 0, dp(8)) })
        progress = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(ACCENT); thumbTintList = ColorStateList.valueOf(ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) elapsedView.text = clock(IpodAudioEngine.nowPlaying.durationMillis?.let { it * p / 1000 }) }
                override fun onStartTrackingTouch(bar: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    seeking = false
                    IpodAudioEngine.nowPlaying.durationMillis?.let { IpodAudioEngine.seekTo(it * bar.progress / 1000) }
                }
            })
        }
        text.addView(progress, LinearLayout.LayoutParams(-1, dp(36)))
        val times = row()
        elapsedView = label("0:00", 12, MUTED); durationView = label("0:00", 12, MUTED).apply { gravity = Gravity.END }
        times.addView(elapsedView, LinearLayout.LayoutParams(0, -2, 1f)); times.addView(durationView, LinearLayout.LayoutParams(0, -2, 1f))
        text.addView(times)
        val controls = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        shuffleButton = control("Shuffle", false) { IpodAudioEngine.toggleShuffle() }
        repeatButton = control("Repeat", false) { IpodAudioEngine.cycleRepeat() }
        playPause = control("▶", true) { IpodAudioEngine.playPause() }.apply { textSize = 26f }
        controls.addView(shuffleButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(6) })
        controls.addView(control("⏮", false) { IpodAudioEngine.previous() }.apply { textSize = 24f }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(6) })
        controls.addView(playPause, LinearLayout.LayoutParams(0, dp(60), 1.4f).apply { rightMargin = dp(6) })
        controls.addView(control("⏭", false) { IpodAudioEngine.next() }.apply { textSize = 24f }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(6) })
        controls.addView(repeatButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        text.addView(controls)
        val apps = row().apply { setPadding(0, dp(10), 0, 0) }
        apps.addView(control("Siri", false) { IpodAudioEngine.siri() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
        listOf("Music" to "com.apple.Music", "Podcasts" to "com.apple.podcasts", "Spotify" to "com.spotify.client", "YT Music" to "com.google.ios.youtubemusic").forEachIndexed { index, (name, bundle) ->
            apps.addView(control(name, false) { IpodAudioEngine.launchApp(bundle); toast("Opening $name on the iPhone") }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (index < 3) rightMargin = dp(6) })
        }
        text.addView(apps)
        body.addView(text, if (wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        playing.addView(body)
        content.addView(playing, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // Connection card.
        val connection = card()
        val connectionRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val connectionText = column()
        detailView = label("", 13, MUTED)
        batteryView = label("", 13, MUTED)
        connectionText.addView(detailView); connectionText.addView(batteryView)
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressTintList = ColorStateList.valueOf(ACCENT) }
        connectionText.addView(meter, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(8) })
        connectionRow.addView(connectionText, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(14) })
        startStop = button("Start", true) { toggleEngine() }
        connectionRow.addView(startStop, LinearLayout.LayoutParams(dp(170), dp(60)))
        connection.addView(connectionRow)
        content.addView(connection, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // Settings card.
        val settings = card()
        settings.addView(label("Volume", 15, MUTED))
        settings.addView(SeekBar(this).apply {
            max = 100; progress = (IpodSettings.volume(this@IpodModeActivity) * 100).toInt()
            progressTintList = ColorStateList.valueOf(ACCENT); thumbTintList = ColorStateList.valueOf(ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) { IpodAudioEngine.volume = p / 100f; if (fromUser) IpodSettings.saveVolume(this@IpodModeActivity, p / 100f) }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit; override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(40)))
        settings.addView(switchRow("Gain boost (+6 dB)", "For quiet phones; the soft clipper catches peaks when it is on.", IpodSettings.gainBoost(this)) {
            IpodAudioEngine.gainBoost = it; IpodSettings.saveGainBoost(this, it)
        })
        settings.addView(switchRow("Direct USB audio driver", "Reads the iPhone's USB audio packets straight from the bus (lowest latency, no Android audio input). Off = Android's USB audio input.", IpodSettings.directUsbAudio(this)) {
            IpodSettings.saveDirectUsbAudio(this, it); if (IpodAudioEngine.isRunning()) toast("Applies at the next connection")
        })
        settings.addView(switchRow("Start iPod audio on plug-in instead of CarPlay", "When Android hands DiPlay the iPhone, this screen opens and connects.", IpodSettings.autoStartOnPlugIn(this)) {
            IpodSettings.saveAutoStartOnPlugIn(this, it)
        })
        settings.addView(label("Charging current announced to the iPhone (what this USB port can supply)", 14, TEXT).apply { setPadding(0, dp(10), 0, dp(4)) })
        val currents = row()
        val currentButtons = ArrayList<Button>()
        IpodSettings.CHARGE_CURRENTS.forEachIndexed { index, ma ->
            val b = control("$ma mA", IpodSettings.chargeCurrent(this) == ma) {
                IpodSettings.saveChargeCurrent(this, ma)
                currentButtons.forEachIndexed { i, other -> style(other, IpodSettings.CHARGE_CURRENTS[i] == ma) }
                if (IpodAudioEngine.isRunning()) toast("Applies at the next connection")
            }
            currentButtons += b
            currents.addView(b, LinearLayout.LayoutParams(0, dp(42), 1f).apply { if (index < IpodSettings.CHARGE_CURRENTS.size - 1) rightMargin = dp(6) })
        }
        settings.addView(currents)
        val links = row().apply { setPadding(0, dp(12), 0, 0) }
        links.addView(button("Equalizer", false) { startActivity(Intent(this, DiPlayActivity::class.java).putExtra("page", "eq")) }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(8) })
        links.addView(button("Restart USB audio", false) { IpodAudioEngine.restartAudio(); toast("Asked the iPhone to restart USB audio") }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(8) })
        links.addView(button("Switch to CarPlay", false) {
            stopEngine()
            startActivity(Intent(this, DiPlayActivity::class.java).putExtra("connect_usb", true))
            finish()
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        settings.addView(links)
        content.addView(settings, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        logView = label("", 11, MUTED).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(12), 0, 0) }
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
        if (usb == null || iphone == null) { toast("Plug the iPhone in with a data cable first."); return }
        if (!usb.hasPermission(iphone)) {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usb.requestPermission(iphone, PendingIntent.getBroadcast(this, 8, Intent("$packageName.IPOD_USB_PERMISSION").setPackage(packageName), flags))
            toast("Allow USB access, then tap Start again."); return
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
        val info = IpodAudioEngine.nowPlaying
        statusView.text = IpodAudioEngine.status
        statusView.setTextColor(when (IpodAudioEngine.state) { IpodAudioEngine.State.READY -> ACCENT; IpodAudioEngine.State.FAILED -> WARNING; else -> MUTED })
        startStop.text = if (running) "Stop" else "Start"
        titleView.text = info.title ?: if (IpodAudioEngine.state == IpodAudioEngine.State.READY) "Nothing playing" else "iPhone as a USB music source"
        artistView.text = info.artist ?: if (IpodAudioEngine.state == IpodAudioEngine.State.READY) "Start playback on the phone, or open an app below." else "Plug the iPhone in, unlock it, then tap Start."
        albumView.text = info.album ?: ""
        appView.text = listOfNotNull(info.sourceApp, shuffleText(), repeatText()).joinToString(" · ")
        val art = IpodAudioEngine.artwork
        if (art !== shownArtwork) {
            shownArtwork = art
            if (art != null) artworkView.setImageBitmap(art) else artworkView.setImageResource(com.shilapi.xcertplay.host.R.drawable.art_now_playing_placeholder)
        }
        val duration = info.durationMillis
        val elapsed = IpodAudioEngine.elapsedNow()
        if (!seeking) {
            progress.progress = if (duration != null && duration > 0 && elapsed != null) (elapsed * 1000 / duration).toInt().coerceIn(0, 1000) else 0
            elapsedView.text = clock(elapsed)
        }
        durationView.text = clock(duration)
        playPause.text = if (IpodAudioEngine.playing) "⏸" else "▶"
        style(shuffleButton, (IpodAudioEngine.shuffle ?: 0) != 0)
        style(repeatButton, (IpodAudioEngine.repeat ?: 0) != 0)
        repeatButton.text = when (IpodAudioEngine.repeat) { 1 -> "Repeat 1"; 2 -> "Repeat all"; else -> "Repeat" }
        detailView.text = if (running) buildString {
            append(if (IpodAudioEngine.authenticated) "Authenticated accessory" else if (IpodAudioEngine.identified) "Identified, authenticating…" else "Connecting…")
            if (IpodAudioEngine.audioActive) append(" · ${if (IpodAudioEngine.audioPath == "direct") "direct USB" else "Android"} audio ${IpodAudioEngine.sampleRate / 1000.0} kHz · ${IpodAudioEngine.framesPlayed / maxOf(1, IpodAudioEngine.sampleRate)} s · underruns ${IpodAudioEngine.underruns}")
            else if (IpodAudioEngine.state == IpodAudioEngine.State.READY) append(" · waiting for USB audio")
            append("\nInput: ${IpodAudioEngine.inputDevice}")
        } else "Plug the iPhone in, unlock it, then Start. Works without CarPlay: music, podcasts, any app's audio, with track info and steering-wheel keys."
        val power = IpodAudioEngine.power.describe()
        batteryView.text = if (running && power.isNotEmpty()) "iPhone $power" else ""
        meter.progress = (IpodAudioEngine.level * 2500).toInt().coerceIn(0, 1000)
        logView.text = IpodAudioEngine.recentLog.takeLast(10).joinToString("\n")
    }

    private fun shuffleText() = when (IpodAudioEngine.shuffle) { 1 -> "Shuffle songs"; 2 -> "Shuffle albums"; else -> null }
    private fun repeatText() = when (IpodAudioEngine.repeat) { 1 -> "Repeat one"; 2 -> "Repeat all"; else -> null }
    private fun clock(millis: Long?): String {
        if (millis == null || millis < 0) return "–:––"
        val total = millis / 1000
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---- view helpers (same look as the main screen) ----
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun card() = column().apply { background = rounded(SURFACE, BORDER); setPadding(dp(18), dp(16), dp(18), dp(16)) }
    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply { this.text = text; textSize = size.toFloat(); setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD) }
    private fun button(title: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 16f; setTextColor(if (primary) BG else TEXT)
        background = rounded(if (primary) ACCENT else SURFACE, BORDER); setOnClickListener { click() }
    }
    private fun control(title: String, active: Boolean, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 15f; setPadding(dp(4), 0, dp(4), 0)
        style(this, active); setOnClickListener { click() }
    }
    private fun style(button: Button, active: Boolean) {
        button.setTextColor(if (active) BG else TEXT)
        button.background = rounded(if (active) ACCENT else Color.rgb(30, 41, 59), BORDER)
    }
    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat(); setStroke(dp(1), stroke) }
    private fun switchRow(title: String, description: String, checked: Boolean, onChanged: (Boolean) -> Unit): View {
        val r = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        val text = column().apply { addView(label(title, 15, TEXT)); if (description.isNotEmpty()) addView(label(description, 12, MUTED)) }
        r.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(Switch(this).apply { isChecked = checked; setOnCheckedChangeListener { _, v -> onChanged(v) } })
        return r
    }

    companion object {
        private val BG = Color.rgb(12, 17, 27); private val SURFACE = Color.rgb(21, 30, 44); private val BORDER = Color.rgb(42, 56, 75)
        private val ACCENT = Color.rgb(166, 200, 255); private val TEXT = Color.rgb(241, 245, 252); private val MUTED = Color.rgb(168, 182, 202)
        private val WARNING = Color.rgb(255, 180, 120)
    }
}
