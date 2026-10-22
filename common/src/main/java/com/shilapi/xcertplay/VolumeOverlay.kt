// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/** A transient "Volume 23 / 30" pill over any screen, for the virtual level of [CleanVolume]. */
class VolumeOverlay(private val activity: Activity, root: FrameLayout) {
    private val handler = Handler(Looper.getMainLooper())
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val view = TextView(activity).apply {
        textSize = 26f; setTextColor(Color.rgb(246, 247, 250)); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { setColor(Color.argb(210, 24, 28, 38)); cornerRadius = dp(24).toFloat(); setStroke(dp(1), Color.rgb(50, 56, 72)) }
        setPadding(dp(28), dp(14), dp(28), dp(14)); visibility = View.GONE; elevation = dp(30).toFloat()
    }
    private val hide = Runnable { view.visibility = View.GONE }
    private val listener: (Int, Int) -> Unit = { index, max -> activity.runOnUiThread { show(index, max) } }

    init { root.addView(view, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(36) }) }

    fun show(index: Int, max: Int) {
        view.text = if (index == 0) "Volume muted" else "Volume $index / $max"
        view.visibility = View.VISIBLE
        handler.removeCallbacks(hide); handler.postDelayed(hide, 1_500)
    }

    fun attach() { CleanVolume.listeners.add(listener) }
    fun detach() { CleanVolume.listeners.remove(listener); handler.removeCallbacks(hide) }
}
