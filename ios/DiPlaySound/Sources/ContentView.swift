// SPDX-License-Identifier: GPL-3.0-only
import SwiftUI

struct ContentView: View {
    @EnvironmentObject var store: SoundStore

    var body: some View {
        TabView {
            SoundTab().tabItem { Label("Sound", systemImage: "slider.horizontal.3") }
            EqualizerTab().tabItem { Label("EQ", systemImage: "waveform.path.ecg") }
            StagesTab().tabItem { Label("Stages", systemImage: "switch.2") }
            StereoTab().tabItem { Label("Stereo", systemImage: "speaker.wave.2") }
            UnitTab().tabItem { Label("Unit", systemImage: "car") }
        }
        .alert("The head unit said", isPresented: Binding(get: { store.lastError != nil }, set: { if !$0 { store.lastError = nil } })) {
            Button("OK") { store.lastError = nil }
        } message: {
            Text(store.lastError ?? "")
        }
    }
}

// MARK: - Sound: presets, the three quick controls, the two switches

struct SoundTab: View {
    @EnvironmentObject var store: SoundStore
    @State private var showSave = false
    @State private var newName = ""

    private let columns = [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    ConnectionBanner()
                    SectionCard(title: "Presets", subtitle: store.settings.presetName.isEmpty ? "Custom (a slider was moved since the last preset)" : "Current: \(store.settings.presetName)") {
                        if !store.userPresets.isEmpty {
                            Text("Yours").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                            LazyVGrid(columns: columns, spacing: 10) {
                                ForEach(store.userPresets, id: \.self) { name in
                                    presetTile(name: name, selected: store.settings.presetName == name) { store.applyPreset(name) }
                                        .contextMenu { Button(role: .destructive) { store.deletePreset(name) } label: { Label("Delete \(name)", systemImage: "trash") } }
                                }
                            }
                        }
                        Text("Built in").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                        LazyVGrid(columns: columns, spacing: 10) {
                            ForEach(store.meta.presets) { preset in
                                presetTile(name: preset.shortName, selected: store.settings.presetName == preset.name) { store.applyPreset(preset.name) }
                            }
                        }
                        Button {
                            newName = store.userPresets.contains(store.settings.presetName) ? store.settings.presetName : ""
                            showSave = true
                        } label: {
                            Label("Save the current sound as a preset", systemImage: "square.and.arrow.down")
                                .frame(maxWidth: .infinity).padding(.vertical, 12)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(!store.hasState)
                    }
                    SectionCard(title: "Quick controls") {
                        StepRow(title: "Bass character", values: Array(stride(from: 0, through: 100, by: 5)), value: store.settings.bassEnhance,
                                label: { $0 == 0 ? "Off" : "\($0) %" }, onChange: { v in store.edit { $0.bassEnhance = v } },
                                detail: "Adds upper-bass harmonics the door speakers can play")
                        StepRow(title: "Drive", values: Array(0...store.meta.maxDrive), value: store.settings.driveDb,
                                label: { $0 == 0 ? "Off" : "+\($0) dB" }, onChange: { v in store.edit { $0.driveDb = v } },
                                detail: "Louder without clipping: gain in front of the bass clip and limiter")
                        StepRow(title: "Auto level", values: Array(stride(from: 0, through: 100, by: 10)), value: store.settings.autoLevel ? store.settings.autoLevelAmount : 0,
                                label: { $0 == 0 ? "Off" : "\($0) %" }, onChange: { v in store.edit { $0.autoLevel = v > 0; if v > 0 { $0.autoLevelAmount = v } } },
                                detail: "Keeps songs and podcasts at a steady level")
                    }
                    SectionCard(title: "Protection") {
                        ToggleRow(title: "Limiter", detail: limiterDetail,
                                  isOn: Binding(get: { store.settings.limiterEffective }, set: { on in store.edit { $0.limiter = on; $0.limiterOff = !on } }))
                        ToggleRow(title: "Bass clip", detail: store.settings.bassClipDb > 0 ? "Holds the band below 120 Hz under −\(store.settings.bassClipDb) dB by soft clipping, so an 808 never makes the limiter duck the vocals." : "Off: bass can make the limiter duck the rest of the mix.",
                                  isOn: Binding(get: { store.settings.bassClipDb > 0 }, set: { on in store.edit { $0.bassClipDb = on ? 3 : 0 } }))
                        ToggleRow(title: "DiPlay sound processing", detail: store.settings.dspMode ? "DiPlay's own chain is on (12 bands, stages, limiter)." : "Off: only the head unit's own equalizer runs.",
                                  isOn: Binding(get: { store.settings.dspMode }, set: { on in store.edit { $0.dspMode = on; if on { $0.enabled = true } } }))
                    }
                }
                .padding(16)
            }
            .navigationTitle("DiPlay Sound")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button { store.refresh() } label: { Image(systemName: "arrow.clockwise") }.disabled(!store.connected) } }
            .alert("Save preset", isPresented: $showSave) {
                TextField("Name", text: $newName)
                Button("Save") { store.savePreset(newName) }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Keeps every slider and switch as they are now, on the head unit. The driver side and output trim follow the car.")
            }
        }
    }

    private var limiterDetail: String {
        let forced = store.settings.limiterForcedBy
        if store.settings.limiterOff && store.settings.driveDb > 0 { return "On: drive needs it." }
        if store.settings.limiterOff { return "OFF by your choice: peaks clip at full scale." }
        if !forced.isEmpty { return "On (needed by \(forced.joined(separator: ", "))). True-peak limiter in front of the DAC." }
        return store.settings.limiter ? "On: true-peak limiter in front of the DAC." : "Off: no limiter; peaks clip at full scale."
    }

    private func presetTile(name: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Text(name).font(.headline).multilineTextAlignment(.center).lineLimit(2).minimumScaleFactor(0.8)
                if selected { Text("✓ Selected").font(.caption2) }
            }
            .frame(maxWidth: .infinity, minHeight: 72)
            .padding(8)
            .background(selected ? Color.white : Color.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 14))
            .foregroundStyle(selected ? Color.black : Color.white)
        }
        .buttonStyle(.plain)
        .disabled(!store.hasState)
    }
}

// MARK: - EQ: the 12 graphic bands

struct EqualizerTab: View {
    @EnvironmentObject var store: SoundStore

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    ConnectionBanner()
                    SectionCard(title: "12-band equalizer", subtitle: store.settings.dspMode ? "Constant-Q bands, ±\(store.meta.maxDb) dB. Changes apply as you drag." : "Turn on DiPlay sound processing (Sound tab) to use these bands.") {
                        HStack(alignment: .bottom, spacing: 6) {
                            ForEach(Array(store.meta.bands.enumerated()), id: \.offset) { index, hz in
                                VStack(spacing: 6) {
                                    Text(gainText(index)).font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                                    VerticalSlider(value: Binding(get: { store.settings.dspGainsDb.indices.contains(index) ? store.settings.dspGainsDb[index] : 0 },
                                                                  set: { v in store.edit { s in
                                                                      if s.dspGainsDb.count < store.meta.bands.count { s.dspGainsDb += Array(repeating: 0, count: store.meta.bands.count - s.dspGainsDb.count) }
                                                                      s.dspGainsDb[index] = v } }),
                                                   range: store.meta.minDb...store.meta.maxDb)
                                        .frame(height: 220)
                                    Text(hzLabel(hz)).font(.caption2).foregroundStyle(.secondary)
                                }
                            }
                        }
                        .disabled(!store.settings.dspMode)
                        .opacity(store.settings.dspMode ? 1 : 0.4)
                        HStack {
                            Button("Flat") { store.edit { $0.dspGainsDb = Array(repeating: 0, count: store.meta.bands.count) } }
                                .buttonStyle(.bordered)
                            Spacer()
                            Menu {
                                ForEach(store.meta.eqPresets, id: \.self) { name in Button(name) { store.applyEqPreset(name) } }
                            } label: {
                                Label("Equalizer curves", systemImage: "list.bullet")
                            }
                            .buttonStyle(.bordered)
                        }
                        .disabled(!store.hasState)
                    }
                    if !store.settings.dspMode {
                        SectionCard(title: "Head-unit equalizer path", subtitle: "Used while DiPlay's own processing is off.") {
                            StepRow(title: "Bass boost (device effect)", values: Array(stride(from: 0, through: 100, by: 10)), value: store.settings.bassStrength / 10,
                                    label: { $0 == 0 ? "Off" : "\($0) %" }, onChange: { v in store.edit { $0.bassStrength = v * 10 } })
                            StepRow(title: "Loudness boost (device effect)", values: Array(0...20), value: store.settings.loudnessGainMb / 100,
                                    label: { $0 == 0 ? "Off" : "+\($0) dB" }, onChange: { v in store.edit { $0.loudnessGainMb = v * 100 } })
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle("Equalizer")
        }
    }

    private func gainText(_ index: Int) -> String {
        let v = store.settings.dspGainsDb.indices.contains(index) ? store.settings.dspGainsDb[index] : 0
        return v > 0 ? "+\(v)" : "\(v)"
    }
}

// MARK: - Stages: every switch and amount in the chain

struct StagesTab: View {
    @EnvironmentObject var store: SoundStore

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    ConnectionBanner()
                    SectionCard(title: "Limiter and clip", subtitle: "What stops the output clipping.") {
                        ToggleRow(title: "Limiter", detail: "True-peak look-ahead limiter in front of the DAC. Off means no limiter at all; drive turns it back on.",
                                  isOn: Binding(get: { store.settings.limiterEffective }, set: { on in store.edit { $0.limiter = on; $0.limiterOff = !on } }))
                        StepRow(title: "Bass clip", values: store.meta.bassClipDb, value: store.settings.bassClipDb,
                                label: { $0 == 0 ? "Off" : "−\($0) dB" }, onChange: { v in store.edit { $0.bassClipDb = v } },
                                detail: "Soft clip of the band below 120 Hz, so bass bounds itself instead of ducking the mix")
                        StepRow(title: "Drive", values: Array(0...store.meta.maxDrive), value: store.settings.driveDb,
                                label: { $0 == 0 ? "Off" : "+\($0) dB" }, onChange: { v in store.edit { $0.driveDb = v } },
                                detail: "Gain into the bass clip and limiter; keeps the limiter on")
                        StepRow(title: "Output trim", values: Array(0...store.meta.maxTrim), value: store.settings.outputTrimDb,
                                label: { $0 == 0 ? "0 dB" : "−\($0) dB" }, onChange: { v in store.edit { $0.outputTrimDb = v } },
                                detail: "Attenuation after the limiter, for a unit that distorts at full scale")
                    }
                    SectionCard(title: "Level", subtitle: "Volume-dependent and programme-dependent stages.") {
                        ToggleRow(title: "Auto level", detail: "Evens out quiet podcasts and loud tracks toward a fixed target. Keeps the limiter on.",
                                  isOn: Binding(get: { store.settings.autoLevel }, set: { on in store.edit { $0.autoLevel = on } }))
                        StepRow(title: "Auto level strength", values: Array(stride(from: 10, through: 100, by: 10)), value: store.settings.autoLevelAmount,
                                label: { "\($0) %" }, onChange: { v in store.edit { $0.autoLevelAmount = v } })
                        ToggleRow(title: "Loudness compensation", detail: "Fuller bass and sparkle at low volume, following the head unit's media volume.",
                                  isOn: Binding(get: { store.settings.loudnessComp }, set: { on in store.edit { $0.loudnessComp = on } }))
                        StepRow(title: "Loudness amount", values: Array(stride(from: 10, through: 100, by: 10)), value: store.settings.loudnessCompAmount,
                                label: { "\($0) %" }, onChange: { v in store.edit { $0.loudnessCompAmount = v } })
                        StepRow(title: "Reference volume step", values: Array(0...max(store.volume.max, 1)), value: store.settings.loudnessCompReferenceStep,
                                label: { $0 == 0 ? "Auto" : "\($0)" }, onChange: { v in store.edit { $0.loudnessCompReferenceStep = v } },
                                detail: "The volume step that counts as 'loud enough': no compensation above it")
                    }
                    SectionCard(title: "Bass", subtitle: "What happens below 200 Hz.") {
                        StepRow(title: "Subsonic filter", values: store.meta.subsonicHz, value: store.settings.subsonicHz,
                                label: { $0 == 0 ? "Off" : "\($0) Hz" }, onChange: { v in store.edit { $0.subsonicHz = v } },
                                detail: "−6 dB at the corner, 24 dB/octave below: removes what the doors cannot play")
                        StepRow(title: "Bass enhancer", values: Array(stride(from: 0, through: 100, by: 10)), value: store.settings.bassEnhance,
                                label: { $0 == 0 ? "Off" : "\($0) %" }, onChange: { v in store.edit { $0.bassEnhance = v } },
                                detail: "Psychoacoustic harmonics of the sub band; keeps the limiter on")
                        StepRow(title: "Enhancer crossover", values: store.meta.enhancerHz, value: store.settings.bassEnhanceHz,
                                label: { "below \($0) Hz" }, onChange: { v in store.edit { $0.bassEnhanceHz = v } })
                    }
                    SectionCard(title: "Treble", subtitle: "Air shelf with de-essing.") {
                        StepRow(title: "Air (high shelf)", values: Array(0...store.meta.maxAirDb), value: store.settings.airDb,
                                label: { $0 == 0 ? "Off" : "+\($0) dB" }, onChange: { v in store.edit { $0.airDb = v } })
                        StepRow(title: "Air corner", values: store.meta.airHz, value: store.settings.airHz,
                                label: { "above \($0 / 1000) kHz" }, onChange: { v in store.edit { $0.airHz = v } })
                        StepRow(title: "De-ess", values: Array(stride(from: 0, through: 100, by: 10)), value: store.settings.deEss,
                                label: { $0 == 0 ? "Off" : "\($0) %" }, onChange: { v in store.edit { $0.deEss = v } },
                                detail: "Pulls the shelf back on sibilant vocals")
                    }
                }
                .padding(16)
            }
            .navigationTitle("Stages")
        }
    }
}

// MARK: - Stereo and parametric

struct StereoTab: View {
    @EnvironmentObject var store: SoundStore

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    ConnectionBanner()
                    SectionCard(title: "Stereo stage", subtitle: "Width, balance, driver-seat alignment.") {
                        StepRow(title: "Width", values: Array(stride(from: 0, through: store.meta.maxWidth, by: 10)), value: store.settings.stereoWidth,
                                label: { $0 == 100 ? "Normal" : "\($0) %" }, onChange: { v in store.edit { $0.stereoWidth = v } })
                        StepRow(title: "Balance", values: Array(stride(from: -store.meta.maxBalance, through: store.meta.maxBalance, by: 10)), value: store.settings.balance,
                                label: { $0 == 0 ? "Centre" : ($0 < 0 ? "L \(-$0)" : "R \($0)") }, onChange: { v in store.edit { $0.balance = v } })
                        StepRow(title: "Driver-seat delay", values: Array(stride(from: 0, through: store.meta.maxDelayTenths, by: 2)), value: store.settings.driverSeatDelayTenthsMs,
                                label: { $0 == 0 ? "Off" : String(format: "%.1f ms", Double($0) / 10) }, onChange: { v in store.edit { $0.driverSeatDelayTenthsMs = v } },
                                detail: "Delays the near speaker so both arrive together at the driver")
                        ChoiceRow(title: "Driver sits on the", options: [(0, "Left"), (1, "Right")], selection: store.settings.driverSide) { v in store.edit { $0.driverSide = v } }
                        StepRow(title: "Crossfeed", values: Array(stride(from: 0, through: store.meta.maxCrossfeed, by: 10)), value: store.settings.crossfeed,
                                label: { $0 == 0 ? "Off" : "\($0) %" }, onChange: { v in store.edit { $0.crossfeed = v } })
                    }
                    SectionCard(title: "Parametric bands", subtitle: "Up to \(store.meta.maxParametric) peaks or shelves on top of the graphic bands.") {
                        ForEach(Array(store.settings.parametric.enumerated()), id: \.offset) { index, band in
                            VStack(alignment: .leading, spacing: 10) {
                                HStack {
                                    Text("Band \(index + 1): \(band.describe)").font(.subheadline.weight(.semibold))
                                    Spacer()
                                    Button(role: .destructive) { store.edit { $0.parametric.remove(at: index) } } label: { Image(systemName: "trash") }
                                }
                                ChoiceRow(title: "Type", options: [(0, "Off"), (1, "Peak"), (2, "Low shelf"), (3, "High shelf")], selection: band.type) { v in store.edit { $0.parametric[index].type = v } }
                                StepRow(title: "Frequency", values: store.meta.parametricHz, value: band.hz, label: { hzLabel($0) + ($0 >= 1000 ? "Hz" : " Hz") }, onChange: { v in store.edit { $0.parametric[index].hz = v } })
                                StepRow(title: "Gain", values: Array(-12...12), value: band.gainTenths / 10, label: { ($0 > 0 ? "+" : "") + "\($0) dB" }, onChange: { v in store.edit { $0.parametric[index].gainTenths = v * 10 } })
                                if band.type == ParametricBand.peak {
                                    StepRow(title: "Width (Q)", values: store.meta.parametricQTenths, value: band.qTenths, label: { String(format: "Q %.1f", Double($0) / 10) }, onChange: { v in store.edit { $0.parametric[index].qTenths = v } })
                                }
                            }
                            .padding(12)
                            .background(Color.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 12))
                        }
                        if store.settings.parametric.count < store.meta.maxParametric {
                            Button { store.edit { $0.parametric.append(ParametricBand(type: ParametricBand.peak, hz: 1000, gainTenths: 0, qTenths: 10)) } } label: {
                                Label("Add a band", systemImage: "plus").frame(maxWidth: .infinity).padding(.vertical, 10)
                            }
                            .buttonStyle(.bordered)
                            .disabled(!store.hasState)
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle("Stereo")
        }
    }
}

// MARK: - Unit: connection, the head unit's own effects, media volume, log

struct UnitTab: View {
    @EnvironmentObject var store: SoundStore
    @State private var volumeDraft: Double = 0
    @State private var dragging = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    ConnectionBanner()
                    SectionCard(title: store.connected ? "Connected" : "Connection", subtitle: store.connected ? store.link.accessoryDetail : "Over the USB cable, through the accessory session DiPlay opens in iPod mode.") {
                        if !store.unitName.isEmpty { Text(store.unitName).font(.body.weight(.semibold)) }
                        if let at = store.lastMessageAt { Text("Last message \(at.formatted(date: .omitted, time: .standard))").font(.caption).foregroundStyle(.secondary) }
                        Button { store.refresh() } label: { Label("Reload from the unit", systemImage: "arrow.clockwise").frame(maxWidth: .infinity).padding(.vertical, 10) }
                            .buttonStyle(.bordered).disabled(!store.connected)
                    }
                    SectionCard(title: "Head unit's own effects", subtitle: "What the unit's Sound Effects app applies to everything, after DiPlay and before the amplifier.") {
                        Text(store.unitFx.text.isEmpty ? "—" : store.unitFx.text).font(.footnote).foregroundStyle(.secondary)
                        ToggleRow(title: "Neutralise head-unit effects",
                                  detail: store.unitFx.neutralised ? "DiPlay holds them flat and off while it runs." : "Takes control of the unit's equalizer, bass boost, virtualizer and reverb and holds them flat.",
                                  isOn: Binding(get: { store.unitFx.enabled }, set: { store.setNeutralise($0) }))
                            .disabled(!store.hasState)
                    }
                    SectionCard(title: "Head-unit media volume", subtitle: store.volume.max > 0 ? "Android's media volume on the unit, \(store.volume.index) of \(store.volume.max)." : "Not reported yet.") {
                        if store.volume.max > 0 {
                            Slider(value: $volumeDraft, in: 0...Double(store.volume.max), step: 1) { editing in
                                dragging = editing
                                if !editing { store.setVolume(Int(volumeDraft.rounded())) }
                            }
                            .onAppear { volumeDraft = Double(max(0, store.volume.index)) }
                            .onChange(of: store.volume.index) { _, index in if !dragging { volumeDraft = Double(max(0, index)) } }
                            HStack {
                                Button { store.setVolume(max(0, store.volume.index - 1)) } label: { Image(systemName: "speaker.minus").frame(width: 60, height: 44) }
                                Spacer()
                                Text("\(Int(volumeDraft.rounded()))").font(.title2.monospacedDigit().weight(.semibold))
                                Spacer()
                                Button { store.setVolume(min(store.volume.max, store.volume.index + 1)) } label: { Image(systemName: "speaker.plus").frame(width: 60, height: 44) }
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                    SectionCard(title: "Your presets", subtitle: store.userPresets.isEmpty ? "None saved yet. Use the Sound tab to save one." : "Saved on the head unit. Swipe left on the car screen's list, or delete here.") {
                        ForEach(store.userPresets, id: \.self) { name in
                            HStack {
                                Text(name).font(.body.weight(.semibold))
                                Spacer()
                                Button("Apply") { store.applyPreset(name) }.buttonStyle(.bordered)
                                Button(role: .destructive) { store.deletePreset(name) } label: { Image(systemName: "trash") }.buttonStyle(.bordered)
                            }
                        }
                    }
                    SectionCard(title: "Link log") {
                        ForEach(Array(store.link.log.suffix(30).reversed().enumerated()), id: \.offset) { _, line in
                            Text(line).font(.caption2.monospaced()).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle("Head unit")
        }
    }
}
