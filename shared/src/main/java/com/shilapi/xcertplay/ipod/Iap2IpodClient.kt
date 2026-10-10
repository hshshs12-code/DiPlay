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
import java.util.concurrent.LinkedBlockingQueue

/**
 * The accessory side of an "iPod" USB session: identification, MFi authentication, then USB
 * device-mode audio, Now Playing updates, power updates and a media-remote HID component. The
 * caller owns [session]; [run] is its only receiver while active.
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
        fun onPower(state: IpodPowerState)
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

    /** Blocks until [isCancelled] or the link ends; throws on protocol failure. */
    fun run(isCancelled: () -> Boolean, linkTimeoutMillis: Long = LINK_TIMEOUT_MILLIS) {
        listener.onStage(Stage.LINKING)
        if (!session.awaitReady(linkTimeoutMillis)) {
            throw IphoneUsbException.TimedOut("The iPhone did not open the iAP2 link over USB HID")
        }
        listener.onProgress("iap2 link ready over HID")
        listener.onStage(Stage.IDENTIFYING)
        val startedAt = System.nanoTime()
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
    }

    // ---- commands from the UI (thread-safe, sent by the control loop) ----

    fun press(key: IpodHidKey) {
        if (!ready) return
        outgoing.add(Command(Iap2IpodMessages.hidReport(1 shl key.bit), pauseAfterMillis = 60))
        outgoing.add(Command(Iap2IpodMessages.hidReport(0)))
    }

    fun seek(elapsedMillis: Long) { if (ready) outgoing.add(Command(Iap2IpodMessages.setNowPlaying(elapsedMillis = elapsedMillis.coerceAtLeast(0)))) }
    fun setShuffle(mode: Int) { if (ready) outgoing.add(Command(Iap2IpodMessages.setNowPlaying(shuffle = mode))) }
    fun setRepeat(mode: Int) { if (ready) outgoing.add(Command(Iap2IpodMessages.setNowPlaying(repeat = mode))) }
    fun launchApp(bundleId: String) { if (ready) outgoing.add(Command(Iap2IpodMessages.requestAppLaunch(bundleId))) }
    fun restartAudio() { if (ready) outgoing.add(Command(Iap2IpodMessages.startUsbDeviceModeAudio())) }

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
                listener.onProgress("iap2 rx 1d00 start-identification → sending accessory identity (usb device audio + media remote)")
                send(Iap2IpodMessages.identificationInformation(config))
            }
            Iap2IpodMessages.IDENTIFICATION_ACCEPTED -> {
                identified = true
                listener.onProgress("iap2 rx 1d02 identification accepted")
                if (!authenticated) listener.onStage(Stage.AUTHENTICATING)
                maybeReady()
            }
            Iap2IpodMessages.IDENTIFICATION_REJECTED -> {
                val ids = runCatching { Iap2BodyReader.of(frame).list().map { "0x${it.id.toString(16)}" } }.getOrDefault(emptyList())
                throw IphoneUsbException.Protocol("The iPhone rejected the accessory identification (parameters $ids)")
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
            Iap2IpodMessages.DEVICE_HID_REPORT -> Unit
            else -> listener.onProgress("iap2 rx 0x${frame.messageId.toString(16).padStart(4, '0')} bytes=${frame.payload.size}")
        }
    }

    private fun maybeReady() {
        if (ready || !identified || !authenticated) return
        ready = true
        send(Iap2IpodMessages.powerSourceUpdate(config))
        send(Iap2IpodMessages.startPowerUpdates())
        send(Iap2IpodMessages.startNowPlayingUpdates())
        send(Iap2IpodMessages.startHid())
        send(Iap2IpodMessages.startUsbDeviceModeAudio())
        listener.onProgress("iap2 tx ae03 power ${config.availableCurrentMilliAmps}mA, ae00, 5000, 6800 media remote, da00 start usb audio")
        listener.onStage(Stage.READY)
    }

    companion object {
        private const val LINK_TIMEOUT_MILLIS = 15_000L
        private const val SETUP_TIMEOUT_MILLIS = 45_000L
        private const val RECV_POLL_MILLIS = 100L
        private const val SEND_TIMEOUT_MILLIS = 5_000L
    }
}
