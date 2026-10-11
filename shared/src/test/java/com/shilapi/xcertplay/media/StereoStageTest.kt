package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class StereoStageTest {
    private val rate = 48_000

    private fun settings() = AudioEffectSettings(enabled = true, dspMode = true)

    /** 16-bit interleaved stereo from per-channel generators. */
    private fun pcm(frames: Int, left: (Int) -> Double, right: (Int) -> Double): ByteArray {
        val data = ByteArray(frames * 4)
        for (i in 0 until frames) {
            for (ch in 0 until 2) {
                val v = Math.round((if (ch == 0) left(i) else right(i)) * 32767).toInt()
                val o = (i * 2 + ch) * 2
                data[o] = v.toByte(); data[o + 1] = (v shr 8).toByte()
            }
        }
        return data
    }

    private fun channel(data: ByteArray, ch: Int, from: Int = 0): DoubleArray {
        val frames = data.size / 4
        return DoubleArray(frames - from) { i ->
            val o = ((i + from) * 2 + ch) * 2
            ((data[o + 1].toInt() shl 8) or (data[o].toInt() and 0xff)) / 32768.0
        }
    }

    private fun channel(data: FloatArray, ch: Int, from: Int = 0) = DoubleArray(data.size / 2 - from) { data[(it + from) * 2 + ch].toDouble() }

    private fun rms(x: DoubleArray) = sqrt(x.sumOf { it * it } / x.size)

    private fun sine(hz: Double, amplitude: Double, phase: Double = 0.0, sr: Int = rate): (Int) -> Double =
        { i -> amplitude * sin(2 * PI * hz * i / sr + phase) }

    /** Band-limited noise (random sines from 60 Hz to 3 kHz) so the correlation peak is smooth. */
    private fun bandNoise(seed: Long, sr: Int): (Int) -> Double {
        val rnd = Random(seed)
        val hz = DoubleArray(80) { 60.0 + rnd.nextDouble() * 2940.0 }
        val ph = DoubleArray(80) { rnd.nextDouble() * 2 * PI }
        return { i -> var s = 0.0; for (k in hz.indices) s += sin(2 * PI * hz[k] * i / sr + ph[k]); s * 0.008 }
    }

    private fun renderFloat(dsp: EqualizerDsp, data: ByteArray, block: Int = 480, onBlock: (Int) -> Unit = {}): FloatArray {
        val out = FloatArray(data.size / 2)
        val tmp = FloatArray(block * 2)
        var frame = 0
        val frames = data.size / 4
        while (frame < frames) {
            onBlock(frame)
            val n = minOf(block, frames - frame)
            val written = dsp.processToFloat(data, frame * 4, n * 4, tmp)
            System.arraycopy(tmp, 0, out, frame * 2, written)
            frame += n
        }
        return out
    }

    /** Lag (in samples, sub-sample by parabolic interpolation) by which [late] trails [early]. */
    private fun delayBetween(early: DoubleArray, late: DoubleArray, maxLag: Int): Double {
        val from = maxLag + 10
        fun corr(k: Int): Double { var s = 0.0; for (n in from until late.size) s += late[n] * early[n - k]; return s }
        val c = DoubleArray(maxLag + 2) { corr(it) }
        var best = 1
        for (k in 1..maxLag) if (c[k] > c[best]) best = k
        val ym = c[best - 1]; val y0 = c[best]; val yp = c[best + 1]
        return best + 0.5 * (ym - yp) / (ym - 2 * y0 + yp)
    }

    private fun maxStep(x: DoubleArray, from: Int, to: Int): Double {
        var m = 0.0
        for (i in max(1, from) until to) m = max(m, abs(x[i] - x[i - 1]))
        return m
    }

    @Test fun monoSignalStaysIdenticalAtWidth100AndIsUntouchedByWideningOrCrossfeed() {
        // Width 100 is neutral: the chain skips the stage and the bytes are untouched.
        val dsp = EqualizerDsp(rate, 2)
        dsp.configure(settings().copy(stereoWidth = 100), softClip = false)
        val original = pcm(9600, sine(1000.0, 0.4), sine(1000.0, 0.4))
        val copy = original.copyOf()
        dsp.process(copy, 0, copy.size)
        assertTrue(original.contentEquals(copy))

        // Mono content has no side signal, so widening and crossfeed leave it exactly as it was.
        val stage = StereoStage(rate).apply { configure(170, 0, 0, 0, 80) }
        val src = sine(220.0, 0.5)
        val buf = DoubleArray(2 * 4800) { src(it / 2) }
        val ref = buf.copyOf()
        stage.process(buf, 4800)
        var err = 0.0
        for (i in buf.indices) err = max(err, abs(buf[i] - ref[i]))
        assertTrue("mono content changed by $err", err < 1e-15)
    }

    @Test fun widthZeroCollapsesTheSideSignal() {
        val dsp = EqualizerDsp(rate, 2)
        dsp.configure(settings().copy(stereoWidth = 0), softClip = false)
        val data = pcm(9600, sine(440.0, 0.5), sine(660.0, 0.3, 1.0))
        val out = renderFloat(dsp, data)
        val l = channel(out, 0); val r = channel(out, 1)
        var side = 0.0
        for (i in l.indices) side = max(side, abs(l[i] - r[i]))
        assertEquals("L−R must vanish at width 0", 0.0, side, 0.0)
        assertTrue("mid must remain", rms(l) > 0.2)
    }

    @Test fun wideningKeepsTheMonoSumAndLeavesBassAlone() {
        val stage = StereoStage(rate).apply { configure(200, 0, 0, 0, 0) }
        val frames = rate
        val lFn = { i: Int -> sine(60.0, 0.3)(i) + sine(4000.0, 0.2)(i) }
        val rFn = { i: Int -> sine(60.0, 0.3, 0.8)(i) + sine(4000.0, 0.2, 1.4)(i) }
        val buf = DoubleArray(2 * frames) { if (it % 2 == 0) lFn(it / 2) else rFn(it / 2) }
        val ref = buf.copyOf()
        stage.process(buf, frames)
        var sumErr = 0.0
        for (n in 0 until frames) sumErr = max(sumErr, abs((buf[2 * n] + buf[2 * n + 1]) - (ref[2 * n] + ref[2 * n + 1])))
        assertTrue("L+R must be preserved, error $sumErr", sumErr < 1e-12)
        fun sideMag(x: DoubleArray, hz: Double): Double {
            var re = 0.0; var im = 0.0
            for (n in rate / 2 until frames) { val s = x[2 * n] - x[2 * n + 1]; val w = 2 * PI * hz * n / rate; re += s * cos(w); im -= s * sin(w) }
            return sqrt(re * re + im * im)
        }
        val bassDb = 20 * log10(sideMag(buf, 60.0) / sideMag(ref, 60.0))
        val highDb = 20 * log10(sideMag(buf, 4000.0) / sideMag(ref, 4000.0))
        assertTrue("side at 60 Hz should stay within 1 dB, got $bassDb", abs(bassDb) < 1.0)
        assertTrue("side at 4 kHz should be about +6 dB at width 200, got $highDb", highDb > 5.5 && highDb < 6.1)
    }

    @Test fun balanceFullLeftSilencesTheRightChannel() {
        val dsp = EqualizerDsp(rate, 2)
        dsp.configure(settings().copy(balance = -100), softClip = false)
        val data = pcm(rate, sine(1000.0, 0.3), sine(1000.0, 0.3))
        val inRms = rms(channel(data, 1))
        dsp.process(data, 0, data.size)
        val rightDb = 20 * log10(rms(channel(data, 1)) / inRms)
        val leftDb = 20 * log10(rms(channel(data, 0)) / inRms)
        assertTrue("right should be below −40 dB, got $rightDb", rightDb < -40)
        assertTrue("constant power: left should be +3 dB, got $leftDb", leftDb > 2.9 && leftDb < 3.1)
        val (gl, gr) = StereoStage.panGains(35)
        assertEquals(2.0, gl * gl + gr * gr, 1e-12)
    }

    @Test fun driverSeatDelayDelaysTheNearSpeakerByExactlyTheConfiguredAmount() {
        // 1.2 ms on the left at 48 kHz = 57.6 samples, through the full chain.
        val dsp = EqualizerDsp(rate, 2)
        dsp.configure(settings().copy(driverSeatDelayTenthsMs = 12), softClip = false)
        val noise = bandNoise(7, rate)
        val out = renderFloat(dsp, pcm(rate, noise, noise))
        val measured = delayBetween(channel(out, 1), channel(out, 0), 120)
        assertEquals("left should trail right by 57.6 samples", 57.6, measured, 0.05)

        // 1.0 ms at 44.1 kHz = 44.1 samples; right-hand drive delays the right channel instead.
        val sr = 44_100
        val stage = StereoStage(sr).apply { configure(100, 0, 10, AudioEffectSettings.DRIVER_RIGHT, 0) }
        val n2 = bandNoise(11, sr)
        val buf = DoubleArray(2 * sr) { n2(it / 2) }
        stage.process(buf, sr)
        val l = DoubleArray(sr) { buf[2 * it] }; val r = DoubleArray(sr) { buf[2 * it + 1] }
        assertEquals(44.1, delayBetween(l, r, 100), 0.05)

        // A whole number of samples (2.0 ms at 48 kHz = 96) is a bit-exact copy.
        val exact = StereoStage(rate).apply { configure(100, 0, 20, AudioEffectSettings.DRIVER_LEFT, 0) }
        val src = bandNoise(3, rate)
        val b3 = DoubleArray(2 * 4800) { src(it / 2) }
        exact.process(b3, 4800)
        for (n in 96 until 4800) assertEquals(src(n - 96), b3[2 * n], 0.0)
        for (n in 0 until 96) assertEquals(0.0, b3[2 * n], 0.0)
    }

    @Test fun changingWidthMidStreamDoesNotClick() {
        val dsp = EqualizerDsp(rate, 2)
        val base = settings()
        dsp.configure(base, softClip = false)
        val frames = rate * 3 / 2
        val change = rate / 2
        val data = pcm(frames, sine(1000.0, 0.3), sine(1000.0, 0.3, 1.2))
        val out = renderFloat(dsp, data) { at -> if (at == change) dsp.configure(base.copy(stereoWidth = 160), softClip = false) }
        for (ch in 0 until 2) {
            val x = channel(out, ch)
            val before = maxStep(x, change - rate / 4, change)
            val transition = maxStep(x, change - 10, change + rate / 2)
            val after = maxStep(x, frames - rate / 4, frames)
            assertTrue("ch $ch: width should have changed the level (before $before, after $after)", abs(after - before) > 0.003)
            assertTrue("ch $ch: transition step $transition exceeds steady state ${max(before, after)}", transition <= max(before, after) * 1.01)
        }
    }

    @Test fun changingDelayAndBalanceMidStreamDoesNotClick() {
        val dsp = EqualizerDsp(rate, 2)
        val base = settings()
        dsp.configure(base, softClip = false)
        val frames = rate * 2
        val change = rate / 2
        val data = pcm(frames, sine(2000.0, 0.3), sine(2000.0, 0.3, 0.5))
        val out = renderFloat(dsp, data) { at ->
            if (at == change) dsp.configure(base.copy(driverSeatDelayTenthsMs = 37, balance = 40), softClip = false)
        }
        for (ch in 0 until 2) {
            val x = channel(out, ch)
            val steady = max(maxStep(x, change - rate / 4, change), maxStep(x, frames - rate / 4, frames))
            val transition = maxStep(x, change - 10, frames)
            // The delay glide shifts pitch by at most 1 %, which raises the slope by at most ~1 %.
            assertTrue("ch $ch: transition step $transition vs steady $steady", transition <= steady * 1.02)
        }
    }

    @Test fun crossfeedBleedsLowsOnlyAndReturnsToNeutral() {
        val stage = StereoStage(rate).apply { configure(100, 0, 0, 0, 100) }
        fun bleedDb(hz: Double): Double {
            val s = StereoStage(rate).apply { configure(100, 0, 0, 0, 100) }
            val src = sine(hz, 0.5)
            val buf = DoubleArray(2 * rate) { if (it % 2 == 0) src(it / 2) else 0.0 }
            s.process(buf, rate)
            val l = DoubleArray(rate / 2) { buf[2 * (it + rate / 2)] }; val r = DoubleArray(rate / 2) { buf[2 * (it + rate / 2) + 1] }
            return 20 * log10(rms(r) / rms(l))
        }
        val low = bleedDb(100.0)
        val high = bleedDb(8000.0)
        assertTrue("bleed at 100 Hz should be about −7.4 dB, got $low", low > -8.5 && low < -6.5)
        assertTrue("bleed at 8 kHz should be small, got $high", high < -25)

        // Back to neutral: ramps out and switches itself off.
        stage.process(DoubleArray(2 * 4800), 4800)
        stage.configure(100, 0, 0, 0, 0)
        repeat(20) { stage.process(DoubleArray(2 * 4800), 4800) }
        assertFalse(stage.isActive())
    }

    @Test fun settingsAndPresetsExposeTheStage() {
        assertFalse(AudioEffectSettings().hasAdvancedProcessing())
        assertTrue(AudioEffectSettings(stereoWidth = 140).hasAdvancedProcessing())
        assertTrue(AudioEffectSettings(balance = -5).hasAdvancedProcessing())
        assertTrue(AudioEffectSettings(driverSeatDelayTenthsMs = 12).hasAdvancedProcessing())
        assertTrue(AudioEffectSettings(crossfeed = 30).hasAdvancedProcessing())
        assertFalse(AudioEffectSettings(driverSide = AudioEffectSettings.DRIVER_RIGHT).hasAdvancedProcessing())
        // Widening without the limiter reserves headroom for hard-panned peaks.
        val wide = AudioEffectSettings(enabled = true, dspMode = true, stereoWidth = 200).headroomGain()
        assertEquals(1 / 1.5f, wide, 1e-4f)

        val aligned = listOf("Rage", "Trap Clean", "Melodic")
        assertEquals(3, SoundPresets.YARIS.count { p -> aligned.any { p.name.contains(it) } })
        for (preset in SoundPresets.YARIS) {
            val s = preset.apply(AudioEffectSettings(driverSide = AudioEffectSettings.DRIVER_RIGHT, stereoWidth = 150, crossfeed = 40))
            val expected = if (aligned.any { preset.name.contains(it) }) 12 else 0
            assertEquals(preset.name, expected, s.driverSeatDelayTenthsMs)
            assertEquals(100, s.stereoWidth); assertEquals(0, s.balance); assertEquals(0, s.crossfeed)
            assertEquals("the driver side belongs to the car, not the preset", AudioEffectSettings.DRIVER_RIGHT, s.driverSide)
        }
    }

    @Test fun cpuCostIsAHandfulOfOperationsPerFrame() {
        val seconds = 20
        val frames = rate * seconds
        val frame = DoubleArray(2)
        val input = DoubleArray(960) { sine(997.0, 0.3)(it) }
        fun time(stage: StereoStage): Double {
            var sink = 0.0
            val t0 = System.nanoTime()
            var n = 0
            while (n < frames) {
                stage.beginBlock()
                for (k in 0 until 960) { frame[0] = input[k]; frame[1] = -0.5 * input[k]; stage.processFrame(frame); sink += frame[0] }
                n += 960
            }
            val ns = (System.nanoTime() - t0).toDouble() / frames
            assertTrue(sink.isFinite())
            return ns
        }
        val stage = StereoStage(rate).apply { configure(160, 15, 12, 0, 50) }
        time(stage) // warm-up / JIT
        val ns = time(stage)
        // The real-time budget is 20 833 ns per frame at 48 kHz.
        println("StereoStage: %.1f ns/frame (%.4f %% of real time on this host)".format(ns, ns / 20_833.0 * 100))
        assertTrue("stage too slow: $ns ns/frame", ns < 1_000)

        // Whole chain with a Yaris preset, with and without the stage.
        fun chain(s: AudioEffectSettings): Double {
            val dsp = EqualizerDsp(rate, 2).apply { configure(s, softClip = false) }
            val data = pcm(rate, sine(997.0, 0.3), sine(503.0, 0.2))
            repeat(3) { dsp.process(data.copyOf(), 0, data.size) }
            val t0 = System.nanoTime()
            repeat(5) { dsp.process(data.copyOf(), 0, data.size) }
            return (System.nanoTime() - t0).toDouble() / (5 * rate)
        }
        val rage = SoundPresets.YARIS.first { it.name.contains("Rage") }.apply(AudioEffectSettings())
        val with = chain(rage.copy(stereoWidth = 160, balance = 15, crossfeed = 50))
        val without = chain(rage.copy(driverSeatDelayTenthsMs = 0))
        println("Yaris Rage chain: %.1f ns/frame without stage, %.1f ns/frame with stage".format(without, with))
    }
}
