# iPod mode (USB audio without CarPlay)

iPod mode makes the head unit behave like a classic car stereo with "iPod control": the iPhone
plays through the car over USB, shows what is playing, and takes track commands, with no CarPlay
session. Everything a factory USB-iPod head unit does, with a screen's worth of extras.

## Using it

- Home screen → **iPod audio** → **Open player** → **Start** (or just plug the phone in and pick
  iPod audio on the chooser). Allow the USB prompt the first time; the screen tells you what it is
  waiting for and starts by itself once access is granted. Keep the phone unlocked for the first
  connection.
- The status pill goes Connecting → Connected → "Connected · 44.1 kHz" once the phone starts
  streaming. Play anything on the phone: Music, Spotify, Podcasts, YouTube Music, a browser.
- Controls: previous / play-pause / next (80 and 96 dp), shuffle, repeat, a seekable scrubber in a
  56 dp strip, and three big buttons: **Siri**, **Apps** (Music, Podcasts, Spotify, YouTube Music
  on the phone) and **Sound**. Anything the phone refused (no HID, no seeking) is disabled, not
  just dimmed. Steering-wheel keys and Bluetooth remotes work through Android's media session.
- The gear (⚙) opens the sheet (with its own Close): volume, +6 dB gain boost, the direct USB
  audio driver switch, what to do when the iPhone is plugged in (ask / CarPlay / iPod audio), the
  charging current announced to the phone, Sound, "Request USB audio", "Switch to CarPlay", the
  level meter and the audio path / underrun counters with the last log lines.
- Playback continues in the background with a media notification (previous / play / next / stop).
- The screen is edge to edge; swipe from the top edge to see the head unit's bar.

## Charging

The phone draws what DiPlay announces (500–2400 mA). The phone reports what it actually pulls;
it is shown next to the battery level as "drawing N mA" and logged. Raise the setting one step at
a time; if the connection stutters or the figure drops back, the port cannot supply it. There is
no USB-C Power Delivery on a USB-A host port, so 5 V × 1–2 A is the ceiling.

## How it works

1. **USB configuration.** The phone is switched to its audio configuration (audio control, audio
   streaming, HID), the same one these head units force at plug-in. Kernel drivers are detached on
   a throwaway connection so the configuration change is accepted.
2. **Accessory link.** iAP2 runs over the HID interface: link packets are fragmented into HID
   reports according to the phone's report descriptor (`Iap2HidFraming`, `Iap2HidStream`), using
   the fork's existing iAP2 link/session stack.
3. **Identification.** `Iap2IpodClient` answers StartIdentification with a USB-device transport
   component (32/44.1/48 kHz — the specification's minimum set; without 32 kHz the phone treats
   USB audio as unsupported), an iAP2 HID "media remote" component and the message lists for
   authentication, Now Playing, power, USB device-mode audio, HID and app launch. If the phone
   rejects a parameter the client retries by elimination (dropping one feature group per attempt)
   and logs the declared message IDs and the rejection TLVs.
4. **Authentication.** The phone's 32-byte challenge is signed with the fork's offline MFi
   identity (the same one CarPlay uses).
5. **Audio.** The UAC streaming alternate is selected and the sample rate set before
   StartUSBDeviceModeAudio (0xDA00) goes out; USBDeviceModeAudioInformation (0xDA01) announces the
   rate. The native driver (`usb_iso_audio.c`) submits isochronous URBs through usbfs on the file
   descriptor Android grants and hands PCM to Kotlin through a ring buffer; `UsbAudioDescriptors`
   parses the UAC1 formats. Startup backlog is flushed and anything above 250 ms is shed so pause
   and skip respond immediately. If the driver cannot open, Android's USB audio input is the
   fallback.
6. **Everything else** rides the same link: Now Playing updates (title, artist, album, duration,
   elapsed, playback state, shuffle, repeat, source app), artwork over the file-transfer session,
   PowerUpdate (battery, charging state, current drawn), AccessoryHIDReport for the remote,
   SetNowPlayingInformation for seek/shuffle/repeat, RequestAppLaunch for the shortcuts.

The audio then passes through DiPlay's sound processor and the "audio path" output stage exactly
like CarPlay music does.

## Troubleshooting

- **"The iPhone rejected the accessory identification"**: upload a diagnostic report; the log
  lists every attempt's message IDs and the phone's rejection parameters.
- **Connected but silent, phone still plays on its speaker**: the phone did not answer the USB
  audio request. The log shows whether 0xDA00 was accepted in identification and whether 0xDA01
  arrived. "Request USB audio" in the gear sheet resends it.
- **Connected, phone routed to USB, still silent**: the direct driver's packet counters are in
  the log (`direct usb audio packets=… errors=…`). Zero packets means the streaming interface did
  not start; try the Android capture fallback by switching the direct driver off.
- **Everything works but it clicks or stutters**: check `underruns` in the status line; lower the
  sound-processing load (fewer stages) or switch the direct driver off to compare.
