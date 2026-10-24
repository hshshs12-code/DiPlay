// SPDX-License-Identifier: GPL-3.0-only
import Foundation

/// One parametric band, as DiPlay stores it (gain and Q in tenths).
struct ParametricBand: Equatable, Identifiable {
    static let off = 0, peak = 1, lowShelf = 2, highShelf = 3

    var type = 0
    var hz = 1000
    var gainTenths = 0
    var qTenths = 10
    var id: Int { hz &* 31 &+ type &* 7 &+ gainTenths &* 3 &+ qTenths }

    init(type: Int = 0, hz: Int = 1000, gainTenths: Int = 0, qTenths: Int = 10) {
        self.type = type; self.hz = hz; self.gainTenths = gainTenths; self.qTenths = qTenths
    }

    init(json: [String: Any]) {
        type = json.int("type", 0); hz = json.int("hz", 1000); gainTenths = json.int("gainTenths", 0); qTenths = json.int("qTenths", 10)
    }

    var json: [String: Any] { ["type": type, "hz": hz, "gainTenths": gainTenths, "qTenths": qTenths] }

    var describe: String {
        switch type {
        case ParametricBand.off: return "Off"
        case ParametricBand.lowShelf: return "Low shelf \(hzText) \(gainText)"
        case ParametricBand.highShelf: return "High shelf \(hzText) \(gainText)"
        default: return "Peak \(hzText) \(gainText) Q \(String(format: "%.1f", Double(qTenths) / 10))"
        }
    }
    var hzText: String { hz >= 1000 ? String(format: "%g kHz", Double(hz) / 1000) : "\(hz) Hz" }
    var gainText: String { String(format: "%+.1f dB", Double(gainTenths) / 10) }
}

/// Mirror of DiPlay's AudioEffectSettings; keys match the head unit's JSON exactly.
struct SoundSettings: Equatable {
    var enabled = false
    var dspMode = false
    var presetName = ""
    var dspGainsDb: [Int] = Array(repeating: 0, count: 12)
    var bandLevelsMb: [Int] = []
    var bassStrength = 0
    var loudnessGainMb = 0
    var limiter = false
    var limiterOff = false
    var subsonicHz = 0
    var bassEnhance = 0
    var bassEnhanceHz = 100
    var bassClipDb = 0
    var driveDb = 0
    var outputTrimDb = 0
    var autoLevel = false
    var autoLevelAmount = 50
    var parametric: [ParametricBand] = []
    var stereoWidth = 100
    var balance = 0
    var driverSeatDelayTenthsMs = 0
    var driverSide = 0
    var crossfeed = 0
    var airDb = 0
    var airHz = 10000
    var deEss = 50
    var loudnessComp = false
    var loudnessCompAmount = 60
    var loudnessCompReferenceStep = 0

    init() {}

    init(json j: [String: Any]) {
        enabled = j.bool("enabled", false); dspMode = j.bool("dspMode", false); presetName = j["presetName"] as? String ?? ""
        dspGainsDb = j.ints("dspGainsDb", Array(repeating: 0, count: 12)); bandLevelsMb = j.ints("bandLevelsMb", [])
        bassStrength = j.int("bassStrength", 0); loudnessGainMb = j.int("loudnessGainMb", 0)
        limiter = j.bool("limiter", false); limiterOff = j.bool("limiterOff", false)
        subsonicHz = j.int("subsonicHz", 0); bassEnhance = j.int("bassEnhance", 0); bassEnhanceHz = j.int("bassEnhanceHz", 100); bassClipDb = j.int("bassClipDb", 0)
        driveDb = j.int("driveDb", 0); outputTrimDb = j.int("outputTrimDb", 0); autoLevel = j.bool("autoLevel", false); autoLevelAmount = j.int("autoLevelAmount", 50)
        parametric = (j["parametric"] as? [[String: Any]] ?? []).map(ParametricBand.init(json:))
        stereoWidth = j.int("stereoWidth", 100); balance = j.int("balance", 0); driverSeatDelayTenthsMs = j.int("driverSeatDelayTenthsMs", 0)
        driverSide = j.int("driverSide", 0); crossfeed = j.int("crossfeed", 0); airDb = j.int("airDb", 0); airHz = j.int("airHz", 10000); deEss = j.int("deEss", 50)
        loudnessComp = j.bool("loudnessComp", false); loudnessCompAmount = j.int("loudnessCompAmount", 60); loudnessCompReferenceStep = j.int("loudnessCompReferenceStep", 0)
    }

    var json: [String: Any] {
        [
            "enabled": enabled, "dspMode": dspMode, "presetName": presetName, "dspGainsDb": dspGainsDb, "bandLevelsMb": bandLevelsMb,
            "bassStrength": bassStrength, "loudnessGainMb": loudnessGainMb, "limiter": limiter, "limiterOff": limiterOff,
            "subsonicHz": subsonicHz, "bassEnhance": bassEnhance, "bassEnhanceHz": bassEnhanceHz, "bassClipDb": bassClipDb,
            "driveDb": driveDb, "outputTrimDb": outputTrimDb, "autoLevel": autoLevel, "autoLevelAmount": autoLevelAmount,
            "parametric": parametric.map { $0.json }, "stereoWidth": stereoWidth, "balance": balance,
            "driverSeatDelayTenthsMs": driverSeatDelayTenthsMs, "driverSide": driverSide, "crossfeed": crossfeed,
            "airDb": airDb, "airHz": airHz, "deEss": deEss, "loudnessComp": loudnessComp, "loudnessCompAmount": loudnessCompAmount,
            "loudnessCompReferenceStep": loudnessCompReferenceStep,
        ]
    }

    /// Which stages force the limiter on, as the head unit's own screen reports it.
    var limiterForcedBy: [String] {
        var reasons: [String] = []
        if driveDb > 0 { reasons.append("drive") }
        if !limiterOff {
            if bassEnhance > 0 { reasons.append("bass enhancer") }
            if autoLevel { reasons.append("auto level") }
            if loudnessComp { reasons.append("loudness") }
        }
        return reasons
    }
    var limiterEffective: Bool { !limiterOff && (limiter || !limiterForcedBy.isEmpty) || driveDb > 0 }
}

struct PresetInfo: Identifiable, Hashable {
    var name: String
    var description: String
    var id: String { name }
    var shortName: String { name.hasPrefix("Yaris · ") ? String(name.dropFirst("Yaris · ".count)) : name }
}

/// Ranges and choice lists the head unit publishes so the phone offers the same steps.
struct Meta {
    var bands = [31, 62, 125, 250, 500, 1000, 2000, 3000, 4000, 8000, 12000, 16000]
    var minDb = -12
    var maxDb = 12
    var maxDrive = 12
    var maxTrim = 12
    var bassClipDb = [0, 1, 2, 3, 4, 6, 9]
    var subsonicHz = [0, 20, 25, 30, 35, 40, 50, 60, 80, 100, 120]
    var enhancerHz = [60, 70, 80, 90, 100, 110, 120, 140, 160, 200]
    var airHz = [6000, 8000, 10000, 12000]
    var maxAirDb = 8
    var maxWidth = 200
    var maxBalance = 100
    var maxDelayTenths = 50
    var maxCrossfeed = 100
    var maxParametric = 5
    var parametricHz = [30, 40, 50, 60, 80, 100, 120, 160, 200, 250, 300, 400, 500, 600, 800, 1000, 1200, 1500, 2000, 2500, 3000, 4000, 5000, 6000, 8000, 10000, 12000, 16000]
    var parametricQTenths = [3, 5, 7, 10, 14, 20, 30, 40, 60]
    var presets: [PresetInfo] = []
    var eqPresets: [String] = []

    init() {}

    init(json j: [String: Any]) {
        bands = j.ints("bands", bands); minDb = j.int("minDb", minDb); maxDb = j.int("maxDb", maxDb)
        maxDrive = j.int("maxDrive", maxDrive); maxTrim = j.int("maxTrim", maxTrim)
        bassClipDb = j.ints("bassClipDb", bassClipDb); subsonicHz = j.ints("subsonicHz", subsonicHz); enhancerHz = j.ints("enhancerHz", enhancerHz)
        airHz = j.ints("airHz", airHz); maxAirDb = j.int("maxAirDb", maxAirDb); maxWidth = j.int("maxWidth", maxWidth); maxBalance = j.int("maxBalance", maxBalance)
        maxDelayTenths = j.int("maxDelayTenths", maxDelayTenths); maxCrossfeed = j.int("maxCrossfeed", maxCrossfeed); maxParametric = j.int("maxParametric", maxParametric)
        parametricHz = j.ints("parametricHz", parametricHz); parametricQTenths = j.ints("parametricQTenths", parametricQTenths)
        presets = (j["presets"] as? [[String: Any]] ?? []).map { PresetInfo(name: $0["name"] as? String ?? "", description: $0["description"] as? String ?? "") }
        eqPresets = j["eqPresets"] as? [String] ?? []
    }
}

struct VolumeInfo: Equatable {
    var index = -1
    var max = 0
    var dndAccess = false
    var muted = false
    var digital = 100
    var note = ""

    init() {}
    init(json j: [String: Any]) {
        index = j.int("index", -1); max = j.int("max", 0); dndAccess = j.bool("dndAccess", false); muted = j.bool("muted", false)
        digital = j.int("digital", 100); note = j["note"] as? String ?? ""
    }
}

/// One of the head unit's own session-0 engines, as the unit reports it.
struct UnitEngine: Equatable {
    var available = false
    var enabled = false
    var strength = 0      // bass boost / virtualizer, 0–1000
    var preset = 0        // reverb

    init() {}
    init(json j: [String: Any]) {
        available = j.bool("available", false); enabled = j.bool("enabled", false); strength = j.int("strength", 0); preset = j.int("preset", 0)
    }
}

struct UnitEq: Equatable {
    var available = false
    var enabled = false
    var hz: [Int] = []
    var mb: [Int] = []
    var minMb = -1500
    var maxMb = 1500
    var preset = ""

    init() {}
    init(json j: [String: Any]) {
        available = j.bool("available", false); enabled = j.bool("enabled", false); hz = j.ints("hz", []); mb = j.ints("mb", [])
        minMb = j.int("minMb", -1500); maxMb = j.int("maxMb", 1500); preset = j["preset"] as? String ?? ""
    }
}

/// The head unit's own effects: what they are set to, and whether DiPlay leaves them, holds them flat or drives them.
struct UnitFx: Equatable {
    static let modeOff = "off", modeNeutral = "neutral", modeDirect = "direct"
    static let reverbNames = ["None", "Small room", "Medium room", "Large room", "Medium hall", "Large hall", "Plate"]

    var mode = "off"
    var controlled = false
    var available = false
    var text = ""
    var eq = UnitEq()
    var bass = UnitEngine()
    var virt = UnitEngine()
    var reverb = UnitEngine()

    init() {}
    init(json j: [String: Any]) {
        mode = j["mode"] as? String ?? (j.bool("enabled", false) ? UnitFx.modeNeutral : UnitFx.modeOff)
        controlled = j.bool("controlled", j.bool("neutralised", false)); available = j.bool("available", true); text = j["text"] as? String ?? ""
        eq = UnitEq(json: j["eq"] as? [String: Any] ?? [:])
        bass = UnitEngine(json: j["bass"] as? [String: Any] ?? [:])
        virt = UnitEngine(json: j["virt"] as? [String: Any] ?? [:])
        reverb = UnitEngine(json: j["reverb"] as? [String: Any] ?? [:])
    }

    var direct: Bool { mode == UnitFx.modeDirect }

    /// The direct-mode target the phone sends, built from the current values.
    var targetJson: [String: Any] {
        ["eqEnabled": eq.enabled, "bandsMb": eq.mb, "bassEnabled": bass.enabled, "bassStrength": bass.strength,
         "virtEnabled": virt.enabled, "virtStrength": virt.strength, "reverbEnabled": reverb.enabled, "reverbPreset": reverb.preset]
    }
}

extension Dictionary where Key == String, Value == Any {
    func int(_ key: String, _ fallback: Int) -> Int {
        if let value = self[key] as? Int { return value }
        if let value = self[key] as? Double { return Int(value) }
        if let value = self[key] as? NSNumber { return value.intValue }
        return fallback
    }
    func bool(_ key: String, _ fallback: Bool) -> Bool {
        if let value = self[key] as? Bool { return value }
        if let value = self[key] as? NSNumber { return value.boolValue }
        return fallback
    }
    func ints(_ key: String, _ fallback: [Int]) -> [Int] {
        guard let array = self[key] as? [Any] else { return fallback }
        return array.compactMap { ($0 as? NSNumber)?.intValue }
    }
}

func hzLabel(_ hz: Int) -> String {
    if hz >= 1000 {
        let k = Double(hz) / 1000
        return k == k.rounded() ? "\(Int(k))k" : String(format: "%.1fk", k)
    }
    return "\(hz)"
}
