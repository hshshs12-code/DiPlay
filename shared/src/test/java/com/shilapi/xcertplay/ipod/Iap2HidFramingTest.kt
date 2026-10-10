package com.shilapi.xcertplay.ipod

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2HidFramingTest {
    private val descriptor = byteArrayOf(
        0x06, 0x00, 0xff.toByte(),       // usage page vendor
        0x09, 0x01, 0xa1.toByte(), 0x01, // collection
        0x75, 0x08,                      // report size 8
        0x85.toByte(), 0x01, 0x95.toByte(), 0x05, 0x81.toByte(), 0x02, // input id 1, 5 bytes
        0x85.toByte(), 0x02, 0x95.toByte(), 0x0c, 0x81.toByte(), 0x02, // input id 2, 12 bytes
        0x85.toByte(), 0x0b, 0x95.toByte(), 0x07, 0x91.toByte(), 0x02, // output id 0x0b, 7 bytes
        0x85.toByte(), 0x0c, 0x95.toByte(), 0x40, 0x91.toByte(), 0x02, // output id 0x0c, 64 bytes
        0xc0.toByte(),
    )

    @Test
    fun parsesReportSizesPerId() {
        val layout = HidReportLayout.parse(descriptor)
        assertEquals(mapOf(1 to 5, 2 to 12), layout.inputs)
        assertEquals(mapOf(0x0b to 7, 0x0c to 64), layout.outputs)
        assertEquals(12, layout.maxInputBytes)
    }

    @Test
    fun fragmentsPicksSmallestFittingReportAndSetsLinkControlBits() {
        val layout = HidReportLayout.parse(descriptor)
        val small = Iap2HidFraming.fragment(byteArrayOf(1, 2, 3), layout.outputs)
        assertEquals(1, small.size)
        assertEquals(8, small[0].size)
        assertEquals(0x0b, small[0][0].toInt())
        assertEquals(0, small[0][1].toInt())
        assertArrayEquals(byteArrayOf(1, 2, 3, 0, 0, 0), small[0].copyOfRange(2, 8))

        val data = ByteArray(100) { it.toByte() }
        val reports = Iap2HidFraming.fragment(data, layout.outputs)
        assertEquals(2, reports.size)
        assertEquals(0x0c, reports[0][0].toInt())
        assertEquals(Iap2HidFraming.LCB_MORE_TO_FOLLOW, reports[0][1].toInt())
        assertEquals(0x0c, reports[1][0].toInt())
        assertEquals(Iap2HidFraming.LCB_CONTINUATION, reports[1][1].toInt())
        val joined = reports[0].copyOfRange(2, 65) + reports[1].copyOfRange(2, 2 + 37)
        assertArrayEquals(data, joined)
    }

    @Test
    fun reassemblesAndTrimsPaddedLinkPackets() {
        val packet = byteArrayOf(0xff.toByte(), 0x5a, 0x00, 0x0b, 0x80.toByte(), 0x2b, 0x00, 0x00, 0x00, 0x01, 0x02)
        val report = ByteArray(1 + 64).also { it[0] = 0x02; it[1] = 0; System.arraycopy(packet, 0, it, 2, packet.size) }
        val reassembler = Iap2HidFraming.Reassembler()
        assertArrayEquals(packet, reassembler.accept(report))

        // A "more to follow" report is always full: 6 payload bytes in an 8-byte report.
        val first = ByteArray(8).also { it[0] = 0x01; it[1] = Iap2HidFraming.LCB_MORE_TO_FOLLOW.toByte(); System.arraycopy(packet, 0, it, 2, 6) }
        val second = ByteArray(14).also { it[0] = 0x02; it[1] = Iap2HidFraming.LCB_CONTINUATION.toByte(); System.arraycopy(packet, 6, it, 2, 5) }
        assertNull(reassembler.accept(first))
        assertArrayEquals(packet, reassembler.accept(second))
    }

    @Test
    fun trimKeepsMarkerAndUnknownContent() {
        val padded = Iap2HidFraming.MARKER + ByteArray(10)
        assertArrayEquals(Iap2HidFraming.MARKER, Iap2HidFraming.trim(padded))
        val unknown = byteArrayOf(1, 2, 3, 0, 0)
        assertArrayEquals(unknown, Iap2HidFraming.trim(unknown))
        val twoPackets = byteArrayOf(0xff.toByte(), 0x5a, 0x00, 0x09, 0, 0, 0, 0, 0) + byteArrayOf(0xff.toByte(), 0x5a, 0x00, 0x09, 0, 0, 0, 0, 0) + ByteArray(3)
        assertEquals(18, Iap2HidFraming.trim(twoPackets).size)
        assertTrue(Iap2HidFraming.trim(twoPackets).all { true })
    }
}
