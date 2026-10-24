// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork: the head unit's own global sound effects (audio session 0): read, held flat, or driven directly.
package com.shilapi.xcertplay

import android.content.Context
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The vendor's "Sound Effects" app (com.xy.tda7729 on the 9212A family) applies an LVM equalizer,
 * bass boost, virtualizer and preset reverb on the global output mix (audio session 0) under the
 * package name com.android.systemui, with up to ±15 dB per band. They sit after every app's
 * output and before the HAL, so they shape DiPlay's sound too. Any app holding
 * MODIFY_AUDIO_SETTINGS can open the same effect instances. DiPlay can read them, hold them flat
 * ([Mode.NEUTRAL]) or drive them with the user's own values ([Mode.DIRECT], from the iPhone app)
 * by taking control at a higher priority for as long as it runs. The vendor app's stored settings
 * are never touched; releasing control hands the engines back with the values they had.
 */
object VendorEffects {
    private const val TAG = "DiPlay-VendorFx"
    private const val SESSION = 0
    /** Below normal: reading never takes control away from the vendor app. */
    private const val PRIORITY_READ = -1
    /** Above normal: controlling does take control, and keeps it while the handles live. */
    private const val PRIORITY_CONTROL = 100

    enum class Mode(val key: String) {
        OFF("off"), NEUTRAL("neutral"), DIRECT("direct");

        companion object {
            fun of(key: String?): Mode = values().firstOrNull { it.key == key } ?: OFF
        }
    }

    data class Band(val centreHz: Int, val levelMb: Int)

    /** What to set the four engines to; [bandsMb] shorter than the equalizer leaves the rest alone. */
    data class Target(
        val eqEnabled: Boolean = false, val bandsMb: List<Int> = emptyList(),
        val bassEnabled: Boolean = false, val bassStrength: Int = 0,
        val virtEnabled: Boolean = false, val virtStrength: Int = 0,
        val reverbEnabled: Boolean = false, val reverbPreset: Int = 0,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("eqEnabled", eqEnabled).put("bandsMb", JSONArray(bandsMb))
            .put("bassEnabled", bassEnabled).put("bassStrength", bassStrength)
            .put("virtEnabled", virtEnabled).put("virtStrength", virtStrength)
            .put("reverbEnabled", reverbEnabled).put("reverbPreset", reverbPreset)

        /** This target with every key present in [json] replaced, clamped to the engines' ranges. */
        fun merge(json: JSONObject): Target {
            fun bool(key: String, current: Boolean) = if (json.has(key) && !json.isNull(key)) json.optBoolean(key, current) else current
            fun int(key: String, current: Int, min: Int, max: Int) = if (json.has(key) && !json.isNull(key)) json.optInt(key, current).coerceIn(min, max) else current
            val bands = json.optJSONArray("bandsMb")?.let { array -> List(array.length()) { array.optInt(it, 0).coerceIn(-3000, 3000) } } ?: bandsMb
            return Target(
                eqEnabled = bool("eqEnabled", eqEnabled), bandsMb = bands,
                bassEnabled = bool("bassEnabled", bassEnabled), bassStrength = int("bassStrength", bassStrength, 0, 1000),
                virtEnabled = bool("virtEnabled", virtEnabled), virtStrength = int("virtStrength", virtStrength, 0, 1000),
                reverbEnabled = bool("reverbEnabled", reverbEnabled), reverbPreset = int("reverbPreset", reverbPreset, 0, 6),
            )
        }

        companion object {
            val FLAT = Target()
            fun fromJson(text: String?): Target? = text?.let { runCatching { FLAT.merge(JSONObject(it)) }.getOrNull() }
            /** The engines as they are now, so switching to direct control changes nothing until a slider moves. */
            fun from(snapshot: Snapshot): Target = Target(
                eqEnabled = snapshot.eqEnabled, bandsMb = snapshot.bands.map { it.levelMb },
                bassEnabled = snapshot.bassEnabled, bassStrength = snapshot.bassStrength,
                virtEnabled = snapshot.virtEnabled, virtStrength = snapshot.virtStrength,
                reverbEnabled = snapshot.reverbEnabled, reverbPreset = snapshot.reverbPreset,
            )
        }
    }

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

        /** The phone's view of the engines, plus the mode DiPlay runs them in. */
        fun toJson(mode: Mode, controlled: Boolean): JSONObject = JSONObject()
            .put("mode", mode.key).put("controlled", controlled).put("available", available).put("text", describe())
            .put("eq", JSONObject().put("available", eqAvailable).put("enabled", eqEnabled)
                .put("hz", JSONArray(bands.map { it.centreHz })).put("mb", JSONArray(bands.map { it.levelMb }))
                .put("minMb", rangeMb.first).put("maxMb", rangeMb.last).put("preset", eqPreset))
            .put("bass", JSONObject().put("available", bassAvailable).put("enabled", bassEnabled).put("strength", bassStrength))
            .put("virt", JSONObject().put("available", virtAvailable).put("enabled", virtEnabled).put("strength", virtStrength))
            .put("reverb", JSONObject().put("available", reverbAvailable).put("enabled", reverbEnabled).put("preset", reverbPreset))

        private fun hz(v: Int) = if (v >= 1000) String.format(java.util.Locale.US, "%.1f kHz", v / 1000.0).replace(".0 kHz", " kHz") else "$v Hz"
        private fun db(mb: Int) = String.format(java.util.Locale.US, "%+.1f dB", mb / 100.0)
    }

    private val held = ArrayList<AudioEffect>()
    private var before: Snapshot? = null

    /** Reads the current state of the global effects without taking control. Blocking binder calls; call off the main thread. */
    fun read(): Snapshot = synchronized(this) { readLocked() }

    private fun readLocked(): Snapshot {
        val errors = ArrayList<String>()
        var eqAvailable = false; var eqEnabled = false; val bands = ArrayList<Band>(); var range = 0..0; var preset = ""
        var bassAvailable = false; var bassEnabled = false; var bassStrength = 0
        var virtAvailable = false; var virtEnabled = false; var virtStrength = 0
        var reverbAvailable = false; var reverbEnabled = false; var reverbPreset = 0
        // When DiPlay already holds the engines, read through those handles: a second instance
        // with lower priority would describe the same engine anyway.
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
        return Snapshot(eqAvailable, eqEnabled, bands, range, preset, bassAvailable, bassEnabled, bassStrength,
            virtAvailable, virtEnabled, virtStrength, reverbAvailable, reverbEnabled, reverbPreset, errors)
    }

    /** Whether DiPlay currently holds the engines (flat or driven). */
    val controlling: Boolean get() = synchronized(this) { held.isNotEmpty() }
    val neutralising: Boolean get() = controlling

    /** Applies the saved mode at session start (idempotent). */
    fun applyIfEnabled(context: Context, log: (String) -> Unit) {
        val app = context.applicationContext
        when (AirPlayPersistence.loadUnitFxMode(app)) {
            Mode.OFF -> Unit
            Mode.NEUTRAL -> runCatching { control(Target.FLAT, log) }.onFailure { log("vendor effects: neutralise failed: ${it.message}") }
            Mode.DIRECT -> runCatching {
                val target = Target.fromJson(AirPlayPersistence.loadUnitFxTarget(app)) ?: Target.from(read()).also { AirPlayPersistence.saveUnitFxTarget(app, it.toJson().toString()) }
                control(target, log)
            }.onFailure { log("vendor effects: direct control failed: ${it.message}") }
        }
    }

    /** Backwards-compatible: on = hold flat, off = hand back. */
    fun neutralise(on: Boolean, log: (String) -> Unit): Snapshot = if (on) control(Target.FLAT, log) else release(log)

    /**
     * Takes control of the four global effects (keeping what they had for [release]) and sets
     * them to [target]. The handles stay open: control is lost the moment they are released.
     * Returns the state afterwards. Blocking; call off the main thread.
     */
    fun control(target: Target, log: (String) -> Unit): Snapshot = synchronized(this) {
        val results = ArrayList<String>()
        if (held.isEmpty()) {
            val previous = readLocked()
            before = previous
            runCatching { Equalizer(PRIORITY_CONTROL, SESSION) }.onSuccess { held += it; results += "eq control=${it.hasControl()}" }.onFailure { results += "eq: ${it.message}" }
            runCatching { BassBoost(PRIORITY_CONTROL, SESSION) }.onSuccess { held += it; results += "bass control=${it.hasControl()}" }.onFailure { results += "bass: ${it.message}" }
            runCatching { Virtualizer(PRIORITY_CONTROL, SESSION) }.onSuccess { held += it; results += "virt control=${it.hasControl()}" }.onFailure { results += "virt: ${it.message}" }
            runCatching { PresetReverb(PRIORITY_CONTROL, SESSION) }.onSuccess { held += it; results += "reverb control=${it.hasControl()}" }.onFailure { results += "reverb: ${it.message}" }
            log("vendor effects: took control (${results.joinToString(", ")}); before: ${previous.describe()}")
        }
        applyLocked(target, results)
        log("vendor effects: set ${if (target == Target.FLAT) "flat/off" else target.toJson()} → ${results.joinToString(", ")}")
        readLocked()
    }

    private fun applyLocked(target: Target, results: MutableList<String>) {
        held.filterIsInstance<Equalizer>().forEach { eq ->
            runCatching {
                val bands = eq.numberOfBands.toInt()
                val r = eq.bandLevelRange
                for (b in 0 until bands) {
                    val level = target.bandsMb.getOrNull(b) ?: if (target == Target.FLAT) 0 else continue
                    eq.setBandLevel(b.toShort(), level.coerceIn(r[0].toInt(), r[1].toInt()).toShort())
                }
            }.onFailure { results += "eq bands: ${it.message}" }
            results += "eq ${if (target.eqEnabled) "on" else "off"}=${eq.setEnabled(target.eqEnabled)}"
        }
        held.filterIsInstance<BassBoost>().forEach { bb ->
            runCatching { bb.setStrength(target.bassStrength.coerceIn(0, 1000).toShort()) }.onFailure { results += "bass strength: ${it.message}" }
            results += "bass=${bb.setEnabled(target.bassEnabled)}"
        }
        held.filterIsInstance<Virtualizer>().forEach { vt ->
            runCatching { vt.setStrength(target.virtStrength.coerceIn(0, 1000).toShort()) }.onFailure { results += "virt strength: ${it.message}" }
            results += "virt=${vt.setEnabled(target.virtEnabled)}"
        }
        held.filterIsInstance<PresetReverb>().forEach { rv ->
            runCatching { rv.preset = target.reverbPreset.coerceIn(0, 6).toShort() }.onFailure { results += "reverb preset: ${it.message}" }
            results += "reverb=${rv.setEnabled(target.reverbEnabled)}"
        }
    }

    /** Puts back what the engines had when control was taken, then releases the handles. */
    fun release(log: (String) -> Unit): Snapshot = synchronized(this) {
        if (held.isEmpty()) return readLocked()
        val previous = before
        if (previous != null) applyLocked(Target.from(previous), ArrayList())
        held.forEach { runCatching { it.release() } }
        held.clear(); before = null
        log("vendor effects: handed back" + (previous?.let { " (${it.describe()})" } ?: ""))
        readLocked()
    }

    private inline fun <T : AudioEffect> use(create: () -> T, releaseAfter: Boolean, errors: MutableList<String>, name: String, block: (T) -> Unit) {
        val effect = runCatching(create).getOrElse { errors += "$name: ${it.javaClass.simpleName}${it.message?.let { m -> " $m" } ?: ""}"; return }
        try { block(effect) } catch (e: Exception) { errors += "$name: ${e.javaClass.simpleName}" }
        finally { if (releaseAfter) runCatching { effect.release() } }
    }

    @Suppress("unused") private fun debug(line: String) = Log.i(TAG, line)
}
