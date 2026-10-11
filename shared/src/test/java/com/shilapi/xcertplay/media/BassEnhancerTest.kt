package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class BassEnhancerTest {
    private val rate = 48_000

    /** Stereo 16-bit tone; [fadeMs] ramps the start in with a raised cosine. */
    private fun tone(hz: Double, frames: Int, amplitude: Double, fadeMs: Double = 0.0): ByteArray {
        val data = ByteArray(frames * 4)
        val fade = (fadeMs * rate / 1000).toInt()
        for (i in 0 until frames) {
            val ramp = if (i < fade) 0.5 - 0.5 * cos(PI * i / fade) else 1.0
            val v = Math.round(sin(2 * PI * hz * i / rate) * amplitude * ramp * 32767).toInt().coerceIn(-32768, 32767)
            for (ch in 0 until 2) { val o = (i * 2 + ch) * 2; data[o] = v.toByte(); data[o + 1] = (v shr 8).toByte() }
        }
        return data
    }

    private fun left(data: ByteArray, fromFrame: Int, frames: Int = Int.MAX_VALUE): DoubleArray {
        val end = min(data.size / 4, fromFrame.toLong().plus(frames).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        return DoubleArray(end - fromFrame) { k ->
            val o = (fromFrame + k) * 4
            ((data[o + 1].toInt() shl 8) or (data[o].toInt() and 0xff)) / 32768.0
        }
    }

    /** Amplitude of one frequency (a full-scale sine reads 1.0); use whole-cycle windows. */
    private fun magnitude(x: DoubleArray, hz: Double): Double {
        var re = 0.0; var im = 0.0
        for (i in x.indices) { val w = 2 * PI * hz * i / rate; re += x[i] * cos(w); im -= x[i] * sin(w) }
        return 2 * sqrt(re * re + im * im) / x.size
    }

    private fun db(x: Double) = 20 * log10(x)

    private fun enhancer(amount: Int, hz: Int, limiter: Boolean = false) = EqualizerDsp(rate, 2).also {
        it.configure(AudioEffectSettings(enabled = true, dspMode = true, bassEnhance = amount, bassEnhanceHz = hz, limiter = limiter), softClip = false)
    }

    /** Runs [data] through a fresh chain and returns 1 s of the left channel after 1 s of settling. */
    private fun steady(amount: Int, hz: Int, data: ByteArray): DoubleArray {
        enhancer(amount, hz).process(data, 0, data.size)
        return left(data, rate, rate)
    }

    @Test fun fortyFiveHertzToneGetsTheDesignedHarmonicSeries() {
        val reference = BassEnhancer(rate, 2).also { it.configure(100, 50); it.snap() }
        val out = steady(100, 50, tone(45.0, rate * 2, 0.2))
        val h = DoubleArray(7) { magnitude(out, 45.0 * it) }
        println("45 Hz @ -14 dBFS, crossover 50 Hz, amount 100: fundamental %.4f, H2 %.1f dB, H3 %.1f dB, H4 %.1f dB, H5 %.1f dB, H6 %.1f dB (re fundamental)"
            .format(h[1], db(h[2] / h[1]), db(h[3] / h[1]), db(h[4] / h[1]), db(h[5] / h[1]), db(h[6] / h[1])))
        // Designed series: weight × band-pass gain at that harmonic.
        val weights = doubleArrayOf(0.0, 0.0, BassEnhancer.W2, BassEnhancer.W3, BassEnhancer.W4)
        for (n in 3..4) {
            val expected = db(weights[n] * reference.harmonicBandGain(45.0 * n) / (weights[2] * reference.harmonicBandGain(90.0)))
            val actual = db(h[n] / h[2])
            assertEquals("H$n/H2 ratio", expected, actual, 1.0)
        }
        assertTrue("2nd harmonic should be strong, got ${db(h[2] / h[1])} dB", h[2] / h[1] > 0.25)
        // No series beyond the 4th (only envelope-ripple residue).
        assertTrue("5th should be > 30 dB below the 2nd, got ${db(h[5] / h[2])}", h[5] / h[2] < 0.03)
        assertTrue("6th should be > 30 dB below the 2nd, got ${db(h[6] / h[2])}", h[6] / h[2] < 0.03)
        // Fundamental is only gently trimmed (−6 dB on the sub band at full amount).
        assertTrue("fundamental ${h[1]}", h[1] > 0.2 * 0.45 && h[1] < 0.2)

        // Report only: the Rage preset's settings (crossover 90 Hz, amount 70) on a 45 Hz 808 at −14 dBFS.
        val rage = steady(70, 90, tone(45.0, rate * 2, 0.2))
        val r = DoubleArray(5) { magnitude(rage, 45.0 * it) }
        println("Rage settings, 45 Hz @ -14 dBFS: fundamental %.1f dB re input, H2 %.1f, H3 %.1f, H4 %.1f dB re input"
            .format(db(r[1] / 0.2), db(r[2] / 0.2), db(r[3] / 0.2), db(r[4] / 0.2)))
    }

    @Test fun oneKilohertzToneGetsNoHarmonicsAndPassesUnchanged() {
        val data = tone(1000.0, rate * 2, 0.5)
        val out = steady(100, 100, data)
        val fundamental = magnitude(out, 1000.0)
        assertEquals("1 kHz level", 0.5, fundamental, 0.5 * 0.005)
        var worst = 0.0
        for (hz in listOf(150.0, 200.0, 300.0, 400.0, 500.0, 600.0, 2000.0, 3000.0, 4000.0)) worst = maxOf(worst, magnitude(out, hz))
        println("1 kHz @ -6 dBFS: largest other component %.1f dB re the tone".format(db(worst / fundamental)))
        assertTrue("no harmonics or band products for a 1 kHz tone, got ${db(worst / fundamental)} dB", worst / fundamental < 1e-4)
    }

    @Test fun harmonicsTrackTheInputLevel() {
        fun harmonics(amplitude: Double): Pair<Double, Double> {
            val out = steady(80, 60, tone(45.0, rate * 2, amplitude))
            val series = sqrt((2..4).sumOf { val m = magnitude(out, 45.0 * it); m * m })
            return series to magnitude(out, 45.0)
        }
        val (quietH, quietF) = harmonics(0.02)  // −34 dBFS
        val (midH, midF) = harmonics(0.2)       // −14 dBFS
        val (loudH, loudF) = harmonics(0.95)    // −0.4 dBFS
        val quietRatio = db(quietH / quietF); val midRatio = db(midH / midF); val loudRatio = db(loudH / loudF)
        println("harmonics re fundamental: quiet %.1f dB, mid %.1f dB, loud %.1f dB; harmonic level quiet %.1f, mid %.1f, loud %.1f dBFS"
            .format(quietRatio, midRatio, loudRatio, db(quietH), db(midH), db(loudH)))
        // Linear below the knee: a 20 dB quieter bass gets 20 dB quieter harmonics, not the same buzz.
        assertEquals("quiet and moderate passages get the same harmonic ratio", midRatio, quietRatio, 1.0)
        assertEquals("harmonics follow the level", 20.0, db(midH / quietH), 1.5)
        // Loud bass is compressed (and headroom-guarded) instead of driven into distortion.
        assertTrue("loud passage ratio $loudRatio should sit below $midRatio", loudRatio < midRatio - 3)
        assertTrue("loud harmonics still louder than moderate ones", loudH > midH)
    }

    @Test fun fullScaleBassAtFullAmountForcesTheLimiterAndNeverClips() {
        // The enhancer's own guard only caps the harmonics; its all-pass dry path can exceed full
        // scale on square-ish bass, so the chain turns the limiter on whenever the enhancer is.
        for (hz in listOf(50, 90, 100, 150, 200)) {
            val data = tone(50.0, rate * 2, 1.0, fadeMs = 30.0)
            val dsp = enhancer(100, hz)
            assertTrue("bass enhance must force the limiter", dsp.limiter)
            dsp.process(data, 0, data.size)
            val all = left(data, 0)
            val peak = all.maxOf { abs(it) }
            val clipped = all.count { abs(it) >= 32767.0 / 32768.0 }
            val harmonics = sqrt((2..4).sumOf { val m = magnitude(left(data, rate, rate), 50.0 * it); m * m })
            println("0 dBFS 50 Hz, crossover $hz Hz, amount 100: peak %.3f, harmonics %.1f dBFS".format(peak, db(harmonics)))
            assertEquals("clipped samples at crossover $hz", 0, clipped)
            assertTrue("peak $peak at crossover $hz", peak < 0.99)
            // Above ~100 Hz the harmonics of 50 Hz fall below the 1.5× band edge by design.
            if (hz <= 100) assertTrue("harmonics should still be added at crossover $hz, got ${db(harmonics)} dBFS", harmonics > 0.03)
        }
    }

    @Test fun splitIsPhaseCoherentAroundTheCrossover() {
        // The dry path is LR4 high band + trimmed LR4 low band. The two bands are in phase, so the
        // response is |HP| + g·|LP| = (r⁴ + g)/(1 + r⁴): a smooth shelf, no comb notch at the crossover.
        val fc = 100.0
        for (hz in listOf(30.0, 50.0, 80.0, 100.0, 120.0, 150.0, 200.0, 400.0, 1000.0)) {
            val enhancer = BassEnhancer(rate, 1).also { it.configure(100, fc.toInt()); it.snap(); it.headroomGuard = false }
            val g = enhancer.subBandGain()
            val x = DoubleArray(rate * 2) { sin(2 * PI * hz * it / rate) * 0.1 }
            enhancer.processBlock(x, x.size)
            val gain = db(magnitude(x.copyOfRange(rate, rate * 2), hz) / 0.1)
            val r4 = Math.pow(hz / fc, 4.0)
            val expected = db((r4 + g) / (1 + r4))
            assertEquals("dry response at $hz Hz", expected, gain, 0.3)
        }
    }

    @Test fun switchingOffFadesOutAndThenBypasses() {
        val dsp = enhancer(70, 90)
        val data = tone(45.0, rate, 0.3)
        dsp.process(data, 0, data.size / 2)
        dsp.configure(AudioEffectSettings(enabled = true, dspMode = true), softClip = false)
        dsp.process(data, data.size / 2, data.size / 2)
        val out = left(data, rate / 2 - 200, 4000)
        var maxStep = 0.0
        for (i in 1 until out.size) maxStep = maxOf(maxStep, abs(out[i] - out[i - 1]))
        assertTrue("fade must not click, step $maxStep", maxStep < 0.02)
        val after = tone(45.0, 4800, 0.3); val copy = after.copyOf()
        dsp.process(copy, 0, copy.size)
        assertTrue("after the fade the stage is bypassed", after.contentEquals(copy))
    }

    @Test fun cpuCostIsSmall() {
        val seconds = 10
        val data = tone(45.0, rate * seconds, 0.5)
        // Warm up the JIT, then time 10 s of stereo audio through the chain in 4096-frame blocks.
        val warm = data.copyOf(); enhancer(70, 90).process(warm, 0, warm.size)
        val dsp = enhancer(70, 90)
        val block = 4096 * 4
        val start = System.nanoTime()
        var o = 0
        while (o < data.size) { val n = min(block, data.size - o); dsp.process(data, o, n); o += n }
        val chainMs = (System.nanoTime() - start) / 1e6

        val raw = DoubleArray(rate * seconds * 2) { sin(2 * PI * 45.0 * (it / 2) / rate) * 0.5 }
        val alone = BassEnhancer(rate, 2).also { it.configure(70, 90); it.snap() }
        alone.processBlock(raw.copyOf(), rate * 2)
        val t0 = System.nanoTime()
        alone.processBlock(raw, rate * seconds)
        val aloneMs = (System.nanoTime() - t0) / 1e6
        println("10 s stereo 48 kHz: chain with enhancer %.1f ms, enhancer alone %.1f ms".format(chainMs, aloneMs))
        assertTrue("chain took $chainMs ms for 10 s", chainMs < 1000)
        assertTrue("enhancer took $aloneMs ms for 10 s", aloneMs < 500)
    }
}
