import Foundation
import SwiftUI
import UserNotifications
import WatchKit

struct NavState: Equatable {
    var distance: String
    var instruction: String
    var eta: String
}

struct NotifItem: Identifiable, Equatable {
    let id: String
    var app: String
    var title: String
    var text: String
    var isCall: Bool
}

/// App state: decodes frames from the phone and drives haptics and alerts.
final class AppModel: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    @Published var nav: NavState?
    @Published var icon: UIImage?
    @Published var notifs: [NotifItem] = []

    let ble = BLEManager()
    let session = SessionKeeper()

    /// True while the app is on screen. Alerts go through the system when it is not.
    private var isActive = true

    override init() {
        super.init()
        ble.onFrame = { [weak self] type, payload in
            self?.handle(type: type, payload: payload)
        }
        ble.onConnected = { [weak self] in
            guard let self = self else { return }
            self.ble.send(Proto.hello, json: [
                "v": 1,
                "os": WKInterfaceDevice.current().systemVersion
            ])
        }
        session.onWillExpire = { [weak self] in
            WKInterfaceDevice.current().play(.retry)
            self?.alert(title: "AW Bridge", body: "Sắp hết phiên chạy nền. Mở app để bật lại.")
        }
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    func setActive(_ active: Bool) {
        isActive = active
        if active {
            ble.ensureConnecting()
        }
    }

    func ringPhone() {
        ble.send(Proto.action, json: ["a": "ring"])
    }

    func stopRinging() {
        ble.send(Proto.action, json: ["a": "stopring"])
    }

    func clearNotifications() {
        notifs.removeAll()
    }

    // MARK: Frames from the phone

    private func handle(type: UInt8, payload: Data) {
        switch type {
        case Proto.nav:
            handleNav(payload)
        case Proto.icon:
            icon = AppModel.image(fromBits: payload)
        case Proto.notif:
            handleNotif(payload)
        case Proto.notifRemove:
            if let json = AppModel.json(payload), let id = json["id"] as? String {
                notifs.removeAll { $0.id == id }
            }
        default:
            break
        }
    }

    private func handleNav(_ payload: Data) {
        guard let json = AppModel.json(payload) else { return }
        let active = (json["a"] as? Bool) ?? false
        if !active {
            if nav != nil {
                WKInterfaceDevice.current().play(.success)
            }
            nav = nil
            icon = nil
            return
        }
        let next = NavState(
            distance: (json["d"] as? String) ?? "",
            instruction: (json["i"] as? String) ?? "",
            eta: (json["e"] as? String) ?? ""
        )
        let instructionChanged = nav?.instruction != next.instruction
        nav = next
        if instructionChanged {
            WKInterfaceDevice.current().play(.notification)
            if !isActive {
                alert(title: next.distance, body: next.instruction)
            }
            // Navigation is the main use: keep the link alive without being asked.
            if isActive && !session.running {
                session.start()
            }
        }
    }

    private func handleNotif(_ payload: Data) {
        guard let json = AppModel.json(payload), let id = json["id"] as? String else { return }
        let item = NotifItem(
            id: id,
            app: (json["app"] as? String) ?? "",
            title: (json["t"] as? String) ?? "",
            text: (json["x"] as? String) ?? "",
            isCall: (json["c"] as? String) == "call"
        )
        notifs.removeAll { $0.id == id }
        notifs.insert(item, at: 0)
        if notifs.count > 30 {
            notifs.removeLast(notifs.count - 30)
        }
        WKInterfaceDevice.current().play(item.isCall ? .retry : .notification)
        if !isActive {
            let heading = item.title.isEmpty ? item.app : "\(item.app): \(item.title)"
            alert(title: heading, body: item.text)
        }
    }

    // MARK: System alerts

    private func alert(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler:
                                    @escaping (UNNotificationPresentationOptions) -> Void) {
        // The app is on screen and already shows the content itself.
        completionHandler([])
    }

    // MARK: Decoding helpers

    private static func json(_ data: Data) -> [String: Any]? {
        (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    /// Decodes the maneuver icon: width, height, then 1 bit per pixel, MSB first.
    static func image(fromBits data: Data) -> UIImage? {
        let bytes = [UInt8](data)
        guard bytes.count >= 2 else { return nil }
        let width = Int(bytes[0])
        let height = Int(bytes[1])
        guard width > 0, height > 0, bytes.count >= 2 + (width * height + 7) / 8 else { return nil }

        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        for index in 0..<(width * height) {
            let mask: UInt8 = 0x80 >> UInt8(index & 7)
            if bytes[2 + (index >> 3)] & mask != 0 {
                let offset = index * 4
                rgba[offset] = 255
                rgba[offset + 1] = 255
                rgba[offset + 2] = 255
                rgba[offset + 3] = 255
            }
        }
        guard let provider = CGDataProvider(data: Data(rgba) as CFData),
              let image = CGImage(
                width: width,
                height: height,
                bitsPerComponent: 8,
                bitsPerPixel: 32,
                bytesPerRow: width * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                provider: provider,
                decode: nil,
                shouldInterpolate: true,
                intent: .defaultIntent
              ) else { return nil }
        return UIImage(cgImage: image)
    }
}
