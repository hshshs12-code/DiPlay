// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork: the head unit's own global sound effects (audio session 0), read and neutralised.
package com.shilapi.xcertplay

import android.content.Context
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.util.Log

/**
 * The vendor's "Sound Effects" app (com.xy.tda7729 on the 9212A family) applies an LVM equalizer,
 * bass boost, virtualizer and preset reverb on the global output mix (audio session 0) under the
 * package name com.android.systemui, with up to ±15 dB per band. They sit after every app's
 * output and before the HAL, so they shape DiPlay's sound too. Any app holding
 * MODIFY_AUDIO_SETTINGS can open the same effect instances: this reads them and, when the user
 * asks, takes control with a higher priority and sets them flat and disabled for as long as
 * DiPlay runs. The vendor app's own stored settings are never touched; switching off hands the
 * engines back with the values they had.
 */
object VendorEffects {
    private const val TAG = "DiPlay-VendorFx"
    private const val SESSION = 0
    /** Below normal: reading never takes control away from the vendor app. */
    private const val PRIORITY_READ = -1
    /** Above normal: neutralising does take control, and keeps it while the handles live. */
    private const val PRIORITY_CONTROL = 100

    data class Band(val centreHz: Int, val levelMb: Int)

    data class Snapshot(
        val eqAvailable: Boolean, val eqEnabled: Boolean, val bands: List<Band>, val rangeMb: IntRange, val eqPreset: String,
        val bassAvailable: Boolean, val bassEnabled: Boolean, val bassStrength: Int,
        val virtAvailable: Boolean, val virtEnabled: Boolean, val virtStrength: Int,
        val reverbAvailable: Boolean, val reverbEnabled: Boolean, val reverbPreset: Int,
        val errors: List<String>,
    ) {
        val available: Boolean get() = eqAvailable || bassAvailable || virtAvailable || reverbAvailable
        /** True when nothing on session 0 shapes the sound right now. */
        val neutral: Boolean get() = !eqEnabled && !bassEnabled && !virtEnabled && !reverbEnabled

        fun describe(): String {
            if (!available) return "none reachable" + (if (errors.isNotEmpty()) " (${errors.joinToString("; ")})" else "")
            val parts = ArrayList<String>()
            if (eqAvailable) {
                val eq = if (eqEnabled) "EQ on" else "EQ off"
                val levels = bands.joinToString(" · ") { "${hz(it.centreHz)} ${db(it.levelMb)}" }
                parts += if (bands.isEmpty()) eq else "$eq: $levels" + (if (eqPreset.isNotEmpty()) " ($eqPreset)" else "")
            }
            if (bassAvailable) parts += if (bassEnabled) "bass boost ${bassStrength / 10} %" else "bass boost off"
            if (virtAvailable) parts += if (virtEnabled) "virtualizer ${virtStrength / 10} %" else "virtualizer off"
            if (reverbAvailable) parts += if (reverbEnabled) "reverb preset $reverbPreset" else "reverb off"
            if (errors.isNotEmpty()) parts += "errors: ${errors.joinToString("; ")}"
            return parts.joinToString("   ·   ")
        }

        private fun hz(v: Int) = if (v >= 1000) String.format(java.util.Locale.US, "%.1f kHz", v / 1000.0).replace(".0 kHz", " kHz") else "$v Hz"
        private fun db(mb: Int) = String.format(java.util.Locale.US, "%+.1f dB", mb / 100.0)
    }

    private val held = ArrayList<AudioEffect>()
    private var before: Snapshot? = null

    /** Reads the current state of the global effects without taking control. Blocking binder calls; call off the main thread. */
    fun read(): Snapshot = synchronized(this) {
        val errors = ArrayList<String>()
        var eqAvailable = false; var eqEnabled = false; val bands = ArrayList<Band>(); var range = 0..0; var preset = ""
        var bassAvailable = false; var bassEnabled = false; var bassStrength = 0
        var virtAvailable = false; var virtEnabled = false; var virtStrength = 0
        var reverbAvailable = false; var reverbEnabled = false; var reverbPreset = 0
        // When DiPlay already holds the engines (neutralised), read through those handles: a second
        // instance with lower priority would describe the same engine anyway.
        val eqHeld = held.filterIsInstance<Equalizer>().firstOrNull()
        val bassHeld = held.filterIsInstance<BassBoost>().firstOrNull()
        val virtHeld = held.filterIsInstance<Virtualizer>().firstOrNull()
        val reverbHeld = held.filterIsInstance<PresetReverb>().firstOrNull()
        use({ eqHeld ?: Equalizer(PRIORITY_READ, SESSION) }, eqHeld == null, errors, "equalizer") { eq ->
            eqAvailable = true; eqEnabled = eq.enabled
            val r = eq.bandLevelRange; range = r[0].toInt()..r[1].toInt()
            for (b in 0 until eq.numberOfBands) bands += Band(eq.getCenterFreq(b.toShort()) / 1000, eq.getBandLevel(b.toShort()).toInt())
            preset = runCatching { val p = eq.currentPreset; if (p >= 0) eq.getPresetName(p) else "" }.getOrDefault("")
        }
        use({ bassHeld ?: BassBoost(PRIORITY_READ, SESSION) }, bassHeld == null, errors, "bass boost") { bb ->
            bassAvailable = true; bassEnabled = bb.enabled; bassStrength = bb.roundedStrength.toInt()
        }
        use({ virtHeld ?: Virtualizer(PRIORITY_READ, SESSION) }, virtHeld == null, errors, "virtualizer") { vt ->
            virtAvailable = true; virtEnabled = vt.enabled; virtStrength = vt.roundedStrength.toInt()
        }
        use({ reverbHeld ?: PresetReverb(PRIORITY_READ, SESSION) }, reverbHeld == null, errors, "reverb") { rv ->
            reverbAvailable = true; reverbEnabled = rv.enabled; reverbPreset = rv.preset.toInt()
        }
        Snapshot(eqAvailable, eqEnabled, bands, range, preset, bassAvailable, bassEnabled, bassStrength,
            virtAvailable, virtEnabled, virtStrength, reverbAvailable, reverbEnabled, reverbPreset, errors)
    }

    /** Whether DiPlay currently holds the engines flat. */
    val neutralising: Boolean get() = synchronized(this) { held.isNotEmpty() }

    /** Applies the saved preference at session start (idempotent). */
    fun applyIfEnabled(context: Context, log: (String) -> Unit) {
        if (!AirPlayPersistence.loadNeutraliseVendorEffects(context)) return
        runCatching { neutralise(true, log) }.onFailure { log("vendor effects: neutralise failed: ${it.message}") }
    }

    /**
     * [on]: take control of the four global effects and set them flat and disabled; the handles
     * stay open (control is lost the moment they are released). Off: put back what was there and
     * release. Returns the state afterwards. Blocking; call off the main thread.
     */
    fun neutralise(on: Boolean, log: (String) -> Unit): Snapshot = synchronized(this) {
        if (on) {
            if (held.isNotEmpty()) return read()
            val previous = read()
            before = previous
            val results = ArrayList<String>()
            runCatching { Equalizer(PRIORITY_CONTROL, SESSION) }.onSuccess { eq ->
                held += eq
                results += "eq control=${eq.hasControl()}"
                runCatching { for (b in 0 until eq.numberOfBands) eq.setBandLevel(b.toShort(), 0) }.onFailure { results += "eq flat: ${it.message}" }
                results += "eq off=${eq.setEnabled(false)}"
            }.onFailure { results += "eq: ${it.message}" }
            runCatching { BassBoost(PRIORITY_CONTROL, SESSION) }.onSuccess { bb ->
                held += bb
                runCatching { bb.setStrength(0) }
                results += "bass control=${bb.hasControl()} off=${bb.setEnabled(false)}"
            }.onFailure { results += "bass: ${it.message}" }
            runCatching { Virtualizer(PRIORITY_CONTROL, SESSION) }.onSuccess { vt ->
                held += vt
                runCatching { vt.setStrength(0) }
                results += "virt control=${vt.hasControl()} off=${vt.setEnabled(false)}"
            }.onFailure { results += "virt: ${it.message}" }
            runCatching { PresetReverb(PRIORITY_CONTROL, SESSION) }.onSuccess { rv ->
                held += rv
                runCatching { rv.preset = PresetReverb.PRESET_NONE }
                results += "reverb control=${rv.hasControl()} off=${rv.setEnabled(false)}"
            }.onFailure { results += "reverb: ${it.message}" }
            log("vendor effects: neutralised (${results.joinToString(", ")}); before: ${previous.describe()}")
        } else {
            if (held.isEmpty()) return read()
            val previous = before
            if (previous != null) {
                held.filterIsInstance<Equalizer>().forEach { eq ->
                    runCatching { previous.bands.forEachIndexed { i, band -> eq.setBandLevel(i.toShort(), band.levelMb.toShort()) } }
                    runCatching { eq.setEnabled(previous.eqEnabled) }
                }
                held.filterIsInstance<BassBoost>().forEach { bb -> runCatching { bb.setStrength(previous.bassStrength.toShort()); bb.setEnabled(previous.bassEnabled) } }
                held.filterIsInstance<Virtualizer>().forEach { vt -> runCatching { vt.setStrength(previous.virtStrength.toShort()); vt.setEnabled(previous.virtEnabled) } }
                held.filterIsInstance<PresetReverb>().forEach { rv -> runCatching { rv.preset = previous.reverbPreset.toShort(); rv.setEnabled(previous.reverbEnabled) } }
            }
            held.forEach { runCatching { it.release() } }
            held.clear(); before = null
            log("vendor effects: handed back" + (previous?.let { " (${it.describe()})" } ?: ""))
        }
        read()
    }

    private inline fun <T : AudioEffect> use(create: () -> T, releaseAfter: Boolean, errors: MutableList<String>, name: String, block: (T) -> Unit) {
        val effect = runCatching(create).getOrElse { errors += "$name: ${it.javaClass.simpleName}${it.message?.let { m -> " $m" } ?: ""}"; return }
        try { block(effect) } catch (e: Exception) { errors += "$name: ${e.javaClass.simpleName}" }
        finally { if (releaseAfter) runCatching { effect.release() } }
    }

    @Suppress("unused") private fun debug(line: String) = Log.i(TAG, line)
}
