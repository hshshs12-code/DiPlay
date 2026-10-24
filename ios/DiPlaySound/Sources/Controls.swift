// SPDX-License-Identifier: GPL-3.0-only
import SwiftUI

/// A titled switch with a one-line explanation, like the head unit's own rows.
struct ToggleRow: View {
    let title: String
    let detail: String
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.body.weight(.semibold))
                if !detail.isEmpty { Text(detail).font(.caption).foregroundStyle(.secondary) }
            }
        }
        .tint(.accentColor)
    }
}

/// Steps through a fixed list of allowed values with − / + buttons, showing the current one.
struct StepRow: View {
    let title: String
    let values: [Int]
    let value: Int
    let label: (Int) -> String
    let onChange: (Int) -> Void
    var detail: String = ""

    private var index: Int {
        guard !values.isEmpty else { return 0 }
        return values.enumerated().min(by: { abs($0.element - value) < abs($1.element - value) })?.offset ?? 0
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body.weight(.semibold))
                    if !detail.isEmpty { Text(detail).font(.caption).foregroundStyle(.secondary) }
                }
                Spacer()
                HStack(spacing: 0) {
                    stepButton("minus", enabled: index > 0) { onChange(values[index - 1]) }
                    Text(label(values.isEmpty ? value : values[index]))
                        .font(.body.monospacedDigit().weight(.medium))
                        .frame(minWidth: 72)
                        .multilineTextAlignment(.center)
                    stepButton("plus", enabled: index < values.count - 1) { onChange(values[index + 1]) }
                }
            }
        }
    }

    private func stepButton(_ system: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: system)
                .font(.body.weight(.bold))
                .frame(width: 44, height: 44)
                .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.35)
    }
}

/// A menu of named choices.
struct ChoiceRow<T: Hashable>: View {
    let title: String
    let options: [(T, String)]
    let selection: T
    let onChange: (T) -> Void

    var body: some View {
        HStack {
            Text(title).font(.body.weight(.semibold))
            Spacer()
            Menu {
                ForEach(options, id: \.0) { option in
                    Button { onChange(option.0) } label: {
                        if option.0 == selection { Label(option.1, systemImage: "checkmark") } else { Text(option.1) }
                    }
                }
            } label: {
                HStack(spacing: 4) {
                    Text(options.first(where: { $0.0 == selection })?.1 ?? "—")
                    Image(systemName: "chevron.up.chevron.down").font(.caption)
                }
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
            }
        }
    }
}

/// One equalizer fader: drag or tap anywhere on the track; the centre line is 0 dB.
struct VerticalSlider: View {
    @Binding var value: Int
    let range: ClosedRange<Int>
    var onCommit: (() -> Void)? = nil

    private var fraction: CGFloat {
        let span = CGFloat(range.upperBound - range.lowerBound)
        return span == 0 ? 0 : CGFloat(value - range.lowerBound) / span
    }

    var body: some View {
        GeometryReader { geo in
            let height = geo.size.height
            let zero = CGFloat(0 - range.lowerBound) / CGFloat(max(1, range.upperBound - range.lowerBound))
            ZStack(alignment: .bottom) {
                RoundedRectangle(cornerRadius: 6).fill(Color.white.opacity(0.08))
                Rectangle().fill(Color.white.opacity(0.25)).frame(height: 1).offset(y: -(height * zero))
                let top = height * fraction
                let base = height * zero
                RoundedRectangle(cornerRadius: 4)
                    .fill(value >= 0 ? Color.accentColor : Color.orange)
                    .frame(width: 10, height: abs(top - base))
                    .offset(y: -min(top, base))
                Circle()
                    .fill(Color.white)
                    .frame(width: 26, height: 26)
                    .shadow(radius: 2)
                    .offset(y: -(top - 13))
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { gesture in
                        let f = max(0, min(1, 1 - gesture.location.y / height))
                        let next = range.lowerBound + Int((f * CGFloat(range.upperBound - range.lowerBound)).rounded())
                        if next != value { value = next }
                    }
                    .onEnded { _ in onCommit?() }
            )
        }
    }
}

struct SectionCard<Content: View>: View {
    let title: String
    var subtitle: String = ""
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.title3.weight(.bold))
                if !subtitle.isEmpty { Text(subtitle).font(.caption).foregroundStyle(.secondary) }
            }
            content()
        }
        .padding(16)
        .background(Color.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 18))
    }
}

struct ConnectionBanner: View {
    @EnvironmentObject var store: SoundStore

    var body: some View {
        if !store.connected {
            banner(icon: "cable.connector", title: "Not connected to the head unit",
                   detail: "Plug the iPhone into the car's USB, open DiPlay on the unit and start iPod audio. This app joins by itself.", color: .orange)
        } else if !store.hasState {
            banner(icon: "waveform", title: "Connected to \(store.link.accessoryName)", detail: "Waiting for the unit's sound state…", color: .blue)
        } else {
            EmptyView()
        }
    }

    private func banner(icon: String, title: String, detail: String, color: Color) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon).font(.title2).foregroundStyle(color)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.body.weight(.semibold))
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(color.opacity(0.15), in: RoundedRectangle(cornerRadius: 14))
    }
}
