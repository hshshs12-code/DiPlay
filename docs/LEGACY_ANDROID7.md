# DiPlay legacy build for Android 7.0 to 8.1 (API 24 to 27)

This branch (`legacy-api24`) lowers the upstream DiPlay API floor from Android 9
(API 28) to Android 7.0 (API 24) so the current upstream code, including the
0.2.13 wired USB fixes, installs and runs on older aftermarket head units.

It is a personal fork for low-spec head units. Nothing here is verified on a
car until someone reports it; see the upstream project for the supported path.

## What changed

Build:

- `minSdk = 24` in `mobile`, `common` and `shared`; `APP_PLATFORM=android-24`
  for the three small native libraries.
- Core library desugaring supplies `java.time` and `java.util.Base64`, which
  the pairing and location code use and which only exist from API 26.

Runtime guards for APIs newer than 24:

- `UsbRequestCompat`: `UsbRequest.queue(ByteBuffer)` and
  `UsbDeviceConnection.requestWait(long)` are API 26. Android 7 gets the
  two-argument `queue` and a worker-thread timeout around the blocking
  `requestWait()`. Used by the USBMUX and NCM paths, so wired CarPlay works.
- Notification channels and `Notification.Builder(Context, String)` are
  API 26; Android 7 uses the channel-less builder.
- `startForegroundService` is API 26; Android 7 uses `startService` and the
  service still calls `startForeground` itself.
- `WifiP2pManager.Channel.close()` is API 27; skipped on Android 7.
- `WifiP2pGroup.getFrequency()` is API 29. Below that the group channel is
  read from a Wi-Fi scan result for the group's own SSID, then the station
  channel, then 0 (the iPhone scans for the AP).
- `LocationManager.isLocationEnabled` and `android.net.MacAddress` are API 28;
  diagnostic use is skipped and the BSSID is parsed by hand.
- `Long.toUnsignedString` / `Integer.toUnsignedString` are API 26; replaced by
  masking.
- LocalOnlyHotspot (API 26) reports a clear error on Android 7 instead of a
  missing-method crash.
- The BYD standalone HUD helper refuses to run below API 28 before touching
  the API 28 signing APIs.

## Fork features (0.2.13-legacy24.6)

- **In-app updater** (Settings → Updates): reads the latest release of
  `hshshs12-code/DiPlay` from the GitHub API, downloads its first `.apk` asset
  and installs it through PackageInstaller (Android shows the confirmation).
  Publish updates as normal releases (not pre-releases) with a tag such as
  `v0.2.13-legacy24.3`; the updater compares the numbers in the tag with the
  installed version name.
- **Equalizer, bass boost, loudness** (Settings → Equalizer and bass boost):
  Android's `Equalizer`, `BassBoost` and `LoudnessEnhancer` attached to the
  CarPlay media track. Band layout comes from the device. Changes apply live to
  a playing track; enabling the feature applies on the next track or reconnect.
- **Upload diagnostic report** (Settings → Diagnostics): posts the same report
  that Save produces to dpaste.com as an unlisted page that expires after
  7 days and shows a short link (dpaste.com/XXXXXX) to read off the screen.
  Reports over ~900 KB keep the header and the newest log lines.
- **CarPlay configuration enforcement** (`UsbConfigurationControl`): a
  MediaTek ac8227l unit puts the re-enumerated iPhone into its USB-audio
  configuration and binds snd-usb-audio/usbhid to it (iPod mode). Linux refuses
  SET_CONFIGURATION from usbfs while any interface is claimed, so the CarPlay
  configuration was never active and the NCM claim targeted a non-existent
  interface. DiPlay now reads the active configuration (GET_CONFIGURATION),
  detaches the kernel drivers from every interface of it with a forced
  claim/release, selects the CarPlay configuration, verifies it, and fails
  with the reason instead of "claiming anyway". The NCM open re-checks the
  active configuration on its own connection.
- **USB conflicts** (Settings → USB conflicts): lists the other apps on the
  head unit that handle USB devices or look like phone-link apps. Per app:
  stop before every USB connect (kills its background processes, and
  force-stops it through already-approved ADB), Stop now, Disable/Enable
  through the head unit's ADB (asks for debugging approval once), and Clear
  defaults (opens the app's details page). "Make DiPlay the default for the
  iPhone" opens Android's USB access prompt, which carries the "Use by default"
  checkbox; Android only shows it before access is granted for a plug-in.
- **USB interface claim retries and ownership diagnostics**: a refused
  `claimInterface` on the USBMUX or NCM interfaces is retried four times with a
  350 ms gap. Each failure and the final error record the sysfs view of the
  iPhone's interfaces (bound kernel driver per interface), and the diagnostic
  report lists every app on the head unit that handles USB device attach, so a
  factory phone-link app holding the iPhone shows up by name.
- **USB writes below Android 9**: `bulkTransfer` silently clips a transfer to
  16384 bytes before API 28. The USBMUX writer sent 16 KiB payloads plus 36
  header bytes in one transfer, which came back short and failed the session.
  Writes are now chunked to 16 KiB on API < 28 (`LegacyUsbLimits`).

## Wireless on Android 7 and 8

- Android 7 to 9 only expose the original `createGroup` overload, so the
  platform picks the Wi-Fi Direct name, password and channel.
- Android 7 has no LocalOnlyHotspot. The car's own hotspot (Manual hotspot
  mode) and Existing Wi-Fi / Same LAN mode remain available.
- One Android 7 port reported that the Android-created hotspot could not open
  the CarPlay port on his unit; Same LAN mode through a router or a second
  phone's hotspot worked there.

## Build

```sh
export DIPLAY_AUTH_ASSETS_DIR=/path/to/dir/containing/offline-mfi
export ANDROID_KEYSTORE_PATH=... ANDROID_KEYSTORE_PASSWORD=... ANDROID_KEY_ALIAS=... ANDROID_KEY_PASSWORD=...
./gradlew :mobile:assembleRelease
```

The MFi runtime assets (`offline-mfi/identity.pk8`, `offline-mfi/certificate.p7b`)
are not in the source tree, as upstream. Copy them from an installed official
DiPlay APK's `assets/offline-mfi/` directory.

## Recommended settings on a 1 GB head unit

30 fps, Efficient video (HEVC) off, Default icon/text size, Resolution 80% or
60% if the picture stutters. Close every other projection app before connecting.
