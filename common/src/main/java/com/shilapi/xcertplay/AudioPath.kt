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
    private val VENDOR_SETTERS = mapOf("GetBesLoudnessStatus" to "SetBesLoudnessStatus", "GetBesSurroundStatus" to "SetBesSurroundStatus", "GetMusicPlusStatus" to "SetMusicPlusStatus")

    /** Current vendor enhancement states as key → "0"/"1", skipping anything the HAL does not answer. */
    private fun vendorStates(manager: AudioManager): Map<String, String> = VENDOR_READ.mapNotNull { key ->
        val raw = runCatching { manager.getParameters(key) }.getOrNull().orEmpty()
        val value = raw.substringAfterLast('=', "").trim()
        if (value == "0" || value == "1") key to value else null
    }.toMap()

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
        val p = prefs(context)
        // Builds before 24.38 inferred HARDWARE from ten quiet minutes; that inference is void.
        if (p.getString("knob_evidence", "")?.startsWith("Android's media volume never moved") == true && p.getString("knob", null) == Knob.HARDWARE.name) {
            p.edit().remove("knob").putString("knob_evidence", "Earlier automatic hardware verdict discarded: confirm the knob type below.").apply()
        }
        val stored = p.getString("knob", null)
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
            // Remember what the firmware had before switching it off, so "match other apps" can put it back.
            val before = vendorStates(manager)
            if (before.isNotEmpty() && !prefs(app).contains("vendor_before")) {
                prefs(app).edit().putString("vendor_before", before.entries.joinToString(",") { "${it.key}=${it.value}" }).apply()
            }
            val results = VENDOR_OFF.map { parameter -> runCatching { manager.setParameters(parameter) }.fold({ "$parameter ok" }, { "$parameter failed" }) }
            val readback = VENDOR_READ.map { key -> "$key=${runCatching { manager.getParameters(key) }.getOrDefault("?").ifEmpty { "(no answer)" }}" }
            lastVendorResult = readback.joinToString(" ")
            log("audio path: vendor enhancements off → ${results.joinToString(", ")}; readback ${lastVendorResult}")
        } else {
            // An earlier session switched them off and some firmwares keep that until reboot: restore
            // the recorded states (BesLoudness is what makes other apps loud and clip-free here).
            val before = prefs(app).getString("vendor_before", null)
            if (!before.isNullOrEmpty()) {
                val results = before.split(',').mapNotNull { entry ->
                    val key = entry.substringBefore('='); val value = entry.substringAfter('=', "")
                    val setter = VENDOR_SETTERS[key] ?: return@mapNotNull null
                    if (value != "0" && value != "1") return@mapNotNull null
                    runCatching { manager.setParameters("$setter=$value") }.fold({ "$setter=$value ok" }, { "$setter=$value failed" })
                }
                prefs(app).edit().remove("vendor_before").apply()
                val readback = VENDOR_READ.map { key -> "$key=${runCatching { manager.getParameters(key) }.getOrDefault("?").ifEmpty { "(no answer)" }}" }
                lastVendorResult = readback.joinToString(" ")
                log("audio path: vendor enhancements restored → ${results.joinToString(", ")}; readback ${lastVendorResult}")
            }
        }
        pinVolume(app, manager, log)
        CleanVolume.begin(app, manager, log)
        synchronized(this) {
            sessions++
            if (receiver == null) {
                sessionStartedAt = SystemClock.elapsedRealtime()
                receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        if (intent.action != VOLUME_CHANGED || intent.getIntExtra(EXTRA_STREAM, -1) != AudioManager.STREAM_MUSIC) return
                        val value = intent.getIntExtra(EXTRA_VALUE, -1)
                        onMediaVolumeChanged(ctx.applicationContext, value, log)
                        ctx.applicationContext.getSystemService(AudioManager::class.java)?.let { CleanVolume.onVolumeChanged(it, value, log) }
                    }
                }
                runCatching { app.registerReceiver(receiver, IntentFilter(VOLUME_CHANGED)) }
                    .onFailure { log("audio path: volume watcher unavailable: ${it.message}") }
            }
        }
        // Loudness compensation follows the media volume only when it is the knob (checked on every update).
        runCatching { LoudnessVolumeTracker.start(app) }.onFailure { log("audio path: loudness tracker unavailable: ${it.message}") }
        log("audio path: ${describe(app)}")
    }

    fun onSessionEnd(context: Context) {
        val app = context.applicationContext
        runCatching { LoudnessVolumeTracker.stop(app) }
        synchronized(this) {
            sessions = (sessions - 1).coerceAtLeast(0)
            if (sessions == 0) {
                receiver?.let { runCatching { app.unregisterReceiver(it) } }
                receiver = null
                CleanVolume.end(app.getSystemService(AudioManager::class.java)) { Log.i(TAG, it) }
                // No external volume change is inconclusive (the knob may simply not have been
                // turned), so it never promotes the knob to HARDWARE: that would let the next
                // session pin Android's volume to maximum on a software-volume unit. The watched
                // time is kept as evidence for the settings page; the user confirms hardware there.
                if (knob(app) == Knob.UNKNOWN) {
                    val watched = prefs(app).getLong("watched_ms", 0L) + (SystemClock.elapsedRealtime() - sessionStartedAt)
                    prefs(app).edit().putLong("watched_ms", watched)
                        .putString("knob_evidence", if (watched >= HARDWARE_AFTER_MILLIS)
                            "Android's media volume never moved during ${watched / 60000} min of sessions; if the on-screen volume bar does not move when you turn the knob, set \"Knob is hardware\" below."
                        else "").apply()
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

    /** Sets STREAM_MUSIC and remembers it, so the resulting broadcast is not taken for a knob movement. */
    internal fun setMediaVolume(manager: AudioManager, index: Int): Boolean {
        lastSetByUs = index; lastSetAt = SystemClock.elapsedRealtime()
        return runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }.isSuccess
    }

    private fun pinVolume(app: Context, manager: AudioManager, log: (String) -> Unit) {
        if (!AirPlayPersistence.loadPinMediaVolume(app)) return
        when (knob(app)) {
            Knob.SOFTWARE -> { lastVolumeNote = "Not pinning media volume: the knob is Android's software volume."; return }
            // Never touch the volume before the knob type is known: on a software-volume unit a
            // pin to maximum would start the first session at full blast.
            Knob.UNKNOWN -> { lastVolumeNote = "Not pinning media volume until the knob type is known (turn it during a session, or set it below)."; return }
            Knob.HARDWARE -> Unit
        }
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
                Knob.UNKNOWN -> "Knob: not known yet (turn it while music plays; a moving on-screen volume bar means software). "
            })
            knobEvidence(app).takeIf { it.isNotEmpty() }?.let { append(it).append(' ') }
            if (lastVolumeNote.isNotEmpty()) append(lastVolumeNote)
            if (CleanVolume.enabled(app)) append("\nClean volume mode: ${if (CleanVolume.active) "active, unit parked at step ${CleanVolume.cleanStep}, DiPlay volume ${CleanVolume.describeLevel()}" else "on, parks the unit at step ${CleanVolume.step(app)} during sessions"}.")
            append("\nVendor enhancements: ").append(lastVendorResult.ifEmpty { if (AirPlayPersistence.loadDisableVendorEnhancements(app)) "switched off at the next session" else "left as the firmware set them" })
            append(".\nInstalled audio effects: ").append(if (effects.isEmpty()) "none reported" else effects.joinToString(", "))
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private const val TAG = "DiPlay-AudioPath"
    @Suppress("unused") private fun debug(line: String) = Log.i(TAG, line)
}
