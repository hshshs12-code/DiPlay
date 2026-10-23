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
| `proc.txt` | Sound cards, device nodes, tty and i2c listings |
| `adb/` | With ADB: root probe, dumpsys audio / audio_flinger / audio_policy, tinymix, `.ini` search and contents, full and audio-filtered logcat, mounts |
| `captures/` | The tagged snapshots: audio flinger, audio service, mixer controls and log tail per capture; the knob sweep's logcat |

## What the analysis looks for

- The Android volume curve and where step 20 sits on it; whether the mixer applies gain above 0 dB.
- The MCU service's command per knob step (the amplifier table), and any interface DiPlay could call.
- The difference between Android Auto's and DiPlay's tracks in the audio flinger: stream, format,
  rate, gain, effects (BesLoudness).
- Whether the unit is rootable without flashing.
