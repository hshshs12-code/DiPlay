package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class BassClipperTest {
    private val rate = 48_000

    /** Level of a tone at [hz] in [x] (samples of one channel), in dB relative to amplitude 1. */
    private fun levelDb(x: DoubleArray, hz: Double): Double {
        var re = 0.0; var im = 0.0
        for (i in x.indices) { val w = 2 * PI * hz * i / rate; re += x[i] * cos(w); im -= x[i] * sin(w) }
        return 20 * log10(2 * sqrt(re * re + im * im) / x.size)
    }

    private fun pcm(frames: Int, f: (Int) -> Double): ByteArray {
        val data = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val v = (f(i) * 32767).toInt().coerceIn(-32768, 32767)
            data[i * 4] = v.toByte(); data[i * 4 + 1] = (v shr 8).toByte(); data[i * 4 + 2] = v.toByte(); data[i * 4 + 3] = (v shr 8).toByte()
        }
        return data
    }

    @Test fun holdsTheLowBandUnderTheCeilingAndLeavesTheMidsAlone() {
        val clipper = BassClipper(rate, 2)
        clipper.configure(3); clipper.snap()
        val frames = rate
        val out = DoubleArray(frames)
        val frame = DoubleArray(2)
        for (i in 0 until frames) {
            val v = 0.95 * sin(2 * PI * 40.0 * i / rate) + 0.2 * sin(2 * PI * 1000.0 * i / rate)
            frame[0] = v; frame[1] = v
            clipper.process(frame)
            out[i] = frame[0]
        }
        val tail = out.copyOfRange(frames / 2, frames)
        // Low band bounded: the 40 Hz component cannot exceed −3 dB (0.708) plus the 1 kHz ride-along.
        var peak = 0.0
        for (v in tail) peak = max(peak, abs(v))
        assertTrue("peak $peak should sit under the −3 dB ceiling plus the 1 kHz tone", peak < 0.708 + 0.2 + 0.02)
        assertEquals("1 kHz passes untouched", 20 * log10(0.2), levelDb(tail, 1000.0), 0.15)
        // A clipped sine keeps a fundamental above its peak (a square wave has 4/π × its amplitude), so
        // only the time-domain peak bounds it; the fundamental must simply have dropped from the input.
        assertTrue("40 Hz is reduced from its 0.95 input", levelDb(tail, 40.0) < 20 * log10(0.92))
    }

    @Test fun offIsBitTransparentAndOnFadesIn() {
        val clipper = BassClipper(rate, 2)
        clipper.configure(0); clipper.snap()
        assertFalse(clipper.active)
        val frame = doubleArrayOf(0.5, -0.5)
        clipper.process(frame)
        assertEquals(0.5, frame[0], 0.0); assertEquals(-0.5, frame[1], 0.0)
        clipper.configure(3)
        assertTrue(clipper.active)
        // The first frames after switching on are still the dry signal; the wet mix glides in.
        val first = doubleArrayOf(0.9, 0.9); clipper.process(first)
        assertEquals(0.9, first[0], 0.02)
    }

    /** The point of the stage: an 808 under a vocal no longer makes the limiter duck the vocal. */
    @Test fun chainKeepsTheVocalLevelUnderHeavyBass() {
        fun vocalLevel(clipDb: Int): Double {
            val s = AudioEffectSettings(enabled = true, dspMode = true, limiter = true, bassClipDb = clipDb,
                dspGainsDb = List(12) { if (it == 1) 6 else 0 })
            val dsp = EqualizerDsp(rate, 2).also { it.configure(s, softClip = false) }
            val frames = rate * 2
            val data = pcm(frames) { i -> 0.75 * sin(2 * PI * 55.0 * i / rate) + 0.22 * sin(2 * PI * 1000.0 * i / rate) }
            val out = FloatArray(frames * 2)
            dsp.processToFloat(data, 0, data.size, out)
            val left = DoubleArray(frames / 2) { out[2 * (frames / 2 + it)].toDouble() }
            return levelDb(left, 1000.0)
        }
        val input = 20 * log10(0.22)
        val ducked = vocalLevel(0)
        val kept = vocalLevel(3)
        println("1 kHz under a boosted 55 Hz 808: input %.2f dB, limiter alone %.2f dB, with bass clip %.2f dB".format(input, ducked, kept))
        assertTrue("without the clipper the limiter ducks the vocal (got ${input - ducked} dB)", input - ducked > 2.0)
        assertTrue("with the clipper the vocal stays within 0.7 dB (got ${input - kept} dB)", abs(input - kept) < 0.7)
    }

    @Test fun limiterHardOffWinsOverEveryForcingStage() {
        val forced = AudioEffectSettings(enabled = true, dspMode = true, bassEnhance = 60, autoLevel = true, loudnessComp = true)
        assertTrue(EqualizerDsp(rate, 2).also { it.configure(forced, softClip = false) }.limiter)
        val off = forced.copy(limiterOff = true)
        assertFalse(EqualizerDsp(rate, 2).also { it.configure(off, softClip = false) }.limiter)
        // Presets always come with the user's hard-off cleared.
        assertFalse(SoundPresets.YARIS.first().apply(off).limiterOff)
    }
}
