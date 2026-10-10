package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class OutputStageTest {
    private fun tone16(hz: Double, rate: Int, frames: Int, amplitude: Double): ByteArray {
        val data = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * hz * i / rate) * amplitude * 32767).toInt()
            for (ch in 0 until 2) { val o = (i * 2 + ch) * 2; data[o] = v.toByte(); data[o + 1] = (v shr 8).toByte() }
        }
        return data
    }

    private fun zeroCrossings(x: FloatArray, from: Int, channels: Int): Int {
        var count = 0
        var i = from * channels + channels
        while (i < x.size) { if ((x[i] >= 0) != (x[i - channels] >= 0)) count++; i += channels }
        return count
    }

    @Test fun resamplerKeepsPitchAndLevel() {
        val r = SincResampler(44_100, 48_000, 2)
        val frames = 44_100
        val input = FloatArray(frames * 2) { i -> (sin(2 * PI * 1000.0 * (i / 2) / 44_100) * 0.5).toFloat() }
        val output = FloatArray(r.maxOutputFrames(frames) * 2)
        val produced = r.process(input, 0, frames, output, 0)
        assertTrue("about 48000 frames expected, got $produced", produced in 47_900..48_010)
        val tail = output.copyOf(produced * 2)
        val crossings = zeroCrossings(tail, produced / 2, 2)
        val seconds = (produced - produced / 2) / 48_000.0
        val measuredHz = crossings / 2.0 / seconds
        assertTrue("pitch should stay 1 kHz, got $measuredHz", abs(measuredHz - 1000) < 5)
        var sum = 0.0; var n = 0
        for (i in produced until produced * 2 step 2) { sum += tail[i] * tail[i]; n++ }
        val rms = sqrt(sum / n)
        assertTrue("level should stay 0.5/√2 = 0.354, got $rms", abs(rms - 0.3536) < 0.004)
    }

    @Test fun resamplerIsContinuousAcrossBlocks() {
        val whole = SincResampler(44_100, 48_000, 1)
        val chunked = SincResampler(44_100, 48_000, 1)
        val input = FloatArray(20_000) { i -> (sin(2 * PI * 440.0 * i / 44_100) * 0.5).toFloat() }
        val a = FloatArray(whole.maxOutputFrames(input.size)); val na = whole.process(input, 0, input.size, a, 0)
        val b = FloatArray(a.size); var nb = 0; var off = 0
        val sizes = intArrayOf(1, 7, 100, 1000, 3, 4096)
        var k = 0
        while (off < input.size) {
            val take = minOf(sizes[k++ % sizes.size], input.size - off)
            nb += chunked.process(input, off, take, b, nb); off += take
        }
        assertEquals(na, nb)
        for (i in 0 until na) assertTrue("sample $i differs", abs(a[i] - b[i]) < 1e-5)
    }

    @Test fun floatStageIsExactWithoutDsp() {
        val stage = MediaOutputStage(48_000, 2, 48_000, floatOutput = true) { null }
        val data = tone16(1000.0, 48_000, 480, 0.5)
        val rendered = stage.render(data, 0, data.size)
        assertEquals(480 * 2 * 4, rendered.lengthBytes)
        val floats = rendered.floats!!
        for (i in 0 until 960) {
            val v = ((data[i * 2 + 1].toInt() shl 8) or (data[i * 2].toInt() and 0xff)) / 32768f
            assertEquals(v, floats[i], 0f)
        }
        assertTrue(stage.active && !stage.resampling && stage.trackFrameBytes == 8)
    }

    @Test fun dspRendersToFloatLikeToBytes() {
        val settings = AudioEffectSettings(enabled = true, dspMode = true, dspGainsDb = List(12) { if (it == 5) 6 else 0 })
        val a = EqualizerDsp(48_000, 2).also { it.configure(settings, false) }
        val b = EqualizerDsp(48_000, 2).also { it.configure(settings, false) }
        val data = tone16(1000.0, 48_000, 4800, 0.2)
        val copy = data.copyOf()
        a.process(copy, 0, copy.size)
        val floats = FloatArray(4800 * 2)
        assertEquals(9600, b.processToFloat(data, 0, data.size, floats))
        for (i in 2000 until 9600) {
            val v = ((copy[i * 2 + 1].toInt() shl 8) or (copy[i * 2].toInt() and 0xff)) / 32768f
            assertTrue("sample $i: ${floats[i]} vs $v", abs(floats[i] - v) < 2.5f / 32768f)
        }
    }

    @Test fun inactiveStageIsReported() {
        val stage = MediaOutputStage(44_100, 2, 0, floatOutput = false) { null }
        assertTrue(!stage.active)
        val resampled = MediaOutputStage(44_100, 2, 48_000, floatOutput = false) { null }
        assertTrue(resampled.active && resampled.resampling && resampled.trackFrameBytes == 4)
        val out = resampled.render(tone16(1000.0, 44_100, 4410, 0.5), 0, 4410 * 4)
        assertTrue("about 4800 frames, got ${out.lengthBytes / 4}", out.lengthBytes / 4 in 4700..4810)
    }
}
