package com.shilapi.xcertplay.ipod

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAudioDescriptorsTest {
    private fun iface(number: Int, alt: Int, subclass: Int) = byteArrayOf(9, 4, number.toByte(), alt.toByte(), if (alt == 0) 0 else 1, 1, subclass.toByte(), 0, 0)
    private val descriptor = byteArrayOf(9, 2, 0, 0, 2, 2, 0, 0xc0.toByte(), 50) +
        iface(0, 0, 1) +
        iface(1, 0, 2) +
        iface(1, 1, 2) +
        byteArrayOf(7, 0x24, 1, 1, 1, 1, 0) + // AS_GENERAL, PCM
        byteArrayOf(14, 0x24, 2, 1, 2, 2, 16, 2, 0x44, 0xac.toByte(), 0, 0x80.toByte(), 0xbb.toByte(), 0) + // stereo 16-bit 44.1k/48k
        byteArrayOf(9, 5, 0x82.toByte(), 0x0d, 0xc8.toByte(), 0, 4, 0, 0) + // iso IN ep 0x82, 200 bytes, interval 4
        byteArrayOf(7, 0x25, 1, 1, 0, 0, 0) + // CS endpoint, sampling freq control
        iface(1, 2, 2) +
        byteArrayOf(7, 0x24, 1, 1, 1, 1, 0) +
        byteArrayOf(11, 0x24, 2, 1, 2, 3, 24, 1, 0x80.toByte(), 0xbb.toByte(), 0) + // stereo 24-bit 48k
        byteArrayOf(9, 5, 0x82.toByte(), 0x0d, 0x2c.toByte(), 1, 4, 0, 0)

    @Test
    fun parsesStreamingAlternates() {
        val alternates = UsbAudioDescriptors.parse(descriptor)
        assertEquals(2, alternates.size)
        val stereo16 = alternates[0]
        assertEquals(1, stereo16.interfaceNumber); assertEquals(1, stereo16.alternateSetting)
        assertEquals(2, stereo16.channels); assertEquals(2, stereo16.subframeBytes); assertEquals(16, stereo16.bitResolution)
        assertEquals(listOf(44_100, 48_000), stereo16.sampleRates)
        assertEquals(0x82, stereo16.endpointAddress); assertEquals(200, stereo16.maxPacketSize); assertEquals(4, stereo16.interval)
        assertTrue(stereo16.sampleRateControl && stereo16.isInput)
        assertEquals(1_000, stereo16.packetPeriodMicros)
        assertEquals(3, alternates[1].subframeBytes)
    }

    @Test
    fun choosesStereo16FirstThenWider() {
        val alternates = UsbAudioDescriptors.parse(descriptor)
        assertEquals(1, UsbAudioDescriptors.chooseInput(alternates, 44_100)?.alternateSetting)
        assertEquals(1, UsbAudioDescriptors.chooseInput(alternates, 48_000)?.alternateSetting)
        assertNotNull(UsbAudioDescriptors.chooseInput(alternates.drop(1), 48_000))
        assertEquals(null, UsbAudioDescriptors.chooseInput(alternates, 96_000))
    }

    @Test
    fun converts24BitTo16() {
        val raw = byteArrayOf(0x00, 0x34, 0x12, 0xff.toByte(), 0xcd.toByte(), 0xab.toByte())
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0xcd.toByte(), 0xab.toByte()), UsbAudioDescriptors.toPcm16(raw, 6, 3, 24))
        val same = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(same, UsbAudioDescriptors.toPcm16(same, 4, 2, 16))
    }
}
