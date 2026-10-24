// SPDX-License-Identifier: GPL-3.0-only
// DiPlay Sound: controls the DiPlay head unit's sound processing from the iPhone over the
// iAP2 External Accessory session DiPlay opens in iPod mode.
import SwiftUI

@main
struct DiPlaySoundApp: App {
    @StateObject private var store = SoundStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .preferredColorScheme(.dark)
        }
    }
}
