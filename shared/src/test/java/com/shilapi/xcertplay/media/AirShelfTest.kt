package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AirShelfTest {
    private val rate = 48_000

    private fun stereo(frames: Int, sample: (Int) -> Double): DoubleArray {
        val out = DoubleArray(frames * 2)
        for (i in 0 until frames) { val v = sample(i); out[i * 2] = v; out[i * 2 + 1] = v }
        return out
    }

    private fun sine(hz: Double, amplitude: Double): (Int) -> Double = { i -> amplitude * sin(2 * PI * hz * i / rate) }

    /** Magnitude of [hz] in the left channel over frames [from, to). */
    private fun magnitude(x: DoubleArray, hz: Double, from: Int, to: Int): Double {
        var re = 0.0; var im = 0.0
        for (i in from until to) { val w = 2 * PI * hz * i / rate; re += x[i * 2] * cos(w); im -= x[i * 2] * sin(w) }
        return 2 * sqrt(re * re + im * im) / (to - from)
    }

    private fun shelf(airDb: Int, airHz: Int, deEss: Int) = AirShelf(rate, 2).also { it.configure(airDb, airHz, deEss, immediate = true) }

    private fun gainChange(airDb: Int, airHz: Int, hz: Double): Double {
        val frames = rate
        val input = stereo(frames, sine(hz, 0.25))
        val output = input.copyOf()
        shelf(airDb, airHz, deEss = 0).process(output, frames)
        return 20 * log10(magnitude(output, hz, frames / 2, frames) / magnitude(input, hz, frames / 2, frames))
    }

    @Test fun staticShelfLiftsTheTopAndLeavesTheMids() {
        val top = gainChange(4, 8000, 14_000.0)
        assertEquals("14 kHz with +4 dB air at 8 kHz", 4.0, top, 0.5)
        val top10 = gainChange(4, 10_000, 14_000.0)
        assertEquals("14 kHz with +4 dB air at 10 kHz (slope-1 shelf not fully on plateau)", 4.0, top10, 0.5)
        val mid = gainChange(4, 10_000, 1000.0)
        assertTrue("1 kHz must not move, got $mid dB", abs(mid) < 0.2)
        println("AirShelf static: 14 kHz +%.2f dB (8 kHz corner), +%.2f dB (10 kHz corner); 1 kHz %.3f dB".format(top, top10, mid))
    }

    @Test fun equalizerChainAppliesTheShelfOnSixteenBitAudio() {
        val dsp = EqualizerDsp(rate, 2)
        dsp.configure(AudioEffectSettings(enabled = true, dspMode = true, airDb = 4, airHz = 8000, deEss = 0), softClip = false)
        val frames = rate
        val pcm = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * 14_000.0 * i / rate) * 0.25 * 32767).toInt()
            for (ch in 0 until 2) { val o = (i * 2 + ch) * 2; pcm[o] = v.toByte(); pcm[o + 1] = (v shr 8).toByte() }
        }
        val before = DoubleArray(frames * 2) { (((pcm[it * 2 + 1].toInt() shl 8) or (pcm[it * 2].toInt() and 0xff))) / 32768.0 }
        dsp.process(pcm, 0, pcm.size)
        val after = DoubleArray(frames * 2) { (((pcm[it * 2 + 1].toInt() shl 8) or (pcm[it * 2].toInt() and 0xff))) / 32768.0 }
        val db = 20 * log10(magnitude(after, 14_000.0, frames / 2, frames) / magnitude(before, 14_000.0, frames / 2, frames))
        assertEquals(4.0, db, 0.5)
    }

    /** −30 dBFS 1 kHz music with a −6 dBFS 7 kHz "s" from 300 to 400 ms. */
    private val burstStart = rate * 300 / 1000
    private val burstEnd = rate * 400 / 1000
    private val burstFrames = rate * 700 / 1000
    private fun burstSignal() = stereo(burstFrames) { i ->
        0.0316 * sin(2 * PI * 1000.0 * i / rate) + if (i in burstStart until burstEnd) 0.5 * sin(2 * PI * 7000.0 * i / rate) else 0.0
    }

    /** Runs [input] through [air] frame by frame; returns the output and the shelf gain per frame. */
    private fun run(air: AirShelf, input: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val out = input.copyOf()
        val gains = DoubleArray(input.size / 2)
        val frame = DoubleArray(2)
        for (n in gains.indices) {
            frame[0] = out[n * 2]; frame[1] = out[n * 2 + 1]
            air.processFrame(frame)
            out[n * 2] = frame[0]; out[n * 2 + 1] = frame[1]
            gains[n] = air.gainDb()
        }
        return out to gains
    }

    @Test fun sibilanceBurstPullsTheShelfDownFastAndReleases() {
        val (_, gains) = run(shelf(4, 10_000, AirShelf.DEFAULT_DE_ESS), burstSignal())
        val ms = rate / 1000
        for (n in 100 * ms until burstStart) assertEquals("no de-essing on plain music at ${n / ms} ms", 4.0, gains[n], 0.05)
        val down = (burstStart until burstEnd).first { gains[it] <= 1.0 }
        assertTrue("shelf must reach <= +1 dB within 5 ms, took ${(down - burstStart) * 1000.0 / rate} ms", down - burstStart <= 5 * ms)
        for (n in burstStart + 5 * ms until burstEnd) assertTrue("held down during the burst", gains[n] <= 1.0)
        val deepest = gains.slice(burstStart until burstEnd).minOrNull()!!
        assertTrue("never below the -3 dB floor, got $deepest", deepest >= -3.0 - 1e-9)
        val back = (burstEnd until gains.size).first { n -> (n until minOf(gains.size, n + 20 * ms)).all { gains[it] >= 3.5 } }
        val recoveryMs = (back - burstEnd) * 1000.0 / rate
        assertTrue("shelf must be back within 0.5 dB of +4 dB by ~100 ms, took $recoveryMs ms", recoveryMs <= 100.0)
        println("AirShelf de-ess: <= +1 dB after %.2f ms, deepest %.2f dB, back to +3.5 dB %.1f ms after the burst"
            .format((down - burstStart) * 1000.0 / rate, deepest, recoveryMs))
    }

    @Test fun deEssZeroNeverReduces() {
        val (_, gains) = run(shelf(4, 10_000, 0), burstSignal())
        for (g in gains) assertEquals(4.0, g, 1e-9)
    }

    @Test fun fullDeEssMayDipSlightlyBelowFlat() {
        val (_, gains) = run(shelf(4, 10_000, 100), burstSignal())
        val deepest = gains.minOrNull()!!
        assertTrue("de-ess 100 reaches into a small cut but not past -3 dB, got $deepest", deepest < -1.0 && deepest >= -3.0 - 1e-9)
    }

    private fun maxStep(x: DoubleArray, from: Int, to: Int): Double {
        var m = 0.0
        for (n in max(1, from) until to) m = max(m, abs(x[n * 2] - x[(n - 1) * 2]))
        return m
    }

    @Test fun gainChangesNeverStepHarderThanTheMusic() {
        val input = burstSignal()
        val (dynamic, _) = run(shelf(4, 10_000, 100), input)
        val (static, _) = run(shelf(4, 10_000, 0), input)
        val all = maxStep(dynamic, 0, burstFrames)
        val reference = max(maxStep(input, 0, burstFrames), maxStep(static, 0, burstFrames))
        assertTrue("largest step $all vs music $reference", all <= reference * 1.01)
        // While the shelf recovers after the burst only the quiet 1 kHz tone plays: any zipper or click
        // from the moving gain would stand out against its small sample-to-sample steps.
        val from = burstEnd + rate / 1000; val to = burstEnd + rate / 5
        val recovering = maxStep(dynamic, from, to)
        val dry = maxStep(input, from, to)
        assertTrue("step while recovering $recovering vs dry $dry", recovering <= dry * 1.05)
    }

    @Test fun switchingOffFadesOutAndGoesIdle() {
        val air = shelf(4, 10_000, 50)
        val frames = rate / 10
        run(air, stereo(frames, sine(14_000.0, 0.25)))
        air.configure(0, 10_000, 50)
        val (out, _) = run(air, stereo(frames, sine(1000.0, 0.25)))
        assertFalse("idle after the fade", air.isActive)
        val tail = stereo(frames, sine(1000.0, 0.25))
        for (n in frames / 2 until frames) assertEquals("dry once idle", tail[n * 2], out[n * 2], 1e-9)
    }

    @Test fun settingsRoundTripAndPresets() {
        val context = RuntimeEnvironment.getApplication()
        val saved = AudioEffectSettings(enabled = true, dspMode = true, airDb = 5, airHz = 6000, deEss = 80)
        AudioEffectSettings.save(context, saved)
        val loaded = AudioEffectSettings.load(context)
        assertEquals(5, loaded.airDb); assertEquals(6000, loaded.airHz); assertEquals(80, loaded.deEss)
        assertTrue(loaded.hasAdvancedProcessing())
        AudioEffectSettings.save(context, saved.copy(airDb = 20, airHz = 13_000, deEss = 300))
        val clamped = AudioEffectSettings.load(context)
        assertEquals(8, clamped.airDb); assertEquals(12_000, clamped.airHz); assertEquals(100, clamped.deEss)

        val defaults = AudioEffectSettings()
        assertEquals(0, defaults.airDb); assertEquals(10_000, defaults.airHz); assertEquals(50, defaults.deEss)
        assertFalse(defaults.hasAdvancedProcessing())

        val byName = SoundPresets.YARIS.associateBy { it.name }
        for (name in listOf("Yaris · Rage", "Yaris · Trap Clean", "Yaris · Windows Down")) {
            val s = byName.getValue(name).apply(defaults)
            assertEquals(name, 3, s.airDb); assertEquals(name, 10_000, s.airHz); assertEquals(name, 60, s.deEss)
        }
        val melodic = byName.getValue("Yaris · Melodic / Vocals").apply(defaults)
        assertEquals(2, melodic.airDb); assertEquals(10_000, melodic.airHz); assertEquals(70, melodic.deEss)
        assertEquals(0, byName.getValue("Yaris · 808 Heavy").apply(defaults.copy(airDb = 6)).airDb)
    }

    @Test fun cpuSanity() {
        val frames = rate * 10
        val signal = stereo(frames) { i -> 0.2 * sin(2 * PI * 1000.0 * i / rate) + 0.1 * sin(2 * PI * 7000.0 * i / rate) }
        val air = shelf(4, 10_000, 60)
        repeat(3) { air.process(signal.copyOf(), frames) } // warm up the JIT
        val buffer = signal.copyOf()
        val start = System.nanoTime()
        air.process(buffer, frames)
        val ns = (System.nanoTime() - start).toDouble() / frames
        // 10 s of stereo 48 kHz; on a desktop JVM this is tens of ns per frame. The bound only
        // catches pathological regressions (e.g. per-sample log/pow or allocation).
        assertTrue("$ns ns per frame", ns < 2_000)
        println("AirShelf CPU: %.1f ns per stereo frame (%.3f %% of one core at 48 kHz on this machine)".format(ns, ns * rate / 1e9 * 100))
    }
}
