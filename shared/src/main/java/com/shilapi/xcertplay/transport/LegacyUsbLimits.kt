package com.shilapi.xcertplay.transport

import android.os.Build

/**
 * Before Android 9 (API 28) `UsbDeviceConnection.bulkTransfer` and `UsbRequest.queue` silently clip
 * one transfer to 16384 bytes (the old usbfs limit kept in libusbhost). Reads already reassemble
 * split USB reads; writes must be split by the caller or the short count fails the session.
 */
internal object LegacyUsbLimits {
    const val LEGACY_MAX_TRANSFER_BYTES = 16 * 1024

    fun maxTransferBytes(requested: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) requested
        else minOf(requested, LEGACY_MAX_TRANSFER_BYTES)
}
