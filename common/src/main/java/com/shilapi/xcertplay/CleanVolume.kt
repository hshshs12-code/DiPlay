// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork: hold the head unit at its one clean volume step and do the volume in DiPlay.
package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager
import com.shilapi.xcertplay.media.EqualizerDsp
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.pow

/**
 * Some MediaTek head units distort on every volume step but one: below the clean step the
 * firmware's own loudness processing clips a full-scale signal, above it the amplifier gain runs
 * out of headroom. The table that does this lives in the MCU and cannot be edited, so while a
 * DiPlay session runs the Android media volume is parked at the clean step and the knob drives a
 * digital volume inside DiPlay's chain instead (float, dithered once at the output). Every knob
 * step still arrives as a VOLUME_CHANGED broadcast: the difference from the parked step is applied
 * to the virtual level and the real index is put back. When the last session ends, the real index
 * is restored to the virtual level, so the radio and the rest of the unit are unaffected.
 *
 * The virtual curve is `(index / max)^2`: −6 dB at 70 % of the range, −12 dB at half, mute at 0.
 */
object CleanVolume {
    private const val PREFS = "diplay_clean_volume"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_STEP = "step"
    const val DEFAULT_STEP = 20
    private const val CURVE_EXPONENT = 2.0

    @Volatile var active = false; private set
    @Volatile private var appContext: Context? = null
    @Volatile var virtualIndex = 0; private set
    @Volatile var maxIndex = 0; private set
    @Volatile var cleanStep = DEFAULT_STEP; private set
    /** Observers of (virtualIndex, maxIndex) for the on-screen level; called on the broadcast thread. */
    val listeners = CopyOnWriteArrayList<(Int, Int) -> Unit>()

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
    fun saveEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    fun step(context: Context): Int = prefs(context).getInt(KEY_STEP, DEFAULT_STEP)
    fun saveStep(context: Context, step: Int) = prefs(context).edit().putInt(KEY_STEP, step.coerceAtLeast(1)).apply()

    /** Linear gain for a virtual level; pure, for tests. */
    fun gainFor(index: Int, max: Int): Float {
        if (max <= 0 || index <= 0) return 0f
        return (index.coerceAtMost(max).toDouble() / max).pow(CURVE_EXPONENT).toFloat()
    }

    /** The virtual level after the knob moved the real index from [clean] to [observed]; pure, for tests. */
    fun nextIndex(virtual: Int, observed: Int, clean: Int, max: Int): Int =
        (virtual + (observed - clean)).coerceIn(0, max)

    /** Session start: park the unit at the clean step and take the current real level as the virtual one. */
    fun begin(context: Context, manager: AudioManager, log: (String) -> Unit) {
        val app = context.applicationContext
        if (!enabled(app) || AudioPath.knob(app) != AudioPath.Knob.SOFTWARE) { active = false; EqualizerDsp.outputGain = 1f; return }
        val max = runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        val current = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        if (max <= 0 || current < 0) { log("clean volume: media volume unavailable; staying off"); return }
        appContext = app
        cleanStep = step(app).coerceIn(1, max)
        maxIndex = max
        virtualIndex = if (active) virtualIndex else current
        active = true
        apply()
        if (current != cleanStep) AudioPath.setMediaVolume(manager, cleanStep)
        log("clean volume: head unit parked at step $cleanStep / $max; DiPlay volume ${describeLevel()}")
    }

    /** A VOLUME_CHANGED broadcast while active: fold the knob movement into the virtual level and re-park. */
    fun onVolumeChanged(manager: AudioManager, observed: Int, log: (String) -> Unit): Boolean {
        if (!active || observed < 0 || observed == cleanStep) return active
        virtualIndex = nextIndex(virtualIndex, observed, cleanStep, maxIndex)
        apply()
        AudioPath.setMediaVolume(manager, cleanStep)
        log("clean volume: knob → ${describeLevel()} (unit stays at $cleanStep)")
        listeners.forEach { it(virtualIndex, maxIndex) }
        LoudnessVolumeTracker.refresh(appContext ?: return true)
        return true
    }

    /** Last session out: give the unit back its real volume at the level the driver chose. */
    fun end(manager: AudioManager?, log: (String) -> Unit) {
        if (!active) return
        active = false
        EqualizerDsp.outputGain = 1f
        if (manager != null) {
            AudioPath.setMediaVolume(manager, virtualIndex)
            log("clean volume: session over; head unit volume restored to $virtualIndex / $maxIndex")
        }
    }

    fun describeLevel(): String = "$virtualIndex / $maxIndex (${"%.1f".format(20 * kotlin.math.log10(gainFor(virtualIndex, maxIndex).toDouble().coerceAtLeast(1e-6)))} dB)"

    private fun apply() { EqualizerDsp.outputGain = gainFor(virtualIndex, maxIndex) }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
