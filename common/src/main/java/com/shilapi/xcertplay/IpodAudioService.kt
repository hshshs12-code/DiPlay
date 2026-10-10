// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.shilapi.xcertplay.host.R

/** Keeps iPod audio playing while the user is in other head-unit apps, with transport controls. */
class IpodAudioService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastNotificationKey = ""
    private val listener: () -> Unit = { handler.post { if (IpodAudioEngine.isRunning()) notify(false) } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                IpodAudioEngine.stop()
                IpodMediaSession.stop()
                IpodAudioEngine.listeners.remove(listener)
                stopForeground(true); stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PLAY_PAUSE -> { IpodAudioEngine.playPause(); return START_NOT_STICKY }
            ACTION_NEXT -> { IpodAudioEngine.next(); return START_NOT_STICKY }
            ACTION_PREVIOUS -> { IpodAudioEngine.previous(); return START_NOT_STICKY }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "iPod audio", NotificationManager.IMPORTANCE_LOW))
        }
        IpodMediaSession.start(this)
        notify(true)
        if (wakeLock == null) runCatching {
            wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DiPlay:ipod")
                ?.apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        if (!IpodAudioEngine.listeners.contains(listener)) IpodAudioEngine.listeners.add(listener)
        if (!IpodAudioEngine.isRunning()) IpodAudioEngine.start(this)
        return START_NOT_STICKY
    }

    private fun notify(force: Boolean) {
        val info = IpodAudioEngine.nowPlaying
        val playing = IpodAudioEngine.playing
        val key = "${info.title}|${info.artist}|$playing|${IpodAudioEngine.state}|${System.identityHashCode(IpodAudioEngine.artwork)}"
        if (!force && key == lastNotificationKey) return
        lastNotificationKey = key
        val open = PendingIntent.getActivity(this, 0, Intent(this, IpodModeActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(code: Int, name: String, icon: Int, title: String) = Notification.Action.Builder(
            Icon.createWithResource(this, icon), title,
            PendingIntent.getService(this, code, Intent(this, IpodAudioService::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        ).build()
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        builder.setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle(info.title ?: "DiPlay iPod audio")
            .setContentText(listOfNotNull(info.artist, info.album).joinToString(" · ").ifEmpty { IpodAudioEngine.status })
            .setSubText(if (info.title != null) IpodAudioEngine.status else null)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setLargeIcon(IpodAudioEngine.artwork)
            .addAction(action(1, ACTION_PREVIOUS, android.R.drawable.ic_media_previous, "Previous"))
            .addAction(action(2, ACTION_PLAY_PAUSE, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "Pause" else "Play"))
            .addAction(action(3, ACTION_NEXT, android.R.drawable.ic_media_next, "Next"))
            .addAction(action(4, ACTION_STOP, android.R.drawable.ic_menu_close_clear_cancel, "Stop"))
        val style = Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)
        IpodMediaSession.token?.let { style.setMediaSession(it) }
        builder.style = style
        startForeground(2, builder.build())
    }

    override fun onDestroy() {
        IpodAudioEngine.listeners.remove(listener)
        IpodAudioEngine.stop()
        IpodMediaSession.stop()
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }; wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.shihab.diplay.IPOD_STOP"
        const val ACTION_PLAY_PAUSE = "com.shihab.diplay.IPOD_PLAY_PAUSE"
        const val ACTION_NEXT = "com.shihab.diplay.IPOD_NEXT"
        const val ACTION_PREVIOUS = "com.shihab.diplay.IPOD_PREVIOUS"
        private const val CHANNEL = "diplay_ipod"
    }
}
