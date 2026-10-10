// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork: make DiPlay the only sound processing between the music and the car's amplifier.
package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.SystemClock
import android.util.Log

/**
 * Everything DiPlay can do about the rest of the Android audio path: open the track at the
 * hardware's own rate, hand the mixer floats, keep Android's software volume at unity when the
 * knob is hardware, and switch off the MediaTek "Bes" enhancements. Also works out whether the
 * volume knob drives Android's media volume (software) or the amplifier chip (hardware).
 */
object AudioPath {
    enum class Knob { UNKNOWN, SOFTWARE, HARDWARE }

    private const val PREFS = "diplay_audio_path"
    private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    private const val EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    private const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
    private const val HARDWARE_AFTER_MILLIS = 10 * 60 * 1000L
    /** MediaTek vendor parameters; harmless on HALs that do not know them. */
    private val VENDOR_OFF = listOf("SetBesLoudnessStatus=0", "SetBesSurroundStatus=0", "BesSurround_OnOff=0", "SetMusicPlusStatus=0", "AudioEnhance=0")
    private val VENDOR_READ = listOf("GetBesLoudnessStatus", "GetBesSurroundStatus", "GetMusicPlusStatus")

    private var receiver: BroadcastReceiver? = null
    private var sessions = 0
    private var sessionStartedAt = 0L
    @Volatile private var lastSetByUs = -1
    @Volatile private var lastSetAt = 0L
    @Volatile var lastVendorResult: String = ""; private set
    @Volatile var lastVolumeNote: String = ""; private set

    fun nativeSampleRate(context: Context): Int =
        runCatching { context.getSystemService(AudioManager::class.java)?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toInt() }.getOrNull() ?: 0

    fun nativeFramesPerBuffer(context: Context): Int =
        runCatching { context.getSystemService(AudioManager::class.java)?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toInt() }.getOrNull() ?: 0

    /** The rate the media track should open at, or 0 to stay at the stream's rate. */
    fun mediaOutputSampleRate(context: Context): Int {
        if (!AirPlayPersistence.loadNativeRateOutput(context)) return 0
        val native = nativeSampleRate(context)
        return if (native in 8_000..192_000) native else 0
    }

    fun floatOutput(context: Context): Boolean = AirPlayPersistence.loadFloatOutput(context)

    fun knob(context: Context): Knob {
        val stored = prefs(context).getString("knob", null)
        val fromPrefs = Knob.entries.firstOrNull { it.name == stored } ?: Knob.UNKNOWN
        if (fromPrefs != Knob.UNKNOWN) return fromPrefs
        val manager = context.getSystemService(AudioManager::class.java) ?: return Knob.UNKNOWN
        return if (manager.isVolumeFixed) Knob.HARDWARE else Knob.UNKNOWN
    }

    fun knobEvidence(context: Context): String = prefs(context).getString("knob_evidence", "") ?: ""

    /** User override from the settings page; null returns to automatic detection. */
    fun setKnob(context: Context, knob: Knob?, evidence: String) {
        prefs(context).edit().putString("knob", knob?.name).putString("knob_evidence", evidence).apply()
    }

    /** Call when a CarPlay or iPod session starts: vendor tweaks, volume pin, knob watching. */
    fun onSessionStart(context: Context, log: (String) -> Unit) {
        val app = context.applicationContext
        val manager = app.getSystemService(AudioManager::class.java) ?: return
        if (AirPlayPersistence.loadDisableVendorEnhancements(app)) {
            val results = VENDOR_OFF.map { parameter -> runCatching { manager.setParameters(parameter) }.fold({ "$parameter ok" }, { "$parameter failed" }) }
            val readback = VENDOR_READ.map { key -> "$key=${runCatching { manager.getParameters(key) }.getOrDefault("?").ifEmpty { "(no answer)" }}" }
            lastVendorResult = readback.joinToString(" ")
            log("audio path: vendor enhancements off → ${results.joinToString(", ")}; readback ${lastVendorResult}")
        }
        pinVolume(app, manager, log)
        synchronized(this) {
            sessions++
            if (receiver == null) {
                sessionStartedAt = SystemClock.elapsedRealtime()
                receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        if (intent.action != VOLUME_CHANGED || intent.getIntExtra(EXTRA_STREAM, -1) != AudioManager.STREAM_MUSIC) return
                        val value = intent.getIntExtra(EXTRA_VALUE, -1)
                        onMediaVolumeChanged(ctx.applicationContext, value, log)
                    }
                }
                runCatching { app.registerReceiver(receiver, IntentFilter(VOLUME_CHANGED)) }
                    .onFailure { log("audio path: volume watcher unavailable: ${it.message}") }
            }
        }
        log("audio path: ${describe(app)}")
    }

    fun onSessionEnd(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            sessions = (sessions - 1).coerceAtLeast(0)
            if (sessions == 0) {
                receiver?.let { runCatching { app.unregisterReceiver(it) } }
                receiver = null
                // No external volume changes across enough session time: the knob is not Android's volume.
                if (knob(app) == Knob.UNKNOWN) {
                    val watched = prefs(app).getLong("watched_ms", 0L) + (SystemClock.elapsedRealtime() - sessionStartedAt)
                    prefs(app).edit().putLong("watched_ms", watched).apply()
                    if (watched >= HARDWARE_AFTER_MILLIS) {
                        setKnob(app, Knob.HARDWARE, "Android's media volume never moved during ${watched / 60000} min of sessions, so the knob drives the amplifier.")
                    }
                }
            }
        }
    }

    private fun onMediaVolumeChanged(app: Context, value: Int, log: (String) -> Unit) {
        if (value < 0) return
        val recent = SystemClock.elapsedRealtime() - lastSetAt < 3_000 && value == lastSetByUs
        if (recent) return
        if (knob(app) != Knob.SOFTWARE) {
            setKnob(app, Knob.SOFTWARE, "Android's media volume changed to $value while DiPlay was not touching it: the knob (or something else) drives the software volume.")
            lastVolumeNote = "Software volume detected; DiPlay stopped pinning it so the knob keeps working."
            log("audio path: $lastVolumeNote")
        }
    }

    private fun pinVolume(app: Context, manager: AudioManager, log: (String) -> Unit) {
        if (!AirPlayPersistence.loadPinMediaVolume(app)) return
        val knob = knob(app)
        if (knob == Knob.SOFTWARE) { lastVolumeNote = "Not pinning media volume: the knob is Android's software volume."; return }
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (current >= max) { lastVolumeNote = "Media volume already at maximum ($max)."; return }
        lastSetByUs = max; lastSetAt = SystemClock.elapsedRealtime()
        val ok = runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0) }.isSuccess
        lastVolumeNote = if (ok) "Media volume raised from $current to $max so Android's mixer runs at unity gain." else "Could not change media volume."
        log("audio path: $lastVolumeNote")
    }

    /** Human-readable state of the path, for the settings page and the diagnostic report. */
    fun describe(context: Context): String {
        val app = context.applicationContext
        val manager = app.getSystemService(AudioManager::class.java)
        val native = nativeSampleRate(app)
        val frames = nativeFramesPerBuffer(app)
        val max = manager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
        val current = manager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        val knob = knob(app)
        val effects = runCatching { AudioEffect.queryEffects().orEmpty().map { it.name }.distinct() }.getOrDefault(emptyList())
        return buildString {
            append("Hardware output: ").append(if (native > 0) "$native Hz" else "rate unknown")
            if (frames > 0) append(", $frames frames/buffer (${frames * 1000 / maxOf(1, native)} ms)")
            append(". Media track: ")
            append(if (mediaOutputSampleRate(app) > 0) "${mediaOutputSampleRate(app)} Hz, DiPlay resampler" else "stream rate, Android resamples")
            append(if (floatOutput(app)) ", float" else ", 16-bit").append(".\n")
            append("Android media volume: $current / $max").append(if (manager?.isVolumeFixed == true) " (fixed by the device)" else "").append(". ")
            append(when (knob) {
                Knob.HARDWARE -> "Knob: hardware (amplifier chip). "
                Knob.SOFTWARE -> "Knob: Android software volume. "
                Knob.UNKNOWN -> "Knob: still detecting (turn it during a session). "
            })
            knobEvidence(app).takeIf { it.isNotEmpty() }?.let { append(it).append(' ') }
            if (lastVolumeNote.isNotEmpty()) append(lastVolumeNote)
            append("\nVendor enhancements: ").append(lastVendorResult.ifEmpty { if (AirPlayPersistence.loadDisableVendorEnhancements(app)) "switched off at the next session" else "left as the firmware set them" })
            append(".\nInstalled audio effects: ").append(if (effects.isEmpty()) "none reported" else effects.joinToString(", "))
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private const val TAG = "DiPlay-AudioPath"
    @Suppress("unused") private fun debug(line: String) = Log.i(TAG, line)
}
