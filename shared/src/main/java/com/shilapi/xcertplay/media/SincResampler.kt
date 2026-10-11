package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Polyphase windowed-sinc sample-rate converter for interleaved float audio, with state carried
 * across calls. 32 taps, Kaiser window (β 8.6, ~87 dB), 256 phases with linear interpolation:
 * about 3 million multiply-adds per second for stereo at 48 kHz, roughly the cost of two
 * equalizer bands. Used so DiPlay feeds the hardware at its own rate instead of letting Android's
 * mixer resample.
 *
 * The cutoff is placed from where images actually land, not at a fixed fraction of Nyquist.
 * 44.1→48 kHz: the image of content at f sits at 44.1 − f and folds to 3.9 + f kHz, which is
 * audible only for images above 28 kHz (out − 20 kHz). Centring the 7.6 kHz Kaiser transition
 * under 28 kHz keeps the passband flat to 20 kHz (was −4.6 dB at 20 kHz) with every audible
 * image still ~85 dB down. 48→44.1 kHz: the replica of content at f sits at 48 − f and folds to
 * f − 3.9 kHz, so content up to 21.5 kHz is protected (stopband from 26.5 kHz): −0.03 dB at
 * 19 kHz and −0.35 dB at 20 kHz (was −1.7 / −4.7 dB). Input rates under 40 kHz, whose images land
 * directly in the audible band, keep the old conservative cutoff.
 *
 * Only the pairs in [supports] are qualified: a fixed 32-tap kernel cannot reject aliases for large
 * downsampling ratios (48→16 kHz measured −18 dB), so for anything else the track opens at the
 * stream's rate and Android's own converter does the work.
 */
class SincResampler(val inRate: Int, val outRate: Int, private val channels: Int) {
    private val step = inRate.toDouble() / outRate
    private val table: FloatArray
    /** Difference to the next phase, so the interpolated coefficient is one multiply-add. */
    private val slope: FloatArray
    /** History of the last [TAPS] input frames followed by the current block. */
    private var history = FloatArray((TAPS + 4096) * channels)
    private var historyFrames = TAPS // valid frames in history (the first TAPS are the overlap)
    private var position = TAPS / 2.0 // read position in history frames
    var framesIn = 0L; private set
    var framesOut = 0L; private set

    init {
        require(inRate > 0 && outRate > 0 && channels in 1..2)
        val cutoff = cutoffFor(inRate, outRate) // −6 dB frequency as a fraction of the input rate
        table = FloatArray((PHASES + 1) * TAPS)
        slope = FloatArray(PHASES * TAPS)
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
        for (i in slope.indices) slope[i] = table[i + TAPS] - table[i]
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
            val first = base - half + 1
            // Interpolate each coefficient once per output frame and use it for both channels.
            if (channels == 2) {
                var left = 0f; var right = 0f
                var idx = first * 2
                for (tap in 0 until TAPS) {
                    val c = table[k0 + tap] + slope[k0 + tap] * mix
                    left += history[idx] * c
                    right += history[idx + 1] * c
                    idx += 2
                }
                output[outIndex] = left; output[outIndex + 1] = right
            } else {
                var acc = 0f
                var idx = first
                for (tap in 0 until TAPS) { acc += history[idx] * (table[k0 + tap] + slope[k0 + tap] * mix); idx++ }
                output[outIndex] = acc
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
        /** Stream rates the phone or CarPlay sends and the hardware rates this kernel is qualified for. */
        private val SUPPORTED_IN = intArrayOf(32_000, 44_100, 48_000)
        private val SUPPORTED_OUT = intArrayOf(44_100, 48_000)

        /** True when the kernel's passband and stopband have been verified for this conversion. */
        fun supports(inRate: Int, outRate: Int): Boolean =
            inRate == outRate || (inRate in SUPPORTED_IN && outRate in SUPPORTED_OUT)
        private const val BETA = 8.6 // Kaiser: ~−87 dB sidelobes
        /** Upper edge of the band whose images and aliases must stay inaudible. */
        private const val AUDIBLE_HZ = 20_000.0
        /** Highest content frequency whose replica must not fold back audibly when downsampling. */
        private const val DOWN_PROTECT_HZ = 21_500.0

        /**
         * Kernel cutoff (fraction of the input rate) for a conversion [inRate] → [outRate]: the
         * stopband starts where the first image or replica that folds back below the protected
         * band would sit, and the Kaiser transition is centred under it.
         */
        internal fun cutoffFor(inRate: Int, outRate: Int): Double {
            val inHz = inRate.toDouble(); val outHz = outRate.toDouble()
            val minRate = minOf(inHz, outHz)
            val legacy = 0.46 * minRate / inHz
            val protect = minOf(AUDIBLE_HZ, 0.45 * minRate)
            // Images below 40 kHz input land straight in the audible band; keep the safe cutoff.
            if (outRate > inRate && inRate < 40_000) return legacy
            // Kaiser design: attenuation A from β, transition width from A and the window length.
            val attenuation = BETA / 0.1102 + 8.7
            val transitionHz = (attenuation - 7.95) / (2.285 * 2 * PI * TAPS) * inHz
            // Upsampling: the image of content at f folds to out − (in − f), audible only when the image
            // sits above out − 20 kHz, whatever the content. Downsampling: the replica at in − f folds
            // to f − (in − out), so it is the content that must be protected, up to 21.5 kHz.
            var stop = if (outRate > inRate) outHz - protect else inHz - minOf(DOWN_PROTECT_HZ, 0.45 * inHz)
            // Downsampling: content above out/2 aliases to out − f; protect if any can exist.
            if (outRate < inRate && outHz - protect < inHz / 2) stop = minOf(stop, outHz - protect)
            val cutoffHz = (stop - transitionHz / 2).coerceIn(legacy * inHz, 0.55 * minRate)
            return cutoffHz / inHz
        }
    }
}
