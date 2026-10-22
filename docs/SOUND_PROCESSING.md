# Sound processing

DiPlay's software chain runs on CarPlay music and iPod audio before Android's mixer, in double
precision, quantised once at the output. Equalizer → 12-band software equalizer must be on for
anything beyond the head unit's own effect.

## Chain, in order

| Stage | Class | What it does |
|---|---|---|
| Subsonic filter | `EqualizerDsp` | 24 dB/oct high-pass (two cascaded Butterworth sections). Removes what the door speakers only flap on. |
| Graphic EQ | `EqualizerDsp` | 12 bands 31 Hz–16 kHz, ±12 dB. Constant-Q from band spacing, Orfanidis prescribed-Nyquist-gain bells (no squashing at 12–16 kHz), neighbour-interaction correction so adjacent +6 dB sliders give +6 dB. |
| Parametric EQ | `EqualizerDsp` | Up to five peak / low-shelf / high-shelf bands (RBJ). |
| Loudness compensation | `LoudnessCompensator` | Volume-linked bass/treble voicing inspired by the ISO 226 equal-loudness contours: a low shelf (100 Hz) and a high shelf (8 kHz) whose plateau gains come from the contour differences (the 100 Hz point itself sits at half the plateau, as any shelf does). Driven by the estimated Android media-volume level, when the knob is the software volume; not a calibrated SPL model. 200 ms ramps. |
| Air shelf | `AirShelf` | High shelf (6/8/10/12 kHz, 0–8 dB) crossfaded by a sibilance detector (5–9 kHz vs broadband, per channel and linked by the louder one, so out-of-phase hats are seen too). It backs the lift off during "s" sounds; it does not remove sibilance already in the mix. |
| Bass enhancer | `BassEnhancer` | Linkwitz-Riley 4th-order split with matching all-pass; peak envelope (25 ms hold, 60 ms release); Chebyshev T2/T3/T4 harmonics at unit amplitude so their level tracks the sub band (2:1 above −12 dBFS); harmonics band-limited 1.5×–6× the crossover (≤700 Hz); sub trim up to −6 dB at full amount. The enhancer always runs under the limiter: its all-pass dry path can exceed full scale on square-ish bass, which its own harmonic guard cannot bound. |
| Bass clip | `BassClipper` | Soft clipper on the band below 120 Hz (Linkwitz-Riley split with matching all-pass, linear to 60 % of the ceiling, tanh knee above). Default −3 dB. Bass bounds itself, so an 808 never drives the full-band limiter and the vocals stop ducking under the bass. |
| Stereo stage | `StereoStage` | Mid/side width (side high-passed at 200 Hz when widening, so the bass image stays as recorded), constant-power balance (equal gain-squares, not equal power for unequal channels), fractional driver-seat delay (4-point Lagrange: exact at low frequencies, the delayed side droops ~1 dB at 12 kHz for fractional delays), Bauer crossfeed. 20 ms gain ramps, glided delay. |
| Auto level | `Leveler` | Gated program leveler with a K-like weighting (60 Hz HPF + 1.5 kHz +4 dB shelf) and a −14 dB weighted-programme target. It is a car AGC, not a BS.1770 meter: short gate blocks, −50 / −20 relative gates, momentary + long-term estimates for program-dependent release, 0.3 s hold, slew-limited, ±12 dB. |
| Limiter | `TruePeakLimiter` | 4× oversampled true-peak detection with a 64-tap Kaiser polyphase kernel (reads broadband bursts within 0.15 dB of a 16×/96-tap reference; the old 12-tap kernel let sign bursts through at +1.7 dBTP), 2 ms look-ahead, sliding-minimum with 20 ms hold, dual release (50 ms / 500 ms), Hann-like attack, stereo-linked, ceiling 0.975 (−0.22 dBFS), hard clip at 0.999. Verified on 200 random sign bursts: output reference true peak ≤ 0.99, also after the 44.1→48 kHz output conversion. Switching it on or off crossfades over 10 ms (no gap, no jump). Digital peak protection only: it cannot know the amplifier's rails or the speakers' excursion. |
| Dither | `EqualizerDsp` | TPDF ±1 LSB at the single 16-bit quantisation; float output skips it. |

All stages are skipped when neutral. A settings change designs every coefficient on the caller's
thread and publishes one immutable snapshot; the audio thread adopts it whole at the next block
boundary and is the only thread that touches filter state, ramps and resets, so a block can never
mix two generations of settings. Coefficient ramps advance on a fixed 64-frame grid counted in
processed audio (~30 ms whatever the write size); removed bands ramp out instead of clicking; every
stage flushes subnormal memory on silence. Output goes through `MediaOutputStage` (float, hardware
rate via the 32-tap Kaiser windowed-sinc `SincResampler`, flat to 20 kHz). The resampler is only
used for the qualified pairs 32/44.1/48 kHz → 44.1/48 kHz; any other hardware rate leaves the
track at the stream rate and lets Android convert, because a fixed 32-tap kernel cannot reject
aliases for large ratios.

## Presets (Sound → favourites, or All presets)

Tuned for a 2018 Toyota Yaris with stock 6.5" door speakers and dash tweeters. The original eleven
share the cabin correction: −3 dB at 170 Hz (door-panel boom), −2 dB at 3.2 kHz (tweeter edge),
+2 dB high shelf at 9 kHz. Those are listening-based starting points, not a measured cabin
response. The three "(Audit)" variants come from the October 2026 external DSP audit and sit next
to the originals for A/B listening: a gentler correction (−2 dB at 170 Hz, −1.5 dB at 3.2 kHz, no
top shelf), lighter 62 Hz lifts, less stacked treble.

| Preset | For | Bass enhancer | Extras |
|---|---|---|---|
| Rage | Ken Carson / Nine / Vicious | 70 % below 90 Hz | driver-seat delay 1.2 ms, air +3 dB, loudness 50 % |
| 808 Heavy | maximum low end | 90 % below 100 Hz | — |
| 808 Heavy Pure | same curve, no harmonics, real sub untouched | off | — |
| Trap Clean | balanced trap | 45 % | delay 1.2 ms, air +3 dB, loudness 50 %, auto level 40 % |
| Melodic / Vocals | melodic rage | 40 % | delay 1.2 ms, air +2 dB |
| Flat Corrected | just the cabin fix | off | limiter off |
| Highway | road noise | 40 % | auto level 70 % |
| Windows Down | wind noise | 30 % | air +3 dB, auto level 50 % |
| Night Drive | quiet listening | 50 % | loudness 70 %, auto level 60 % |
| Podcast / Voice | speech | off | subsonic 90 Hz, auto level 90 % |
| Max Loud | as loud as the limiter allows | 50 % | auto level 100 % |
| Rage (Audit) | audit retune of Rage | 55 % below 85 Hz | delay 1.2 ms, air +1 dB, no loudness |
| 808 Heavy (Audit) | audit retune of 808 Heavy | 65 % below 85 Hz | subsonic 45 Hz, 62 Hz +2 dB instead of +6 |
| Podcast (Audit) | audit retune of Podcast | off | subsonic 70 Hz, no extra presence bell, auto level 75 % |

"More equalizer curves…" holds the generic band-only curves (Flat, Sound Good, Bass Boost, …).

## Controls

The Sound page opens on an overview: four favourite presets (Trap Clean, Rage, 808 Heavy,
Podcast), **Bass character** (the enhancer amount) and **Auto level** as two large −/+ cards, a
status line (limiter state and what forces it, driver side, "Custom" once anything is edited), then
**All presets** and **Advanced**. Every target is at least 64 dp; every value is a −/+ stepper;
groups in Advanced collapse.

- **Bands**: touch a column and slide; the column you touch first stays selected for the whole
  slide, so a drag that wanders sideways never edits the neighbour. Flatten, all −1 dB, all +1 dB.
- **Bass**: enhancer amount, bass clip level (−1 … −9 dB, Off), enhancer crossover (the line below
  which harmonics are generated and the sub is trimmed; nothing is removed by it), subsonic filter
  (the only stage that removes frequencies outright), device bass boost.
- **Loudness and dynamics**: auto level + strength, limiter (kept on while auto level, the bass
  enhancer or loudness compensation is on; switching it off means off completely, whatever those
  stages prefer, and peaks then clip at full scale), loudness compensation + amount + reference
  volume step, device loudness.
- **Sound page switches**: Limiter and Bass clip sit right under the two everyday controls.
- **Tone**: air dB, air corner, de-ess.
- **Stereo**: width, balance, driver-seat delay, driver side, crossfeed.
- **Parametric EQ**: tap a band to pick Off / Peak / Low shelf / High shelf from a list; frequency,
  gain, Q.
- **Reset everything to flat** offers Undo.

## Audio path (Settings → Display → Audio path)

- Output at the hardware sample rate (DiPlay resampler, qualified pairs only), float output, keep
  Android media volume at maximum (only after you set the knob to hardware), disable MediaTek sound
  enhancements.
- Knob detection: a media-volume change DiPlay did not make means the knob is Android's software
  volume; `isVolumeFixed` means hardware. Nothing else is inferred: ten quiet minutes used to
  promote the knob to hardware, which would have let the next session pin a software-volume unit to
  maximum, so that inference was removed in 24.38 (and an earlier automatic verdict is discarded).
  "Knob is hardware" / "Knob is software" buttons set it by hand.
- The readout shows hardware rate, track format, media volume, knob verdict, vendor readback and
  installed effects; the same text is in the diagnostic report.
- **Clean volume mode** (`CleanVolume`): for units whose firmware volume table distorts on every
  step but one (below the clean step its own loudness processing clips, above it the amplifier
  gain runs out of headroom). During a session the Android media volume is parked at the clean
  step and every knob step is folded into a digital volume at the end of DiPlay's chain
  (`EqualizerDsp.outputGain`, applied before the single dither, curve `(index/max)²`). A pill
  shows "Volume n / max" on the CarPlay and iPod screens; the loudness compensation follows the
  virtual level. When the last session ends the real index is restored, so the radio and other
  sources keep their behaviour. Needs a software knob; the clean step is a setting (default 20).

## Cost

Measured on the development machine's JVM with every stage on at once: about 400 ns per stereo
frame at 48 kHz (2 % of a desktop core). A typical preset is well under that. The limiter's
true-peak detector is the most expensive single stage under sustained limiting (about 60 ns per
stereo frame hot on the dev JVM with the 64-tap kernel, nothing on quiet programme). No head-unit
profile exists yet; the Cortex-A7 budget is 20.8 µs per frame and the recursive filters run in
scalar double precision there.

## Audit regressions

`DspAuditTest` keeps the October 2026 audit's reproductions as fixtures: the sign-burst limiter
overshoot, the final output after resampling, ramp identity across block sizes, limiter-toggle
continuity, bass square waves through the chain, the stereo anti-phase bound, the anti-phase
de-esser case, subnormal-free silence, and the resampler's qualified pairs.
