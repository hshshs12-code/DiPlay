package com.shilapi.xcertplay.media

import java.util.Collections
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * A 12-band graphic equalizer as peaking biquads on 16-bit interleaved PCM, applied in place
 * before the AudioTrack write. About 1.2 M filter steps per second at 48 kHz stereo: a few percent
 * of one small core. Bands at 0 dB are skipped. An optional soft limiter tames boosted peaks.
 */
class EqualizerDsp(private val sampleRate: Int, private val channels: Int) {
    @Volatile private var coefficients = FloatArray(BANDS * 5)
    @Volatile private var activeBands = BooleanArray(BANDS)
    @Volatile private var anyActive = false
    @Volatile var limiter = false
        private set
    private val state = FloatArray(channels * BANDS * 4)

    /** [gainsDb] has one entry per band in [CENTER_HZ] order; out-of-range bands are ignored. */
    fun configure(gainsDb: FloatArray, limiter: Boolean) {
        val next = FloatArray(BANDS * 5)
        val active = BooleanArray(BANDS)
        var any = false
        for (band in 0 until BANDS) {
            val gain = gainsDb.getOrElse(band) { 0f }.coerceIn(MIN_DB, MAX_DB)
            val f = CENTER_HZ[band].toDouble()
            if (abs(gain) < 0.05f || f >= sampleRate / 2.0) continue
            val a = Math.pow(10.0, gain / 40.0)
            val w0 = 2.0 * PI * f / sampleRate
            val alpha = sin(w0) / (2.0 * Q)
            val cosW0 = cos(w0)
            val a0 = 1.0 + alpha / a
            next[band * 5] = ((1.0 + alpha * a) / a0).toFloat()
            next[band * 5 + 1] = ((-2.0 * cosW0) / a0).toFloat()
            next[band * 5 + 2] = ((1.0 - alpha * a) / a0).toFloat()
            next[band * 5 + 3] = ((-2.0 * cosW0) / a0).toFloat()
            next[band * 5 + 4] = ((1.0 - alpha / a) / a0).toFloat()
            active[band] = true
            any = true
        }
        coefficients = next
        activeBands = active
        anyActive = any
        this.limiter = limiter
    }

    /** Processes 16-bit little-endian interleaved PCM in place. */
    fun process(data: ByteArray, offset: Int, length: Int) {
        val useLimiter = limiter
        if (!anyActive && !useLimiter) return
        val c = coefficients
        val active = activeBands
        val frames = length / (2 * channels)
        var index = offset
        for (frame in 0 until frames) {
            for (ch in 0 until channels) {
                val lo = data[index].toInt() and 0xff
                val hi = data[index + 1].toInt()
                var x = ((hi shl 8) or lo).toFloat() / 32768f
                if (anyActive) {
                    val base = ch * BANDS * 4
                    for (band in 0 until BANDS) {
                        if (!active[band]) continue
                        val s = base + band * 4
                        val k = band * 5
                        val y = c[k] * x + c[k + 1] * state[s] + c[k + 2] * state[s + 1] -
                            c[k + 3] * state[s + 2] - c[k + 4] * state[s + 3]
                        state[s + 1] = state[s]; state[s] = x
                        state[s + 3] = state[s + 2]; state[s + 2] = y
                        x = y
                    }
                }
                if (useLimiter) {
                    val m = abs(x)
                    if (m > KNEE) {
                        val soft = KNEE + (CEILING - KNEE) * tanh(((m - KNEE) / (CEILING - KNEE)).toDouble()).toFloat()
                        x = if (x < 0) -soft else soft
                    }
                }
                val v = (x * 32767f).toInt().coerceIn(-32768, 32767)
                data[index] = v.toByte()
                data[index + 1] = (v shr 8).toByte()
                index += 2
            }
        }
    }

    fun register() { active.add(this) }
    fun unregister() { active.remove(this) }

    companion object {
        val CENTER_HZ = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 3000, 4000, 8000, 12000, 16000)
        const val BANDS = 12
        const val MIN_DB = -12f
        const val MAX_DB = 12f
        private const val Q = 1.1
        private const val KNEE = 0.8f
        /** Peaks settle just under full scale so rounding never produces a hard-clipped sample. */
        private const val CEILING = 0.985f
        private val active: MutableSet<EqualizerDsp> = Collections.synchronizedSet(mutableSetOf<EqualizerDsp>())

        fun applyToActive(settings: AudioEffectSettings) {
            val snapshot = synchronized(active) { active.toList() }
            snapshot.forEach { it.configure(settings.dspGainsDb(), settings.limiter && settings.enabled && settings.dspMode) }
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
