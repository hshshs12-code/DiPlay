// SPDX-License-Identifier: GPL-3.0-only
import Foundation
import AVFoundation
import MediaPlayer
import UIKit

/// Turns the phone's volume buttons into head-unit volume steps. iOS offers no button API, so
/// this watches the system output volume: a press moves it one sixteenth, the direction says up
/// or down, and the level is put back to a fixed reference so the next press registers too. The
/// reference sits one step below full scale, which costs about half a decibel of the phone's own
/// output: nothing the unit's processing cannot make up. When the route's volume is fixed by the
/// accessory (iOS says so with a greyed slider), no change is ever observed and nothing happens.
final class VolumeButtonWatcher: NSObject, ObservableObject {
    static let reference: Float = 15.0 / 16.0

    @Published private(set) var active = false
    @Published private(set) var presses = 0
    @Published private(set) var lastEvent = ""

    var onStep: ((Int) -> Void)?

    private var observation: NSKeyValueObservation?
    private var ignoreUntil = Date.distantPast
    private let slider: UISlider? = {
        let view = MPVolumeView(frame: CGRect(x: -1000, y: -1000, width: 10, height: 10))
        view.isHidden = false
        view.alpha = 0.01
        return view.subviews.compactMap { $0 as? UISlider }.first
    }()

    func start() {
        guard !active else { return }
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setActive(true)
        } catch {
            lastEvent = "audio session: \(error.localizedDescription)"
            return
        }
        observation = session.observe(\.outputVolume, options: [.new]) { [weak self] _, change in
            guard let self = self, let value = change.newValue else { return }
            DispatchQueue.main.async { self.observed(value) }
        }
        active = true
        lastEvent = "watching (system volume \(Int(session.outputVolume * 100)) %)"
        reset()
    }

    func stop() {
        observation = nil
        active = false
        lastEvent = "off"
    }

    private func observed(_ value: Float) {
        guard active, Date() >= ignoreUntil else { return }
        let reference = Self.reference
        if abs(value - reference) < 0.01 { return }
        let step = value > reference ? 1 : -1
        presses += 1
        lastEvent = "\(step > 0 ? "up" : "down") (\(presses))"
        onStep?(step)
        reset()
    }

    private func reset() {
        ignoreUntil = Date().addingTimeInterval(0.25)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
            self?.slider?.setValue(Self.reference, animated: false)
            self?.slider?.sendActions(for: .touchUpInside)
        }
    }
}
