// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork: JSON form of the sound settings, shared with the iPhone companion app.
package com.shilapi.xcertplay.media

import org.json.JSONArray
import org.json.JSONObject

/**
 * The sound settings as the iPhone companion app sees them: one flat JSON object whose keys are
 * the [AudioEffectSettings] property names. [merge] accepts a partial object, so a phone can send
 * only what changed; every value is clamped to the same ranges the settings screen enforces.
 */
object AudioEffectSettingsJson {
    const val VERSION = 1

    fun toJson(s: AudioEffectSettings): JSONObject = JSONObject().apply {
        put("enabled", s.enabled)
        put("dspMode", s.dspMode)
        put("presetName", s.presetName)
        put("dspGainsDb", JSONArray(s.dspGainsDb))
        put("bandLevelsMb", JSONArray(s.bandLevelsMb))
        put("bassStrength", s.bassStrength)
        put("loudnessGainMb", s.loudnessGainMb)
        put("limiter", s.limiter)
        put("limiterOff", s.limiterOff)
        put("subsonicHz", s.subsonicHz)
        put("bassEnhance", s.bassEnhance)
        put("bassEnhanceHz", s.bassEnhanceHz)
        put("bassClipDb", s.bassClipDb)
        put("driveDb", s.driveDb)
        put("outputTrimDb", s.outputTrimDb)
        put("autoLevel", s.autoLevel)
        put("autoLevelAmount", s.autoLevelAmount)
        put("parametric", JSONArray().apply {
            s.parametric.forEach { b -> put(JSONObject().put("type", b.type).put("hz", b.hz).put("gainTenths", b.gainTenths).put("qTenths", b.qTenths)) }
        })
        put("stereoWidth", s.stereoWidth)
        put("balance", s.balance)
        put("driverSeatDelayTenthsMs", s.driverSeatDelayTenthsMs)
        put("driverSide", s.driverSide)
        put("crossfeed", s.crossfeed)
        put("airDb", s.airDb)
        put("airHz", s.airHz)
        put("deEss", s.deEss)
        put("loudnessComp", s.loudnessComp)
        put("loudnessCompAmount", s.loudnessCompAmount)
        put("loudnessCompReferenceStep", s.loudnessCompReferenceStep)
    }

    /** [base] with every key present in [json] replaced; unknown keys are ignored, values clamped. */
    fun merge(base: AudioEffectSettings, json: JSONObject): AudioEffectSettings {
        fun int(key: String, current: Int, min: Int, max: Int): Int = if (json.has(key) && !json.isNull(key)) json.optInt(key, current).coerceIn(min, max) else current
        fun bool(key: String, current: Boolean): Boolean = if (json.has(key) && !json.isNull(key)) json.optBoolean(key, current) else current
        fun ints(key: String, current: List<Int>, size: Int, min: Int, max: Int): List<Int> {
            val array = json.optJSONArray(key) ?: return current
            val values = List(array.length()) { array.optInt(it, 0).coerceIn(min, max) }
            return if (size > 0) List(size) { values.getOrElse(it) { current.getOrElse(it) { 0 } } } else values
        }
        val parametric = json.optJSONArray("parametric")?.let { array ->
            (0 until minOf(array.length(), EqualizerDsp.MAX_PARAMETRIC)).mapNotNull { i ->
                val band = array.optJSONObject(i) ?: return@mapNotNull null
                ParametricBand(
                    type = band.optInt("type", ParametricBand.OFF).coerceIn(ParametricBand.OFF, ParametricBand.HIGH_SHELF),
                    hz = band.optInt("hz", 1000).coerceIn(10, 20_000),
                    gainTenths = band.optInt("gainTenths", 0).coerceIn(-120, 120),
                    qTenths = band.optInt("qTenths", 10).coerceIn(3, 80),
                )
            }
        } ?: base.parametric
        return base.copy(
            enabled = bool("enabled", base.enabled),
            dspMode = bool("dspMode", base.dspMode),
            presetName = if (json.has("presetName")) json.optString("presetName", "").take(60) else base.presetName,
            dspGainsDb = ints("dspGainsDb", base.dspGainsDb, EqualizerDsp.BANDS, EqualizerDsp.MIN_DB.toInt(), EqualizerDsp.MAX_DB.toInt()),
            bandLevelsMb = ints("bandLevelsMb", base.bandLevelsMb, 0, -3000, 3000),
            bassStrength = int("bassStrength", base.bassStrength, 0, AudioEffectSettings.MAX_BASS),
            loudnessGainMb = int("loudnessGainMb", base.loudnessGainMb, 0, AudioEffectSettings.MAX_LOUDNESS_MB),
            limiter = bool("limiter", base.limiter),
            limiterOff = bool("limiterOff", base.limiterOff),
            subsonicHz = int("subsonicHz", base.subsonicHz, 0, 200),
            bassEnhance = int("bassEnhance", base.bassEnhance, 0, 100),
            bassEnhanceHz = int("bassEnhanceHz", base.bassEnhanceHz, 40, 300),
            bassClipDb = int("bassClipDb", base.bassClipDb, 0, BassClipper.MAX_CLIP_DB),
            driveDb = int("driveDb", base.driveDb, 0, AudioEffectSettings.MAX_DRIVE_DB),
            outputTrimDb = int("outputTrimDb", base.outputTrimDb, 0, AudioEffectSettings.MAX_TRIM_DB),
            autoLevel = bool("autoLevel", base.autoLevel),
            autoLevelAmount = int("autoLevelAmount", base.autoLevelAmount, 0, 100),
            parametric = parametric,
            stereoWidth = int("stereoWidth", base.stereoWidth, 0, AudioEffectSettings.MAX_STEREO_WIDTH),
            balance = int("balance", base.balance, -AudioEffectSettings.MAX_BALANCE, AudioEffectSettings.MAX_BALANCE),
            driverSeatDelayTenthsMs = int("driverSeatDelayTenthsMs", base.driverSeatDelayTenthsMs, 0, AudioEffectSettings.MAX_DRIVER_DELAY_TENTHS),
            driverSide = int("driverSide", base.driverSide, AudioEffectSettings.DRIVER_LEFT, AudioEffectSettings.DRIVER_RIGHT),
            crossfeed = int("crossfeed", base.crossfeed, 0, AudioEffectSettings.MAX_CROSSFEED),
            airDb = int("airDb", base.airDb, 0, AirShelf.MAX_DB),
            airHz = if (json.has("airHz")) AirShelf.snapCorner(json.optInt("airHz", base.airHz)) else base.airHz,
            deEss = int("deEss", base.deEss, 0, 100),
            loudnessComp = bool("loudnessComp", base.loudnessComp),
            loudnessCompAmount = int("loudnessCompAmount", base.loudnessCompAmount, 0, 100),
            loudnessCompReferenceStep = int("loudnessCompReferenceStep", base.loudnessCompReferenceStep, 0, AudioEffectSettings.MAX_VOLUME_STEP),
        )
    }
}
