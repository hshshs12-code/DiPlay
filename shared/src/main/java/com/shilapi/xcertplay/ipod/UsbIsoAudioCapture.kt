// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.ipod

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import java.io.Closeable
import java.io.IOException

/** JNI binding for the usbfs isochronous capture driver (`usb_iso_audio.c`). */
object UsbIsoAudioNative {
    init { System.loadLibrary("diplay_usb_iso_audio") }
    external fun open(fd: Int, interfaceNumber: Int, altSetting: Int, endpoint: Int, maxPacket: Int, packetsPerUrb: Int, urbCount: Int): Long
    external fun read(handle: Long, target: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int
    external fun stats(handle: Long): LongArray
    external fun close(handle: Long)
}

/**
 * Captures the iPhone's USB device-mode audio directly from its audio-streaming interface: claims
 * the interface (detaching the kernel's snd-usb-audio), selects the PCM alternate setting, sets the
 * sample rate on the endpoint, then streams isochronous packets through the native driver.
 */
class UsbIsoAudioCapture private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    val alternate: UsbAudioAlternate,
    val rateHz: Int,
    private val handle: Long,
    val packetsPerUrb: Int,
    val urbCount: Int,
) : Closeable {
    val channels: Int get() = alternate.channels
    private val frameBytes = alternate.channels * alternate.subframeBytes
    private var raw = ByteArray(0)
    @Volatile private var closed = false

    /** Reads up to [millis] of audio as 16-bit interleaved PCM; empty on timeout. */
    fun read(millis: Int, timeoutMillis: Int): ByteArray {
        if (closed) throw IOException("USB audio capture is closed")
        val wanted = rateHz * millis / 1000 * frameBytes
        if (raw.size < wanted) raw = ByteArray(wanted)
        val got = UsbIsoAudioNative.read(handle, raw, 0, wanted, timeoutMillis)
        if (got <= 0) return EMPTY
        val whole = got - got % frameBytes
        return UsbAudioDescriptors.toPcm16(raw, whole, alternate.subframeBytes, alternate.bitResolution)
    }

    fun stats(): String {
        val s = UsbIsoAudioNative.stats(handle)
        return "packets=${s[0]} bytes=${s[1]} errors=${s[2]} overruns=${s[3]} empty=${s[4]} urbs=${s[5]} buffered=${s[6]}"
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { UsbIsoAudioNative.close(handle) }
        // Hands the streaming interface back so the kernel can rebind its audio driver.
        runCatching { connection.releaseInterface(usbInterface) }
    }

    companion object {
        private val EMPTY = ByteArray(0)
        private const val GET_DESCRIPTOR = 0x06
        private const val CONFIGURATION_DESCRIPTOR = 0x02
        private const val SET_CUR = 0x01
        private const val SAMPLING_FREQ_CONTROL = 0x01

        /** Reads the full configuration descriptor for [configurationValue] (bConfigurationValue). */
        fun readConfigurationDescriptor(connection: UsbDeviceConnection, device: UsbDevice, configurationValue: Int): ByteArray? {
            for (index in 0 until device.configurationCount) {
                val header = ByteArray(9)
                val got = connection.controlTransfer(UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD, GET_DESCRIPTOR,
                    (CONFIGURATION_DESCRIPTOR shl 8) or index, 0, header, header.size, 1_000)
                if (got < 9) continue
                if ((header[5].toInt() and 0xff) != configurationValue) continue
                val total = (header[2].toInt() and 0xff) or ((header[3].toInt() and 0xff) shl 8)
                val full = ByteArray(total)
                val read = connection.controlTransfer(UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD, GET_DESCRIPTOR,
                    (CONFIGURATION_DESCRIPTOR shl 8) or index, 0, full, full.size, 1_000)
                return if (read > 0) full.copyOf(read) else null
            }
            return null
        }

        /**
         * Opens direct capture at [rateHz]. [interfaces] must contain the alternate-setting
         * interfaces of the active configuration (Android lists each alternate separately).
         */
        fun open(
            connection: UsbDeviceConnection,
            interfaces: List<UsbInterface>,
            alternates: List<UsbAudioAlternate>,
            rateHz: Int,
            onDiagnostic: (String) -> Unit,
        ): UsbIsoAudioCapture {
            val alternate = UsbAudioDescriptors.chooseInput(alternates, rateHz)
                ?: throw IOException("No USB audio input format for $rateHz Hz among ${alternates.map { it.describe() }}")
            val usbInterface = interfaces.firstOrNull { it.id == alternate.interfaceNumber && it.alternateSetting == alternate.alternateSetting }
                ?: interfaces.firstOrNull { it.id == alternate.interfaceNumber }
                ?: throw IOException("Android does not list audio interface ${alternate.interfaceNumber}")
            if (!connection.claimInterface(usbInterface, true)) throw IOException("Could not claim audio interface ${alternate.interfaceNumber}")
            try {
                // About 8 ms of packets per URB, six URBs in flight: ~50 ms of pipeline.
                val packetsPerUrb = (8_000 / alternate.packetPeriodMicros).coerceIn(1, 64)
                val urbCount = 6
                val handle = UsbIsoAudioNative.open(connection.fileDescriptor, alternate.interfaceNumber, alternate.alternateSetting,
                    alternate.endpointAddress, alternate.maxPacketSize, packetsPerUrb, urbCount)
                val rate = byteArrayOf((rateHz and 0xff).toByte(), ((rateHz shr 8) and 0xff).toByte(), ((rateHz shr 16) and 0xff).toByte())
                val set = connection.controlTransfer(
                    UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or 0x02 /* recipient endpoint */,
                    SET_CUR, SAMPLING_FREQ_CONTROL shl 8, alternate.endpointAddress, rate, rate.size, 1_000,
                )
                onDiagnostic("direct usb audio ${alternate.describe()} packetsPerUrb=$packetsPerUrb urbs=$urbCount setRate=$set")
                return UsbIsoAudioCapture(connection, usbInterface, alternate, rateHz, handle, packetsPerUrb, urbCount)
            } catch (error: Throwable) {
                runCatching { connection.releaseInterface(usbInterface) }
                throw error
            }
        }
    }
}
