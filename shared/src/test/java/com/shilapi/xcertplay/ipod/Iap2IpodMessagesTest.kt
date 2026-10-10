package com.shilapi.xcertplay.ipod

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2IpodMessagesTest {
    private val config = Iap2IpodConfig("DiPlay", "HeadUnit", "Fork", "SERIAL1", "1.0", "1.0")

    @Test
    fun identificationDeclaresUsbDeviceAudioAndMediaRemote() {
        val frame = Iap2IpodMessages.identificationInformation(config)
        assertEquals(0x1d01, frame.messageId)
        val body = Iap2BodyReader.of(frame)
        assertEquals("DiPlay", body.string(0))
        val sent = body.u16List(6)
        assertTrue(sent.containsAll(listOf(0xaa01, 0xaa03, 0xda00, 0x6800, 0x6802, 0x5000, 0xae03)))
        val received = body.u16List(7)
        assertTrue(received.containsAll(listOf(0xaa00, 0xaa02, 0xda01, 0x5001, 0xae01)))
        val usb = body.group(15)
        assertEquals(1, usb.u16(0))
        assertTrue(usb.void(2))
        assertEquals(listOf(7, 8), usb.all(3).map { it.payload[0].toInt() })
        val hid = body.group(18)
        assertEquals(2, hid.u16(0))
        assertEquals(1, hid.u8(2))
    }

    @Test
    fun sampleRateCodesRoundTrip() {
        assertEquals(44_100, Iap2IpodMessages.sampleRateFromCode(7))
        assertEquals(48_000, Iap2IpodMessages.sampleRateFromCode(8))
        assertNull(Iap2IpodMessages.sampleRateFromCode(42))
        val info = Iap2Messages.buildRaw(Iap2IpodMessages.USB_DEVICE_MODE_AUDIO_INFORMATION) { u8(0, 8) }
        assertEquals(48_000, Iap2IpodMessages.sampleRate(info))
    }

    @Test
    fun hidReportCarriesComponentAndBits() {
        val frame = Iap2IpodMessages.hidReport(1 shl IpodHidKey.NEXT.bit)
        val body = Iap2BodyReader.of(frame)
        assertEquals(Iap2IpodMessages.HID_COMPONENT_ID, body.u16(0))
        assertEquals(0x04, body.bytes(1)[0].toInt())
        val start = Iap2BodyReader.of(Iap2IpodMessages.startHid())
        assertEquals(Iap2IpodMessages.MEDIA_REMOTE_DESCRIPTOR.size, start.bytes(4).size)
    }

    @Test
    fun powerStateReadsLevelAndCharging() {
        val frame = Iap2Messages.buildRaw(Iap2IpodMessages.POWER_UPDATE) { u8(5, 1); u16(6, 73); u8(4, 1) }
        val state = Iap2IpodMessages.powerState(frame, IpodPowerState())
        assertEquals(1, state.chargingState)
        assertEquals(73, state.batteryLevelPercent)
        assertEquals(true, state.externalChargerConnected)
    }
}
