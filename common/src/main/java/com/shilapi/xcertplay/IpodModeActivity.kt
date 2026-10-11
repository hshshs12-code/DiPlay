// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * iPod audio over USB: a Now Playing screen in the spirit of an OEM or Apple layout. Large
 * artwork, a colour wash taken from the art, few words, big controls. Everything secondary lives
 * behind the gear button.
 *
 * Built for a cheap resistive touchscreen at arm's length: no target under 56 dp (back and gear
 * 64, previous/next 80, play 96), pressed feedback on every control, controls the iPhone refused
 * drawn dimmed and disabled rather than merely translucent, and a persistent recovery line
 * instead of a toast when USB access or audio needs the driver's attention.
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
    private lateinit var previousButton: TextView
    private lateinit var nextButton: TextView
    private lateinit var shuffleButton: TextView
    private lateinit var repeatButton: TextView
    private lateinit var actions: LinearLayout
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
    private var shuffleActive: Boolean? = null
    private var repeatActive: Boolean? = null
    /** A message the driver has to act on; shown in the footer until the state moves on. */
    private var recoveryMessage: String? = null
    private var awaitingUsbPermission = false
    private val refresh = object : Runnable {
        override fun run() {
            render()
            // The playhead shows whole seconds; the meters and log only matter while the sheet is up.
            handler.postDelayed(this, if (sheet.visibility == View.VISIBLE) 250L else 1000L)
        }
    }
    private var renderPosted = false
    private val engineListener: () -> Unit = {
        if (!renderPosted) { renderPosted = true; handler.post { renderPosted = false; render() } }
    }

    private val usbPermissionAction get() = "$packageName.IPOD_USB_PERMISSION"
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != usbPermissionAction || !awaitingUsbPermission) return
            awaitingUsbPermission = false
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                recoveryMessage = null
                if (inForeground) startEngine()
            } else {
                recoveryMessage = "USB access was denied. Tap Start to ask again."
            }
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG; window.navigationBarColor = BG
        applyFullscreen()
        val widthDp = resources.configuration.screenWidthDp
        val heightDp = resources.configuration.screenHeightDp
        val wide = widthDp >= 600 && widthDp > heightDp
        // A landscape window under 480 dp tall (1024 × 600 px at density 1.5 gives 683 × 400 dp)
        // keeps the two columns but shrinks the artwork and drops the album line, never the controls.
        val short = wide && heightDp < 480
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        setContentView(root)

        // Backdrop: the artwork, heavily downscaled so it reads as a soft colour field.
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        wash = View(this)
        root.addView(wash, FrameLayout.LayoutParams(-1, -1))
        applyWash(BG)

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(12)) }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        // Top bar: home, title, status, settings.
        val top = row().apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(glyphButton("‹", 32, "Back") { finish() }, LinearLayout.LayoutParams(dp(64), dp(64)))
        top.addView(label("iPod", 20, TEXT, true).apply { setPadding(dp(8), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        statusPill = label("Idle", 14, MUTED).apply {
            background = rounded(Color.argb(60, 255, 255, 255), Color.TRANSPARENT, 16); setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        top.addView(statusPill)
        top.addView(glyphButton("⚙", 26, "iPod settings") { toggleSheet() }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { leftMargin = dp(8) })
        content.addView(top)

        // Main: artwork + text/controls.
        val main = if (wide) row().apply { gravity = Gravity.CENTER_VERTICAL } else column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val artSize = dp(if (short) 200 else if (wide) 300 else 180)
        artworkView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(com.shilapi.xcertplay.host.R.drawable.art_now_playing_placeholder)
            contentDescription = "Album artwork"
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, dp(22).toFloat()) }
            }
            clipToOutline = true
            elevation = dp(12).toFloat()
        }
        main.addView(artworkView, LinearLayout.LayoutParams(artSize, artSize).apply { if (wide) rightMargin = dp(32) else bottomMargin = dp(12) })

        val text = column().apply { gravity = if (wide) Gravity.START else Gravity.CENTER_HORIZONTAL }
        sourceView = label("", 14, MUTED).apply { if (!wide) gravity = Gravity.CENTER; letterSpacing = 0.08f }
        titleView = label("", if (short) 28 else 32, TEXT, true).apply { maxLines = if (short) 1 else 2; ellipsize = android.text.TextUtils.TruncateAt.END; if (!wide) gravity = Gravity.CENTER }
        artistView = label("", 22, TEXT).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; alpha = 0.92f; if (!wide) gravity = Gravity.CENTER }
        albumView = label("", 17, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; if (!wide) gravity = Gravity.CENTER; if (short) visibility = View.GONE }
        text.addView(sourceView)
        text.addView(titleView.apply { setPadding(0, dp(2), 0, 0) }); text.addView(artistView.apply { setPadding(0, dp(4), 0, 0) }); text.addView(albumView.apply { setPadding(0, dp(2), 0, dp(6)) })

        // Seek: a 56 dp touch region with the times inside it, so a thumb can land on it.
        progress = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(TEXT); progressBackgroundTintList = ColorStateList.valueOf(Color.argb(70, 255, 255, 255))
            thumbTintList = ColorStateList.valueOf(TEXT)
            setPadding(dp(8), dp(16), dp(8), dp(16))
            contentDescription = "Track position"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) elapsedView.text = clock(IpodAudioEngine.nowPlaying.durationMillis?.let { it * p / 1000 }) }
                override fun onStartTrackingTouch(bar: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    seeking = false
                    IpodAudioEngine.nowPlaying.durationMillis?.let { IpodAudioEngine.seekTo(it * bar.progress / 1000) }
                }
            })
        }
        text.addView(progress, LinearLayout.LayoutParams(-1, dp(56)))
        val times = row().apply { setPadding(dp(8), 0, dp(8), 0) }
        elapsedView = label("0:00", 14, MUTED); remainingView = label("-0:00", 14, MUTED).apply { gravity = Gravity.END }
        times.addView(elapsedView, LinearLayout.LayoutParams(0, -2, 1f)); times.addView(remainingView, LinearLayout.LayoutParams(0, -2, 1f))
        text.addView(times)

        controls = row().apply { gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL; setPadding(0, dp(if (short) 4 else 10), 0, 0) }
        shuffleButton = glyphButton("⇄", 24, "Shuffle") { IpodAudioEngine.toggleShuffle() }
        repeatButton = glyphButton("↻", 24, "Repeat") { IpodAudioEngine.cycleRepeat() }
        previousButton = glyphButton("⏮", 36, "Previous track") { IpodAudioEngine.previous() }
        nextButton = glyphButton("⏭", 36, "Next track") { IpodAudioEngine.next() }
        playPause = TextView(this).apply {
            this.text = "▶"; textSize = 34f; setTextColor(BG); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            background = feedback(circle(TEXT)); isClickable = true; isFocusable = true; contentDescription = "Play"
            setOnClickListener { IpodAudioEngine.playPause() }
        }
        val big = dp(if (short) 80 else 96); val mid = dp(if (short) 68 else 80); val small = dp(64)
        controls.addView(shuffleButton, LinearLayout.LayoutParams(small, small).apply { rightMargin = dp(10) })
        controls.addView(previousButton, LinearLayout.LayoutParams(mid, mid).apply { rightMargin = dp(6) })
        controls.addView(playPause, LinearLayout.LayoutParams(big, big).apply { leftMargin = dp(6); rightMargin = dp(6) })
        controls.addView(nextButton, LinearLayout.LayoutParams(mid, mid).apply { leftMargin = dp(6) })
        controls.addView(repeatButton, LinearLayout.LayoutParams(small, small).apply { leftMargin = dp(10) })
        text.addView(controls, LinearLayout.LayoutParams(-1, -2))

        // Three equal actions instead of five small chips: Siri, the app launcher, Sound.
        actions = row().apply { setPadding(0, dp(if (short) 8 else 14), 0, 0) }
        actions.addView(button("Siri") { IpodAudioEngine.siri() }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { rightMargin = dp(8) })
        actions.addView(button("Apps") { showApps() }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { rightMargin = dp(8) })
        actions.addView(button("Sound") { startActivity(Intent(this, DiPlayActivity::class.java).putExtra("page", "eq")) }, LinearLayout.LayoutParams(0, dp(64), 1f))
        text.addView(actions, LinearLayout.LayoutParams(-1, -2))

        startButton = TextView(this).apply {
            this.text = "Start"; textSize = 20f; setTextColor(BG); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            background = feedback(rounded(TEXT, Color.TRANSPARENT, 28)); isClickable = true; isFocusable = true
            setOnClickListener { toggleEngine() }
        }
        text.addView(startButton, LinearLayout.LayoutParams(if (wide) dp(240) else -1, dp(64)).apply { topMargin = dp(14) })

        main.addView(text, if (wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        content.addView(main, LinearLayout.LayoutParams(-1, 0, 1f))

        footer = label("", 14, MUTED).apply { gravity = Gravity.CENTER; maxLines = 2 }
        content.addView(footer)

        buildSheet(root)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbPermissionReceiver, IntentFilter(usbPermissionAction), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbPermissionReceiver, IntentFilter(usbPermissionAction))
        }
    }

    /** The iPhone apps worth a direct launch, as a grid of 64 dp buttons. */
    private fun showApps() {
        val apps = listOf("Music" to "com.apple.Music", "Podcasts" to "com.apple.podcasts", "Spotify" to "com.spotify.client", "YouTube Music" to "com.google.ios.youtubemusic")
        val grid = GridLayout(this).apply { columnCount = 2; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val dialog = AlertDialog.Builder(this).setTitle("Open on the iPhone").setView(grid).setNegativeButton("Close", null).create()
        apps.forEach { (name, bundle) ->
            grid.addView(button(name) {
                IpodAudioEngine.launchApp(bundle); dialog.dismiss(); toast("Opening $name on the iPhone")
            }, GridLayout.LayoutParams().apply {
                width = 0; height = dp(64)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply { minimumHeight = dp(64); minimumWidth = dp(96); textSize = 18f; isAllCaps = false }
    }

    /** Settings and diagnostics, slid over the bottom of the screen from the gear button. */
    private fun buildSheet(root: FrameLayout) {
        sheet = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(Color.argb(140, 0, 0, 0)); setOnClickListener { toggleSheet() } }
        val panelColumn = column().apply { background = rounded(SURFACE, BORDER, 22); isClickable = true; setPadding(dp(22), dp(12), dp(22), dp(18)) }
        // Fixed header with an explicit Close: on a resistive screen "tap the dim area" is not a reliable way out.
        val head = row().apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("iPod audio", 22, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        sheetStartStop = TextView(this).apply {
            text = "Start"; textSize = 17f; setTextColor(BG); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            background = feedback(rounded(ACCENT, Color.TRANSPARENT, 22)); isClickable = true; isFocusable = true; setOnClickListener { toggleEngine() }
        }
        head.addView(sheetStartStop, LinearLayout.LayoutParams(dp(130), dp(64)).apply { rightMargin = dp(10) })
        head.addView(button("Close") { toggleSheet() }, LinearLayout.LayoutParams(dp(110), dp(64)))
        panelColumn.addView(head, LinearLayout.LayoutParams(-1, dp(64)))
        val panel = ScrollView(this)
        sheetBody = column().apply { setPadding(0, dp(4), 0, 0) }
        panel.addView(sheetBody)
        panelColumn.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))
        sheet.addView(panelColumn, FrameLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.8).toInt(), Gravity.BOTTOM))
        root.addView(sheet, FrameLayout.LayoutParams(-1, -1))

        meter = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressTintList = ColorStateList.valueOf(ACCENT) }
        sheetBody.addView(meter, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(6) })

        sheetBody.addView(label("Volume", 15, MUTED).apply { setPadding(0, dp(12), 0, 0) })
        sheetBody.addView(SeekBar(this).apply {
            max = 100; progress = (IpodSettings.volume(this@IpodModeActivity) * 100).toInt()
            progressTintList = ColorStateList.valueOf(ACCENT); thumbTintList = ColorStateList.valueOf(ACCENT)
            setPadding(dp(8), dp(16), dp(8), dp(16)); contentDescription = "iPod volume"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) { IpodAudioEngine.volume = p / 100f; if (fromUser) IpodSettings.saveVolume(this@IpodModeActivity, p / 100f) }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit; override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }, LinearLayout.LayoutParams(-1, dp(56)))
        sheetBody.addView(switchRow("Gain boost (+6 dB)", "For quiet phones; the limiter catches peaks.", IpodSettings.gainBoost(this)) {
            IpodAudioEngine.gainBoost = it; IpodSettings.saveGainBoost(this, it)
        })
        sheetBody.addView(switchRow("Direct USB audio driver", "Reads the iPhone's USB audio packets straight from the bus. Off = Android's USB audio input.", IpodSettings.directUsbAudio(this)) {
            IpodSettings.saveDirectUsbAudio(this, it); if (IpodAudioEngine.isRunning()) toast("Applies at the next connection")
        })
        sheetBody.addView(label("When the iPhone is plugged in", 15, TEXT).apply { setPadding(0, dp(12), 0, dp(6)) })
        val choiceRow = row()
        val choiceButtons = ArrayList<Pair<String, TextView>>()
        listOf(IpodSettings.CHOICE_ASK to "Ask me", IpodSettings.CHOICE_CARPLAY to "CarPlay", IpodSettings.CHOICE_IPOD to "iPod audio").forEachIndexed { index, (value, title) ->
            val b = chip(title, IpodSettings.plugInChoice(this) == value) {
                IpodSettings.savePlugInChoice(this, value)
                choiceButtons.forEach { (v, view) -> styleChip(view, v == value) }
            }
            choiceButtons += value to b
            choiceRow.addView(b, LinearLayout.LayoutParams(0, dp(64), 1f).apply { if (index < 2) rightMargin = dp(8) })
        }
        sheetBody.addView(choiceRow)
        sheetBody.addView(label("Charging current announced to the iPhone", 15, TEXT).apply { setPadding(0, dp(14), 0, dp(6)) })
        val currentButtons = ArrayList<TextView>()
        var currents: LinearLayout? = null
        IpodSettings.CHARGE_CURRENTS.forEachIndexed { index, ma ->
            if (index % 4 == 0) { currents = row().apply { if (index > 0) setPadding(0, dp(8), 0, 0) }; sheetBody.addView(currents) }
            val b = chip("$ma mA", IpodSettings.chargeCurrent(this) == ma) {
                IpodSettings.saveChargeCurrent(this, ma)
                currentButtons.forEachIndexed { i, other -> styleChip(other, IpodSettings.CHARGE_CURRENTS[i] == ma) }
                if (IpodAudioEngine.isRunning()) toast("Applies at the next connection")
            }
            currentButtons += b
            currents!!.addView(b, LinearLayout.LayoutParams(0, dp(64), 1f).apply { if (index % 4 != 3) rightMargin = dp(8) })
        }
        val remainder = IpodSettings.CHARGE_CURRENTS.size % 4
        if (remainder != 0) repeat(4 - remainder) { currents!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { rightMargin = dp(8) }) }
        val links = row().apply { setPadding(0, dp(16), 0, 0) }
        links.addView(button("Sound") { startActivity(Intent(this, DiPlayActivity::class.java).putExtra("page", "eq")) }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { rightMargin = dp(8) })
        links.addView(button("Request USB audio") {
            if (IpodAudioEngine.capabilities.usbAudio) { IpodAudioEngine.restartAudio(); toast("Asked the iPhone to (re)start USB audio") }
            else { IpodAudioEngine.forceAudioRequest(); toast("Experimental: sent the USB-audio request the iPhone did not accept in identification; check the log") }
        }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { rightMargin = dp(8) })
        links.addView(button("Switch to CarPlay") {
            stopEngine()
            startActivity(Intent(this, DiPlayActivity::class.java).putExtra("connect_usb", true))
            finish()
        }, LinearLayout.LayoutParams(0, dp(64), 1f))
        sheetBody.addView(links)
        logView = label("", 12, MUTED).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(12), 0, 0) }
        sheetBody.addView(logView)
    }

    private fun toggleSheet() {
        val show = sheet.visibility != View.VISIBLE
        sheet.visibility = if (show) View.VISIBLE else View.GONE
        if (show) render()
    }

    /** Edge to edge: the head unit's bars hide and come back with a swipe from the edge. */
    private fun applyFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }
    /** Plugging the iPhone in while this screen is up starts iPod audio by itself. */
    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
            val device = intent.getParcelableExtra<android.hardware.usb.UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            if (device.vendorId != com.shilapi.xcertplay.transport.IphoneUsbMatcher.APPLE_VENDOR_ID) return
            if (IpodAudioEngine.isRunning()) return
            IpodAudioEngine.log(this@IpodModeActivity, "iPhone plugged in while the iPod screen is open: starting")
            // Give Android a moment to settle permission and enumeration before claiming interfaces.
            handler.removeCallbacks(autoStart); handler.postDelayed(autoStart, 900)
        }
    }
    private val autoStart = Runnable { if (!IpodAudioEngine.isRunning() && inForeground) startEngine() }

    override fun onResume() {
        super.onResume(); applyFullscreen()
        inForeground = true
        runCatching { registerReceiver(attachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)) }
    }
    override fun onPause() {
        inForeground = false
        handler.removeCallbacks(autoStart)
        runCatching { unregisterReceiver(attachReceiver) }
        super.onPause()
    }

    override fun onStart() {
        super.onStart(); IpodAudioEngine.listeners.add(engineListener); handler.post(refresh)
        if (intent.getBooleanExtra("auto_start", false)) { intent.removeExtra("auto_start"); if (!IpodAudioEngine.isRunning()) startEngine() }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent) }
    override fun onStop() { IpodAudioEngine.listeners.remove(engineListener); handler.removeCallbacks(refresh); super.onStop() }
    override fun onDestroy() {
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    override fun onBackPressed() { if (sheet.visibility == View.VISIBLE) toggleSheet() else super.onBackPressed() }

    private fun toggleEngine() { if (IpodAudioEngine.isRunning()) stopEngine() else startEngine() }

    private fun startEngine() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 41); return
        }
        val usb = getSystemService(UsbManager::class.java)
        val iphone = IpodAudioEngine.findIphone(this)
        if (usb == null || iphone == null) { recoveryMessage = "No iPhone found. Plug it in with a data cable, unlock it, then tap Start."; render(); return }
        if (!usb.hasPermission(iphone)) {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            awaitingUsbPermission = true
            recoveryMessage = "Allow USB access on the Android prompt to continue."
            usb.requestPermission(iphone, PendingIntent.getBroadcast(this, 8, Intent(usbPermissionAction).setPackage(packageName), flags))
            render(); return
        }
        recoveryMessage = null
        if (CarPlayBackgroundSession.hasSession()) {
            CarPlayBackgroundSession.stop { runOnUiThread { startService(Intent(this, IpodAudioService::class.java)) } }
        } else startService(Intent(this, IpodAudioService::class.java))
    }

    private fun stopEngine() { startService(Intent(this, IpodAudioService::class.java).setAction(IpodAudioService.ACTION_STOP)) }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startEngine()
            else { recoveryMessage = "Microphone permission is needed for Android's USB audio input. Tap Start to ask again."; render() }
        }
    }

    private fun render() {
        val running = IpodAudioEngine.isRunning()
        val state = IpodAudioEngine.state
        val ready = state == IpodAudioEngine.State.READY
        val info = IpodAudioEngine.nowPlaying
        val caps = IpodAudioEngine.capabilities
        if (running) awaitingUsbPermission = false
        if (ready) recoveryMessage = null

        statusPill.set(when (state) {
            IpodAudioEngine.State.IDLE, IpodAudioEngine.State.STOPPED -> "Not connected"
            IpodAudioEngine.State.READY -> if (IpodAudioEngine.audioActive) "USB audio" else "Connected"
            IpodAudioEngine.State.FAILED -> "Needs attention"
            else -> "Connecting…"
        })
        statusPill.setTextColor(when (state) { IpodAudioEngine.State.READY -> TEXT; IpodAudioEngine.State.FAILED -> WARNING; else -> MUTED })

        if (ready && info.title != null) {
            titleView.set(info.title ?: "")
            artistView.set(info.artist ?: "")
            albumView.set(info.album ?: "")
        } else if (ready) {
            titleView.set("Nothing playing")
            artistView.set("Play something on the iPhone, or open an app below.")
            albumView.set("")
        } else if (running) {
            titleView.set(IpodAudioEngine.status.substringBefore('.').ifEmpty { "Connecting" })
            artistView.set("Keep the iPhone unlocked.")
            albumView.set("")
        } else {
            titleView.set("iPhone")
            artistView.set(if (state == IpodAudioEngine.State.FAILED) "Connection failed" else "Plug it in with a data cable, unlock it, then Start.")
            albumView.set("")
        }
        sourceView.set(listOfNotNull(
            info.sourceApp?.takeIf { ready }?.uppercase(),
            when (IpodAudioEngine.shuffle) { 1 -> "Shuffle"; 2 -> "Shuffle albums"; else -> null },
            when (IpodAudioEngine.repeat) { 1 -> "Repeat one"; 2 -> "Repeat all"; else -> null },
        ).joinToString("  ·  "))

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
            elapsedView.set(clock(elapsed))
        }
        remainingView.set(if (duration != null && elapsed != null) "-" + clock((duration - elapsed).coerceAtLeast(0)) else clock(duration))
        // Transport comes from the HID report, seeking/shuffle/repeat from SetNowPlaying: gate them separately.
        setActionEnabled(progress, ready && caps.setNowPlaying && (duration ?: 0L) > 0L)
        setActionEnabled(previousButton, ready && caps.hid)
        setActionEnabled(playPause, ready && caps.hid)
        setActionEnabled(nextButton, ready && caps.hid)
        setActionEnabled(shuffleButton, ready && caps.setNowPlaying)
        setActionEnabled(repeatButton, ready && caps.setNowPlaying)

        playPause.set(if (IpodAudioEngine.playing) "⏸" else "▶")
        playPause.contentDescription = if (IpodAudioEngine.playing) "Pause" else "Play"
        controls.visibility = if (running) View.VISIBLE else View.GONE
        actions.visibility = if (ready) View.VISIBLE else View.GONE
        val shuffleOn = (IpodAudioEngine.shuffle ?: 0) != 0
        if (shuffleOn != shuffleActive) { shuffleActive = shuffleOn; styleGlyph(shuffleButton, shuffleOn) }
        shuffleButton.contentDescription = if (shuffleOn) "Shuffle on" else "Shuffle off"
        val repeatOn = (IpodAudioEngine.repeat ?: 0) != 0
        if (repeatOn != repeatActive) { repeatActive = repeatOn; styleGlyph(repeatButton, repeatOn) }
        repeatButton.set(when (IpodAudioEngine.repeat) { 1 -> "↻¹"; else -> "↻" })
        repeatButton.contentDescription = when (IpodAudioEngine.repeat) { 1 -> "Repeat one"; 2 -> "Repeat all"; else -> "Repeat off" }
        startButton.visibility = if (running) View.GONE else View.VISIBLE
        startButton.set(if (state == IpodAudioEngine.State.FAILED) "Retry" else "Start")
        sheetStartStop.set(if (running) "Stop" else "Start")

        // The footer carries one line the driver can act on; counters live in the sheet's log.
        val recovery = recoveryMessage
        footer.set(when {
            recovery != null -> recovery
            state == IpodAudioEngine.State.FAILED -> IpodAudioEngine.status.ifEmpty { "The connection failed." } + " Tap Retry."
            !running -> "Music, podcasts and any app's audio from the iPhone, with track info and steering-wheel keys. No CarPlay needed."
            ready && IpodAudioEngine.audioActive -> if (caps.hid) "" else "The iPhone declined remote control: use the phone or Siri."
            ready -> if (caps.usbAudio) "Waiting for the iPhone to start USB audio… If nothing plays, open ⚙ and tap Request USB audio." else "The iPhone refused the USB-audio request; audio may stay on the phone. Try “Request USB audio” under ⚙."
            else -> IpodAudioEngine.status
        })
        footer.setTextColor(if (recovery != null || state == IpodAudioEngine.State.FAILED) WARNING else MUTED)
        if (sheet.visibility == View.VISIBLE) {
            meter.progress = (IpodAudioEngine.level * 2500).toInt().coerceIn(0, 1000)
            val tail = if (ready && IpodAudioEngine.audioActive)
                "${if (IpodAudioEngine.audioPath == "direct") "Direct USB audio" else "Android audio input"} · ${IpodAudioEngine.sampleRate / 1000.0} kHz · ${IpodAudioEngine.framesPlayed / maxOf(1, IpodAudioEngine.sampleRate)} s · underruns ${IpodAudioEngine.underruns} · ${IpodAudioEngine.power.describe()}\n"
            else ""
            logView.set(tail + IpodAudioEngine.recentLog.takeLast(10).joinToString("\n"))
        }
    }

    private fun setActionEnabled(view: View, enabled: Boolean) {
        if (view.isEnabled == enabled && (view.alpha == 1f) == enabled) return
        view.isEnabled = enabled
        view.isClickable = enabled
        view.alpha = if (enabled) 1f else 0.38f
    }

    // ---- colour from the artwork ----
    private fun dominantColor(bitmap: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        var r = 0L; var g = 0L; var b = 0L
        for (y in 0 until 8) for (x in 0 until 8) { val c = small.getPixel(x, y); r += Color.red(c); g += Color.green(c); b += Color.blue(c) }
        // createScaledBitmap returns its input when the size already matches: never recycle the engine's bitmap.
        if (small !== bitmap) small.recycle()
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
    /** Assigns only on change: TextView.setText always relayouts, and render runs every second. */
    private fun TextView.set(value: String) { if (text.toString() != value) text = value }
    /** Pressed feedback on top of any shape, so a tap on the resistive panel is visibly acknowledged. */
    private fun feedback(content: Drawable): Drawable = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), content, null)
    private fun glyphButton(glyph: String, size: Int, description: String, click: () -> Unit) = TextView(this).apply {
        text = glyph; textSize = size.toFloat(); setTextColor(TEXT); gravity = Gravity.CENTER; contentDescription = description
        background = feedback(circle(Color.argb(28, 255, 255, 255))); isClickable = true; isFocusable = true; setOnClickListener { click() }
    }
    private fun styleGlyph(view: TextView, active: Boolean) {
        view.setTextColor(if (active) BG else TEXT)
        view.background = feedback(circle(if (active) TEXT else Color.argb(28, 255, 255, 255)))
        view.isSelected = active
    }
    private fun chip(title: String, active: Boolean = false, click: () -> Unit) = TextView(this).apply {
        text = title; textSize = 16f; gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8))
        styleChip(this, active); isClickable = true; isFocusable = true; setOnClickListener { click() }
    }
    private fun styleChip(view: TextView, active: Boolean) {
        view.setTextColor(if (active) BG else TEXT)
        view.background = feedback(rounded(if (active) TEXT else Color.argb(36, 255, 255, 255), Color.TRANSPARENT, 18))
        view.isSelected = active
    }
    private fun button(title: String, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 18f; setTextColor(TEXT); stateListAnimator = null
        background = feedback(rounded(Color.argb(36, 255, 255, 255), BORDER, 16)); setOnClickListener { click() }
    }
    private fun rounded(color: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat(); if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke) }
    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun switchRow(title: String, description: String, checked: Boolean, onChanged: (Boolean) -> Unit): View {
        val r = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)); minimumHeight = dp(64); isClickable = true; isFocusable = true }
        val text = column().apply { addView(label(title, 17, TEXT)); if (description.isNotEmpty()) addView(label(description, 13, MUTED)) }
        r.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        val switch = Switch(this).apply { isChecked = checked; minimumHeight = dp(56); minimumWidth = dp(56); contentDescription = title; setOnCheckedChangeListener { _, v -> onChanged(v) } }
        r.addView(switch)
        r.setOnClickListener { switch.performClick() }   // the whole row is the target, not the small switch
        return r
    }

    companion object {
        /** True while this screen is the one on screen; the CarPlay host hands plug-ins to it. */
        @Volatile var inForeground = false
        private val BG = Color.rgb(10, 12, 18); private val SURFACE = Color.rgb(24, 28, 38); private val BORDER = Color.rgb(50, 56, 72)
        private val ACCENT = Color.rgb(166, 200, 255); private val TEXT = Color.rgb(246, 247, 250); private val MUTED = Color.rgb(170, 178, 194)
        private val WARNING = Color.rgb(255, 180, 120)
    }
}
