// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork, friend build: automatic Wi-Fi Direct channel strategy ladder.
package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.network.WifiP2pChannels

/**
 * When the iPhone keeps accepting the Wi-Fi configuration but never joins the Wi-Fi Direct group
 * (first-TCP timeout), the channel strategy is the usual suspect on Android 8 units: the pin is a
 * request the driver may ignore, and the phone only scans the channel it was told. After every
 * [TIMEOUTS_PER_STEP] consecutive timeouts the preferred-channel setting moves one step along
 * [LADDER]; a rendered session keeps whatever worked.
 */
object WirelessFallback {
    private const val PREFS = "diplay_wireless_fallback"
    private const val KEY_STREAK = "first_tcp_timeouts"
    const val TIMEOUTS_PER_STEP = 2
    /** Auto (pinned ladder 5 GHz → 2.4 GHz) → car's own choice → pinned 1 → pinned 11 → pinned 6. */
    val LADDER = listOf(WifiP2pChannels.AUTO, WifiP2pChannels.SYSTEM_DEFAULT, 1, 11, 6)

    fun label(channel: Int): String = when (channel) {
        WifiP2pChannels.AUTO -> "auto (pinned ladder)"
        WifiP2pChannels.SYSTEM_DEFAULT -> "car's own choice (no pin)"
        else -> "pinned channel $channel"
    }

    fun streak(context: Context): Int = prefs(context).getInt(KEY_STREAK, 0)

    fun onFirstTcpTimeout(context: Context, log: (String) -> Unit) {
        val streak = streak(context) + 1
        prefs(context).edit().putInt(KEY_STREAK, streak).apply()
        if (streak % TIMEOUTS_PER_STEP != 0) {
            log("wireless fallback: the iPhone did not join the Wi-Fi Direct group (timeout $streak); one more try with ${label(AirPlayPersistence.loadWifiP2pPreferredChannel(context))} before changing strategy")
            return
        }
        val current = AirPlayPersistence.loadWifiP2pPreferredChannel(context)
        val next = LADDER[(LADDER.indexOf(current) + 1) % LADDER.size]
        AirPlayPersistence.saveWifiP2pPreferredChannel(context, next)
        log("wireless fallback: $streak timeouts with ${label(current)}; next attempt uses ${label(next)}")
    }

    fun onSessionRendered(context: Context, log: (String) -> Unit) {
        if (streak(context) == 0) return
        prefs(context).edit().putInt(KEY_STREAK, 0).apply()
        log("wireless fallback: CarPlay rendered with ${label(AirPlayPersistence.loadWifiP2pPreferredChannel(context))}; keeping it")
    }

    fun reset(context: Context) { prefs(context).edit().putInt(KEY_STREAK, 0).apply() }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
