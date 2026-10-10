// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork: "iPod mode" over USB. The iPhone's USB-audio configuration (the one this head
// unit keeps forcing) makes the phone a USB audio source; Android's USB audio support exposes it
// as an input device, DiPlay captures it, applies its own EQ/bass/loudness/clipper and plays it.
package com.shilapi.xcertplay

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.media.AudioEffectController
import com.shilapi.xcertplay.media.AudioEffectSettings
import com.shilapi.xcertplay.media.EqualizerDsp
import com.shilapi.xcertplay.media.ThreadPriorities
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.transport.UsbClaimDiagnostics
import com.shilapi.xcertplay.transport.UsbConfigurationControl
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

object IpodAudioEngine {
    enum class State { IDLE, SWITCHING, WAITING_FOR_AUDIO_DEVICE, STARTING, RUNNING, FAILED, STOPPED }

    @Volatile var state: State = State.IDLE; private set
    @Volatile var status: String = "Idle"; private set
    @Volatile var level: Float = 0f; private set              // 0..1 RMS of the last block
    @Volatile var peak: Float = 0f; private set
    @Volatile var sampleRate: Int = 0; private set
    @Volatile var inputDevice: String = "none"; private set
    @Volatile var framesPlayed: Long = 0; private set
    @Volatile var underruns: Int = 0; private set
    @Volatile var volume: Float = 1f
    @Volatile var gainBoost: Boolean = false                   // +6 dB before the clipper
    @Volatile var muted: Boolean = false
    val recentLog = CopyOnWriteArrayList<String>()
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var logFile: SessionLogFile? = null

    fun isRunning() = running.get()

    fun log(context: Context, line: String) {
        Log.i(TAG, line)
        recentLog.add(line); while (recentLog.size > 80) recentLog.removeAt(0)
        (logFile ?: SessionLogFile(File(context.filesDir, "logs/ipod.log")).also { logFile = it; it.reset("iPod audio log") }).append(line)
        listeners.forEach { runCatching { it() } }
    }

    private fun set(context: Context, next: State, text: String) { state = next; status = text; log(context, "state=$next $text") }

    fun findIphone(context: Context): UsbDevice? {
        val usb = context.getSystemService(UsbManager::class.java) ?: return null
        return usb.deviceList.values.firstOrNull { it.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID }
    }

    /** Runs the whole bring-up on a worker thread; progress through [status] and [listeners]. */
    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        volume = IpodSettings.volume(app); gainBoost = IpodSettings.gainBoost(app)
        worker = Thread({
            ThreadPriorities.audio()
            try { run(app) } catch (error: Throwable) {
                set(app, State.FAILED, "Failed: ${error.message ?: error.javaClass.simpleName}")
                Log.w(TAG, "ipod audio failed", error)
            } finally {
                running.set(false)
                if (state == State.RUNNING) set(app, State.STOPPED, "Stopped")
            }
        }, "diplay-ipod-audio").apply { isDaemon = true; start() }
    }

    fun stop() { running.set(false); worker?.interrupt() }

    private fun run(app: Context) {
        val usb = app.getSystemService(UsbManager::class.java) ?: error("USB service unavailable")
        val device = findIphone(app) ?: error("No iPhone on USB. Plug it in with a data cable.")
        if (!usb.hasPermission(device)) error("USB permission not granted; tap Start again and allow access")

        set(app, State.SWITCHING, "Switching the iPhone to USB audio mode…")
        switchToAudioConfiguration(app, usb, device)

        set(app, State.WAITING_FOR_AUDIO_DEVICE, "Waiting for Android to pick up the USB audio input…")
        val audioManager = app.getSystemService(AudioManager::class.java) ?: error("Audio service unavailable")
        val input = waitForUsbInput(app, audioManager)
        inputDevice = input?.let { "${it.productName} type=${it.type} rates=${it.sampleRates.joinToString("/")} ch=${it.channelCounts.joinToString("/")}" } ?: "none (trying default input)"
        log(app, "USB input device: $inputDevice")

        set(app, State.STARTING, "Opening audio capture…")
        val record = openRecord(app, input) ?: error("Could not open USB audio capture. This firmware may not expose the iPhone's audio to apps; see the log.")
        val rate = record.sampleRate
        sampleRate = rate
        val channels = if (record.channelCount >= 2) 2 else 1
        val track = openTrack(app, rate, channels)
        val fx = AudioEffectSettings.load(app)
        val dsp = if (fx.enabled && fx.dspMode) EqualizerDsp(rate, channels).also { it.configure(fx.dspGainsDb(), fx.limiter || AirPlayPersistence.loadSoftClipMedia(app)); it.register() }
            else if (AirPlayPersistence.loadSoftClipMedia(app)) EqualizerDsp(rate, channels).also { it.configure(FloatArray(EqualizerDsp.BANDS), limiter = true) }
            else null
        val effects = if (fx.enabled) AudioEffectController.attach(track.audioSessionId, fx) else null
        log(app, "capture rate=$rate channels=$channels source=${record.audioSource} track=${track.sampleRate}Hz session=${track.audioSessionId} dsp=${dsp != null} effects=${effects?.describe() ?: "none"}")

        val blockFrames = rate / 100 // 10 ms
        val buffer = ByteArray(blockFrames * channels * 2)
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("AudioRecord did not start (state=${record.recordingState})")
            track.play()
            set(app, State.RUNNING, "Playing iPhone audio over USB at ${rate / 1000.0} kHz")
            var silentBlocks = 0
            while (running.get()) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) error("USB audio read failed ($read); the iPhone may have left audio mode")
                if (read == 0) { Thread.sleep(2); continue }
                applyGain(buffer, read)
                dsp?.process(buffer, 0, read)
                measure(buffer, read)
                if (level < 0.001f) { if (++silentBlocks == 500) log(app, "5 s of silence from the iPhone: start playback on the phone (lock screen or any app)") } else silentBlocks = 0
                var written = 0
                while (written < read && running.get()) {
                    val n = track.write(buffer, written, read - written)
                    if (n < 0) error("AudioTrack write failed ($n)")
                    written += n
                }
                framesPlayed += read / (channels * 2)
                if (Build.VERSION.SDK_INT >= 24) underruns = track.underrunCount
            }
        } finally {
            runCatching { record.stop() }; runCatching { record.release() }
            runCatching { track.pause(); track.flush(); track.release() }
            dsp?.unregister(); runCatching { effects?.close() }
            log(app, "stopped framesPlayed=$framesPlayed underruns=$underruns")
        }
    }

    private fun applyGain(data: ByteArray, length: Int) {
        val gain = (if (muted) 0f else volume) * (if (gainBoost) 2f else 1f)
        if (gain > 0.999f && gain < 1.001f) return
        var i = 0
        while (i + 1 < length) {
            val v = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff))
            val s = (v * gain).toInt().coerceIn(-32768, 32767)
            data[i] = s.toByte(); data[i + 1] = (s shr 8).toByte()
            i += 2
        }
    }

    private fun measure(data: ByteArray, length: Int) {
        var sum = 0.0; var max = 0; var i = 0; var n = 0
        while (i + 1 < length) {
            val v = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff))
            sum += v.toDouble() * v; if (Math.abs(v) > max) max = Math.abs(v); i += 2; n++
        }
        level = if (n == 0) 0f else (sqrt(sum / n) / 32768.0).toFloat()
        peak = max / 32768f
    }

    // ---- USB configuration ---------------------------------------------------------------------

    private fun switchToAudioConfiguration(app: Context, usb: UsbManager, device: UsbDevice) {
        var target: android.hardware.usb.UsbConfiguration? = null
        for (index in 0 until device.configurationCount) {
            val configuration = device.getConfiguration(index)
            val hasAudio = (0 until configuration.interfaceCount).any { configuration.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
            val hasHid = (0 until configuration.interfaceCount).any { configuration.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_HID }
            log(app, "configuration id=${configuration.id} interfaces=${configuration.interfaceCount} audio=$hasAudio hid=$hasHid")
            if (hasAudio && target == null) target = configuration
        }
        val audioConfiguration = target ?: error("The iPhone exposes no USB audio configuration (is it unlocked and awake?)")
        val connection = usb.openDevice(device) ?: error("Could not open the iPhone")
        try {
            val active = UsbConfigurationControl.activeConfiguration(connection)
            log(app, "active configuration=${active ?: "unknown"} target=${audioConfiguration.id} ${UsbClaimDiagnostics.describe(device)}")
            if (active != audioConfiguration.id) {
                val ok = UsbConfigurationControl.ensureConfiguration(device, connection, audioConfiguration,
                    openConnection = { runCatching { usb.openDevice(device) }.getOrNull() }) { log(app, it) }
                if (!ok) error("Could not switch the iPhone to its audio configuration: ${UsbClaimDiagnostics.describe(device)}")
            }
            dumpHidDescriptor(app, connection, audioConfiguration)
        } finally {
            // Closing (without releaseInterface) leaves the kernel free to bind snd-usb-audio/usbhid.
            runCatching { connection.close() }
        }
        Thread.sleep(400)
        log(app, "after switch: ${UsbClaimDiagnostics.describe(device)}")
    }

    /** Phase-2 evidence: the Apple accessory-protocol HID interface's report descriptor. */
    private fun dumpHidDescriptor(app: Context, connection: android.hardware.usb.UsbDeviceConnection, configuration: android.hardware.usb.UsbConfiguration) {
        for (index in 0 until configuration.interfaceCount) {
            val iface = configuration.getInterface(index)
            if (iface.interfaceClass != UsbConstants.USB_CLASS_HID) continue
            val endpoints = (0 until iface.endpointCount).joinToString(",") { e -> val ep = iface.getEndpoint(e); "0x${ep.address.toString(16)}/${ep.type}/${ep.maxPacketSize}" }
            val buffer = ByteArray(1024)
            val claimed = runCatching { connection.claimInterface(iface, true) }.getOrDefault(false)
            val length = runCatching {
                connection.controlTransfer(UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD or 0x01 /* recipient interface */,
                    0x06, 0x22 shl 8, iface.id, buffer, buffer.size, 1000)
            }.getOrDefault(-1)
            log(app, "HID interface id=${iface.id} endpoints=[$endpoints] claimed=$claimed reportDescriptorBytes=$length " +
                (if (length > 0) "hex=" + buffer.copyOf(length).joinToString("") { "%02x".format(it.toInt() and 0xff) } else ""))
            if (claimed) runCatching { connection.releaseInterface(iface) }
        }
    }

    // ---- Android audio devices ---------------------------------------------------------------

    private fun waitForUsbInput(app: Context, audioManager: AudioManager): AudioDeviceInfo? {
        val deadline = System.currentTimeMillis() + 8_000
        var lastListing = ""
        while (System.currentTimeMillis() < deadline && running.get()) {
            val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val listing = inputs.joinToString(" | ") { "${it.productName} type=${it.type}" }
            if (listing != lastListing) { lastListing = listing; log(app, "input devices: ${listing.ifEmpty { "none" }}") }
            val usb = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY || it.type == 22 /* TYPE_USB_HEADSET */ }
            if (usb != null) return usb
            Thread.sleep(300)
        }
        return null
    }

    private fun openRecord(app: Context, preferred: AudioDeviceInfo?): AudioRecord? {
        val rates = (preferred?.sampleRates?.toList().orEmpty().filter { it > 0 } + listOf(44100, 48000)).distinct()
        val sources = listOfNotNull(if (Build.VERSION.SDK_INT >= 24) MediaRecorder.AudioSource.UNPROCESSED else null,
            MediaRecorder.AudioSource.DEFAULT, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION)
        for (rate in rates) for (source in sources) {
            val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) { log(app, "capture rate=$rate unsupported ($minimum)"); continue }
            val record = runCatching { AudioRecord(source, rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 4, rate * 4 / 5)) }.getOrNull() ?: continue
            if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); log(app, "capture rate=$rate source=$source not initialized"); continue }
            if (preferred != null && Build.VERSION.SDK_INT >= 23) {
                val set = runCatching { record.setPreferredDevice(preferred) }.getOrDefault(false)
                log(app, "capture rate=$rate source=$source preferredDevice=${preferred.productName} set=$set")
            }
            return record
        }
        return null
    }

    private fun openTrack(app: Context, rate: Int, channels: Int): AudioTrack {
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minimum = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val bytes = maxOf(minimum * 2, rate * channels * 2 / 5) // ~200 ms
        val legacy = AirPlayPersistence.loadMediaAudioChannel(app)
        val attributes = if (legacy in 1..10) AudioAttributes.Builder().setLegacyStreamType(legacy).build()
            else AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val builder = AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bytes)
        if (Build.VERSION.SDK_INT >= 26 && AirPlayPersistence.loadLowLatencyAudioTrack(app)) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        val track = builder.build()
        check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack did not initialize" }
        return track
    }

    private const val TAG = "DiPlay-iPod"
}

/** Persisted iPod-mode preferences. */
object IpodSettings {
    private const val PREFS = "diplay_ipod"
    fun volume(context: Context): Float = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("volume", 1f)
    fun saveVolume(context: Context, value: Float) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("volume", value).apply() }
    fun gainBoost(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("gain_boost", false)
    fun saveGainBoost(context: Context, value: Boolean) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("gain_boost", value).apply() }
    /** On USB plug-in, start iPod audio instead of CarPlay. */
    fun autoStartOnPlugIn(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto_start", false)
    fun saveAutoStartOnPlugIn(context: Context, value: Boolean) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto_start", value).apply() }
}
