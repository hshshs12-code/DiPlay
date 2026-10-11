package com.shilapi.xcertplay.media

import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class LoudnessCompensatorTest {
    @After fun resetLevel() { LoudnessCompensator.listeningLevelDb = 0.0 }

    /** Interleaved stereo doubles: sum of (hz, amplitude) sines. */
    private fun signal(rate: Int, frames: Int, vararg tones: Pair<Double, Double>): DoubleArray {
        val out = DoubleArray(frames * 2)
        for (i in 0 until frames) {
            var v = 0.0
            for ((hz, a) in tones) v += a * sin(2 * PI * hz * i / rate)
            out[i * 2] = v; out[i * 2 + 1] = v
        }
        return out
    }

    private fun rmsLeft(x: DoubleArray, fromFrame: Int, toFrame: Int): Double {
        var sum = 0.0
        for (i in fromFrame until toFrame) sum += x[i * 2] * x[i * 2]
        return sqrt(sum / (toFrame - fromFrame))
    }

    /** Steady-state gain in dB of one tone at a fixed listening level and amount. */
    private fun toneGainDb(hz: Double, levelDb: Double, amount: Int = 100, rate: Int = 48_000): Double {
        LoudnessCompensator.listeningLevelDb = levelDb
        val comp = LoudnessCompensator(rate, 2).also { it.configure(true, amount) }
        val frames = rate * 2
        val input = signal(rate, frames, hz to 0.1)
        val output = input.copyOf()
        comp.process(output, frames)
        return 20 * log10(rmsLeft(output, rate, frames) / rmsLeft(input, rate, frames))
    }

    @Test fun referenceLevelIsBitExact() {
        LoudnessCompensator.listeningLevelDb = 0.0
        val comp = LoudnessCompensator(48_000, 2).also { it.configure(true, 100) }
        val input = signal(48_000, 48_000, 50.0 to 0.3, 1000.0 to 0.2, 12_000.0 to 0.1)
        val output = input.copyOf()
        assertFalse("idle stage must not touch the samples", comp.process(output, 48_000))
        assertArrayEquals(input, output, 0.0)
        // Louder than the reference is never cut: still a pass-through.
        LoudnessCompensator.listeningLevelDb = 6.0
        assertFalse(comp.process(output, 48_000))
        assertArrayEquals(input, output, 0.0)
    }

    @Test fun chainAtReferenceLevelMatchesTheChainWithoutLoudness() {
        LoudnessCompensator.listeningLevelDb = 0.0
        val base = AudioEffectSettings(enabled = true, dspMode = true, limiter = true, dspGainsDb = List(12) { if (it == 1) 3 else 0 })
        val with = EqualizerDsp(48_000, 2).also { it.configure(base.copy(loudnessComp = true, loudnessCompAmount = 100), softClip = false) }
        val without = EqualizerDsp(48_000, 2).also { it.configure(base, softClip = false) }
        assertTrue("loudness forces the limiter", with.limiter)
        val a = pcm(signal(48_000, 48_000, 60.0 to 0.3, 2000.0 to 0.2))
        val b = a.copyOf()
        with.process(a, 0, a.size); without.process(b, 0, b.size)
        assertArrayEquals(b, a)
    }

    @Test fun minusTwentyDbLiftsBassSixAndTrebleTwo() {
        for (rate in intArrayOf(44_100, 48_000)) {
            val low = toneGainDb(50.0, -20.0, rate = rate)
            val high = toneGainDb(12_000.0, -20.0, rate = rate)
            println("loudness @-20 dB, $rate Hz: 50 Hz ${"%+.2f".format(low)} dB, 12 kHz ${"%+.2f".format(high)} dB")
            assertEquals("50 Hz at -20 dB ($rate)", 6.0, low, 1.0)
            assertEquals("12 kHz at -20 dB ($rate)", 2.0, high, 0.7)
        }
    }

    @Test fun minusFortyDbLiftsBassTwelveAndTrebleFour() {
        for (rate in intArrayOf(44_100, 48_000)) {
            val low = toneGainDb(50.0, -40.0, rate = rate)
            val high = toneGainDb(12_000.0, -40.0, rate = rate)
            println("loudness @-40 dB, $rate Hz: 50 Hz ${"%+.2f".format(low)} dB, 12 kHz ${"%+.2f".format(high)} dB")
            assertEquals("50 Hz at -40 dB ($rate)", 12.0, low, 1.0)
            assertEquals("12 kHz at -40 dB ($rate)", 4.0, high, 0.7)
        }
    }

    @Test fun amountScalesAndCapsHold() {
        assertEquals(3.1, LoudnessCompensator.lowShelfDb(-20.0, 0.5), 1e-9)
        assertEquals(0.0, LoudnessCompensator.lowShelfDb(-20.0, 0.0), 0.0)
        assertEquals(0.0, LoudnessCompensator.lowShelfDb(3.0, 1.0), 0.0)
        assertEquals(LoudnessCompensator.MAX_LOW_DB, LoudnessCompensator.lowShelfDb(-60.0, 1.0), 1e-9)
        assertEquals(LoudnessCompensator.MAX_LOW_DB, LoudnessCompensator.lowShelfDb(-200.0, 1.0), 1e-9)
        assertTrue(LoudnessCompensator.highShelfDb(-60.0, 1.0) <= LoudnessCompensator.MAX_HIGH_DB)
        // Monotone in level: quieter never means less boost.
        var lastLow = 0.0; var lastHigh = 0.0
        for (l in 0 downTo -60) {
            val low = LoudnessCompensator.lowShelfDb(l.toDouble(), 1.0); val high = LoudnessCompensator.highShelfDb(l.toDouble(), 1.0)
            assertTrue(low >= lastLow && high >= lastHigh)
            lastLow = low; lastHigh = high
        }
        val half = toneGainDb(50.0, -40.0, amount = 50)
        assertEquals(6.0, half, 1.0)
    }

    @Test fun oneKilohertzStaysPutAtEveryLevel() {
        for (level in 0 downTo -60 step 10) {
            val g = toneGainDb(1000.0, level.toDouble())
            assertTrue("1 kHz moved by $g dB at $level dB", abs(g) < 0.5)
        }
    }

    @Test fun volumeJumpRampsWithoutClicks() {
        val rate = 48_000
        val comp = LoudnessCompensator(rate, 2).also { it.configure(true, 100) }
        val frames = rate * 2
        val music = signal(rate, frames, 50.0 to 0.25, 1000.0 to 0.1, 9000.0 to 0.03)
        val out = music.copyOf()
        val block = 480
        val jumpAt = rate / 2
        val lowAt = mutableMapOf<Int, Double>()
        var done = 0
        while (done < frames) {
            if (done == jumpAt) LoudnessCompensator.listeningLevelDb = -30.0
            val chunk = minOf(block, frames - done)
            val part = out.copyOfRange(done * 2, (done + chunk) * 2)
            comp.process(part, chunk)
            System.arraycopy(part, 0, out, done * 2, part.size)
            done += chunk
            lowAt[done - jumpAt] = comp.lowShelfDb()
        }
        val target = LoudnessCompensator.lowShelfDb(-30.0, 1.0)
        // ~200 ms linear ramp: about half-way after 100 ms, there at 200 ms.
        val mid = lowAt.getValue(rate / 10)
        assertTrue("half-way after 100 ms, got $mid of $target", mid > target * 0.35 && mid < target * 0.65)
        assertEquals(target, lowAt.getValue(rate / 5 + block), 1e-9)
        // Steps and curvature during the transition never exceed those of the settled, boosted music.
        fun maxDiff(from: Int, to: Int, order: Int): Double {
            var m = 0.0
            for (i in from until to) {
                val d = if (order == 1) out[i * 2] - out[(i - 1) * 2] else out[i * 2] - 2 * out[(i - 1) * 2] + out[(i - 2) * 2]
                m = maxOf(m, abs(d))
            }
            return m
        }
        for (order in 1..2) {
            val transition = maxDiff(jumpAt - 100, jumpAt + rate / 5 + block, order)
            val settled = maxDiff(frames - rate / 2, frames, order)
            println("ramp order $order: transition max ${"%.5f".format(transition)}, settled max ${"%.5f".format(settled)}")
            assertTrue("order-$order step $transition exceeds the music's own $settled", transition <= settled * 1.01)
        }
    }

    @Test fun switchingOffFadesOutAndThenStopsProcessing() {
        LoudnessCompensator.listeningLevelDb = -30.0
        val rate = 48_000
        val comp = LoudnessCompensator(rate, 2).also { it.configure(true, 100) }
        val buffer = signal(rate, rate, 50.0 to 0.2)
        assertTrue(comp.process(buffer, rate))
        comp.configure(false, 100)
        comp.process(signal(rate, rate / 10, 50.0 to 0.2), rate / 10)
        assertTrue("still fading after 100 ms", comp.lowShelfDb() > 0.0 && comp.busy)
        comp.process(signal(rate, rate / 5, 50.0 to 0.2), rate / 5)
        assertEquals(0.0, comp.lowShelfDb(), 0.0)
        comp.process(signal(rate, rate / 10, 50.0 to 0.2), rate / 10)
        val idle = signal(rate, 4800, 50.0 to 0.2)
        val copy = idle.copyOf()
        assertFalse("idle after the tail", comp.process(copy, 4800))
        assertArrayEquals(idle, copy, 0.0)
    }

    @Test fun chainPlacesLoudnessAndLiftsBass() {
        LoudnessCompensator.listeningLevelDb = -20.0
        val dsp = EqualizerDsp(48_000, 2)
        dsp.configure(AudioEffectSettings(enabled = true, dspMode = true, loudnessComp = true, loudnessCompAmount = 100), softClip = false)
        assertTrue(dsp.limiter)
        val input = signal(48_000, 96_000, 50.0 to 0.1)
        val data = pcm(input)
        dsp.process(data, 0, data.size)
        val out = DoubleArray(input.size) { ((data[it * 2 + 1].toInt() shl 8) or (data[it * 2].toInt() and 0xff)) / 32768.0 }
        val gain = 20 * log10(rmsLeft(out, 48_000, 96_000) / rmsLeft(input, 48_000, 96_000))
        assertEquals(6.0, gain, 1.0)
        // Off in the settings (or the chain off) means no loudness at all.
        val off = EqualizerDsp(48_000, 2).also { it.configure(AudioEffectSettings(enabled = true, dspMode = true), softClip = false) }
        assertFalse(off.limiter)
    }

    @Test fun cpuCostIsTwoBiquadsPerChannel() {
        LoudnessCompensator.listeningLevelDb = -30.0
        val rate = 48_000
        val comp = LoudnessCompensator(rate, 2).also { it.configure(true, 60) }
        val seconds = 10
        val buffer = signal(rate, rate * seconds, 60.0 to 0.2, 3000.0 to 0.1)
        repeat(2) { comp.process(buffer.copyOf(), rate * seconds) } // warm-up / JIT
        val start = System.nanoTime()
        comp.process(buffer, rate * seconds)
        val ms = (System.nanoTime() - start) / 1e6
        val percent = ms / (seconds * 1000.0) * 100
        println("loudness compensator: ${"%.1f".format(ms)} ms for $seconds s stereo 48 kHz = ${"%.3f".format(percent)} % of real time (host JVM)")
        assertTrue("too slow: $percent % of real time", percent < 2.0)
    }

    @Test fun presetsCarryLoudness() {
        fun preset(name: String) = SoundPresets.YARIS.first { it.name.contains(name) }.apply(AudioEffectSettings())
        val night = preset("Night Drive")
        assertTrue(night.loudnessComp); assertEquals(70, night.loudnessCompAmount)
        for (name in listOf("Rage", "Trap Clean")) {
            val s = preset(name)
            assertTrue(name, s.loudnessComp); assertEquals(name, 50, s.loudnessCompAmount)
        }
        assertFalse(preset("Flat Corrected").loudnessComp)
        assertTrue(AudioEffectSettings(loudnessComp = true).hasAdvancedProcessing())
        assertFalse(AudioEffectSettings(loudnessComp = false).hasAdvancedProcessing())
    }

    private fun pcm(x: DoubleArray): ByteArray {
        val data = ByteArray(x.size * 2)
        for (i in x.indices) {
            val v = Math.round(x[i] * 32767).toInt().coerceIn(-32768, 32767)
            data[i * 2] = v.toByte(); data[i * 2 + 1] = (v shr 8).toByte()
        }
        return data
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LoudnessSettingsTest {
    @Test fun loudnessSettingsRoundTripThroughPreferences() {
        val context = RuntimeEnvironment.getApplication()
        val defaults = AudioEffectSettings.load(context)
        assertFalse(defaults.loudnessComp)
        assertEquals(60, defaults.loudnessCompAmount)
        assertEquals(0, defaults.loudnessCompReferenceStep)
        val saved = defaults.copy(enabled = true, dspMode = true, loudnessComp = true, loudnessCompAmount = 80, loudnessCompReferenceStep = 12)
        AudioEffectSettings.save(context, saved)
        val loaded = AudioEffectSettings.load(context)
        assertEquals(saved, loaded)
        val prefs = context.getSharedPreferences("diplay_audio_effects", 0)
        assertTrue(prefs.getBoolean("loudness_comp", false))
        assertEquals(80, prefs.getInt("loudness_comp_amount", -1))
        assertEquals(12, prefs.getInt("loudness_comp_reference", -1))
        assertTrue(loaded.hasAdvancedProcessing())
    }
}
