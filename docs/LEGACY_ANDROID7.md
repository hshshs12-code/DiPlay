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

## Fork features (0.2.13-legacy24.46)

24.46: unit effects driven from the phone, volume that survives mute, phone volume buttons
- `VendorEffects` gains `Mode` (off / neutral / direct) and `Target`: direct mode drives the unit's
  session-0 equalizer bands, bass boost, virtualizer and reverb with the phone's values;
  `UnitFxControl` parks DiPlay's own processing while direct is on and restores it after.
  `AirPlayPersistence` stores the mode and target (the 24.44 neutralise switch migrates).
- `SoundControlBridge`: `unitFxMode`, `unitFxSet`, `volume {delta}`, `digitalVolume`; the volume
  path un-mutes first when Do Not Disturb access is granted, falls back to stepping, then to
  DiPlay's own iPod digital volume, and reports which in `volume.note`. Settings → Audio path
  gains "Allow Do Not Disturb access" (Android refuses app volume changes while the unit is
  muted without it).
- iPhone app: Unit EQ tab (direct control switch, the unit's bands ±15 dB, bass boost,
  virtualizer, reverb), mode picker on the Unit tab, phone volume buttons stepping the unit's
  volume (`VolumeButtonWatcher`: system-volume observation with a reference one step below
  full), DiPlay digital-level slider.

24.45: iPhone companion app over the iPod-mode cable, user presets
- iPod identification offers the External Accessory protocol `com.shilapi.xcertplay` (group 10,
  0xEA00/0xEA01 received, 0xEA03 sent; withheld on the last-resort levels and retried without it
  when a rejection names parameters 6, 7 or 10). `Iap2LinkChannel` / `Iap2CsmChannel` /
  `Iap2Session` carry EA packets (link session 11) both ways; `Iap2IpodClient` tracks sessions.
- `SoundControlBridge` (common): newline-delimited JSON protocol (hello/get/set/preset/eqPreset/
  savePreset/deletePreset/neutralise/volume → state); `AudioEffectSettings.onSaved` pushes every
  change made on the unit to the phone.
- `AudioEffectSettingsJson` (full JSON form with clamped partial merge), `UserPresets` (named
  full-settings snapshots on the unit), `UserPresetSeed` (first start saves the current sound as
  "Good"), Sound → All presets shows user presets and "Save the current sound as a preset…".
- `ios/DiPlaySound`: the SwiftUI app (xcodegen project), see its README.

24.44: the handles the head-unit analysis turned up
- `VendorEffects`: reads the vendor sound app's global effects on audio session 0 (LVM equalizer,
  bass boost, virtualizer, preset reverb) and, with Sound → "Neutralise head-unit effects", takes
  control at a higher priority and holds them flat and off while DiPlay runs (re-applied at every
  session start by `AudioPath`); off hands them back with their previous values.
- `HalVolumeTable`: reads the MediaTek HAL's per-step volume table the way EngineerMode does
  (`GET_AUDIO_VOLUME_VERSION`, `GetCmd=5`, `GetBuffer=256,<size>`, base64) and decodes the
  version-1 layout (9 types × 4 modes × 15 levels); Diagnostics → "Read the firmware volume table".
- `EngineerMode`: Diagnostics → "Open EngineerMode" starts the unit's exported MediaTek
  EngineerMode main menu, whose Audio pages edit that table live.
- Collector: `vendor-probe.txt` (vendor framework classes, device-node permissions, HAL tuning
  keys, decoded volume table, session-0 effects, vendor apps). See docs/HEAD_UNIT_COLLECTOR.md
  for what the first collected zip showed.

24.43: head-unit audio data collector
- `HeadUnitCollector` + Settings → Diagnostics → "Collect head-unit audio data…": app-level data
  (audio service report, config files, relevant APKs and native libraries, kernel and device
  listings) plus, through the unit's own ADB on localhost, dumpsys audio / audio_flinger /
  audio_policy, tinymix, logcat, `.ini` search, root probe; guided captures (Android Auto playing,
  DiPlay playing, knob sweep) tag snapshots; everything packed into one zip saved through the
  document picker or into the app's external files. `LocalAdb` command timeout raised to 120 s.
  See docs/HEAD_UNIT_COLLECTOR.md.

24.42: output path A/B and vendor-enhancement restore
- Android Auto on the same unit is louder and clean on the top volume steps because it plays
  through MediaTek's BesLoudness (the firmware's loudness maximiser and limiter), which DiPlay
  switches off at session start. `AudioPath` now records the firmware's states before switching
  them off and restores them at the next session once "Disable MediaTek sound enhancements" is
  off (some firmwares keep the off state until reboot).
- Settings → Audio path: "Match Android Auto's path" (plain 16-bit at the stream rate, firmware
  enhancements restored) and "DiPlay as the sound card" (float, hardware rate, enhancements off)
  flip the three toggles at once for a direct comparison.

24.41: drive and output trim
- `driveDb` (0 … 12): a loudness maximiser stage, gain after the equalizers and before the bass
  clip and limiter, ramped on the 64-frame grid; it forces the limiter on, even over the hard-off.
  Overview card "Drive" and a stepper under Advanced › Loudness and dynamics. Max Loud: +6 dB.
- `outputTrimDb` (0 … 12): level after the limiter, before the dither, so a unit whose amplifier
  clips on the top volume steps can keep using them; a car setting that presets leave alone.
- `DriveTest`: +6 dB on a quiet tone, ceiling held at +12 dB with the limiter hard-off, trim −6 dB.

24.40: clean volume mode
- `CleanVolume` + `VolumeOverlay`: the head unit distorted on every volume step except 20 (its
  own loudness processing below, amplifier headroom above). With the mode on, a session parks the
  Android media volume at the clean step, the VOLUME_CHANGED broadcasts from the knob move a
  virtual level instead and the real index is put back each time; the level is applied as
  `EqualizerDsp.outputGain` at the end of the chain (also to PCM that bypasses the chain: calls,
  navigation, media without the software chain, iPod audio without a chain) and shown as a pill
  on the CarPlay and iPod screens. The loudness compensation reads the virtual level. The last
  session out restores the real index. Settings → Audio path: switch and clean step (1 … max,
  default 20). Off by default; requires the knob to be detected as Android's software volume.

24.39: bass clip against ducking, limiter hard-off
- `BassClipper`: a soft clipper on the band below 120 Hz, between the enhancer and the stereo
  stage, so bass bounds itself before the full-band limiter. Without it every 808 past the ceiling
  made the limiter pull the whole mix down and hold it there for the note (vocals ducked under the
  bass); with it the limiter only sees mid and treble peaks. Setting `bass_clip_db`, default −3 dB,
  off in the Flat and Podcast presets; level stepper under Advanced › Bass, on/off switch on the
  Sound page. `BassClipperTest` shows a 1 kHz tone under a boosted 55 Hz 808 losing over 2 dB with
  the limiter alone and under 0.7 dB with the clip.
- Limiter hard-off (`limiter_off`): the Limiter switch on the Sound page and in Advanced now means
  off completely, overriding auto level, loudness compensation and the bass enhancer (which 24.38
  had started forcing it on). Peaks then clip at full scale; the status line says so. Presets clear
  it.

24.38: external DSP audit fixes, audit presets, Sound overview, two-card home, bigger targets
- DSP, from the October 2026 external audit (every finding reproduced on the real code first,
  regression fixtures in `DspAuditTest`):
  - `TruePeakLimiter`: 64-tap detector kernel (was 12) and a 0.975 ceiling; random ±0.98 sign
    bursts that used to leave at +1.7 dBTP now stay under 0.99 by a 16×/96-tap reference, also
    after the output resampler. Switching the limiter on or off crossfades over 10 ms instead of
    resetting (no silence gap, no 2 ms jump).
  - `EqualizerDsp`: `configure` designs coefficients on the caller's thread and publishes one
    immutable `Prepared` snapshot; the audio thread adopts it at a block boundary and alone
    touches state, ramps and resets. Ramps advance on a persistent 64-frame grid counted in
    processed audio, so a +12 dB step sounds identical in 1-frame and 4096-frame writes (it was
    0.5 ms vs 32 ms before). The bass enhancer now forces the limiter (its all-pass dry path
    can exceed full scale, which its harmonic guard never bounded). Graphic interaction keeps
    its last correction pass instead of discarding it.
  - `AirShelf`: atomic configuration pickup; per-channel sibilance detector linked by the louder
    channel, so out-of-phase hats are de-essed like centred ones.
  - `StereoStage.boostDb`: width bound is w, not (1+w)/2 (anti-phase input doubles at width
    200); `headroomGain` composes serial boosts (still unused by the renderers).
  - `AirShelf`, `StereoStage`, `LoudnessCompensator`: subnormal flush every 1024 frames.
  - `SincResampler.supports`: only 32/44.1/48 → 44.1/48 kHz use DiPlay's resampler; other
    hardware rates leave the track at the stream rate (48→16 kHz measured −18 dB alias rejection).
  - `AudioPath`: ten quiet minutes no longer promote the knob to HARDWARE (that let the next
    session pin a software-volume unit to maximum); an earlier automatic verdict is discarded and
    the settings page explains the manual buttons. Automatic detection only ever concludes
    SOFTWARE.
- Presets: three "(Audit)" variants next to the originals (Rage, 808 Heavy, Podcast) with the
  audit's gentler correction and lighter lifts, for A/B listening; preset descriptions no longer
  promise speaker protection or "untouched" sub-bass.
- UI, from the audit's review plus its four mockups:
  - Sound page opens on an overview: four favourite presets, Bass character and Auto level
    cards, a limiter/driver-side/Custom status line, All presets and Advanced. Parametric band
    type is picked from a list; Reset offers Undo; every target ≥ 64 dp; EQ graph locks the
    touched column for the whole slide.
  - Simple home: one header (car-home glyph, DiPlay, status pill, Update, clock), two source
    cards (CarPlay with wireless/disconnect inside, iPod audio), a live Now Playing strip with
    play/next, then Sound and Settings. The "eq" page goes through the common render tail again
    (scroll position restored, single Back).
  - iPod screen: 64 dp back/gear/seek, 80 dp previous/next, 96 dp play, three equal Siri / Apps /
    Sound buttons (apps in a dialog grid), controls the phone refused are disabled not just
    dimmed, a persistent recovery line for USB permission / failures (with a permission-result
    receiver that starts the engine once granted), 1 s refresh with meters/log only while the
    sheet is open, sheet with an explicit Close and 64 dp rows.
  - Plug-in chooser: whole-row Remember, 64 dp Not now, first-tap lock on both cards, state kept
    across recreation; "When the iPhone is plugged in" added to Settings → Home screen and launch.
  - Switch rows everywhere are 64 dp and the whole row toggles them.

24.37: plug-in chooser
- `PlugInChooserActivity`: on a USB plug-in from anywhere, a fullscreen screen in the iPod
  design offers CarPlay or iPod audio as two big targets, with "Remember my choice" and "Not now".
- `IpodSettings.plugInChoice` (ask / carplay / ipod, migrated from the old auto-start flag) and a
  three-way chip row on the iPod screen's sheet. The iPod screen in the foreground or a running
  iPod session still takes the plug-in directly; CarPlay auto-launch off still means nothing opens.

24.36: plug-in on the iPod screen starts iPod audio
- While `IpodModeActivity` is in the foreground (or an iPod session is running), a USB attach is
  handed to iPod mode instead of CarPlay by the CarPlay host, and the iPod screen's own attach
  receiver starts the session without tapping Start. The "start iPod audio on plug-in" setting
  still covers plug-ins from anywhere else.

24.35: sound UI for a car touchscreen, EQ page fix, home update button, 808 Heavy Pure
- `EqualizerPanel` rebuilt: preset tiles, collapsible groups (Bands, Bass, Loudness and
  dynamics, Tone, Stereo, Parametric EQ) and big −/+ steppers instead of drag sliders.
- Fix: the "eq" page in `DiPlayActivity.render` returned before `setContentView`, so the
  Equalizer tile on the home screen and the Equalizer button on the iPod screen did nothing.
- "Update" tile on the simple home and "Check for updates" on the classic home, running the
  existing GitHub release check in a dialog.
- Preset "Yaris · 808 Heavy Pure": 808 Heavy's curve and correction with the bass enhancer off
  (no harmonics, no sub trim), subsonic 40 Hz, limiter on.
- Whole app restyled to the iPod screen's design language: darker base with a soft wash,
  translucent rounded tiles and buttons (white primary), status pill in the home header.

24.34: professional sound-processing pass (seven parallel DSP work streams, merged by hand)
- `BassEnhancer`: Linkwitz-Riley 4th-order band split with matching all-pass, peak envelope with
  25 ms hold / 60 ms release, Chebyshev T2/T3/T4 harmonic generator driven at unit amplitude so
  harmonic level tracks the sub band (2:1 above −12 dBFS), harmonics band-limited 1.5×–6× the
  crossover (≤700 Hz), sub-band trim up to −6 dB at full amount, clip guard when the limiter is off.
- `Leveler`: K-weighted (60 Hz HPF + 1.5 kHz +4 dB shelf) gated program leveler, −14 LUFS target,
  absolute (−50 LU) and relative (−20 LU) gates, momentary + long-term estimates for program-
  dependent release, 0.3 s hold, slew-limited attack/release, ±12 dB.
- `StereoStage`: mid/side width (side high-passed at 200 Hz when widening), constant-power
  balance, fractional driver-seat delay (Lagrange), Bauer crossfeed; 20 ms gain ramps, glided
  delay; settings stereo_width / balance / driver_seat_delay_tenths_ms / driver_side / crossfeed.
- `TruePeakLimiter`: 4× oversampled true-peak detection (12-tap Kaiser polyphase), sliding-min
  look-ahead with 20 ms hold, dual release (50 ms / 500 ms program-dependent), Hann-like attack via
  cascaded moving averages, stereo-linked, hard clip at 0.999; bass THD < 0.0001 %.
- `AirShelf`: de-essed air shelf (air_db 0–8, air_hz 6/8/10/12 kHz, de_ess 0–100): one shelf per
  channel crossfaded by a sibilance detector (5–9 kHz band vs broadband, 1 ms attack).
- `LoudnessCompensator` + `LoudnessVolumeTracker`: ISO 226-derived low shelf (100 Hz) and high
  shelf (8 kHz) driven by the Android media-volume index when the knob is the software volume
  (loudness_comp / loudness_comp_amount / loudness_comp_reference), 200 ms ramps.
- Chain order: subsonic → graphic → parametric → loudness → air → bass → stereo → leveler →
  limiter → dither. Yaris presets updated (driver-seat delay 1.2 ms, air +3 dB, loudness on Night
  Drive / Rage / Trap Clean). EQ panel: Stereo stage, Air and Loudness sections.
- Cost on the dev JVM with every stage on: ~365 ns per stereo frame at 48 kHz (1.8 % of a desktop
  core); the typical preset runs well under that.

24.33: iPod audio confirmed on the car; fullscreen; latency guard; charge readout
- Fifth on-car report (24.32, iPhone 16 Pro Max): the full identity with 32/44.1/48 kHz
  was accepted first time, the phone answered StartUSBDeviceModeAudio with 44.1 kHz and
  the car played through the direct driver. The missing 32 kHz was the cause all along.
- The same log showed ~1.3 s of packets buffered in the driver ring (captured while the
  output was being opened) sitting in front of the music. The engine now flushes the
  ring when playback starts and drops backlog above 250 ms (`UsbIsoAudioNative.flush`).
- iPod screen is edge to edge: system bars hidden, swipe from the edge brings them back.
- PowerUpdate parameter 0 (MaximumCurrentDrawnFromAccessory) is subscribed and shown as
  "drawing N mA" next to the battery level, and logged with the announced current, so the
  charging-current setting can be tuned against what the phone actually pulls.

24.32: audio request sequencing and richer rejection logging
- IdentificationRejected parameters are logged with length and raw hex
  (they may carry the offending message IDs, not just the parameter number).
- The UAC streaming alternate is selected and a rate set before
  StartUSBDeviceModeAudio is sent (real hosts have the audio interface active
  first); the phone's USBDeviceModeAudioInformation re-opens capture at the
  announced rate. The blind 6 s capture fallback now only applies when the
  audio request could not be declared.
- Ladder level 2 additionally declares the identification messages
  themselves (1D01/1D05 sent, 1D00/1D02/1D03 received), the control-session
  version 2 rule from newer specification revisions.

24.31: required sample rates + Now Playing redesign
- Fourth on-car report (24.30, iPhone 16 Pro Max, USB-C): the phone rejects
  0xDA00 in the sent list and 0xDA01 in the received list individually, so
  the message IDs are not the issue; it treats USB device-mode audio as
  unsupported by this accessory. The MFi specification requires accessories
  using USB device-mode audio to offer at least 32, 44.1 and 48 kHz; DiPlay
  offered only 44.1 and 48. The USBDeviceTransportComponent now declares all
  three (and level 1 of the ladder declares every enum rate as a hedge).
- iPod Now Playing screen redesigned: large rounded artwork with a blurred
  backdrop and a colour wash from the cover, large type, scrubber with
  elapsed/remaining, deck-style transport row, Siri/app chips; settings,
  meter and log moved to a bottom sheet behind the gear.

24.30: iPod mode accepted on the car; bisecting the USB-audio request
- Third on-car report (24.29): level 1 (everything except the USB-device-mode
  audio messages 0xDA00/01/02) was accepted; MFi authentication succeeded with
  the offline identity; Now Playing, artwork and the HID remote worked; the
  direct isochronous driver streamed 44.1 kHz packets with zero errors. The
  phone kept its own speaker as the route because the audio request could
  not be declared.
- Ladder now bisects that group: level 1 = start + information (no stop),
  level 2 = start only, level 3 = no audio group (the accepted one), then the
  other eliminations. "Request USB audio" on the iPod screen sends 0xDA00 even
  when undeclared, as a logged experiment.

24.29: iPod identification elimination ladder
- Second on-car report: every 24.27 level was rejected on parameters 6/7,
  including the minimal one, so the fault is not in the optional groups the
  ladder removed. The ladder is now by elimination: level 1 drops only the
  USB-device-mode-audio messages, 2 only the HID remote, 3 only app launch +
  SetNowPlaying, 4 only power, 5 the fork's wired-proven message set, 6 auth +
  Now Playing only. Each attempt logs its exact message-ID lists (payload
  lines are redacted from uploads), so the next report names the culprit.
- When the accepted level has no USB-audio request, the engine's 6 s capture
  fallback still tries to pick the stream up.

24.28: float track buffer sized correctly
- The on-car log showed the float media track at 684 ms instead of the
  configured 500 ms: 24.25 doubled the whole plan, including the already
  float-sized minimum buffer. `MediaAudioBuffer.plan` now takes the sample size
  and scales only the time-based part.

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
