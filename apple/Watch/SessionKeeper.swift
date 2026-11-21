import Foundation
import WatchKit

/// Keeps the app (and its Bluetooth link) running with the wrist down by
/// holding an extended runtime session. watchOS caps one session at an hour.
final class SessionKeeper: NSObject, ObservableObject, WKExtendedRuntimeSessionDelegate {
    @Published var running = false
    @Published var note = ""

    var onWillExpire: (() -> Void)?

    private var session: WKExtendedRuntimeSession?

    func start() {
        if let current = session, current.state == .running || current.state == .scheduled {
            return
        }
        let fresh = WKExtendedRuntimeSession()
        fresh.delegate = self
        session = fresh
        fresh.start()
    }

    func stop() {
        session?.invalidate()
    }

    func extendedRuntimeSessionDidStart(_ extendedRuntimeSession: WKExtendedRuntimeSession) {
        DispatchQueue.main.async {
            self.running = true
            self.note = ""
        }
    }

    func extendedRuntimeSessionWillExpire(_ extendedRuntimeSession: WKExtendedRuntimeSession) {
        DispatchQueue.main.async {
            self.onWillExpire?()
        }
    }

    func extendedRuntimeSession(_ extendedRuntimeSession: WKExtendedRuntimeSession,
                                didInvalidateWith reason: WKExtendedRuntimeSessionInvalidationReason,
                                error: Error?) {
        DispatchQueue.main.async {
            if self.session === extendedRuntimeSession {
                self.session = nil
            }
            self.running = false
            if let error = error {
                self.note = error.localizedDescription
            } else if reason == .expired {
                self.note = "Hết 1 giờ. Bật lại để tiếp tục."
            } else {
                self.note = ""
            }
        }
    }
}
