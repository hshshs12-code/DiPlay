# DiPlay legacy fork (Android 7–8.1 head units)

**CarPlay and iPod-style USB audio for cheap MediaTek Android head units, with a car-audio-grade
sound processor.** This is a personal fork of [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)
that lowers the Android floor from 9 to 7.0 (API 24) and adds everything a one-DIN-era "iPod
head unit" does, built and tested on an `alps 9212A` (ac8227l, Android 8.1, 1 GB RAM, 1024×600).

[Latest release](https://github.com/hshshs12-code/DiPlay/releases/latest) ·
[All releases](https://github.com/hshshs12-code/DiPlay/releases) ·
[Changelog](docs/LEGACY_ANDROID7.md) ·
[iPod mode guide](docs/IPOD_MODE.md) ·
[Sound processing guide](docs/SOUND_PROCESSING.md)

> Not an Apple-certified product. The APK carries the same experimental accessory identity as
> upstream DiPlay. It works on the car it was built for; nothing else is guaranteed. The upstream
> project's own README is kept at [docs/UPSTREAM-README.md](docs/UPSTREAM-README.md).

## What you get

- **Wired and Wi-Fi Direct CarPlay on Android 7.0+**, including the USB fixes this class of unit
  needs: configuration switching past the forced "iPod" USB mode, kernel-driver detach, 16 KB bulk
  limits, IPv6 neighbour discovery for the NCM link.
- **iPod mode**: music without CarPlay. DiPlay identifies and MFi-authenticates to the iPhone as a
  USB audio accessory, the phone streams PCM over USB, and a native isochronous driver reads it
  straight off the bus. Artwork, title/artist/album, seek, play/pause/skip, shuffle/repeat, Siri,
  app shortcuts, battery and charging readout, steering-wheel keys through an Android media
  session, background playback with a notification. See [docs/IPOD_MODE.md](docs/IPOD_MODE.md).
- **Sound processing** on CarPlay music and iPod mode alike: 12-band constant-Q graphic EQ with
  analog-matched bells, 5 parametric bands, subsonic filter, MaxxBass-style bass enhancer,
  K-weighted program leveler, true-peak limiter, de-essed air shelf, volume-linked loudness
  compensation, stereo width / balance / driver-seat delay / crossfeed, plus fourteen presets tuned
  for a 2018 Toyota Yaris with stock door speakers (eleven originals and three audit retunes). The
  chain was externally audited in October 2026 and its findings are fixed and kept as regression
  tests. See
  [docs/SOUND_PROCESSING.md](docs/SOUND_PROCESSING.md).
- **DiPlay as the sound card**: music opens at the hardware rate in float with DiPlay's own
  windowed-sinc resampler, MediaTek BesLoudness/BesSurround/MusicPlus are switched off, and the
  volume-knob type (hardware vs Android software volume) is detected when the knob moves Android's
  volume, and set by hand otherwise.
- **Head-unit practicalities**: in-app updater from this repository's releases, one-tap diagnostic
  upload, USB-competitor killer and default-handler shortcut, plug-in auto-launch (CarPlay or iPod
  mode), simple home screen, cold-start warm-up and baseline profile, touch-friendly settings.

## Install

1. Download the APK from the [latest release](https://github.com/hshshs12-code/DiPlay/releases/latest)
   and install it on the **head unit** (allow unknown sources). Later updates arrive through the
   Update button on the home screen.
2. Plug the iPhone in with a data cable. When Android asks which app should handle the device,
   pick DiPlay and tick "Use by default".
3. Home screen: the **CarPlay** card (Connect USB, wireless underneath), the **iPod audio** card
   (Open player), a Now Playing strip, then **Sound** and **Settings**; **Update** sits in the
   header. Plugging the iPhone in asks CarPlay or iPod audio unless you told it to remember.

If something fails, Settings → Diagnostics → **Upload diagnostic report** gives a link to a
redacted log (no payloads, no credentials). The previous APK is always on the releases page.

## Documentation

| Topic | Where |
|---|---|
| Everything this fork changed, release by release | [docs/LEGACY_ANDROID7.md](docs/LEGACY_ANDROID7.md) |
| iPod mode: how it works, controls, troubleshooting | [docs/IPOD_MODE.md](docs/IPOD_MODE.md) |
| Sound processing: presets, controls, design | [docs/SOUND_PROCESSING.md](docs/SOUND_PROCESSING.md) |
| Building from source | [docs/BUILD.md](docs/BUILD.md) (plus the notes below) |
| Upstream documentation | [docs/UPSTREAM-README.md](docs/UPSTREAM-README.md) and the other files in `docs/` |

## Building

Branch `legacy-api24`. Gradle 9.5, JDK 25, Android SDK with NDK 28, `minSdk 24`. The release
build needs two things that are deliberately not in the repository: the accessory identity
(`identity.pk8` and `certificate.p7b` under a directory passed as `DIPLAY_AUTH_ASSETS_DIR`,
extracted from an official DiPlay APK) and a signing keystore (`ANDROID_KEYSTORE_PATH`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`).

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
export DIPLAY_AUTH_ASSETS_DIR=/path/to/auth   # contains offline-mfi/identity.pk8 and certificate.p7b
./gradlew :mobile:assembleRelease
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest
```

## Credits and license

Upstream DiPlay by [shihabal3amri](https://github.com/shihabal3amri/DiPlay). MediaTek decoder
and audio-path findings informed by [Open Headunit](https://github.com/andreknieriem/open-headunit).
Same license as upstream; see [LICENSE](LICENSE) and
[docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md).
