package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Volume-linked equal-loudness compensation, the "loudness" of Helix / Audison car DSPs.
 *
 * Below the level the music was mixed for, the ear loses bass (and some extreme treble) faster
 * than midrange: the ISO 226:2003 equal-loudness contours spread apart as the level drops. This
 * stage lifts a low shelf (100 Hz) and a high shelf (8 kHz) by the difference between the contour
 * at the current listening level and the contour at the reference level, so turning the volume
 * down keeps the tonal balance instead of thinning out.
 *
 * The listening level comes from outside, as [listeningLevelDb] (0 dB = the user's reference
 * volume, negative when quieter). It is only meaningful when the volume knob is Android's software
 * media volume; the tracker in `common` leaves it at 0 dB otherwise, which makes this stage a
 * bit-exact pass-through.
 *
 * Gains come from [LOW_TABLE_DB] / [HIGH_TABLE_DB]: ISO 226:2003 contour differences against an
 * 80-phon reference, at 100 Hz for the low shelf and the mean of 10 kHz and 12.5 kHz for the high
 * shelf, on a 10 dB grid and linearly interpolated (the contours are nearly straight in level, so
 * the interpolation error is under 0.1 dB). Roughly +3 dB bass and +1.1 dB treble per 10 dB of
 * attenuation, scaled by the amount and clamped to [MAX_LOW_DB] / [MAX_HIGH_DB].
 *
 * Gain changes ramp linearly in dB over [RAMP_SECONDS], with the shelves redesigned every block
 * (≤ 64 frames, so each redesign moves by ≤ 0.1 dB), so a volume step never clicks. Cost: two
 * double-precision biquads per channel while any gain is non-zero; nothing at the reference level.
 */
class LoudnessCompensator(private val sampleRate: Int, private val channels: Int) {
    @Volatile var enabled = false
        private set
    @Volatile private var amount = 0.0

    // Current (ramping) and target shelf gains in dB, and the per-frame ramp step.
    private var lowDb = 0.0
    private var highDb = 0.0
    private var lowTarget = 0.0
    private var highTarget = 0.0
    private var lowStep = 0.0
    private var highStep = 0.0
    private var tail = 0
    private var running = false

    private val lowC = DoubleArray(5)
    private val highC = DoubleArray(5)
    private val state = DoubleArray(channels * 8)
    private val rampFrames = max(1.0, RAMP_SECONDS * sampleRate)
    private val tailFrames = (TAIL_SECONDS * sampleRate).toInt()
    private val lowCos: Double
    private val lowAlpha: Double
    private val highCos: Double
    private val highAlpha: Double

    init {
        val wl = 2.0 * PI * LOW_HZ / sampleRate
        lowCos = cos(wl); lowAlpha = sin(wl) / (2.0 * SHELF_Q)
        val wh = 2.0 * PI * min(HIGH_HZ, sampleRate * 0.45) / sampleRate
        highCos = cos(wh); highAlpha = sin(wh) / (2.0 * SHELF_Q)
    }

    /** [amountPercent] 0..100 scales the ISO 226 gains; off or 0 fades the shelves out. */
    fun configure(enabled: Boolean, amountPercent: Int) {
        amount = if (enabled) amountPercent.coerceIn(0, 100) / 100.0 else 0.0
        this.enabled = enabled
    }

    /**
     * Advances the gain ramp by one block of [frames] (call before processing the block, with
     * blocks of at most [MAX_BLOCK_FRAMES]); returns true when [processSample] must run for it.
     */
    fun prepare(frames: Int): Boolean {
        val level = listeningLevelDb
        val newLow = lowShelfDb(level, amount)
        val newHigh = highShelfDb(level, amount)
        if (newLow != lowTarget || newHigh != highTarget) {
            lowTarget = newLow; highTarget = newHigh
            // Both shelves arrive together, RAMP_SECONDS after the change, whatever its size.
            lowStep = abs(lowTarget - lowDb) / rampFrames
            highStep = abs(highTarget - highDb) / rampFrames
        }
        val moving = lowDb != lowTarget || highDb != highTarget
        if (moving) {
            lowDb = approach(lowDb, lowTarget, lowStep * frames)
            highDb = approach(highDb, highTarget, highStep * frames)
            design()
            running = true
            tail = tailFrames
            return true
        }
        if (lowDb != 0.0 || highDb != 0.0) { running = true; return true }
        if (!running) return false
        // Back at 0 dB: let the filters' memory ring out, then stop and forget it.
        tail -= frames
        if (tail > 0) return true
        running = false
        state.fill(0.0)
        return false
    }

    /** Both shelves on one sample of channel [ch]; only valid after [prepare] returned true. */
    fun processSample(x: Double, ch: Int): Double {
        val s = state; val i = ch * 8
        val lc = lowC; val hc = highC
        val y1 = lc[0] * x + lc[1] * s[i] + lc[2] * s[i + 1] - lc[3] * s[i + 2] - lc[4] * s[i + 3]
        s[i + 1] = s[i]; s[i] = x; s[i + 3] = s[i + 2]; s[i + 2] = y1
        val y2 = hc[0] * y1 + hc[1] * s[i + 4] + hc[2] * s[i + 5] - hc[3] * s[i + 6] - hc[4] * s[i + 7]
        s[i + 5] = s[i + 4]; s[i + 4] = y1; s[i + 7] = s[i + 6]; s[i + 6] = y2
        return y2
    }

    /** Standalone use: processes interleaved samples in place; returns false (untouched) when idle. */
    fun process(buffer: DoubleArray, frames: Int): Boolean {
        var touched = false
        var done = 0
        while (done < frames) {
            val chunk = min(MAX_BLOCK_FRAMES, frames - done)
            if (prepare(chunk)) {
                touched = true
                var k = done * channels
                for (n in 0 until chunk) for (ch in 0 until channels) { buffer[k] = processSample(buffer[k], ch); k++ }
            }
            done += chunk
        }
        return touched
    }

    /** True while the shelves still run (boosting, ramping, or ringing out after switching off). */
    val busy: Boolean get() = running || lowDb != 0.0 || highDb != 0.0

    /** Current (ramped) shelf gains, for the UI and tests. */
    fun lowShelfDb(): Double = lowDb
    fun highShelfDb(): Double = highDb

    private fun approach(value: Double, target: Double, step: Double): Double =
        if (abs(target - value) <= step) target else if (target > value) value + step else value - step

    private fun design() {
        shelf(lowC, lowDb, lowCos, lowAlpha, high = false)
        shelf(highC, highDb, highCos, highAlpha, high = true)
    }

    /** RBJ shelf (Q [SHELF_Q]); at 0 dB numerator equals denominator, so it is exactly transparent. */
    private fun shelf(out: DoubleArray, gainDb: Double, c: Double, alpha: Double, high: Boolean) {
        val a = 10.0.pow(gainDb / 40.0)
        val sa = 2.0 * sqrt(a) * alpha
        val sign = if (high) -1.0 else 1.0
        val b0 = a * ((a + 1) - sign * (a - 1) * c + sa)
        val b1 = sign * 2 * a * ((a - 1) - sign * (a + 1) * c)
        val b2 = a * ((a + 1) - sign * (a - 1) * c - sa)
        val a0 = (a + 1) + sign * (a - 1) * c + sa
        val a1 = -sign * 2 * ((a - 1) + sign * (a + 1) * c)
        val a2 = (a + 1) + sign * (a - 1) * c - sa
        out[0] = b0 / a0; out[1] = b1 / a0; out[2] = b2 / a0; out[3] = a1 / a0; out[4] = a2 / a0
    }

    companion object {
        /**
         * Listening level relative to the user's reference volume in dB: 0 = reference (no
         * compensation), negative when quieter, clamped to [MIN_LEVEL_DB]. Written by the volume
         * tracker in `common`, read by every running chain once per block.
         */
        @Volatile var listeningLevelDb: Double = 0.0

        const val LOW_HZ = 100.0
        const val HIGH_HZ = 8000.0
        const val SHELF_Q = 0.8
        const val MAX_LOW_DB = 15.0
        const val MAX_HIGH_DB = 6.0
        const val MIN_LEVEL_DB = -60.0
        const val RAMP_SECONDS = 0.2
        const val MAX_BLOCK_FRAMES = 64
        private const val TAIL_SECONDS = 0.05
        private const val GRID_DB = 10.0

        /** ISO 226:2003 boost vs. an 80-phon reference at 0, −10 … −60 dB: 100 Hz. */
        val LOW_TABLE_DB = doubleArrayOf(0.0, 3.1, 6.2, 9.1, 11.9, 14.3, 15.9)
        /** Same, mean of 10 kHz and 12.5 kHz (the region the 8 kHz shelf lifts). */
        val HIGH_TABLE_DB = doubleArrayOf(0.0, 1.2, 2.3, 3.4, 4.3, 5.0, 5.2)

        fun lowShelfDb(levelDb: Double, amount: Double): Double = lookup(LOW_TABLE_DB, levelDb, amount, MAX_LOW_DB)
        fun highShelfDb(levelDb: Double, amount: Double): Double = lookup(HIGH_TABLE_DB, levelDb, amount, MAX_HIGH_DB)

        private fun lookup(table: DoubleArray, levelDb: Double, amount: Double, cap: Double): Double {
            if (amount <= 0.0 || levelDb.isNaN() || levelDb >= 0.0) return 0.0
            val position = (-max(levelDb, MIN_LEVEL_DB)) / GRID_DB
            val index = min(position.toInt(), table.size - 2)
            val frac = position - index
            val boost = table[index] + (table[index + 1] - table[index]) * frac
            return (boost * amount).coerceIn(0.0, cap)
        }
    }
}
