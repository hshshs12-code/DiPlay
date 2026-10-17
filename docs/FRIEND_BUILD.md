# Friend build (`legacy-api24-friend`)

A side line of the legacy fork for a second alps 8227L head unit whose iPhone pairs over
Bluetooth, accepts the Wi-Fi configuration, but never joins the Wi-Fi Direct group (every start
ends in "No AirPlay TCP after CarPlay StartSession"). Its diagnostic report showed the group
created on a pinned 2.4 GHz channel 6 with `verified=false`: below Android 10 the platform never
reports the group's real channel, and the iPhone only scans the channel it is told.

What differs from the main line (everything else is 24.38):

- **Live channel from a scan.** After the group is up, the unit scans for its own SSID and sends
  the iPhone the channel it actually sees, logging `mismatch requested=… observed=…` when the
  driver ignored the pin. With no scan hit, a single-radio unit's station channel is used, then
  the requested one.
- **"Let the car pick (no channel pin)"** in Connection setup → Preferred Wi-Fi Direct channel:
  plain `createGroup` with no channel request, channel observed as above.
- **Automatic ladder.** After every two first-TCP timeouts in Wi-Fi Direct mode the preference
  moves on: auto (pinned ladder) → car's own choice → pinned 1 → pinned 11 → pinned 6. A rendered
  session keeps the step that worked; picking a channel by hand resets the counter. Each step is
  logged as `wireless fallback: …`.
- **Updater** only offers releases whose tag contains `friend`, so the main line's releases never
  replace this build; the main line's updater ignores pre-releases, so the reverse holds too.
- Version `0.2.13-legacy24.38-friend1`, published as a pre-release.

If Wi-Fi Direct never works on this unit, the remaining options need no build: **Built-in car
hotspot** (turn the head unit's own hotspot on in Android settings and save its name and
password in Connection setup) or **Existing Wi-Fi / Same LAN**.
