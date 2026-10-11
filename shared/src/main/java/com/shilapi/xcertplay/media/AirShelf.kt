package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * De-essed "air": a high shelf (RBJ, slope 1) whose boost is pulled back while sibilance is present,
 * so the top octave can be lifted for small dash tweeters without making "s" sounds spit.
 *
 * Audio path, per channel: one shelf biquad designed at the full static gain, then a crossfade
 * `y = x + g·(shelf(x) − x)`. Because the shelf is minimum phase with unity gain at DC and gain G
 * at the top, the crossfade's high-frequency gain is `1 + g·(G − 1)`: g = 1 is the full shelf,
 * g = 0 is the dry signal and a small negative g dips slightly below flat. Only g moves at audio
 * rate, so there is no coefficient recomputation and no zipper noise; g is smoothed per sample.
 *
 * Control path (stereo-linked mono sum): a 5–9 kHz band-pass (2nd-order high-pass at 5 kHz into a
 * 2nd-order low-pass at 9 kHz) feeds a peak envelope that is compared with the broadband peak
 * envelope. Both attack in 1 ms. The broadband reference releases in 60 ms; the sibilance envelope
 * releases faster (20 ms). With equal releases the two envelopes of an "s" decay together after
 * it ends, their ratio stays put and the shelf would stay down until the reference reaches the
 * music's own level (~250 ms for a −6 dBFS "s" over −30 dBFS music); the faster band release lets
 * the detector let go within a few tens of milliseconds while the reference still holds steady.
 * When the band is above the reference by more than a threshold (−10 dB at de-ess 50), the shelf
 * gain is reduced in proportion to the excess, toward 0 dB and down to a small cut at de-ess 100.
 * The gain computer runs every [CONTROL_FRAMES] frames; g follows it with a 1 ms attack and a
 * 20 ms release one-pole per sample.
 *
 * Cost per frame: one biquad per channel plus two control biquads and two envelope updates; the
 * logarithms run once per control block. Configuration changes (gain, corner) are interpolated
 * over ~30 ms, and switching off fades g to zero before the stage goes idle.
 */
class AirShelf(private val sampleRate: Int, private val channels: Int) {
    /** Immutable configuration handed from the UI thread to the audio thread. */
    private class Config(val shelf: DoubleArray, val staticDb: Double, val deEss: Double, val on: Boolean)

    @Volatile private var pending: Config? = null
    @Volatile private var pendingSnap = false

    // Audio-thread state
    private var config = Config(identity(), 0.0, 0.0, false)
    private val shelf = identity()          // current (interpolated) shelf coefficients
    private var staticDb = 0.0              // current (interpolated) static shelf gain in dB
    private var interpolate = 0             // control blocks left in a configuration glide
    private val shelfState = DoubleArray(channels * 4)
    private val band = DoubleArray(10)
    private val bandState = DoubleArray(8)
    private var sibEnv = 0.0
    private var broadEnv = 0.0
    private var g = 0.0
    private var gTarget = 0.0
    private var counter = 0
    private var running = false

    private val envAttack = coefficient(ENV_ATTACK_S)
    private val sibRelease = coefficient(SIB_RELEASE_S)
    private val broadRelease = coefficient(BROAD_RELEASE_S)
    private val gainAttack = coefficient(GAIN_ATTACK_S)
    private val gainRelease = coefficient(GAIN_RELEASE_S)
    private val interpolateBlocks = max(1, (CONFIG_GLIDE_S * sampleRate / CONTROL_FRAMES).toInt())

    init {
        val hp = DoubleArray(5); val lp = DoubleArray(5)
        val nyquistSafe = sampleRate * 0.45
        highPass(hp, min(DETECT_LOW_HZ, nyquistSafe * 0.5), BUTTERWORTH_Q)
        lowPass(lp, min(DETECT_HIGH_HZ, nyquistSafe), BUTTERWORTH_Q)
        System.arraycopy(hp, 0, band, 0, 5); System.arraycopy(lp, 0, band, 5, 5)
    }

    /**
     * Sets the static shelf gain [airDb] (0 = off), its corner [airHz] and the de-ess amount
     * [deEss] 0..100 (0 = static shelf). [immediate] skips the glide (first configuration).
     */
    fun configure(airDb: Int, airHz: Int, deEss: Int, immediate: Boolean = false) {
        val db = airDb.coerceIn(0, MAX_DB).toDouble()
        val hz = airHz.toDouble().coerceIn(1000.0, sampleRate * 0.45)
        val on = db > 0.0
        val coefficients = if (on) highShelf(hz, db) else (pending ?: config).shelf.copyOf()
        pending = Config(coefficients, if (on) db else (pending ?: config).staticDb, deEss.coerceIn(0, 100) / 100.0, on)
        pendingSnap = immediate
    }

    /** True while the stage changes the signal (including the fade-out after switching off). */
    val isActive: Boolean get() = running || pending?.on == true

    /** Effective high-frequency gain of the stage right now, in dB (static gain minus de-essing). */
    fun gainDb(): Double {
        val top = 10.0.pow(staticDb / 20.0)
        return 20.0 * log10(max(1e-6, 1.0 + g * (top - 1.0)))
    }

    fun reset() {
        shelfState.fill(0.0); bandState.fill(0.0); sibEnv = 0.0; broadEnv = 0.0
    }

    /** Processes one interleaved frame ([channels] samples, full scale = 1.0) in place. */
    fun processFrame(frame: DoubleArray) {
        if (counter == 0) control()
        if (!running) return
        counter = (counter + 1) % CONTROL_FRAMES
        // Control path: stereo-linked mono sum through the 5–9 kHz band-pass.
        var mono = 0.0
        for (ch in 0 until channels) mono += frame[ch]
        mono /= channels
        val sib = abs(biquad(biquad(mono, band, 0, bandState, 0), band, 5, bandState, 4))
        val broad = abs(mono)
        sibEnv += (sib - sibEnv) * (if (sib > sibEnv) envAttack else sibRelease)
        broadEnv += (broad - broadEnv) * (if (broad > broadEnv) envAttack else broadRelease)
        g += (gTarget - g) * (if (gTarget < g) gainAttack else gainRelease)
        // Audio path: one shelf per channel, crossfaded with the dry signal by g.
        val c = shelf
        for (ch in 0 until channels) {
            val x = frame[ch]
            val s = biquad(x, c, 0, shelfState, ch * 4)
            frame[ch] = x + g * (s - x)
        }
    }

    /** Processes [frames] interleaved frames of [buffer] in place. */
    fun process(buffer: DoubleArray, frames: Int) {
        val frame = DoubleArray(channels)
        for (n in 0 until frames) {
            val at = n * channels
            for (ch in 0 until channels) frame[ch] = buffer[at + ch]
            processFrame(frame)
            for (ch in 0 until channels) buffer[at + ch] = frame[ch]
        }
    }

    /** Once per control block: pick up configuration, glide coefficients, run the gain computer. */
    private fun control() {
        pending?.let { next ->
            pending = null
            val wasRunning = running
            config = next
            if (pendingSnap || !wasRunning) {
                System.arraycopy(next.shelf, 0, shelf, 0, 5)
                staticDb = next.staticDb
                interpolate = 0
                if (!wasRunning) { reset(); g = if (pendingSnap) 1.0 else 0.0 }
            } else {
                interpolate = interpolateBlocks
            }
            if (next.on) running = true
        }
        if (!running) return
        val cfg = config
        if (interpolate > 0) {
            val alpha = 1.0 / interpolate
            for (k in 0 until 5) shelf[k] += (cfg.shelf[k] - shelf[k]) * alpha
            staticDb += (cfg.staticDb - staticDb) * alpha
            interpolate--
        }
        if (!cfg.on) {
            gTarget = 0.0
            if (abs(g) < IDLE_G && interpolate == 0) { running = false; g = 0.0; reset() }
            return
        }
        val top = 10.0.pow(staticDb / 20.0) - 1.0
        if (top < 1e-4) { gTarget = 0.0; return }
        val amount = cfg.deEss
        var targetDb = staticDb
        if (amount > 0.0 && sibEnv > SIB_GATE && broadEnv > BROAD_GATE) {
            val ratioDb = 20.0 * log10(sibEnv / broadEnv)
            val excess = ratioDb - (THRESHOLD_BASE_DB + THRESHOLD_RANGE_DB * amount)
            if (excess > 0.0) {
                val floorDb = FLOOR_DB * amount
                val reduction = min(excess * (SLOPE_BASE + SLOPE_RANGE * amount), staticDb - floorDb)
                targetDb = staticDb - reduction
            }
        }
        gTarget = if (targetDb >= staticDb) 1.0 else (10.0.pow(targetDb / 20.0) - 1.0) / top
    }

    private fun biquad(x: Double, c: DoubleArray, k: Int, s: DoubleArray, i: Int): Double {
        val y = c[k] * x + c[k + 1] * s[i] + c[k + 2] * s[i + 1] - c[k + 3] * s[i + 2] - c[k + 4] * s[i + 3]
        s[i + 1] = s[i]; s[i] = x
        s[i + 3] = s[i + 2]; s[i + 2] = y
        return y
    }

    private fun coefficient(seconds: Double): Double = 1.0 - exp(-1.0 / (seconds * sampleRate))

    private fun identity() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)

    private fun store(out: DoubleArray, b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        out[0] = b0 / a0; out[1] = b1 / a0; out[2] = b2 / a0; out[3] = a1 / a0; out[4] = a2 / a0
    }

    /** RBJ high shelf, shelf slope S = 1. */
    private fun highShelf(f: Double, gainDb: Double): DoubleArray {
        val out = DoubleArray(5)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / sampleRate
        val alpha = sin(w0) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / SHELF_SLOPE - 1.0) + 2.0)
        val cosW0 = cos(w0)
        val sa = 2.0 * sqrt(a) * alpha
        store(out,
            a * ((a + 1) + (a - 1) * cosW0 + sa), -2 * a * ((a - 1) + (a + 1) * cosW0), a * ((a + 1) + (a - 1) * cosW0 - sa),
            (a + 1) - (a - 1) * cosW0 + sa, 2 * ((a - 1) - (a + 1) * cosW0), (a + 1) - (a - 1) * cosW0 - sa)
        return out
    }

    private fun highPass(out: DoubleArray, f: Double, q: Double) {
        val w0 = 2.0 * PI * f / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        store(out, (1 + cosW0) / 2, -(1 + cosW0), (1 + cosW0) / 2, 1 + alpha, -2 * cosW0, 1 - alpha)
    }

    private fun lowPass(out: DoubleArray, f: Double, q: Double) {
        val w0 = 2.0 * PI * f / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        store(out, (1 - cosW0) / 2, 1 - cosW0, (1 - cosW0) / 2, 1 + alpha, -2 * cosW0, 1 - alpha)
    }

    companion object {
        const val MAX_DB = 8
        val CORNERS_HZ = listOf(6000, 8000, 10000, 12000)
        const val DEFAULT_HZ = 10000
        const val DEFAULT_DE_ESS = 50

        /** Nearest allowed corner frequency. */
        fun snapCorner(hz: Int): Int = CORNERS_HZ.minByOrNull { abs(it - hz) } ?: DEFAULT_HZ

        private const val SHELF_SLOPE = 1.0
        private const val BUTTERWORTH_Q = 0.7071
        private const val DETECT_LOW_HZ = 5000.0
        private const val DETECT_HIGH_HZ = 9000.0
        private const val CONTROL_FRAMES = 16
        private const val ENV_ATTACK_S = 0.001
        private const val SIB_RELEASE_S = 0.020
        private const val BROAD_RELEASE_S = 0.060
        private const val GAIN_ATTACK_S = 0.001
        private const val GAIN_RELEASE_S = 0.020
        private const val CONFIG_GLIDE_S = 0.030
        /** Threshold of band level over broadband level: −4 dB at de-ess 0+, −10 dB at 50, −16 dB at 100. */
        private const val THRESHOLD_BASE_DB = -4.0
        private const val THRESHOLD_RANGE_DB = -12.0
        /** dB of shelf reduction per dB of excess: 0.5 at de-ess 0+, 1.25 at 50, 2 at 100. */
        private const val SLOPE_BASE = 0.5
        private const val SLOPE_RANGE = 1.5
        /** Lowest shelf gain under full de-essing, scaled by the amount: −3 dB at de-ess 100. */
        private const val FLOOR_DB = -3.0
        /** Below these levels (−60 / −70 dBFS) the detector does not act on noise or dither. */
        private const val SIB_GATE = 0.001
        private const val BROAD_GATE = 0.000316
        private const val IDLE_G = 1e-4
    }
}
