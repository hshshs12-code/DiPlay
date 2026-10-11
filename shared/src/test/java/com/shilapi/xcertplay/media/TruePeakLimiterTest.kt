package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class TruePeakLimiterTest {
    private val rate = 48_000
    private val ceiling = TruePeakLimiter.CEILING

    /** Interleaved stereo from a per-frame generator (both channels equal unless [right] is given). */
    private fun stereo(frames: Int, right: ((Int) -> Double)? = null, left: (Int) -> Double): DoubleArray {
        val out = DoubleArray(frames * 2)
        for (i in 0 until frames) { out[2 * i] = left(i); out[2 * i + 1] = right?.invoke(i) ?: left(i) }
        return out
    }

    private fun run(input: DoubleArray, blocks: IntArray = intArrayOf(4096), limiter: TruePeakLimiter = TruePeakLimiter(rate, 2)): DoubleArray {
        val out = input.copyOf()
        val frames = input.size / 2
        var at = 0; var k = 0
        while (at < frames) {
            val n = minOf(blocks[k++ % blocks.size], frames - at)
            val chunk = out.copyOfRange(at * 2, (at + n) * 2)
            limiter.process(chunk, n)
            System.arraycopy(chunk, 0, out, at * 2, n * 2)
            at += n
        }
        return out
    }

    private fun channel(x: DoubleArray, ch: Int, from: Int, to: Int = x.size / 2) = DoubleArray(to - from) { x[2 * (from + it) + ch] }
    private fun samplePeak(x: DoubleArray) = x.maxOf { abs(it) }

    /** Reference true peak: 16x oversampling with a long (+-48 taps) Kaiser-windowed sinc. */
    private val referenceKernel = Array(16) { p ->
        DoubleArray(96) { i ->
            val d = (i - 47) - p / 16.0
            val sinc = if (d == 0.0) 1.0 else sin(PI * d) / (PI * d)
            val r = d / 48
            if (abs(r) >= 1) 0.0 else sinc * bessel(9.0 * sqrt(1 - r * r)) / bessel(9.0)
        }
    }

    private fun truePeak(x: DoubleArray, guard: Int = 64): Double {
        var peak = 0.0
        for (n in guard until x.size - guard) for (kernel in referenceKernel) {
            var acc = 0.0
            for (i in 0 until 96) acc += x[n - 47 + i] * kernel[i]
            peak = max(peak, abs(acc))
        }
        return peak
    }

    private fun bessel(x: Double): Double { var s = 1.0; var t = 1.0; var k = 1; while (t > 1e-17 * s) { val h = x / (2 * k); t *= h * h; s += t; k++ }; return s }

    /** THD (harmonics 2..60 relative to the fundamental) over a whole number of periods, via Goertzel. */
    private fun thd(x: DoubleArray, hz: Double): Double {
        fun amplitude(f: Double): Double {
            val w = 2 * PI * f / rate; val c = 2 * cos(w)
            var s1 = 0.0; var s2 = 0.0
            for (v in x) { val s0 = v + c * s1 - s2; s2 = s1; s1 = s0 }
            return sqrt(s1 * s1 + s2 * s2 - c * s1 * s2) * 2 / x.size
        }
        val fundamental = amplitude(hz)
        var harmonics = 0.0
        for (k in 2..60) { val a = amplitude(hz * k); harmonics += a * a }
        return sqrt(harmonics) / fundamental
    }

    @Test fun latencyEqualsLookAheadAndIsExact() {
        assertEquals(96, TruePeakLimiter(48_000, 2).latencyFrames)  // 2 ms
        assertEquals(88, TruePeakLimiter(44_100, 2).latencyFrames)
        val limiter = TruePeakLimiter(rate, 2)
        val input = stereo(1000) { if (it == 10) 0.5 else 0.0 }
        val out = run(input, limiter = limiter)
        val left = channel(out, 0, 0)
        assertEquals(0.5, left[10 + limiter.latencyFrames], 0.0)
        assertEquals(0.5, left.sumOf { abs(it) }, 0.0)
    }

    @Test fun transparentBelowCeilingBitExact() {
        val amp = 10.0.pow(-10.0 / 20) * sqrt(2.0) // -10 dBFS RMS, peak 0.447
        // Right channel: a hot but legal 100 Hz tone keeps the true-peak detector busy.
        val input = stereo(rate, { 0.9 * sin(2 * PI * 100 * it / rate) }) { amp * sin(2 * PI * 1000 * it / rate) }
        val limiter = TruePeakLimiter(rate, 2)
        val out = run(input, intArrayOf(3, 100, 517), limiter)
        val l = limiter.latencyFrames
        for (i in 0 until rate - l) {
            assertEquals(input[2 * i], out[2 * (i + l)], 0.0)
            assertEquals(input[2 * i + 1], out[2 * (i + l) + 1], 0.0)
        }
    }

    @Test fun ceilingHoldsOnTwelveDecibelOverloadAcrossOddBlockSizes() {
        val gain = 10.0.pow(12.0 / 20) * ceiling
        // Music-like: bass + mids + HF with bursts, uncorrelated channels.
        val input = stereo(rate * 2, { i ->
            gain * (0.5 * sin(2 * PI * 55 * i / rate + 1) + 0.3 * sin(2 * PI * 3_300 * i / rate) + 0.2 * sin(2 * PI * 11_025 * i / rate)) *
                (if ((i / 2_400) % 3 == 0) 1.0 else 0.3)
        }) { i ->
            gain * (0.6 * sin(2 * PI * 82 * i / rate) + 0.25 * sin(2 * PI * 1_234 * i / rate) + 0.15 * sin(2 * PI * 14_000 * i / rate + 2)) *
                (if ((i / 1_700) % 2 == 0) 1.0 else 0.25)
        }
        val whole = run(input)
        val odd = run(input, intArrayOf(100, 37, 1000, 3, 777, 4096, 1))
        assertArrayEquals("output must not depend on block size", whole, odd, 0.0)
        val peak = samplePeak(whole)
        assertTrue("sample peak $peak must not exceed the ceiling", peak <= ceiling + 1e-12)
        assertTrue("limiting should sit close to the ceiling, got $peak", peak > 0.95)
        val tp = maxOf(truePeak(channel(whole, 0, 4_800, 40_000)), truePeak(channel(whole, 1, 4_800, 40_000)))
        assertTrue("true peak $tp should stay below 0.99", tp < 0.99)
    }

    @Test fun truePeakOfQuarterRateSineStaysBelowCeiling() {
        // fs/4 at 45 degrees: samples are +0.98, +0.98, -0.98, -0.98 ... and the waveform peaks at
        // 0.98 * sqrt(2) = 1.386 between them. A sample-peak limiter would not touch it.
        val input = stereo(rate / 2) { 0.98 * sqrt(2.0) * sin(PI * it / 2 + PI / 4) }
        assertTrue(abs(samplePeak(input) - 0.98) < 1e-9)
        assertTrue(truePeak(channel(input, 0, 1_000, 3_000)) > 1.38)
        val out = run(input, intArrayOf(3, 64, 1001))
        val steady = channel(out, 0, 4_800, 12_000)
        val tp = truePeak(steady)
        println("fs/4 sine: input TP 1.386, output TP %.4f, sample peak %.4f".format(tp, samplePeak(steady)))
        assertTrue("output true peak $tp must stay below 0.99", tp < 0.99)
        assertTrue("and should not be over-limited by more than ~0.2 dB ($tp)", tp > 0.96)
        assertTrue(samplePeak(out) <= ceiling + 1e-12)
    }

    @Test fun truePeakOfImpulsePairsAndHighTonesStaysBelowCeiling() {
        // Pairs of equal full-height samples (reconstructed peak 1.27x the samples), alternating sign.
        val pairs = stereo(rate / 2) { i -> val p = i % 97; if (p == 0 || p == 1) (if ((i / 97) % 2 == 0) 0.98 else -0.98) else 0.0 }
        val out = run(pairs)
        val tpIn = truePeak(channel(pairs, 0, 2_000, 8_000))
        val tpOut = truePeak(channel(out, 0, 2_000, 8_000))
        println("impulse pairs: input TP %.4f, output TP %.4f".format(tpIn, tpOut))
        assertTrue(tpIn > 1.2)
        assertTrue("impulse-pair true peak $tpOut must stay below 0.99", tpOut < 0.99)
        assertTrue(samplePeak(out) <= ceiling + 1e-12)

        // +6 dB over the ceiling at high frequencies, phases chosen off the sample grid.
        var worst = 0.0
        for ((k, hz) in doubleArrayOf(5_000.0, 9_700.0, 12_100.0, 15_300.0).withIndex()) {
            val input = stereo(rate / 4) { 2 * ceiling * sin(2 * PI * hz * it / rate + 0.37 * (k + 1)) }
            val tp = truePeak(channel(run(input), 0, 4_000, 8_000))
            worst = max(worst, tp)
        }
        println("HF tones +6 dB: worst output TP %.4f".format(worst))
        assertTrue("HF tone true peak $worst must stay below 0.99", worst < 0.99)
    }

    @Test fun sustainedBassIsNotModulated() {
        // 40 Hz, 6 dB over the ceiling: the gain must stay flat across the half cycles.
        val input = stereo(rate * 3) { 2 * ceiling * sin(2 * PI * 40 * it / rate) }
        val out = run(input, intArrayOf(3, 480, 1023))
        val steady = channel(out, 0, rate * 2, rate * 3) // 1 s = 40 whole periods
        val distortion = thd(steady, 40.0)
        println("40 Hz +6 dB: THD %.6f %%, peak %.4f".format(distortion * 100, samplePeak(steady)))
        assertTrue("THD ${distortion * 100} % must stay under 1 %", distortion < 0.01)
        assertTrue(samplePeak(steady) > 0.98 && samplePeak(steady) <= ceiling + 1e-12)
    }

    @Test fun gainEnvelopeHasNoCorners() {
        // DC with a 10 ms burst 6 dB over the ceiling: gain = output / delayed input.
        val limiter = TruePeakLimiter(rate, 2)
        val input = stereo(rate / 2) { if (it in 4_800 until 5_280) 2 * ceiling else 0.5 }
        val out = run(input, limiter = limiter)
        val l = limiter.latencyFrames
        val gain = DoubleArray(rate / 2 - l) { out[2 * (it + l)] / input[2 * it] }
        val depth = 1 - gain.min()
        var curvature = 0.0
        for (i in 1 until gain.size - 1) curvature = max(curvature, abs(gain[i + 1] - 2 * gain[i] + gain[i - 1]))
        // A linear ramp over the look-ahead would have a corner of depth / look-ahead in one step.
        assertTrue("gain should dip about 6 dB, got depth $depth", depth > 0.45)
        assertTrue("gain curvature $curvature should be far below a linear ramp's corner ${depth / l}", curvature < depth / l / 5)
    }

    @Test fun releaseIsProgrammeDependent() {
        fun gainAfter(burstFrames: Int, afterMs: Int): Double {
            val limiter = TruePeakLimiter(rate, 2)
            val end = 4_800 + burstFrames
            val input = stereo(end + rate) { if (it in 4_800 until end) 2 * ceiling else 0.25 }
            val out = run(input, limiter = limiter)
            val at = end + afterMs * rate / 1000
            return out[2 * (at + limiter.latencyFrames)] / 0.25
        }
        val transient = gainAfter(rate / 200, 200)  // 5 ms hit
        val sustained = gainAfter(rate * 2, 200)    // 2 s of limiting
        println("gain 200 ms after: transient %.2f dB, sustained %.2f dB".format(20 * log10(transient), 20 * log10(sustained)))
        assertTrue("a short hit should have recovered within 200 ms, got $transient", transient > 0.95)
        assertTrue("sustained limiting should release slowly, got $sustained", sustained < 0.8)
    }

    private fun Double.pow(e: Double) = Math.pow(this, e)
}
