package com.shilapi.xcertplay.media

import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * DiPlay's software audio chain on 16-bit interleaved PCM, applied in place before the AudioTrack
 * write. Stages in order: subsonic high-pass, 12-band graphic equalizer, up to five parametric
 * bands, volume-linked loudness compensation ([LoudnessCompensator]), de-essed air shelf
 * ([AirShelf]), psychoacoustic bass enhancer ([BassEnhancer]), low-band soft clipper ([BassClipper]), stereo stage (width, crossfeed,
 * balance, driver-seat delay; see [StereoStage]), auto level ([Leveler]), true-peak look-ahead
 * limiter ([TruePeakLimiter]), TPDF dither.
 *
 * Design notes, in the spirit of a car-audio DSP rather than a phone app:
 * - All filtering runs in double precision; the signal is quantised once, at the output, with
 *   triangular dither. Direct-form-I biquads in single precision lose low-frequency accuracy at
 *   31 Hz / 48 kHz. (Cortex-A7 has scalar double-precision VFP; the recursive filters stay double
 *   for accuracy, the float FIR paths are the first candidates for NEON if profiling ever asks.)
 * - Peaking filters use Orfanidis' design with prescribed Nyquist gain, so bells near 16 kHz keep
 *   their analog shape instead of being squashed against Nyquist by the bilinear transform.
 * - The graphic equalizer is constant-Q per band spacing and its band gains are corrected for
 *   neighbour interaction, so +6 dB on adjacent sliders produces +6 dB, not +9.
 * - Coefficient changes are interpolated over ~30 ms so slider drags never click. The ramp
 *   advances on a fixed 64-frame grid counted in processed audio, so it lasts the same 30 ms
 *   whether the stream arrives in 1-frame or 4096-frame writes.
 * - The limiter ([TruePeakLimiter]) is a stereo-linked true-peak look-ahead limiter (2 ms): 4x
 *   oversampled peak detection, a smooth gain envelope, hold plus dual release so bass is not
 *   modulated, instead of a static waveshaper that distorts whenever it works. It fades in and
 *   out instead of resetting, so toggling it mid-song neither gaps nor jumps.
 * - Threading: [configure] designs every coefficient on the caller's thread and publishes one
 *   immutable [Prepared] snapshot; the audio thread adopts it at the next block boundary and is
 *   the only thread that touches filter state, ramps, resets and the stages' own configuration.
 *   A block can never mix two generations of settings.
 * Neutral stages are skipped, so the cost scales with what is switched on.
 */
class EqualizerDsp(private val sampleRate: Int, private val channels: Int) {
    /** A bank of biquads with target coefficients, interpolating current coefficients and state. */
    private inner class Bank(val count: Int) {
        /** Published atomically by [commit] so the audio thread never reads a half-written design. */
        @Volatile var target = identityDesign()
            private set
        val current = identityDesign()
        @Volatile var active = BooleanArray(count)
        @Volatile var any = false
        /** The activity the design is heading for; adopted by [settle] once the ramp has finished. */
        @Volatile private var pendingActive = BooleanArray(count)
        val state = DoubleArray(channels * count * 4)

        private fun identityDesign() = DoubleArray(count * 5).also { for (i in 0 until count) it[i * 5] = 1.0 }
        /** A fresh staging design: identity for every section until [set] overwrites it. */
        fun begin(): DoubleArray = identityDesign()
        fun set(design: DoubleArray, index: Int, coefficients: DoubleArray) { System.arraycopy(coefficients, 0, design, index * 5, 5) }

        /**
         * Publishes [design]. Sections that are switching off stay active while their coefficients
         * ramp to identity, so removing a band is as click-free as adding one.
         */
        fun commit(design: DoubleArray, activeNext: BooleanArray) {
            val previous = active
            val merged = BooleanArray(count) { previous[it] || activeNext[it] }
            pendingActive = activeNext
            target = design
            active = merged
            any = merged.any { it }
        }
        fun snap() { System.arraycopy(target, 0, current, 0, current.size); settle() }
        fun step(alpha: Double) { val t = target; for (k in current.indices) current[k] += (t[k] - current[k]) * alpha }
        /** Ramp finished: drop the sections that ramped to identity. */
        fun settle() { val next = pendingActive; active = next; any = next.any { it } }
        /** Flushes subnormal filter memory so long silences cannot slow the arithmetic. */
        fun flushDenormals() { for (i in state.indices) if (abs(state[i]) < DENORMAL_FLOOR) state[i] = 0.0 }
        fun clear() { state.fill(0.0) }
    }

    /** One complete chain configuration: designed off the audio thread, adopted whole by [adopt]. */
    private class Prepared(
        val graphicDesign: DoubleArray, val graphicActive: BooleanArray,
        val parametricDesign: DoubleArray, val parametricActive: BooleanArray,
        val subsonicDesign: DoubleArray, val subsonicActive: Boolean,
        val bassAmount: Int, val bassHz: Int, val bassClipDb: Int, val driveDb: Int, val trimDb: Int,
        val autoLevel: Boolean, val autoStrength: Double,
        val width: Int, val balance: Int, val delayTenths: Int, val driverSide: Int, val crossfeed: Int,
        val airDb: Int, val airHz: Int, val deEss: Int,
        val loudness: Boolean, val loudnessAmount: Int,
        val limiter: Boolean,
    )

    private val pending = AtomicReference<Prepared?>(null)

    private val graphic = Bank(BANDS)
    private val parametric = Bank(MAX_PARAMETRIC)
    private val subsonic = Bank(2)
    /** Psychoacoustic bass enhancer (LR4 split, envelope-tracked harmonic series); see [BassEnhancer]. */
    private val bass = BassEnhancer(sampleRate, channels)
    /** Low-band soft clipper so bass bounds itself instead of making the limiter duck the mix; see [BassClipper]. */
    private val clipper = BassClipper(sampleRate, channels)
    @Volatile private var subsonicOn = false
    /** De-essed air shelf; see [AirShelf]. */
    private val air = AirShelf(sampleRate, channels)
    /** Volume-linked loudness compensation; see [LoudnessCompensator]. */
    private val loudness = LoudnessCompensator(sampleRate, channels)

    // Stereo stage (stereo streams only)
    private val stage: StereoStage? = if (channels == 2) StereoStage(sampleRate) else null

    // Auto level: gated, K-weighted program leveler (see Leveler). [autoPolicy] is the configured
    // state (any thread), [autoOn] the adopted one (audio thread).
    @Volatile private var autoPolicy = false
    private var autoOn = false
    private val leveler = Leveler(sampleRate, channels)

    // Look-ahead limiter. [limiter] is the configured policy (what the chain is heading for, readable
    // from any thread); [limiterOn] is the adopted state on the audio thread.
    @Volatile var limiter = false
        private set
    private var limiterOn = false
    @Volatile private var softClipForced = false
    private val truePeak = TruePeakLimiter(sampleRate, channels)
    private var scratch = DoubleArray(0)

    // Coefficient smoothing (on a persistent 64-frame grid) and dither
    private val rampSteps = max(1, Math.round(RAMP_SECONDS * sampleRate / SMOOTH_CHUNK_FRAMES).toInt())
    private var smoothSteps = 0
    // Loudness drive (before the bass clip and limiter) and output trim (after), ramped on the 64-frame grid.
    private var driveTarget = 1.0
    private var drive = 1.0
    private var trimTarget = 1.0
    private var trim = 1.0
    private var stepPhase = 0
    private var denormalCounter = 0
    private var ditherState = 0x9E3779B9.toInt()
    private var floatOut: FloatArray? = null
    private var floatIndex = 0
    private val frame = DoubleArray(channels)
    @Volatile private var configured = false

    /** Graphic-only configuration kept for callers and tests; all other stages switch off. */
    fun configure(gainsDb: FloatArray, limiter: Boolean) {
        this.limiter = limiter
        autoPolicy = false
        val (gDesign, gActive) = designGraphic(gainsDb)
        val (pDesign, pActive) = designParametric(emptyList())
        val (sDesign, sActive) = designSubsonic(0)
        pending.set(Prepared(gDesign, gActive, pDesign, pActive, sDesign, sActive,
            0, 100, 0, 0, 0, false, 0.0, 100, 0, 0, 0, 0, 0, AirShelf.DEFAULT_HZ, 0, false, 0, limiter))
    }

    /** Full configuration from the user's settings; [softClip] forces the limiter on (media soft clipper). */
    fun configure(settings: AudioEffectSettings, softClip: Boolean) {
        softClipForced = softClip
        val active = settings.enabled && settings.dspMode
        val loud = active && settings.loudnessComp
        // Loudness compensation lifts bass by up to 15 dB at low volume and the bass enhancer adds
        // harmonics on top of a dry path that an all-pass can push past full scale: both need the
        // limiter in front of the DAC, as does auto level.
        // The user's hard-off wins over every stage that would want the limiter: peaks then clip at full scale.
        // Drive only makes sense with a limiter behind it, so it wins even over the hard-off.
        val useLimiter = if (active && settings.limiterOff && settings.driveDb == 0) false
            else softClip || (active && (settings.limiter || settings.autoLevel || loud || settings.bassEnhance > 0 || settings.driveDb > 0))
        this.limiter = useLimiter
        val auto = active && settings.autoLevel && settings.autoLevelAmount > 0
        if (auto && !autoPolicy) leveler.reset()   // queued: the audio thread performs it
        autoPolicy = auto
        val (gDesign, gActive) = designGraphic(if (active) settings.dspGainsDb() else FloatArray(BANDS))
        val (pDesign, pActive) = designParametric(if (active) settings.parametric else emptyList())
        val (sDesign, sActive) = designSubsonic(if (active) settings.subsonicHz else 0)
        pending.set(Prepared(
            gDesign, gActive, pDesign, pActive, sDesign, sActive,
            if (active) settings.bassEnhance else 0, settings.bassEnhanceHz, if (active) settings.bassClipDb else 0,
            if (active) settings.driveDb else 0, if (active) settings.outputTrimDb else 0,
            active && settings.autoLevel, settings.autoLevelAmount.coerceIn(0, 100) / 100.0,
            if (active) settings.stereoWidth else 100, if (active) settings.balance else 0,
            if (active) settings.driverSeatDelayTenthsMs else 0, settings.driverSide, if (active) settings.crossfeed else 0,
            if (active) settings.airDb else 0, settings.airHz, settings.deEss,
            loud, settings.loudnessCompAmount,
            useLimiter,
        ))
    }

    /** Audio thread: makes [p] the running configuration at a block boundary. */
    private fun adopt(p: Prepared) {
        val first = !configured
        graphic.commit(p.graphicDesign, p.graphicActive)
        parametric.commit(p.parametricDesign, p.parametricActive)
        subsonic.commit(p.subsonicDesign, BooleanArray(2) { p.subsonicActive })
        subsonicOn = subsonic.any
        bass.configure(p.bassAmount, p.bassHz)
        clipper.configure(p.bassClipDb)
        driveTarget = 10.0.pow(p.driveDb.coerceIn(0, 24) / 20.0)
        trimTarget = 10.0.pow(-p.trimDb.coerceIn(0, 24) / 20.0)
        leveler.strength = p.autoStrength
        autoOn = p.autoLevel && p.autoStrength > 0.0
        stage?.configure(p.width, p.balance, p.delayTenths, p.driverSide, p.crossfeed)
        air.configure(p.airDb, p.airHz, p.deEss, immediate = first)
        loudness.configure(p.loudness, p.loudnessAmount)
        if (p.limiter != limiterOn || first) {
            truePeak.setEnabled(p.limiter, immediate = first)
            limiterOn = p.limiter
        }
        // With a limiter after it the enhancer may exceed full scale; without one it guards its own headroom.
        bass.headroomGuard = !p.limiter
        if (first) {
            graphic.snap(); parametric.snap(); subsonic.snap(); bass.snap(); clipper.snap()
            drive = driveTarget; trim = trimTarget
            configured = true
        } else {
            smoothSteps = rampSteps
        }
    }

    // ---- stage design (any thread; pure functions of the settings) ---------------------------

    private fun designGraphic(gainsDb: FloatArray): Pair<DoubleArray, BooleanArray> {
        val targets = DoubleArray(BANDS) { gainsDb.getOrElse(it) { 0f }.coerceIn(MIN_DB, MAX_DB).toDouble() }
        val usable = BooleanArray(BANDS) { abs(targets[it]) >= 0.05 && CENTER_HZ[it] < sampleRate / 2.0 }
        val designed = DoubleArray(BANDS) { if (usable[it]) targets[it] else 0.0 }
        val sections = Array(BANDS) { DoubleArray(5) }
        // Neighbour interaction: redesign a few times until the composite response hits each target.
        // The last pass only rebuilds the sections, so the final correction is not thrown away.
        val passes = if (usable.count { it } > 1) INTERACTION_ITERATIONS else 1
        for (pass in 0 until passes) {
            for (band in 0 until BANDS) if (usable[band]) peakingOrfanidis(sections[band], CENTER_HZ[band].toDouble(), designed[band], GRAPHIC_Q[band])
            if (usable.count { it } <= 1 || pass == passes - 1) break
            for (band in 0 until BANDS) {
                if (!usable[band]) continue
                var actual = 0.0
                for (other in 0 until BANDS) if (usable[other]) actual += magnitudeDb(sections[other], CENTER_HZ[band].toDouble())
                designed[band] = (designed[band] + (targets[band] - actual) * INTERACTION_DAMPING).coerceIn(MIN_DB.toDouble() - 6, MAX_DB.toDouble() + 6)
            }
        }
        val active = BooleanArray(BANDS)
        val design = graphic.begin()
        for (band in 0 until BANDS) if (usable[band]) { graphic.set(design, band, sections[band]); active[band] = true }
        return design to active
    }

    private fun designParametric(bands: List<ParametricBand>): Pair<DoubleArray, BooleanArray> {
        val active = BooleanArray(MAX_PARAMETRIC)
        val design = parametric.begin()
        for (index in 0 until MAX_PARAMETRIC) {
            val band = bands.getOrNull(index)
            val gain = band?.gainDb?.coerceIn(MIN_DB, MAX_DB)?.toDouble() ?: 0.0
            val f = band?.hz?.toDouble() ?: 0.0
            if (band == null || band.type == ParametricBand.OFF || abs(gain) < 0.05 || f < 10 || f >= sampleRate / 2.0) continue
            val q = band.q.coerceIn(0.3f, 8f).toDouble()
            val c = DoubleArray(5)
            when (band.type) {
                ParametricBand.LOW_SHELF -> lowShelf(c, f, gain, q)
                ParametricBand.HIGH_SHELF -> highShelf(c, f, gain, q)
                else -> peakingOrfanidis(c, f, gain, q)
            }
            parametric.set(design, index, c)
            active[index] = true
        }
        return design to active
    }

    private fun designSubsonic(hz: Int): Pair<DoubleArray, Boolean> {
        val design = subsonic.begin()
        // Off: ramps to identity, then the bank settles to inactive.
        if (hz < 10 || hz >= sampleRate / 4) return design to false
        val c = DoubleArray(5)
        highPass(c, hz.toDouble(), BUTTERWORTH_Q)
        subsonic.set(design, 0, c); subsonic.set(design, 1, c)
        return design to true
    }

    // ---- processing ---------------------------------------------------------------------------

    /** Processes 16-bit little-endian interleaved PCM in place. */
    fun process(data: ByteArray, offset: Int, length: Int) {
        floatOut = null
        run(data, offset, length)
    }

    /**
     * Processes 16-bit PCM and writes the result as float samples into [out] (no requantisation);
     * returns the number of samples written. A neutral chain copies the input exactly.
     */
    fun processToFloat(data: ByteArray, offset: Int, length: Int, out: FloatArray): Int {
        val frames = length / (2 * channels)
        val samples = frames * channels
        floatOut = out
        floatIndex = 0
        try {
            if (!run(data, offset, length)) MediaOutputStage.convert(data, offset, samples, out)
        } finally {
            floatOut = null
        }
        return samples
    }

    /** Returns false when nothing is active and the data was left untouched. */
    private fun run(data: ByteArray, offset: Int, length: Int): Boolean {
        pending.getAndSet(null)?.let { adopt(it) }
        val useLimiter = truePeak.busy
        val g = graphic.any; val p = parametric.any; val sub = subsonicOn; val b = bass.active; val clip = clipper.active; val auto = autoOn
        val stereo = stage
        val st = stereo != null && stereo.isActive()
        val airOn = air.isActive
        val loudStage = loudness.enabled || loudness.busy
        val gainStages = drive != 1.0 || driveTarget != 1.0 || trim != 1.0 || trimTarget != 1.0
        if (!g && !p && !sub && !b && !clip && !auto && !st && !airOn && !loudStage && !useLimiter && !gainStages) return false
        val frames = length / (2 * channels)
        if (frames <= 0) return false
        ensureScratch(frames)
        if (st) stereo!!.beginBlock()
        val gActive = graphic.active; val pActive = parametric.active
        var index = offset
        var produced = 0
        while (produced < frames) {
            if (stepPhase == 0) {
                // A control boundary on the persistent 64-frame grid: the ramp and the denormal
                // flush advance here, independent of how the stream is split into writes.
                stepPhase = SMOOTH_CHUNK_FRAMES
                if (smoothSteps > 0) {
                    val alpha = 1.0 / smoothSteps
                    graphic.step(alpha); parametric.step(alpha); subsonic.step(alpha); bass.step(alpha)
                    drive += (driveTarget - drive) * alpha; trim += (trimTarget - trim) * alpha
                    if (--smoothSteps == 0) {
                        graphic.settle(); parametric.settle(); subsonic.settle()
                        subsonicOn = subsonic.any
                    }
                }
                if (++denormalCounter >= DENORMAL_FLUSH_CHUNKS) {
                    denormalCounter = 0
                    graphic.flushDenormals(); parametric.flushDenormals(); subsonic.flushDenormals()
                }
            }
            val chunk = min(stepPhase, frames - produced)
            val gc = graphic.current; val pc = parametric.current; val sc = subsonic.current
            val l = loudStage && loudness.prepare(chunk)
            for (n in produced until produced + chunk) {
                for (ch in 0 until channels) {
                    val o = index + ch * 2
                    var x = (((data[o + 1].toInt() shl 8) or (data[o].toInt() and 0xff)).toDouble()) / 32768.0
                    if (sub) {
                        val s = ch * 8
                        x = biquad(x, sc, 0, subsonic.state, s)
                        x = biquad(x, sc, 5, subsonic.state, s + 4)
                    }
                    if (g) {
                        val base = ch * BANDS * 4
                        for (band in 0 until BANDS) if (gActive[band]) x = biquad(x, gc, band * 5, graphic.state, base + band * 4)
                    }
                    if (p) {
                        val base = ch * MAX_PARAMETRIC * 4
                        for (band in 0 until MAX_PARAMETRIC) if (pActive[band]) x = biquad(x, pc, band * 5, parametric.state, base + band * 4)
                    }
                    if (l) x = loudness.processSample(x, ch)
                    frame[ch] = x * drive
                }
                // Stereo-linked stages: one mono control path for all channels.
                if (airOn) air.processFrame(frame)
                if (b) bass.process(frame)
                if (clip) clipper.process(frame)
                if (st) stereo!!.processFrame(frame)
                val gain = if (auto) leveler.process(frame) else 1.0
                val at = n * channels
                for (ch in 0 until channels) scratch[at + ch] = frame[ch] * gain
                index += channels * 2
            }
            stepPhase -= chunk
            produced += chunk
        }
        if (useLimiter) truePeak.process(scratch, frames)
        writeDirect(data, offset, frames)
        return true
    }

    /** Quantises the processed (and, when on, limited) block with dither. */
    private fun writeDirect(data: ByteArray, offset: Int, frames: Int) {
        var o = offset
        for (i in 0 until frames * channels) { put(data, o, scratch[i]); o += 2 }
    }

    /** 16-bit output with TPDF dither (two uniform draws, ±1 LSB triangular), or float when rendering to float. */
    private fun put(data: ByteArray, o: Int, v0: Double) {
        val x = v0 * outputGain * trim
        val f = floatOut
        if (f != null) { f[floatIndex++] = x.toFloat(); return }
        var r = ditherState
        r = r xor (r shl 13); r = r xor (r ushr 17); r = r xor (r shl 5)
        val d1 = (r and 0xffff) / 65536.0
        r = r xor (r shl 13); r = r xor (r ushr 17); r = r xor (r shl 5)
        val d2 = (r and 0xffff) / 65536.0
        ditherState = r
        // Same 32768 scale as the input so a neutral chain returns the sample exactly (± dither).
        val v = Math.round(x * 32768.0 + (d1 - d2)).toInt().coerceIn(-32768, 32767)
        data[o] = v.toByte()
        data[o + 1] = (v shr 8).toByte()
    }

    private fun ensureScratch(frames: Int) {
        if (scratch.size < frames * channels) scratch = DoubleArray(frames * channels)
    }

    /** Current auto-level gain in dB, for the UI. */
    fun autoLevelGainDb(): Float = if (autoPolicy && autoOn) leveler.gainDb() else 0f

    /** Output delay introduced by the chain, in frames (the limiter's look-ahead when it is configured on). */
    fun latencyFrames(): Int = if (limiter) truePeak.latencyFrames else 0

    private fun biquad(x: Double, c: DoubleArray, k: Int, s: DoubleArray, i: Int): Double {
        val y = c[k] * x + c[k + 1] * s[i] + c[k + 2] * s[i + 1] - c[k + 3] * s[i + 2] - c[k + 4] * s[i + 3]
        s[i + 1] = s[i]; s[i] = x
        s[i + 3] = s[i + 2]; s[i + 2] = y
        return y
    }

    private fun coefficient(seconds: Double): Double = 1.0 - Math.exp(-1.0 / (seconds * sampleRate))

    // ---- filter design (coefficients stored normalised as [b0, b1, b2, a1, a2]) ------------------

    private fun store(out: DoubleArray, b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        out[0] = b0 / a0; out[1] = b1 / a0; out[2] = b2 / a0; out[3] = a1 / a0; out[4] = a2 / a0
    }

    /**
     * Peaking equalizer with prescribed Nyquist gain (S. J. Orfanidis, "Digital Parametric
     * Equalizer Design with Prescribed Nyquist-Frequency Gain", JAES 1997). Matches the analog
     * bell's shape up to Nyquist; bandwidth is defined at the half-gain (dB) points like RBJ's.
     */
    internal fun peakingOrfanidis(out: DoubleArray, f: Double, gainDb: Double, q: Double) {
        val g0 = 1.0
        val g = 10.0.pow(gainDb / 20.0)
        val gb = sqrt(g0 * g)
        val w0 = 2.0 * PI * f / sampleRate
        val dw = w0 / q
        if (abs(g - g0) < 1e-6) { out[0] = 1.0; out[1] = 0.0; out[2] = 0.0; out[3] = 0.0; out[4] = 0.0; return }
        val fF = abs(g * g - gb * gb)
        val g00 = abs(g * g - g0 * g0)
        val f00 = abs(gb * gb - g0 * g0)
        val num = g0 * g0 * (w0 * w0 - PI * PI).pow(2) + g * g * f00 * PI * PI * dw * dw / fF
        val den = (w0 * w0 - PI * PI).pow(2) + f00 * PI * PI * dw * dw / fF
        val g1 = sqrt(num / den)
        val g01 = abs(g * g - g0 * g1)
        val g11 = abs(g * g - g1 * g1)
        val f01 = abs(gb * gb - g0 * g1)
        val f11 = abs(gb * gb - g1 * g1)
        if (g00 < 1e-12 || f11 < 1e-12) { peakingRbj(out, f, gainDb, q); return }
        val w2 = sqrt(g11 / g00) * tan(w0 / 2).pow(2)
        val dW = (1 + sqrt(f00 / f11) * w2) * tan(dw / 2)
        val c = f11 * dW * dW - 2 * w2 * (f01 - sqrt(f00 * f11))
        val d = 2 * w2 * (g01 - sqrt(g00 * g11))
        val a = sqrt((c + d) / fF)
        val b = sqrt((g * g * c + gb * gb * d) / fF)
        if (a.isNaN() || b.isNaN()) { peakingRbj(out, f, gainDb, q); return }
        val a0 = 1 + w2 + a
        store(out, g1 + g0 * w2 + b, -2 * (g1 - g0 * w2), g1 - b + g0 * w2, a0, -2 * (1 - w2), 1 + w2 - a)
    }

    private fun peakingRbj(out: DoubleArray, f: Double, gainDb: Double, q: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        store(out, 1.0 + alpha * a, -2.0 * cosW0, 1.0 - alpha * a, 1.0 + alpha / a, -2.0 * cosW0, 1.0 - alpha / a)
    }

    private fun lowShelf(out: DoubleArray, f: Double, gainDb: Double, q: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        val sa = 2.0 * sqrt(a) * alpha
        store(out,
            a * ((a + 1) - (a - 1) * cosW0 + sa), 2 * a * ((a - 1) - (a + 1) * cosW0), a * ((a + 1) - (a - 1) * cosW0 - sa),
            (a + 1) + (a - 1) * cosW0 + sa, -2 * ((a - 1) + (a + 1) * cosW0), (a + 1) + (a - 1) * cosW0 - sa)
    }

    private fun highShelf(out: DoubleArray, f: Double, gainDb: Double, q: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        val sa = 2.0 * sqrt(a) * alpha
        store(out,
            a * ((a + 1) + (a - 1) * cosW0 + sa), -2 * a * ((a - 1) + (a + 1) * cosW0), a * ((a + 1) + (a - 1) * cosW0 - sa),
            (a + 1) - (a - 1) * cosW0 + sa, 2 * ((a - 1) - (a + 1) * cosW0), (a + 1) - (a - 1) * cosW0 - sa)
    }

    private fun highPass(out: DoubleArray, f: Double, q: Double) {
        val w0 = 2.0 * PI * f / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        store(out, (1 + cosW0) / 2, -(1 + cosW0), (1 + cosW0) / 2, 1 + alpha, -2 * cosW0, 1 - alpha)
    }

    /** Magnitude response of one section at [f] in dB. */
    internal fun magnitudeDb(c: DoubleArray, f: Double): Double {
        val w = 2.0 * PI * f / sampleRate
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = c[0] + c[1] * c1 + c[2] * c2; val ni = -(c[1] * s1 + c[2] * s2)
        val dr = 1.0 + c[3] * c1 + c[4] * c2; val di = -(c[3] * s1 + c[4] * s2)
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    fun register() { active.add(this) }
    fun unregister() { active.remove(this) }

    companion object {
        val CENTER_HZ = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 3000, 4000, 8000, 12000, 16000)
        const val BANDS = 12
        const val MAX_PARAMETRIC = 5
        const val MIN_DB = -12f
        const val MAX_DB = 12f
        /** The limiter's ceiling in dBFS, for the settings page. */
        val LIMITER_CEILING_DB: Double = 20.0 * log10(TruePeakLimiter.CEILING)

        /** Constant-Q per band from the neighbour spacing: a band's bell reaches its neighbours' centres at half gain. */
        val GRAPHIC_Q: DoubleArray = DoubleArray(BANDS) { i ->
            val lower = if (i > 0) CENTER_HZ[i - 1].toDouble() else CENTER_HZ[i] / 2.0
            val upper = if (i < BANDS - 1) CENTER_HZ[i + 1].toDouble() else CENTER_HZ[i] * 2.0
            val octaves = (ln(upper) - ln(lower)) / ln(2.0) / 2.0
            val n = 2.0.pow(octaves)
            (sqrt(n) / (n - 1)).coerceIn(1.0, 2.9)
        }

        private const val BUTTERWORTH_Q = 0.7071
        private const val INTERACTION_ITERATIONS = 6
        private const val INTERACTION_DAMPING = 0.8
        private const val SMOOTH_CHUNK_FRAMES = 64
        private const val DENORMAL_FLOOR = 1e-30
        private const val DENORMAL_FLUSH_CHUNKS = 64
        /** Coefficient ramp length; converted to 64-frame steps per sample rate. */
        private const val RAMP_SECONDS = 0.030
        private val active: MutableSet<EqualizerDsp> = Collections.synchronizedSet(mutableSetOf<EqualizerDsp>())

        fun applyToActive(settings: AudioEffectSettings) {
            val snapshot = synchronized(active) { active.toList() }
            snapshot.forEach { it.configure(settings, it.softClipForced) }
            headroom = settings.headroomGain()
        }

        /** Shared pre-gain read by every renderer on each write; updated with the settings. */
        @Volatile var headroom: Float = 1f

        /**
         * Digital volume applied at the very end of the chain (before dither), 0..1. Driven by the
         * clean-volume mode in `common`, which parks the head unit at its one undistorted step and
         * does the knob's work here in float.
         */
        @Volatile var outputGain: Float = 1f

        /** Scales 16-bit interleaved PCM in place by [gain]. */
        fun scale(data: ByteArray, offset: Int, length: Int, gain: Float) {
            if (gain >= 0.999f) return
            var i = offset
            val end = offset + length - 1
            while (i < end) {
                val v = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff))
                val s = Math.round(v * gain).coerceIn(-32768, 32767)
                data[i] = s.toByte(); data[i + 1] = (s shr 8).toByte()
                i += 2
            }
        }
    }
}

/** One parametric equalizer band. Gains and Q are stored in tenths so the preference string stays integer. */
data class ParametricBand(val type: Int = OFF, val hz: Int = 1000, val gainTenths: Int = 0, val qTenths: Int = 10) {
    val gainDb: Float get() = gainTenths / 10f
    val q: Float get() = qTenths / 10f
    fun describe(): String = when (type) {
        OFF -> "Off"
        LOW_SHELF -> "Low shelf $hz Hz ${signed(gainDb)} dB"
        HIGH_SHELF -> "High shelf $hz Hz ${signed(gainDb)} dB"
        else -> "Peak $hz Hz ${signed(gainDb)} dB Q ${"%.1f".format(q)}"
    }
    fun encode(): String = "$type:$hz:$gainTenths:$qTenths"

    companion object {
        const val OFF = 0
        const val PEAK = 1
        const val LOW_SHELF = 2
        const val HIGH_SHELF = 3
        fun peak(hz: Int, gainDb: Float, q: Float = 1f) = ParametricBand(PEAK, hz, Math.round(gainDb * 10), Math.round(q * 10))
        fun lowShelf(hz: Int, gainDb: Float, q: Float = 0.7f) = ParametricBand(LOW_SHELF, hz, Math.round(gainDb * 10), Math.round(q * 10))
        fun highShelf(hz: Int, gainDb: Float, q: Float = 0.7f) = ParametricBand(HIGH_SHELF, hz, Math.round(gainDb * 10), Math.round(q * 10))
        fun decode(text: String): ParametricBand? {
            val parts = text.split(':').map { it.trim().toIntOrNull() ?: return null }
            if (parts.size != 4) return null
            return ParametricBand(parts[0].coerceIn(0, 3), parts[1].coerceIn(10, 20_000), parts[2].coerceIn(-120, 120), parts[3].coerceIn(3, 80))
        }
        fun decodeList(text: String?): List<ParametricBand> =
            text.orEmpty().split(',').filter { it.isNotBlank() }.mapNotNull(::decode).take(EqualizerDsp.MAX_PARAMETRIC)
        private fun signed(v: Float) = (if (v >= 0) "+" else "") + "%.1f".format(v)
    }
}
