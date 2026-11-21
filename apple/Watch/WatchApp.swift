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
        }
    }
}

struct RootView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        TabView {
            NavView()
            NotifView()
            StatusView()
        }
        .tabViewStyle(.page)
        .onChange(of: scenePhase) { phase in
            model.setActive(phase == .active)
        }
    }
}

/// Page 1: the current maneuver.
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
                    Text("Bắt đầu dẫn đường trong Google Maps trên điện thoại")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                }
            }
            .padding(.horizontal, 4)
        }
    }
}

/// Page 2: notifications forwarded from the phone.
struct NotifView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        if model.notifs.isEmpty {
            Text("Chưa có thông báo")
                .font(.footnote)
                .foregroundColor(.secondary)
        } else {
            List {
                ForEach(model.notifs) { item in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.app)
                            .font(.caption2)
                            .foregroundColor(item.isCall ? .green : .secondary)
                        if !item.title.isEmpty {
                            Text(item.title)
                                .font(.headline)
                                .lineLimit(1)
                        }
                        if !item.text.isEmpty {
                            Text(item.text)
                                .font(.footnote)
                                .lineLimit(5)
                        }
                    }
                }
                Button("Xóa hết") {
                    model.clearNotifications()
                }
            }
        }
    }
}

/// Page 3: link state and controls.
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
                if ble.drops > 0 {
                    Text("Rớt \(ble.drops) lần. Lần cuối: \(ble.lastDrop)")
                        .font(.caption2)
                        .foregroundColor(.secondary)
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
