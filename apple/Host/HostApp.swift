import SwiftUI

/// The iPhone app is only a container. watchOS apps are installed through the
/// paired iPhone, so the watch app has to ship inside an iOS app.
@main
struct HostApp: App {
    var body: some Scene {
        WindowGroup {
            VStack(spacing: 16) {
                Text("AW Bridge")
                    .font(.largeTitle)
                    .bold()
                Text("App này chỉ là vỏ để cài app đồng hồ.")
                Text("Mở app Watch trên iPhone, kéo xuống mục Available Apps và bấm Install cho AW Bridge.")
                    .foregroundColor(.secondary)
            }
            .multilineTextAlignment(.center)
            .padding()
        }
    }
}
