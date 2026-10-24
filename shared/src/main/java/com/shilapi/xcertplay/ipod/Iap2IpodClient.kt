// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.ipod

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2AuthenticationMessages
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.media.CarPlayPlaybackStatus
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import com.shilapi.xcertplay.transport.IphoneUsbException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * The accessory side of an "iPod" USB session: identification, MFi authentication, then USB
 * device-mode audio, Now Playing updates, power updates, a media-remote HID component and the
 * External Accessory protocol the iPhone companion app talks over. The caller owns [session];
 * [run] is its only control-frame receiver while active.
 */
class Iap2IpodClient(
    private val session: Iap2Session,
    private val mfi: MfiAuthenticator,
    private val config: Iap2IpodConfig,
    private val listener: Listener,
) {
    enum class Stage { LINKING, IDENTIFYING, AUTHENTICATING, READY }

    interface Listener {
        fun onProgress(line: String)
        fun onStage(stage: Stage)
        fun onNowPlaying(update: CarPlayNowPlaying, shuffle: Int?, repeat: Int?)
        fun onAudioSampleRate(rateHz: Int)
        /** Called right before StartUSBDeviceModeAudio is sent: select the audio alternate setting now. */
        fun onBeforeAudioRequest()
        fun onPower(state: IpodPowerState)
        /** An iPhone app opened ([open]) or closed an External Accessory session for the companion protocol. */
        fun onExternalAccessorySession(identifier: Int, open: Boolean) {}
        /** One packet from the iPhone app on that session (link thread; return quickly). */
        fun onExternalAccessoryData(identifier: Int, bytes: ByteArray) {}
    }

    private class Command(val frame: Iap2Frame, val pauseAfterMillis: Long = 0)

    private val outgoing = LinkedBlockingQueue<Command>()
    private val playback = CarPlayPlaybackStatus()
    @Volatile var identified = false; private set
    @Volatile var authenticated = false; private set
    @Volatile var ready = false; private set
    @Volatile var power = IpodPowerState(); private set
    @Volatile var shuffle: Int? = null; private set
    @Volatile var repeat: Int? = null; private set
    /** Identification fallback level reached; 0 = everything offered. */
    @Volatile var level = 0; private set
    /** Whether the current identification attempt offers the companion app's External Accessory protocol. */
    @Volatile var externalAccessoryOffered = true; private set
    /** Open External Accessory sessions: phone-assigned session identifier → protocol identifier. */
    private val externalAccessorySessions = ConcurrentHashMap<Int, Int>()
    val capabilities: Iap2IpodMessages.Capabilities get() = Iap2IpodMessages.capabilities(level)
    val externalAccessoryOpen: Boolean get() = externalAccessorySessions.isNotEmpty()

    /** Blocks until [isCancelled] or the link ends; throws on protocol failure. */
    fun run(isCancelled: () -> Boolean, linkTimeoutMillis: Long = LINK_TIMEOUT_MILLIS) {
        listener.onStage(Stage.LINKING)
        session.onExternalAccessory = { identifier, bytes ->
            if (externalAccessorySessions.containsKey(identifier)) listener.onExternalAccessoryData(identifier, bytes)
        }
        if (!session.awaitReady(linkTimeoutMillis)) {
            throw IphoneUsbException.TimedOut("The iPhone did not open the iAP2 link over USB HID")
        }
        listener.onProgress("iap2 link ready over HID")
        listener.onStage(Stage.IDENTIFYING)
        val startedAt = System.nanoTime()
        try {
            while (!isCancelled()) {
                drainOutgoing()
                val frame = session.recv(RECV_POLL_MILLIS)
                if (frame == null) {
                    if (session.isClosed) throw IphoneUsbException.DeviceUnavailable("The iAP2 link closed")
                    if (!ready && (System.nanoTime() - startedAt) / 1_000_000 > SETUP_TIMEOUT_MILLIS) {
                        throw IphoneUsbException.TimedOut(
                            "The iPhone did not finish ${if (identified) "authentication" else "identification"} (unlock the phone and tap Trust/Allow if asked)",
                        )
                    }
                    continue
                }
                handle(frame)
            }
        } finally {
            val open = externalAccessorySessions.keys.toList()
            externalAccessorySessions.clear()
            open.forEach { runCatching { listener.onExternalAccessorySession(it, false) } }
        }
    }

    // ---- commands from the UI (thread-safe, sent by the control loop) ----

    fun press(key: IpodHidKey) {
        if (!ready || !capabilities.hid) return
        outgoing.add(Command(Iap2IpodMessages.hidReport(1 shl key.bit), pauseAfterMillis = 60))
        outgoing.add(Command(Iap2IpodMessages.hidReport(0)))
    }

    fun seek(elapsedMillis: Long) { if (ready && capabilities.setNowPlaying) outgoing.add(Command(Iap2IpodMessages.setNowPlaying(elapsedMillis = elapsedMillis.coerceAtLeast(0)))) }
    fun setShuffle(mode: Int) { if (ready && capabilities.setNowPlaying) outgoing.add(Command(Iap2IpodMessages.setNowPlaying(shuffle = mode))) }
    fun setRepeat(mode: Int) { if (ready && capabilities.setNowPlaying) outgoing.add(Command(Iap2IpodMessages.setNowPlaying(repeat = mode))) }
    fun launchApp(bundleId: String) { if (ready && capabilities.appLaunch) outgoing.add(Command(Iap2IpodMessages.requestAppLaunch(bundleId))) }
    fun restartAudio() { if (ready && capabilities.usbAudio) outgoing.add(Command(Iap2IpodMessages.startUsbDeviceModeAudio())) }

    /** Experiment: send StartUSBDeviceModeAudio even though the accepted identity did not declare it. */
    fun forceAudioRequest() {
        if (!ready) return
        listener.onProgress("experiment: sending da00 StartUSBDeviceModeAudio although it was not declared (watch for da01 or a link close)")
        outgoing.add(Command(Iap2IpodMessages.startUsbDeviceModeAudio()))
    }

    /**
     * Sends [bytes] to the iPhone app on External Accessory session [identifier]. Thread-safe;
     * false when that session is not open. Blocks only for link queue capacity.
     */
    fun sendExternalAccessory(identifier: Int, bytes: ByteArray): Boolean {
        if (!externalAccessorySessions.containsKey(identifier)) return false
        session.sendExternalAccessory(identifier, bytes, SEND_TIMEOUT_MILLIS)
        return true
    }

    /** Asks the phone to close the app's session (StatusExternalAccessoryProtocolSession close). */
    fun closeExternalAccessory(identifier: Int) {
        if (externalAccessorySessions.containsKey(identifier)) outgoing.add(Command(Iap2IpodMessages.statusExternalAccessorySession(identifier, close = true)))
    }

    private fun drainOutgoing() {
        while (true) {
            val command = outgoing.poll() ?: return
            session.send(command.frame, SEND_TIMEOUT_MILLIS)
            if (command.pauseAfterMillis > 0) Thread.sleep(command.pauseAfterMillis)
        }
    }

    private fun send(frame: Iap2Frame) = session.send(frame, SEND_TIMEOUT_MILLIS)

    private fun handle(frame: Iap2Frame) {
        when (frame.messageId) {
            Iap2IpodMessages.START_IDENTIFICATION -> {
                listener.onProgress("iap2 rx 1d00 start-identification → sending accessory identity level=$level (${capabilities.describe()}) ${Iap2IpodMessages.describeLists(level, externalAccessoryOffered)}")
                send(Iap2IpodMessages.identificationInformation(config, level, externalAccessoryOffered))
            }
            Iap2IpodMessages.IDENTIFICATION_ACCEPTED -> {
                identified = true
                listener.onProgress("iap2 rx 1d02 identification accepted" + (if (externalAccessoryOffered) " (companion protocol ${config.externalAccessoryProtocol} offered)" else " (companion protocol withheld)"))
                if (!authenticated) listener.onStage(Stage.AUTHENTICATING)
                maybeReady()
            }
            Iap2IpodMessages.IDENTIFICATION_REJECTED -> {
                // The rejection parameters can carry the offending message IDs; log their raw contents.
                val parameters = runCatching { Iap2BodyReader.of(frame).list() }.getOrDefault(emptyList())
                val ids = parameters.map { p -> "0x${p.id.toString(16)}(len=${p.payload.size}${if (p.payload.isNotEmpty()) " hex=" + p.payload.joinToString("") { "%02x".format(it.toInt() and 0xff) } else ""})" }
                // The companion protocol lives in parameter 10 and the message lists (6, 7). When the
                // phone names one of those, try the same level without it before giving anything else up.
                val couldBeExternalAccessory = parameters.isEmpty() || parameters.any { it.id == 10 || it.id == 6 || it.id == 7 }
                if (externalAccessoryOffered && couldBeExternalAccessory && config.externalAccessoryProtocol.isNotBlank()) {
                    externalAccessoryOffered = false
                    listener.onProgress("iap2 rx 1d03 identification rejected parameters $ids → retrying level=$level without the companion External Accessory protocol")
                    send(Iap2IpodMessages.identificationInformation(config, level, false))
                    return
                }
                if (level >= Iap2IpodMessages.MAX_LEVEL) {
                    throw IphoneUsbException.Protocol("The iPhone rejected every accessory identification profile (last rejected parameters $ids)")
                }
                level++
                externalAccessoryOffered = true
                listener.onProgress("iap2 rx 1d03 identification rejected parameters $ids → retrying with level=$level (${capabilities.describe()}) ${Iap2IpodMessages.describeLists(level, true)}")
                send(Iap2IpodMessages.identificationInformation(config, level, true))
            }
            Iap2IpodMessages.REQUEST_AUTHENTICATION_CERTIFICATE -> {
                val certificate = mfi.readCertificate()
                val reply = when (mfi.certificateType) {
                    MfiCertificateType.MFI -> Iap2AuthenticationMessages.accessoryCertificate(certificate)
                    MfiCertificateType.BAA -> Iap2AuthenticationMessages.accessoryCertificateBody(certificate)
                }
                listener.onProgress("iap2 rx aa00 → tx aa01 certificate bytes=${certificate.size}")
                send(reply)
            }
            Iap2IpodMessages.REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE -> {
                val challenge = Iap2AuthenticationMessages.challenge(frame)
                val signature = mfi.signChallenge(challenge)
                listener.onProgress("iap2 rx aa02 challenge bytes=${challenge.size} → tx aa03 signature bytes=${signature.size}")
                send(Iap2AuthenticationMessages.response(signature))
            }
            Iap2IpodMessages.AUTHENTICATION_SUCCEEDED -> {
                authenticated = true
                listener.onProgress("iap2 rx aa05 authentication succeeded")
                maybeReady()
            }
            Iap2IpodMessages.AUTHENTICATION_FAILED -> throw IphoneUsbException.Protocol("The iPhone rejected the MFi authentication")
            Iap2IpodMessages.NOW_PLAYING_UPDATE -> {
                val (nextShuffle, nextRepeat) = Iap2IpodMessages.shuffleRepeat(frame)
                var changed = false
                if (nextShuffle != null && nextShuffle != shuffle) { shuffle = nextShuffle; changed = true }
                if (nextRepeat != null && nextRepeat != repeat) { repeat = nextRepeat; changed = true }
                val update = synchronized(playback) { playback.acceptUpdate(frame) }
                if (update != null || changed) listener.onNowPlaying(update ?: synchronized(playback) { playback.nowPlaying }, shuffle, repeat)
            }
            Iap2IpodMessages.POWER_UPDATE -> {
                power = Iap2IpodMessages.powerState(frame, power)
                listener.onPower(power)
            }
            Iap2IpodMessages.USB_DEVICE_MODE_AUDIO_INFORMATION -> {
                val rate = Iap2IpodMessages.sampleRate(frame)
                listener.onProgress("iap2 rx da01 usb-device-mode-audio sampleRate=${rate ?: "unknown"}")
                if (rate != null) listener.onAudioSampleRate(rate)
            }
            Iap2IpodMessages.START_EXTERNAL_ACCESSORY_SESSION -> {
                val reader = Iap2BodyReader.of(frame)
                val protocolId = runCatching { reader.optionalU8(0) }.getOrNull()
                val identifier = runCatching { reader.u16(1) }.getOrNull()
                if (identifier == null) {
                    listener.onProgress("iap2 rx ea00 start external accessory session without a session identifier; ignored")
                } else {
                    externalAccessorySessions[identifier] = protocolId ?: Iap2IpodMessages.EXTERNAL_ACCESSORY_PROTOCOL_ID
                    listener.onProgress("iap2 rx ea00 iPhone app opened external accessory session=$identifier protocol=$protocolId")
                    listener.onExternalAccessorySession(identifier, true)
                }
            }
            Iap2IpodMessages.STOP_EXTERNAL_ACCESSORY_SESSION -> {
                val identifier = runCatching { Iap2BodyReader.of(frame).u16(0) }.getOrNull()
                listener.onProgress("iap2 rx ea01 iPhone app closed external accessory session=$identifier")
                if (identifier != null && externalAccessorySessions.remove(identifier) != null) listener.onExternalAccessorySession(identifier, false)
            }
            Iap2IpodMessages.DEVICE_HID_REPORT -> Unit
            else -> listener.onProgress("iap2 rx 0x${frame.messageId.toString(16).padStart(4, '0')} bytes=${frame.payload.size}")
        }
    }

    private fun maybeReady() {
        if (ready || !identified || !authenticated) return
        ready = true
        val c = capabilities
        if (c.power) { send(Iap2IpodMessages.powerSourceUpdate(config)); send(Iap2IpodMessages.startPowerUpdates()) }
        send(Iap2IpodMessages.startNowPlayingUpdates())
        if (c.hid) send(Iap2IpodMessages.startHid())
        if (c.usbAudio) {
            // Real hosts have the UAC streaming interface active before they ask for audio.
            runCatching { listener.onBeforeAudioRequest() }
            send(Iap2IpodMessages.startUsbDeviceModeAudio())
        }
        listener.onProgress("iap2 ready level=$level (${c.describe()}): sent ${if (c.power) "ae03 power ${config.availableCurrentMilliAmps}mA, ae00, " else ""}5000 now playing${if (c.hid) ", 6800 media remote" else ""}${if (c.usbAudio) ", da00 start usb audio" else " (no USB-audio request accepted: relying on the phone routing audio to USB by itself)"}")
        listener.onStage(Stage.READY)
    }

    companion object {
        private const val LINK_TIMEOUT_MILLIS = 15_000L
        private const val SETUP_TIMEOUT_MILLIS = 45_000L
        private const val RECV_POLL_MILLIS = 100L
        private const val SEND_TIMEOUT_MILLIS = 5_000L
    }
}
