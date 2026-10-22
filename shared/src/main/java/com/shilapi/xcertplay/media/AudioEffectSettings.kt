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
    /** Hard off: no limiter at all, even when auto level, loudness compensation or the bass enhancer would want one. */
    val limiterOff: Boolean = false,
    val presetName: String = "",
    /** Subsonic high-pass corner in Hz; 0 = off. */
    val subsonicHz: Int = 0,
    /** Psychoacoustic bass enhancer amount 0..100; 0 = off. */
    val bassEnhance: Int = 0,
    /** Crossover of the bass enhancer in Hz: content below it feeds the harmonic generator. */
    val bassEnhanceHz: Int = 100,
    /** Soft clip of the band below 120 Hz at −N dBFS so bass never drives the limiter; 0 = off. Neutral here; the stored default and every bass preset use 3. */
    val bassClipDb: Int = 0,
    /** Loudness drive in dB (0..12): gain before the bass clip and limiter, so the average level rises while peaks stay under the ceiling. */
    val driveDb: Int = 0,
    /** Output trim in dB (0..12, applied as −trim): the whole chain's level after limiting, to stay under an amplifier that clips on high volume steps. */
    val outputTrimDb: Int = 0,
    /** Auto level (slow leveler) on/off and its strength 0..100 (100 = full normalisation). */
    val autoLevel: Boolean = false,
    val autoLevelAmount: Int = 50,
    /** Up to five parametric bands applied after the graphic equalizer. */
    val parametric: List<ParametricBand> = emptyList(),
    /** Stereo width 0..200 %: 100 = unchanged, 0 = mono, above 100 widens above ~200 Hz only. */
    val stereoWidth: Int = 100,
    /** Balance −100 (left only) .. 100 (right only), constant-power pan law; 0 = centre. */
    val balance: Int = 0,
    /** Delay of the speaker nearest the driver in tenths of a millisecond, 0..60 (0..6 ms). */
    val driverSeatDelayTenthsMs: Int = 0,
    /** Which side the driver sits on: [DRIVER_LEFT] (left-hand drive) or [DRIVER_RIGHT]. */
    val driverSide: Int = DRIVER_LEFT,
    /** Bauer-style low-frequency crossfeed amount 0..100; 0 = off. */
    val crossfeed: Int = 0,
    /** De-essed air shelf: static high-shelf boost in whole dB, 0..8; 0 = off. */
    val airDb: Int = 0,
    /** Corner of the air shelf in Hz, one of [AirShelf.CORNERS_HZ]. */
    val airHz: Int = AirShelf.DEFAULT_HZ,
    /** How hard sibilance pulls the air shelf back, 0..100; 0 = static shelf. */
    val deEss: Int = AirShelf.DEFAULT_DE_ESS,
    /** Volume-linked equal-loudness compensation (ISO 226); only meaningful with a software volume knob. */
    val loudnessComp: Boolean = false,
    /** Strength of the loudness compensation 0..100 (100 = full ISO 226 difference). */
    val loudnessCompAmount: Int = 60,
    /** Android media volume step treated as the reference level (no compensation); 0 = the maximum step. */
    val loudnessCompReferenceStep: Int = 0,
) {
    /** True when any stage beyond the graphic equalizer is active. */
    fun hasAdvancedProcessing(): Boolean =
        subsonicHz > 0 || bassEnhance > 0 || bassClipDb > 0 || driveDb > 0 || outputTrimDb > 0 || autoLevel || airDb > 0 || (loudnessComp && loudnessCompAmount > 0) ||
            parametric.any { it.type != ParametricBand.OFF && it.gainTenths != 0 } || hasStereoStage()

    /** True when the stereo stage (width, balance, driver-seat delay, crossfeed) changes the signal. */
    fun hasStereoStage(): Boolean =
        stereoWidth != 100 || balance != 0 || driverSeatDelayTenthsMs > 0 || crossfeed > 0

    fun dspGainsDb(): FloatArray = FloatArray(EqualizerDsp.BANDS) { dspGainsDb.getOrElse(it) { 0 }.toFloat() }

    /**
     * Linear gain that would fit the chain's worst-case boost into full scale when no limiter
     * protects the output: the serial stages' positive gains add up (graphic maximum, every
     * boosting parametric band, the air shelf, the stereo stage's anti-phase bound), and the
     * device BassBoost is taken as roughly +6 dB at full strength. Not applied by the renderers
     * since 24.15 (the user preferred boosts as set); kept for diagnostics and tests.
     */
    fun headroomGain(): Float {
        if (!enabled) return 1f
        var boostDb = 0f
        if (dspMode) {
            if (!limiter && !autoLevel && !loudnessComp && bassEnhance == 0) {
                boostDb += (dspGainsDb.maxOrNull() ?: 0).coerceAtLeast(0).toFloat()
                boostDb += parametric.filter { it.type != ParametricBand.OFF }.sumOf { it.gainDb.coerceAtLeast(0f).toDouble() }.toFloat()
                boostDb += airDb.coerceAtLeast(0).toFloat()
                boostDb += StereoStage.boostDb(stereoWidth, balance).toFloat()
            }
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
        private const val KEY_LIMITER_OFF = "limiter_off"
        private const val KEY_PRESET = "preset"
        private const val KEY_SUBSONIC = "subsonic_hz"
        private const val KEY_BASS_ENHANCE = "bass_enhance"
        private const val KEY_BASS_ENHANCE_HZ = "bass_enhance_hz"
        private const val KEY_BASS_CLIP = "bass_clip_db"
        private const val KEY_DRIVE = "drive_db"
        private const val KEY_OUTPUT_TRIM = "output_trim_db"
        private const val KEY_AUTO_LEVEL = "auto_level"
        private const val KEY_AUTO_LEVEL_AMOUNT = "auto_level_amount"
        private const val KEY_PARAMETRIC = "parametric"
        private const val KEY_STEREO_WIDTH = "stereo_width"
        private const val KEY_BALANCE = "balance"
        private const val KEY_DRIVER_DELAY = "driver_seat_delay_tenths_ms"
        private const val KEY_DRIVER_SIDE = "driver_side"
        private const val KEY_CROSSFEED = "crossfeed"
        private const val KEY_AIR_DB = "air_db"
        private const val KEY_AIR_HZ = "air_hz"
        private const val KEY_DE_ESS = "de_ess"
        private const val KEY_LOUDNESS_COMP = "loudness_comp"
        private const val KEY_LOUDNESS_COMP_AMOUNT = "loudness_comp_amount"
        private const val KEY_LOUDNESS_COMP_REFERENCE = "loudness_comp_reference"
        /** Upper bound for the stored reference step; real units have 15..40 media volume steps. */
        const val MAX_VOLUME_STEP = 100
        const val MAX_BASS = 1000
        const val MAX_LOUDNESS_MB = 2000
        const val MAX_STEREO_WIDTH = 200
        const val MAX_DRIVE_DB = 12
        const val MAX_TRIM_DB = 12
        const val MAX_BALANCE = 100
        const val MAX_DRIVER_DELAY_TENTHS = StereoStage.MAX_DELAY_TENTHS
        const val MAX_CROSSFEED = 100
        const val DRIVER_LEFT = 0
        const val DRIVER_RIGHT = 1

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
                limiterOff = prefs.getBoolean(KEY_LIMITER_OFF, false),
                presetName = prefs.getString(KEY_PRESET, "") ?: "",
                subsonicHz = prefs.getInt(KEY_SUBSONIC, 0).coerceIn(0, 200),
                bassEnhance = prefs.getInt(KEY_BASS_ENHANCE, 0).coerceIn(0, 100),
                bassEnhanceHz = prefs.getInt(KEY_BASS_ENHANCE_HZ, 100).coerceIn(50, 200),
                bassClipDb = prefs.getInt(KEY_BASS_CLIP, 3).coerceIn(0, BassClipper.MAX_CLIP_DB),
                driveDb = prefs.getInt(KEY_DRIVE, 0).coerceIn(0, MAX_DRIVE_DB),
                outputTrimDb = prefs.getInt(KEY_OUTPUT_TRIM, 0).coerceIn(0, MAX_TRIM_DB),
                autoLevel = prefs.getBoolean(KEY_AUTO_LEVEL, false),
                autoLevelAmount = prefs.getInt(KEY_AUTO_LEVEL_AMOUNT, 50).coerceIn(0, 100),
                parametric = ParametricBand.decodeList(prefs.getString(KEY_PARAMETRIC, "")),
                stereoWidth = prefs.getInt(KEY_STEREO_WIDTH, 100).coerceIn(0, MAX_STEREO_WIDTH),
                balance = prefs.getInt(KEY_BALANCE, 0).coerceIn(-MAX_BALANCE, MAX_BALANCE),
                driverSeatDelayTenthsMs = prefs.getInt(KEY_DRIVER_DELAY, 0).coerceIn(0, MAX_DRIVER_DELAY_TENTHS),
                driverSide = prefs.getInt(KEY_DRIVER_SIDE, DRIVER_LEFT).coerceIn(DRIVER_LEFT, DRIVER_RIGHT),
                crossfeed = prefs.getInt(KEY_CROSSFEED, 0).coerceIn(0, MAX_CROSSFEED),
                airDb = prefs.getInt(KEY_AIR_DB, 0).coerceIn(0, AirShelf.MAX_DB),
                airHz = AirShelf.snapCorner(prefs.getInt(KEY_AIR_HZ, AirShelf.DEFAULT_HZ)),
                deEss = prefs.getInt(KEY_DE_ESS, AirShelf.DEFAULT_DE_ESS).coerceIn(0, 100),
                loudnessComp = prefs.getBoolean(KEY_LOUDNESS_COMP, false),
                loudnessCompAmount = prefs.getInt(KEY_LOUDNESS_COMP_AMOUNT, 60).coerceIn(0, 100),
                loudnessCompReferenceStep = prefs.getInt(KEY_LOUDNESS_COMP_REFERENCE, 0).coerceIn(0, MAX_VOLUME_STEP),
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
                .putBoolean(KEY_LIMITER_OFF, settings.limiterOff)
                .putString(KEY_PRESET, settings.presetName)
                .putInt(KEY_SUBSONIC, settings.subsonicHz)
                .putInt(KEY_BASS_ENHANCE, settings.bassEnhance)
                .putInt(KEY_BASS_ENHANCE_HZ, settings.bassEnhanceHz)
                .putInt(KEY_BASS_CLIP, settings.bassClipDb.coerceIn(0, BassClipper.MAX_CLIP_DB))
                .putInt(KEY_DRIVE, settings.driveDb.coerceIn(0, MAX_DRIVE_DB))
                .putInt(KEY_OUTPUT_TRIM, settings.outputTrimDb.coerceIn(0, MAX_TRIM_DB))
                .putBoolean(KEY_AUTO_LEVEL, settings.autoLevel)
                .putInt(KEY_AUTO_LEVEL_AMOUNT, settings.autoLevelAmount)
                .putString(KEY_PARAMETRIC, settings.parametric.joinToString(",") { it.encode() })
                .putInt(KEY_STEREO_WIDTH, settings.stereoWidth.coerceIn(0, MAX_STEREO_WIDTH))
                .putInt(KEY_BALANCE, settings.balance.coerceIn(-MAX_BALANCE, MAX_BALANCE))
                .putInt(KEY_DRIVER_DELAY, settings.driverSeatDelayTenthsMs.coerceIn(0, MAX_DRIVER_DELAY_TENTHS))
                .putInt(KEY_DRIVER_SIDE, settings.driverSide.coerceIn(DRIVER_LEFT, DRIVER_RIGHT))
                .putInt(KEY_CROSSFEED, settings.crossfeed.coerceIn(0, MAX_CROSSFEED))
                .putInt(KEY_AIR_DB, settings.airDb.coerceIn(0, AirShelf.MAX_DB))
                .putInt(KEY_AIR_HZ, AirShelf.snapCorner(settings.airHz))
                .putInt(KEY_DE_ESS, settings.deEss.coerceIn(0, 100))
                .putBoolean(KEY_LOUDNESS_COMP, settings.loudnessComp)
                .putInt(KEY_LOUDNESS_COMP_AMOUNT, settings.loudnessCompAmount.coerceIn(0, 100))
                .putInt(KEY_LOUDNESS_COMP_REFERENCE, settings.loudnessCompReferenceStep.coerceIn(0, MAX_VOLUME_STEP))
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
