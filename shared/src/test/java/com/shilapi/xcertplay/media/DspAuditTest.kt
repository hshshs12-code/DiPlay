package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Regression fixtures for the defects found by the October 2026 external DSP audit: each test
 * reproduces the original failure case and asserts the corrected behaviour.
 */
class DspAuditTest {
    private val rate = 48_000

    /** Reference true peak: 16x oversampling with a long (±48 taps) Kaiser-windowed sinc. */
    private val referenceKernel = Array(16) { p ->
        DoubleArray(96) { i ->
            val d = (i - 47) - p / 16.0
            val sinc = if (d == 0.0) 1.0 else sin(PI * d) / (PI * d)
            val r = d / 48
            if (abs(r) >= 1) 0.0 else sinc * bessel(9.0 * sqrt(1 - r * r)) / bessel(9.0)
        }
    }
    private fun bessel(x: Double): Double { var s = 1.0; var t = 1.0; var k = 1; while (t > 1e-17 * s) { val h = x / (2 * k); t *= h * h; s += t; k++ }; return s }
    private fun truePeak(x: DoubleArray, guard: Int = 64): Double {
        var peak = 0.0
        for (n in guard until x.size - guard) for (kernel in referenceKernel) {
            var acc = 0.0
            for (i in 0 until 96) acc += x[n - 47 + i] * kernel[i]
            peak = max(peak, abs(acc))
        }
        return peak
    }

    private fun pcm(frames: Int, right: ((Int) -> Double)? = null, left: (Int) -> Double): ByteArray {
        val data = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val l = (left(i) * 32767).toInt(); val r = ((right?.invoke(i) ?: left(i)) * 32767).toInt()
            data[i * 4] = l.toByte(); data[i * 4 + 1] = (l shr 8).toByte()
            data[i * 4 + 2] = r.toByte(); data[i * 4 + 3] = (r shr 8).toByte()
        }
        return data
    }
    private fun left(data: ByteArray) = DoubleArray(data.size / 4) { ((data[it * 4 + 1].toInt() shl 8) or (data[it * 4].toInt() and 0xff)) / 32768.0 }

    /** C2: a 64-sample ±0.98 sign burst whose reconstructed peak (1.27) a 12-tap detector read as 0.62. */
    @Test fun limiterHoldsBroadbandSignBursts() {
        val fixture = "--------+-----+++-+++---+++-+-+-+-++--+-+++++----++--+-+---+-+-+"
        val frames = 1400
        val mono = DoubleArray(frames)
        fixture.forEachIndexed { i, c -> mono[500 + i] = if (c == '+') 0.98 else -0.98 }
        assertTrue("the fixture itself overshoots", truePeak(mono) > 1.2)
        TruePeakLimiter(rate, 1).process(mono, frames)
        val tp = truePeak(mono)
        println("sign-burst fixture: output reference true peak %.4f (%.2f dBTP)".format(tp, 20 * log10(tp)))
        assertTrue("fixture true peak $tp must stay inside full scale", tp < 0.995)

        // The deterministic search the audit used: 200 random bursts, worst case below full scale.
        val random = java.util.Random(424242)
        var worst = 0.0
        repeat(200) {
            val burst = DoubleArray(frames)
            for (i in 0 until 64) burst[500 + i] = if (random.nextBoolean()) 0.98 else -0.98
            TruePeakLimiter(rate, 1).process(burst, frames)
            worst = max(worst, truePeak(burst))
        }
        println("200 random sign bursts: worst output true peak %.4f".format(worst))
        assertTrue("worst burst true peak $worst must stay inside full scale", worst < 0.995)
    }

    /** C6: the ceiling has to survive the output stage's 44.1→48 kHz conversion too. */
    @Test fun finalOutputAfterResamplingStaysInsideFullScale() {
        val inRate = 44_100
        val dsp = EqualizerDsp(inRate, 2).also { it.configure(FloatArray(12), limiter = true) }
        val stage = MediaOutputStage(inRate, 2, 48_000, floatOutput = true) { dsp }
        val random = java.util.Random(7)
        var worst = 0f
        repeat(20) {
            val data = pcm(4096) { i -> if (i in 1000 until 1064) (if (random.nextBoolean()) 0.98 else -0.98) else 0.0 }
            val rendered = stage.render(data, 0, data.size)
            val floats = rendered.floats!!
            for (i in 0 until rendered.lengthBytes / 4) worst = max(worst, abs(floats[i]))
        }
        println("after 44.1→48 kHz: worst output sample %.4f".format(worst))
        assertTrue("resampled output $worst must not reach full scale", worst < 1.0f)
    }

    /** C5: a +12 dB step must ramp identically whether the stream arrives in one block or one frame at a time. */
    @Test fun coefficientRampDoesNotDependOnBlockSize() {
        fun run(block: Int): DoubleArray {
            val dsp = EqualizerDsp(rate, 2).also { it.configure(FloatArray(12), limiter = false) }
            val frames = 4800
            val data = pcm(frames) { i -> 0.1 * sin(2 * PI * 1000.0 * i / rate) }
            dsp.process(data, 0, 4)   // adopt the neutral configuration, then change it
            dsp.configure(FloatArray(12).also { it[5] = 12f }, limiter = false)
            var at = 1
            while (at < frames) { val n = minOf(block, frames - at); dsp.process(data, at * 4, n * 4); at += n }
            return left(data)
        }
        val whole = run(4800); val single = run(1); val odd = run(37)
        var worst = 0.0
        for (i in whole.indices) worst = max(worst, max(abs(whole[i] - single[i]), abs(whole[i] - odd[i])))
        println("ramp block-size dependence: worst sample difference ${worst * 32768} LSB")
        assertTrue("ramp must be identical at every block size (got ${worst * 32768} LSB)", worst <= 1.0 / 32768)
        // And it is a ramp, not a jump: the boost is still growing 10 ms in and finished by 60 ms.
        fun peakAround(frameAt: Int) = (frameAt until frameAt + 48).maxOf { abs(whole[it]) }
        assertTrue(peakAround(480) < 0.3)
        assertTrue(peakAround(2880) > 0.35)
    }

    /** C8: toggling the limiter while audio plays crossfades instead of gapping or jumping. */
    @Test fun limiterToggleIsContinuous() {
        val dsp = EqualizerDsp(rate, 2).also { it.configure(FloatArray(12).also { g -> g[1] = 1f }, limiter = false) }
        val frames = rate
        val data = pcm(frames) { i -> 0.5 * sin(2 * PI * 100.0 * i / rate) }
        val block = 480
        var at = 0
        while (at < frames) {
            if (at == 4800) dsp.configure(FloatArray(12).also { g -> g[1] = 1f }, limiter = true)
            if (at == 24000) dsp.configure(FloatArray(12).also { g -> g[1] = 1f }, limiter = false)
            if (at == 33600) dsp.configure(FloatArray(12).also { g -> g[1] = 1f }, limiter = true)
            dsp.process(data, at * 4, block * 4); at += block
        }
        val out = left(data)
        var worstJump = 0.0; var worstAt = 0
        for (i in 1 until out.size) { val j = abs(out[i] - out[i - 1]); if (j > worstJump) { worstJump = j; worstAt = i } }
        val slope = 2 * PI * 100.0 * 0.5 / rate   // steepest sample-to-sample change of the tone itself
        println("limiter toggles: worst sample step %.5f at frame %d (tone slope %.5f)".format(worstJump, worstAt, slope))
        assertTrue("toggling must not jump (step $worstJump at $worstAt)", worstJump < slope * 2.5)
        // No gap either: the tone never drops out around the switch points.
        for (switchAt in intArrayOf(4800, 24000, 33600)) {
            val window = (switchAt until switchAt + 1200)
            assertTrue("level held across the switch at $switchAt", window.maxOf { abs(out[it]) } > 0.4)
        }
    }

    /** B1/C4: the enhancer and every boosting stage run under the limiter, so nothing reaches the clip. */
    @Test fun bassEnhancerSquareWaveNeverClipsThroughTheChain() {
        for (hz in intArrayOf(50, 100, 200)) {
            val s = AudioEffectSettings(enabled = true, dspMode = true, bassEnhance = 100, bassEnhanceHz = 100, limiter = false)
            val dsp = EqualizerDsp(rate, 2).also { it.configure(s, softClip = false) }
            assertTrue(dsp.limiter)
            val data = pcm(rate) { i -> if (sin(2 * PI * hz * i / rate) >= 0) 0.99 else -0.99 }
            val out = FloatArray(rate * 2)
            dsp.processToFloat(data, 0, data.size, out)
            val peak = out.maxOf { abs(it) }
            assertTrue("square $hz Hz through enhancer + limiter peaked at $peak", peak <= TruePeakLimiter.CLIP + 1e-6)
        }
        // The headroom helper (unused by the renderers) now composes serial boosts instead of taking their maximum.
        val shelf = AudioEffectSettings(enabled = true, dspMode = true, parametric = listOf(ParametricBand.lowShelf(150, 12f)), limiter = false)
        assertEquals(10.0.pow(-12 / 20.0).toFloat(), shelf.headroomGain(), 1e-4f)
        val stacked = shelf.copy(dspGainsDb = List(12) { if (it == 1) 6 else 0 }, airDb = 2)
        assertEquals(10.0.pow(-20 / 20.0).toFloat(), stacked.headroomGain(), 1e-4f)
    }

    /** B3: the stereo stage's bound covers out-of-phase material. */
    @Test fun stereoBoundCoversAntiPhase() {
        val st = StereoStage(rate)
        st.configure(200, 0, 0, AudioEffectSettings.DRIVER_LEFT, 0)
        val buf = DoubleArray(rate * 2)
        for (i in 0 until rate) { val v = 0.5 * sin(2 * PI * 8000.0 * i / rate); buf[2 * i] = v; buf[2 * i + 1] = -v }
        st.process(buf, rate)
        var peak = 0.0
        for (i in rate until rate * 2) peak = max(peak, abs(buf[i]))
        val measured = 20 * log10(peak / 0.5)
        println("width 200 anti-phase 8 kHz: measured %.2f dB, bound %.2f dB".format(measured, StereoStage.boostDb(200, 0)))
        assertTrue("bound ${StereoStage.boostDb(200, 0)} must cover the measured $measured dB", StereoStage.boostDb(200, 0) >= measured - 0.01)
    }

    /** Q1: the de-esser must see sibilance that cancels in a mono sum. */
    @Test fun airDeEsserDetectsAntiPhaseSibilance() {
        fun topGainDb(anti: Boolean): Double {
            val air = AirShelf(rate, 2)
            air.configure(4, 10000, 100, immediate = true)
            val frames = rate * 2
            val buf = DoubleArray(frames * 2)
            for (i in 0 until frames) { val v = 0.4 * sin(2 * PI * 7000.0 * i / rate); buf[2 * i] = v; buf[2 * i + 1] = if (anti) -v else v }
            air.process(buf, frames)
            return air.gainDb()
        }
        val inPhase = topGainDb(false); val antiPhase = topGainDb(true)
        println("air de-ess on a 7 kHz tone: in-phase %.2f dB, anti-phase %.2f dB".format(inPhase, antiPhase))
        assertTrue("in-phase sibilance pulls the shelf down", inPhase < -2.0)
        assertEquals("anti-phase sibilance must be detected just the same", inPhase, antiPhase, 0.1)
    }

    /** P1: digital silence after programme leaves no subnormal state in the stereo, air and loudness stages. */
    @Test fun silenceLeavesNoSubnormalsBehind() {
        val dsp = EqualizerDsp(rate, 2)
        val s = AudioEffectSettings(enabled = true, dspMode = true, stereoWidth = 160, airDb = 4, deEss = 50, loudnessComp = true, loudnessCompAmount = 80, limiter = true)
        LoudnessCompensator.listeningLevelDb = -20.0
        try {
            dsp.configure(s, softClip = false)
            val burst = pcm(4800) { i -> if (i < 480) 0.5 * sin(2 * PI * 1000.0 * i / rate) else 0.0 }
            dsp.process(burst, 0, burst.size)
            val silence = ByteArray(rate * 4)
            val started = System.nanoTime()
            repeat(10) { silence.fill(0); dsp.process(silence, 0, silence.size) }
            val perFrame = (System.nanoTime() - started) / (10.0 * rate)
            println("10 s of silence through stereo + air + loudness + limiter: %.0f ns/frame".format(perFrame))
            // The audio must be silent (no residue) and a 1 s block must not take longer than real time / 50.
            assertTrue(silence.all { it.toInt() in -1..1 })
            assertTrue("silence should be cheap, got $perFrame ns/frame", perFrame < 20_000.0 / 50)
        } finally {
            LoudnessCompensator.listeningLevelDb = 0.0
        }
    }

    /** C7: only qualified rate pairs go through DiPlay's resampler. */
    @Test fun resamplerOnlyClaimsQualifiedPairs() {
        assertTrue(SincResampler.supports(44_100, 48_000))
        assertTrue(SincResampler.supports(48_000, 44_100))
        assertTrue(SincResampler.supports(32_000, 48_000))
        assertTrue(SincResampler.supports(48_000, 48_000))
        assertFalse(SincResampler.supports(48_000, 16_000))
        assertFalse(SincResampler.supports(48_000, 96_000))
        assertFalse(SincResampler.supports(8_000, 48_000))
    }

    private fun Double.pow(e: Double) = Math.pow(this, e)
}
