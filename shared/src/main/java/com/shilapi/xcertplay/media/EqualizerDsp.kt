package com.shilapi.xcertplay.media

import java.util.Collections
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * DiPlay's software audio chain on 16-bit interleaved PCM, applied in place before the AudioTrack
 * write. Stages in order: subsonic high-pass, 12-band graphic equalizer, up to five parametric
 * bands, psychoacoustic bass enhancer, auto level, soft limiter. Every stage is skipped when it is
 * at its neutral setting, so the cost scales with what is switched on; the full chain is a few
 * percent of one small core at 48 kHz stereo.
 */
class EqualizerDsp(private val sampleRate: Int, private val channels: Int) {
    // Graphic equalizer
    @Volatile private var coefficients = FloatArray(BANDS * 5)
    @Volatile private var activeBands = BooleanArray(BANDS)
    @Volatile private var anyActive = false
    private val state = FloatArray(channels * BANDS * 4)

    // Parametric bands
    @Volatile private var paramCoefficients = FloatArray(MAX_PARAMETRIC * 5)
    @Volatile private var paramActive = BooleanArray(MAX_PARAMETRIC)
    @Volatile private var anyParam = false
    private val paramState = FloatArray(channels * MAX_PARAMETRIC * 4)

    // Subsonic filter: two cascaded second-order high-pass sections (24 dB/octave)
    @Volatile private var subsonic = FloatArray(5)
    @Volatile private var subsonicOn = false
    private val subState = FloatArray(channels * 2 * 4)

    // Psychoacoustic bass: low band → saturation → band-pass → mixed back in
    @Volatile private var bassOn = false
    @Volatile private var bassGain = 0f
    @Volatile private var bassLow = FloatArray(5)
    @Volatile private var bassHigh = FloatArray(5)
    @Volatile private var bassTop = FloatArray(5)
    private val bassState = FloatArray(channels * 3 * 4)

    // Auto level
    @Volatile private var autoOn = false
    @Volatile private var autoStrength = 0f
    private var envelope = 0f
    private var agcGain = 1f
    private var agcDesired = 1f
    private var agcCounter = 0
    private val envAttack = coefficient(0.010)
    private val envRelease = coefficient(1.0)
    private val gainAttack = coefficient(0.060)
    private val gainRelease = coefficient(2.0)

    @Volatile var limiter = false
        private set
    @Volatile private var softClipForced = false
    private val frame = FloatArray(channels)

    /** Graphic-only configuration kept for callers and tests; all other stages switch off. */
    fun configure(gainsDb: FloatArray, limiter: Boolean) {
        configureGraphic(gainsDb)
        anyParam = false; paramActive = BooleanArray(MAX_PARAMETRIC)
        subsonicOn = false; bassOn = false; autoOn = false
        this.limiter = limiter
    }

    /** Full configuration from the user's settings; [softClip] forces the limiter on (media soft clipper). */
    fun configure(settings: AudioEffectSettings, softClip: Boolean) {
        softClipForced = softClip
        val active = settings.enabled && settings.dspMode
        configureGraphic(if (active) settings.dspGainsDb() else FloatArray(BANDS))
        configureParametric(if (active) settings.parametric else emptyList())
        configureSubsonic(if (active) settings.subsonicHz else 0)
        configureBass(if (active) settings.bassEnhance else 0, settings.bassEnhanceHz)
        configureAutoLevel(active && settings.autoLevel, settings.autoLevelAmount)
        limiter = softClip || (active && (settings.limiter || settings.autoLevel))
    }

    private fun configureGraphic(gainsDb: FloatArray) {
        val next = FloatArray(BANDS * 5)
        val active = BooleanArray(BANDS)
        var any = false
        for (band in 0 until BANDS) {
            val gain = gainsDb.getOrElse(band) { 0f }.coerceIn(MIN_DB, MAX_DB)
            val f = CENTER_HZ[band].toDouble()
            if (abs(gain) < 0.05f || f >= sampleRate / 2.0) continue
            peaking(next, band * 5, f, gain.toDouble(), Q)
            active[band] = true
            any = true
        }
        coefficients = next
        activeBands = active
        anyActive = any
    }

    private fun configureParametric(bands: List<ParametricBand>) {
        val next = FloatArray(MAX_PARAMETRIC * 5)
        val active = BooleanArray(MAX_PARAMETRIC)
        var any = false
        bands.take(MAX_PARAMETRIC).forEachIndexed { index, band ->
            val gain = band.gainDb.coerceIn(MIN_DB, MAX_DB).toDouble()
            val f = band.hz.toDouble()
            if (band.type == ParametricBand.OFF || abs(gain) < 0.05 || f < 10 || f >= sampleRate / 2.0) return@forEachIndexed
            val q = band.q.coerceIn(0.3f, 8f).toDouble()
            when (band.type) {
                ParametricBand.LOW_SHELF -> lowShelf(next, index * 5, f, gain, q)
                ParametricBand.HIGH_SHELF -> highShelf(next, index * 5, f, gain, q)
                else -> peaking(next, index * 5, f, gain, q)
            }
            active[index] = true
            any = true
        }
        paramCoefficients = next
        paramActive = active
        anyParam = any
    }

    private fun configureSubsonic(hz: Int) {
        if (hz < 10 || hz >= sampleRate / 4) { subsonicOn = false; return }
        val c = FloatArray(5)
        highPass(c, 0, hz.toDouble(), BUTTERWORTH_Q)
        subsonic = c
        subsonicOn = true
    }

    private fun configureBass(amount: Int, hz: Int) {
        if (amount <= 0) { bassOn = false; return }
        val crossover = hz.coerceIn(50, 200).toDouble()
        val low = FloatArray(5); val high = FloatArray(5); val top = FloatArray(5)
        lowPass(low, 0, crossover, BUTTERWORTH_Q)
        highPass(high, 0, crossover, BUTTERWORTH_Q)
        lowPass(top, 0, minOf(crossover * 5.0, 900.0), BUTTERWORTH_Q)
        bassLow = low; bassHigh = high; bassTop = top
        bassGain = amount.coerceIn(0, 100) / 100f * BASS_MIX
        bassOn = true
    }

    private fun configureAutoLevel(on: Boolean, amount: Int) {
        autoStrength = amount.coerceIn(0, 100) / 100f
        if (on && !autoOn) { envelope = 0f; agcGain = 1f; agcDesired = 1f; agcCounter = 0 }
        autoOn = on && autoStrength > 0f
    }

    /** Processes 16-bit little-endian interleaved PCM in place. */
    fun process(data: ByteArray, offset: Int, length: Int) {
        val useLimiter = limiter
        val graphic = anyActive; val param = anyParam; val sub = subsonicOn; val bass = bassOn; val auto = autoOn
        if (!graphic && !param && !sub && !bass && !auto && !useLimiter) return
        val c = coefficients; val active = activeBands
        val pc = paramCoefficients; val pActive = paramActive
        val sc = subsonic
        val bl = bassLow; val bh = bassHigh; val bt = bassTop; val bg = bassGain
        val frames = length / (2 * channels)
        var index = offset
        for (n in 0 until frames) {
            var mono = 0f
            for (ch in 0 until channels) {
                val o = index + ch * 2
                val lo = data[o].toInt() and 0xff
                val hi = data[o + 1].toInt()
                var x = ((hi shl 8) or lo).toFloat() / 32768f
                if (sub) {
                    val s = ch * 8
                    x = biquad(x, sc, 0, subState, s)
                    x = biquad(x, sc, 0, subState, s + 4)
                }
                if (graphic) {
                    val base = ch * BANDS * 4
                    for (band in 0 until BANDS) {
                        if (active[band]) x = biquad(x, c, band * 5, state, base + band * 4)
                    }
                }
                if (param) {
                    val base = ch * MAX_PARAMETRIC * 4
                    for (band in 0 until MAX_PARAMETRIC) {
                        if (pActive[band]) x = biquad(x, pc, band * 5, paramState, base + band * 4)
                    }
                }
                if (bass) {
                    val s = ch * 12
                    val low = biquad(x, bl, 0, bassState, s)
                    val u = low * BASS_DRIVE
                    val a = abs(u)
                    val sat = a / (1f + a)
                    // Odd harmonics from the symmetric part, even harmonics from the rectified part.
                    var h = (if (u < 0) -sat else sat) + BASS_EVEN * sat * sat
                    h = biquad(h, bh, 0, bassState, s + 4)
                    h = biquad(h, bt, 0, bassState, s + 8)
                    x += h * bg
                }
                frame[ch] = x
                mono += x
            }
            if (auto) {
                val level = abs(mono) / channels
                envelope += (level - envelope) * (if (level > envelope) envAttack else envRelease)
                if (++agcCounter >= AGC_UPDATE_FRAMES) {
                    agcCounter = 0
                    if (envelope > AGC_GATE) {
                        agcDesired = (AGC_TARGET / envelope).toDouble().pow(autoStrength.toDouble()).toFloat().coerceIn(AGC_MIN_GAIN, AGC_MAX_GAIN)
                    }
                }
                agcGain += (agcDesired - agcGain) * (if (agcDesired < agcGain) gainAttack else gainRelease)
            }
            for (ch in 0 until channels) {
                var x = frame[ch]
                if (auto) x *= agcGain
                if (useLimiter) {
                    val m = abs(x)
                    if (m > KNEE) {
                        val soft = KNEE + (CEILING - KNEE) * tanh(((m - KNEE) / (CEILING - KNEE)).toDouble()).toFloat()
                        x = if (x < 0) -soft else soft
                    }
                }
                val v = (x * 32767f).toInt().coerceIn(-32768, 32767)
                val o = index + ch * 2
                data[o] = v.toByte()
                data[o + 1] = (v shr 8).toByte()
            }
            index += channels * 2
        }
    }

    /** Current auto-level gain in dB, for the UI. */
    fun autoLevelGainDb(): Float = if (autoOn) (20.0 * Math.log10(agcGain.toDouble())).toFloat() else 0f

    private fun biquad(x: Float, c: FloatArray, k: Int, s: FloatArray, i: Int): Float {
        val y = c[k] * x + c[k + 1] * s[i] + c[k + 2] * s[i + 1] - c[k + 3] * s[i + 2] - c[k + 4] * s[i + 3]
        s[i + 1] = s[i]; s[i] = x
        s[i + 3] = s[i + 2]; s[i + 2] = y
        return y
    }

    private fun coefficient(seconds: Double): Float = (1.0 - Math.exp(-1.0 / (seconds * sampleRate))).toFloat()

    // RBJ cookbook sections, stored normalised as [b0, b1, b2, a1, a2].
    private fun store(out: FloatArray, k: Int, b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        out[k] = (b0 / a0).toFloat(); out[k + 1] = (b1 / a0).toFloat(); out[k + 2] = (b2 / a0).toFloat()
        out[k + 3] = (a1 / a0).toFloat(); out[k + 4] = (a2 / a0).toFloat()
    }

    private fun peaking(out: FloatArray, k: Int, f: Double, gainDb: Double, q: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        store(out, k, 1.0 + alpha * a, -2.0 * cosW0, 1.0 - alpha * a, 1.0 + alpha / a, -2.0 * cosW0, 1.0 - alpha / a)
    }

    private fun lowShelf(out: FloatArray, k: Int, f: Double, gainDb: Double, q: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        val sa = 2.0 * sqrt(a) * alpha
        store(out, k,
            a * ((a + 1) - (a - 1) * cosW0 + sa), 2 * a * ((a - 1) - (a + 1) * cosW0), a * ((a + 1) - (a - 1) * cosW0 - sa),
            (a + 1) + (a - 1) * cosW0 + sa, -2 * ((a - 1) + (a + 1) * cosW0), (a + 1) + (a - 1) * cosW0 - sa)
    }

    private fun highShelf(out: FloatArray, k: Int, f: Double, gainDb: Double, q: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        val sa = 2.0 * sqrt(a) * alpha
        store(out, k,
            a * ((a + 1) + (a - 1) * cosW0 + sa), -2 * a * ((a - 1) + (a + 1) * cosW0), a * ((a + 1) + (a - 1) * cosW0 - sa),
            (a + 1) - (a - 1) * cosW0 + sa, 2 * ((a - 1) - (a + 1) * cosW0), (a + 1) - (a - 1) * cosW0 - sa)
    }

    private fun highPass(out: FloatArray, k: Int, f: Double, q: Double) {
        val w0 = 2.0 * PI * f / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        store(out, k, (1 + cosW0) / 2, -(1 + cosW0), (1 + cosW0) / 2, 1 + alpha, -2 * cosW0, 1 - alpha)
    }

    private fun lowPass(out: FloatArray, k: Int, f: Double, q: Double) {
        val w0 = 2.0 * PI * f / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        store(out, k, (1 - cosW0) / 2, 1 - cosW0, (1 - cosW0) / 2, 1 + alpha, -2 * cosW0, 1 - alpha)
    }

    fun register() { active.add(this) }
    fun unregister() { active.remove(this) }

    companion object {
        val CENTER_HZ = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 3000, 4000, 8000, 12000, 16000)
        const val BANDS = 12
        const val MAX_PARAMETRIC = 5
        const val MIN_DB = -12f
        const val MAX_DB = 12f
        private const val Q = 1.1
        private const val BUTTERWORTH_Q = 0.7071
        private const val KNEE = 0.8f
        /** Peaks settle just under full scale so rounding never produces a hard-clipped sample. */
        private const val CEILING = 0.985f
        private const val BASS_DRIVE = 6f
        private const val BASS_EVEN = 0.6f
        private const val BASS_MIX = 1.0f
        /** Auto level aims the smoothed peak envelope at about -14 dBFS. */
        private const val AGC_TARGET = 0.2f
        private const val AGC_GATE = 0.003f
        private const val AGC_MIN_GAIN = 0.25f
        private const val AGC_MAX_GAIN = 4f
        private const val AGC_UPDATE_FRAMES = 128
        private val active: MutableSet<EqualizerDsp> = Collections.synchronizedSet(mutableSetOf<EqualizerDsp>())

        fun applyToActive(settings: AudioEffectSettings) {
            val snapshot = synchronized(active) { active.toList() }
            snapshot.forEach { it.configure(settings, it.softClipForced) }
            headroom = settings.headroomGain()
        }

        /** Shared pre-gain read by every renderer on each write; updated with the settings. */
        @Volatile var headroom: Float = 1f

        /** Scales 16-bit interleaved PCM in place by [gain]. */
        fun scale(data: ByteArray, offset: Int, length: Int, gain: Float) {
            if (gain >= 0.999f) return
            var i = offset
            val end = offset + length - 1
            while (i < end) {
                val v = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff))
                val s = (v * gain).toInt().coerceIn(-32768, 32767)
                data[i] = s.toByte(); data[i + 1] = (s shr 8).toByte()
                i += 2
            }
        }
    }
}

/** One parametric equalizer band. Gains and Q are stored in tenths so the preference string stays integer. */
data class ParametricBand(val type: Int = OFF, val hz: Int = 1000, val gainTenths: Int = 0, val qTenths: Int = 10) {
    val gainDb: Float get() = gainTenths / 10f
    val q: Float get() = qTenths / 10f
    fun describe(): String = when (type) {
        OFF -> "Off"
        LOW_SHELF -> "Low shelf $hz Hz ${signed(gainDb)} dB"
        HIGH_SHELF -> "High shelf $hz Hz ${signed(gainDb)} dB"
        else -> "Peak $hz Hz ${signed(gainDb)} dB Q ${"%.1f".format(q)}"
    }
    fun encode(): String = "$type:$hz:$gainTenths:$qTenths"

    companion object {
        const val OFF = 0
        const val PEAK = 1
        const val LOW_SHELF = 2
        const val HIGH_SHELF = 3
        fun peak(hz: Int, gainDb: Float, q: Float = 1f) = ParametricBand(PEAK, hz, Math.round(gainDb * 10), Math.round(q * 10))
        fun lowShelf(hz: Int, gainDb: Float, q: Float = 0.7f) = ParametricBand(LOW_SHELF, hz, Math.round(gainDb * 10), Math.round(q * 10))
        fun highShelf(hz: Int, gainDb: Float, q: Float = 0.7f) = ParametricBand(HIGH_SHELF, hz, Math.round(gainDb * 10), Math.round(q * 10))
        fun decode(text: String): ParametricBand? {
            val parts = text.split(':').map { it.trim().toIntOrNull() ?: return null }
            if (parts.size != 4) return null
            return ParametricBand(parts[0].coerceIn(0, 3), parts[1].coerceIn(10, 20_000), parts[2].coerceIn(-120, 120), parts[3].coerceIn(3, 80))
        }
        fun decodeList(text: String?): List<ParametricBand> =
            text.orEmpty().split(',').filter { it.isNotBlank() }.mapNotNull(::decode).take(EqualizerDsp.MAX_PARAMETRIC)
        private fun signed(v: Float) = (if (v >= 0) "+" else "") + "%.1f".format(v)
    }
}
