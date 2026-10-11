// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork: feeds Android's media volume into the loudness compensation of the software DSP.
package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.media.AudioEffectSettings
import com.shilapi.xcertplay.media.LoudnessCompensator
import kotlin.math.roundToInt

/**
 * Tracks the listening level for [LoudnessCompensator] while a session runs. Only when the knob is
 * Android's software media volume ([AudioPath.Knob.SOFTWARE]) does STREAM_MUSIC say how loud the
 * car is; with a hardware knob (or while still detecting) the level stays at 0 dB, which turns the
 * compensation into a pass-through instead of guessing.
 *
 * Index → dB follows Android's typical media volume curve: each step below the maximum is roughly
 * 60 / maxIndex dB, clamped to −60 dB. The level is relative to the user's reference step.
 * Updates come from the "android.media.VOLUME_CHANGED_ACTION" broadcast, with a 2 s poll as a
 * fallback for head units whose firmware changes the volume without broadcasting it.
 */
object LoudnessVolumeTracker {
    private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    private const val POLL_MILLIS = 2_000L
    private const val RANGE_DB = 60.0

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var app: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var sessions = 0
    @Volatile var lastIndex = -1; private set
    @Volatile var lastMax = 0; private set
    @Volatile var tracking = false; private set

    private val poll = object : Runnable {
        override fun run() {
            app?.let { update(it) }
            if (tracking) handler.postDelayed(this, POLL_MILLIS)
        }
    }

    /** Listening level in dB relative to [referenceStep] (0 = the maximum step); 0 or below. */
    fun levelDb(index: Int, maxIndex: Int, referenceStep: Int): Double {
        if (maxIndex <= 0 || index < 0) return 0.0
        val reference = if (referenceStep in 1..maxIndex) referenceStep else maxIndex
        val db = -(reference - index.coerceIn(0, maxIndex)) * (RANGE_DB / maxIndex)
        return db.coerceIn(LoudnessCompensator.MIN_LEVEL_DB, 0.0)
    }

    /** Called from [AudioPath.onSessionStart]. */
    fun start(context: Context) {
        val appContext = context.applicationContext
        synchronized(this) {
            sessions++
            if (tracking) return
            app = appContext
            tracking = true
            receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == VOLUME_CHANGED) update(ctx.applicationContext)
                }
            }
            runCatching { appContext.registerReceiver(receiver, IntentFilter(VOLUME_CHANGED)) }
            handler.removeCallbacks(poll)
            handler.post(poll)
        }
    }

    /** Called from [AudioPath.onSessionEnd]; the last session out stops tracking and resets the level. */
    fun stop(context: Context) {
        synchronized(this) {
            sessions = (sessions - 1).coerceAtLeast(0)
            if (sessions > 0 || !tracking) return
            tracking = false
            handler.removeCallbacks(poll)
            receiver?.let { r -> runCatching { context.applicationContext.unregisterReceiver(r) } }
            receiver = null
            app = null
            LoudnessCompensator.listeningLevelDb = 0.0
        }
    }

    /** Re-reads the volume now (for example after the reference step changed); no-op outside a session. */
    fun refresh(context: Context) {
        if (tracking) update(context.applicationContext)
    }

    private fun update(context: Context) {
        if (!tracking) return
        val manager = context.getSystemService(AudioManager::class.java)
        if (manager == null || AudioPath.knob(context) != AudioPath.Knob.SOFTWARE) {
            LoudnessCompensator.listeningLevelDb = 0.0
            return
        }
        val index = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        val max = runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        lastIndex = index; lastMax = max
        val reference = AudioEffectSettings.current(context).loudnessCompReferenceStep
        LoudnessCompensator.listeningLevelDb = levelDb(index, max, reference)
    }

    /** One line for the equalizer page: what the compensation sees right now. */
    fun describe(context: Context): String {
        val app = context.applicationContext
        if (AudioPath.knob(app) != AudioPath.Knob.SOFTWARE) {
            return "Now: inactive, the knob is not detected as Android's volume (see Audio path)."
        }
        val manager = app.getSystemService(AudioManager::class.java) ?: return "Now: media volume unavailable."
        val index = runCatching { manager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        val max = runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
        if (index < 0 || max <= 0) return "Now: media volume unavailable."
        val settings = AudioEffectSettings.current(app)
        val level = levelDb(index, max, settings.loudnessCompReferenceStep)
        val amount = if (settings.loudnessComp) settings.loudnessCompAmount / 100.0 else 0.0
        val low = LoudnessCompensator.lowShelfDb(level, amount)
        val high = LoudnessCompensator.highShelfDb(level, amount)
        return "Now: volume $index / $max, ${level.roundToInt()} dB vs reference" +
            (if (amount > 0) " → bass +${"%.1f".format(low)} dB, treble +${"%.1f".format(high)} dB." else ".")
    }
}
