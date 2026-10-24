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

    let link = AccessoryLink()

    private var sendWork: DispatchWorkItem?
    private var suppressIncomingUntil = Date.distantPast
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
            }
            .store(in: &cancellables)
        link.start()
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

    func setNeutralise(_ on: Bool) {
        unitFx.enabled = on
        link.send(["t": "neutralise", "on": on])
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
            if let v = object["volume"] as? [String: Any] { volume = VolumeInfo(index: v.int("index", -1), max: v.int("max", 0)) }
            if let u = object["unitFx"] as? [String: Any] {
                unitFx = UnitFx(text: u["text"] as? String ?? "", neutralised: u.bool("neutralised", false), enabled: u.bool("enabled", false))
            }
            unitName = object["unit"] as? String ?? ""
            hasState = true
        case "error":
            lastError = object["message"] as? String
        default:
            break
        }
    }
}
