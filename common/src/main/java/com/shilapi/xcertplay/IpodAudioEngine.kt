// SPDX-License-Identifier: GPL-3.0-only
// Legacy fork: "iPod mode" over USB. DiPlay identifies and authenticates to the iPhone as a USB
// audio accessory over the HID interface of the phone's "iPod USB Interface" configuration (the
// one this head unit forces anyway), asks for USB device-mode audio, captures the stream Android
// exposes for the USB audio class interfaces and plays it through the car with the fork's EQ.
// Track metadata, artwork, power state and a media-remote HID component ride the same iAP2 link.
package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.ipod.HidReportLayout
import com.shilapi.xcertplay.ipod.Iap2HidStream
import com.shilapi.xcertplay.ipod.Iap2IpodClient
import com.shilapi.xcertplay.ipod.Iap2IpodConfig
import com.shilapi.xcertplay.ipod.IpodHidKey
import com.shilapi.xcertplay.ipod.IpodPowerState
import com.shilapi.xcertplay.ipod.UsbAudioAlternate
import com.shilapi.xcertplay.ipod.UsbAudioDescriptors
import com.shilapi.xcertplay.ipod.UsbIsoAudioCapture
import com.shilapi.xcertplay.media.AudioEffectController
import com.shilapi.xcertplay.media.AudioEffectSettings
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.media.EqualizerDsp
import com.shilapi.xcertplay.media.ThreadPriorities
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2ArtworkTransfer
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.transport.UsbClaimDiagnostics
import com.shilapi.xcertplay.transport.UsbConfigurationControl
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

object IpodAudioEngine {
    enum class State { IDLE, SWITCHING, LINKING, IDENTIFYING, AUTHENTICATING, READY, FAILED, STOPPED }

    @Volatile var state: State = State.IDLE; private set
    @Volatile var status: String = "Idle"; private set
    @Volatile var level: Float = 0f; private set
    @Volatile var peak: Float = 0f; private set
    @Volatile var sampleRate: Int = 0; private set
    @Volatile var inputDevice: String = "none"; private set
    @Volatile var framesPlayed: Long = 0; private set
    @Volatile var underruns: Int = 0; private set
    @Volatile var audioActive: Boolean = false; private set
    @Volatile var identified = false; private set
    @Volatile var authenticated = false; private set
    @Volatile var nowPlaying = CarPlayNowPlaying(); private set
    @Volatile var elapsedAtRealtime = 0L; private set
    @Volatile var artwork: Bitmap? = null; private set
    @Volatile var shuffle: Int? = null; private set
    @Volatile var repeat: Int? = null; private set
    @Volatile var power = IpodPowerState(); private set
    @Volatile var volume: Float = 1f
    @Volatile var gainBoost: Boolean = false
    @Volatile var muted: Boolean = false
    val recentLog = CopyOnWriteArrayList<String>()
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var logFile: SessionLogFile? = null
    @Volatile private var client: Iap2IpodClient? = null
    @Volatile private var audio: CaptureLoop? = null
    /** The open USB connection plus the audio-streaming formats, for the direct isochronous driver. */
    private class DirectContext(val connection: UsbDeviceConnection, val interfaces: List<UsbInterface>, val alternates: List<UsbAudioAlternate>)
    @Volatile private var direct: DirectContext? = null
    @Volatile var audioPath: String = ""; private set
    private val traceCounts = HashMap<String, Int>()

    fun isRunning() = running.get()
    val playing: Boolean get() = nowPlaying.playing && state == State.READY

    /** Elapsed position with the time since the phone's last report added while playing. */
    fun elapsedNow(): Long? {
        val elapsed = nowPlaying.elapsedMillis ?: return null
        if (!nowPlaying.playing || elapsedAtRealtime == 0L) return elapsed
        val estimate = elapsed + (SystemClock.elapsedRealtime() - elapsedAtRealtime)
        return nowPlaying.durationMillis?.let { minOf(estimate, it) } ?: estimate
    }

    fun log(context: Context, line: String) {
        Log.i(TAG, line)
        recentLog.add(line); while (recentLog.size > 120) recentLog.removeAt(0)
        (logFile ?: SessionLogFile(File(context.filesDir, "logs/ipod.log")).also { logFile = it; it.reset("iPod audio log") }).append(line)
        notifyListeners()
    }

    private fun notifyListeners() = listeners.forEach { runCatching { it() } }
    private fun set(context: Context, next: State, text: String) { state = next; status = text; log(context, "state=$next $text") }

    fun findIphone(context: Context): UsbDevice? {
        val usb = context.getSystemService(UsbManager::class.java) ?: return null
        return usb.deviceList.values.firstOrNull { it.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID }
    }

    // ---- transport controls -------------------------------------------------------------------

    fun playPause() = client?.press(IpodHidKey.PLAY_PAUSE)
    fun play() = client?.press(IpodHidKey.PLAY)
    fun pause() = client?.press(IpodHidKey.PAUSE)
    fun next() = client?.press(IpodHidKey.NEXT)
    fun previous() = client?.press(IpodHidKey.PREVIOUS)
    fun siri() = client?.press(IpodHidKey.SIRI)
    fun seekTo(millis: Long) = client?.seek(millis)
    fun launchApp(bundleId: String) = client?.launchApp(bundleId)
    fun toggleShuffle() = client?.setShuffle(if ((shuffle ?: 0) == 0) 1 else 0)
    fun cycleRepeat() = client?.setRepeat(((repeat ?: 0) + 1) % 3)
    fun restartAudio() = client?.restartAudio()

    // ---- lifecycle ----------------------------------------------------------------------------

    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        volume = IpodSettings.volume(app); gainBoost = IpodSettings.gainBoost(app)
        identified = false; authenticated = false; audioActive = false; sampleRate = 0; framesPlayed = 0; underruns = 0
        nowPlaying = CarPlayNowPlaying(); artwork = null; shuffle = null; repeat = null; power = IpodPowerState()
        synchronized(traceCounts) { traceCounts.clear() }
        worker = Thread({
            ThreadPriorities.transport()
            try { run(app) } catch (error: Throwable) {
                set(app, State.FAILED, "Failed: ${error.message ?: error.javaClass.simpleName}")
                Log.w(TAG, "ipod session failed", error)
            } finally {
                audio?.stop(); audio = null; client = null
                running.set(false)
                audioActive = false
                if (state != State.FAILED) set(app, State.STOPPED, "Stopped")
                notifyListeners()
            }
        }, "diplay-ipod-control").apply { isDaemon = true; start() }
    }

    fun stop() { running.set(false); worker?.interrupt() }

    private fun run(app: Context) {
        val usb = app.getSystemService(UsbManager::class.java) ?: error("USB service unavailable")
        val device = findIphone(app) ?: error("No iPhone on USB. Plug it in with a data cable.")
        if (!usb.hasPermission(device)) error("USB permission not granted; tap Start again and allow access")
        if (AirPlayPersistence.loadMfiTarget(app) != MfiTarget.LOCAL) error("iPod mode needs the built-in MFi identity (Settings → MFi target: Local)")
        DiPlayBootstrap.ensure(app, MfiTarget.LOCAL)
        val mfi = LocalMfiAuthenticationClient.load(File(app.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)) { }
        log(app, "mfi identity loaded certificateBytes=${mfi.readCertificate().size}")

        set(app, State.SWITCHING, "Switching the iPhone to its USB audio configuration…")
        val configuration = switchToAudioConfiguration(app, usb, device)
        val hid = (0 until configuration.interfaceCount).map(configuration::getInterface)
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            ?: error("The audio configuration has no HID interface for the accessory protocol")
        val endpoints = (0 until hid.endpointCount).map(hid::getEndpoint)
        val inEndpoint = endpoints.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_INT && it.direction == UsbConstants.USB_DIR_IN }
            ?: error("The HID interface has no interrupt IN endpoint")
        val outEndpoint = endpoints.firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_INT && it.direction == UsbConstants.USB_DIR_OUT }

        set(app, State.LINKING, "Opening the accessory protocol link…")
        val connection = usb.openDevice(device) ?: error("Could not open the iPhone")
        var stream: Iap2HidStream? = null
        try {
            if (!connection.claimInterface(hid, true)) {
                error("Could not claim the HID interface (${UsbClaimDiagnostics.describe(device)})")
            }
            val descriptor = Iap2HidStream.readReportDescriptor(connection, hid.id)
                ?: error("Could not read the HID report descriptor")
            val layout = HidReportLayout.parse(descriptor)
            log(app, "hid interface=${hid.id} in=0x${inEndpoint.address.toString(16)}/${inEndpoint.maxPacketSize} out=${outEndpoint?.let { "0x" + it.address.toString(16) } ?: "set-report"} descriptorBytes=${descriptor.size} ${layout.describe()}")
            if (layout.outputs.isEmpty() || layout.inputs.isEmpty()) {
                log(app, "hid descriptor hex=" + descriptor.joinToString("") { "%02x".format(it.toInt() and 0xff) })
                error("The HID descriptor declares no iAP2 reports (${layout.describe()})")
            }
            // Direct capture needs the audio-streaming formats, which Android does not expose: read the
            // configuration descriptor and parse the UAC1 format descriptors ourselves.
            val interfaces = (0 until configuration.interfaceCount).map(configuration::getInterface)
            val alternates = runCatching {
                UsbIsoAudioCapture.readConfigurationDescriptor(connection, device, configuration.id)?.let(UsbAudioDescriptors::parse)
            }.onFailure { log(app, "configuration descriptor read failed: ${it.message}") }.getOrNull().orEmpty()
            alternates.forEach { log(app, "usb audio format ${it.describe()}") }
            if (alternates.isEmpty()) log(app, "no UAC1 audio-streaming formats found; direct USB audio unavailable")
            direct = DirectContext(connection, interfaces, alternates)
            val hidStream = Iap2HidStream(connection, hid.id, inEndpoint, outEndpoint, layout,
                isAttached = { usb.deviceList.values.any { it.deviceName == device.deviceName } }) { log(app, it) }
            stream = hidStream
            val session = Iap2Session.open(hidStream, traceContext = "ipod", onTrace = { trace(app, it) }, onArtwork = { onArtwork(app, it) })
            try {
                val config = ipodConfig(app)
                val ipod = Iap2IpodClient(session, mfi, config, object : Iap2IpodClient.Listener {
                    override fun onProgress(line: String) = log(app, line)
                    override fun onStage(stage: Iap2IpodClient.Stage) {
                        when (stage) {
                            Iap2IpodClient.Stage.LINKING -> set(app, State.LINKING, "Waiting for the iPhone to open the accessory link…")
                            Iap2IpodClient.Stage.IDENTIFYING -> set(app, State.IDENTIFYING, "Identifying as a USB audio accessory…")
                            Iap2IpodClient.Stage.AUTHENTICATING -> { identified = true; set(app, State.AUTHENTICATING, "Authenticating (MFi)…") }
                            Iap2IpodClient.Stage.READY -> {
                                identified = true; authenticated = true
                                set(app, State.READY, "Connected. Waiting for the iPhone to start USB audio…")
                                scheduleAudioFallback(app)
                            }
                        }
                    }
                    override fun onNowPlaying(update: CarPlayNowPlaying, shuffle: Int?, repeat: Int?) {
                        if (update.elapsedMillis != nowPlaying.elapsedMillis || update.playing != nowPlaying.playing) elapsedAtRealtime = SystemClock.elapsedRealtime()
                        nowPlaying = update
                        this@IpodAudioEngine.shuffle = shuffle; this@IpodAudioEngine.repeat = repeat
                        notifyListeners()
                    }
                    override fun onAudioSampleRate(rateHz: Int) = startAudio(app, rateHz, fallback = false)
                    override fun onPower(state: IpodPowerState) { power = state; notifyListeners() }
                })
                client = ipod
                ipod.run(isCancelled = { !running.get() })
            } finally {
                client = null
                audio?.stop(); audio = null
                direct = null
                runCatching { session.close() }
            }
        } finally {
            if (stream == null) runCatching { connection.close() }
            log(app, "session closed framesPlayed=$framesPlayed underruns=$underruns")
        }
    }

    private fun ipodConfig(app: Context): Iap2IpodConfig {
        fun clean(value: String?, fallback: String) = value?.filter { it.code in 0x20..0x7e }?.trim()?.take(60)?.ifEmpty { null } ?: fallback
        val androidId = runCatching { Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull()
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
        return Iap2IpodConfig(
            name = "DiPlay",
            modelIdentifier = clean(Build.MODEL, "HeadUnit"),
            manufacturer = clean(Build.MANUFACTURER, "DiPlay"),
            serialNumber = "DIPLAY-" + clean(androidId, "0000").uppercase(),
            firmwareVersion = clean(version, "0.2.13"),
            hardwareVersion = "1.0",
            availableCurrentMilliAmps = IpodSettings.chargeCurrent(app),
        )
    }

    /** iOS normally answers StartUSBDeviceModeAudio within a second; otherwise capture blindly. */
    private fun scheduleAudioFallback(app: Context) {
        Thread({
            try { Thread.sleep(6_000) } catch (_: InterruptedException) { return@Thread }
            if (running.get() && audio == null && state == State.READY) {
                log(app, "no USBDeviceModeAudioInformation after 6 s: capturing at 44.1 kHz anyway")
                startAudio(app, 44_100, fallback = true)
            }
        }, "diplay-ipod-audio-fallback").apply { isDaemon = true; start() }
    }

    @Synchronized
    private fun startAudio(app: Context, rateHz: Int, fallback: Boolean) {
        val current = audio
        if (current != null && current.rateHz == rateHz) return
        current?.stop()
        audio = CaptureLoop(app, rateHz).also { it.start() }
        set(app, State.READY, if (fallback) "Connected · capturing at ${rateHz / 1000.0} kHz" else "Connected · iPhone streaming USB audio at ${rateHz / 1000.0} kHz")
    }

    private fun trace(app: Context, line: String) {
        // Now Playing and power updates repeat twice a second; keep a few, then only count them.
        val key = when {
            "0x5001" in line || "5001" in line -> "5001"
            "ae01" in line -> "ae01"
            else -> null
        }
        if (key != null) {
            val count = synchronized(traceCounts) { (traceCounts[key] ?: 0) + 1 }.also { synchronized(traceCounts) { traceCounts[key] = it } }
            if (count > 4 && count % 200 != 0) return
        }
        logFile?.append(line) ?: log(app, line)
    }

    private fun onArtwork(app: Context, transfer: Iap2ArtworkTransfer) {
        log(app, "artwork transfer id=${transfer.id} bytes=${transfer.bytes.size}")
        val decoded = runCatching { CarPlayMediaKeys.decodeArtworkBytes(transfer.bytes) }.getOrNull()
        artwork = decoded
        notifyListeners()
    }

    // ---- USB configuration ---------------------------------------------------------------------

    private fun switchToAudioConfiguration(app: Context, usb: UsbManager, device: UsbDevice): UsbConfiguration {
        var target: UsbConfiguration? = null
        for (index in 0 until device.configurationCount) {
            val configuration = device.getConfiguration(index)
            val interfaces = (0 until configuration.interfaceCount).map(configuration::getInterface)
            val hasAudio = interfaces.any { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO }
            val hasHid = interfaces.any { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            log(app, "configuration id=${configuration.id} interfaces=${configuration.interfaceCount} audio=$hasAudio hid=$hasHid")
            if (hasAudio && hasHid && target == null) target = configuration
        }
        val audioConfiguration = target ?: error("The iPhone exposes no USB audio + HID configuration (is it unlocked and awake?)")
        val connection = usb.openDevice(device) ?: error("Could not open the iPhone")
        try {
            val active = UsbConfigurationControl.activeConfiguration(connection)
            log(app, "active configuration=${active ?: "unknown"} target=${audioConfiguration.id} ${UsbClaimDiagnostics.describe(device)}")
            if (active != audioConfiguration.id) {
                val ok = UsbConfigurationControl.ensureConfiguration(device, connection, audioConfiguration,
                    openConnection = { runCatching { usb.openDevice(device) }.getOrNull() }) { log(app, it) }
                if (!ok) error("Could not switch the iPhone to its audio configuration: ${UsbClaimDiagnostics.describe(device)}")
                Thread.sleep(600) // let snd-usb-audio bind the audio interfaces
                log(app, "after switch: ${UsbClaimDiagnostics.describe(device)}")
            }
        } finally {
            runCatching { connection.close() }
        }
        return audioConfiguration
    }

    // ---- audio capture --------------------------------------------------------------------------

    private class CaptureLoop(private val app: Context, val rateHz: Int) {
        private val active = AtomicBoolean(true)
        private val thread = Thread(::run, "diplay-ipod-audio").apply { isDaemon = true }
        fun start() = thread.start()
        fun stop() { active.set(false); thread.interrupt(); runCatching { thread.join(1_500) } }

        private fun run() {
            ThreadPriorities.audio()
            try { capture() } catch (error: Throwable) {
                if (active.get()) log(app, "audio capture failed: ${error.message ?: error.javaClass.simpleName}")
            } finally { audioActive = false; notifyListeners() }
        }

        private fun capture() {
            val context = direct
            if (context != null && IpodSettings.directUsbAudio(app) && context.alternates.isNotEmpty()) {
                val opened = runCatching {
                    UsbIsoAudioCapture.open(context.connection, context.interfaces, context.alternates, rateHz) { log(app, it) }
                }.onFailure { log(app, "direct usb audio unavailable: ${it.message}; falling back to Android capture") }.getOrNull()
                if (opened != null) { captureDirect(opened); return }
            }
            captureAndroid()
        }

        /** usbfs isochronous packets → EQ → AudioTrack, no Android audio input in the path. */
        private fun captureDirect(source: UsbIsoAudioCapture) {
            val rate = source.rateHz
            val channels = source.channels
            sampleRate = rate
            audioPath = "direct"
            inputDevice = "direct USB isochronous (${source.alternate.describe()})"
            val track = openTrack(rate, channels, bufferMillis = 120)
            val fx = AudioEffectSettings.load(app)
            val dsp = if (fx.enabled && fx.dspMode) EqualizerDsp(rate, channels).also { it.configure(fx.dspGainsDb(), fx.limiter || AirPlayPersistence.loadSoftClipMedia(app)); it.register() }
                else if (AirPlayPersistence.loadSoftClipMedia(app)) EqualizerDsp(rate, channels).also { it.configure(FloatArray(EqualizerDsp.BANDS), limiter = true) }
                else null
            val effects = if (fx.enabled) AudioEffectController.attach(track.audioSessionId, fx) else null
            log(app, "direct capture rate=$rate channels=$channels session=${track.audioSessionId} dsp=${dsp != null} effects=${effects?.describe() ?: "none"}")
            var timeouts = 0
            var started = false
            var lastStats = System.currentTimeMillis()
            try {
                source.use {
                    while (active.get()) {
                        val pcm = source.read(10, 250)
                        if (pcm.isEmpty()) {
                            timeouts++
                            if (timeouts == 20) log(app, "no USB audio packets for 5 s (${source.stats()}): start playback on the phone")
                            if (!started && timeouts >= 40) { log(app, "direct usb audio delivered nothing in 10 s; falling back to Android capture"); break }
                            continue
                        }
                        timeouts = 0
                        if (!started) { started = true; track.play(); audioActive = true; notifyListeners(); log(app, "direct usb audio flowing: ${source.stats()}") }
                        applyGain(pcm, pcm.size)
                        dsp?.process(pcm, 0, pcm.size)
                        measure(pcm, pcm.size)
                        var written = 0
                        while (written < pcm.size && active.get()) {
                            val n = track.write(pcm, written, pcm.size - written)
                            if (n < 0) error("AudioTrack write failed ($n)")
                            written += n
                        }
                        framesPlayed += pcm.size / (channels * 2)
                        underruns = track.underrunCount
                        val now = System.currentTimeMillis()
                        if (now - lastStats > 30_000) { lastStats = now; log(app, "direct usb audio ${source.stats()} underruns=$underruns") }
                    }
                }
            } finally {
                runCatching { track.pause(); track.flush(); track.release() }
                dsp?.unregister(); runCatching { effects?.close() }
                audioActive = false
            }
            if (active.get() && !started) captureAndroid()
        }

        private fun captureAndroid() {
            audioPath = "android"
            val audioManager = app.getSystemService(AudioManager::class.java) ?: error("Audio service unavailable")
            val input = waitForUsbInput(audioManager)
            inputDevice = input?.let { "${it.productName} type=${it.type} rates=${it.sampleRates.joinToString("/")} ch=${it.channelCounts.joinToString("/")}" } ?: "none (default input)"
            log(app, "USB input device: $inputDevice")
            if (!active.get()) return
            val record = openRecord(input) ?: error("Could not open USB audio capture; this firmware may not expose the iPhone's audio to apps")
            val rate = record.sampleRate
            sampleRate = rate
            val channels = if (record.channelCount >= 2) 2 else 1
            val track = openTrack(rate, channels, bufferMillis = 200)
            val fx = AudioEffectSettings.load(app)
            val dsp = if (fx.enabled && fx.dspMode) EqualizerDsp(rate, channels).also { it.configure(fx.dspGainsDb(), fx.limiter || AirPlayPersistence.loadSoftClipMedia(app)); it.register() }
                else if (AirPlayPersistence.loadSoftClipMedia(app)) EqualizerDsp(rate, channels).also { it.configure(FloatArray(EqualizerDsp.BANDS), limiter = true) }
                else null
            val effects = if (fx.enabled) AudioEffectController.attach(track.audioSessionId, fx) else null
            log(app, "capture rate=$rate channels=$channels source=${record.audioSource} session=${track.audioSessionId} dsp=${dsp != null} effects=${effects?.describe() ?: "none"}")
            val buffer = ByteArray(rate / 100 * channels * 2)
            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("AudioRecord did not start")
                track.play()
                audioActive = true; notifyListeners()
                while (active.get()) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read < 0) error("USB audio read failed ($read)")
                    if (read == 0) { Thread.sleep(2); continue }
                    applyGain(buffer, read)
                    dsp?.process(buffer, 0, read)
                    measure(buffer, read)
                    var written = 0
                    while (written < read && active.get()) {
                        val n = track.write(buffer, written, read - written)
                        if (n < 0) error("AudioTrack write failed ($n)")
                        written += n
                    }
                    framesPlayed += read / (channels * 2)
                    underruns = track.underrunCount
                }
            } finally {
                runCatching { record.stop() }; runCatching { record.release() }
                runCatching { track.pause(); track.flush(); track.release() }
                dsp?.unregister(); runCatching { effects?.close() }
                audioActive = false
            }
        }

        private fun waitForUsbInput(audioManager: AudioManager): AudioDeviceInfo? {
            val deadline = System.currentTimeMillis() + 10_000
            var lastListing = ""
            while (System.currentTimeMillis() < deadline && active.get()) {
                val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                val listing = inputs.joinToString(" | ") { "${it.productName} type=${it.type}" }
                if (listing != lastListing) { lastListing = listing; log(app, "input devices: ${listing.ifEmpty { "none" }}") }
                inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY || it.type == 22 }?.let { return it }
                Thread.sleep(300)
            }
            return null
        }

        private fun openRecord(preferred: AudioDeviceInfo?): AudioRecord? {
            val rates = (listOf(rateHz) + preferred?.sampleRates?.toList().orEmpty().filter { it > 0 } + listOf(44_100, 48_000)).distinct()
            val sources = listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.DEFAULT, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION)
            for (rate in rates) for (source in sources) {
                val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                if (minimum <= 0) { log(app, "capture rate=$rate unsupported ($minimum)"); break }
                val record = runCatching { AudioRecord(source, rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 4, rate * 4 / 5)) }.getOrNull() ?: continue
                if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); log(app, "capture rate=$rate source=$source not initialized"); continue }
                if (preferred != null) {
                    val set = runCatching { record.setPreferredDevice(preferred) }.getOrDefault(false)
                    log(app, "capture rate=$rate source=$source preferredDevice=${preferred.productName} set=$set")
                }
                return record
            }
            return null
        }

        private fun openTrack(rate: Int, channels: Int, bufferMillis: Int): AudioTrack {
            val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minimum = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
            val bytes = maxOf(minimum * 2, rate * channels * 2 * bufferMillis / 1000)
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

    private const val TAG = "DiPlay-iPod"
}

/** Persisted iPod-mode preferences. */
object IpodSettings {
    private const val PREFS = "diplay_ipod"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun volume(context: Context): Float = prefs(context).getFloat("volume", 1f)
    fun saveVolume(context: Context, value: Float) = prefs(context).edit().putFloat("volume", value).apply()
    fun gainBoost(context: Context): Boolean = prefs(context).getBoolean("gain_boost", false)
    fun saveGainBoost(context: Context, value: Boolean) = prefs(context).edit().putBoolean("gain_boost", value).apply()
    /** On USB plug-in, start iPod audio instead of CarPlay. */
    fun autoStartOnPlugIn(context: Context): Boolean = prefs(context).getBoolean("auto_start", false)
    fun saveAutoStartOnPlugIn(context: Context, value: Boolean) = prefs(context).edit().putBoolean("auto_start", value).apply()
    /** Current the head unit's USB port can supply, announced to the iPhone (mA). */
    /** Capture the iPhone's USB audio with the fork's own isochronous driver instead of Android's audio input. */
    fun directUsbAudio(context: Context): Boolean = prefs(context).getBoolean("direct_usb_audio", true)
    fun saveDirectUsbAudio(context: Context, value: Boolean) = prefs(context).edit().putBoolean("direct_usb_audio", value).apply()
    fun chargeCurrent(context: Context): Int = prefs(context).getInt("charge_ma", 1000)
    fun saveChargeCurrent(context: Context, value: Int) = prefs(context).edit().putInt("charge_ma", value).apply()
    val CHARGE_CURRENTS = listOf(500, 1000, 1500, 2100, 2400)
}
