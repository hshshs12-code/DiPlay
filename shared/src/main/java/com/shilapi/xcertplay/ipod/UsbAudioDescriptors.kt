// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.ipod

/** One USB Audio Class 1 streaming alternate setting with a PCM format and an isochronous endpoint. */
data class UsbAudioAlternate(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val channels: Int,
    val subframeBytes: Int,
    val bitResolution: Int,
    /** Discrete rates; empty when the descriptor declares a continuous range. */
    val sampleRates: List<Int>,
    val continuousRange: IntRange?,
    val endpointAddress: Int,
    val maxPacketSize: Int,
    val interval: Int,
    val sampleRateControl: Boolean,
) {
    val isInput: Boolean get() = endpointAddress and 0x80 != 0
    fun supports(rateHz: Int): Boolean = rateHz in sampleRates || (continuousRange?.contains(rateHz) ?: false)
    /** Packet period in microseconds for a high-speed device (bInterval is a power of two of 125 µs). */
    val packetPeriodMicros: Int get() = 125 shl (interval.coerceIn(1, 16) - 1)
    fun describe(): String = "iface=$interfaceNumber alt=$alternateSetting ch=$channels ${bitResolution}bit/${subframeBytes}B " +
        "rates=${if (sampleRates.isNotEmpty()) sampleRates.joinToString("/") else "${continuousRange?.first}-${continuousRange?.last}"} " +
        "ep=0x${endpointAddress.toString(16)} maxPacket=$maxPacketSize interval=$interval freqCtl=$sampleRateControl"
}

/** Walks a full USB configuration descriptor for UAC1 audio-streaming formats. */
object UsbAudioDescriptors {
    private const val DESCRIPTOR_INTERFACE = 0x04
    private const val DESCRIPTOR_ENDPOINT = 0x05
    private const val DESCRIPTOR_CS_INTERFACE = 0x24
    private const val DESCRIPTOR_CS_ENDPOINT = 0x25
    private const val CLASS_AUDIO = 0x01
    private const val SUBCLASS_AUDIO_STREAMING = 0x02
    private const val FORMAT_TYPE = 0x02
    private const val AS_GENERAL = 0x01
    private const val FORMAT_TYPE_I = 0x01

    fun parse(configuration: ByteArray): List<UsbAudioAlternate> {
        val result = ArrayList<UsbAudioAlternate>()
        var offset = 0
        var interfaceNumber = -1
        var alternate = 0
        var streaming = false
        var formatTag = -1
        var channels = 0; var subframe = 0; var bits = 0
        var rates: List<Int> = emptyList(); var range: IntRange? = null
        var endpoint = -1; var maxPacket = 0; var interval = 0
        var freqControl = false
        fun flush() {
            if (streaming && endpoint >= 0 && channels > 0 && subframe > 0 && (formatTag == 1 || formatTag == -1)) {
                result += UsbAudioAlternate(interfaceNumber, alternate, channels, subframe, bits, rates, range, endpoint, maxPacket, interval, freqControl)
            }
            formatTag = -1; channels = 0; subframe = 0; bits = 0; rates = emptyList(); range = null
            endpoint = -1; maxPacket = 0; interval = 0; freqControl = false
        }
        while (offset + 2 <= configuration.size) {
            val length = configuration[offset].toInt() and 0xff
            val type = configuration[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > configuration.size) break
            fun u8(i: Int) = configuration[offset + i].toInt() and 0xff
            fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
            fun u24(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)
            when (type) {
                DESCRIPTOR_INTERFACE -> if (length >= 9) {
                    flush()
                    interfaceNumber = u8(2); alternate = u8(3)
                    streaming = u8(5) == CLASS_AUDIO && u8(6) == SUBCLASS_AUDIO_STREAMING && u8(7) == 0
                }
                DESCRIPTOR_CS_INTERFACE -> if (streaming && length >= 3) when (u8(2)) {
                    AS_GENERAL -> if (length >= 7) formatTag = u16(5)
                    FORMAT_TYPE -> if (length >= 8 && u8(3) == FORMAT_TYPE_I) {
                        channels = u8(4); subframe = u8(5); bits = u8(6)
                        val count = u8(7)
                        if (count == 0 && length >= 14) range = u24(8)..u24(11)
                        else rates = (0 until count).mapNotNull { i -> if (8 + i * 3 + 2 < length) u24(8 + i * 3) else null }
                    }
                }
                DESCRIPTOR_ENDPOINT -> if (streaming && length >= 7 && (u8(3) and 0x03) == 0x01) {
                    endpoint = u8(2); maxPacket = u16(4) and 0x7ff; interval = u8(6)
                }
                DESCRIPTOR_CS_ENDPOINT -> if (streaming && length >= 4) freqControl = (u8(3) and 0x01) != 0
            }
            offset += length
        }
        flush()
        return result
    }

    /** The capture alternate best matching [rateHz]: stereo 16-bit first, then wider samples, then mono. */
    fun chooseInput(alternates: List<UsbAudioAlternate>, rateHz: Int): UsbAudioAlternate? {
        val inputs = alternates.filter { it.isInput && it.supports(rateHz) }
        return inputs.firstOrNull { it.channels == 2 && it.subframeBytes == 2 }
            ?: inputs.firstOrNull { it.channels == 2 && it.subframeBytes in 3..4 }
            ?: inputs.firstOrNull { it.channels == 1 && it.subframeBytes in 2..4 }
            ?: inputs.firstOrNull { it.channels in 1..2 }
    }

    /** Converts interleaved PCM of [subframeBytes] per sample to 16-bit little-endian in place of a new array. */
    fun toPcm16(data: ByteArray, length: Int, subframeBytes: Int, bitResolution: Int): ByteArray {
        if (subframeBytes == 2) return if (length == data.size) data else data.copyOf(length)
        val samples = length / subframeBytes
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val base = i * subframeBytes
            // The significant bits are left-justified in the subframe; keep the top 16.
            val high = data[base + subframeBytes - 1].toInt()
            val low = data[base + subframeBytes - 2].toInt() and 0xff
            if (bitResolution < 16 && subframeBytes == 3) { /* rare: already left-justified, same bytes */ }
            out[i * 2] = low.toByte(); out[i * 2 + 1] = high.toByte()
        }
        return out
    }
}
