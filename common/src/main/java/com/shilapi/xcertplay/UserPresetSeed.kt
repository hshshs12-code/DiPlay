// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork: keeps the sound the user had before user presets existed, as the preset "Good".
package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.media.AudioEffectSettings
import com.shilapi.xcertplay.media.UserPresets

/**
 * The first time 24.45 starts it snapshots whatever the sound settings are into a user preset
 * called "Good": the owner asked for the EQ and sound settings in use at the collector run to be
 * kept under that name, and the head unit is the only place they exist. Runs once per install.
 */
object UserPresetSeed {
    const val NAME = "Good"
    private const val PREFS = "diplay_user_presets"
    private const val KEY_SEEDED = "seeded_good"

    fun seedOnce(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        if (UserPresets.find(app, NAME) != null) return
        val current = AudioEffectSettings.load(app)
        if (!current.enabled && !current.dspMode) return   // never touched: nothing worth keeping
        UserPresets.save(app, NAME, current)
    }
}
