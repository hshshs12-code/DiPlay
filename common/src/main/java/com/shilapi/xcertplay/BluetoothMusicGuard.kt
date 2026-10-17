// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork, friend build: keep the head unit's own Bluetooth music profile out of a CarPlay session.
package com.shilapi.xcertplay

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context

/**
 * Wireless CarPlay pairs the phone over Bluetooth, and these head units then auto-connect their
 * own A2DP sink ("Bluetooth music") to it. iOS keeps whatever audio route was active, so music
 * started before CarPlay came up stays on the car's Bluetooth instead of moving to CarPlay. A real
 * wireless-CarPlay car drops A2DP once CarPlay runs and keeps only hands-free for calls; this does
 * the same: while a session is active the phone's A2DP priority is turned off and the profile is
 * disconnected, and both are restored when the session ends. Hidden A2DP methods, reachable by
 * reflection on Android 8.
 */
object BluetoothMusicGuard {
    private const val PRIORITY_ON = 100
    private const val PRIORITY_OFF = 0
    @Volatile private var guardedAddress: String? = null

    fun enabled(context: Context): Boolean = AirPlayPersistence.rawTuned(context, "bt_music_guard")

    fun onCarPlayActive(context: Context, log: (String) -> Unit) {
        if (!enabled(context)) return
        val address = DiPlayPreferences.phoneAddress(context) ?: return
        guardedAddress = address
        withA2dp(context, log) { proxy, device ->
            val connected = runCatching { proxy.connectedDevices.any { it.address.equals(address, true) } }.getOrDefault(false)
            val priority = call(proxy, "setPriority", device, PRIORITY_OFF)
            val dropped = if (connected) call(proxy, "disconnect", device) else null
            log("bluetooth music guard: CarPlay active; A2DP connected=$connected priorityOff=${priority ?: "unavailable"} disconnect=${dropped ?: "not needed"}")
        }
    }

    fun onCarPlayEnded(context: Context, log: (String) -> Unit) {
        val address = guardedAddress ?: return
        guardedAddress = null
        withA2dp(context, log, address) { proxy, device ->
            val priority = call(proxy, "setPriority", device, PRIORITY_ON)
            log("bluetooth music guard: CarPlay ended; A2DP priority restored=${priority ?: "unavailable"}")
        }
    }

    private fun withA2dp(context: Context, log: (String) -> Unit, address: String? = null, action: (BluetoothA2dp, BluetoothDevice) -> Unit) {
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull() ?: return
        val target = address ?: DiPlayPreferences.phoneAddress(context) ?: return
        val device = runCatching { adapter.getRemoteDevice(target) }.getOrNull() ?: return
        val opened = runCatching {
            adapter.getProfileProxy(context.applicationContext, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    try { if (proxy is BluetoothA2dp) action(proxy, device) }
                    catch (error: RuntimeException) { log("bluetooth music guard: ${error.javaClass.simpleName}: ${error.message}") }
                    finally { runCatching { adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy) } }
                }
                override fun onServiceDisconnected(profile: Int) = Unit
            }, BluetoothProfile.A2DP)
        }.getOrDefault(false)
        if (!opened) log("bluetooth music guard: A2DP profile unavailable on this head unit")
    }

    /** Invokes a hidden BluetoothA2dp method; returns null when the firmware does not expose it. */
    private fun call(proxy: BluetoothA2dp, name: String, device: BluetoothDevice, priority: Int? = null): Boolean? = runCatching {
        if (priority == null) proxy.javaClass.getMethod(name, BluetoothDevice::class.java).invoke(proxy, device) as? Boolean
        else proxy.javaClass.getMethod(name, BluetoothDevice::class.java, Int::class.javaPrimitiveType).invoke(proxy, device, priority) as? Boolean
    }.getOrNull()
}
