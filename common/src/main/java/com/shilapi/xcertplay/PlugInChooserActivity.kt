// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Shown when the iPhone is plugged in: two big targets, CarPlay or iPod audio, in the iPod
 * screen's design language. "Remember my choice" turns it into an automatic launch.
 */
class PlugInChooserActivity : Activity() {
    private var remember = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.statusBarColor = BG; window.navigationBarColor = BG
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val wide = resources.configuration.screenWidthDp >= 600
        val root = FrameLayout(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(WASH, BG, BG)) }
        setContentView(root)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(18), dp(28), dp(18)) }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("iPhone connected", 26, TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label("What would you like?", 14, MUTED).apply {
            background = rounded(Color.argb(60, 255, 255, 255), Color.TRANSPARENT, 14); setPadding(dp(12), dp(6), dp(12), dp(6))
        })
        content.addView(header)

        val choices = LinearLayout(this).apply { orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        choices.addView(choice("CarPlay", "Maps, calls, messages, music — the full iPhone screen.", com.shilapi.xcertplay.host.R.drawable.ic_carplay, "") { chooseCarPlay() },
            if (wide) LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(10) } else LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(10) })
        choices.addView(choice("iPod audio", "Music without CarPlay: track info, artwork, better sound, steering-wheel keys.", null, "♪") { chooseIpod() },
            if (wide) LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(10) } else LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        content.addView(choices, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16); bottomMargin = dp(12) })

        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label("Remember my choice", 16, TEXT))
        text.addView(label("Next plug-ins start it straight away. Change it later on the iPod screen (⚙) or in Settings.", 12, MUTED))
        footer.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(Switch(this).apply { isChecked = false; setOnCheckedChangeListener { _, v -> remember = v } })
        footer.addView(TextView(this).apply {
            this.text = "Not now"; textSize = 15f; setTextColor(TEXT); gravity = Gravity.CENTER; setPadding(dp(18), dp(10), dp(18), dp(10))
            background = rounded(Color.argb(36, 255, 255, 255), Color.argb(60, 255, 255, 255), 18); isClickable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(16) })
        content.addView(footer)
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

    private fun choice(title: String, subtitle: String, icon: Int?, glyph: String, click: () -> Unit): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = rounded(Color.argb(32, 255, 255, 255), Color.argb(60, 255, 255, 255), 24)
            isClickable = true; isFocusable = true; setOnClickListener { click() }; setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        if (icon != null) tile.addView(ImageView(this).apply { setImageResource(icon); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(96), dp(96)))
        else tile.addView(TextView(this).apply { text = glyph; textSize = 64f; setTextColor(TEXT); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(96), dp(96)))
        tile.addView(label(title, 30, TEXT, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
        tile.addView(label(subtitle, 14, MUTED).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), 0) })
        return tile
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply { this.text = text; textSize = size.toFloat(); setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD) }
    private fun rounded(fill: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(radiusDp).toFloat(); if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke) }

    companion object {
        private val BG = Color.rgb(10, 12, 18); private val WASH = Color.rgb(30, 36, 54)
        private val TEXT = Color.rgb(246, 247, 250); private val MUTED = Color.rgb(170, 178, 194)
    }
}
