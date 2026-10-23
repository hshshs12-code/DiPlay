# Head-unit audio data collector

Settings → Diagnostics → **Collect head-unit audio data…** (24.43). One zip with everything needed
to understand how this unit turns a knob step into sound, for offline analysis with
reverse-engineering tools. Nothing on the unit is changed.

## Before you start (once)

1. Settings → System → About: tap Build number seven times. Developer options → USB debugging on,
   and **ADB over network** on if the toggle exists (some builds list it under Car settings).
2. Optional: the factory menu (482 or 1234) → any Export / Backup / Config item → USB stick.
3. Put a USB stick in. Have a loud, bass-heavy track ready on the phone.

## In the car

1. Open the collector. It first gathers the app-level data, then asks to use the unit's ADB (approve
   the "Allow USB debugging?" prompt if it appears). With ADB it also captures the audio flinger
   state, every app's log, the mixer controls and a root probe.
2. Guided captures, any order:
   - **Android Auto playing**: start Android Auto, play the track at the loud step (25), come back
     to DiPlay, tap the button.
   - **DiPlay playing**: connect DiPlay, same track, same step, tap the button.
   - **Knob sweep**: tap Start, turn the knob slowly from 0 to max, tap again.
3. Finish and save the zip to the USB stick.

## What is in the zip

| Folder / file | Content |
|---|---|
| `meta.txt`, `getprop.txt`, `diplay-report.txt` | Identity, properties, DiPlay's own report |
| `audio-manager.txt` | Every stream's volume and maximum, vendor parameters (BesLoudness…), output devices, installed effects |
| `config-files/` | Audio policy and volume tables, mixer paths, effects config, MediaTek audio parameters, any `.ini` in the usual places |
| `packages.txt`, `apks/`, `apk-libs/` | Package list; the MCU, factory, radio, Bluetooth, phone-link and audio apps with their native libraries |
| `libs/`, `libs-index.txt` | Audio HAL, effect, MCU and serial libraries from the system and vendor partitions |
| `vendor-probe.txt` | (24.44) Vendor framework classes visible to apps (AutoChips audio-processor API, metazone, MCU serial), device-node permissions, the HAL tuning keys EngineerMode uses, the decoded HAL volume table, the global effects on audio session 0 |
| `proc.txt` | Sound cards, device nodes, tty and i2c listings |
| `adb/` | With ADB: root probe, dumpsys audio / audio_flinger / audio_policy, tinymix, `.ini` search and contents, full and audio-filtered logcat, mounts |
| `captures/` | The tagged snapshots: audio flinger, audio service, mixer controls and log tail per capture; the knob sweep's logcat |

## What the first run found (9212A, ac8227l, build F9212A_00017_V001)

Decompiling the collected apps and HAL libraries settled where the gain is on this board:

- No amplifier or audio-processor chip that software can address. The vendor Sound Effects app
  (`com.xy.tda7729`) picks one of three board variants from the build string; builds containing
  `00017` get the "internal" variant (SoC codec only). Its TDA7729 master-volume API
  (`com.autochips.audiosettings.AudioSettings` → HIDL → ALSA `TDA7729*` controls) exists for the
  other boards. The power amp is fixed gain, switched by the `XY_AMP_CTRL_ONOFF` ioctl on
  `/dev/mygpio`.
- Gain stages that do exist: Android's stream volume (4-point curve, same in /vendor/etc); the
  MediaTek HAL volume controller with its NVRAM per-step table (speaker PGA plus digital gain),
  edited by EngineerMode and readable with `GetBuffer=` (see `HalVolumeTable`); the vendor's global
  effects on audio session 0 (LVM equalizer up to ±15 dB, bass boost, virtualizer, reverb, applied
  under package name com.android.systemui by the Sound Effects app's service); BesLoudness,
  MusicPlus and BesSurround in the HAL.
- `VolumePosControl` is the stock LVM Volume effect; its "position" is stereo balance.

24.44 adds the handles that follow from this: Sound → "Neutralise head-unit effects" (reads and
holds the session-0 effects flat), Diagnostics → "Read the firmware volume table" and "Open
EngineerMode", and the vendor probe in the collector.

## What the analysis looks for

- The Android volume curve and where step 20 sits on it; whether the mixer applies gain above 0 dB.
- The MCU service's command per knob step (the amplifier table), and any interface DiPlay could call.
- The difference between Android Auto's and DiPlay's tracks in the audio flinger: stream, format,
  rate, gain, effects (BesLoudness).
- Whether the unit is rootable without flashing.
