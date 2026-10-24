// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork: the sound-control protocol the iPhone companion app speaks over the iAP2 External Accessory session.
package com.shilapi.xcertplay

import android.content.Context
import android.media.AudioManager
import com.shilapi.xcertplay.media.AirShelf
import com.shilapi.xcertplay.media.AudioEffectSettings
import com.shilapi.xcertplay.media.AudioEffectSettingsJson
import com.shilapi.xcertplay.media.EqPresets
import com.shilapi.xcertplay.media.EqualizerDsp
import com.shilapi.xcertplay.media.SoundPresets
import com.shilapi.xcertplay.media.UserPresets
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Newline-delimited JSON, one object per line, over the External Accessory session the iPhone
 * companion app opens while the head unit runs in iPod mode.
 *
 * Phone → unit: `hello`, `get`, `ping`, `set {s: partial settings}`, `preset {name}`,
 * `eqPreset {name}`, `savePreset {name}`, `deletePreset {name}`, `unitFxMode {mode}`,
 * `unitFxSet {eqEnabled, bandsMb, bassEnabled, bassStrength, virtEnabled, virtStrength, reverbEnabled, reverbPreset}`,
 * `volume {index | delta}`, `digitalVolume {percent}` (and the 1.0 `neutralise {on}`).
 * Unit → phone: `state` (settings, metadata, user presets, media volume, head-unit effects),
 * `pong`, `error {message}`. Every change made on the head unit itself is pushed as a fresh
 * `state`, so the phone and the car screen always show the same thing.
 */
class SoundControlBridge(context: Context, private val log: (String) -> Unit) {
    private val app = context.applicationContext
    /** Set by the owner once the iAP2 client exists: sends raw bytes on one External Accessory session. */
    @Volatile var sender: ((identifier: Int, bytes: ByteArray) -> Boolean)? = null
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "diplay-sound-bridge").apply { isDaemon = true } }
    private val buffers = ConcurrentHashMap<Int, ByteArrayOutputStream>()
    private var pendingPush: ScheduledFuture<*>? = null
    @Volatile private var unitFx: VendorEffects.Snapshot? = null

    val connected: Boolean get() = buffers.isNotEmpty()

    fun start() { AudioEffectSettings.onSaved = { schedulePush() } }

    fun stop() {
        if (AudioEffectSettings.onSaved != null) AudioEffectSettings.onSaved = null
        buffers.clear()
        executor.shutdownNow()
    }

    fun onSession(identifier: Int, open: Boolean) {
        if (open) {
            buffers[identifier] = ByteArrayOutputStream()
            log("phone app: external accessory session $identifier opened")
            submit { refreshUnitFx(); sendState(identifier) }
        } else {
            buffers.remove(identifier)
            log("phone app: external accessory session $identifier closed")
        }
    }

    fun onData(identifier: Int, bytes: ByteArray) {
        val buffer = buffers[identifier] ?: return
        submit {
            buffer.write(bytes)
            if (buffer.size() > MAX_LINE_BYTES) { buffer.reset(); log("phone app: oversized message dropped"); return@submit }
            val data = buffer.toByteArray()
            val last = data.lastIndexOf(NEWLINE)
            if (last < 0) return@submit
            buffer.reset(); buffer.write(data, last + 1, data.size - last - 1)
            String(data, 0, last, Charsets.UTF_8).split('\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                runCatching { handleLine(identifier, line) }.onFailure { log("phone app: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }
    }

    private fun handleLine(identifier: Int, line: String) {
        val json = runCatching { JSONObject(line) }.getOrElse { log("phone app: not json: ${line.take(80)}"); return }
        when (val type = json.optString("t")) {
            "hello" -> { log("phone app: hello ${json.optString("app", "?")} protocol v${json.optInt("v", 0)}"); refreshUnitFx(); sendState(identifier) }
            "get" -> sendState(identifier)
            "ping" -> send(identifier, JSONObject().put("t", "pong").put("at", json.opt("at")))
            "set" -> {
                val s = json.optJSONObject("s") ?: return error(identifier, "set needs s")
                val current = AudioEffectSettings.current(app)
                var merged = AudioEffectSettingsJson.merge(current, s)
                if (!s.has("presetName")) merged = merged.copy(presetName = "")
                AudioEffectSettings.save(app, merged)
                LoudnessVolumeTracker.refresh(app)
                log("phone app: set ${s.keys().asSequence().joinToString(",")}")
            }
            "preset" -> {
                val name = json.optString("name")
                val current = AudioEffectSettings.current(app)
                val next = UserPresets.find(app, name)?.let { UserPresets.apply(it, current) }
                    ?: SoundPresets.YARIS.firstOrNull { it.name == name }?.apply(current)
                    ?: return error(identifier, "unknown preset $name")
                AudioEffectSettings.save(app, next)
                LoudnessVolumeTracker.refresh(app)
                log("phone app: preset $name")
            }
            "eqPreset" -> {
                val name = json.optString("name")
                val preset = EqPresets.ALL.firstOrNull { it.name == name } ?: return error(identifier, "unknown eq preset $name")
                val current = AudioEffectSettings.current(app)
                AudioEffectSettings.save(app, current.copy(enabled = true, dspMode = true, dspGainsDb = preset.db.toList(), presetName = preset.name))
                log("phone app: eq preset $name")
            }
            "savePreset" -> {
                val name = UserPresets.cleanName(json.optString("name")) ?: return error(identifier, "preset needs a name")
                val current = AudioEffectSettings.current(app)
                UserPresets.save(app, name, current)
                AudioEffectSettings.save(app, current.copy(presetName = name))
                log("phone app: saved preset $name")
            }
            "deletePreset" -> {
                val name = json.optString("name")
                UserPresets.delete(app, name)
                val current = AudioEffectSettings.current(app)
                if (current.presetName == name) AudioEffectSettings.save(app, current.copy(presetName = "")) else schedulePush()
                log("phone app: deleted preset $name")
            }
            "neutralise" -> {   // 1.0 phones: on = hold flat, off = leave alone
                val on = json.optBoolean("on")
                unitFx = runCatching { UnitFxControl.setMode(app, if (on) VendorEffects.Mode.NEUTRAL else VendorEffects.Mode.OFF, log) }.getOrNull()
                pushAll()
            }
            "unitFxMode" -> {
                val mode = VendorEffects.Mode.of(json.optString("mode"))
                unitFx = runCatching { UnitFxControl.setMode(app, mode, log) }.getOrElse { return error(identifier, "unit effects: ${it.message}") }
                log("phone app: unit effects mode ${mode.key}")
                pushAll()
            }
            "unitFxSet" -> {
                val snapshot = runCatching { UnitFxControl.adjust(app, json, log) }.getOrElse { return error(identifier, "unit effects: ${it.message}") }
                    ?: return error(identifier, "Turn on direct control of the head unit's effects first")
                unitFx = snapshot
                pushAll()
            }
            "volume" -> {
                val delta = json.optInt("delta", 0)
                val index = json.optInt("index", -1)
                if (index < 0 && delta == 0) return error(identifier, "volume needs index or delta")
                volumeNote = setUnitVolume(index, delta)
                log("phone app: volume ${if (index >= 0) "index $index" else "delta $delta"} → $volumeNote")
                pushAll()
            }
            "digitalVolume" -> {
                val percent = json.optInt("percent", -1)
                if (percent < 0) return error(identifier, "digitalVolume needs percent")
                val value = percent.coerceIn(0, 100) / 100f
                IpodAudioEngine.volume = value
                IpodSettings.saveVolume(app, value)
                pushAll()
            }
            else -> error(identifier, "unknown message type $type")
        }
    }

    @Volatile private var volumeNote: String = ""

    /**
     * Android's media volume, by absolute index or by steps. A muted unit (ringer silent) counts
     * as Do Not Disturb, and Android then refuses the change unless DiPlay holds Do Not Disturb
     * access; with that access the unit is un-muted first. When Android still refuses, the change
     * goes to DiPlay's own digital volume for the iPod session instead, so the phone always does
     * something audible.
     */
    private fun setUnitVolume(index: Int, delta: Int): String {
        val manager = app.getSystemService(AudioManager::class.java) ?: return "no audio service"
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (if (index >= 0) index else current + delta).coerceIn(0, max)
        val policy = runCatching { app.getSystemService(android.app.NotificationManager::class.java)?.isNotificationPolicyAccessGranted }.getOrNull() == true
        val silent = runCatching { manager.ringerMode != AudioManager.RINGER_MODE_NORMAL }.getOrDefault(false)
        if (silent && policy) runCatching { manager.ringerMode = AudioManager.RINGER_MODE_NORMAL }
        val direct = runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        if (direct.isSuccess) return "Android media volume $target of $max"
        // Step by step sometimes passes where an absolute set does not (no ringer-mode change implied).
        if (delta != 0) {
            val stepped = runCatching { repeat(kotlin.math.abs(delta)) { manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0) } }
            if (stepped.isSuccess) return "Android media volume ${manager.getStreamVolume(AudioManager.STREAM_MUSIC)} of $max"
        }
        val reason = direct.exceptionOrNull()?.message ?: "refused"
        if (IpodAudioEngine.isRunning()) {
            val step = 1f / max.coerceAtLeast(1)
            val digital = (if (index >= 0) index * step else IpodAudioEngine.volume + delta * step).coerceIn(0f, 1f)
            IpodAudioEngine.volume = digital
            IpodSettings.saveVolume(app, digital)
            return "Android refused ($reason${if (silent && !policy) "; the unit is muted and DiPlay lacks Do Not Disturb access" else ""}): set DiPlay's digital volume to ${(digital * 100).toInt()} %"
        }
        return "Android refused: $reason"
    }

    private fun error(identifier: Int, message: String) {
        log("phone app: $message")
        send(identifier, JSONObject().put("t", "error").put("message", message))
    }

    private fun refreshUnitFx() { unitFx = runCatching { VendorEffects.read() }.getOrNull() }

    private fun schedulePush() {
        try {
            synchronized(this) {
                pendingPush?.cancel(false)
                pendingPush = executor.schedule({ pushAll() }, PUSH_DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS)
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun pushAll() { buffers.keys.forEach { sendState(it) } }

    private fun sendState(identifier: Int) {
        val settings = AudioEffectSettings.current(app)
        val manager = app.getSystemService(AudioManager::class.java)
        val state = JSONObject()
            .put("t", "state")
            .put("v", AudioEffectSettingsJson.VERSION)
            .put("unit", "DiPlay " + (runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: ""))
            .put("s", AudioEffectSettingsJson.toJson(settings))
            .put("meta", meta())
            .put("userPresets", JSONArray(UserPresets.load(app).map { it.name }))
            .put("volume", JSONObject()
                .put("index", runCatching { manager?.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: -1)
                .put("max", runCatching { manager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: 0)
                .put("dndAccess", runCatching { app.getSystemService(android.app.NotificationManager::class.java)?.isNotificationPolicyAccessGranted }.getOrNull() == true)
                .put("muted", runCatching { manager?.ringerMode != AudioManager.RINGER_MODE_NORMAL }.getOrDefault(false))
                .put("digital", (IpodAudioEngine.volume * 100).toInt())
                .put("note", volumeNote))
            .put("unitFx", (unitFx ?: VendorEffects.read().also { unitFx = it }).toJson(UnitFxControl.mode(app), VendorEffects.controlling)
                .put("neutralised", VendorEffects.controlling)
                .put("enabled", AirPlayPersistence.loadNeutraliseVendorEffects(app)))
        send(identifier, state)
    }

    private fun meta(): JSONObject = JSONObject()
        .put("bands", JSONArray(EqualizerDsp.CENTER_HZ.toList()))
        .put("minDb", EqualizerDsp.MIN_DB.toInt()).put("maxDb", EqualizerDsp.MAX_DB.toInt())
        .put("maxDrive", AudioEffectSettings.MAX_DRIVE_DB).put("maxTrim", AudioEffectSettings.MAX_TRIM_DB)
        .put("bassClipDb", JSONArray(listOf(0, 1, 2, 3, 4, 6, 9)))
        .put("subsonicHz", JSONArray(listOf(0, 20, 25, 30, 35, 40, 50, 60, 80, 100, 120)))
        .put("enhancerHz", JSONArray(listOf(60, 70, 80, 90, 100, 110, 120, 140, 160, 200)))
        .put("airHz", JSONArray(AirShelf.CORNERS_HZ)).put("maxAirDb", AirShelf.MAX_DB)
        .put("maxWidth", AudioEffectSettings.MAX_STEREO_WIDTH).put("maxBalance", AudioEffectSettings.MAX_BALANCE)
        .put("maxDelayTenths", AudioEffectSettings.MAX_DRIVER_DELAY_TENTHS).put("maxCrossfeed", AudioEffectSettings.MAX_CROSSFEED)
        .put("maxParametric", EqualizerDsp.MAX_PARAMETRIC)
        .put("parametricHz", JSONArray(PARAMETRIC_HZ)).put("parametricQTenths", JSONArray(listOf(3, 5, 7, 10, 14, 20, 30, 40, 60)))
        .put("presets", JSONArray(SoundPresets.YARIS.map { JSONObject().put("name", it.name).put("description", it.description) }))
        .put("eqPresets", JSONArray(EqPresets.ALL.map { it.name }))

    private fun send(identifier: Int, json: JSONObject) {
        val bytes = (json.toString() + "\n").toByteArray(Charsets.UTF_8)
        val ok = runCatching { sender?.invoke(identifier, bytes) ?: false }.onFailure { log("phone app: send failed: ${it.message}") }.getOrDefault(false)
        if (!ok) log("phone app: could not send ${json.optString("t")} to session $identifier")
    }

    private fun submit(block: () -> Unit) {
        try { executor.execute { runCatching(block).onFailure { log("phone app: ${it.javaClass.simpleName}: ${it.message}") } } } catch (_: RejectedExecutionException) {}
    }

    companion object {
        private const val MAX_LINE_BYTES = 256 * 1024
        private const val PUSH_DEBOUNCE_MILLIS = 150L
        private const val NEWLINE: Byte = 0x0a
        val PARAMETRIC_HZ = listOf(30, 40, 50, 60, 70, 80, 90, 100, 120, 140, 160, 180, 200, 250, 300, 350, 400, 500, 600, 700, 800, 1000, 1200, 1500, 1800, 2000, 2500, 3000, 3500, 4000, 5000, 6000, 7000, 8000, 10000, 12000, 14000, 16000)
    }
}
