// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork: finds other apps on the head unit that handle USB devices (factory phone-link
// apps) and offers the levers an ordinary app has against them: kill their background
// processes before a USB connect, and force-stop / disable / enable them through the head unit's
// own ADB when it is enabled and approved.
package com.shilapi.xcertplay

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb

object UsbCompetitors {
    data class Handler(
        val packageName: String,
        val label: String,
        val likely: Boolean,
        val system: Boolean,
        val enabled: Boolean,
        val declaresUsbAttach: Boolean,
    )

    class AdbOutcome(val access: LocalAdb.Access, val lines: List<String>)

    private const val PREFS = "diplay_usb_competitors"
    private const val KEY_STOP_SET = "stop_before_connect"
    private val KNOWN = listOf(
        "zlink", "carbit", "autokit", "carlink", "carplay", "headunit", "hicar", "easyconn", "easyconnect",
        "phonelink", "mirrorlink", "ecarx", "weblink", "androidauto", "auto_connect", "wlink", "smartlink",
        "carpl", "nexusplay", "dmd", "t-link", "tlink", "sinjet", "rikooo", "mnp",
    )

    fun list(context: Context): List<Handler> {
        val pm = context.packageManager
        val attach = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        val declared = runCatching {
            (pm.queryIntentActivities(attach, PackageManager.GET_META_DATA).map { it.activityInfo.packageName } +
                pm.queryBroadcastReceivers(attach, 0).map { it.activityInfo.packageName }).toSet()
        }.getOrDefault(emptySet())
        val installed = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        val byName = installed.filter { app ->
            val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault("")
            KNOWN.any { app.packageName.contains(it, true) || label.contains(it, true) }
        }.map { it.packageName }
        return (declared + byName).distinct()
            .filter { it != context.packageName }
            .mapNotNull { pkg ->
                val info = runCatching { pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS) }.getOrNull()
                    ?: runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
                val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(pkg)
                Handler(
                    packageName = pkg,
                    label = label,
                    likely = KNOWN.any { pkg.contains(it, true) || label.contains(it, true) },
                    system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    enabled = info.enabled,
                    declaresUsbAttach = pkg in declared,
                )
            }
            .sortedWith(compareByDescending<Handler> { it.likely }.thenByDescending { it.declaresUsbAttach }.thenBy { it.label })
    }

    fun stopSet(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_STOP_SET, emptySet())?.toSet() ?: emptySet()

    fun setStopBeforeConnect(context: Context, packageName: String, stop: Boolean) {
        val next = stopSet(context).toMutableSet().apply { if (stop) add(packageName) else remove(packageName) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_STOP_SET, next).apply()
    }

    /** Asks Android to kill the package's background processes; foreground services survive. */
    fun killBackground(context: Context, packageName: String): Boolean = runCatching {
        context.getSystemService(ActivityManager::class.java)?.killBackgroundProcesses(packageName)
        true
    }.getOrDefault(false)

    /**
     * Runs before a USB connect: kills the selected packages' background processes and, when the
     * head unit's ADB is already approved, force-stops them too (never shows the ADB dialog here).
     * Returns a short summary for the log.
     */
    fun stopSelected(context: Context): String {
        val targets = stopSet(context)
        if (targets.isEmpty()) return "no USB competitors selected"
        val killed = targets.filter { killBackground(context, it) }
        val adb = runCatching { LocalAdb(AdbKeys.load(context)) }.getOrNull()
        val forced = mutableListOf<String>()
        if (adb != null) {
            runCatching {
                if (adb.connect(mayAsk = false) == LocalAdb.Access.READY) {
                    for (pkg in targets) {
                        if (adb.shell("am force-stop ${quote(pkg)}") != null) forced += pkg
                    }
                }
            }
            runCatching { adb.close() }
        }
        return "killBackground=${killed.size}/${targets.size} adbForceStop=${forced.size}"
    }

    fun forceStopCommand(packageName: String) = "am force-stop ${quote(packageName)}"
    fun disableCommand(packageName: String) = "pm disable-user --user 0 ${quote(packageName)}"
    fun enableCommand(packageName: String) = "pm enable ${quote(packageName)}"

    /** Runs the commands through the head unit's ADB, offering the key for approval if needed. */
    fun runAdb(context: Context, commands: List<String>): AdbOutcome {
        val adb = LocalAdb(AdbKeys.load(context))
        try {
            val access = adb.connect(mayAsk = true)
            if (access != LocalAdb.Access.READY) return AdbOutcome(access, emptyList())
            val lines = commands.map { command ->
                val output = adb.shell("( $command ) 2>&1; echo \"exit=\$?\"") ?: "no reply"
                "$ $command\n${output.trim()}"
            }
            return AdbOutcome(access, lines)
        } finally {
            runCatching { adb.close() }
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
