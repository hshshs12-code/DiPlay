package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class DriveTest {
    private val rate = 48_000

    private fun levelDb(x: DoubleArray, hz: Double): Double {
        var re = 0.0; var im = 0.0
        for (i in x.indices) { val w = 2 * PI * hz * i / rate; re += x[i] * cos(w); im -= x[i] * sin(w) }
        return 20 * log10(2 * sqrt(re * re + im * im) / x.size)
    }

    private fun run(settings: AudioEffectSettings, amplitude: Double): DoubleArray {
        val dsp = EqualizerDsp(rate, 2).also { it.configure(settings, softClip = false) }
        val frames = rate
        val data = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val v = (amplitude * sin(2 * PI * 1000.0 * i / rate) * 32767).toInt()
            data[i * 4] = v.toByte(); data[i * 4 + 1] = (v shr 8).toByte(); data[i * 4 + 2] = v.toByte(); data[i * 4 + 3] = (v shr 8).toByte()
        }
        val out = FloatArray(frames * 2)
        dsp.processToFloat(data, 0, data.size, out)
        return DoubleArray(frames / 2) { out[2 * (frames / 2 + it)].toDouble() }
    }

    @Test fun driveRaisesAQuietSignalByItsValue() {
        val base = AudioEffectSettings(enabled = true, dspMode = true, limiter = true)
        val quiet = levelDb(run(base, 0.1), 1000.0)
        val driven = levelDb(run(base.copy(driveDb = 6), 0.1), 1000.0)
        assertEquals(6.0, driven - quiet, 0.3)
    }

    @Test fun driveNeverPushesPastTheCeilingAndKeepsTheLimiterOnEvenWhenHardOff() {
        val s = AudioEffectSettings(enabled = true, dspMode = true, limiterOff = true, driveDb = 12)
        val dsp = EqualizerDsp(rate, 2).also { it.configure(s, softClip = false) }
        assertTrue("drive keeps the limiter on", dsp.limiter)
        val out = run(s, 0.9)
        val peak = out.maxOf { abs(it) }
        assertTrue("peak $peak must stay under the clip", peak <= TruePeakLimiter.CLIP + 1e-6)
        assertTrue("and the limiter is actually holding it near the ceiling", peak > 0.9)
    }

    @Test fun outputTrimLowersTheWholeChainAfterLimiting() {
        val base = AudioEffectSettings(enabled = true, dspMode = true, limiter = true)
        val reference = levelDb(run(base, 0.1), 1000.0)
        val trimmed = levelDb(run(base.copy(outputTrimDb = 6), 0.1), 1000.0)
        assertEquals(-6.0, trimmed - reference, 0.3)
        // A full-scale signal limited at the ceiling then trimmed sits 6 dB under it.
        val loud = run(base.copy(outputTrimDb = 6), 0.99)
        assertTrue(loud.maxOf { abs(it) } < TruePeakLimiter.CEILING * 0.55)
    }

    @Test fun presetsExposeDriveAndKeepTheUsersTrim() {
        val maxLoud = SoundPresets.YARIS.first { it.name.contains("Max Loud") }.apply(AudioEffectSettings(outputTrimDb = 4))
        assertEquals(6, maxLoud.driveDb)
        assertEquals("trim belongs to the car, not the preset", 4, maxLoud.outputTrimDb)
        assertEquals(0, SoundPresets.YARIS.first { it.name.contains("Rage") }.apply(AudioEffectSettings()).driveDb)
    }
}
