// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Shown when the iPhone is plugged in: two big targets, CarPlay or iPod audio, in the iPod
 * screen's design language. "Remember my choice" turns it into an automatic launch. Both cards
 * lock after the first tap so a double tap on the resistive panel cannot start two sessions.
 */
class PlugInChooserActivity : Activity() {
    private var remember = false
    private var choosing = false
    private lateinit var cards: List<View>
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        remember = savedInstanceState?.getBoolean("remember") ?: false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.statusBarColor = BG; window.navigationBarColor = BG
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val wide = resources.configuration.screenWidthDp >= 600
        val root = FrameLayout(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(WASH, BG, BG)) }
        setContentView(root)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), dp(16)) }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "‹"; textSize = 32f; setTextColor(TEXT); gravity = Gravity.CENTER; contentDescription = "Not now"
            background = feedback(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(28, 255, 255, 255)) })
            isClickable = true; isFocusable = true; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { rightMargin = dp(8) })
        header.addView(label("iPhone connected", 26, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label("Ready", 15, MUTED).apply {
            background = rounded(Color.argb(60, 255, 255, 255), Color.TRANSPARENT, 16); setPadding(dp(14), dp(8), dp(14), dp(8))
        })
        content.addView(header)
        content.addView(label("How would you like to connect?", 30, TEXT, true).apply { setPadding(0, dp(10), 0, 0) })
        hint = label("Choose a mode for this drive.", 16, MUTED).apply { setPadding(0, dp(2), 0, 0) }
        content.addView(hint)

        val choices = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val carPlay = choice("CarPlay", "Maps, calls and music", "Use CarPlay", true, com.shilapi.xcertplay.host.R.drawable.ic_carplay, "") { chooseOnce { chooseCarPlay() } }
        val ipod = choice("iPod audio", "Music and podcasts over USB", "Use iPod audio", false, null, "♪") { chooseOnce { chooseIpod() } }
        cards = listOf(carPlay, ipod)
        choices.addView(carPlay, if (wide) LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(10) } else LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(10) })
        choices.addView(ipod, if (wide) LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(10) } else LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        content.addView(choices, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14); bottomMargin = dp(12) })

        // Remember: the whole 64 dp row is the target, not the small switch inside it.
        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val rememberRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = feedback(rounded(Color.argb(28, 255, 255, 255), Color.argb(60, 255, 255, 255), 18))
            setPadding(dp(18), 0, dp(12), 0); isClickable = true; isFocusable = true
        }
        val rememberSwitch = Switch(this).apply {
            isChecked = remember; minimumHeight = dp(56); minimumWidth = dp(56); contentDescription = "Remember my choice"
            setOnCheckedChangeListener { _, v -> remember = v }
        }
        rememberRow.addView(label("Remember my choice", 19, TEXT), LinearLayout.LayoutParams(0, -2, 1f))
        rememberRow.addView(rememberSwitch)
        rememberRow.setOnClickListener { rememberSwitch.performClick() }
        footer.addView(rememberRow, LinearLayout.LayoutParams(0, dp(64), 1f))
        footer.addView(TextView(this).apply {
            this.text = "Not now"; textSize = 18f; setTextColor(TEXT); gravity = Gravity.CENTER
            background = feedback(rounded(Color.argb(36, 255, 255, 255), Color.argb(60, 255, 255, 255), 18)); isClickable = true; isFocusable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(140), dp(64)).apply { leftMargin = dp(12) })
        content.addView(footer)
        content.addView(label("Change this later in Settings or on the iPod screen (⚙).", 14, MUTED).apply { setPadding(dp(4), dp(8), 0, 0) })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("remember", remember)
    }

    /** First tap wins; both cards dim and the hint shows what is starting. */
    private fun chooseOnce(action: () -> Unit) {
        if (choosing) return
        choosing = true
        cards.forEach { it.isEnabled = false; it.alpha = 0.5f }
        hint.text = "Starting…"
        try { action() } catch (error: RuntimeException) {
            choosing = false
            cards.forEach { it.isEnabled = true; it.alpha = 1f }
            hint.text = "Choose a mode for this drive."
            Toast.makeText(this, "Could not open this mode. Try again.", Toast.LENGTH_LONG).show()
        }
    }

    private fun chooseCarPlay() {
        if (remember) IpodSettings.savePlugInChoice(this, IpodSettings.CHOICE_CARPLAY)
        AirPlayPersistence.saveWirelessEnabled(this, false)
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }

    private fun chooseIpod() {
        if (remember) IpodSettings.savePlugInChoice(this, IpodSettings.CHOICE_IPOD)
        startActivity(Intent(this, IpodModeActivity::class.java).putExtra("auto_start", true))
        finish()
    }

    private fun choice(title: String, subtitle: String, action: String, primary: Boolean, icon: Int?, glyph: String, click: () -> Unit): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = feedback(rounded(Color.argb(32, 255, 255, 255), Color.argb(60, 255, 255, 255), 24))
            isClickable = true; isFocusable = true; setOnClickListener { click() }; setPadding(dp(20), dp(16), dp(20), dp(16))
            contentDescription = "$action: $subtitle"
        }
        if (icon != null) tile.addView(ImageView(this).apply { setImageResource(icon); scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(80), dp(80)))
        else tile.addView(TextView(this).apply { text = glyph; textSize = 56f; setTextColor(TEXT); gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(80), dp(80)))
        tile.addView(label(title, 30, TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
        tile.addView(label(subtitle, 17, MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(4), dp(8), 0) })
        // The whole card is the target; the button inside is the visible affordance.
        tile.addView(TextView(this).apply {
            text = action; textSize = 20f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) BG else TEXT)
            background = rounded(if (primary) TEXT else Color.argb(40, 255, 255, 255), if (primary) Color.TRANSPARENT else Color.argb(70, 255, 255, 255), 28)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(14) })
        return tile
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply { this.text = text; textSize = size.toFloat(); setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD) }
    private fun rounded(fill: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(radiusDp).toFloat(); if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke) }
    private fun feedback(content: Drawable): Drawable = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), content, null)

    companion object {
        private val BG = Color.rgb(10, 12, 18); private val WASH = Color.rgb(30, 36, 54)
        private val TEXT = Color.rgb(246, 247, 250); private val MUTED = Color.rgb(170, 178, 194)
    }
}
