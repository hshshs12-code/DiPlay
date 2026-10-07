package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import java.io.File

/**
 * Describes who holds an iPhone's USB interfaces when Android refuses to claim one. Reads the
 * world-readable sysfs tree: for each interface of the device's current configuration it reports
 * the kernel driver bound to it (for example cdc_ncm, usbfs for another app, or none).
 */
internal object UsbClaimDiagnostics {
    fun describe(device: UsbDevice?): String {
        if (device == null) return "sysfs=device-unknown"
        return runCatching { describeOrThrow(device) }.getOrElse { "sysfs=unavailable(${it.javaClass.simpleName})" }
    }

    private fun describeOrThrow(device: UsbDevice): String {
        // /dev/bus/usb/001/004 -> busnum 1, devnum 4
        val parts = device.deviceName.trimEnd('/').split('/')
        val busnum = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: return "sysfs=bad-name(${device.deviceName})"
        val devnum = parts.lastOrNull()?.toIntOrNull() ?: return "sysfs=bad-name(${device.deviceName})"
        val root = File("/sys/bus/usb/devices")
        val entries = root.listFiles() ?: return "sysfs=unreadable"
        val node = entries.firstOrNull { dir ->
            !dir.name.contains(':') &&
                readInt(File(dir, "busnum")) == busnum && readInt(File(dir, "devnum")) == devnum
        } ?: return "sysfs=no-match bus=$busnum dev=$devnum"
        val configuration = readInt(File(node, "bConfigurationValue"))
        val interfaces = node.listFiles()
            ?.filter { it.name.startsWith(node.name + ":") }
            ?.sortedBy { it.name }
            ?.joinToString(" ") { iface ->
                val driver = runCatching { File(iface, "driver").canonicalFile.name }.getOrNull() ?: "none"
                val cls = runCatching { File(iface, "bInterfaceClass").readText().trim() }.getOrNull() ?: "?"
                "${iface.name.substringAfter(':')}(class=$cls driver=$driver)"
            } ?: "none"
        return "sysfs=${node.name} config=${configuration ?: "?"} interfaces=[$interfaces]"
    }

    private fun readInt(file: File): Int? = runCatching { file.readText().trim().toInt() }.getOrNull()
}
