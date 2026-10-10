package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SoundProcessingTest {
    private val rate = 48_000

    private fun tone(hz: Double, frames: Int, amplitude: Double): ByteArray {
        val data = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * hz * i / rate) * amplitude * 32767).toInt()
            for (ch in 0 until 2) { val o = (i * 2 + ch) * 2; data[o] = v.toByte(); data[o + 1] = (v shr 8).toByte() }
        }
        return data
    }

    private fun samples(data: ByteArray, from: Int): DoubleArray {
        val out = ArrayList<Double>()
        var i = from
        while (i + 1 < data.size) { out += ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff)) / 32768.0; i += 4 }
        return out.toDoubleArray()
    }

    private fun rms(x: DoubleArray) = sqrt(x.sumOf { it * it } / x.size)

    /** Magnitude of one frequency in the left channel, normalised so a full-scale sine reads 1.0. */
    private fun magnitude(x: DoubleArray, hz: Double): Double {
        var re = 0.0; var im = 0.0
        for (i in x.indices) { val w = 2 * PI * hz * i / rate; re += x[i] * cos(w); im -= x[i] * sin(w) }
        return 2 * sqrt(re * re + im * im) / x.size
    }

    private fun settings() = AudioEffectSettings(enabled = true, dspMode = true)

    @Test fun subsonicFilterRemovesTwentyHertzAndKeepsOneKilohertz() {
        val dsp = EqualizerDsp(rate, 2)
        dsp.configure(settings().copy(subsonicHz = 40), softClip = false)
        val low = tone(20.0, rate, 0.3); val before = rms(samples(low, rate))
        dsp.process(low, 0, low.size)
        val lowDb = 20 * Math.log10(rms(samples(low, rate)) / before)
        assertTrue("20 Hz should drop well over 12 dB, got $lowDb", lowDb < -12)

        val mid = EqualizerDsp(rate, 2).also { it.configure(settings().copy(subsonicHz = 40), softClip = false) }
        val tone1k = tone(1000.0, rate, 0.3); val midBefore = rms(samples(tone1k, rate))
        mid.process(tone1k, 0, tone1k.size)
        val midDb = 20 * Math.log10(rms(samples(tone1k, rate)) / midBefore)
        assertTrue("1 kHz should pass, got $midDb", midDb > -0.5 && midDb < 0.5)
    }

    @Test fun lowShelfRaisesBassAndLeavesTreble() {
        val dsp = EqualizerDsp(rate, 2)
        dsp.configure(settings().copy(parametric = listOf(ParametricBand.lowShelf(150, 6f))), softClip = false)
        val bass = tone(40.0, rate * 2, 0.2); val before = rms(samples(bass, rate))
        dsp.process(bass, 0, bass.size)
        val gain = 20 * Math.log10(rms(samples(bass, rate)) / before)
        assertTrue("expected about +6 dB at 40 Hz, got $gain", gain > 5 && gain < 7)

        val treble = EqualizerDsp(rate, 2).also { it.configure(settings().copy(parametric = listOf(ParametricBand.lowShelf(150, 6f))), softClip = false) }
        val hi = tone(6000.0, rate, 0.2); val hiBefore = rms(samples(hi, rate / 2))
        treble.process(hi, 0, hi.size)
        val hiGain = 20 * Math.log10(rms(samples(hi, rate / 2)) / hiBefore)
        assertTrue("6 kHz should be untouched, got $hiGain", hiGain > -0.5 && hiGain < 0.5)
    }

    @Test fun bassEnhancerAddsHarmonicsAboveTheCrossover() {
        val dsp = EqualizerDsp(rate, 2)
        dsp.configure(settings().copy(bassEnhance = 80, bassEnhanceHz = 100), softClip = false)
        val data = tone(50.0, rate * 2, 0.25)
        dsp.process(data, 0, data.size)
        val out = samples(data, rate)
        val fundamental = magnitude(out, 50.0)
        val second = magnitude(out, 100.0)
        val third = magnitude(out, 150.0)
        assertTrue("fundamental should remain, got $fundamental", fundamental > 0.2)
        assertTrue("harmonics should appear (2nd=$second 3rd=$third)", second > 0.02 || third > 0.02)
    }

    @Test fun autoLevelRaisesQuietAndLowersLoud() {
        val quiet = EqualizerDsp(rate, 2)
        quiet.configure(settings().copy(autoLevel = true, autoLevelAmount = 100), softClip = false)
        val q = tone(440.0, rate * 6, 0.03)
        quiet.process(q, 0, q.size)
        val quietGain = 20 * Math.log10(rms(samples(q, rate * 5 * 4)) / 0.03 * sqrt(2.0))
        assertTrue("quiet tone should come up, got $quietGain dB", quietGain > 6)

        val loud = EqualizerDsp(rate, 2)
        loud.configure(settings().copy(autoLevel = true, autoLevelAmount = 100), softClip = false)
        val l = tone(440.0, rate * 6, 0.8)
        loud.process(l, 0, l.size)
        val loudGain = 20 * Math.log10(rms(samples(l, rate * 5 * 4)) / 0.8 * sqrt(2.0))
        assertTrue("loud tone should come down, got $loudGain dB", loudGain < -4)
    }

    @Test fun parametricBandsRoundTripThroughPreferences() {
        val bands = listOf(ParametricBand.peak(170, -3f, 1.2f), ParametricBand.highShelf(9000, 2f))
        val decoded = ParametricBand.decodeList(bands.joinToString(",") { it.encode() })
        assertEquals(bands, decoded)
        assertEquals("Peak 170 Hz -3.0 dB Q 1.2", bands[0].describe())
    }

    @Test fun yarisPresetsApplyTheWholeChain() {
        val rage = SoundPresets.YARIS.first { it.name.contains("Rage") }.apply(AudioEffectSettings())
        assertTrue(rage.enabled && rage.dspMode && rage.limiter)
        assertEquals(35, rage.subsonicHz)
        assertEquals(70, rage.bassEnhance)
        assertEquals(3, rage.parametric.size)
        assertTrue(rage.hasAdvancedProcessing())
        assertTrue(SoundPresets.YARIS.map { it.name }.distinct().size == SoundPresets.YARIS.size)
    }
}
