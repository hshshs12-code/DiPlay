package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler

/**
 * Audio focus on Android 7 (API 24/25), where [AudioFocusRequest] (API 26) does not exist.
 *
 * Android 8+ uses the attribute-based request. Android 7 uses the legacy stream-based request
 * and maps the attribute usage to the closest stream type. The returned token is opaque; pass it
 * back to [abandonAudioFocusCompat].
 */
class AudioFocusToken internal constructor(
    internal val request: Any?,
    val listener: AudioManager.OnAudioFocusChangeListener,
)

fun requestAudioFocusCompat(
    manager: AudioManager,
    gain: Int,
    attributes: AudioAttributes,
    listener: AudioManager.OnAudioFocusChangeListener,
    handler: Handler,
): Pair<AudioFocusToken, Int> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val request = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(listener, handler)
            .build()
        return AudioFocusToken(request, listener) to manager.requestAudioFocus(request)
    }
    @Suppress("DEPRECATION")
    val result = manager.requestAudioFocus(listener, legacyStreamFor(attributes), gain)
    return AudioFocusToken(null, listener) to result
}

fun abandonAudioFocusCompat(manager: AudioManager, token: AudioFocusToken): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val request = token.request as? AudioFocusRequest ?: return AudioManager.AUDIOFOCUS_REQUEST_FAILED
        return manager.abandonAudioFocusRequest(request)
    }
    @Suppress("DEPRECATION")
    return manager.abandonAudioFocus(token.listener)
}

private fun legacyStreamFor(attributes: AudioAttributes): Int = when (attributes.usage) {
    AudioAttributes.USAGE_VOICE_COMMUNICATION,
    AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> AudioManager.STREAM_VOICE_CALL
    AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> AudioManager.STREAM_RING
    AudioAttributes.USAGE_ALARM -> AudioManager.STREAM_ALARM
    AudioAttributes.USAGE_NOTIFICATION,
    AudioAttributes.USAGE_NOTIFICATION_EVENT -> AudioManager.STREAM_NOTIFICATION
    else -> AudioManager.STREAM_MUSIC
}
