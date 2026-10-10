package com.shilapi.xcertplay.media

import java.util.Collections
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
import kotlin.math.tanh

/**
 * DiPlay's software audio chain on 16-bit interleaved PCM, applied in place before the AudioTrack
 * write. Stages in order: subsonic high-pass, 12-band graphic equalizer, up to five parametric
 * bands, psychoacoustic bass enhancer, auto level, look-ahead limiter, TPDF dither.
 *
 * Design notes, in the spirit of a car-audio DSP rather than a phone app:
 * - All filtering runs in double precision; the signal is quantised once, at the output, with
 *   triangular dither. Direct-form-I biquads in single precision lose low-frequency accuracy at
 *   31 Hz / 48 kHz; double costs nothing extra on this CPU.
 * - Peaking filters use Orfanidis' design with prescribed Nyquist gain, so bells near 16 kHz keep
 *   their analog shape instead of being squashed against Nyquist by the bilinear transform.
 * - The graphic equalizer is constant-Q per band spacing and its band gains are corrected for
 *   neighbour interaction, so +6 dB on adjacent sliders produces +6 dB, not +9.
 * - Coefficient changes are interpolated over ~30 ms so slider drags never click.
 * - The limiter is a true look-ahead peak limiter (2 ms) with a soft-knee safety stage after it,
 *   instead of a static waveshaper that distorts whenever it works.
 * Neutral stages are skipped, so the cost scales with what is switched on.
 */
class EqualizerDsp(private val sampleRate: Int, private val channels: Int) {
    /** A bank of biquads with target coefficients, interpolating current coefficients and state. */
    private inner class Bank(val count: Int) {
        val target = DoubleArray(count * 5)
        val current = DoubleArray(count * 5)
        @Volatile var active = BooleanArray(count)
        @Volatile var any = false
        val state = DoubleArray(channels * count * 4)
        fun set(index: Int, coefficients: DoubleArray) { System.arraycopy(coefficients, 0, target, index * 5, 5) }
        fun identity(index: Int) { target[index * 5] = 1.0; for (k in 1..4) target[index * 5 + k] = 0.0 }
        fun snap() { System.arraycopy(target, 0, current, 0, target.size) }
        fun step(alpha: Double) { for (k in target.indices) current[k] += (target[k] - current[k]) * alpha }
        fun clear() { state.fill(0.0) }
    }

    private val graphic = Bank(BANDS)
    private val parametric = Bank(MAX_PARAMETRIC)
    private val subsonic = Bank(2)
    private val bass = Bank(3)
    @Volatile private var subsonicOn = false
    @Volatile private var bassOn = false
    private var bassGainTarget = 0.0
    private var bassGain = 0.0

    // Auto level: RMS envelope → slow gain
    @Volatile private var autoOn = false
    @Volatile private var autoStrength = 0.0
    private var meanSquare = 0.0
    private var agcGain = 1.0
    private var agcDesired = 1.0
    private var agcCounter = 0
    private val envAttack = coefficient(0.050)
    private val envRelease = coefficient(1.0)
    private val gainAttack = coefficient(0.080)
    private val gainRelease = coefficient(2.0)

    // Look-ahead limiter
    @Volatile var limiter = false
        private set
    @Volatile private var softClipForced = false
    private val lookahead = max(8, sampleRate * LOOKAHEAD_MICROS / 1_000_000)
    private val delay = DoubleArray(lookahead * channels)
    private val delayPeak = DoubleArray(lookahead)
    private var limiterGain = 1.0
    private val limiterRelease = coefficient(0.080)
    private var scratch = DoubleArray(0)
    private var peaks = DoubleArray(0)
    private var required = DoubleArray(0)

    // Coefficient smoothing and dither
    private var smoothChunks = 0
    private var ditherState = 0x9E3779B9.toInt()
    private var floatOut: FloatArray? = null
    private var floatIndex = 0
    private val frame = DoubleArray(channels)
    @Volatile private var configured = false

    /** Graphic-only configuration kept for callers and tests; all other stages switch off. */
    fun configure(gainsDb: FloatArray, limiter: Boolean) {
        configureGraphic(gainsDb)
        configureParametric(emptyList())
        configureSubsonic(0)
        configureBass(0, 100)
        configureAutoLevel(false, 0)
        setLimiter(limiter)
        finishConfigure()
    }

    /** Full configuration from the user's settings; [softClip] forces the limiter on (media soft clipper). */
    fun configure(settings: AudioEffectSettings, softClip: Boolean) {
        softClipForced = softClip
        val active = settings.enabled && settings.dspMode
        configureGraphic(if (active) settings.dspGainsDb() else FloatArray(BANDS))
        configureParametric(if (active) settings.parametric else emptyList())
        configureSubsonic(if (active) settings.subsonicHz else 0)
        configureBass(if (active) settings.bassEnhance else 0, settings.bassEnhanceHz)
        configureAutoLevel(active && settings.autoLevel, settings.autoLevelAmount)
        setLimiter(softClip || (active && (settings.limiter || settings.autoLevel)))
        finishConfigure()
    }

    private fun finishConfigure() {
        if (!configured) {
            graphic.snap(); parametric.snap(); subsonic.snap(); bass.snap(); bassGain = bassGainTarget
            configured = true
        } else {
            smoothChunks = SMOOTH_CHUNKS
        }
    }

    private fun setLimiter(on: Boolean) {
        if (on && !limiter) { delay.fill(0.0); delayPeak.fill(0.0); limiterGain = 1.0 }
        limiter = on
    }

    // ---- stage configuration ------------------------------------------------------------------

    private fun configureGraphic(gainsDb: FloatArray) {
        val targets = DoubleArray(BANDS) { gainsDb.getOrElse(it) { 0f }.coerceIn(MIN_DB, MAX_DB).toDouble() }
        val usable = BooleanArray(BANDS) { abs(targets[it]) >= 0.05 && CENTER_HZ[it] < sampleRate / 2.0 }
        val designed = DoubleArray(BANDS) { if (usable[it]) targets[it] else 0.0 }
        val sections = Array(BANDS) { DoubleArray(5) }
        // Neighbour interaction: redesign a few times until the composite response hits each target.
        repeat(if (usable.count { it } > 1) INTERACTION_ITERATIONS else 1) {
            for (band in 0 until BANDS) if (usable[band]) peakingOrfanidis(sections[band], CENTER_HZ[band].toDouble(), designed[band], GRAPHIC_Q[band])
            if (usable.count { it } <= 1) return@repeat
            for (band in 0 until BANDS) {
                if (!usable[band]) continue
                var actual = 0.0
                for (other in 0 until BANDS) if (usable[other]) actual += magnitudeDb(sections[other], CENTER_HZ[band].toDouble())
                designed[band] = (designed[band] + (targets[band] - actual) * INTERACTION_DAMPING).coerceIn(MIN_DB.toDouble() - 6, MAX_DB.toDouble() + 6)
            }
        }
        val active = BooleanArray(BANDS)
        for (band in 0 until BANDS) {
            if (usable[band]) { graphic.set(band, sections[band]); active[band] = true } else graphic.identity(band)
        }
        graphic.active = active
        graphic.any = active.any { it }
    }

    private fun configureParametric(bands: List<ParametricBand>) {
        val active = BooleanArray(MAX_PARAMETRIC)
        for (index in 0 until MAX_PARAMETRIC) {
            val band = bands.getOrNull(index)
            val gain = band?.gainDb?.coerceIn(MIN_DB, MAX_DB)?.toDouble() ?: 0.0
            val f = band?.hz?.toDouble() ?: 0.0
            if (band == null || band.type == ParametricBand.OFF || abs(gain) < 0.05 || f < 10 || f >= sampleRate / 2.0) {
                parametric.identity(index); continue
            }
            val q = band.q.coerceIn(0.3f, 8f).toDouble()
            val c = DoubleArray(5)
            when (band.type) {
                ParametricBand.LOW_SHELF -> lowShelf(c, f, gain, q)
                ParametricBand.HIGH_SHELF -> highShelf(c, f, gain, q)
                else -> peakingOrfanidis(c, f, gain, q)
            }
            parametric.set(index, c)
            active[index] = true
        }
        parametric.active = active
        parametric.any = active.any { it }
    }

    private fun configureSubsonic(hz: Int) {
        if (hz < 10 || hz >= sampleRate / 4) { subsonicOn = false; subsonic.identity(0); subsonic.identity(1); return }
        val c = DoubleArray(5)
        highPass(c, hz.toDouble(), BUTTERWORTH_Q)
        subsonic.set(0, c); subsonic.set(1, c)
        subsonicOn = true
    }

    private fun configureBass(amount: Int, hz: Int) {
        if (amount <= 0) { bassOn = false; bassGainTarget = 0.0; return }
        val crossover = hz.coerceIn(50, 200).toDouble()
        val low = DoubleArray(5); val high = DoubleArray(5); val top = DoubleArray(5)
        lowPass(low, crossover, BUTTERWORTH_Q)
        highPass(high, crossover, BUTTERWORTH_Q)
        lowPass(top, min(crossover * 5.0, 900.0), BUTTERWORTH_Q)
        bass.set(0, low); bass.set(1, high); bass.set(2, top)
        bassGainTarget = amount.coerceIn(0, 100) / 100.0 * BASS_MIX
        bassOn = true
    }

    private fun configureAutoLevel(on: Boolean, amount: Int) {
        autoStrength = amount.coerceIn(0, 100) / 100.0
        if (on && !autoOn) { meanSquare = 0.0; agcGain = 1.0; agcDesired = 1.0; agcCounter = 0 }
        autoOn = on && autoStrength > 0.0
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
        val useLimiter = limiter
        val g = graphic.any; val p = parametric.any; val sub = subsonicOn; val b = bassOn; val auto = autoOn
        if (!g && !p && !sub && !b && !auto && !useLimiter) return false
        val frames = length / (2 * channels)
        if (frames <= 0) return false
        ensureScratch(frames)
        val gActive = graphic.active; val pActive = parametric.active
        var index = offset
        var produced = 0
        while (produced < frames) {
            val chunk = min(SMOOTH_CHUNK_FRAMES, frames - produced)
            if (smoothChunks > 0) {
                val alpha = 1.0 / smoothChunks
                graphic.step(alpha); parametric.step(alpha); subsonic.step(alpha); bass.step(alpha)
                bassGain += (bassGainTarget - bassGain) * alpha
                smoothChunks--
            }
            val gc = graphic.current; val pc = parametric.current; val sc = subsonic.current; val bc = bass.current
            val bg = bassGain
            for (n in produced until produced + chunk) {
                var mono = 0.0
                var peak = 0.0
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
                    if (b) {
                        val s = ch * 12
                        val low = biquad(x, bc, 0, bass.state, s)
                        val u = low * BASS_DRIVE
                        val a = abs(u)
                        val sat = a / (1.0 + a)
                        // Odd harmonics from the symmetric part, even harmonics from the rectified part.
                        var h = (if (u < 0) -sat else sat) + BASS_EVEN * sat * sat
                        h = biquad(h, bc, 5, bass.state, s + 4)
                        h = biquad(h, bc, 10, bass.state, s + 8)
                        x += h * bg
                    }
                    frame[ch] = x
                    mono += x
                }
                if (auto) {
                    mono /= channels
                    val square = mono * mono
                    meanSquare += (square - meanSquare) * (if (square > meanSquare) envAttack else envRelease)
                    if (++agcCounter >= AGC_UPDATE_FRAMES) {
                        agcCounter = 0
                        val rms = sqrt(meanSquare)
                        if (rms > AGC_GATE) agcDesired = (AGC_TARGET_RMS / rms).pow(autoStrength).coerceIn(AGC_MIN_GAIN, AGC_MAX_GAIN)
                    }
                    agcGain += (agcDesired - agcGain) * (if (agcDesired < agcGain) gainAttack else gainRelease)
                }
                val gain = if (auto) agcGain else 1.0
                val at = n * channels
                for (ch in 0 until channels) {
                    val x = frame[ch] * gain
                    scratch[at + ch] = x
                    val m = abs(x)
                    if (m > peak) peak = m
                }
                peaks[n] = peak
                index += channels * 2
            }
            produced += chunk
        }
        if (useLimiter) writeLimited(data, offset, frames) else writeDirect(data, offset, frames)
        return true
    }

    /** No limiter: quantise the processed block with dither. */
    private fun writeDirect(data: ByteArray, offset: Int, frames: Int) {
        var o = offset
        for (i in 0 until frames * channels) { put(data, o, scratch[i]); o += 2 }
    }

    /**
     * Look-ahead limiter. The output lags the input by [lookahead] frames; the gain for each
     * output frame is the minimum required over the next [lookahead] frames, reached by a linear
     * ramp (attack) and recovered with an exponential release, so peaks are caught before they
     * arrive and the waveform itself is not bent.
     */
    private fun writeLimited(data: ByteArray, offset: Int, frames: Int) {
        val total = lookahead + frames
        // required[i]: gain needed so that frame i of (delay ++ current) stays under the ceiling.
        for (i in 0 until lookahead) required[i] = if (delayPeak[i] > CEILING) CEILING / delayPeak[i] else 1.0
        for (i in 0 until frames) required[lookahead + i] = if (peaks[i] > CEILING) CEILING / peaks[i] else 1.0
        // Backward pass: let the gain rise by at most `step` per frame going back in time, which is a
        // forward-time ramp down to each peak over the look-ahead window.
        val step = 1.0 / lookahead
        for (i in total - 2 downTo 0) {
            val allowed = required[i + 1] + step
            if (allowed < required[i]) required[i] = allowed
        }
        var gain = limiterGain
        var o = offset
        for (i in 0 until frames) {
            val target = required[i]
            gain = if (target < gain) target else gain + (target - gain) * limiterRelease
            // Output frame i is the oldest delayed frame; the current frame i takes its place.
            val slot = i % lookahead
            val di = slot * channels
            val si = i * channels
            for (ch in 0 until channels) {
                var x = delay[di + ch] * gain
                val m = abs(x)
                if (m > CEILING) { // safety stage: only reached by content faster than the look-ahead
                    val soft = CEILING + (SAFETY_MAX - CEILING) * tanh((m - CEILING) / (SAFETY_MAX - CEILING))
                    x = if (x < 0) -soft else soft
                }
                put(data, o, x); o += 2
                delay[di + ch] = scratch[si + ch]
            }
            delayPeak[slot] = peaks[i]
        }
        limiterGain = gain
        if (frames % lookahead != 0) rotateDelay(frames % lookahead)
    }

    /** Keeps the delay line ordered oldest-first after a block that did not fill whole rounds. */
    private fun rotateDelay(by: Int) {
        val tmp = DoubleArray(delay.size)
        val tmpPeak = DoubleArray(lookahead)
        for (i in 0 until lookahead) {
            val from = (i + by) % lookahead
            System.arraycopy(delay, from * channels, tmp, i * channels, channels)
            tmpPeak[i] = delayPeak[from]
        }
        System.arraycopy(tmp, 0, delay, 0, delay.size)
        System.arraycopy(tmpPeak, 0, delayPeak, 0, lookahead)
    }

    /** 16-bit output with TPDF dither (two uniform draws, ±1 LSB triangular), or float when rendering to float. */
    private fun put(data: ByteArray, o: Int, x: Double) {
        val f = floatOut
        if (f != null) { f[floatIndex++] = x.toFloat(); return }
        var r = ditherState
        r = r xor (r shl 13); r = r xor (r ushr 17); r = r xor (r shl 5)
        val d1 = (r and 0xffff) / 65536.0
        r = r xor (r shl 13); r = r xor (r ushr 17); r = r xor (r shl 5)
        val d2 = (r and 0xffff) / 65536.0
        ditherState = r
        val v = Math.round(x * 32767.0 + (d1 - d2)).toInt().coerceIn(-32768, 32767)
        data[o] = v.toByte()
        data[o + 1] = (v shr 8).toByte()
    }

    private fun ensureScratch(frames: Int) {
        if (scratch.size < frames * channels) scratch = DoubleArray(frames * channels)
        if (peaks.size < frames) peaks = DoubleArray(frames)
        if (required.size < frames + lookahead) required = DoubleArray(frames + lookahead)
    }

    /** Current auto-level gain in dB, for the UI. */
    fun autoLevelGainDb(): Float = if (autoOn) (20.0 * log10(agcGain)).toFloat() else 0f

    /** Output delay introduced by the chain, in frames (the limiter's look-ahead when it is on). */
    fun latencyFrames(): Int = if (limiter) lookahead else 0

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

    private fun lowPass(out: DoubleArray, f: Double, q: Double) {
        val w0 = 2.0 * PI * f / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        store(out, (1 - cosW0) / 2, 1 - cosW0, (1 - cosW0) / 2, 1 + alpha, -2 * cosW0, 1 - alpha)
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
        private const val SMOOTH_CHUNKS = 24
        private const val LOOKAHEAD_MICROS = 2_000
        /** Peaks settle just under full scale so rounding never produces a hard-clipped sample. */
        private const val CEILING = 0.985
        private const val SAFETY_MAX = 0.999
        private const val BASS_DRIVE = 6.0
        private const val BASS_EVEN = 0.6
        private const val BASS_MIX = 1.0
        /** Auto level aims the RMS envelope at about -16 dBFS. */
        private const val AGC_TARGET_RMS = 0.16
        private const val AGC_GATE = 0.002
        private const val AGC_MIN_GAIN = 0.25
        private const val AGC_MAX_GAIN = 4.0
        private const val AGC_UPDATE_FRAMES = 128
        private val active: MutableSet<EqualizerDsp> = Collections.synchronizedSet(mutableSetOf<EqualizerDsp>())

        fun applyToActive(settings: AudioEffectSettings) {
            val snapshot = synchronized(active) { active.toList() }
            snapshot.forEach { it.configure(settings, it.softClipForced) }
            headroom = settings.headroomGain()
        }

        /** Shared pre-gain read by every renderer on each write; updated with the settings. */
        @Volatile var headroom: Float = 1f

        /** Scales 16-bit interleaved PCM in place by [gain]. */
        fun scale(data: ByteArray, offset: Int, length: Int, gain: Float) {
            if (gain >= 0.999f) return
            var i = offset
            val end = offset + length - 1
            while (i < end) {
                val v = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff))
                val s = (v * gain).toInt().coerceIn(-32768, 32767)
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
