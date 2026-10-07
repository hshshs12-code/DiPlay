// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork: a manual "parked" switch for iOS video in car on head units without vehicle data.
package com.shilapi.xcertplay

import android.content.Context

object ManualParkedVideo {
    private const val PREFS = "diplay_manual_parked_video"
    private const val KEY_ENABLED = "enabled"

    /** Offer video playback to the iPhone at session start (persisted). */
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) parked = false
    }

    /** The driver's own assertion that the car is parked. Never persisted; off at every launch. */
    @Volatile var parked: Boolean = false
}
