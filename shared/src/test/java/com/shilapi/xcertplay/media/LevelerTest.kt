package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class LevelerTest {
    private val rate = 48_000

    /** Runs [seconds] of a mono signal (copied to both channels) and returns the gain trace in dB, one value per block. */
    private class Rig(rate: Int, strength: Double = 1.0) {
        val leveler = Leveler(rate, 2).also { it.strength = strength }
        val frame = DoubleArray(2)
        var t = 0
        val trace = ArrayList<Double>()
        val tracePos = ArrayList<Int>()
        fun run(frames: Int, signal: (Int) -> Double) {
            repeat(frames) {
                val x = signal(t)
                frame[0] = x; frame[1] = x
                leveler.process(frame)
                t++
                if (t % leveler.blockFrames == 0) { trace += leveler.gainDb().toDouble(); tracePos += t }
            }
        }
        fun gainAt(frame: Int): Double = trace[tracePos.indexOfFirst { it >= frame }.coerceAtLeast(0)]
    }

    private fun amp(dbfs: Double) = Math.pow(10.0, dbfs / 20.0)
    private fun sine(hz: Double, t: Int) = sin(2 * PI * hz * t / rate)

    /** Synthetic "music": a few partials with a slow 3 Hz amplitude movement, about -20 dBFS peak. */
    private fun music(t: Int): Double {
        val env = 0.8 + 0.2 * sin(2 * PI * 3.0 * t / rate)
        return env * 2.0 * (0.05 * sine(110.0, t) + 0.03 * sine(440.0, t) + 0.02 * sine(1320.0, t) + 0.012 * sine(3100.0, t))
    }

    @Test fun quietToneRisesAtLeastEightDbWithinThreeSeconds() {
        val rig = Rig(rate)
        rig.run(rate * 3) { amp(-30.0) * sine(1000.0, it) }
        val g = rig.leveler.gainDb()
        println("quiet -30 dBFS: gain after 1 s ${rig.gainAt(rate)}, 2 s ${rig.gainAt(2 * rate)}, 3 s $g dB")
        assertTrue("quiet tone should rise >= 8 dB in 3 s, got $g", g >= 8.0)
        assertTrue("gain range is +-12 dB, got $g", g <= Leveler.MAX_GAIN_DB + 1e-6)
    }

    @Test fun loudToneFallsAtLeastSixDb() {
        val rig = Rig(rate)
        rig.run(rate * 3) { amp(-3.0) * sine(1000.0, it) }
        val g = rig.leveler.gainDb()
        println("loud -3 dBFS: gain after 1 s ${rig.gainAt(rate)}, 3 s $g dB")
        assertTrue("loud tone should fall >= 6 dB, got $g", g <= -6.0)
        rig.run(rate * 5) { amp(-3.0) * sine(1000.0, it) }
        assertTrue("gain range is +-12 dB, got ${rig.leveler.gainDb()}", rig.leveler.gainDb() >= -Leveler.MAX_GAIN_DB - 1e-6)
    }

    @Test fun twentyMillisecondGapDoesNotMakeTheGainJump() {
        val gapStart = rate * 5
        val gapEnd = gapStart + rate / 50
        val reference = Rig(rate).also { it.run(rate * 7) { t -> music(t) } }
        val gapped = Rig(rate).also { it.run(rate * 7) { t -> if (t in gapStart until gapEnd) 0.0 else music(t) } }
        var worst = 0.0
        for (i in reference.trace.indices) worst = maxOf(worst, abs(reference.trace[i] - gapped.trace[i]))
        val before = gapped.gainAt(gapStart)
        val after = gapped.trace.subList(gapped.tracePos.indexOfFirst { it >= gapEnd }, gapped.tracePos.indexOfFirst { it >= gapEnd + rate / 2 })
        val jump = after.maxOf { abs(it - before) }
        println("20 ms gap: gain before ${before}, max deviation in 500 ms after ${jump} dB, max diff vs no-gap $worst dB")
        assertTrue("gain should not jump after a 20 ms gap, moved $jump dB", jump < 1.0)
        assertTrue("gap should not change the trajectory, diff $worst dB", worst < 1.0)
    }

    @Test fun twoSecondsOfDigitalSilenceDoNotRaiseTheGain() {
        // From cold: silence alone never moves the gain.
        val cold = Rig(rate).also { it.run(rate * 2) { 0.0 } }
        assertEquals(0.0, cold.trace.maxOf { abs(it) }, 1e-9)
        // Mid-program: a quiet tone is still pulling the gain up when the silence starts.
        val rig = Rig(rate)
        rig.run(rate) { amp(-30.0) * sine(1000.0, it) }
        val atStart = rig.leveler.gainDb()
        rig.run(rate * 2) { 0.0 }
        val atEnd = rig.leveler.gainDb()
        val maxDuring = rig.trace.takeLast(rate * 2 / rig.leveler.blockFrames).maxOrNull()!!
        println("2 s silence: gain at start $atStart, max during ${maxDuring}, at end $atEnd dB")
        assertTrue("silence must not raise gain ($atStart -> $maxDuring)", maxDuring <= atStart + 0.05)
        // And the program resumes without a jump.
        rig.run(rate / 4) { amp(-30.0) * sine(1000.0, it) }
        assertTrue("resume must be smooth", abs(rig.leveler.gainDb() - atEnd) < 1.5)
    }

    @Test fun kickDrumBurstsDoNotPumpThePad() {
        // -20 dBFS pad (three partials), 60 Hz kick bursts at -3 dBFS peak, 120 BPM, 80 ms decay.
        val period = rate / 2
        val signal = { t: Int ->
            val pad = 0.1 * (0.5 * sine(400.0, t) + 0.3 * sine(1000.0, t) + 0.2 * sine(2500.0, t))
            val k = t % period
            val kick = 0.7 * exp(-k / (0.08 * rate)) * sin(2 * PI * 60.0 * k / rate)
            pad + kick
        }
        val rig = Rig(rate)
        rig.run(rate * 12, signal)
        val from = rig.tracePos.indexOfFirst { it >= rate * 5 }
        val steady = rig.trace.subList(from, rig.trace.size)
        val swing = steady.max() - steady.min()
        println("kick + pad: gain ${steady.min()}..${steady.max()} dB, swing $swing dB")
        assertTrue("pad modulation (gain swing) should stay under 1.5 dB, got $swing", swing <= 1.5)
    }

    @Test fun releaseIsFastAfterShortBurstsAndSlowAfterLongLoudPassages() {
        /** Returns (seconds to come back within 1 dB of the pre-burst gain, deepest gain reduction). */
        fun recovery(burstSeconds: Double): Pair<Double, Double> {
            val rig = Rig(rate)
            val base = { t: Int -> amp(-22.0) * sine(1000.0, t) }
            rig.run(rate * 6, base)
            val before = rig.leveler.gainDb().toDouble()
            rig.run((rate * burstSeconds).toInt()) { amp(-10.0) * sine(1000.0, it) }
            val burstStart = rig.trace.size
            val start = rig.t
            rig.run(rate * 20, base)
            val dip = before - rig.trace.subList(burstStart, rig.trace.size).min()
            val i = rig.tracePos.indexOfFirst { it > start }
            val back = (i until rig.trace.size).firstOrNull { rig.trace[it] >= before - 1.0 } ?: return Double.MAX_VALUE to dip
            return (rig.tracePos[back] - start).toDouble() / rate to dip
        }
        val (short, shortDip) = recovery(0.3)
        val (long, longDip) = recovery(10.0)
        println("recovery to within 1 dB: after 0.3 s burst ${short} s (dip $shortDip dB), after 10 s loud passage ${long} s (dip $longDip dB)")
        assertTrue("short burst should recover quickly, took $short s", short < 2.0)
        assertTrue("a 0.3 s burst should only dent the gain, dipped $shortDip dB", shortDip < 3.5)
        assertTrue("long loud passage should recover slower ($long s vs $short s)", long > short * 3)
    }

    @Test fun equalizerDspReportsLevelerGainAndResetsWhenSwitchedOn() {
        val dsp = EqualizerDsp(rate, 2)
        val settings = AudioEffectSettings(enabled = true, dspMode = true, autoLevel = true, autoLevelAmount = 100)
        dsp.configure(settings, softClip = false)
        val data = ByteArray(rate * 4 * 2)
        for (i in 0 until rate * 2) {
            val v = (sine(1000.0, i) * amp(-30.0) * 32767).toInt()
            for (ch in 0 until 2) { val o = (i * 2 + ch) * 2; data[o] = v.toByte(); data[o + 1] = (v shr 8).toByte() }
        }
        dsp.process(data, 0, data.size)
        assertTrue("UI gain should show the boost, got ${dsp.autoLevelGainDb()}", dsp.autoLevelGainDb() > 4f)
        dsp.configure(settings.copy(autoLevel = false), softClip = false)
        assertEquals(0f, dsp.autoLevelGainDb(), 0f)
        dsp.configure(settings, softClip = false)
        assertEquals(0f, dsp.autoLevelGainDb(), 0f)
    }
}
