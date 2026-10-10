// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.ipod

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Identity the head unit presents to the iPhone as a USB "iPod" accessory. */
data class Iap2IpodConfig(
    val name: String,
    val modelIdentifier: String,
    val manufacturer: String,
    val serialNumber: String,
    val firmwareVersion: String,
    val hardwareVersion: String,
    /** Sample rates the head unit can capture; the phone picks one and reports it in 0xDA01. */
    val sampleRates: List<Int> = listOf(44_100, 48_000),
    /** Current the USB port can supply; announced with PowerSourceUpdate after authentication. */
    val availableCurrentMilliAmps: Int = 1_000,
    val language: String = "en",
) {
    init {
        listOf(name, modelIdentifier, manufacturer, serialNumber, firmwareVersion, hardwareVersion, language)
            .forEach { require(it.isNotBlank() && '\u0000' !in it) { "Identification strings must be non-blank without NUL" } }
        require(sampleRates.isNotEmpty() && sampleRates.all { it in Iap2IpodMessages.SAMPLE_RATE_CODES }) { "Unsupported sample rate" }
        require(availableCurrentMilliAmps in 0..0xffff)
    }
}

/** Power state the iPhone reports through PowerUpdate (0xAE01). */
data class IpodPowerState(
    val chargingState: Int? = null,          // 0 disconnected, 1 charging, 2 charged
    val batteryLevelPercent: Int? = null,
    val externalChargerConnected: Boolean? = null,
) {
    fun describe(): String = buildString {
        batteryLevelPercent?.let { append("battery $it%") }
        when (chargingState) { 1 -> append(" · charging"); 2 -> append(" · charged"); 0 -> append(" · not charging") }
    }.trim().trimStart('·').trim()
}

/** Media-remote HID usages (Consumer page) the accessory can "press" on the iPhone. */
enum class IpodHidKey(val bit: Int, val label: String) {
    PLAY(0, "Play"), PAUSE(1, "Pause"), NEXT(2, "Next"), PREVIOUS(3, "Previous"),
    PLAY_PAUSE(4, "Play/Pause"), VOLUME_UP(5, "Volume up"), VOLUME_DOWN(6, "Volume down"), SIRI(7, "Siri"),
}

/** Message IDs and bodies for the iPod-style accessory session. Raw builders: these messages are not in the schema catalog. */
object Iap2IpodMessages {
    const val START_IDENTIFICATION = 0x1d00
    const val IDENTIFICATION_INFORMATION = 0x1d01
    const val IDENTIFICATION_ACCEPTED = 0x1d02
    const val IDENTIFICATION_REJECTED = 0x1d03
    const val REQUEST_AUTHENTICATION_CERTIFICATE = 0xaa00
    const val AUTHENTICATION_CERTIFICATE = 0xaa01
    const val REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE = 0xaa02
    const val AUTHENTICATION_RESPONSE = 0xaa03
    const val AUTHENTICATION_FAILED = 0xaa04
    const val AUTHENTICATION_SUCCEEDED = 0xaa05
    const val START_NOW_PLAYING_UPDATES = 0x5000
    const val NOW_PLAYING_UPDATE = 0x5001
    const val STOP_NOW_PLAYING_UPDATES = 0x5002
    const val SET_NOW_PLAYING_INFORMATION = 0x5003
    const val START_POWER_UPDATES = 0xae00
    const val POWER_UPDATE = 0xae01
    const val STOP_POWER_UPDATES = 0xae02
    const val POWER_SOURCE_UPDATE = 0xae03
    const val START_USB_DEVICE_MODE_AUDIO = 0xda00
    const val USB_DEVICE_MODE_AUDIO_INFORMATION = 0xda01
    const val STOP_USB_DEVICE_MODE_AUDIO = 0xda02
    const val START_HID = 0x6800
    const val DEVICE_HID_REPORT = 0x6801
    const val ACCESSORY_HID_REPORT = 0x6802
    const val STOP_HID = 0x6803
    const val REQUEST_APP_LAUNCH = 0xea02
    const val DEVICE_INFORMATION_UPDATE = 0x4e0d
    const val DEVICE_LANGUAGE_UPDATE = 0x4e0e

    const val USB_TRANSPORT_COMPONENT_ID = 1
    const val HID_COMPONENT_ID = 2
    const val HID_VENDOR_ID = 0x05ac
    const val HID_PRODUCT_ID = 0x1234

    /** iAP2 sample-rate enum values keyed by Hz. */
    val SAMPLE_RATE_CODES: Map<Int, Int> = mapOf(
        8_000 to 0, 11_025 to 1, 12_000 to 2, 16_000 to 3, 22_050 to 4, 24_000 to 5, 32_000 to 6, 44_100 to 7, 48_000 to 8,
    )

    fun sampleRateFromCode(code: Int): Int? = SAMPLE_RATE_CODES.entries.firstOrNull { it.value == code }?.key

    val MESSAGES_SENT = intArrayOf(
        AUTHENTICATION_CERTIFICATE, AUTHENTICATION_RESPONSE,
        START_NOW_PLAYING_UPDATES, STOP_NOW_PLAYING_UPDATES, SET_NOW_PLAYING_INFORMATION,
        START_POWER_UPDATES, STOP_POWER_UPDATES, POWER_SOURCE_UPDATE,
        START_USB_DEVICE_MODE_AUDIO, STOP_USB_DEVICE_MODE_AUDIO,
        START_HID, ACCESSORY_HID_REPORT, STOP_HID,
        REQUEST_APP_LAUNCH,
    )
    val MESSAGES_RECEIVED = intArrayOf(
        REQUEST_AUTHENTICATION_CERTIFICATE, REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE,
        AUTHENTICATION_FAILED, AUTHENTICATION_SUCCEEDED,
        NOW_PLAYING_UPDATE, POWER_UPDATE, USB_DEVICE_MODE_AUDIO_INFORMATION, DEVICE_HID_REPORT,
        DEVICE_INFORMATION_UPDATE, DEVICE_LANGUAGE_UPDATE,
    )

    /**
     * Consumer-control report descriptor for the iAP2 "Media Playback Remote" HID component: one
     * byte, one bit per [IpodHidKey].
     */
    val MEDIA_REMOTE_DESCRIPTOR: ByteArray = byteArrayOf(
        0x05, 0x0c,             // Usage Page (Consumer)
        0x09, 0x01,             // Usage (Consumer Control)
        0xa1.toByte(), 0x01,    // Collection (Application)
        0x15, 0x00,             //   Logical Minimum 0
        0x25, 0x01,             //   Logical Maximum 1
        0x75, 0x01,             //   Report Size 1
        0x95.toByte(), 0x08,    //   Report Count 8
        0x09, 0xb0.toByte(),    //   Usage Play
        0x09, 0xb1.toByte(),    //   Usage Pause
        0x09, 0xb5.toByte(),    //   Usage Scan Next Track
        0x09, 0xb6.toByte(),    //   Usage Scan Previous Track
        0x09, 0xcd.toByte(),    //   Usage Play/Pause
        0x09, 0xe9.toByte(),    //   Usage Volume Increment
        0x09, 0xea.toByte(),    //   Usage Volume Decrement
        0x09, 0xcf.toByte(),    //   Usage Voice Command
        0x81.toByte(), 0x02,    //   Input (Data, Variable, Absolute)
        0xc0.toByte(),          // End Collection
    )

    /** IdentificationInformation for a USB-device-transport audio accessory with a media remote. */
    fun identificationInformation(config: Iap2IpodConfig): Iap2Frame = Iap2Messages.buildRaw(IDENTIFICATION_INFORMATION) {
        string(0, config.name)
        string(1, config.modelIdentifier)
        string(2, config.manufacturer)
        string(3, config.serialNumber)
        string(4, config.firmwareVersion)
        string(5, config.hardwareVersion)
        u16List(6, MESSAGES_SENT.asIterable())
        u16List(7, MESSAGES_RECEIVED.asIterable())
        u8(8, 2)   // PowerProvidingCapability: advanced (we send PowerSourceUpdate)
        u16(9, 0)  // MaximumCurrentDrawnFromDevice
        string(12, config.language)
        strings(13, listOf(config.language))
        group(15) { // USBDeviceTransportComponent
            u16(0, USB_TRANSPORT_COMPONENT_ID)
            string(1, "USB")
            void(2) // TransportSupportsiAP2Connection
            config.sampleRates.forEach { u8(3, SAMPLE_RATE_CODES.getValue(it)) }
        }
        group(18) { // iAP2HIDComponent
            u16(0, HID_COMPONENT_ID)
            string(1, "Media Remote")
            u8(2, 1) // HIDComponentFunction: media playback remote
        }
    }

    fun powerSourceUpdate(config: Iap2IpodConfig): Iap2Frame = Iap2Messages.buildRaw(POWER_SOURCE_UPDATE) {
        u16(0, config.availableCurrentMilliAmps)
        u8(1, 1) // DeviceBatteryShouldChargeIfPowerIsPresent
    }

    fun startPowerUpdates(): Iap2Frame = Iap2Messages.buildRaw(START_POWER_UPDATES) { void(4); void(5); void(6) }

    fun startNowPlayingUpdates(): Iap2Frame = Iap2Messages.buildRaw(START_NOW_PLAYING_UPDATES) {
        group(0) { listOf(1, 4, 6, 12, 16, 26).forEach(::void) }  // title, duration, album, artist, genre, artwork
        group(1) { listOf(0, 1, 5, 6, 7).forEach(::void) }        // status, elapsed, shuffle, repeat, app name
    }

    fun startUsbDeviceModeAudio(): Iap2Frame = Iap2Messages.buildRaw(START_USB_DEVICE_MODE_AUDIO) {}
    fun stopUsbDeviceModeAudio(): Iap2Frame = Iap2Messages.buildRaw(STOP_USB_DEVICE_MODE_AUDIO) {}

    fun startHid(): Iap2Frame = Iap2Messages.buildRaw(START_HID) {
        u16(0, HID_COMPONENT_ID)
        u16(1, HID_VENDOR_ID)
        u16(2, HID_PRODUCT_ID)
        bytes(4, MEDIA_REMOTE_DESCRIPTOR)
    }

    fun hidReport(bits: Int): Iap2Frame = Iap2Messages.buildRaw(ACCESSORY_HID_REPORT) {
        u16(0, HID_COMPONENT_ID)
        bytes(1, byteArrayOf(bits.toByte()))
    }

    fun stopHid(): Iap2Frame = Iap2Messages.buildRaw(STOP_HID) { u16(0, HID_COMPONENT_ID) }

    fun requestAppLaunch(bundleId: String): Iap2Frame = Iap2Messages.buildRaw(REQUEST_APP_LAUNCH) {
        string(0, bundleId)
        u8(1, 1) // LaunchAlert: launch without alert
    }

    fun setNowPlaying(elapsedMillis: Long? = null, shuffle: Int? = null, repeat: Int? = null): Iap2Frame =
        Iap2Messages.buildRaw(SET_NOW_PLAYING_INFORMATION) {
            optionalU32(0, elapsedMillis)
            optionalU8(3, shuffle)
            optionalU8(4, repeat)
        }

    /** The sample rate announced by USBDeviceModeAudioInformation, or null when the body is unreadable. */
    fun sampleRate(frame: Iap2Frame): Int? =
        runCatching { Iap2BodyReader.of(frame).optionalU8(0)?.let(::sampleRateFromCode) }.getOrNull()

    fun powerState(frame: Iap2Frame, previous: IpodPowerState): IpodPowerState {
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return previous
        val level = runCatching { body.optionalU16(6) }.getOrNull()
        return previous.copy(
            chargingState = runCatching { body.optionalU8(5) }.getOrNull() ?: previous.chargingState,
            batteryLevelPercent = level?.let { if (it <= 100) it else (it * 100L / 65_535L).toInt() } ?: previous.batteryLevelPercent,
            externalChargerConnected = runCatching { body.optionalBool(4) }.getOrNull() ?: previous.externalChargerConnected,
        )
    }

    fun shuffleRepeat(frame: Iap2Frame): Pair<Int?, Int?> {
        val playback = runCatching { Iap2BodyReader.of(frame).optionalGroup(1) }.getOrNull() ?: return null to null
        return runCatching { playback.optionalU8(5) }.getOrNull() to runCatching { playback.optionalU8(6) }.getOrNull()
    }
}
