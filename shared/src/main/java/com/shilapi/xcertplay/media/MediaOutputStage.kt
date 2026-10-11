package com.shilapi.xcertplay.media

/**
 * Turns DiPlay's 16-bit PCM at the stream rate into what the AudioTrack is opened with: float
 * samples and/or the hardware's native rate. With both, Android's mixer has nothing left to do
 * but add streams together: no resampling, no requantisation, and the first integer rounding
 * happens in the audio driver. The DSP, when present, renders straight to float so its output is
 * never squeezed through 16 bits first.
 */
class MediaOutputStage(
    val inRate: Int,
    val channels: Int,
    val outRate: Int,
    val floatOutput: Boolean,
    private val dsp: () -> EqualizerDsp?,
) {
    class Rendered(val floats: FloatArray?, val bytes: ByteArray?, val lengthBytes: Int)

    val resampling: Boolean = outRate != inRate && outRate > 0
    /** False when the stage would be a pure copy; callers then write their PCM directly. */
    val active: Boolean = floatOutput || resampling
    val trackFrameBytes: Int = channels * (if (floatOutput) 4 else 2)
    private val resampler = if (resampling) SincResampler(inRate, outRate, channels) else null
    private var inFloat = FloatArray(0)
    private var outFloat = FloatArray(0)
    private var outBytes = ByteArray(0)
    private var ditherState = 0x2545F491

    fun describe(): String = "output=${outRate}Hz ${if (floatOutput) "float" else "16-bit"}${if (resampling) " resampled from $inRate" else ""}"

    /** Renders one block of 16-bit interleaved PCM ([length] bytes) into track format. */
    fun render(data: ByteArray, offset: Int, length: Int): Rendered {
        val frames = length / (2 * channels)
        val samples = frames * channels
        if (inFloat.size < samples) inFloat = FloatArray(samples)
        val processor = dsp()
        val produced = if (processor != null) processor.processToFloat(data, offset, frames * channels * 2, inFloat)
        else { convert(data, offset, samples, inFloat); samples }
        var floats = inFloat
        var count = produced
        resampler?.let { r ->
            val capacity = r.maxOutputFrames(produced / channels) * channels
            if (outFloat.size < capacity) outFloat = FloatArray(capacity)
            count = r.process(inFloat, 0, produced / channels, outFloat, 0) * channels
            floats = outFloat
        }
        if (floatOutput) return Rendered(floats, null, count * 4)
        if (outBytes.size < count * 2) outBytes = ByteArray(count * 2)
        var r = ditherState
        for (i in 0 until count) {
            // TPDF dither at the only requantisation point: the two 16-bit halves of one xorshift
            // draw are the two uniforms, their difference is triangular over ±1 LSB.
            r = r xor (r shl 13); r = r xor (r ushr 17); r = r xor (r shl 5)
            val d = ((r and 0xffff) - (r ushr 16)) * (1f / 65536f)
            // Same 32768 scale as [convert], so an unprocessed sample comes back exactly (± dither).
            val v = Math.floor((floats[i] * 32768f + d).toDouble() + 0.5).toInt().coerceIn(-32768, 32767)
            outBytes[i * 2] = v.toByte(); outBytes[i * 2 + 1] = (v shr 8).toByte()
        }
        ditherState = r
        return Rendered(null, outBytes, count * 2)
    }

    companion object {
        fun convert(data: ByteArray, offset: Int, samples: Int, out: FloatArray) {
            var o = offset
            for (i in 0 until samples) {
                out[i] = ((data[o + 1].toInt() shl 8) or (data[o].toInt() and 0xff)) * (1f / 32768f)
                o += 2
            }
        }
    }
}
