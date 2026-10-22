package com.shilapi.xcertplay.media

/**
 * Whole-chain presets: graphic bands, parametric correction, subsonic filter, bass enhancer, auto
 * level and limiter together. The Yaris set targets a 2018 Toyota Yaris with the stock door
 * speakers (thin 6.5" paper cones, no subwoofer, small dash tweeters): weak below ~60 Hz, a door
 * resonance around 150–200 Hz, some harshness around 3 kHz, and a rolled-off top end.
 */
object SoundPresets {
    class Preset(
        val name: String,
        val description: String,
        //                        31  62 125 250 500  1k  2k  3k  4k  8k 12k 16k
        val graphic: IntArray,
        val parametric: List<ParametricBand> = emptyList(),
        val subsonicHz: Int = 0,
        val bassEnhance: Int = 0,
        val bassEnhanceHz: Int = 100,
        /** Low-band soft clip at −N dB (0 = off): bass bounds itself instead of ducking the mix. */
        val bassClipDb: Int = 3,
        /** Loudness drive before the bass clip and limiter, dB. */
        val driveDb: Int = 0,
        val autoLevel: Boolean = false,
        val autoLevelAmount: Int = 50,
        val limiter: Boolean = true,
        /** Stereo stage; neutral by default (see [StereoStage]). The driver side stays the user's. */
        val stereoWidth: Int = 100,
        val balance: Int = 0,
        val driverSeatDelayTenthsMs: Int = 0,
        val crossfeed: Int = 0,
        /** De-essed air shelf; 0 dB = off. */
        val airDb: Int = 0,
        val airHz: Int = AirShelf.DEFAULT_HZ,
        val deEss: Int = AirShelf.DEFAULT_DE_ESS,
        /** Volume-linked loudness compensation; neutral (off) unless a preset is meant for quiet listening. */
        val loudnessComp: Boolean = false,
        val loudnessCompAmount: Int = 60,
    ) {
        fun apply(settings: AudioEffectSettings): AudioEffectSettings = settings.copy(
            enabled = true,
            dspMode = true,
            dspGainsDb = graphic.toList(),
            parametric = parametric,
            subsonicHz = subsonicHz,
            bassEnhance = bassEnhance,
            bassEnhanceHz = bassEnhanceHz,
            bassClipDb = bassClipDb,
            driveDb = driveDb,
            outputTrimDb = settings.outputTrimDb,
            autoLevel = autoLevel,
            autoLevelAmount = autoLevelAmount,
            limiter = limiter,
            limiterOff = false,
            stereoWidth = stereoWidth,
            balance = balance,
            driverSeatDelayTenthsMs = driverSeatDelayTenthsMs,
            crossfeed = crossfeed,
            airDb = airDb,
            airHz = airHz,
            deEss = deEss,
            loudnessComp = loudnessComp,
            loudnessCompAmount = loudnessCompAmount,
            presetName = name,
        )
    }

    /**
     * Driver-seat alignment for the Yaris: the driver sits roughly 40 cm closer to the left door
     * speaker than to the right one, i.e. about 1.2 ms earlier arrival from the left.
     */
    private const val YARIS_DRIVER_DELAY_TENTHS = 12

    /** Cabin correction shared by the Yaris presets: door boom, tweeter edge, dull top end. */
    private val YARIS_CORRECTION = listOf(
        ParametricBand.peak(170, -3f, 1.2f),
        ParametricBand.peak(3200, -2f, 1.5f),
        ParametricBand.highShelf(9000, 2f),
    )

    /** The audit's gentler correction: no shared top shelf when air or graphic treble is in use. */
    private val AUDIT_CORRECTION = listOf(
        ParametricBand.peak(170, -2f, 1.2f),
        ParametricBand.peak(3200, -1.5f, 1.3f),
    )

    val YARIS: List<Preset> = listOf(
        Preset(
            "Yaris · Rage", "Ken Carson / Nine / Vicious: 808s felt through harmonics, hi-hats forward, door boom tamed.",
            intArrayOf(-2, 4, 3, 0, -2, -1, 0, 1, 2, 3, 4, 3),
            parametric = YARIS_CORRECTION, subsonicHz = 35, bassEnhance = 70, bassEnhanceHz = 90, autoLevel = false,
            driverSeatDelayTenthsMs = YARIS_DRIVER_DELAY_TENTHS,
            airDb = 3, airHz = 10000, deEss = 60,
            loudnessComp = true, loudnessCompAmount = 50,
        ),
        Preset(
            "Yaris · 808 Heavy", "Maximum low end for the stock doors, with the digital peak limiter on. Protects the signal, not the speakers: back off if the doors rattle.",
            intArrayOf(-3, 6, 4, 1, -2, -1, 0, 1, 1, 2, 3, 2),
            parametric = YARIS_CORRECTION, subsonicHz = 40, bassEnhance = 90, bassEnhanceHz = 100,
        ),
        Preset(
            "Yaris · 808 Heavy Pure", "808 Heavy without the harmonic enhancer: the same bass curve, no synthetic harmonics, 40 Hz subsonic filter, limiter on.",
            intArrayOf(-3, 6, 4, 1, -2, -1, 0, 1, 1, 2, 3, 2),
            parametric = YARIS_CORRECTION, subsonicHz = 40, bassEnhance = 0,
        ),
        Preset(
            "Yaris · Trap Clean", "Balanced trap mix: tight bass, clear vocals, no mud around 500 Hz.",
            intArrayOf(-2, 3, 2, 0, -2, 0, 0, 1, 1, 2, 3, 2),
            parametric = YARIS_CORRECTION, subsonicHz = 32, bassEnhance = 45, bassEnhanceHz = 90, autoLevel = true, autoLevelAmount = 40,
            driverSeatDelayTenthsMs = YARIS_DRIVER_DELAY_TENTHS,
            airDb = 3, airHz = 10000, deEss = 60,
            loudnessComp = true, loudnessCompAmount = 50,
        ),
        Preset(
            "Yaris · Melodic / Vocals", "Destroy Lonely-style melodic rage: vocals and synth leads forward, bass still present.",
            intArrayOf(-2, 3, 1, 0, -1, 1, 2, 2, 1, 2, 2, 1),
            parametric = YARIS_CORRECTION, subsonicHz = 35, bassEnhance = 40, bassEnhanceHz = 90,
            driverSeatDelayTenthsMs = YARIS_DRIVER_DELAY_TENTHS,
            airDb = 2, deEss = 70,
        ),
        Preset(
            "Yaris · Flat Corrected", "Only the cabin correction (door boom and tweeter edge tamed, +2 dB top shelf): no genre voicing, no limiter.",
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            parametric = YARIS_CORRECTION, subsonicHz = 28, limiter = false, bassClipDb = 0,
        ),
        Preset(
            "Yaris · Highway", "Road noise masks 60–250 Hz and dulls detail: lifts both, auto level keeps quiet parts audible.",
            intArrayOf(-2, 3, 3, 1, -1, 0, 1, 2, 2, 2, 2, 1),
            parametric = YARIS_CORRECTION, subsonicHz = 35, bassEnhance = 40, bassEnhanceHz = 100, autoLevel = true, autoLevelAmount = 70,
        ),
        Preset(
            "Yaris · Windows Down", "Louder mids and highs so the music survives wind noise.",
            intArrayOf(-3, 2, 2, 1, 0, 1, 2, 3, 3, 3, 2, 1),
            parametric = YARIS_CORRECTION, subsonicHz = 40, bassEnhance = 30, bassEnhanceHz = 110, autoLevel = true, autoLevelAmount = 50,
            airDb = 3, airHz = 10000, deEss = 60,
        ),
        Preset(
            "Yaris · Night Drive", "Low volume, late: fuller bass and sparkle so it still sounds big when quiet.",
            intArrayOf(-2, 4, 3, 1, -1, -1, 0, 1, 1, 2, 3, 2),
            parametric = YARIS_CORRECTION, subsonicHz = 35, bassEnhance = 50, bassEnhanceHz = 90, autoLevel = true, autoLevelAmount = 60,
            loudnessComp = true, loudnessCompAmount = 70,
        ),
        Preset(
            "Yaris · Podcast / Voice", "Speech clarity: no bass, presence lifted, strong auto level across different shows.",
            intArrayOf(-6, -4, -2, 0, 1, 1, 3, 3, 2, 0, -2, -3),
            parametric = listOf(ParametricBand.peak(170, -3f, 1.2f), ParametricBand.peak(2500, 2f, 1.0f)),
            subsonicHz = 90, autoLevel = true, autoLevelAmount = 90, bassClipDb = 0,
        ),
        Preset(
            "Yaris · Max Loud", "As loud as the limiter allows: full auto level, limiter hard at work. Digital protection only; mind the doors.",
            intArrayOf(-3, 3, 2, 1, -1, 0, 1, 2, 2, 2, 2, 1),
            parametric = YARIS_CORRECTION, subsonicHz = 40, bassEnhance = 50, bassEnhanceHz = 100, autoLevel = true, autoLevelAmount = 100, driveDb = 6,
        ),
        // Retunes proposed by the October 2026 external DSP audit, kept next to the originals for
        // A/B listening in the car: less stacked treble (no shared +2 dB shelf), a lighter 62 Hz
        // lift, moderate harmonics, and a Podcast curve without the extra presence bell.
        Preset(
            "Yaris · Rage (Audit)", "Audit retune of Rage: about 3 dB less stacked treble, lighter 62 Hz, moderate harmonics, loudness off.",
            intArrayOf(-4, 2, 2, 0, -1, 0, 0, 0, 1, 1, 1, 0),
            parametric = AUDIT_CORRECTION, subsonicHz = 40, bassEnhance = 55, bassEnhanceHz = 85, autoLevel = false,
            driverSeatDelayTenthsMs = YARIS_DRIVER_DELAY_TENTHS,
            airDb = 1, airHz = 10000, deEss = 60,
        ),
        Preset(
            "Yaris · 808 Heavy (Audit)", "Audit retune of 808 Heavy: 45 Hz subsonic, a 2 dB rather than 6 dB 62 Hz lift, harmonics at 65, limiter on.",
            intArrayOf(-6, 2, 2, 0, -1, 0, 0, 0, 0, 1, 1, 0),
            parametric = AUDIT_CORRECTION, subsonicHz = 45, bassEnhance = 65, bassEnhanceHz = 85,
        ),
        Preset(
            "Yaris · Podcast (Audit)", "Audit retune of Podcast: 70 Hz high-pass keeps low voices, presence from the graphic bands only, auto level 75.",
            intArrayOf(-6, -3, -1, 0, 0, 0, 1, 1, 0, 0, -1, -2),
            parametric = listOf(ParametricBand.peak(170, -1.5f, 1.2f)),
            subsonicHz = 70, autoLevel = true, autoLevelAmount = 75, bassClipDb = 0,
        ),
    )
}
