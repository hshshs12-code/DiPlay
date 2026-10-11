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
| Loudness compensation | `LoudnessCompensator` | ISO 226-derived low shelf (100 Hz) and high shelf (8 kHz) driven by the Android media-volume index, when the knob is the software volume. 200 ms ramps. |
| Air shelf | `AirShelf` | High shelf (6/8/10/12 kHz, 0–8 dB) crossfaded by a sibilance detector (5–9 kHz vs broadband). |
| Bass enhancer | `BassEnhancer` | Linkwitz-Riley 4th-order split with matching all-pass; peak envelope (25 ms hold, 60 ms release); Chebyshev T2/T3/T4 harmonics at unit amplitude so their level tracks the sub band (2:1 above −12 dBFS); harmonics band-limited 1.5×–6× the crossover (≤700 Hz); sub trim up to −6 dB at full amount; clip guard when the limiter is off. |
| Stereo stage | `StereoStage` | Mid/side width (side high-passed at 200 Hz when widening, so bass stays mono), constant-power balance, fractional driver-seat delay (Lagrange), Bauer crossfeed. 20 ms gain ramps, glided delay. |
| Auto level | `Leveler` | K-weighted (60 Hz HPF + 1.5 kHz +4 dB shelf) gated program leveler, −14 LUFS target, −50 LU absolute and −20 LU relative gates, momentary + long-term estimates for program-dependent release, 0.3 s hold, slew-limited, ±12 dB. |
| Limiter | `TruePeakLimiter` | 4× oversampled true-peak detection, 2 ms look-ahead, sliding-minimum with 20 ms hold, dual release (50 ms / 500 ms), Hann-like attack, stereo-linked, ceiling 0.985, hard clip at 0.999. Bass THD under limiting < 0.0001 %. |
| Dither | `EqualizerDsp` | TPDF ±1 LSB at the single 16-bit quantisation; float output skips it. |

All stages are skipped when neutral. Coefficient changes are handed to the audio thread atomically
and interpolated over ~30 ms; removed bands ramp out instead of clicking; filter memory is flushed
of subnormals. Output goes through `MediaOutputStage` (float, hardware rate via the 32-tap Kaiser
windowed-sinc `SincResampler`, flat to 20 kHz) so Android's mixer neither resamples nor
requantises.

## Presets (Equalizer → tiles)

Tuned for a 2018 Toyota Yaris with stock 6.5" door speakers and dash tweeters. All share the cabin
correction: −3 dB at 170 Hz (door-panel boom), −2 dB at 3.2 kHz (tweeter edge), +2 dB high shelf
at 9 kHz.

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
| Max Loud | as loud as safe | 50 % | auto level 100 % |

"More equalizer curves…" holds the generic band-only curves (Flat, Sound Good, Bass Boost, …).

## Controls

Every value is a big −/+ stepper; groups collapse.

- **Bands**: drag columns; Flatten, all −1 dB, all +1 dB.
- **Bass**: enhancer amount, enhancer crossover (the line below which harmonics are generated and
  the sub is trimmed; nothing is removed by it), subsonic filter (the only stage that removes
  frequencies outright), device bass boost.
- **Loudness and dynamics**: auto level + strength, limiter (forced on while auto level or
  loudness compensation is on), loudness compensation + amount + reference volume step, device
  loudness.
- **Tone**: air dB, air corner, de-ess.
- **Stereo**: width, balance, driver-seat delay, driver side, crossfeed.
- **Parametric EQ**: tap a band to cycle Off / Peak / Low shelf / High shelf; frequency, gain, Q.

## Audio path (Settings → Display → Audio path)

- Output at the hardware sample rate (DiPlay resampler), float output, keep Android media volume
  at maximum (only once the knob is known to be hardware), disable MediaTek sound enhancements.
- Knob detection: a media-volume change DiPlay did not make means the knob is Android's software
  volume; `isVolumeFixed` or ten minutes of unchanged volume means hardware. Override buttons.
- The readout shows hardware rate, track format, media volume, knob verdict, vendor readback and
  installed effects; the same text is in the diagnostic report.

## Cost

Measured on the development machine's JVM with every stage on at once: about 365 ns per stereo
frame at 48 kHz (1.8 % of a desktop core). A typical preset is well under that. The limiter's
true-peak detector is the most expensive single stage under sustained limiting.
