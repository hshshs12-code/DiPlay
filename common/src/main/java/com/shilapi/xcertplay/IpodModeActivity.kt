// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * iPod audio over USB: a Now Playing screen in the spirit of an OEM or Apple layout. Large
 * artwork, a colour wash taken from the art, few words, big controls. Everything secondary lives
 * behind the gear button.
 */
class IpodModeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var backdrop: ImageView
    private lateinit var wash: View
    private lateinit var statusPill: TextView
    private lateinit var artworkView: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var albumView: TextView
    private lateinit var sourceView: TextView
    private lateinit var progress: SeekBar
    private lateinit var elapsedView: TextView
    private lateinit var remainingView: TextView
    private lateinit var controls: LinearLayout
    private lateinit var playPause: TextView
    private lateinit var shuffleButton: TextView
    private lateinit var repeatButton: TextView
    private lateinit var chips: LinearLayout
    private lateinit var startButton: TextView
    private lateinit var footer: TextView
    private lateinit var sheet: FrameLayout
    private lateinit var sheetBody: LinearLayout
    private lateinit var meter: android.widget.ProgressBar
    private lateinit var logView: TextView
    private lateinit var sheetStartStop: TextView
    private var seeking = false
    private var shownArtwork: Any? = null
    private var washColor = 0
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 250) }
    }
    private val engineListener: () -> Unit = { handler.post { render() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG; window.navigationBarColor = BG
        val wide = resources.configuration.screenWidthDp >= 700
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        setContentView(root)

        // Backdrop: the artwork, heavily downscaled so it reads as a soft colour field.
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        wash = View(this)
        root.addView(wash, FrameLayout.LayoutParams(-1, -1))
        applyWash(BG)

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(16), dp(28), dp(16)) }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        // Top bar: home, title, status, settings.
        val top = row().apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(glyphButton("‹", 30) { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(label("iPod", 18, TEXT, true).apply { setPadding(dp(4), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        statusPill = label("Idle", 13, MUTED).apply {
            background = rounded(Color.argb(60, 255, 255, 255), Color.TRANSPARENT, 14); setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        top.addView(statusPill)
        top.addView(glyphButton("⚙", 24) { toggleSheet() }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })
        content.addView(top)

        // Main: artwork + text/controls.
        val main = if (wide) row().apply { gravity = Gravity.CENTER_VERTICAL } else column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val artSize = if (wide) dp(330) else dp(220)
        artworkView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(com.shilapi.xcertplay.host.R.drawable.art_now_playing_placeholder)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, dp(22).toFloat()) }
            }
            clipToOutline = true
            elevation = dp(18).toFloat()
        }
        main.addView(artworkView, LinearLayout.LayoutParams(artSize, artSize).apply { if (wide) rightMargin = dp(40) else bottomMargin = dp(16) })

        val text = column().apply { gravity = if (wide) Gravity.START else Gravity.CENTER_HORIZONTAL }
        titleView = label("", 32, TEXT, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; if (!wide) gravity = Gravity.CENTER }
        artistView = label("", 21, TEXT).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; alpha = 0.92f; if (!wide) gravity = Gravity.CENTER }
        albumView = label("", 16, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; if (!wide) gravity = Gravity.CENTER }
        sourceView = label("", 13, MUTED).apply { if (!wide) gravity = Gravity.CENTER }
        text.addView(titleView); text.addView(artistView.apply { setPadding(0, dp(4), 0, 0) }); text.addView(albumView.apply { setPadding(0, dp(2), 0, 0) })
        text.addView(sourceView.apply { setPadding(0, dp(6), 0, dp(10)) })

        progress = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(TEXT); progressBackgroundTintList = ColorStateList.valueOf(Color.argb(70, 255, 255, 255))
            thumbTintList = ColorStateList.valueOf(TEXT)
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) elapsedView.text = clock(IpodAudioEngine.nowPlaying.durationMillis?.let { it * p / 1000 }) }
                override fun onStartTrackingTouch(bar: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    seeking = false
                    IpodAudioEngine.nowPlaying.durationMillis?.let { IpodAudioEngine.seekTo(it * bar.progress / 1000) }
                }
            })
        }
        text.addView(progress, LinearLayout.LayoutParams(-1, dp(28)))
        val times = row()
        elapsedView = label("0:00", 12, MUTED); remainingView = label("-0:00", 12, MUTED).apply { gravity = Gravity.END }
        times.addView(elapsedView, LinearLayout.LayoutParams(0, -2, 1f)); times.addView(remainingView, LinearLayout.LayoutParams(0, -2, 1f))
        text.addView(times)

        controls = row().apply { gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        shuffleButton = glyphButton("⇄", 22) { IpodAudioEngine.toggleShuffle() }
        repeatButton = glyphButton("↻", 22) { IpodAudioEngine.cycleRepeat() }
        playPause = TextView(this).apply {
            this.text = "▶"; textSize = 30f; setTextColor(BG); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            background = circle(TEXT); isClickable = true; isFocusable = true
            setOnClickListener { IpodAudioEngine.playPause() }
        }
        controls.addView(shuffleButton, LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(10) })
        controls.addView(glyphButton("⏮", 34) { IpodAudioEngine.previous() }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { rightMargin = dp(6) })
        controls.addView(playPause, LinearLayout.LayoutParams(dp(84), dp(84)).apply { leftMargin = dp(6); rightMargin = dp(6) })
        controls.addView(glyphButton("⏭", 34) { IpodAudioEngine.next() }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { leftMargin = dp(6) })
        controls.addView(repeatButton, LinearLayout.LayoutParams(dp(56), dp(56)).apply { leftMargin = dp(10) })
        text.addView(controls, LinearLayout.LayoutParams(-1, -2))

        chips = row().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        chips.addView(chip("Siri") { IpodAudioEngine.siri() })
        listOf("Music" to "com.apple.Music", "Podcasts" to "com.apple.podcasts", "Spotify" to "com.spotify.client", "YT Music" to "com.google.ios.youtubemusic").forEach { (name, bundle) ->
            chips.addView(chip(name) { IpodAudioEngine.launchApp(bundle); toast("Opening $name on the iPhone") })
        }
        text.addView(chips, LinearLayout.LayoutParams(-1, -2))

        startButton = TextView(this).apply {
            this.text = "Start"; textSize = 20f; setTextColor(BG); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            background = rounded(TEXT, Color.TRANSPARENT, 28); isClickable = true; isFocusable = true
            setOnClickListener { toggleEngine() }
        }
        text.addView(startButton, LinearLayout.LayoutParams(if (wide) dp(220) else -1, dp(56)).apply { topMargin = dp(16) })

        main.addView(text, if (wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        content.addView(main, LinearLayout.LayoutParams(-1, 0, 1f))

        footer = label("", 12, MUTED).apply { gravity = Gravity.CENTER; maxLines = 2 }
        content.addView(footer)

        buildSheet(root)
    }

    /** Settings and diagnostics, slid over the bottom of the screen from the gear button. */
    private fun buildSheet(root: FrameLayout) {
        sheet = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(Color.argb(140, 0, 0, 0)); setOnClickListener { toggleSheet() } }
        val panel = ScrollView(this).apply {
            background = rounded(SURFACE, BORDER, 22); isClickable = true
            setPadding(dp(22), dp(18), dp(22), dp(18))
        }
        sheetBody = column()
        panel.addView(sheetBody)
        sheet.addView(panel, FrameLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.78).toInt(), Gravity.BOTTOM))
        root.addView(sheet, FrameLayout.LayoutParams(-1, -1))

        val head = row().apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("iPod audio", 20, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        sheetStartStop = TextView(this).apply {
            text = "Start"; textSize = 16f; setTextColor(BG); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            background = rounded(ACCENT, Color.TRANSPARENT, 22); isClickable = true; setOnClickListener { toggleEngine() }
        }
        head.addView(sheetStartStop, LinearLayout.LayoutParams(dp(120), dp(44)))
        sheetBody.addView(head)
        meter = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressTintList = ColorStateList.valueOf(ACCENT) }
        sheetBody.addView(meter, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(10) })

        sheetBody.addView(label("Volume", 14, MUTED).apply { setPadding(0, dp(14), 0, 0) })
        sheetBody.addView(SeekBar(this).apply {
            max = 100; progress = (IpodSettings.volume(this@IpodModeActivity) * 100).toInt()
            progressTintList = ColorStateList.valueOf(ACCENT); thumbTintList = ColorStateList.valueOf(ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) { IpodAudioEngine.volume = p / 100f; if (fromUser) IpodSettings.saveVolume(this@IpodModeActivity, p / 100f) }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit; override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(40)))
        sheetBody.addView(switchRow("Gain boost (+6 dB)", "For quiet phones; the limiter catches peaks.", IpodSettings.gainBoost(this)) {
            IpodAudioEngine.gainBoost = it; IpodSettings.saveGainBoost(this, it)
        })
        sheetBody.addView(switchRow("Direct USB audio driver", "Reads the iPhone's USB audio packets straight from the bus. Off = Android's USB audio input.", IpodSettings.directUsbAudio(this)) {
            IpodSettings.saveDirectUsbAudio(this, it); if (IpodAudioEngine.isRunning()) toast("Applies at the next connection")
        })
        sheetBody.addView(switchRow("Start iPod audio on plug-in instead of CarPlay", "When Android hands DiPlay the iPhone, this screen opens and connects.", IpodSettings.autoStartOnPlugIn(this)) {
            IpodSettings.saveAutoStartOnPlugIn(this, it)
        })
        sheetBody.addView(label("Charging current announced to the iPhone", 14, TEXT).apply { setPadding(0, dp(12), 0, dp(4)) })
        val currents = row()
        val currentButtons = ArrayList<TextView>()
        IpodSettings.CHARGE_CURRENTS.forEachIndexed { index, ma ->
            val b = chip("$ma mA", IpodSettings.chargeCurrent(this) == ma) {
                IpodSettings.saveChargeCurrent(this, ma)
                currentButtons.forEachIndexed { i, other -> styleChip(other, IpodSettings.CHARGE_CURRENTS[i] == ma) }
                if (IpodAudioEngine.isRunning()) toast("Applies at the next connection")
            }
            currentButtons += b
            currents.addView(b)
        }
        sheetBody.addView(currents)
        val links = row().apply { setPadding(0, dp(14), 0, 0) }
        links.addView(button("Equalizer") { startActivity(Intent(this, DiPlayActivity::class.java).putExtra("page", "eq")) }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(8) })
        links.addView(button("Request USB audio") {
            if (IpodAudioEngine.capabilities.usbAudio) { IpodAudioEngine.restartAudio(); toast("Asked the iPhone to (re)start USB audio") }
            else { IpodAudioEngine.forceAudioRequest(); toast("Experimental: sent the USB-audio request the iPhone did not accept in identification; check the log") }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(8) })
        links.addView(button("Switch to CarPlay") {
            stopEngine()
            startActivity(Intent(this, DiPlayActivity::class.java).putExtra("connect_usb", true))
            finish()
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        sheetBody.addView(links)
        logView = label("", 11, MUTED).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(12), 0, 0) }
        sheetBody.addView(logView)
    }

    private fun toggleSheet() { sheet.visibility = if (sheet.visibility == View.VISIBLE) View.GONE else View.VISIBLE }

    override fun onStart() {
        super.onStart(); IpodAudioEngine.listeners.add(engineListener); handler.post(refresh)
        if (intent.getBooleanExtra("auto_start", false)) { intent.removeExtra("auto_start"); if (!IpodAudioEngine.isRunning()) startEngine() }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent) }
    override fun onStop() { IpodAudioEngine.listeners.remove(engineListener); handler.removeCallbacks(refresh); super.onStop() }
    override fun onBackPressed() { if (sheet.visibility == View.VISIBLE) toggleSheet() else super.onBackPressed() }

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
        val ready = IpodAudioEngine.state == IpodAudioEngine.State.READY
        val info = IpodAudioEngine.nowPlaying
        val caps = IpodAudioEngine.capabilities

        statusPill.text = when (IpodAudioEngine.state) {
            IpodAudioEngine.State.IDLE, IpodAudioEngine.State.STOPPED -> "Not connected"
            IpodAudioEngine.State.READY -> if (IpodAudioEngine.audioActive) "Connected · ${IpodAudioEngine.sampleRate / 1000.0} kHz" else "Connected"
            IpodAudioEngine.State.FAILED -> "Failed"
            else -> "Connecting…"
        }
        statusPill.setTextColor(when (IpodAudioEngine.state) { IpodAudioEngine.State.READY -> TEXT; IpodAudioEngine.State.FAILED -> WARNING; else -> MUTED })

        if (ready && info.title != null) {
            titleView.text = info.title
            artistView.text = info.artist ?: ""
            albumView.text = info.album ?: ""
        } else if (ready) {
            titleView.text = "Nothing playing"
            artistView.text = "Play something on the iPhone or open an app below."
            albumView.text = ""
        } else if (running) {
            titleView.text = IpodAudioEngine.status.substringBefore('.').ifEmpty { "Connecting" }
            artistView.text = "Keep the iPhone unlocked."
            albumView.text = ""
        } else {
            titleView.text = "iPhone"
            artistView.text = "Plug it in with a data cable, unlock it, then Start."
            albumView.text = if (IpodAudioEngine.state == IpodAudioEngine.State.FAILED) IpodAudioEngine.status else ""
        }
        sourceView.text = listOfNotNull(
            info.sourceApp?.takeIf { ready },
            when (IpodAudioEngine.shuffle) { 1 -> "Shuffle"; 2 -> "Shuffle albums"; else -> null },
            when (IpodAudioEngine.repeat) { 1 -> "Repeat one"; 2 -> "Repeat all"; else -> null },
            IpodAudioEngine.power.describe().takeIf { ready && it.isNotEmpty() },
            if (ready && !caps.hid) "controls refused by the iPhone: use the phone or Siri" else null,
        ).joinToString("  ·  ")

        val art = IpodAudioEngine.artwork
        if (art !== shownArtwork) {
            shownArtwork = art
            if (art != null) {
                artworkView.setImageBitmap(art)
                backdrop.setImageBitmap(softBackdrop(art)); backdrop.animate().alpha(0.55f).setDuration(600).start()
                applyWash(dominantColor(art))
            } else {
                artworkView.setImageResource(com.shilapi.xcertplay.host.R.drawable.art_now_playing_placeholder)
                backdrop.animate().alpha(0f).setDuration(400).start()
                applyWash(BG)
            }
        }

        val duration = info.durationMillis
        val elapsed = IpodAudioEngine.elapsedNow()
        if (!seeking) {
            progress.progress = if (duration != null && duration > 0 && elapsed != null) (elapsed * 1000 / duration).toInt().coerceIn(0, 1000) else 0
            elapsedView.text = clock(elapsed)
        }
        remainingView.text = if (duration != null && elapsed != null) "-" + clock((duration - elapsed).coerceAtLeast(0)) else clock(duration)
        progress.isEnabled = ready && caps.setNowPlaying
        progress.alpha = if (ready) 1f else 0.35f

        playPause.text = if (IpodAudioEngine.playing) "⏸" else "▶"
        controls.visibility = if (running) View.VISIBLE else View.GONE
        chips.visibility = if (ready) View.VISIBLE else View.GONE
        controls.alpha = if (ready && caps.hid) 1f else 0.4f
        styleGlyph(shuffleButton, (IpodAudioEngine.shuffle ?: 0) != 0)
        styleGlyph(repeatButton, (IpodAudioEngine.repeat ?: 0) != 0)
        repeatButton.text = when (IpodAudioEngine.repeat) { 1 -> "↻¹"; else -> "↻" }
        startButton.visibility = if (running) View.GONE else View.VISIBLE
        sheetStartStop.text = if (running) "Stop" else "Start"

        footer.text = when {
            !running -> "Music, podcasts and any app's audio from the iPhone, with track info and steering-wheel keys. No CarPlay needed."
            ready && IpodAudioEngine.audioActive -> "${if (IpodAudioEngine.audioPath == "direct") "Direct USB audio" else "Android audio input"} · ${IpodAudioEngine.framesPlayed / maxOf(1, IpodAudioEngine.sampleRate)} s · underruns ${IpodAudioEngine.underruns}"
            ready -> if (caps.usbAudio) "Waiting for the iPhone to start USB audio…" else "The iPhone refused the USB-audio request; audio may stay on the phone. Try “Request USB audio” under ⚙."
            else -> IpodAudioEngine.status
        }
        meter.progress = (IpodAudioEngine.level * 2500).toInt().coerceIn(0, 1000)
        logView.text = IpodAudioEngine.recentLog.takeLast(10).joinToString("\n")
    }

    // ---- colour from the artwork ----
    private fun dominantColor(bitmap: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        var r = 0L; var g = 0L; var b = 0L
        for (y in 0 until 8) for (x in 0 until 8) { val c = small.getPixel(x, y); r += Color.red(c); g += Color.green(c); b += Color.blue(c) }
        small.recycle()
        val n = 64
        // Pull toward a dark, slightly saturated tone so white text stays readable.
        val hsv = FloatArray(3)
        Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
        hsv[1] = (hsv[1] * 1.2f).coerceIn(0.25f, 0.8f); hsv[2] = 0.28f
        return Color.HSVToColor(hsv)
    }

    private fun softBackdrop(bitmap: Bitmap): Bitmap = Bitmap.createScaledBitmap(bitmap, 24, 24, true)

    private fun applyWash(color: Int) {
        if (color == washColor) return
        washColor = color
        wash.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.argb(200, Color.red(color), Color.green(color), Color.blue(color)), BG))
    }

    private fun clock(millis: Long?): String {
        if (millis == null || millis < 0) return "–:––"
        val total = millis / 1000
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---- view helpers ----
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply { this.text = text; textSize = size.toFloat(); setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD) }
    private fun glyphButton(glyph: String, size: Int, click: () -> Unit) = TextView(this).apply {
        text = glyph; textSize = size.toFloat(); setTextColor(TEXT); gravity = Gravity.CENTER
        background = circle(Color.argb(28, 255, 255, 255)); isClickable = true; isFocusable = true; setOnClickListener { click() }
    }
    private fun styleGlyph(view: TextView, active: Boolean) {
        view.setTextColor(if (active) BG else TEXT)
        view.background = circle(if (active) TEXT else Color.argb(28, 255, 255, 255))
    }
    private fun chip(title: String, active: Boolean = false, click: () -> Unit) = TextView(this).apply {
        text = title; textSize = 14f; gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(8))
        styleChip(this, active); isClickable = true; isFocusable = true; setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }
    }
    private fun styleChip(view: TextView, active: Boolean) {
        view.setTextColor(if (active) BG else TEXT)
        view.background = rounded(if (active) TEXT else Color.argb(36, 255, 255, 255), Color.TRANSPARENT, 18)
    }
    private fun button(title: String, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 15f; setTextColor(TEXT)
        background = rounded(Color.argb(36, 255, 255, 255), BORDER, 14); setOnClickListener { click() }
    }
    private fun rounded(color: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat(); if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke) }
    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun switchRow(title: String, description: String, checked: Boolean, onChanged: (Boolean) -> Unit): View {
        val r = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        val text = column().apply { addView(label(title, 15, TEXT)); if (description.isNotEmpty()) addView(label(description, 12, MUTED)) }
        r.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(Switch(this).apply { isChecked = checked; setOnCheckedChangeListener { _, v -> onChanged(v) } })
        return r
    }

    companion object {
        private val BG = Color.rgb(10, 12, 18); private val SURFACE = Color.rgb(24, 28, 38); private val BORDER = Color.rgb(50, 56, 72)
        private val ACCENT = Color.rgb(166, 200, 255); private val TEXT = Color.rgb(246, 247, 250); private val MUTED = Color.rgb(170, 178, 194)
        private val WARNING = Color.rgb(255, 180, 120)
    }
}
