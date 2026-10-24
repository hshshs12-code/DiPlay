// SPDX-License-Identifier: GPL-3.0-only
import Foundation
import ExternalAccessory

/// The cable link to the head unit. DiPlay identifies to the iPhone as an MFi accessory that
/// offers the External Accessory protocol below; iOS exposes that as an `EASession` with a pair
/// of streams. Messages are newline-delimited JSON objects in both directions.
final class AccessoryLink: NSObject, ObservableObject, StreamDelegate {
    static let protocolString = "com.shilapi.xcertplay"

    @Published private(set) var connected = false
    @Published private(set) var accessoryName = ""
    @Published private(set) var accessoryDetail = ""
    @Published private(set) var log: [String] = []

    /// One complete line from the head unit (main thread).
    var onLine: ((String) -> Void)?

    private var session: EASession?
    private var inbound = Data()
    private var outbound = Data()
    private var retry: Timer?

    func start() {
        EAAccessoryManager.shared().registerForLocalNotifications()
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(accessoryConnected(_:)), name: .EAAccessoryDidConnect, object: nil)
        center.addObserver(self, selector: #selector(accessoryDisconnected(_:)), name: .EAAccessoryDidDisconnect, object: nil)
        connect()
    }

    @objc private func accessoryConnected(_ note: Notification) {
        if let accessory = note.userInfo?[EAAccessoryKey] as? EAAccessory {
            self.note("accessory connected: \(accessory.name) protocols \(accessory.protocolStrings)")
        }
        connect()
    }

    @objc private func accessoryDisconnected(_ note: Notification) {
        guard let accessory = note.userInfo?[EAAccessoryKey] as? EAAccessory else { return }
        if session?.accessory == accessory { close(reason: "\(accessory.name) disconnected") }
    }

    func connect() {
        guard session == nil else { return }
        let all = EAAccessoryManager.shared().connectedAccessories
        guard let accessory = all.first(where: { $0.protocolStrings.contains(Self.protocolString) }) else {
            if all.isEmpty {
                note("no accessory connected")
            } else {
                note("connected accessories offer \(all.map { "\($0.name): \($0.protocolStrings)" }.joined(separator: "; ")), not \(Self.protocolString)")
            }
            scheduleRetry()
            return
        }
        guard let session = EASession(accessory: accessory, forProtocol: Self.protocolString) else {
            note("EASession could not be opened with \(accessory.name)")
            scheduleRetry()
            return
        }
        self.session = session
        inbound.removeAll(); outbound.removeAll()
        if let input = session.inputStream {
            input.delegate = self; input.schedule(in: .main, forMode: .common); input.open()
        }
        if let output = session.outputStream {
            output.delegate = self; output.schedule(in: .main, forMode: .common); output.open()
        }
        accessoryName = accessory.name
        accessoryDetail = "\(accessory.manufacturer) \(accessory.modelNumber) · firmware \(accessory.firmwareRevision) · serial \(accessory.serialNumber)"
        connected = true
        retry?.invalidate(); retry = nil
        note("session open with \(accessory.name)")
    }

    func close(reason: String) {
        guard let session = session else { return }
        for stream in [session.inputStream as Stream?, session.outputStream as Stream?].compactMap({ $0 }) {
            stream.close()
            stream.remove(from: .main, forMode: .common)
            stream.delegate = nil
        }
        self.session = nil
        connected = false
        note("session closed: \(reason)")
        scheduleRetry()
    }

    /// Queues one JSON object; it goes out as soon as the output stream has room.
    func send(_ object: [String: Any]) {
        guard JSONSerialization.isValidJSONObject(object), var data = try? JSONSerialization.data(withJSONObject: object) else {
            note("refused to send a non-JSON object")
            return
        }
        data.append(0x0a)
        outbound.append(data)
        flush()
    }

    private func flush() {
        guard let output = session?.outputStream else { return }
        while !outbound.isEmpty && output.hasSpaceAvailable {
            let written = outbound.withUnsafeBytes { raw -> Int in
                guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return 0 }
                return output.write(base, maxLength: outbound.count)
            }
            if written <= 0 { break }
            outbound.removeFirst(written)
        }
    }

    private func scheduleRetry() {
        retry?.invalidate()
        retry = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: false) { [weak self] _ in self?.connect() }
    }

    // MARK: StreamDelegate

    func stream(_ aStream: Stream, handle eventCode: Stream.Event) {
        switch eventCode {
        case .hasBytesAvailable:
            if let input = aStream as? InputStream { read(from: input) }
        case .hasSpaceAvailable:
            flush()
        case .errorOccurred:
            close(reason: "stream error: \(aStream.streamError?.localizedDescription ?? "unknown")")
        case .endEncountered:
            close(reason: "stream ended")
        default:
            break
        }
    }

    private func read(from input: InputStream) {
        var buffer = [UInt8](repeating: 0, count: 4096)
        while input.hasBytesAvailable {
            let count = input.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            inbound.append(buffer, count: count)
        }
        while let newline = inbound.firstIndex(of: 0x0a) {
            let lineData = inbound.subdata(in: inbound.startIndex..<newline)
            inbound.removeSubrange(inbound.startIndex...newline)
            if let line = String(data: lineData, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines), !line.isEmpty {
                onLine?(line)
            }
        }
    }

    private func note(_ line: String) {
        let stamp = DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium)
        log.append("\(stamp)  \(line)")
        if log.count > 200 { log.removeFirst(log.count - 200) }
    }
}
