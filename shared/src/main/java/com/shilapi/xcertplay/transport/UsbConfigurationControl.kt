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

    /**
     * Detaches kernel drivers from every interface of [configuration] and keeps them detached.
     *
     * Android's releaseInterface re-attaches the kernel driver at once, so a claim/release pair
     * changes nothing. Claiming on a throwaway connection and then closing that connection drops
     * the claims without a re-attach, which leaves the interfaces unbound for the configuration
     * change that follows on another connection.
     */
    fun detachKernelDrivers(
        openConnection: () -> UsbDeviceConnection?,
        configuration: UsbConfiguration,
    ): String {
        val scratch = openConnection() ?: return "no-connection"
        val seen = HashSet<Int>()
        val parts = ArrayList<String>()
        try {
            for (index in 0 until configuration.interfaceCount) {
                val iface = configuration.getInterface(index)
                if (!seen.add(iface.id)) continue
                val claimed = runCatching { scratch.claimInterface(iface, true) }.getOrDefault(false)
                parts += "${iface.id}:${if (claimed) "detached" else "claim-refused"}"
            }
        } finally {
            // Close without releaseInterface: the kernel drops the claims and leaves the
            // interfaces unbound instead of handing them back to snd-usb-audio / usbhid.
            runCatching { scratch.close() }
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
        openConnection: () -> UsbDeviceConnection?,
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
        if (current == null) {
            onDiagnostic("USB active configuration unknown; detaching nothing")
            return false
        }
        for (round in 1..DETACH_ROUNDS) {
            val detached = detachKernelDrivers(openConnection, current)
            Thread.sleep(SETTLE_MILLIS)
            onDiagnostic("USB kernel drivers detached round=$round configuration=${current.id}: $detached; " +
                UsbClaimDiagnostics.describe(device))
            selected = connection.setConfiguration(target)
            active = activeConfiguration(connection)
            onDiagnostic("USB setConfiguration retry round=$round target=${target.id} ok=$selected active=${active ?: "unknown"}")
            if (selected && (active == null || active == target.id)) return true
            // A fresh descriptor may be needed after the unbind; try the switch on a new one too.
            val fresh = openConnection()
            if (fresh != null) {
                val freshOk = runCatching { fresh.setConfiguration(target) }.getOrDefault(false)
                runCatching { fresh.close() }
                active = activeConfiguration(connection)
                onDiagnostic("USB setConfiguration fresh-connection round=$round ok=$freshOk active=${active ?: "unknown"}")
                if (active == target.id) return true
            }
        }
        return false
    }

    private const val DETACH_ROUNDS = 3
    private const val SETTLE_MILLIS = 150L
}
