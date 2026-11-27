import Foundation
import SwiftUI
import UserNotifications
import WatchKit

/// A turn after the next one, as listed under it.
struct NavStep: Equatable {
    var distance: String
    var instruction: String
    /// "r", "l", "sr", "sl", "hr", "hl", "u", "rb" (roundabout), "d" (destination) or "s" (straight on).
    var direction: String

    /// The arrow that goes with the direction.
    var symbol: String {
        switch direction {
        case "r", "hr": return "arrow.turn.up.right"
        case "l", "hl": return "arrow.turn.up.left"
        case "sr": return "arrow.up.right"
        case "sl": return "arrow.up.left"
        case "u": return "arrow.uturn.down"
        case "rb": return "arrow.triangle.2.circlepath"
        case "d": return "mappin.and.ellipse"
        default: return "arrow.up"
        }
    }
}

struct NavState: Equatable {
    var distance: String
    var instruction: String
    var eta: String
    /// The turns after this one, nearest first. Empty when only Google Maps is navigating.
    var later: [NavStep] = []
}

struct NotifItem: Identifiable, Equatable {
    let id: String
    var app: String
    var title: String
    var text: String
    var isCall: Bool
    var time: Date?
}

/// App state: decodes frames from the phone and drives haptics and alerts.
final class AppModel: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    @Published var nav: NavState?
    @Published var icon: UIImage?
    /// The phone's notification list, newest first.
    @Published var notifs: [NotifItem] = []
    /// False when alerts for this app are switched off in the watch's settings.
    @Published var alertsAllowed = true
    /// The notification shown full screen over every page until it is closed.
    /// watchOS does not reliably pop up an alert of the app that is running
    /// in front, so the app shows the newest one itself.
    @Published var card: NotifItem?
    /// What became of the alerts handed to watchOS, for the status page.
    @Published var alertNote = ""

    private var cardTimer: DispatchWorkItem?
    private var alertsPosted = 0
    private var alertsRefused = 0
    private var alertsAsked = 0
    private var lastAlertError = ""

    let ble = BLEManager()
    let session = SessionKeeper()
    let map = MapStore()
    let gps = WatchGPS()

    /// True while the app is on screen. Alerts go through the system when it is not.
    private var isActive = true
    private var mapSelected = true
    private var mapStateSent: Bool?
    /// When the phone was last asked to start sending positions.
    private var mapOnAt: Date?
    private var gpsTimer: Timer?
    private var lastTap = 0

    // Catching up after the link was down: one tap for everything that was missed.
    private var seenIds = Set<String>()
    private var syncedOnce = false
    private var missed = 0
    private var missedTimer: DispatchWorkItem?

    override init() {
        super.init()
        ble.onFrame = { [weak self] type, payload in
            self?.handle(type: type, payload: payload)
        }
        ble.onConnected = { [weak self] in
            guard let self = self else { return }
            self.ble.send(Proto.hello, json: [
                "v": 2,
                "os": WKInterfaceDevice.current().systemVersion
            ])
            self.map.linkLost()
            self.mapStateSent = nil
            self.sendMapState()
            self.holdLinkIfWanted()
        }
        map.requestTile = { [weak self] key in
            guard let self = self, self.ble.connected else { return false }
            self.ble.send(Proto.tileReq, json: ["z": key.z, "x": key.x, "y": key.y, "hd": 1])
            return true
        }
        session.onWillExpire = { [weak self] in
            WKInterfaceDevice.current().play(.retry)
            self?.alert(id: "session", title: "AW Bridge", body: "Sắp hết phiên chạy nền. Chạm để mở app và bật lại.")
        }
        gps.onFix = { [weak self] fix in
            self?.map.update(watchFix: fix)
        }
        gpsTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            self?.checkGPS()
        }
        map.setVisible(true)
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound]) { [weak self] _, _ in
            self?.refreshAlertStatus()
        }
    }

    func setActive(_ active: Bool) {
        isActive = active
        if active {
            ble.ensureConnecting()
            gps.prepare()
            holdLinkIfWanted()
            refreshAlertStatus()
            // A card that arrived with the wrist down stays a while once it is looked at.
            scheduleCardClose()
        } else {
            map.saveTrail()
        }
        map.setVisible(mapSelected && isActive)
        sendMapState()
        checkGPS()
    }

    /// The watch's own GPS runs only while the app is awake and the phone is
    /// not sending positions: link down, or asked for positions and silent.
    private func checkGPS() {
        let awake = isActive || session.running
        var phoneQuiet = !ble.connected
        if !phoneQuiet, mapStateSent == true, let since = mapOnAt,
           Date().timeIntervalSince(since) > 15, map.phoneSilentFor > 15 {
            phoneQuiet = true
        }
        if awake && phoneQuiet {
            gps.start()
        } else if gps.running {
            gps.stop()
            map.watchGPSStopped()
        }
        holdLinkIfWanted()
    }

    /// Whether the app should keep itself (and the Bluetooth link) alive with
    /// the wrist down. On by default: without it watchOS suspends the app a few
    /// seconds after the screen turns off and the link drops every time.
    var keepAlive: Bool {
        UserDefaults.standard.object(forKey: "keepAlive") as? Bool ?? true
    }

    func setKeepAlive(_ wanted: Bool) {
        UserDefaults.standard.set(wanted, forKey: "keepAlive")
        if wanted {
            session.start()
        } else {
            session.stop()
        }
    }

    /// Starts the background session once the link is up, or once the watch
    /// finds its own way without the phone. A session can only be started
    /// while the app is on screen, so this runs on connect and on every return
    /// to the foreground (which also renews it after the 1 h cap).
    private func holdLinkIfWanted() {
        if isActive && keepAlive && (ble.connected || gps.running) && !session.running {
            session.start()
        }
    }

    // MARK: Way back

    func startGoingBack() {
        map.startGoingBack()
        sendMapState()
    }

    func stopGoingBack() {
        map.stopGoingBack()
        sendMapState()
    }

    func ringPhone() {
        ble.send(Proto.action, json: ["a": "ring"])
    }

    func stopRinging() {
        ble.send(Proto.action, json: ["a": "stopring"])
    }

    func stopRoute() {
        ble.send(Proto.action, json: ["a": "stoproute"])
    }

    // MARK: Map

    func setMapSelected(_ selected: Bool) {
        mapSelected = selected
        map.setVisible(mapSelected && isActive)
        sendMapState()
    }

    /// The phone only runs its GPS for the watch while the map is being looked
    /// at, or while a route is followed (so the map is current on wrist raise).
    private func sendMapState() {
        guard ble.connected else {
            mapStateSent = nil
            return
        }
        let wanted = mapSelected && (isActive || nav != nil || map.goingBack)
        if mapStateSent == wanted { return }
        mapStateSent = wanted
        if wanted { mapOnAt = Date() }
        ble.send(Proto.map, json: ["on": wanted])
    }

    // MARK: Notifications

    /// Removes a notification here and on the phone.
    func dismiss(_ item: NotifItem) {
        if card?.id == item.id { closeCard() }
        notifs.removeAll { $0.id == item.id }
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [item.id])
        ble.send(Proto.notifDismiss, json: ["id": item.id])
    }

    func dismissAll() {
        closeCard()
        for item in notifs {
            ble.send(Proto.notifDismiss, json: ["id": item.id])
        }
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: notifs.map { $0.id })
        notifs.removeAll()
    }

    // MARK: Notification card

    /// Shows a notification full screen, on top of whatever page is open.
    private func show(card item: NotifItem) {
        card = item
        scheduleCardClose()
    }

    func closeCard() {
        cardTimer?.cancel()
        cardTimer = nil
        if card != nil { card = nil }
    }

    /// The card goes away by itself, sooner while a route is followed so the
    /// map comes back. The countdown only runs while the app is on screen.
    private func scheduleCardClose() {
        cardTimer?.cancel()
        cardTimer = nil
        guard card != nil, isActive else { return }
        let work = DispatchWorkItem { [weak self] in
            self?.closeCard()
        }
        cardTimer = work
        DispatchQueue.main.asyncAfter(deadline: .now() + (nav != nil ? 12 : 30), execute: work)
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
                if card?.id == id { closeCard() }
                notifs.removeAll { $0.id == id }
                UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [id])
            }
        case Proto.notifClear:
            // The phone resends its whole list after this.
            notifs.removeAll()
        case Proto.loc:
            if let json = AppModel.json(payload),
               let lat = AppModel.number(json["la"]), let lon = AppModel.number(json["lo"]) {
                map.update(fix: GeoFix(lat: lat, lon: lon, bearing: AppModel.number(json["b"]) ?? -1),
                           age: AppModel.number(json["ag"]) ?? 0)
            }
        case Proto.tile:
            map.receive(tile: payload)
        case Proto.route:
            if let json = AppModel.json(payload) {
                var destination: (lat: Double, lon: Double)?
                if let lat = AppModel.number(json["la"]), let lon = AppModel.number(json["lo"]) {
                    destination = (lat, lon)
                }
                map.setRoute(encoded: (json["p"] as? String) ?? "", destination: destination)
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
            sendMapState()
            return
        }
        var later: [NavStep] = []
        for item in (json["n"] as? [[String: Any]]) ?? [] {
            later.append(NavStep(
                distance: (item["d"] as? String) ?? "",
                instruction: (item["i"] as? String) ?? "",
                direction: (item["t"] as? String) ?? "s"
            ))
        }
        let next = NavState(
            distance: (json["d"] as? String) ?? "",
            instruction: (json["i"] as? String) ?? "",
            eta: (json["e"] as? String) ?? "",
            later: later
        )
        let started = nav == nil
        let instructionChanged = nav?.instruction != next.instruction
        // The phone bumps "v" shortly before a turn to ask for one more tap.
        let tap = Int(AppModel.number(json["v"]) ?? 0)
        let tapRequested = tap != lastTap
        lastTap = tap
        nav = next
        if instructionChanged || tapRequested {
            // A tap on the wrist only. A system alert would cover the map until
            // closed by hand, which cannot be done while riding. Messages and
            // other phone notifications still pop up as before.
            WKInterfaceDevice.current().play(.notification)
            // Navigation is the main use: keep the link alive without being asked.
            if isActive && !session.running {
                session.start()
            }
        }
        if started {
            // Turn alerts left by an earlier version must not sit over the map.
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["nav"])
            UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ["nav"])
            sendMapState()
        }
    }

    private func handleNotif(_ payload: Data) {
        guard let json = AppModel.json(payload), let id = json["id"] as? String else { return }
        var time: Date?
        if let millis = AppModel.number(json["w"]), millis > 0 {
            time = Date(timeIntervalSince1970: millis / 1000)
        }
        let item = NotifItem(
            id: id,
            app: (json["app"] as? String) ?? "",
            title: (json["t"] as? String) ?? "",
            text: (json["x"] as? String) ?? "",
            isCall: (json["c"] as? String) == "call",
            time: time
        )
        let quiet = (json["q"] as? Bool) ?? false
        let isNew = !seenIds.contains(id)
        if seenIds.count > 600 { seenIds.removeAll() }
        seenIds.insert(id)

        notifs.removeAll { $0.id == id }
        notifs.append(item)
        notifs.sort { ($0.time ?? .distantPast) > ($1.time ?? .distantPast) }
        if notifs.count > 60 {
            notifs.removeLast(notifs.count - 60)
        }

        if quiet {
            // Part of a resend. Anything not seen before arrived while the link was down.
            if isNew && syncedOnce { missed += 1 }
            scheduleMissedSummary()
            return
        }
        // Two ways at once, because neither covers every case. The card is what
        // is seen when the wrist is raised while this app is in front (which the
        // background session keeps it for a while). The system alert is what is
        // seen on the watch face or over another app.
        WKInterfaceDevice.current().play(item.isCall ? .retry : .notification)
        show(card: item)
        let heading = item.title.isEmpty ? item.app : "\(item.app): \(item.title)"
        alert(id: id, title: heading, body: item.text)
    }

    /// After a resend settles: one tap and one line for everything that was missed.
    private func scheduleMissedSummary() {
        missedTimer?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self.syncedOnce = true
            let count = self.missed
            self.missed = 0
            guard count > 0 else { return }
            WKInterfaceDevice.current().play(.notification)
            self.alert(id: "missed", title: "AW Bridge", body: "\(count) thông báo mới khi mất kết nối")
        }
        missedTimer = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5, execute: work)
    }

    // MARK: System alerts

    /// Posts a watch notification. Reusing an id replaces the earlier one.
    private func alert(id: String, title: String, body: String, after seconds: TimeInterval = 1) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        // Always a short timer, never "now": alerts without a trigger are the
        // ones reported to go missing on watchOS.
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(seconds, 1), repeats: false)
        let request = UNNotificationRequest(identifier: id, content: content, trigger: trigger)
        alertsPosted += 1
        UNUserNotificationCenter.current().add(request) { [weak self] error in
            DispatchQueue.main.async {
                guard let self = self else { return }
                if let error = error {
                    // watchOS refused it (alerts switched off for this app): the status page says so.
                    self.alertsRefused += 1
                    self.lastAlertError = error.localizedDescription
                }
                self.refreshAlertStatus()
            }
        }
    }

    /// Shows a test notification a few seconds from now, both ways, so it can
    /// be checked with the wrist down or from the watch face.
    func testAlert() {
        // Asks again in case the question was never answered on this watch.
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { [weak self] _, _ in
            DispatchQueue.main.async {
                guard let self = self else { return }
                let text = "Thông báo thử: nếu thấy dòng này bật lên là ổn."
                self.alert(id: "test", title: "AW Bridge", body: text, after: 5)
                DispatchQueue.main.asyncAfter(deadline: .now() + 5) {
                    WKInterfaceDevice.current().play(.notification)
                    self.show(card: NotifItem(id: "test", app: "AW Bridge", title: "Thông báo thử",
                                              text: text, isCall: false, time: Date()))
                }
            }
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler:
                                    @escaping (UNNotificationPresentationOptions) -> Void) {
        // watchOS asks here before showing an alert while this app is in front.
        // Always say yes; if it then shows nothing, the card has it covered.
        completionHandler([.banner, .list, .sound])
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.alertsAsked += 1
            self.refreshAlertStatus()
        }
    }

    /// Reads what watchOS allows this app and what it did with the alerts so
    /// far; checked on launch, on every return to the app and after each alert.
    func refreshAlertStatus() {
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { [weak self] settings in
            center.getDeliveredNotifications { delivered in
                let allowed = settings.authorizationStatus == .authorized
                    || settings.authorizationStatus == .provisional
                let permission: String
                switch settings.authorizationStatus {
                case .authorized: permission = "có"
                case .provisional: permission = "tạm"
                case .denied: permission = "bị từ chối"
                case .notDetermined: permission = "chưa hỏi"
                default: permission = "khác"
                }
                let banner = AppModel.word(settings.alertSetting)
                let sound = AppModel.word(settings.soundSetting)
                let list = AppModel.word(settings.notificationCenterSetting)
                let kept = delivered.count
                DispatchQueue.main.async {
                    guard let self = self else { return }
                    if self.alertsAllowed != allowed { self.alertsAllowed = allowed }
                    var note = "quyền \(permission), bật lên \(banner), âm \(sound), danh sách \(list). "
                    note += "Đã gửi \(self.alertsPosted), lỗi \(self.alertsRefused), "
                    note += "watch hỏi app \(self.alertsAsked), đang lưu \(kept)."
                    if !self.lastAlertError.isEmpty {
                        note += " Lỗi cuối: \(self.lastAlertError)"
                    }
                    if self.alertNote != note { self.alertNote = note }
                }
            }
        }
    }

    private static func word(_ setting: UNNotificationSetting) -> String {
        switch setting {
        case .enabled: return "bật"
        case .disabled: return "tắt"
        default: return "không có"
        }
    }

    // MARK: Decoding helpers

    private static func json(_ data: Data) -> [String: Any]? {
        (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private static func number(_ value: Any?) -> Double? {
        (value as? NSNumber)?.doubleValue
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
