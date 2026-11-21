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
        ZStack(alignment: .top) {
            TabView(selection: $page) {
                MapPage().tag(0)
                NavView().tag(1)
                NotifView().tag(2)
                StatusView().tag(3)
            }
            .tabViewStyle(.page)

            // A notification that just arrived, over whatever page is open.
            if let banner = model.banner, page != 2 {
                NotifBanner(item: banner)
                    .onTapGesture {
                        page = 2
                    }
            }
        }
        .onChange(of: scenePhase) { phase in
            model.setActive(phase == .active)
        }
        .onChange(of: page) { value in
            model.setMapSelected(value == 0)
        }
    }
}

/// Shown for a few seconds when a notification arrives while the app is open.
struct NotifBanner: View {
    let item: NotifItem

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(item.title.isEmpty ? item.app : "\(item.app) · \(item.title)")
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(item.isCall ? .green : .orange)
                .lineLimit(1)
            Text(item.text)
                .font(.system(size: 13))
                .lineLimit(3)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(Color.black.opacity(0.92))
        .cornerRadius(12)
        .padding(.horizontal, 4)
    }
}

/// Page 2: the current maneuver, large.
struct NavView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var ble: BLEManager

    var body: some View {
        if let nav = model.nav {
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
        } else {
            VStack(spacing: 6) {
                Image(systemName: ble.connected ? "location.fill" : "antenna.radiowaves.left.and.right")
                    .font(.title2)
                    .foregroundColor(ble.connected ? .green : .orange)
                Text(ble.connected ? "Chưa có chỉ đường" : ble.status)
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                if ble.connected {
                    Text("Chia sẻ một địa điểm từ Google Maps sang AW Bridge trên điện thoại")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                }
            }
            .padding(.horizontal, 4)
        }
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
                if !model.alertsAllowed {
                    Text("Thông báo đang bị tắt cho AW Bridge. Bật trong app Watch trên iPhone: Thông báo > AW Bridge.")
                        .font(.caption2)
                        .foregroundColor(.orange)
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
    }
}
