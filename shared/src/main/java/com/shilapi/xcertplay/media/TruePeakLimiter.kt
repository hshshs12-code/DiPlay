package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stereo-linked true-peak look-ahead limiter on interleaved double samples, processed in place.
 * The output lags the input by [latencyFrames]; every stage runs frame by frame, so the result
 * does not depend on how the stream is split into blocks.
 *
 * Signal flow of the gain computer (the audio itself only passes through a delay line and one
 * multiply):
 * 1. True-peak detection. A 4x polyphase interpolator (Kaiser-windowed sinc, [TAPS] taps per
 *    phase, folded so the three phases cost 18 multiplies per channel) estimates the
 *    reconstructed waveform between samples; the largest local maximum of each interval is
 *    refined with a parabola through its 4x neighbours. The estimate errs on the safe side: it
 *    under-reads tones up to 0.35 fs by less than 0.01 dB and broadband transients by under 0.3 %,
 *    and over-reads by at most 0.16 dB. When every sample in the interpolator's window is below
 *    [CEILING] divided by the filter's absolute sum, no interpolated value can reach the ceiling
 *    and the detector is skipped entirely, so quiet programme costs almost nothing.
 * 2. Required gain per frame, `ceiling / peak`, stereo-linked (maximum over channels), with each
 *    inter-sample peak charged to both frames around it.
 * 3. Sliding minimum (monotonic deque, O(1) amortised) over the look-ahead plus [HOLD_MICROS] of
 *    hold. The hold spans half a period down to 25 Hz, so a sustained bass note sees a flat gain
 *    instead of one that recovers and dips again every half cycle.
 * 4. Programme-dependent dual release: a fast envelope (instant attack, [FAST_RELEASE_S]) and a
 *    slow one that only charges while limiting persists ([SLOW_CHARGE_S]) and recovers over
 *    [SLOW_RELEASE_S]. The gain is the lower of the two: isolated transients recover quickly,
 *    dense or sustained limiting recovers slowly and without pumping.
 * 5. Attack/release smoothing by three cascaded moving averages (a cubic B-spline kernel, a close
 *    and O(1) stand-in for a Hann window) whose support fits inside the look-ahead. The gain
 *    envelope has continuous slope and curvature, and because every value inside the kernel is
 *    already at or below the requirement of the frame being output, the smoothed gain still meets
 *    it exactly. The moving averages run in 64-bit fixed point, so they cannot drift.
 * 6. Output = delayed sample x gain, with a hard clip at [CLIP] as a last-resort guard.
 */
internal class TruePeakLimiter(sampleRate: Int, private val channels: Int) {
    /** Output delay in frames; equal to the look-ahead. */
    val latencyFrames: Int = max(MIN_LOOKAHEAD, (sampleRate.toLong() * LOOKAHEAD_MICROS / 1_000_000).toInt())

    // Look-ahead budget: detector delay (HALF frames) + hold-window front (window - 1 frames).
    private val window = latencyFrames + 1 - HALF
    private val box = (window + 2) / 3                       // 3 boxes of `box` frames span 3*box-2 <= window
    private val span = window + (sampleRate.toLong() * HOLD_MICROS / 1_000_000).toInt()

    // ---- detector ----
    private val history = DoubleArray(channels * 2 * TAPS)  // doubled ring per channel: contiguous reads
    private var historyPos = 0
    private var hot = 0
    private val previousSide = DoubleArray(channels)        // |value at k - 1/4| of the previous interval
    private var sideValid = false
    private var previousPeak = 0.0

    // ---- sliding minimum ----
    private val dequeValue = DoubleArray(span + 1)
    private val dequeIndex = IntArray(span + 1)
    private var dequeHead = 0
    private var dequeSize = 0
    private var frameIndex = 0

    // ---- release ----
    private val fastRelease = coefficient(FAST_RELEASE_S, sampleRate)
    private val slowCharge = coefficient(SLOW_CHARGE_S, sampleRate)
    private val slowRelease = coefficient(SLOW_RELEASE_S, sampleRate)
    private var fast = 1.0
    private var slow = 1.0

    // ---- smoothing (fixed point, ONE = unity gain) ----
    private val ring1 = LongArray(box)
    private val ring2 = LongArray(box)
    private val ring3 = LongArray(box)
    private val full1 = box.toLong() * ONE
    private val full2 = full1 * box
    private val full3 = full2 * box
    private val inverseFull = 1.0 / full3
    private var sum1 = 0L
    private var sum2 = 0L
    private var sum3 = 0L
    private var boxPos = 0
    private var unityRun = 0

    // ---- audio delay ----
    private val delay = DoubleArray(latencyFrames * channels)
    private var delayPos = 0

    init { reset() }

    fun reset() {
        history.fill(0.0); historyPos = 0; hot = 0
        previousSide.fill(0.0); sideValid = false; previousPeak = 0.0
        dequeHead = 0; dequeSize = 0; frameIndex = 0
        fast = 1.0; slow = 1.0
        ring1.fill(ONE); ring2.fill(full1); ring3.fill(full2)
        sum1 = full1; sum2 = full2; sum3 = full3
        boxPos = 0; unityRun = 3 * box
        delay.fill(0.0); delayPos = 0
    }

    /** Limits [frames] interleaved frames of [buffer] in place (output delayed by [latencyFrames]). */
    fun process(buffer: DoubleArray, frames: Int) {
        val n = channels
        for (i in 0 until frames) {
            val base = i * n
            // 1. Detector history and the cheap "could anything reach the ceiling?" test.
            var loudest = 0.0
            val pos = historyPos
            for (ch in 0 until n) {
                val x = buffer[base + ch]
                val h = ch * 2 * TAPS + pos
                history[h] = x; history[h + TAPS] = x
                val a = abs(x)
                if (a > loudest) loudest = a
            }
            historyPos = if (pos + 1 == TAPS) 0 else pos + 1
            if (loudest >= HOT_THRESHOLD) hot = TAPS
            var peak = 0.0
            if (hot > 0) {
                hot--
                peak = if (n == 2) intervalPeakStereo() else intervalPeak()
                sideValid = true
            } else {
                sideValid = false
            }

            // 2. Required gain for detection frame k (the frame starting the interval just measured).
            val worst = if (peak > previousPeak) peak else previousPeak
            previousPeak = peak
            val required = if (worst > CEILING) CEILING / worst else 1.0

            // 3. Sliding minimum over [k - window + 1 - hold, k]. Unity is the largest possible
            //    value, so it is never stored: an empty deque means "no reduction in the window".
            val k = frameIndex++
            val cap = dequeValue.size
            if (required < 1.0) {
                while (dequeSize > 0) {
                    var back = dequeHead + dequeSize - 1; if (back >= cap) back -= cap
                    if (dequeValue[back] >= required) dequeSize-- else break
                }
                var slot = dequeHead + dequeSize; if (slot >= cap) slot -= cap
                dequeValue[slot] = required; dequeIndex[slot] = k; dequeSize++
            }
            var held = 1.0
            if (dequeSize > 0) {
                if (k - dequeIndex[dequeHead] >= span) { dequeHead = if (dequeHead + 1 == cap) 0 else dequeHead + 1; dequeSize-- }
                if (dequeSize > 0) held = dequeValue[dequeHead]
            }

            // 4. Dual release.
            if (held < 1.0 || fast < 1.0 || slow < 1.0) {
                if (held < fast) fast = held else { fast += (held - fast) * fastRelease; if (held - fast < SNAP) fast = held }
                if (held < slow) slow += (held - slow) * slowCharge else { slow += (held - slow) * slowRelease; if (held - slow < SNAP) slow = held }
            }
            val envelope = if (fast < slow) fast else slow

            // 5. Smoothing: three cascaded moving averages (skipped once they are settled at unity).
            val gain: Double
            if (envelope >= 1.0 && unityRun >= 3 * box) {
                gain = 1.0
            } else {
                if (envelope >= 1.0) unityRun++ else unityRun = 0
                val v = (envelope * ONE).toLong()
                val p = boxPos
                sum1 += v - ring1[p]; ring1[p] = v
                sum2 += sum1 - ring2[p]; ring2[p] = sum1
                sum3 += sum2 - ring3[p]; ring3[p] = sum2
                boxPos = if (p + 1 == box) 0 else p + 1
                gain = if (sum3 == full3) 1.0 else sum3 * inverseFull
            }

            // 6. Delayed output.
            val d = delayPos * n
            for (ch in 0 until n) {
                var y = delay[d + ch] * gain
                delay[d + ch] = buffer[base + ch]
                if (y > CLIP) y = CLIP else if (y < -CLIP) y = -CLIP
                buffer[base + ch] = y
            }
            delayPos = if (delayPos + 1 == latencyFrames) 0 else delayPos + 1
        }
    }

    /**
     * Largest reconstructed magnitude over all channels in the interval between the two centre
     * samples of the detector window (sample point included, next sample point excluded).
     */
    private fun intervalPeak(): Double {
        var result = 0.0
        val start = historyPos // oldest sample of the window, contiguous thanks to the doubled ring
        for (ch in 0 until channels) {
            val w = ch * 2 * TAPS + start
            var sum = 0.0; var diff = 0.0; var mid = 0.0
            for (t in 0 until HALF) {
                val a = history[w + t]
                val b = history[w + TAPS - 1 - t]
                val s = a + b
                sum += FOLD_SUM[t] * s
                diff += FOLD_DIFF[t] * (a - b)
                mid += HALF_PHASE[t] * s
            }
            val best = channelPeak(
                ch,
                abs(history[w + HALF - 1]),              // x[k]
                abs(0.5 * (sum + diff)),                 // k + 1/4
                abs(mid),                                // k + 1/2
                abs(0.5 * (sum - diff)),                 // k + 3/4
                abs(history[w + HALF]),                  // x[k + 1]
            )
            if (best > result) result = best
        }
        return result
    }

    /** [intervalPeak] unrolled for two channels: one pass over the shared window positions. */
    private fun intervalPeakStereo(): Double {
        val h = history
        val l = historyPos
        val r = 2 * TAPS + historyPos
        var sumL = 0.0; var diffL = 0.0; var midL = 0.0
        var sumR = 0.0; var diffR = 0.0; var midR = 0.0
        for (t in 0 until HALF) {
            val u = FOLD_SUM[t]; val v = FOLD_DIFF[t]; val m = HALF_PHASE[t]
            val aL = h[l + t]; val bL = h[l + TAPS - 1 - t]
            val aR = h[r + t]; val bR = h[r + TAPS - 1 - t]
            val sL = aL + bL; val sR = aR + bR
            sumL += u * sL; diffL += v * (aL - bL); midL += m * sL
            sumR += u * sR; diffR += v * (aR - bR); midR += m * sR
        }
        return max(
            channelPeak(0, abs(h[l + HALF - 1]), abs(0.5 * (sumL + diffL)), abs(midL), abs(0.5 * (sumL - diffL)), abs(h[l + HALF])),
            channelPeak(1, abs(h[r + HALF - 1]), abs(0.5 * (sumR + diffR)), abs(midR), abs(0.5 * (sumR - diffR)), abs(h[r + HALF])),
        )
    }

    /** Largest of the 4x points from sample k up to (not including) sample k + 1, refined. */
    private fun channelPeak(ch: Int, a0: Double, a1: Double, a2: Double, a3: Double, a4: Double): Double {
        var best = a0; var j = 0
        if (a1 > best) { best = a1; j = 1 }
        if (a2 > best) { best = a2; j = 2 }
        if (a3 > best) { best = a3; j = 3 }
        if (best * MAX_REFINEMENT > CEILING) best = refine(best, j, if (sideValid) previousSide[ch] else -1.0, a0, a1, a2, a3, a4)
        previousSide[ch] = a3
        return best
    }

    /**
     * Parabolic refinement of the 4x point [j] (0 = sample k, 1..3 = k + j/4) when it is a local
     * maximum; [side] is the point at k - 1/4 (negative when unknown).
     */
    private fun refine(best: Double, j: Int, side: Double, a0: Double, a1: Double, a2: Double, a3: Double, a4: Double): Double {
        val left = when (j) { 0 -> side; 1 -> a0; 2 -> a1; else -> a2 }
        val right = when (j) { 0 -> a1; 1 -> a2; 2 -> a3; else -> a4 }
        if (left < 0.0 || best < left || best < right) return best
        val curvature = left - 2.0 * best + right
        if (curvature >= 0.0) return best
        val offset = 0.5 * (left - right) / curvature
        return best - 0.25 * (left - right) * offset
    }

    companion object {
        /** Sample and true-peak ceiling: -0.13 dBFS. */
        const val CEILING = 0.985
        /** Hard clip after the gain; never reached by detected content. */
        const val CLIP = 0.999
        const val LOOKAHEAD_MICROS = 2_000
        const val HOLD_MICROS = 20_000
        const val FAST_RELEASE_S = 0.050
        const val SLOW_RELEASE_S = 0.500
        /** How long limiting has to persist before the slow stage takes over most of it. */
        const val SLOW_CHARGE_S = 0.300
        /** Taps per polyphase branch; the detector looks HALF frames ahead. */
        const val TAPS = 12
        private const val HALF = TAPS / 2
        private const val MIN_LOOKAHEAD = 16
        /**
         * Kaiser window wider than the taps (+-8 for 12 taps) keeps the centre taps near the ideal
         * sinc, so broadband transients (e.g. a pair of full-scale samples, reconstructed peak 1.27x)
         * are not under-read; the price is a conservative over-read of at most 0.16 dB on tones.
         */
        private const val KAISER_BETA = 6.0
        private const val WINDOW_HALF_WIDTH = HALF + 2.0
        private const val ONE = 1L shl 32
        private const val SNAP = 1e-12
        /** A parabola through three neighbouring 4x points can raise a peak by at most 1/8. */
        private const val MAX_REFINEMENT = 1.125

        private val PHASES: Array<DoubleArray> = Array(3) { p -> phase((p + 1) / 4.0) }
        private val FOLD_SUM = DoubleArray(HALF) { PHASES[0][it] + PHASES[0][TAPS - 1 - it] }
        private val FOLD_DIFF = DoubleArray(HALF) { PHASES[0][it] - PHASES[0][TAPS - 1 - it] }
        private val HALF_PHASE = DoubleArray(HALF) { PHASES[1][it] }
        /** Below this sample level no interpolated value can reach the ceiling. */
        private val HOT_THRESHOLD = CEILING / PHASES.maxOf { c -> c.sumOf { abs(it) } }

        /** Fractional-delay branch at [fraction] between taps HALF-1 and HALF, unity DC gain. */
        private fun phase(fraction: Double): DoubleArray {
            val c = DoubleArray(TAPS) { i ->
                val d = i - (HALF - 1) - fraction
                val sinc = if (d == 0.0) 1.0 else sin(PI * d) / (PI * d)
                val r = d / WINDOW_HALF_WIDTH
                sinc * besselI0(KAISER_BETA * sqrt(max(0.0, 1.0 - r * r))) / besselI0(KAISER_BETA)
            }
            val sum = c.sum()
            for (i in c.indices) c[i] /= sum
            return c
        }

        private fun besselI0(x: Double): Double {
            var sum = 1.0; var term = 1.0; var k = 1
            while (term > 1e-17 * sum) { val h = x / (2 * k); term *= h * h; sum += term; k++ }
            return sum
        }

        private fun coefficient(seconds: Double, rate: Int): Double = 1.0 - exp(-1.0 / (seconds * rate))
    }
}
