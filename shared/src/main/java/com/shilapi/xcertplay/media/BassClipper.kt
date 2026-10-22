package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Soft clipper on the band below [CROSSOVER_HZ], so bass bounds itself before the full-band
 * limiter sees it. A wideband limiter has to pull the whole mix down whenever an 808 crosses the
 * ceiling, and its hold and release keep it down for the length of the note: the vocals and
 * everything else duck under the bass. Clipping the low band instead keeps the bass at its
 * ceiling, adds a little harmonic weight that small door speakers reproduce better than the
 * fundamental anyway, and leaves the limiter with only real mid and treble peaks. Car bass
 * amplifiers have done exactly this for decades.
 *
 * Split: the same Linkwitz-Riley topology as [BassEnhancer] (LP4 and the matching all-pass, so
 * low + high reproduces the input through an all-pass). Clip: linear up to 60 % of the ceiling,
 * then a tanh knee that never reaches the ceiling. On/off and ceiling changes glide per sample;
 * idle costs nothing. Audio thread only.
 */
class BassClipper(sampleRate: Int, private val channels: Int) {
    private val lp = DoubleArray(5)
    private val ap = DoubleArray(5)
    private val state = DoubleArray(channels * 12)
    private var ceilingTarget = 0.0
    private var ceiling = 0.0
    private var wetTarget = 0.0
    private var wet = 0.0
    private var flushCounter = 0
    private val smooth = 1.0 - exp(-1.0 / (SMOOTH_SECONDS * sampleRate))

    /** True while the stage changes the signal (including while it fades out). */
    var active = false
        private set

    init {
        val fc = CROSSOVER_HZ.coerceAtMost(sampleRate / 8.0)
        lowPass(lp, fc, sampleRate)
        allPass(ap, fc, sampleRate)
    }

    /** [clipDb] 0 = off; otherwise the low band is held under −[clipDb] dBFS. */
    fun configure(clipDb: Int) {
        val db = clipDb.coerceIn(0, MAX_CLIP_DB)
        if (db == 0) { wetTarget = 0.0; return }
        ceilingTarget = 10.0.pow(-db / 20.0)
        wetTarget = 1.0
        if (!active) { state.fill(0.0); ceiling = ceilingTarget; wet = 0.0; active = true }
    }

    /** Jumps to the configured values (first configuration, no fade). */
    fun snap() {
        ceiling = ceilingTarget; wet = wetTarget
        if (wetTarget == 0.0) active = false
    }

    /** Current clip ceiling (linear), for tests and diagnostics. */
    fun ceiling(): Double = ceiling

    fun process(frame: DoubleArray) {
        if (!active) return
        ceiling += (ceilingTarget - ceiling) * smooth
        wet += (wetTarget - wet) * smooth
        val c = ceiling
        val knee = KNEE * c
        val range = c - knee
        for (ch in 0 until channels) {
            val x = frame[ch]
            val s = ch * 12
            val low = biquad(biquad(x, lp, state, s), lp, state, s + 4)
            val high = biquad(x, ap, state, s + 8) - low
            val a = abs(low)
            val clipped = if (a <= knee) low else {
                val y = knee + range * tanh((a - knee) / range)
                if (low < 0) -y else y
            }
            frame[ch] = x + wet * (clipped + high - x)
        }
        if (++flushCounter >= FLUSH_FRAMES) {
            flushCounter = 0
            for (i in state.indices) if (abs(state[i]) < DENORMAL_FLOOR) state[i] = 0.0
            if (wetTarget == 0.0 && wet < IDLE) { wet = 0.0; active = false }
        }
    }

    private fun biquad(x: Double, c: DoubleArray, s: DoubleArray, i: Int): Double {
        val y = c[0] * x + c[1] * s[i] + c[2] * s[i + 1] - c[3] * s[i + 2] - c[4] * s[i + 3]
        s[i + 1] = s[i]; s[i] = x
        s[i + 3] = s[i + 2]; s[i + 2] = y
        return y
    }

    companion object {
        const val CROSSOVER_HZ = 120.0
        const val MAX_CLIP_DB = 12
        /** Linear region of the clipper as a fraction of the ceiling. */
        const val KNEE = 0.6
        private const val SMOOTH_SECONDS = 0.005
        private const val FLUSH_FRAMES = 1024
        private const val DENORMAL_FLOOR = 1e-24
        private const val IDLE = 1e-6
        private const val BUTTERWORTH_Q = 0.7071067811865476

        private fun lowPass(out: DoubleArray, f: Double, rate: Int) {
            val w0 = 2.0 * PI * f / rate; val alpha = sin(w0) / (2.0 * BUTTERWORTH_Q); val cw = cos(w0); val a0 = 1 + alpha
            out[0] = (1 - cw) / 2 / a0; out[1] = (1 - cw) / a0; out[2] = (1 - cw) / 2 / a0; out[3] = -2 * cw / a0; out[4] = (1 - alpha) / a0
        }

        private fun allPass(out: DoubleArray, f: Double, rate: Int) {
            val w0 = 2.0 * PI * f / rate; val alpha = sin(w0) / (2.0 * BUTTERWORTH_Q); val cw = cos(w0); val a0 = 1 + alpha
            out[0] = (1 - alpha) / a0; out[1] = -2 * cw / a0; out[2] = 1.0; out[3] = -2 * cw / a0; out[4] = (1 - alpha) / a0
        }
    }
}
