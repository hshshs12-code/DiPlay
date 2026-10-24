// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork: switching between leaving the head unit's effects alone, holding them flat, and driving them.
package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.media.AudioEffectSettings
import com.shilapi.xcertplay.media.AudioEffectSettingsJson
import org.json.JSONObject

/**
 * One place for the three ways DiPlay treats the head unit's own session-0 effects. Direct mode
 * is the phone's "Unit EQ": DiPlay's own processing goes off (its settings are kept aside) and the
 * phone's sliders drive the unit's equalizer, bass boost, virtualizer and reverb. Leaving direct
 * mode puts DiPlay's processing back the way it was.
 */
object UnitFxControl {
    private const val PREFS = "diplay_unit_fx"
    private const val KEY_SETTINGS_BEFORE_DIRECT = "settings_before_direct"

    fun mode(context: Context): VendorEffects.Mode = AirPlayPersistence.loadUnitFxMode(context)

    fun target(context: Context): VendorEffects.Target? = VendorEffects.Target.fromJson(AirPlayPersistence.loadUnitFxTarget(context))

    /** Switches modes, applying the engines and DiPlay's own processing accordingly. Blocking; call off the main thread. */
    fun setMode(context: Context, mode: VendorEffects.Mode, log: (String) -> Unit): VendorEffects.Snapshot {
        val app = context.applicationContext
        val previous = mode(app)
        if (previous == VendorEffects.Mode.DIRECT && mode != VendorEffects.Mode.DIRECT) restoreDiPlayProcessing(app, log)
        AirPlayPersistence.saveUnitFxMode(app, mode)
        return when (mode) {
            VendorEffects.Mode.OFF -> VendorEffects.release(log)
            VendorEffects.Mode.NEUTRAL -> VendorEffects.control(VendorEffects.Target.FLAT, log)
            VendorEffects.Mode.DIRECT -> {
                if (previous != VendorEffects.Mode.DIRECT) parkDiPlayProcessing(app, log)
                val target = target(app) ?: VendorEffects.Target.from(VendorEffects.read()).also { AirPlayPersistence.saveUnitFxTarget(app, it.toJson().toString()) }
                VendorEffects.control(target, log)
            }
        }
    }

    /** Merges [changes] into the direct-mode target and applies it; only meaningful in direct mode. */
    fun adjust(context: Context, changes: JSONObject, log: (String) -> Unit): VendorEffects.Snapshot? {
        val app = context.applicationContext
        if (mode(app) != VendorEffects.Mode.DIRECT) return null
        val base = target(app) ?: VendorEffects.Target.from(VendorEffects.read())
        val next = base.merge(changes)
        AirPlayPersistence.saveUnitFxTarget(app, next.toJson().toString())
        return VendorEffects.control(next, log)
    }

    /** DiPlay's chain goes silent in direct mode so only the unit's effects shape the sound. */
    private fun parkDiPlayProcessing(app: Context, log: (String) -> Unit) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = AudioEffectSettings.current(app)
        if (!prefs.contains(KEY_SETTINGS_BEFORE_DIRECT)) prefs.edit().putString(KEY_SETTINGS_BEFORE_DIRECT, AudioEffectSettingsJson.toJson(current).toString()).apply()
        if (current.enabled || current.dspMode) AudioEffectSettings.save(app, current.copy(enabled = false, dspMode = false))
        log("unit fx: direct mode on, DiPlay processing parked")
    }

    private fun restoreDiPlayProcessing(app: Context, log: (String) -> Unit) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_SETTINGS_BEFORE_DIRECT, null)
        prefs.edit().remove(KEY_SETTINGS_BEFORE_DIRECT).apply()
        if (saved == null) return
        runCatching { AudioEffectSettings.save(app, AudioEffectSettingsJson.merge(AudioEffectSettings.current(app), JSONObject(saved))) }
            .onSuccess { log("unit fx: direct mode off, DiPlay processing restored") }
            .onFailure { log("unit fx: could not restore DiPlay processing: ${it.message}") }
    }
}
