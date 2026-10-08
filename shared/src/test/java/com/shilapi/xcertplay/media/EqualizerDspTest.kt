package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class EqualizerDspTest {
    private fun tone(hz: Double, rate: Int, frames: Int, amplitude: Double = 0.25): ByteArray {
        val data = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * hz * i / rate) * amplitude * 32767).toInt()
            for (ch in 0 until 2) {
                val o = (i * 2 + ch) * 2
                data[o] = v.toByte(); data[o + 1] = (v shr 8).toByte()
            }
        }
        return data
    }

    private fun rms(data: ByteArray, from: Int): Double {
        var sum = 0.0; var n = 0
        var i = from
        while (i + 1 < data.size) {
            val v = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff)).toDouble() / 32768
            sum += v * v; n++; i += 2
        }
        return sqrt(sum / n)
    }

    @Test fun flatSettingsLeaveSamplesUntouched() {
        val dsp = EqualizerDsp(48000, 2)
        dsp.configure(FloatArray(12), limiter = false)
        val original = tone(1000.0, 48000, 4800)
        val copy = original.copyOf()
        dsp.process(copy, 0, copy.size)
        assertTrue(original.contentEquals(copy))
    }

    @Test fun boostingTheOneKilohertzBandRaisesAOneKilohertzTone() {
        val dsp = EqualizerDsp(48000, 2)
        val gains = FloatArray(12); gains[5] = 6f // 1 kHz
        dsp.configure(gains, limiter = false)
        val data = tone(1000.0, 48000, 48000)
        val before = rms(data, 20000)
        dsp.process(data, 0, data.size)
        val after = rms(data, 20000)
        val gainDb = 20 * Math.log10(after / before)
        assertTrue("expected about +6 dB, got $gainDb", gainDb > 5.0 && gainDb < 7.0)
    }

    @Test fun cuttingAFarBandLeavesTheToneAlone() {
        val dsp = EqualizerDsp(48000, 2)
        val gains = FloatArray(12); gains[0] = -12f // 31 Hz
        dsp.configure(gains, limiter = false)
        val data = tone(4000.0, 48000, 48000)
        val before = rms(data, 20000)
        dsp.process(data, 0, data.size)
        val after = rms(data, 20000)
        val gainDb = 20 * Math.log10(after / before)
        assertTrue("expected about 0 dB, got $gainDb", gainDb > -0.5 && gainDb < 0.5)
    }

    @Test fun limiterKeepsBoostedPeaksBelowFullScale() {
        val dsp = EqualizerDsp(48000, 2)
        val gains = FloatArray(12); gains[5] = 12f
        dsp.configure(gains, limiter = true)
        val data = tone(1000.0, 48000, 48000, amplitude = 0.9)
        dsp.process(data, 0, data.size)
        var peak = 0
        var i = 40000
        while (i + 1 < data.size) { val v = (data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff); if (Math.abs(v) > peak) peak = Math.abs(v); i += 2 }
        assertTrue("peak $peak should be limited to about 98.5% of full scale", peak in 31000..32400)
        assertEquals(12, EqualizerDsp.BANDS)
    }
}

class HeadroomTest {
    @Test fun deviceBoostLowersTheLevelByTheSameAmount() {
        val s = AudioEffectSettings(enabled = true, bandLevelsMb = listOf(400, 0, 0, 0, 0))
        val gain = s.headroomGain()
        assertTrue("expected -4 dB (0.63), got $gain", gain > 0.62f && gain < 0.64f)
        assertEquals(1f, AudioEffectSettings(enabled = false, bandLevelsMb = listOf(400)).headroomGain())
        assertEquals(1f, AudioEffectSettings(enabled = true, bandLevelsMb = listOf(-400, 0)).headroomGain())
    }

    @Test fun scaleHalvesSamples() {
        val data = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte()) // +16384, -16384
        EqualizerDsp.scale(data, 0, data.size, 0.5f)
        val a = (data[1].toInt() shl 8) or (data[0].toInt() and 0xff)
        val b = (data[3].toInt() shl 8) or (data[2].toInt() and 0xff)
        assertEquals(8192, a); assertEquals(-8192, b)
    }
}
