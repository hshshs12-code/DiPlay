// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.shilapi.xcertplay.host.R

/** Keeps iPod audio playing while the user is in other head-unit apps. */
class IpodAudioService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            IpodAudioEngine.stop()
            stopForeground(true); stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "iPod audio", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, IpodModeActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, IpodAudioService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val notification = builder.setSmallIcon(R.drawable.ic_diplay_notification).setContentTitle("DiPlay iPod audio")
            .setContentText("Playing iPhone audio over USB").setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        startForeground(2, notification)
        if (wakeLock == null) runCatching {
            wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DiPlay:ipod")?.apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        if (!IpodAudioEngine.isRunning()) IpodAudioEngine.start(this)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        IpodAudioEngine.stop()
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }; wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.shihab.diplay.IPOD_STOP"
        private const val CHANNEL = "diplay_ipod"
    }
}
