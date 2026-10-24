// SPDX-License-Identifier: GPL-3.0-only
import Foundation
import Combine

/// The phone's copy of the head unit's sound state, and the commands that change it.
/// Edits apply optimistically here and go out as one `set` after a short pause; the head unit
/// answers every change (its own or ours) with a full `state`, which wins once our edits settle.
@MainActor
final class SoundStore: ObservableObject {
    @Published var settings = SoundSettings()
    @Published var meta = Meta()
    @Published var userPresets: [String] = []
    @Published var volume = VolumeInfo()
    @Published var unitFx = UnitFx()
    @Published var unitName = ""
    @Published var hasState = false
    @Published var lastError: String?
    @Published var lastMessageAt: Date?

    /// Phone volume buttons step the head unit's volume while connected (Unit tab switch).
    @Published var volumeButtons: Bool = UserDefaults.standard.bool(forKey: "volumeButtons") {
        didSet { UserDefaults.standard.set(volumeButtons, forKey: "volumeButtons"); updateVolumeWatcher() }
    }

    let link = AccessoryLink()
    let buttons = VolumeButtonWatcher()

    private var sendWork: DispatchWorkItem?
    private var unitFxWork: DispatchWorkItem?
    private var suppressIncomingUntil = Date.distantPast
    private var suppressUnitFxUntil = Date.distantPast
    private var cancellables = Set<AnyCancellable>()

    init() {
        link.onLine = { [weak self] line in
            Task { @MainActor in self?.handle(line: line) }
        }
        link.$connected
            .receive(on: DispatchQueue.main)
            .sink { [weak self] connected in
                guard let self = self else { return }
                if connected { self.hello() } else { self.hasState = false }
                self.updateVolumeWatcher()
            }
            .store(in: &cancellables)
        buttons.onStep = { [weak self] step in self?.adjustVolume(step) }
        buttons.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }.store(in: &cancellables)
        link.start()
        updateVolumeWatcher()
    }

    private func updateVolumeWatcher() {
        if volumeButtons && link.connected { buttons.start() } else { buttons.stop() }
    }

    // MARK: head-unit volume

    func adjustVolume(_ delta: Int) {
        link.send(["t": "volume", "delta": delta])
    }

    func setDigitalVolume(_ percent: Int) {
        volume.digital = percent
        link.send(["t": "digitalVolume", "percent": percent])
    }

    // MARK: the head unit's own effects

    func setUnitFxMode(_ mode: String) {
        unitFx.mode = mode
        link.send(["t": "unitFxMode", "mode": mode])
    }

    /// Edits the unit's own engines (direct mode): applied here at once, sent after a short pause.
    func editUnitFx(_ change: (inout UnitFx) -> Void) {
        change(&unitFx)
        unitFxWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self.unitFxWork = nil
            self.suppressUnitFxUntil = Date().addingTimeInterval(0.8)
            var message: [String: Any] = ["t": "unitFxSet"]
            for (key, value) in self.unitFx.targetJson { message[key] = value }
            self.link.send(message)
        }
        unitFxWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.12, execute: work)
    }

    var connected: Bool { link.connected }

    func hello() { link.send(["t": "hello", "app": "DiPlay Sound iOS", "v": 1]) }
    func refresh() { link.send(["t": "get"]) }

    /// Changes one or more settings by hand: the preset name clears, like on the head unit.
    func edit(_ change: (inout SoundSettings) -> Void) {
        change(&settings)
        settings.presetName = ""
        scheduleSend()
    }

    func applyPreset(_ name: String) {
        settings.presetName = name
        suppressIncomingUntil = .distantPast
        link.send(["t": "preset", "name": name])
    }

    func applyEqPreset(_ name: String) {
        suppressIncomingUntil = .distantPast
        link.send(["t": "eqPreset", "name": name])
    }

    func savePreset(_ name: String) {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        flushPendingEdit()
        settings.presetName = clean
        link.send(["t": "savePreset", "name": clean])
    }

    func deletePreset(_ name: String) {
        userPresets.removeAll { $0 == name }
        link.send(["t": "deletePreset", "name": name])
    }

    func setVolume(_ index: Int) {
        volume.index = index
        link.send(["t": "volume", "index": index])
    }

    private func scheduleSend() {
        sendWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.sendSettings() }
        sendWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.12, execute: work)
    }

    private func flushPendingEdit() {
        if let work = sendWork, !work.isCancelled {
            work.cancel()
            sendSettings()
        }
    }

    private func sendSettings() {
        sendWork = nil
        suppressIncomingUntil = Date().addingTimeInterval(0.8)
        link.send(["t": "set", "s": settings.json])
    }

    private func handle(line: String) {
        guard let data = line.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
        lastMessageAt = Date()
        switch object["t"] as? String {
        case "state":
            if let s = object["s"] as? [String: Any] {
                let incoming = SoundSettings(json: s)
                if Date() >= suppressIncomingUntil || !hasState { settings = incoming }
            }
            if let m = object["meta"] as? [String: Any] { meta = Meta(json: m) }
            userPresets = object["userPresets"] as? [String] ?? []
            if let v = object["volume"] as? [String: Any] { volume = VolumeInfo(json: v) }
            if let u = object["unitFx"] as? [String: Any], Date() >= suppressUnitFxUntil || !hasState { unitFx = UnitFx(json: u) }
            unitName = object["unit"] as? String ?? ""
            hasState = true
        case "error":
            lastError = object["message"] as? String
        default:
            break
        }
    }
}
