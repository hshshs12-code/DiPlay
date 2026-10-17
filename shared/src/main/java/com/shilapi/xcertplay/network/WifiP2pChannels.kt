package com.shilapi.xcertplay.network

/** Non-DFS channel choices. Android still enforces the radio's capabilities and country limits. */
object WifiP2pChannels {
    const val AUTO = 0
    /** Plain createGroup with no channel pinned: the car's driver picks; the live channel is then observed. */
    const val SYSTEM_DEFAULT = -1
    val channels: List<Int> = listOf(36, 40, 44, 48, 149, 153, 157, 161, 165) + (1..11)

    fun isValid(channel: Int): Boolean = channel == AUTO || channel == SYSTEM_DEFAULT || channel in channels

    fun frequencyMhz(channel: Int): Int? {
        require(isValid(channel)) { "Unsupported Wi-Fi Direct channel: $channel" }
        return when {
            channel == AUTO || channel == SYSTEM_DEFAULT -> null
            channel <= 11 -> 2407 + channel * 5
            else -> 5000 + channel * 5
        }
    }
}

internal class P2pChannelUnavailableException(channel: Int, details: String, cause: Throwable? = null) :
    java.io.IOException(
        if (channel == WifiP2pChannels.SYSTEM_DEFAULT) "Wi-Fi Direct could not create a group with the car's own settings. Choose Auto or a channel. $details"
        else "Wi-Fi Direct could not use channel $channel. Choose Auto or another channel. $details", cause)
