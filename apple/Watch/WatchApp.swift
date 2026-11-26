import SwiftUI

@main
struct AWBridgeWatchApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .environmentObject(model.ble)
                .environmentObject(model.session)
                .environmentObject(model.map)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var page = 0

    var body: some View {
        ZStack {
            TabView(selection: $page) {
                MapPage().tag(0)
                NavView().tag(1)
                NotifView().tag(2)
                StatusView().tag(3)
            }
            .tabViewStyle(.page)
            // The newest notification covers whatever page is open, the way a
            // system notification covers the app.
            if let card = model.card {
                NotifCard(item: card)
                    .transition(.move(edge: .top))
            }
        }
        .animation(.easeOut(duration: 0.2), value: model.card)
        .onChange(of: scenePhase) { phase in
            model.setActive(phase == .active)
        }
        .onChange(of: page) { value in
            model.setMapSelected(value == 0)
        }
    }
}

/// A notification from the phone, full screen. Tap "Đóng" to go back to the
/// page underneath; it also closes by itself after a while.
struct NotifCard: View {
    let item: NotifItem
    @EnvironmentObject var model: AppModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 5) {
                    Image(systemName: item.isCall ? "phone.fill" : "bell.fill")
                        .font(.system(size: 12, weight: .bold))
                    Text(item.app)
                        .font(.system(size: 13, weight: .semibold))
                        .lineLimit(1)
                }
                .foregroundColor(item.isCall ? .green : .orange)
                if !item.title.isEmpty {
                    Text(item.title)
                        .font(.headline)
                        .lineLimit(3)
                }
                if !item.text.isEmpty {
                    Text(item.text)
                        .font(.body)
                        .lineLimit(14)
                }
                Button("Đóng") {
                    model.closeCard()
                }
                .padding(.top, 4)
                // The test card is not a notification of the phone: nothing to remove there.
                if item.id != "test" {
                    Button("Xóa cả trên điện thoại") {
                        model.dismiss(item)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 4)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black.ignoresSafeArea())
    }
}

/// Page 2: the next turn, large, and the turns that follow it.
struct NavView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var ble: BLEManager

    var body: some View {
        if let nav = model.nav {
            if nav.later.isEmpty {
                single(nav)
            } else {
                list(nav)
            }
        } else {
            idle
        }
    }

    /// Only the next turn is known (Google Maps tells no more): shown as large as it fits.
    private func single(_ nav: NavState) -> some View {
        VStack(spacing: 4) {
            HStack(spacing: 8) {
                if let icon = model.icon {
                    Image(uiImage: icon)
                        .renderingMode(.template)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 52, height: 52)
                        .foregroundColor(.green)
                }
                Text(nav.distance)
                    .font(.system(size: 30, weight: .bold, design: .rounded))
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
            }
            Text(nav.instruction)
                .font(.headline)
                .multilineTextAlignment(.center)
                .lineLimit(3)
                .minimumScaleFactor(0.7)
            if !nav.eta.isEmpty {
                Text(nav.eta)
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
            }
        }
        .padding(.horizontal, 4)
    }

    /// The next turn on top, then the ones after it, each with its distance from here.
    private func list(_ nav: NavState) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                if let icon = model.icon {
                    Image(uiImage: icon)
                        .renderingMode(.template)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 34, height: 34)
                        .foregroundColor(.green)
                }
                VStack(alignment: .leading, spacing: 0) {
                    Text(nav.distance)
                        .font(.system(size: 22, weight: .bold, design: .rounded))
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    Text(nav.instruction)
                        .font(.system(size: 13, weight: .semibold))
                        .lineLimit(2)
                        .minimumScaleFactor(0.75)
                }
            }
            ForEach(Array(nav.later.enumerated()), id: \.offset) { _, step in
                Divider()
                HStack(spacing: 6) {
                    Image(systemName: step.symbol)
                        .font(.system(size: 15, weight: .bold))
                        .foregroundColor(.green)
                        .frame(width: 22)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(step.distance)
                            .font(.system(size: 14, weight: .bold, design: .rounded))
                            .lineLimit(1)
                        Text(step.instruction)
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                }
            }
            if !nav.eta.isEmpty {
                Text(nav.eta)
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .frame(maxWidth: .infinity, alignment: .center)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 6)
    }

    private var idle: some View {
        VStack(spacing: 6) {
            Image(systemName: ble.connected ? "location.fill" : "antenna.radiowaves.left.and.right")
                .font(.title2)
                .foregroundColor(ble.connected ? .green : .orange)
            Text(ble.connected ? "Chưa có chỉ đường" : ble.status)
                .font(.footnote)
                .multilineTextAlignment(.center)
            if ble.connected {
                Text("Bấm Start trong Google Maps, hoặc chia sẻ điểm đến sang AW Bridge để xem trước 3 chỗ quẹo")
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(.horizontal, 4)
    }
}

/// Page 3: the phone's notifications. Swipe one away to dismiss it on the phone too.
struct NotifView: View {
    @EnvironmentObject var model: AppModel

    private static let clock: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter
    }()

    var body: some View {
        if model.notifs.isEmpty {
            Text("Chưa có thông báo")
                .font(.footnote)
                .foregroundColor(.secondary)
        } else {
            List {
                ForEach(model.notifs) { item in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(item.app)
                                .font(.caption2)
                                .foregroundColor(item.isCall ? .green : .orange)
                                .lineLimit(1)
                            Spacer(minLength: 4)
                            if let time = item.time {
                                Text(NotifView.clock.string(from: time))
                                    .font(.caption2)
                                    .foregroundColor(.secondary)
                            }
                        }
                        if !item.title.isEmpty {
                            Text(item.title)
                                .font(.headline)
                                .lineLimit(2)
                        }
                        if !item.text.isEmpty {
                            Text(item.text)
                                .font(.footnote)
                                .lineLimit(12)
                        }
                    }
                }
                .onDelete { offsets in
                    // Look the rows up first: dismissing one shifts the others.
                    let items = offsets.compactMap { $0 < model.notifs.count ? model.notifs[$0] : nil }
                    for item in items {
                        model.dismiss(item)
                    }
                }
                Button("Xóa hết (cả trên điện thoại)") {
                    model.dismissAll()
                }
            }
        }
    }
}

/// Page 4: link state and controls.
struct StatusView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var ble: BLEManager
    @EnvironmentObject var session: SessionKeeper
    @EnvironmentObject var map: MapStore

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 6) {
                    Circle()
                        .fill(ble.connected ? Color.green : Color.orange)
                        .frame(width: 10, height: 10)
                    Text(ble.status)
                        .font(.footnote)
                }
                // Which build is installed: a watch app with an unchanged build
                // number is not replaced, so this shows whether an update landed.
                Text("Bản build \(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?")")
                    .font(.caption2)
                    .foregroundColor(.secondary)
                Toggle("Bản đồ tối (đỡ pin)", isOn: $map.darkMap)
                Toggle("Giữ chạy nền", isOn: Binding(
                    get: { session.running },
                    set: { wanted in
                        model.setKeepAlive(wanted)
                    }
                ))
                if !session.note.isEmpty {
                    Text(session.note)
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
                if model.alertsAllowed {
                    Text("Thông báo: được phép hiện trên mặt đồng hồ")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                } else {
                    Text("Thông báo đang BỊ TẮT cho AW Bridge. Bật trong app Watch trên iPhone: Thông báo > AW Bridge.")
                        .font(.caption2)
                        .foregroundColor(.orange)
                }
                Button("Thử thông báo (sau 5 giây)") {
                    model.testAlert()
                }
                // What watchOS did with the alerts, to tell where one got lost.
                if !model.alertNote.isEmpty {
                    Text("Chẩn đoán: \(model.alertNote)")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
                if ble.drops > 0 {
                    Text("Rớt \(ble.drops) lần. Lần cuối: \(ble.lastDrop)")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
                if model.nav != nil {
                    Button("Hủy chỉ đường") {
                        model.stopRoute()
                    }
                    .disabled(!ble.connected)
                }
                Button("Tìm điện thoại") {
                    model.ringPhone()
                }
                .disabled(!ble.connected)
                Button("Tắt chuông") {
                    model.stopRinging()
                }
                .disabled(!ble.connected)
            }
            .padding(.horizontal, 4)
        }
        .onAppear {
            model.refreshAlertStatus()
        }
    }
}
