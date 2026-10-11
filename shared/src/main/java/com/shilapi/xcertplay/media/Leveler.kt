package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Slow, stereo-linked program leveler (broadcast / car-radio style "AGC"), feed-forward.
 *
 * Control path (never heard):
 * 1. K-weighting-style pre-filter per channel: 2nd-order high-pass at [HPF_HZ] (Q 0.5, removes
 *    sub-bass that is loud on a meter but not to the ear, so kick drums and 808s do not drive the
 *    gain) followed by a +4 dB high shelf at [SHELF_HZ] (head/presence weighting, as in BS.1770).
 * 2. Mean square per block of [blockFrames] frames (~2.7 ms), summed over channels (BS.1770 channel
 *    sum; mono is counted as dual-mono), converted to LUFS-like dB.
 * 3. Gating: blocks under [ABS_GATE_LUFS] (digital silence, dither, hiss) or more than
 *    [REL_GATE_LU] under the current control loudness (pauses between words, gaps, tails) are not
 *    integrated and freeze the gain, so the gain never creeps up in gaps. A relatively-gated stretch
 *    longer than [REL_GATE_ESCAPE_S] is accepted as genuinely quieter program; [REPRIME_AFTER_S] of
 *    absolute silence (track change) re-seeds the estimators so a new song is acquired quickly.
 * 4. Two estimators: "momentary" M, a power average with time constant [MOMENTARY_TAU_S] (BS.1770
 *    momentary window; falls with [MOMENTARY_FALL_TAU_S]), and "slow" S, which rises toward M with
 *    [SLOW_RISE_TAU_S] and falls at no more than [SLOW_FALL_DB_PER_S]. Control loudness = max(M, S). This is the program-dependent
 *    release: a short loud event barely charges S, so the gain recovers as fast as M decays; a long
 *    loud passage charges S, so the gain comes back slowly and the macro-dynamics survive.
 * 5. Gain computer: desired = strength * (target - control), clamped to +-[MAX_GAIN_DB] (strength 1
 *    = full normalisation, 0.5 = ratio 2:1 around the target). The gain integrator follows it with
 *    attack tau [ATTACK_TAU_S], slew-limited to [ATTACK_MIN_DB_PER_S] rising to [ATTACK_MAX_DB_PER_S]
 *    over [ATTACK_RAMP_S] of sustained overshoot, and after a [HOLD_S] hold with release tau
 *    [RELEASE_TAU_S] capped at [RELEASE_DB_PER_S]. Slew limits plus hold prevent pumping.
 * 6. The gain is updated once per block and ramped linearly per frame (no zipper noise).
 *
 * No peak protection here: the look-ahead limiter that follows in [EqualizerDsp] handles peaks.
 * Cost per frame: two biquads per channel, a square-accumulate and a gain ramp; per block one log10
 * and one pow. Not thread-safe except for [strength], [reset] and [gainDb], which may be called from
 * any thread; [process] belongs to the audio thread.
 */
class Leveler(sampleRate: Int, private val channels: Int) {
    /** 0..1: 1 = full normalisation to the target, 0 = no gain change. */
    @Volatile var strength: Double = 0.5
        set(value) { field = value.coerceIn(0.0, 1.0) }

    val blockFrames: Int = max(16, min(256, Math.round(sampleRate * BLOCK_S).toInt()))
    private val dt = blockFrames.toDouble() / sampleRate
    private val powerScale = 2.0 / max(1, channels) / blockFrames   // dual-mono for 1 channel, sum for 2

    private val hp = DoubleArray(5)
    private val shelf = DoubleArray(5)
    private val state = DoubleArray(max(1, channels) * 8)

    private val momentaryCoef = 1.0 - exp(-dt / MOMENTARY_TAU_S)
    private val momentaryFallCoef = 1.0 - exp(-dt / MOMENTARY_FALL_TAU_S)
    private val slowRiseCoef = 1.0 - exp(-dt / SLOW_RISE_TAU_S)
    private val attackCoef = 1.0 - exp(-dt / ATTACK_TAU_S)
    private val releaseCoef = 1.0 - exp(-dt / RELEASE_TAU_S)
    private val primeBlocks = max(1, Math.round(MOMENTARY_TAU_S / dt).toInt())

    // Block accumulation
    private var acc = 0.0
    private var count = 0

    // Estimators (audio thread only)
    private var primed = false
    private var validBlocks = 0
    private var momentary = 0.0
    private var slowDb = 0.0
    private var controlDb = 0.0
    private var silenceS = 0.0
    private var relGatedS = 0.0
    private var holdS = 0.0
    private var attackS = 0.0
    private var levelDb = 0.0

    // Applied gain, ramped per frame toward the block target
    private var gain = 1.0
    private var gainStep = 0.0

    @Volatile private var resetPending = false
    @Volatile private var published = 0f

    init {
        highPass(hp, sampleRate.toDouble(), HPF_HZ, HPF_Q)
        highShelf(shelf, sampleRate.toDouble(), min(SHELF_HZ, sampleRate * 0.4), SHELF_DB)
    }

    /** Requests a full reset (gain back to 0 dB); applied on the audio thread at the next frame. */
    fun reset() { resetPending = true; published = 0f }

    /** Current leveler gain in dB (published once per block). */
    fun gainDb(): Float = published

    /**
     * Feeds one frame of the program ([frame] holds [channels] samples, full scale = 1.0) and
     * returns the linear gain to apply to that same frame.
     */
    fun process(frame: DoubleArray): Double {
        if (resetPending) doReset()
        val h = hp; val k = shelf; val s = state
        var sum = 0.0
        for (ch in 0 until channels) {
            val i = ch * 8
            val x = frame[ch]
            val y1 = h[0] * x + h[1] * s[i] + h[2] * s[i + 1] - h[3] * s[i + 2] - h[4] * s[i + 3]
            s[i + 1] = s[i]; s[i] = x; s[i + 3] = s[i + 2]; s[i + 2] = y1
            val y2 = k[0] * y1 + k[1] * s[i + 4] + k[2] * s[i + 5] - k[3] * s[i + 6] - k[4] * s[i + 7]
            s[i + 5] = s[i + 4]; s[i + 4] = y1; s[i + 7] = s[i + 6]; s[i + 6] = y2
            sum += y2 * y2
        }
        acc += sum
        gain += gainStep
        if (++count >= blockFrames) endBlock()
        return gain
    }

    private fun doReset() {
        resetPending = false
        state.fill(0.0); acc = 0.0; count = 0
        primed = false; validBlocks = 0; momentary = 0.0; slowDb = 0.0; controlDb = 0.0
        silenceS = 0.0; relGatedS = 0.0; holdS = 0.0; attackS = 0.0; levelDb = 0.0
        gain = 1.0; gainStep = 0.0; published = 0f
    }

    private fun endBlock() {
        val p = acc * powerScale
        acc = 0.0; count = 0
        // Flush tiny filter state so long digital silence never runs the CPU on denormals.
        for (i in state.indices) if (abs(state[i]) < 1e-18) state[i] = 0.0
        val blockDb = if (p > 1e-20) 10.0 * log10(p) + LUFS_OFFSET else -200.0

        if (blockDb < ABS_GATE_LUFS) {
            // Silence / noise floor: freeze everything; a long silence means a new program follows.
            relGatedS = 0.0
            silenceS += dt
            if (silenceS >= REPRIME_AFTER_S) primed = false
            setTarget()
            return
        }
        silenceS = 0.0
        if (primed && blockDb < controlDb - REL_GATE_LU) {
            if (relGatedS < REL_GATE_ESCAPE_S) {   // a pause or gap: freeze
                relGatedS += dt
                setTarget()
                return
            }
            // Gated for long enough: this is quieter program, integrate it.
        } else {
            relGatedS = 0.0
        }

        if (!primed) { primed = true; validBlocks = 0 }
        validBlocks++
        // Running mean for the first window, exponential afterwards: unbiased fast acquisition.
        val mc = when {
            validBlocks < primeBlocks -> 1.0 / validBlocks
            p > momentary -> momentaryCoef
            else -> momentaryFallCoef
        }
        momentary += (p - momentary) * mc
        val mDb = if (momentary > 1e-20) 10.0 * log10(momentary) + LUFS_OFFSET else -200.0
        slowDb = when {
            validBlocks <= primeBlocks -> mDb
            mDb > slowDb -> slowDb + (mDb - slowDb) * slowRiseCoef
            else -> max(mDb, slowDb - SLOW_FALL_DB_PER_S * dt)
        }
        controlDb = max(mDb, slowDb)

        val desired = (strength * (TARGET_LUFS - controlDb)).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        val err = desired - levelDb
        if (err < 0) {
            if (err < -HOLD_TRIGGER_DB) holdS = HOLD_S
            // Attack slew starts gentle and speeds up the longer the program stays too loud, so a
            // shout or a drum fill only dents the gain while a loud new song is caught within ~1 s.
            attackS += dt
            val rate = ATTACK_MIN_DB_PER_S + (ATTACK_MAX_DB_PER_S - ATTACK_MIN_DB_PER_S) * min(1.0, attackS / ATTACK_RAMP_S)
            levelDb += max(err * attackCoef, -rate * dt)
        } else {
            attackS = 0.0
            if (holdS > 0) holdS -= dt else levelDb += min(err * releaseCoef, RELEASE_DB_PER_S * dt)
        }
        setTarget()
    }

    private fun setTarget() {
        val target = 10.0.pow(levelDb / 20.0)
        gainStep = (target - gain) / blockFrames
        published = levelDb.toFloat()
    }

    companion object {
        const val TARGET_LUFS = -14.0
        const val MAX_GAIN_DB = 12.0
        const val ABS_GATE_LUFS = -50.0
        const val REL_GATE_LU = 20.0
        const val REL_GATE_ESCAPE_S = 1.5
        const val REPRIME_AFTER_S = 2.5
        const val MOMENTARY_TAU_S = 0.4
        const val MOMENTARY_FALL_TAU_S = 0.2
        const val SLOW_RISE_TAU_S = 3.0
        const val SLOW_FALL_DB_PER_S = 1.5
        const val ATTACK_TAU_S = 0.5
        const val ATTACK_MIN_DB_PER_S = 3.0
        const val ATTACK_MAX_DB_PER_S = 10.0
        const val ATTACK_RAMP_S = 1.0
        const val RELEASE_TAU_S = 0.6
        const val RELEASE_DB_PER_S = 4.0
        const val HOLD_S = 0.3
        const val HOLD_TRIGGER_DB = 0.5
        const val BLOCK_S = 128.0 / 48_000.0
        const val HPF_HZ = 60.0
        const val HPF_Q = 0.5
        const val SHELF_HZ = 1500.0
        const val SHELF_DB = 4.0
        /** BS.1770 calibration: a 0 dBFS 1 kHz sine in both channels reads about 0 LUFS. */
        private const val LUFS_OFFSET = -0.691

        private fun highPass(out: DoubleArray, rate: Double, f: Double, q: Double) {
            val w0 = 2.0 * PI * f / rate
            val alpha = sin(w0) / (2.0 * q)
            val c = cos(w0)
            val a0 = 1 + alpha
            out[0] = (1 + c) / 2 / a0; out[1] = -(1 + c) / a0; out[2] = (1 + c) / 2 / a0
            out[3] = -2 * c / a0; out[4] = (1 - alpha) / a0
        }

        /** RBJ high shelf, shelf slope 1. */
        private fun highShelf(out: DoubleArray, rate: Double, f: Double, gainDb: Double) {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * f / rate
            val alpha = sin(w0) / 2.0 * sqrt(2.0)
            val c = cos(w0)
            val sa = 2.0 * sqrt(a) * alpha
            val a0 = (a + 1) - (a - 1) * c + sa
            out[0] = a * ((a + 1) + (a - 1) * c + sa) / a0
            out[1] = -2 * a * ((a - 1) + (a + 1) * c) / a0
            out[2] = a * ((a + 1) + (a - 1) * c - sa) / a0
            out[3] = 2 * ((a - 1) - (a + 1) * c) / a0
            out[4] = ((a + 1) - (a - 1) * c - sa) / a0
        }
    }
}
