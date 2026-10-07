package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

/**
 * Makes sure the iPhone is really in the CarPlay configuration before interfaces are claimed.
 *
 * Some head units (observed: MediaTek ac8227l, Android 8.1) put a re-enumerated iPhone into its
 * USB-audio configuration and bind snd-usb-audio and usbhid to it, treating the phone as an iPod.
 * Linux refuses SET_CONFIGURATION from usbfs while any interface of the active configuration is
 * claimed, so DiPlay's selection of the CarPlay configuration silently failed and its later claims
 * targeted interfaces that do not exist in the audio configuration.
 *
 * Android's forced [UsbDeviceConnection.claimInterface] detaches a kernel driver from one
 * interface; releasing it afterwards leaves the interface unclaimed. Doing that for every
 * interface of the active configuration lets the configuration change go through.
 */
internal object UsbConfigurationControl {
    private const val GET_CONFIGURATION = 0x08
    private const val TIMEOUT_MILLIS = 1_000

    /** The bConfigurationValue the device reports as active, or null when the request fails. */
    fun activeConfiguration(connection: UsbDeviceConnection): Int? {
        val buffer = ByteArray(1)
        val transferred = runCatching {
            connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD,
                GET_CONFIGURATION, 0, 0, buffer, 1, TIMEOUT_MILLIS,
            )
        }.getOrDefault(-1)
        return if (transferred == 1) buffer[0].toInt() and 0xff else null
    }

    private fun configurationById(device: UsbDevice, id: Int): UsbConfiguration? {
        for (index in 0 until device.configurationCount) {
            val configuration = device.getConfiguration(index)
            if (configuration.id == id) return configuration
        }
        return null
    }

    /** Detaches kernel drivers from every interface of [configuration]; returns a summary. */
    fun releaseKernelDrivers(connection: UsbDeviceConnection, configuration: UsbConfiguration): String {
        val seen = HashSet<Int>()
        val parts = ArrayList<String>()
        for (index in 0 until configuration.interfaceCount) {
            val iface = configuration.getInterface(index)
            if (!seen.add(iface.id)) continue
            val claimed = runCatching { connection.claimInterface(iface, true) }.getOrDefault(false)
            val released = if (claimed) runCatching { connection.releaseInterface(iface) }.getOrDefault(false) else false
            parts += "${iface.id}:${if (claimed) "detached" else "claim-refused"}${if (claimed && !released) "/release-failed" else ""}"
        }
        return parts.joinToString(",")
    }

    /**
     * Selects [target] and verifies it is active. Returns true on success. Every step is reported
     * through [onDiagnostic] so a refusal names what blocked it.
     */
    fun ensureConfiguration(
        device: UsbDevice,
        connection: UsbDeviceConnection,
        target: UsbConfiguration,
        onDiagnostic: (String) -> Unit,
    ): Boolean {
        val before = activeConfiguration(connection)
        if (before == target.id) {
            onDiagnostic("USB configuration active=${target.id} already")
            return true
        }
        var selected = connection.setConfiguration(target)
        var active = activeConfiguration(connection)
        onDiagnostic("USB setConfiguration target=${target.id} previous=${before ?: "unknown"} ok=$selected active=${active ?: "unknown"}")
        if (selected && (active == null || active == target.id)) return true
        // Blocked by drivers bound to the current configuration: detach them and try again.
        val current = (active ?: before)?.let { configurationById(device, it) }
        if (current != null) {
            val detached = releaseKernelDrivers(connection, current)
            onDiagnostic("USB kernel drivers detached from configuration ${current.id}: $detached")
        } else {
            onDiagnostic("USB active configuration unknown; detaching nothing")
        }
        selected = connection.setConfiguration(target)
        active = activeConfiguration(connection)
        onDiagnostic("USB setConfiguration retry target=${target.id} ok=$selected active=${active ?: "unknown"}")
        return selected && (active == null || active == target.id)
    }
}
