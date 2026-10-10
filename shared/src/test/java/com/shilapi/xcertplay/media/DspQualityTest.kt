package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class DspQualityTest {
    private fun tone(hz: Double, rate: Int, frames: Int, amplitude: Double): ByteArray {
        val data = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * hz * i / rate) * amplitude * 32767).toInt()
            for (ch in 0 until 2) { val o = (i * 2 + ch) * 2; data[o] = v.toByte(); data[o + 1] = (v shr 8).toByte() }
        }
        return data
    }

    private fun left(data: ByteArray, fromFrame: Int): DoubleArray {
        val out = ArrayList<Double>()
        var i = fromFrame * 4
        while (i + 1 < data.size) { out += ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff)) / 32768.0; i += 4 }
        return out.toDoubleArray()
    }

    private fun rms(x: DoubleArray) = sqrt(x.sumOf { it * it } / x.size)
    private fun gainDb(hz: Double, rate: Int, configure: (EqualizerDsp) -> Unit): Double {
        val dsp = EqualizerDsp(rate, 2); configure(dsp)
        val data = tone(hz, rate, rate, 0.2)
        dsp.process(data, 0, data.size)
        return 20 * Math.log10(rms(left(data, rate / 2)) / (0.2 / sqrt(2.0)))
    }

    @Test fun orfanidisBellHitsItsTargetAndKeepsShapeNearNyquist() {
        val rate = 44_100
        val gains = FloatArray(12).also { it[11] = 6f } // 16 kHz
        val atCentre = gainDb(16_000.0, rate) { it.configure(gains, false) }
        assertTrue("16 kHz band should be about +6 dB, got $atCentre", atCentre > 5.3 && atCentre < 6.7)
        // RBJ's bilinear bell returns to 0 dB at Nyquist; the analog-matched one is still well up at 21 kHz.
        val nearNyquist = gainDb(21_000.0, rate) { it.configure(gains, false) }
        assertTrue("analog-matched bell should still be raised at 21 kHz, got $nearNyquist", nearNyquist > 1.8)
        val low = gainDb(1_000.0, 48_000) { it.configure(FloatArray(12).also { g -> g[5] = -8f }, false) }
        assertTrue("1 kHz cut should be about -8 dB, got $low", low > -8.7 && low < -7.3)
    }

    @Test fun adjacentBandsDoNotPileUp() {
        val all = FloatArray(12) { 6f }
        val mid = gainDb(1_000.0, 48_000) { it.configure(all, false) }
        assertTrue("all bands +6 should read about +6 at 1 kHz (not +9), got $mid", mid > 5.0 && mid < 7.0)
        val lowMid = gainDb(250.0, 48_000) { it.configure(all, false) }
        assertTrue("all bands +6 should read about +6 at 250 Hz, got $lowMid", lowMid > 5.0 && lowMid < 7.0)
    }

    @Test fun lookAheadLimiterIsTransparentBelowCeilingAndCatchesPeaks() {
        val rate = 48_000
        val quiet = EqualizerDsp(rate, 2).also { it.configure(FloatArray(12), limiter = true) }
        val q = tone(1_000.0, rate, rate, 0.3)
        quiet.process(q, 0, q.size)
        val level = rms(left(q, rate / 2)) / (0.3 / sqrt(2.0))
        assertTrue("a -10 dBFS tone must pass the limiter untouched, got ratio $level", abs(level - 1.0) < 0.01)

        val hot = EqualizerDsp(rate, 2).also { it.configure(FloatArray(12).also { g -> g[5] = 12f }, limiter = true) }
        val h = tone(1_000.0, rate, rate, 0.9)
        hot.process(h, 0, h.size)
        val peak = left(h, rate / 2).maxOf { abs(it) }
        assertTrue("limited peak $peak should sit at the 0.985 ceiling", peak > 0.95 && peak <= 0.99)
        assertEquals(96, hot.latencyFrames())
    }

    @Test fun limiterWorksAcrossOddBlockSizes() {
        val rate = 48_000
        val dsp = EqualizerDsp(rate, 2).also { it.configure(FloatArray(12).also { g -> g[5] = 12f }, limiter = true) }
        val data = tone(1_000.0, rate, rate, 0.9)
        var offset = 0
        val sizes = intArrayOf(100, 37, 1000, 3, 777, 4096)
        var k = 0
        while (offset < data.size) {
            val frames = sizes[k++ % sizes.size]
            val bytes = minOf(frames * 4, data.size - offset)
            dsp.process(data, offset, bytes)
            offset += bytes
        }
        val peak = left(data, rate / 2).maxOf { abs(it) }
        assertTrue("peak $peak must stay under the ceiling regardless of block size", peak <= 0.99)
        val tail = left(data, rate / 2)
        assertTrue("tone should still be continuous (rms ${rms(tail)})", rms(tail) > 0.5)
    }

    @Test fun coefficientChangesSettleWithoutClicks() {
        val rate = 48_000
        val dsp = EqualizerDsp(rate, 2)
        dsp.configure(FloatArray(12), false)
        val data = tone(1_000.0, rate, rate, 0.2)
        dsp.process(data, 0, rate * 2) // first half flat (0.5 s)
        dsp.configure(FloatArray(12).also { it[5] = 6f }, false)
        dsp.process(data, rate * 2, data.size - rate * 2)
        val settled = left(data, (rate * 0.8).toInt())
        val gain = 20 * Math.log10(rms(settled) / (0.2 / sqrt(2.0)))
        assertTrue("after smoothing the new gain applies, got $gain", gain > 5.3 && gain < 6.7)
        // No sample-to-sample jump larger than a sine at this level could produce.
        val transition = left(data, rate / 2 - 100).take(400)
        var maxStep = 0.0
        for (i in 1 until transition.size) maxStep = maxOf(maxStep, abs(transition[i] - transition[i - 1]))
        assertTrue("transition step $maxStep should be smooth", maxStep < 0.08)
    }

    @Test fun graphicQFollowsBandSpacing() {
        val q = EqualizerDsp.GRAPHIC_Q
        assertTrue("octave-spaced bands near Q 1.41, got ${q[2]}", q[2] > 1.3 && q[2] < 1.5)
        assertTrue("half-octave bands narrower, got ${q[7]}", q[7] > 2.5)
    }
}
