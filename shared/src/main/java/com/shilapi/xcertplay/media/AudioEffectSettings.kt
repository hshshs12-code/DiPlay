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
) {
    fun dspGainsDb(): FloatArray = FloatArray(EqualizerDsp.BANDS) { dspGainsDb.getOrElse(it) { 0 }.toFloat() }

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
            ).also { cached = it }
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
