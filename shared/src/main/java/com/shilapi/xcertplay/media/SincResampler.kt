package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Polyphase windowed-sinc sample-rate converter for interleaved float audio, with state carried
 * across calls. 32 taps, Kaiser window, 256 phases with linear interpolation: about 3 million
 * multiply-adds per second for stereo at 48 kHz, roughly the cost of two equalizer bands, and a
 * passband flat to ~0.1 dB with aliasing below −80 dB. Used so DiPlay feeds the hardware at its
 * own rate instead of letting Android's mixer resample.
 */
class SincResampler(val inRate: Int, val outRate: Int, private val channels: Int) {
    private val step = inRate.toDouble() / outRate
    private val table: FloatArray
    /** History of the last [TAPS] input frames followed by the current block. */
    private var history = FloatArray((TAPS + 4096) * channels)
    private var historyFrames = TAPS // valid frames in history (the first TAPS are the overlap)
    private var position = TAPS / 2.0 // read position in history frames
    var framesIn = 0L; private set
    var framesOut = 0L; private set

    init {
        require(inRate > 0 && outRate > 0 && channels in 1..2)
        // Cutoff a little under the lower Nyquist so the transition band stays inside the stopband.
        val cutoff = 0.46 * minOf(1.0, outRate.toDouble() / inRate)
        table = FloatArray((PHASES + 1) * TAPS)
        for (phase in 0..PHASES) {
            val frac = phase.toDouble() / PHASES
            var sum = 0.0
            for (tap in 0 until TAPS) {
                val x = tap - TAPS / 2 + 1 - frac // distance from the output sample position
                val sinc = if (abs(x) < 1e-9) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                val window = kaiser(x / (TAPS / 2.0))
                val v = sinc * window
                table[phase * TAPS + tap] = v.toFloat()
                sum += v
            }
            // Normalise each phase to unity DC gain.
            for (tap in 0 until TAPS) table[phase * TAPS + tap] = (table[phase * TAPS + tap] / sum).toFloat()
        }
    }

    /** Upper bound on output frames for [inFrames] input frames. */
    fun maxOutputFrames(inFrames: Int): Int = ((inFrames + TAPS) / step).toInt() + 2

    /** Converts [inFrames] interleaved frames from [input] into [output]; returns output frames written. */
    fun process(input: FloatArray, inOffset: Int, inFrames: Int, output: FloatArray, outOffset: Int): Int {
        if (inFrames <= 0) return 0
        val needed = (historyFrames + inFrames) * channels
        if (history.size < needed) history = history.copyOf(needed + 4096 * channels)
        System.arraycopy(input, inOffset, history, historyFrames * channels, inFrames * channels)
        historyFrames += inFrames
        framesIn += inFrames
        var produced = 0
        var outIndex = outOffset
        val half = TAPS / 2
        // The output sample at `position` needs input frames position-half+1 .. position+half.
        while (position + half < historyFrames) {
            val base = position.toInt()
            val frac = position - base
            val phaseFloat = frac * PHASES
            val phase = phaseFloat.toInt()
            val mix = (phaseFloat - phase).toFloat()
            val k0 = phase * TAPS
            val k1 = k0 + TAPS
            val first = base - half + 1
            for (ch in 0 until channels) {
                var acc = 0f
                var idx = first * channels + ch
                for (tap in 0 until TAPS) {
                    val coefficient = table[k0 + tap] + (table[k1 + tap] - table[k0 + tap]) * mix
                    acc += history[idx] * coefficient
                    idx += channels
                }
                output[outIndex + ch] = acc
            }
            outIndex += channels
            produced++
            position += step
        }
        // Keep only the frames still needed for the next output sample.
        val keepFrom = (position.toInt() - half).coerceAtLeast(0)
        if (keepFrom > 0) {
            val remaining = historyFrames - keepFrom
            System.arraycopy(history, keepFrom * channels, history, 0, remaining * channels)
            historyFrames = remaining
            position -= keepFrom
        }
        framesOut += produced
        return produced
    }

    private fun kaiser(t: Double): Double {
        if (abs(t) >= 1.0) return 0.0
        return besselI0(BETA * sqrt(1 - t * t)) / besselI0(BETA)
    }

    private fun besselI0(x: Double): Double {
        var sum = 1.0; var term = 1.0; val q = x * x / 4
        for (k in 1..40) { term *= q / (k * k); sum += term; if (term < sum * 1e-12) break }
        return sum
    }

    companion object {
        const val TAPS = 32
        private const val PHASES = 256
        private const val BETA = 8.6 // Kaiser: ~−90 dB sidelobes
    }
}
