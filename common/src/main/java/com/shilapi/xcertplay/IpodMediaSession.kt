// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.media.AudioFocusToken
import com.shilapi.xcertplay.media.abandonAudioFocusCompat
import com.shilapi.xcertplay.media.requestAudioFocusCompat

/**
 * Publishes the iPhone's Now Playing state through Android's media session so steering-wheel
 * keys, the head unit's own media widgets and Bluetooth remotes drive the phone, and holds media
 * audio focus so the car's other sources pause like they would for a native USB iPod source.
 */
internal object IpodMediaSession {
    private val handler = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var focus: AudioFocusToken? = null
    private var appContext: Context? = null
    private var placeholder: Bitmap? = null
    private var lastKey = ""
    private val listener: () -> Unit = { handler.post { publish() } }
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { IpodAudioEngine.muted = false; IpodAudioEngine.pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> IpodAudioEngine.muted = true
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> IpodAudioEngine.muted = true
            AudioManager.AUDIOFOCUS_GAIN -> IpodAudioEngine.muted = false
        }
    }

    val token: MediaSession.Token? get() = session?.sessionToken

    fun start(context: Context) {
        if (session != null) return
        appContext = context.applicationContext
        val created = MediaSession(context.applicationContext, "DiPlay-iPod")
        @Suppress("DEPRECATION")
        created.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        created.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { IpodAudioEngine.muted = false; IpodAudioEngine.play() }
            override fun onPause() { IpodAudioEngine.pause() }
            override fun onSkipToNext() { IpodAudioEngine.next() }
            override fun onSkipToPrevious() { IpodAudioEngine.previous() }
            override fun onStop() { IpodAudioEngine.pause() }
            override fun onSeekTo(pos: Long) { IpodAudioEngine.seekTo(pos) }
        }, handler)
        created.isActive = true
        session = created
        val manager = context.getSystemService(AudioManager::class.java)
        if (manager != null) {
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            focus = requestAudioFocusCompat(manager, AudioManager.AUDIOFOCUS_GAIN, attributes, focusListener, handler).first
        }
        IpodAudioEngine.listeners.add(listener)
        lastKey = ""
        publish()
    }

    fun stop() {
        IpodAudioEngine.listeners.remove(listener)
        appContext?.getSystemService(AudioManager::class.java)?.let { manager -> focus?.let { abandonAudioFocusCompat(manager, it) } }
        focus = null
        session?.let { it.isActive = false; it.release() }
        session = null
    }

    private fun publish() {
        val current = session ?: return
        val info = IpodAudioEngine.nowPlaying
        val art = IpodAudioEngine.artwork ?: placeholder ?: appContext?.let(CarPlayMediaKeys::placeholderArt)?.also { placeholder = it }
        val key = "${info.title}|${info.artist}|${info.album}|${info.durationMillis}|${info.sourceApp}|${System.identityHashCode(art)}"
        if (key != lastKey) {
            lastKey = key
            current.setMetadata(CarPlayMediaKeys.androidMetadata(info, art))
        }
        val playing = IpodAudioEngine.playing
        current.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP)
            .setState(
                when {
                    !IpodAudioEngine.isRunning() -> PlaybackState.STATE_STOPPED
                    IpodAudioEngine.state != IpodAudioEngine.State.READY -> PlaybackState.STATE_CONNECTING
                    playing -> PlaybackState.STATE_PLAYING
                    else -> PlaybackState.STATE_PAUSED
                },
                IpodAudioEngine.elapsedNow() ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (playing) 1f else 0f,
                SystemClock.elapsedRealtime(),
            ).build())
    }
}
