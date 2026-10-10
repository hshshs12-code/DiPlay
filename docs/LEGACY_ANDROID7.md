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

## Fork features (0.2.13-legacy24.27)

24.27: iPod identification accepted
- First on-car report (24.26): config switch, HID link (reports 0x1–0xc in,
  0xd–0x15 out) and iAP2 sync all worked; the iPhone rejected
  IdentificationInformation parameters 6/7. Cause: 0x4E0D/0x4E0E (wireless
  CarPlay notifications) were wrongly declared as received messages. Removed.
- Identification fallback ladder: on IdentificationRejected the client resends
  a reduced identity (level 1 drops app launch + SetNowPlayingInformation,
  level 2 drops the HID media remote, level 3 drops power, level 4 minimal)
  instead of failing. `Capabilities` reflect what the accepted level offers;
  controls the phone refused are greyed on the iPod screen.
- The phone's audio format seen on the car: interface 1 alt 1, stereo 16-bit,
  8–48 kHz, iso IN 0x81, 192-byte packets, 1 ms interval — exactly what the
  direct driver targets.

24.26: media-volume pin only after the knob is known to be hardware
- `AudioPath.pinVolume` does nothing while the knob type is UNKNOWN (24.25
  pinned to maximum on the first session, which on a software-volume unit
  starts playback at full volume).

24.25: audio path — DiPlay as the sound card
- `MediaOutputStage` + `SincResampler` (32-tap Kaiser windowed sinc, 256
  phases): the media AudioTrack opens at the hardware's native rate and/or in
  float, DiPlay resamples 44.1→48 kHz itself and the DSP renders straight to
  float (`EqualizerDsp.processToFloat`), so Android's mixer neither resamples
  nor requantises. Applies to CarPlay media and both iPod capture paths.
- `AudioPath` (common): native rate/buffer query, MediaTek BesLoudness /
  BesSurround / MusicPlus switched off through `AudioManager.setParameters`
  with readback, media volume pinned to maximum when the knob is hardware, and
  knob detection (VOLUME_CHANGED_ACTION watcher: an external change means the
  knob is Android's software volume; isVolumeFixed or 10 min of unchanged
  volume across sessions means hardware). Manual override buttons.
- Four independent toggles under the fork tuning master switch
  (native_rate_output, float_output, pin_media_volume, disable_vendor_fx),
  a live readout on the settings page, and an "Audio path" section in the
  diagnostic report.

24.24: DSP core brought to car-audio grade
- Double-precision processing end to end, quantised once at the output with
  TPDF dither (±1 LSB triangular) instead of truncation.
- Peaking filters use Orfanidis' prescribed-Nyquist-gain design: bells at
  12–16 kHz keep their analog shape instead of collapsing against Nyquist.
- Graphic EQ is constant-Q from the band spacing and iteratively corrects
  neighbour interaction, so the composite response hits the slider values.
- Coefficient interpolation over ~30 ms (64-frame chunks) removes zipper
  noise while dragging sliders.
- The limiter is a 2 ms look-ahead peak limiter (linear attack ramp, 80 ms
  release) with the soft knee kept only as a safety stage, so limiting no
  longer bends the waveform. Auto level now follows an RMS envelope
  (−16 dBFS target).
- Cost: the same per-sample work as before plus the limiter's O(1) pass; still
  a few percent of one core with everything on.

24.23: sound processing beyond the graphic EQ
- `EqualizerDsp` is now a chain: subsonic high-pass (two cascaded Butterworth
  sections, 24 dB/oct), the 12-band graphic EQ, up to five parametric bands
  (peak / low shelf / high shelf, RBJ biquads), a psychoacoustic bass enhancer
  (low band → rational saturation for odd + even harmonics → band-pass → mix),
  a slow auto-level (peak envelope, ±12 dB, target −14 dBFS, 128-frame gain
  updates) and the soft limiter. Neutral stages cost nothing.
- `AudioEffectSettings` gains subsonicHz, bassEnhance(+Hz), autoLevel(+amount)
  and `parametric` (persisted as "type:hz:gain10:q10"). The media sink and the
  iPod engine configure the whole chain; live changes still apply through
  `applyToActive`.
- `SoundPresets.YARIS`: ten whole-chain presets for a 2018 Toyota Yaris with
  stock door speakers (shared correction: −3 dB @170 Hz, −2 dB @3.2 kHz,
  +2 dB high shelf @9 kHz), including Rage, 808 Heavy, Trap Clean, Melodic,
  Highway, Windows Down, Night Drive, Podcast, Max Loud and Flat Corrected.
- Equalizer panel: "Car sound presets" picker, Sound processing sliders and the
  parametric band editor (12-band mode).

24.22: direct USB audio driver for iPod mode
- `usb_iso_audio.c` submits isochronous URBs through usbfs on the file
  descriptor Android already grants, so the iPhone's USB device-mode audio is
  read straight off the bus into DiPlay's output path. No Android USB audio
  input, no microphone permission needed for audio, ~50 ms pipeline.
- `UsbAudioDescriptors` parses the UAC1 streaming formats from the
  configuration descriptor (Android does not expose them); `UsbIsoAudioCapture`
  claims the streaming interface (detaching snd-usb-audio), selects the PCM
  alternate, sets the endpoint sample rate (SET_CUR) and converts 24/32-bit
  subframes to 16-bit when that is all the phone offers.
- On by default ("Direct USB audio driver" switch); the Android capture path
  from 24.20 remains as an automatic fallback when the direct driver cannot
  open or delivers nothing for 10 s.

24.21: iPod mode made native (iAP2 over USB HID)
- DiPlay now identifies and authenticates to the iPhone as a real USB audio
  accessory. The iAP2 link runs over the HID interface of the phone's audio
  configuration (`Iap2HidStream` fragments link packets into HID reports per
  the report descriptor; `Iap2IpodClient` drives the session). Identification
  declares a USBDeviceTransportComponent (44.1/48 kHz) and an iAP2 HID
  "media remote" component; MFi authentication reuses the fork's offline
  identity (same one CarPlay uses).
- After authentication the accessory sends PowerSourceUpdate (charging
  current is a setting: 500–2400 mA), StartPowerUpdates, StartNowPlayingUpdates,
  StartHID and StartUSBDeviceModeAudio; the phone's
  USBDeviceModeAudioInformation sets the capture sample rate.
- Now Playing screen: artwork (file-transfer session), title/artist/album,
  source app, seekable progress bar (SetNowPlayingInformation), previous /
  play-pause / next, shuffle and repeat, Siri, launch Music / Podcasts /
  Spotify / YouTube Music on the phone (RequestAppLaunch), battery and
  charging state from PowerUpdate.
- Android media session + media audio focus: steering-wheel keys, the unit's
  media widgets and Bluetooth remotes control the iPhone; the car's other
  sources pause. Media-style notification with previous/play/next/stop.

24.20: iPod audio over USB (audio without CarPlay)
- New "iPod audio (USB)" button on both home screens and a dedicated screen
  (`IpodModeActivity`). It switches the iPhone to its USB-audio configuration
  (the one this head unit forces anyway), lets the kernel bind snd-usb-audio,
  waits for Android to list the USB input device, captures it with AudioRecord
  and plays it through the car with the fork's EQ/bass/loudness, soft clipper,
  legacy STREAM_MUSIC routing and low-latency track (`IpodAudioEngine`).
- Foreground service with a Stop action keeps it playing while other head-unit
  apps are in front; partial wake lock while running.
- Controls: volume, +6 dB gain boost, mute, live level/peak meter, seconds
  played and underrun counter, last log lines on screen, Equalizer shortcut,
  "Switch to CarPlay" (stops iPod audio and starts the wired connect).
- "Start iPod audio on plug-in instead of CarPlay": when Android hands DiPlay
  the iPhone, the iPod screen opens and starts instead of the CarPlay host.
- Diagnostics: `logs/ipod.log` is part of the uploaded report, including the
  HID interface's report descriptor dump (groundwork for track controls and
  metadata over Apple's accessory protocol).



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
- **Master tuning switch (24.19)**: "Fork performance tuning (master)" in
  Display and performance, default on. On, every fork option below it is
  active by default: uncompressed PCM music, legacy STREAM_MUSIC routing,
  low-latency audio track, soft clipper, low-latency video (MediaTek key
  ladder + newest-frame catch-up), SurfaceView output, video feed wait,
  video socket tuning, sustained performance mode and a session wake lock.
  Off, every loader returns the upstream behaviour regardless of the
  sub-options. Also: `largeHeap`, byte-level "wired link" trace lines
  throttled after 400 per session (1 in 50 afterwards). From Open Headunit:
  socket options (no-delay, keep-alive, IPTOS_LOWDELAY, 256 KiB receive
  bound), feed wait instead of shedding reference frames (1 s, 25 ms slices),
  and the MediaTek power-saving wake lock.
- **Video decoder sizing and ladder from Open Headunit (24.18)**:
  KEY_MAX_INPUT_SIZE is derived from the picture (macroblock-aligned 4:2:0
  samples / 2, floor 128 KiB, cap 1 MiB below API 28) instead of a fixed
  8 MiB, which on a 1 GB MediaTek unit meant about 64 MiB of graphics memory
  for input buffers alone. Low-latency keys ride a configure ladder (tier 2:
  vdec-lowlatency + vdec-no-record=1 + use-clearmotion-mode=0; tier 1:
  vdec-lowlatency; tier 0: none), the operating-rate hint is the negotiated
  frame rate, and newest-frame catch-up discards only from the third ready
  frame on (one frame ahead of the display is ordinary pipeline depth).
- **Audio path options from Open Headunit (24.17)**: "Legacy music stream
  routing" (media channel 3 = STREAM_MUSIC instead of usage attributes),
  "Low-latency audio track" (AudioTrack.PERFORMANCE_MODE_LOW_LATENCY on API
  26+ for media) and "Soft clipper on music" (the 12-band stage's limiter run
  with flat bands). All off by default. Preset "Phone (Android Auto)" copies
  the user's phone equalizer curve.
- **Uncompressed music (24.16)**: "Uncompressed music (PCM)" offers only
  PCM for the main music stream (type 102) so the iPhone sends raw 48 kHz
  PCM and the head unit decodes nothing (Open Headunit does this by default
  for Android Auto, which is the likely reason it sounds cleaner on the
  same unit). Off by default; the iPhone picks AAC whenever AAC is offered.
- **Boost headroom (24.14, removed in 24.15 at the user's request; the gain helper remains unused)**: Android's Equalizer and BassBoost run after
  the track with no headroom, so a boosted band on a loud master hard-clips
  (heard as fuzz). The media PCM is now attenuated by the largest boost (device
  band boost, bass boost ≈ 6 dB at full strength, or 12-band boost with the
  limiter off) before the effects. Calls and navigation are untouched.
- **Cold-start performance (24.13)**: (1) native ChaCha20-Poly1305 through
  bundled Conscrypt on Android < 9, where the platform has none and every
  video frame and audio packet otherwise went through BouncyCastle's Java
  implementation, interpreted until the JIT caught up (the "laggy first
  minute"); (2) thread priorities: audio renderer and audio receiver at
  URGENT_AUDIO, video decoder and screen receiver at DISPLAY, USB/NCM
  readers above foreground; (3) the session log keeps one buffered writer
  flushed per line instead of stat + open + append + close per line on the
  producing thread; (4) `StartupWarmup` exercises the seal/open path (3 MiB)
  and, when enabled, the 12-band EQ at app launch so the JIT compiles them
  before the iPhone connects; (5) a wildcard baseline profile with
  ProfileInstaller so background dexopt compiles the app ahead of time, and
  a one-tap "Precompile app code (ADB)" button running `cmd package compile
  -m speed -f`. Revert point: tag `stable-24.12`.
- **12-band software equalizer** (`EqualizerDsp`): optional mode next to the
  device's own effect. Twelve peaking biquads (31 Hz to 16 kHz, ±12 dB, Q 1.1)
  on the decoded PCM before the AudioTrack write, bands at 0 dB skipped, with
  an optional soft limiter (knee 0.8, ceiling 0.985; off by default). About
  1.2 M filter steps per second at 48 kHz stereo. Presets (24, iOS-style) are
  defined on the 12 bands and interpolated onto the device bands in device
  mode. Horizontal drag-to-set band view (`EqualizerBandsView`) shared by the
  settings page and the overlay (`EqualizerPanel`). Unit-tested for flat
  passthrough, measured +6 dB boost, far-band isolation and limiter headroom.
- **Simple home screen** (default on, Settings → Home screen and launch): big
  tiles for Connect USB, Connect wireless, Choose iPhone, Equalizer, Settings
  and Disconnect, plus an Equalizer page.
- **Auto-launch on USB plug-in** (default on): when Android hands the iPhone
  to DiPlay (default handler), the session activity starts wired at once, app
  closed or not; off makes that launch exit quietly.
- **Parked switch persisted**: "Car is parked: allow video" keeps its state at
  the user's request.
- **Equalizer inside CarPlay**: the in-session settings overlay (three-finger
  swipe down) carries the same equalizer, bass boost and loudness sliders,
  applied live.
- **Video latency** (Display and performance): "Low-latency video" (default
  on) sets the MediaTek `vdec-lowlatency` hint, operating rate and priority on
  the decoder and shows only the newest decoded frame when several are
  waiting; "SurfaceView output" (default off) replaces the TextureView with a
  SurfaceView to skip a compositing step. Both apply on reconnect.
- **Phone video with a manual parked switch**: "Phone video playback" offers
  iOS video in car to the iPhone on units without vehicle data; playback is
  only allowed while "Car is parked: allow video" is on in the in-CarPlay
  settings. That switch is persisted (24.11).
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
  detaches the kernel drivers from every interface of it with a forced claim on
  a throwaway connection that is then closed without releaseInterface (Android's
  releaseInterface re-attaches the kernel driver at once), selects the CarPlay
  configuration on a clean connection, verifies it, and fails
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
- **Wired network diagnostics and local NDP** (`Ipv6NcmBridge`, `NdpResponder`):
  the IPv6-over-NCM bridge now reports its first frames, 5-second link
  counters, deferred unicast and the AirPlay accept into the diagnostic
  report, and answers Neighbor Solicitations for the host link-local address
  on the NCM link itself instead of relying on the head-unit kernel.
- **USB reads below Android 9**: Android 8.x's `UsbRequest.queue(ByteBuffer)`
  throws IllegalArgumentException for buffers over 16384 bytes (the upstream
  policy expected `false`), so the 64 KiB USBMUX and 32 KiB NCM reads failed
  on the first request ("USBMUX read failed"). `UsbRequestCompat` now uses the
  two-argument `queue(buffer, length)` with a 16 KiB cap below API 28.
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
