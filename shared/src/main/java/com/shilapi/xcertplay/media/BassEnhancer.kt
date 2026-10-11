package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Psychoacoustic ("virtual") bass enhancer for small speakers, in the spirit of MaxxBass: the ear
 * infers a missing fundamental from its harmonic series, so the part of the bass the door speakers
 * cannot reproduce is re-expressed as 2nd–4th harmonics in the range they can.
 *
 * Signal flow, per frame (zero look-ahead; the only delay is the filters' own phase):
 *
 *     x ──┬── LP4(fc) ─────────────┬── × cut ──┐
 *         │                        │           ▼
 *         └── AP2(fc) ───────────────────────(−)── dry ──(+)── out
 *                                  │ mono sum              ▲
 *                                  ▼                       │
 *               peak/hold envelope → normalise → Chebyshev T2,T3,T4
 *                                  → HP4(1.5 fc) → LP2(min(6 fc, 700 Hz)) → × level gain
 *
 * - Band split: a Linkwitz–Riley 4th-order low-pass (two Butterworth sections) and the matching
 *   2nd-order all-pass. LR4 LP + LR4 HP equals that all-pass exactly (also after the bilinear
 *   transform, as all three share one denominator), so `AP − cut·LP` is `HP + (1 − cut)·LP`: the
 *   sub band can be trimmed without a high-pass section and with no comb anywhere, since the
 *   output never mixes filtered and unfiltered copies of the same band.
 * - Harmonic generator: the mono sub band is divided by its peak envelope, so a steady tone arrives
 *   with unit amplitude, and fed through Chebyshev polynomials: T_n(cos θ) = cos nθ, so each term
 *   yields exactly one harmonic at a designed weight, with no level-dependent drive. The output
 *   level is then set by the envelope, so harmonics track the bass linearly (a quiet passage gets
 *   the same harmonic ratio as a loud one, never a saturated buzz), with a 2:1 compressor above
 *   −12 dBFS so loud 808s do not pile up.
 * - Envelope: instant-attack peak detector with a 25 ms hold (longer than half a period down to
 *   20 Hz, so it sits flat on a sustained note and the harmonics stay pure) and a 60 ms release;
 *   the level that scales the harmonics is that envelope smoothed by a 5 ms one-pole (attack).
 * - Harmonics are band-limited to what a 6.5" door cone plays: 4th-order high-pass at 1.5× the
 *   crossover (removes DC, the fundamental and difference tones the nonlinearity creates) and a
 *   2nd-order low-pass at 6× the crossover, at most 700 Hz.
 * - Headroom guard (when no limiter follows): the harmonic gain is capped so `|dry| + |harmonics|`
 *   stays under the ceiling, using held instant-attack peak envelopes of the dry output and of the
 *   harmonic waveform. A 0 dBFS bass line therefore never clips because of the enhancer.
 * - Stereo linked: one mono control path; the same harmonic signal is added to every channel.
 *
 * Cost per stereo frame: nine biquads (three per channel, three on the mono path), one division,
 * one square root and a handful of multiplies.
 *
 * Not thread-safe by itself: [configure] may run on another thread like the rest of [EqualizerDsp],
 * whose coefficient smoothing ([step]) carries the change into the audio thread.
 */
class BassEnhancer(private val sampleRate: Int, private val channels: Int) {
    private val target = DoubleArray(SECTIONS * 5)
    private val current = DoubleArray(SECTIONS * 5)
    /** Per channel: LP4 stage 1, LP4 stage 2, all-pass (4 state values each, direct form I). */
    private val channelState = DoubleArray(channels * 12)
    /** Mono harmonic path: HP4 stage 1, HP4 stage 2, top low-pass. */
    private val monoState = DoubleArray(12)

    private var harmonicTarget = 0.0
    private var harmonic = 0.0
    private var cutTarget = 0.0
    private var cut = 0.0
    private var wetTarget = 0.0
    private var wet = 0.0
    // Per-sample smoothed copies (the chain steps its parameters once per 64-frame chunk).
    private var harmonicSmooth = 0.0
    private var cutSmooth = 0.0
    private var wetSmooth = 0.0

    /** True while the stage changes the signal (including while it fades out). */
    @Volatile var active = false
        private set
    /** Cap the harmonics so the stage cannot push its own output past full scale; off when a limiter follows. */
    @Volatile var headroomGuard = true

    private var env = 0.0
    private var hold = 0
    private var level = 0.0
    private var dryEnv = 0.0
    private var dryHold = 0
    private var harmEnv = 0.0
    private var harmHold = 0
    private var flushCounter = 0
    /** True once audio has been processed since the last [reset]; before that [snap] jumps directly. */
    private var running = false
    private val holdFrames = (HOLD_SECONDS * sampleRate).roundToInt()
    private val releaseMul = exp(-1.0 / (RELEASE_SECONDS * sampleRate))
    private val levelAttack = 1.0 - exp(-1.0 / (ATTACK_SECONDS * sampleRate))
    private val paramSmooth = 1.0 - exp(-1.0 / (PARAM_SECONDS * sampleRate))
    private val invChannels = 1.0 / channels

    /**
     * [amount] 0..100 sets the harmonic level and the sub-band trim together; [crossoverHz] 50..200
     * is the split frequency: content below it feeds the generator and is gently trimmed.
     */
    fun configure(amount: Int, crossoverHz: Int) {
        val a = amount.coerceIn(0, 100)
        if (a == 0) { harmonicTarget = 0.0; cutTarget = 0.0; wetTarget = 0.0; return }
        val fc = crossoverHz.coerceIn(50, 200).toDouble().coerceAtMost(sampleRate / 8.0)
        val c = DoubleArray(5)
        lowPass(c, fc, BUTTERWORTH_Q); set(LP, c)
        allPass(c, fc, BUTTERWORTH_Q); set(AP, c)
        val hp = HARMONIC_LOW * fc
        highPass(c, hp, BUTTERWORTH4_Q1); set(HPA, c)
        highPass(c, hp, BUTTERWORTH4_Q2); set(HPB, c)
        lowPass(c, min(HARMONIC_HIGH * fc, HARMONIC_CAP_HZ), BUTTERWORTH_Q); set(TOP, c)
        val x = a / 100.0
        harmonicTarget = HARMONIC_MAX * x
        cutTarget = 1.0 - 10.0.pow(-SUB_CUT_DB * x * x / 20.0)
        wetTarget = 1.0
        if (!active) {
            // Start from the dry signal with the new filters; the smoothing fades the stage in.
            reset()
            System.arraycopy(target, 0, current, 0, target.size)
            harmonic = 0.0; cut = 0.0; wet = 0.0
            harmonicSmooth = 0.0; cutSmooth = 0.0; wetSmooth = 0.0
            active = true
        }
    }

    /** Jumps to the configured values (first configuration, no fade). */
    fun snap() {
        System.arraycopy(target, 0, current, 0, target.size)
        harmonic = harmonicTarget; cut = cutTarget; wet = wetTarget
        if (!active || !running) { harmonicSmooth = harmonic; cutSmooth = cut; wetSmooth = wet }
        if (wetTarget == 0.0 && wetSmooth == 0.0) active = false
    }

    /** One smoothing step towards the configured values; [alpha] = 1 lands on them. */
    fun step(alpha: Double) {
        if (alpha >= 1.0) { snap(); return }
        for (k in target.indices) current[k] += (target[k] - current[k]) * alpha
        harmonic += (harmonicTarget - harmonic) * alpha
        cut += (cutTarget - cut) * alpha
        wet += (wetTarget - wet) * alpha
    }

    fun reset() {
        channelState.fill(0.0); monoState.fill(0.0)
        env = 0.0; hold = 0; level = 0.0; dryEnv = 0.0; dryHold = 0; harmEnv = 0.0; harmHold = 0
        running = false
    }

    /** Processes one interleaved frame ([channels] samples from [at]) in place. */
    fun process(frame: DoubleArray, at: Int = 0) {
        running = true
        val c = current
        wetSmooth += (wet - wetSmooth) * paramSmooth
        cutSmooth += (cut - cutSmooth) * paramSmooth
        harmonicSmooth += (harmonic - harmonicSmooth) * paramSmooth
        val w = wetSmooth
        val trim = cutSmooth
        var mono = 0.0
        var peak = 0.0
        for (ch in 0 until channels) {
            val x = frame[at + ch]
            val s = ch * 12
            val lp = biquad(biquad(x, c, LP * 5, channelState, s), c, LP * 5, channelState, s + 4)
            val ap = biquad(x, c, AP * 5, channelState, s + 8)
            val dry = x + w * (ap - trim * lp - x)
            frame[at + ch] = dry
            mono += lp
            val m = abs(dry)
            if (m > peak) peak = m
        }
        mono *= invChannels

        // Sub-band envelope: instant attack; the hold re-arms on every peak within 0.5 dB, so a
        // sustained note keeps a flat envelope; after the hold, exponential release.
        val r = abs(mono)
        if (r >= env) { env = r; hold = holdFrames }
        else if (r >= env * REARM) hold = holdFrames
        else if (hold > 0) hold-- else env *= releaseMul
        level += (env - level) * levelAttack

        // Unit-amplitude carrier → exact harmonic series.
        var u = mono / (if (env > FLOOR) env else FLOOR)
        if (u > 1.0) u = 1.0 else if (u < -1.0) u = -1.0
        val u2 = u * u
        val t2 = 2.0 * u2 - 1.0
        val p = (W2 * t2 + W3 * u * (4.0 * u2 - 3.0) + W4 * (2.0 * t2 * t2 - 1.0)) * INV_WEIGHTS
        var h = biquad(p, c, HPA * 5, monoState, 0)
        h = biquad(h, c, HPB * 5, monoState, 4)
        h = biquad(h, c, TOP * 5, monoState, 8)

        // Level tracking: linear below the knee, 2:1 above it.
        var g = harmonicSmooth * w * (if (level > KNEE) sqrt(KNEE * level) else level)

        // Headroom guard: held peaks of the dry output and of the harmonic waveform, both with
        // instant attack, so |dry| + |h|·g <= dryEnv + harmEnv·g <= CEILING on every sample.
        if (peak >= dryEnv) { dryEnv = peak; dryHold = holdFrames }
        else if (peak >= dryEnv * REARM) dryHold = holdFrames
        else if (dryHold > 0) dryHold-- else dryEnv *= releaseMul
        val ah = abs(h)
        if (ah >= harmEnv) { harmEnv = ah; harmHold = holdFrames }
        else if (ah >= harmEnv * REARM) harmHold = holdFrames
        else if (harmHold > 0) harmHold-- else harmEnv *= releaseMul
        if (headroomGuard) {
            val room = CEILING - dryEnv
            if (room <= 0.0) g = 0.0 else if (g * harmEnv > room) g = room / harmEnv
        }
        val add = h * g
        for (ch in 0 until channels) frame[at + ch] += add

        if (++flushCounter >= FLUSH_FRAMES) {
            flushCounter = 0
            flushDenormals()
            if (wetTarget == 0.0 && wet == 0.0 && wetSmooth < 1e-6) active = false
        }
    }

    /** Processes [frames] interleaved frames of [buffer] in place. */
    fun processBlock(buffer: DoubleArray, frames: Int) {
        var at = 0
        for (n in 0 until frames) { process(buffer, at); at += channels }
    }

    /** Magnitude of the harmonic band-pass at [hz] (linear), for tests and diagnostics. */
    internal fun harmonicBandGain(hz: Double): Double =
        magnitude(target, HPA * 5, hz) * magnitude(target, HPB * 5, hz) * magnitude(target, TOP * 5, hz)

    /** Linear gain applied to the sub band at full mix (1 − trim). */
    internal fun subBandGain(): Double = 1.0 - cutTarget

    /** Envelope-to-harmonic gain ignoring the headroom guard, for tests. */
    internal fun harmonicGain(): Double = harmonicTarget

    // ---- internals -----------------------------------------------------------------------------

    private fun set(section: Int, c: DoubleArray) { System.arraycopy(c, 0, target, section * 5, 5) }

    private fun flushDenormals() {
        for (i in channelState.indices) if (abs(channelState[i]) < 1e-30) channelState[i] = 0.0
        for (i in monoState.indices) if (abs(monoState[i]) < 1e-30) monoState[i] = 0.0
        if (env < 1e-30) env = 0.0
        if (level < 1e-30) level = 0.0
        if (dryEnv < 1e-30) dryEnv = 0.0
        if (harmEnv < 1e-30) harmEnv = 0.0
        if (wetSmooth < 1e-30) wetSmooth = 0.0
    }

    private fun biquad(x: Double, c: DoubleArray, k: Int, s: DoubleArray, i: Int): Double {
        val y = c[k] * x + c[k + 1] * s[i] + c[k + 2] * s[i + 1] - c[k + 3] * s[i + 2] - c[k + 4] * s[i + 3]
        s[i + 1] = s[i]; s[i] = x
        s[i + 3] = s[i + 2]; s[i + 2] = y
        return y
    }

    private fun magnitude(c: DoubleArray, k: Int, f: Double): Double {
        val w = 2.0 * PI * f / sampleRate
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = c[k] + c[k + 1] * c1 + c[k + 2] * c2; val ni = -(c[k + 1] * s1 + c[k + 2] * s2)
        val dr = 1.0 + c[k + 3] * c1 + c[k + 4] * c2; val di = -(c[k + 3] * s1 + c[k + 4] * s2)
        return sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    // RBJ cookbook sections, normalised [b0, b1, b2, a1, a2]. LP, HP and AP at one f and Q share a
    // denominator, which is what makes LP4 + HP4 = AP2 hold exactly.
    private fun lowPass(out: DoubleArray, f: Double, q: Double) {
        val w0 = 2.0 * PI * f / sampleRate; val alpha = sin(w0) / (2.0 * q); val cw = cos(w0); val a0 = 1 + alpha
        out[0] = (1 - cw) / 2 / a0; out[1] = (1 - cw) / a0; out[2] = (1 - cw) / 2 / a0; out[3] = -2 * cw / a0; out[4] = (1 - alpha) / a0
    }

    private fun highPass(out: DoubleArray, f: Double, q: Double) {
        val w0 = 2.0 * PI * f / sampleRate; val alpha = sin(w0) / (2.0 * q); val cw = cos(w0); val a0 = 1 + alpha
        out[0] = (1 + cw) / 2 / a0; out[1] = -(1 + cw) / a0; out[2] = (1 + cw) / 2 / a0; out[3] = -2 * cw / a0; out[4] = (1 - alpha) / a0
    }

    private fun allPass(out: DoubleArray, f: Double, q: Double) {
        val w0 = 2.0 * PI * f / sampleRate; val alpha = sin(w0) / (2.0 * q); val cw = cos(w0); val a0 = 1 + alpha
        out[0] = (1 - alpha) / a0; out[1] = -2 * cw / a0; out[2] = 1.0; out[3] = -2 * cw / a0; out[4] = (1 - alpha) / a0
    }

    companion object {
        private const val SECTIONS = 5
        private const val LP = 0
        private const val AP = 1
        private const val HPA = 2
        private const val HPB = 3
        private const val TOP = 4

        private const val BUTTERWORTH_Q = 0.7071067811865476
        private const val BUTTERWORTH4_Q1 = 0.5411961001461970
        private const val BUTTERWORTH4_Q2 = 1.3065629648763766

        /** Harmonic weights: 2nd 0 dB, 3rd −4.4 dB, 4th −9.1 dB, normalised so the series peaks at 1. */
        const val W2 = 1.0
        const val W3 = 0.6
        const val W4 = 0.35
        private const val INV_WEIGHTS = 1.0 / (W2 + W3 + W4)

        /** Harmonic band: 1.5× to 6× the crossover, never above 700 Hz. */
        const val HARMONIC_LOW = 1.5
        const val HARMONIC_HIGH = 6.0
        const val HARMONIC_CAP_HZ = 700.0

        /** At amount 100 the harmonic series (before the band-pass) carries as much RMS as the sub band. */
        const val HARMONIC_MAX = 1.6
        /** Sub-band trim at amount 100, applied with amount² so low settings are purely additive. */
        const val SUB_CUT_DB = 6.0
        /** Envelope level (sub-band peak) above which harmonics grow at 2:1, about −12 dBFS. */
        const val KNEE = 0.25

        const val ATTACK_SECONDS = 0.005
        const val HOLD_SECONDS = 0.025
        const val RELEASE_SECONDS = 0.060
        /** A peak within 0.5 dB of the held envelope re-arms the hold. */
        private const val REARM = 0.944
        /** Per-sample smoothing of amount, trim and fade between the chain's 64-frame parameter steps. */
        private const val PARAM_SECONDS = 0.002
        private const val FLOOR = 3e-5
        private const val CEILING = 0.98
        private const val FLUSH_FRAMES = 1024
    }
}
