package com.shilapi.xcertplay.media

import android.content.Context
import android.media.audiofx.Equalizer

/**
 * User settings for the media equalizer, bass boost and loudness boost. Stored in their own
 * preference file so the shared module can read them without the host app's persistence class.
 */
data class AudioEffectSettings(
    val enabled: Boolean = false,
    /** 0..1000 per android.media.audiofx.BassBoost. */
    val bassStrength: Int = 0,
    /** Target gain in millibels for LoudnessEnhancer, 0..2000 (0..20 dB). */
    val loudnessGainMb: Int = 0,
    /** Device equalizer band levels in millibels, indexed by band; missing bands read as 0. */
    val bandLevelsMb: List<Int> = emptyList(),
    /** True for the 12-band software equalizer, false for the device's own effect. */
    val dspMode: Boolean = false,
    /** Software equalizer gains in dB for [EqualizerDsp.CENTER_HZ]. */
    val dspGainsDb: List<Int> = List(EqualizerDsp.BANDS) { 0 },
    /** Soft limiter after the software equalizer; off by default. */
    val limiter: Boolean = false,
    val presetName: String = "",
    /** Subsonic high-pass corner in Hz; 0 = off. */
    val subsonicHz: Int = 0,
    /** Psychoacoustic bass enhancer amount 0..100; 0 = off. */
    val bassEnhance: Int = 0,
    /** Crossover of the bass enhancer in Hz: content below it feeds the harmonic generator. */
    val bassEnhanceHz: Int = 100,
    /** Auto level (slow leveler) on/off and its strength 0..100 (100 = full normalisation). */
    val autoLevel: Boolean = false,
    val autoLevelAmount: Int = 50,
    /** Up to five parametric bands applied after the graphic equalizer. */
    val parametric: List<ParametricBand> = emptyList(),
) {
    /** True when any stage beyond the graphic equalizer is active. */
    fun hasAdvancedProcessing(): Boolean =
        subsonicHz > 0 || bassEnhance > 0 || autoLevel || parametric.any { it.type != ParametricBand.OFF && it.gainTenths != 0 }

    fun dspGainsDb(): FloatArray = FloatArray(EqualizerDsp.BANDS) { dspGainsDb.getOrElse(it) { 0 }.toFloat() }

    /**
     * Linear gain applied to the PCM before any boost so the boosted peaks fit in 16 bits. Android's
     * Equalizer and BassBoost run after the track with no headroom: a +4 dB band on a loud master
     * hard-clips, which is heard as fuzz. The level drops by the largest boost in dB instead.
     */
    fun headroomGain(): Float {
        if (!enabled) return 1f
        var boostDb = 0f
        if (dspMode) {
            if (!limiter) boostDb = maxOf(boostDb, (dspGainsDb.maxOrNull() ?: 0).toFloat())
        } else {
            boostDb = maxOf(boostDb, (bandLevelsMb.maxOrNull() ?: 0) / 100f)
        }
        boostDb = maxOf(boostDb, bassStrength / 1000f * 6f)   // BassBoost at full strength is roughly +6 dB
        if (boostDb <= 0f) return 1f
        return Math.pow(10.0, -boostDb / 20.0).toFloat()
    }

    companion object {
        private const val PREFS = "diplay_audio_effects"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BASS = "bass_strength"
        private const val KEY_LOUDNESS = "loudness_mb"
        private const val KEY_BANDS = "band_levels_mb"
        private const val KEY_DSP_MODE = "dsp_mode"
        private const val KEY_DSP_GAINS = "dsp_gains_db"
        private const val KEY_LIMITER = "limiter"
        private const val KEY_PRESET = "preset"
        private const val KEY_SUBSONIC = "subsonic_hz"
        private const val KEY_BASS_ENHANCE = "bass_enhance"
        private const val KEY_BASS_ENHANCE_HZ = "bass_enhance_hz"
        private const val KEY_AUTO_LEVEL = "auto_level"
        private const val KEY_AUTO_LEVEL_AMOUNT = "auto_level_amount"
        private const val KEY_PARAMETRIC = "parametric"
        const val MAX_BASS = 1000
        const val MAX_LOUDNESS_MB = 2000

        @Volatile private var cached: AudioEffectSettings? = null

        fun load(context: Context): AudioEffectSettings {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val bands = prefs.getString(KEY_BANDS, "")?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
            val dsp = prefs.getString(KEY_DSP_GAINS, "")?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()
            return AudioEffectSettings(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                bassStrength = prefs.getInt(KEY_BASS, 0).coerceIn(0, MAX_BASS),
                loudnessGainMb = prefs.getInt(KEY_LOUDNESS, 0).coerceIn(0, MAX_LOUDNESS_MB),
                bandLevelsMb = bands,
                dspMode = prefs.getBoolean(KEY_DSP_MODE, false),
                dspGainsDb = List(EqualizerDsp.BANDS) { dsp.getOrElse(it) { 0 }.coerceIn(-12, 12) },
                limiter = prefs.getBoolean(KEY_LIMITER, false),
                presetName = prefs.getString(KEY_PRESET, "") ?: "",
                subsonicHz = prefs.getInt(KEY_SUBSONIC, 0).coerceIn(0, 200),
                bassEnhance = prefs.getInt(KEY_BASS_ENHANCE, 0).coerceIn(0, 100),
                bassEnhanceHz = prefs.getInt(KEY_BASS_ENHANCE_HZ, 100).coerceIn(50, 200),
                autoLevel = prefs.getBoolean(KEY_AUTO_LEVEL, false),
                autoLevelAmount = prefs.getInt(KEY_AUTO_LEVEL_AMOUNT, 50).coerceIn(0, 100),
                parametric = ParametricBand.decodeList(prefs.getString(KEY_PARAMETRIC, "")),
            ).also { cached = it; EqualizerDsp.headroom = it.headroomGain() }
        }

        fun save(context: Context, settings: AudioEffectSettings) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, settings.enabled)
                .putInt(KEY_BASS, settings.bassStrength.coerceIn(0, MAX_BASS))
                .putInt(KEY_LOUDNESS, settings.loudnessGainMb.coerceIn(0, MAX_LOUDNESS_MB))
                .putString(KEY_BANDS, settings.bandLevelsMb.joinToString(","))
                .putBoolean(KEY_DSP_MODE, settings.dspMode)
                .putString(KEY_DSP_GAINS, settings.dspGainsDb.joinToString(","))
                .putBoolean(KEY_LIMITER, settings.limiter)
                .putString(KEY_PRESET, settings.presetName)
                .putInt(KEY_SUBSONIC, settings.subsonicHz)
                .putInt(KEY_BASS_ENHANCE, settings.bassEnhance)
                .putInt(KEY_BASS_ENHANCE_HZ, settings.bassEnhanceHz)
                .putBoolean(KEY_AUTO_LEVEL, settings.autoLevel)
                .putInt(KEY_AUTO_LEVEL_AMOUNT, settings.autoLevelAmount)
                .putString(KEY_PARAMETRIC, settings.parametric.joinToString(",") { it.encode() })
                .apply()
            cached = settings
            AudioEffectController.applyToActive(settings)
            EqualizerDsp.applyToActive(settings)
        }

        /** The last loaded or saved settings; renderers use this without a context. */
        fun current(context: Context?): AudioEffectSettings =
            cached ?: context?.let { load(it) } ?: AudioEffectSettings()

        /** Equalizer band layout of this device: center frequency in Hz and the level range in mB. */
        class BandInfo(val centerHz: List<Int>, val minLevelMb: Int, val maxLevelMb: Int)

        fun bandInfo(): BandInfo {
            var probe: Equalizer? = null
            return try {
                @Suppress("DEPRECATION")
                probe = Equalizer(0, 0)
                val count = probe.numberOfBands.toInt()
                val range = probe.bandLevelRange
                BandInfo(
                    centerHz = (0 until count).map { probe.getCenterFreq(it.toShort()) / 1000 },
                    minLevelMb = range[0].toInt(),
                    maxLevelMb = range[1].toInt(),
                )
            } catch (_: Exception) {
                BandInfo(listOf(60, 230, 910, 3600, 14000), -1500, 1500)
            } finally {
                runCatching { probe?.release() }
            }
        }
    }
}
