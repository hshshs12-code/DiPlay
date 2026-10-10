// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.ipod

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.IphoneUsbException
import java.io.ByteArrayOutputStream

/**
 * The iAP2 link byte stream carried by the iPhone's USB HID interface. Reads come from the
 * interrupt IN endpoint; writes go to the interrupt OUT endpoint when the interface has one and
 * otherwise through HID Set_Report control transfers. The link layer above sees a plain stream.
 */
class Iap2HidStream(
    private val connection: UsbDeviceConnection,
    private val interfaceNumber: Int,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint?,
    private val layout: HidReportLayout,
    private val isAttached: () -> Boolean,
    private val onDiagnostic: (String) -> Unit = {},
) : BlockingDuplexByteStream {
    private val readLock = Any()
    private val writeLock = Any()
    private val reassembler = Iap2HidFraming.Reassembler()
    private val buffered = ByteArrayOutputStream()
    private val readBuffer = ByteArray(maxOf(64, layout.maxInputBytes + 1))
    @Volatile private var closed = false
    @Volatile var bytesIn = 0L; private set
    @Volatile var bytesOut = 0L; private set
    @Volatile var reportsIn = 0L; private set
    @Volatile var reportsOut = 0L; private set

    override fun send(data: ByteArray) = synchronized(writeLock) {
        if (closed) throw IphoneUsbException.DeviceUnavailable("HID stream is closed")
        if (data.isEmpty()) return@synchronized
        for (report in Iap2HidFraming.fragment(data, layout.outputs)) {
            val transferred = if (outEndpoint != null) {
                connection.bulkTransfer(outEndpoint, report, report.size, WRITE_TIMEOUT_MILLIS)
            } else {
                connection.controlTransfer(
                    UsbConstants.USB_TYPE_CLASS or UsbConstants.USB_DIR_OUT or RECIPIENT_INTERFACE,
                    SET_REPORT, (REPORT_TYPE_OUTPUT shl 8) or (report[0].toInt() and 0xff), interfaceNumber,
                    report, report.size, WRITE_TIMEOUT_MILLIS,
                )
            }
            if (transferred != report.size) {
                throw IphoneUsbException.DeviceUnavailable(
                    "HID report 0x${(report[0].toInt() and 0xff).toString(16)} write transferred $transferred of ${report.size} bytes",
                )
            }
            reportsOut++
            bytesOut += report.size
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        if (closed) return@synchronized ByteArray(0)
        if (buffered.size() == 0) {
            val timeout = timeoutMillis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            val count = connection.bulkTransfer(inEndpoint, readBuffer, readBuffer.size, timeout)
            if (count < 0) {
                // Android reports timeouts and failures alike; only a vanished device ends the stream.
                return@synchronized if (closed || !isAttached()) ByteArray(0) else null
            }
            if (count == 0) return@synchronized null
            reportsIn++
            bytesIn += count
            reassembler.accept(readBuffer, count)?.let { buffered.write(it, 0, it.size) }
            if (buffered.size() == 0) return@synchronized null
        }
        val all = buffered.toByteArray()
        buffered.reset()
        if (all.size <= maxBytes) return@synchronized all
        buffered.write(all, maxBytes, all.size - maxBytes)
        all.copyOf(maxBytes)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { onDiagnostic("HID stream closed reportsIn=$reportsIn bytesIn=$bytesIn reportsOut=$reportsOut bytesOut=$bytesOut") }
        // Releasing hands the interface back to the kernel's usbhid driver before the connection goes.
        runCatching { connection.close() }
    }

    companion object {
        private const val WRITE_TIMEOUT_MILLIS = 1_000
        private const val SET_REPORT = 0x09
        private const val GET_DESCRIPTOR = 0x06
        private const val REPORT_TYPE_OUTPUT = 0x02
        private const val RECIPIENT_INTERFACE = 0x01
        private const val HID_REPORT_DESCRIPTOR_TYPE = 0x22

        /** Reads the interface's HID report descriptor, or null when the request fails. */
        fun readReportDescriptor(connection: UsbDeviceConnection, interfaceNumber: Int): ByteArray? {
            val buffer = ByteArray(4096)
            val length = connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD or RECIPIENT_INTERFACE,
                GET_DESCRIPTOR, HID_REPORT_DESCRIPTOR_TYPE shl 8, interfaceNumber, buffer, buffer.size, 1_000,
            )
            return if (length > 0) buffer.copyOf(length) else null
        }
    }
}
