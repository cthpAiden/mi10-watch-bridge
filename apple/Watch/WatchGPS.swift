import CoreLocation
import Foundation

/// The watch's own GPS. Normally the phone sends the position; this only runs
/// while the phone cannot (link down, phone switched off or out of battery),
/// so the map keeps working without it.
final class WatchGPS: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var running = false
    /// Location access was refused in the watch's settings.
    @Published private(set) var denied = false

    var onFix: ((GeoFix) -> Void)?

    private let manager = CLLocationManager()

    /// Background updates may only be switched on when Info.plist declares
    /// them, or CoreLocation stops the app.
    private static let backgroundAllowed: Bool = {
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String]
        return modes?.contains("location") ?? false
    }()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        // A fix every second even when standing still, or the map turns grey.
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = .otherNavigation
        refreshDenied()
    }

    /// Asks for location access while the app is on screen, so the question
    /// is answered before the GPS is ever needed with the wrist down.
    func prepare() {
        if manager.authorizationStatus == .notDetermined {
            manager.requestAlwaysAuthorization()
        }
    }

    func start() {
        guard !running else { return }
        running = true
        prepare()
        if WatchGPS.backgroundAllowed {
            manager.allowsBackgroundLocationUpdates = true
        }
        manager.startUpdatingLocation()
    }

    func stop() {
        guard running else { return }
        running = false
        manager.stopUpdatingLocation()
    }

    /// Call when the app comes back on screen. Updates asked for with the
    /// wrist down may have been held back by watchOS; asking again while the
    /// app is in front gets them going. Harmless when they already run.
    func resume() {
        prepare()
        if running {
            manager.startUpdatingLocation()
        }
    }

    private func refreshDenied() {
        let status = manager.authorizationStatus
        let refused = status == .denied || status == .restricted
        if denied != refused { denied = refused }
    }

    // MARK: CLLocationManagerDelegate

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        refreshDenied()
        if running {
            manager.startUpdatingLocation()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard running else { return }
        for location in locations {
            // The first fixes of a cold start can be a kilometre off: skip
            // those. Anything within 100 m is shown, because a rough position
            // is far better than none when this is the only GPS left.
            guard location.horizontalAccuracy >= 0, location.horizontalAccuracy <= 100 else { continue }
            let moving = location.speed > 1.0 && location.course >= 0
            onFix?(GeoFix(lat: location.coordinate.latitude,
                          lon: location.coordinate.longitude,
                          bearing: moving ? location.course : -1))
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        refreshDenied()
    }
}
