# DiPlay Sound (iPhone companion app)

Controls everything on the head unit's Sound screen from the phone: presets (built in and your
own), the 12-band equalizer, every stage switch and amount (limiter, bass clip, drive, output
trim, auto level, loudness compensation, subsonic, bass enhancer, air, de-ess), the stereo stage,
parametric bands, the head unit's own effects (neutralise), and the unit's media volume.

It talks to DiPlay over the USB cable while the unit runs **iPod audio** (24.45 or later): DiPlay
identifies to the iPhone as an MFi accessory and offers the External Accessory protocol
`com.shilapi.xcertplay`; this app opens an `EASession` on it and exchanges newline-delimited JSON.
No Wi-Fi, no Bluetooth, no pairing: plug in, start iPod audio on the unit, open the app.
CarPlay mode is not covered yet.

## Build and install (needs a Mac with Xcode)

1. `brew install xcodegen` (once), then in this folder: `xcodegen generate`.
2. Open `DiPlaySound.xcodeproj`. Select the DiPlaySound target → Signing & Capabilities → pick your
   Apple ID team (a free personal team works; the app then needs re-installing every 7 days).
   Change the bundle identifier if Xcode says it is taken.
3. Plug the iPhone into the Mac, choose it as the run destination, press Run. On the phone:
   Settings → General → VPN & Device Management → trust the developer certificate the first time.
4. In the car: plug the phone into the unit, open DiPlay → iPod audio → Start. The unit's status
   pill shows "· iPhone app" once the app has joined.

`Info.plist` carries `UISupportedExternalAccessoryProtocols = [com.shilapi.xcertplay]`; that key
is what lets iOS hand the session to this app. Without it the accessory is invisible to the app.

## Protocol (phone ↔ head unit)

One JSON object per line. The unit answers every change with a full `state`.

| Phone sends | Unit does |
|---|---|
| `{"t":"hello","app":…,"v":1}` / `{"t":"get"}` | replies `state` |
| `{"t":"set","s":{…any settings keys…}}` | merges, saves, applies live; clears the preset name unless `presetName` is included |
| `{"t":"preset","name":…}` | applies a built-in or user preset |
| `{"t":"eqPreset","name":…}` | applies an equalizer curve (bands only) |
| `{"t":"savePreset","name":…}` / `{"t":"deletePreset","name":…}` | stores or removes a user preset on the unit |
| `{"t":"neutralise","on":true}` | holds the unit's own session-0 effects flat |
| `{"t":"volume","index":n}` | sets Android's media volume |

`state` = `{t, v, unit, s: settings, meta: ranges and choice lists, userPresets: [names], volume: {index,max}, unitFx: {text, neutralised, enabled}}`.
