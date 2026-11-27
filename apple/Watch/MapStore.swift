import Foundation
import SwiftUI
import UIKit

/// Where the phone is, as last reported over Bluetooth.
struct GeoFix: Equatable {
    var lat: Double
    var lon: Double
    /// Direction of travel in degrees clockwise from north, or -1 when standing still.
    var bearing: Double
}

struct TileKey: Hashable {
    let z: Int
    let x: Int
    let y: Int

    init(z: Int, x: Int, y: Int) {
        self.z = z
        self.x = x
        self.y = y
    }

    /// The tile a cache file holds, or nil for a file that is not a tile.
    init?(fileName: String) {
        guard fileName.hasPrefix("e_"), fileName.hasSuffix(".png") else { return nil }
        let parts = fileName.dropFirst(2).dropLast(4).split(separator: "_").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        self.init(z: parts[0], x: parts[1], y: parts[2])
    }

    /// "e" marks the exact (not JPEG compressed) double resolution tiles; files
    /// under any other name are from earlier versions and get deleted.
    var fileName: String { "e_\(z)_\(x)_\(y).png" }
}

/// A point in Web Mercator, as a fraction of the whole world (0 to 1 on both axes).
struct WorldPoint {
    var x: Double
    var y: Double

    init(x: Double, y: Double) {
        self.x = x
        self.y = y
    }

    init(lat: Double, lon: Double) {
        let clamped = min(max(lat, -85.0), 85.0) * .pi / 180
        x = (lon + 180) / 360
        y = (1 - log(tan(clamped) + 1 / cos(clamped)) / .pi) / 2
    }

    var lat: Double { atan(sinh(.pi * (1 - 2 * y))) * 180 / .pi }
}

/// Map state for the watch: the position, the planned route, the road
/// travelled so far and the map tiles. The watch has no internet, so every
/// tile it lacks is requested from the phone over Bluetooth and kept on disk.
///
/// Two kinds of tiles are kept. Tiles that were simply looked at sit in the
/// cache folder, which is trimmed and which watchOS may empty when space runs
/// low. Tiles of an area saved for offline use sit in Application Support,
/// which is never trimmed, so the map still works once the phone is gone.
final class MapStore: ObservableObject {
    /// One map tile is drawn this many points wide. Tiles are 256 pixels and the
    /// screen has two pixels per point, so this is pixel for pixel as long as
    /// each tile also starts on a whole pixel (see MapPage).
    static let tilePoints: CGFloat = 128
    /// A stand-in tile (ordinary resolution) is asked for again after this long.
    static let provisionalSeconds = 45.0
    /// How many zoom levels a coarser tile may be magnified while the sharp one loads.
    static let magnifyLevels = 3
    /// A position measured longer ago than this is drawn as not current.
    static let oldFixSeconds = 90.0

    static let zoomRange = 13...19

    @Published private(set) var fix: GeoFix?
    /// True while the position comes from the watch's own GPS because the
    /// phone is not sending one (link down, phone off).
    @Published private(set) var usingWatchGPS = false
    @Published var zoom = 16 {
        didSet {
            if zoom != oldValue { ensureTiles() }
        }
    }
    /// The middle of the screen after the map was dragged away from the
    /// position, or nil while the map follows the position.
    @Published var center: WorldPoint? {
        didSet { ensureTiles() }
    }
    /// The compass direction at the top of the screen, in degrees clockwise from north. 0 is north up.
    @Published var rotation = 0.0 {
        didSet {
            if (rotation == 0) != (oldValue == 0) { ensureTiles() }
        }
    }
    /// The map turns by itself so that the direction of travel points up.
    @Published var headingUp = false

    /// While the map follows a moving position, the middle of the screen sits
    /// this many points ahead of it along the direction of travel. The marker
    /// then sits off centre, behind, so more of the road ahead is on screen
    /// and the turn banner at the top covers less of it.
    static let lookAheadPoints = 42.0

    /// The point in the middle of the screen.
    var viewPoint: WorldPoint? {
        if let center = center { return center }
        guard let fix = fix else { return nil }
        let here = WorldPoint(lat: fix.lat, lon: fix.lon)
        guard fix.bearing >= 0 else { return here }
        let world = Double(MapStore.tilePoints) * Double(1 << tileZoom)
        let bearing = fix.bearing * .pi / 180
        let ahead = MapStore.lookAheadPoints / world
        return WorldPoint(x: min(max(here.x + sin(bearing) * ahead, 0), 1),
                          y: min(max(here.y - cos(bearing) * ahead, 0), 1))
    }
    /// The zoom level of the tiles on screen. Each one covers a quarter of the
    /// ground of a tile at `zoom` and is cut by the phone from a double
    /// resolution tile of that level, so the map looks like `zoom` but sharp.
    var tileZoom: Int { zoom + 1 }
    /// The route line in world coordinates, empty when no route is set.
    @Published private(set) var route: [WorldPoint] = []
    @Published private(set) var destination: WorldPoint?
    /// The route is the road travelled so far, backwards: the way back to
    /// where the trip started, made on the watch without the phone.
    @Published private(set) var goingBack = false
    /// How far along the route the position has come: the segment it was
    /// last matched to (from `route[routeDone]` to the next point), and the
    /// matched spot on it. Only the route from that spot on is drawn, so the
    /// road already travelled disappears. It only ever moves forward.
    private(set) var routeDone = 0
    private(set) var routeCut: WorldPoint?
    private var routeT = 0.0
    /// Bumped whenever a tile arrives so the map redraws.
    @Published private(set) var revision = 0
    /// True when the position has not been refreshed for a while (link down, GPS off).
    @Published private(set) var stale = false
    /// Tiles drawn dark. A black pixel on this OLED screen uses no power, and
    /// OpenStreetMap is mostly white, so this is the largest saving while the
    /// map is on screen. On unless switched off on the status page.
    @Published var darkMap = UserDefaults.standard.object(forKey: "darkMap") as? Bool ?? true {
        didSet { UserDefaults.standard.set(darkMap, forKey: "darkMap") }
    }

    /// True while the phone answers tile requests with "could not download".
    @Published private(set) var tileProblem = false

    /// Asks the phone for a tile. Returns false when the request could not be sent.
    var requestTile: ((TileKey) -> Bool)?

    private var memory: [TileKey: UIImage] = [:]
    /// Every tile saved in the cache folder, so drawing never has to probe the file system.
    private var onDisk = Set<TileKey>()
    /// Every tile saved for offline use.
    private var kept = Set<TileKey>()
    private var recent: [TileKey] = []
    private var pending: [TileKey: Date] = [:]
    private var unavailable: [TileKey: Date] = [:]
    /// Tiles shown from a stand-in image, and since when.
    private var provisional: [TileKey: Date] = [:]
    private var fixAt: Date?
    private var phoneFixAt: Date?
    private var fixAge = 0.0
    private var timer: Timer?
    private let folder: URL
    private let keepFolder: URL
    private let routeFile: URL
    private let trailFile: URL
    /// File writes run one after the other, so an older save can never land
    /// on top of a newer one.
    private let io = DispatchQueue(label: "dev.awbridge.map.io", qos: .utility)

    init() {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        folder = caches.appendingPathComponent("tiles", isDirectory: true)
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        keepFolder = support.appendingPathComponent("offline", isDirectory: true)
        routeFile = support.appendingPathComponent("route.json")
        trailFile = support.appendingPathComponent("trail.json")
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: keepFolder, withIntermediateDirectories: true)
        loadRoute()
        loadTrail()
        let folder = self.folder
        let keepFolder = self.keepFolder
        DispatchQueue.global(qos: .utility).async { [weak self] in
            MapStore.prune(folder)
            let names = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
            // Tiles saved by earlier versions were the blurry kind: drop them.
            for name in names where TileKey(fileName: name) == nil {
                try? FileManager.default.removeItem(at: folder.appendingPathComponent(name))
            }
            let keys = Set(names.compactMap { TileKey(fileName: $0) })
            // An offline map deleted just before the app was closed may still be lying around.
            let supportNames = (try? FileManager.default.contentsOfDirectory(atPath: support.path)) ?? []
            for name in supportNames where name.hasPrefix("offline-trash-") {
                try? FileManager.default.removeItem(at: support.appendingPathComponent(name))
            }
            var keptKeys = Set<TileKey>()
            var keptSize = 0
            let saved = (try? FileManager.default.contentsOfDirectory(
                at: keepFolder, includingPropertiesForKeys: [.fileSizeKey])) ?? []
            for url in saved {
                guard let key = TileKey(fileName: url.lastPathComponent) else { continue }
                keptKeys.insert(key)
                keptSize += (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
            }
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.onDisk.formUnion(keys)
                // Not if the offline map was deleted while this list was being read.
                if !self.offlineCleared {
                    self.kept.formUnion(keptKeys)
                    self.keptBytes += keptSize
                    self.keptCount = self.kept.count
                }
                self.revision += 1
                self.ensureTiles()
            }
        }
    }

    /// While the map is on screen, missing tiles are asked for again every few
    /// seconds, so a failed download or a dropped link heals without user action.
    func setVisible(_ visible: Bool) {
        if visible {
            guard timer == nil else { return }
            tick()
            timer = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in
                self?.tick()
            }
        } else {
            timer?.invalidate()
            timer = nil
        }
    }

    private func tick() {
        ensureTiles()
        // Stale: no position for a while, or what the phone reports was measured long ago.
        var old = false
        if let since = fixAt {
            old = Date().timeIntervalSince(since) > 12 || fixAge > MapStore.oldFixSeconds
        }
        if old != stale { stale = old }
    }

    // MARK: Position

    /// Seconds since the phone last sent a position.
    var phoneSilentFor: TimeInterval {
        phoneFixAt.map { Date().timeIntervalSince($0) } ?? .infinity
    }

    /// A position from the phone. `age` is how many seconds ago the phone measured it.
    func update(fix: GeoFix, age: Double) {
        phoneFixAt = Date()
        if usingWatchGPS { usingWatchGPS = false }
        apply(fix, age: age)
    }

    /// A position from the watch's own GPS. Ignored while the phone is sending:
    /// the phone's fix is the better one and there is no point in mixing them.
    func update(watchFix: GeoFix) {
        guard phoneSilentFor > 6 else { return }
        if !usingWatchGPS { usingWatchGPS = true }
        apply(watchFix, age: 0)
    }

    func watchGPSStopped() {
        if usingWatchGPS { usingWatchGPS = false }
    }

    private func apply(_ fix: GeoFix, age: Double) {
        if self.fix != fix { self.fix = fix }
        if headingUp && fix.bearing >= 0 && rotation != fix.bearing {
            rotation = fix.bearing
        }
        fixAt = Date()
        fixAge = age
        let old = age > MapStore.oldFixSeconds
        if old != stale { stale = old }
        advanceRoute(fix)
        if age < 30 { record(fix) }
        ensureTiles()
    }

    // MARK: Route

    /// A route from the phone, or its removal (empty string).
    func setRoute(encoded: String, destination: (lat: Double, lon: Double)?) {
        let points = MapStore.decode(encoded)
        // The phone replays "no route" on every reconnect: that must not wipe
        // out the way back, which the phone knows nothing about.
        if points.isEmpty && goingBack { return }
        goingBack = false
        var end: WorldPoint?
        if let destination = destination, !points.isEmpty {
            end = WorldPoint(lat: destination.lat, lon: destination.lon)
        }
        use(points, destination: end)
    }

    /// Turns the road travelled so far around and follows it back to where the trip began.
    func startGoingBack() {
        guard trail.count > 1 else { return }
        goingBack = true
        use(Array(trail.reversed()), destination: trail.first)
    }

    func stopGoingBack() {
        guard goingBack else { return }
        goingBack = false
        use([], destination: nil)
    }

    private func use(_ points: [WorldPoint], destination: WorldPoint?) {
        route = points
        routeDone = 0
        routeT = 0
        routeCut = nil
        self.destination = points.isEmpty ? nil : destination
        if let fix = fix { advanceRoute(fix) }
        saveRoute()
    }

    /// Metres from the position to the end of the route, along the route.
    func remainingMetres() -> Double? {
        guard route.count > 1, let fix = fix else { return nil }
        var previous = routeCut ?? route[0]
        let first = routeCut == nil ? 1 : routeDone + 1
        var total = 0.0
        if first < route.count {
            for point in route[first...] {
                total += hypot(point.x - previous.x, point.y - previous.y)
                previous = point
            }
        }
        return total * MapStore.metresPerWorld(lat: fix.lat)
    }

    private struct SavedRoute: Codable {
        var points: [[Double]]
        var destination: [Double]?
        var back: Bool
    }

    /// The route is kept on disk so it is still there after the app restarts
    /// with the phone switched off.
    private func saveRoute() {
        let saved = SavedRoute(points: route.map { [$0.x, $0.y] },
                               destination: destination.map { [$0.x, $0.y] },
                               back: goingBack)
        let file = routeFile
        io.async {
            if let data = try? JSONEncoder().encode(saved) {
                try? data.write(to: file, options: .atomic)
            }
        }
    }

    private func loadRoute() {
        guard let data = try? Data(contentsOf: routeFile),
              let saved = try? JSONDecoder().decode(SavedRoute.self, from: data) else { return }
        route = saved.points.compactMap { $0.count == 2 ? WorldPoint(x: $0[0], y: $0[1]) : nil }
        if let end = saved.destination, end.count == 2, !route.isEmpty {
            destination = WorldPoint(x: end[0], y: end[1])
        }
        goingBack = saved.back && !route.isEmpty
    }

    // MARK: Road travelled

    /// Where the watch has been on this trip, oldest first, a point every few
    /// metres. Drawn while the phone is gone, and the source of the way back.
    @Published private(set) var trail: [WorldPoint] = []
    private var trailAt: Date?
    private var trailSavedAt = Date.distantPast
    /// A new point once the position has moved this far from the last one.
    static let trailStepMetres = 15.0
    /// A trail with nothing added for this long belongs to an earlier trip.
    static let trailGapSeconds = 6.0 * 3600
    static let trailMaxPoints = 4000

    /// True when the position moved along the trail in the last ten minutes.
    var movedRecently: Bool {
        trailAt.map { Date().timeIntervalSince($0) < 600 } ?? false
    }

    /// Length of the trail in metres.
    var trailMetres: Double {
        guard trail.count > 1 else { return 0 }
        var total = 0.0
        for i in 1..<trail.count {
            total += hypot(trail[i].x - trail[i - 1].x, trail[i].y - trail[i - 1].y)
        }
        return total * MapStore.metresPerWorld(lat: trail[0].lat)
    }

    private func record(_ fix: GeoFix) {
        let here = WorldPoint(lat: fix.lat, lon: fix.lon)
        let now = Date()
        if let at = trailAt, now.timeIntervalSince(at) > MapStore.trailGapSeconds, !goingBack {
            trail = []
        }
        if let last = trail.last,
           hypot(here.x - last.x, here.y - last.y) * MapStore.metresPerWorld(lat: fix.lat) < MapStore.trailStepMetres {
            return
        }
        var next = trail
        next.append(here)
        if next.count > MapStore.trailMaxPoints {
            // Every other point: the whole trip stays, a little coarser.
            var thin = stride(from: 0, to: next.count, by: 2).map { next[$0] }
            if (next.count - 1) % 2 != 0 { thin.append(next[next.count - 1]) }
            next = thin
        }
        trail = next
        trailAt = now
        if now.timeIntervalSince(trailSavedAt) > 20 { saveTrail() }
    }

    func clearTrail() {
        trail = []
        trailAt = nil
        saveTrail()
    }

    private struct SavedTrail: Codable {
        var points: [[Double]]
        var at: Date?
    }

    func saveTrail() {
        trailSavedAt = Date()
        let saved = SavedTrail(points: trail.map { [$0.x, $0.y] }, at: trailAt)
        let file = trailFile
        io.async {
            if let data = try? JSONEncoder().encode(saved) {
                try? data.write(to: file, options: .atomic)
            }
        }
    }

    private func loadTrail() {
        guard let data = try? Data(contentsOf: trailFile),
              let saved = try? JSONDecoder().decode(SavedTrail.self, from: data) else { return }
        trail = saved.points.compactMap { $0.count == 2 ? WorldPoint(x: $0[0], y: $0[1]) : nil }
        trailAt = saved.at
    }

    /// Metres on the ground for one world unit (the whole map width) at this latitude.
    static func metresPerWorld(lat: Double) -> Double {
        40_075_016.686 * cos(lat * .pi / 180)
    }

    // MARK: Input from the phone

    /// Payload: z, x (4 bytes), y (4 bytes), status, then the image (PNG or JPEG).
    func receive(tile payload: Data) {
        let bytes = [UInt8](payload.prefix(10))
        guard bytes.count == 10 else { return }
        let x = (Int(bytes[1]) << 24) | (Int(bytes[2]) << 16) | (Int(bytes[3]) << 8) | Int(bytes[4])
        let y = (Int(bytes[5]) << 24) | (Int(bytes[6]) << 16) | (Int(bytes[7]) << 8) | Int(bytes[8])
        let key = TileKey(z: Int(bytes[0]), x: x, y: y)
        pending[key] = nil
        let fetching = inFlight.removeValue(forKey: key) != nil
        defer { if fetching { pump() } }
        let data = Data(payload.dropFirst(10))
        // Status: 0 = final, 1 = could not be downloaded, 2 = stand-in until the sharp one is ready.
        guard bytes[9] != 1, let image = UIImage(data: data) else {
            unavailable[key] = Date()
            if !tileProblem { tileProblem = true }
            if fetching { retry(key) }
            return
        }
        if bytes[9] == 2 {
            if fetching && (tries[key] ?? 0) >= 2 {
                // The sharp map server keeps failing: for offline use an
                // ordinary tile is much better than none.
                provisional[key] = nil
                keep(key, data, counted: true)
            } else {
                // Shown now, not kept: the sharp tile is asked for again in a moment.
                provisional[key] = Date()
                if fetching { retry(key) }
            }
        } else {
            provisional[key] = nil
            if fetching || kept.contains(key) {
                keep(key, data, counted: fetching)
            } else {
                try? data.write(to: folder.appendingPathComponent(key.fileName), options: .atomic)
                onDisk.insert(key)
            }
        }
        remember(key, image)
        unavailable[key] = nil
        if tileProblem { tileProblem = false }
        revision += 1
        ensureTiles()
    }

    /// The link dropped: requests that were on their way will never be answered.
    func linkLost() {
        pending.removeAll()
        for key in inFlight.keys { queue.append(key) }
        inFlight.removeAll()
    }

    // MARK: Tiles

    private func stored(_ key: TileKey) -> Bool {
        kept.contains(key) || onDisk.contains(key)
    }

    private func file(for key: TileKey) -> URL {
        (kept.contains(key) ? keepFolder : folder).appendingPathComponent(key.fileName)
    }

    /// A tile from memory or disk, or nil while it is still missing. Never talks to the phone.
    func image(for key: TileKey) -> UIImage? {
        if let hit = memory[key] { return hit }
        guard stored(key) else { return nil }
        guard let data = try? Data(contentsOf: file(for: key)),
              let image = UIImage(data: data) else {
            onDisk.remove(key)
            kept.remove(key)
            return nil
        }
        remember(key, image)
        return image
    }

    /// Whether there is anything to draw in the middle of the screen: the tile
    /// at this zoom, or a coarser one that can be magnified in its place.
    func hasMap() -> Bool {
        guard let world = viewPoint else { return false }
        let level = tileZoom
        let scale = Double(1 << level)
        let tx = Int(floor(world.x * scale))
        let ty = Int(floor(world.y * scale))
        var up = 0
        while up <= MapStore.magnifyLevels && level - up >= 0 {
            let key = TileKey(z: level - up, x: tx >> up, y: ty >> up)
            if memory[key] != nil || stored(key) { return true }
            up += 1
        }
        return false
    }

    private func remember(_ key: TileKey, _ image: UIImage) {
        memory[key] = image
        recent.removeAll { $0 == key }
        recent.append(key)
        while recent.count > 48 {
            let dropped = recent.removeFirst()
            memory[dropped] = nil
            provisional[dropped] = nil
        }
    }

    /// Requests the tiles around the middle of the screen that are not cached
    /// yet, nearest first, a few at a time: Bluetooth is slow and turns come first.
    func ensureTiles() {
        guard let world = viewPoint else { return }
        let now = Date()
        pending = pending.filter { now.timeIntervalSince($0.value) < 30 }
        let level = tileZoom
        let scale = Double(1 << level)
        let cx = world.x * scale
        let cy = world.y * scale
        // Half a watch screen plus a margin, in tiles. A turned map shows up to
        // half the screen diagonal in every direction.
        let reachX = (rotation == 0 ? 116.0 : 160.0) / Double(MapStore.tilePoints)
        let reachY = (rotation == 0 ? 136.0 : 160.0) / Double(MapStore.tilePoints)
        let limit = 1 << level
        var wanted: [(key: TileKey, distance: Double)] = []
        for tx in Int(floor(cx - reachX))...Int(floor(cx + reachX)) {
            for ty in Int(floor(cy - reachY))...Int(floor(cy + reachY)) {
                guard tx >= 0, ty >= 0, tx < limit, ty < limit else { continue }
                let key = TileKey(z: level, x: tx, y: ty)
                if pending[key] != nil || inFlight[key] != nil { continue }
                // A stand-in is on screen: ask for the sharp tile once it has had time to be made.
                let standIn = provisional[key].map { now.timeIntervalSince($0) > MapStore.provisionalSeconds } ?? false
                if (memory[key] != nil || stored(key)) && !standIn { continue }
                // A tile the phone could not download is asked for again shortly.
                if let since = unavailable[key], now.timeIntervalSince(since) < 6 { continue }
                let dx = Double(tx) + 0.5 - cx
                let dy = Double(ty) + 0.5 - cy
                wanted.append((key, dx * dx + dy * dy))
            }
        }
        for item in wanted.sorted(by: { $0.distance < $1.distance }) {
            if pending.count >= 3 { break }
            if requestTile?(item.key) == true {
                pending[item.key] = now
            }
        }
    }

    /// Keeps the tile cache from growing without bound. Offline tiles live
    /// in another folder and are never touched here.
    private static func prune(_ folder: URL) {
        let keys: [URLResourceKey] = [.contentModificationDateKey]
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: folder, includingPropertiesForKeys: keys) else { return }
        guard files.count > 1600 else { return }
        let dated = files.map { url -> (URL, Date) in
            let date = (try? url.resourceValues(forKeys: Set(keys)))?.contentModificationDate ?? .distantPast
            return (url, date)
        }
        for (url, _) in dated.sorted(by: { $0.1 < $1.1 }).prefix(files.count - 1200) {
            try? FileManager.default.removeItem(at: url)
        }
    }

    // MARK: Offline map

    /// Tile levels saved for offline use: crown zoom 13 to 16. Closer zooms
    /// magnify level 17, which `drawSubstitute` in MapPage does by itself.
    static let offlineLevels = 14...17
    /// Radius of the area saved around the position.
    static let offlineRadiusMetres = 5000.0
    /// Width of the strip saved on each side of the route.
    static let corridorMetres = 400.0
    /// More than this is too much for Bluetooth and for the free map servers.
    static let offlineMaxTiles = 8000

    @Published private(set) var keptCount = 0
    @Published private(set) var keptBytes = 0
    /// Tiles in the running download, 0 when none is running.
    @Published private(set) var downloadTotal = 0
    @Published private(set) var downloadDone = 0
    @Published private(set) var downloadFailed = 0
    /// One line about the last download, for the status page.
    @Published private(set) var downloadNote = ""

    private var queue: [TileKey] = []
    private var queueHead = 0
    private var inFlight: [TileKey: Date] = [:]
    private var tries: [TileKey: Int] = [:]
    private var downloadTimer: Timer?
    private var offlineCleared = false

    /// Saves the map within a few kilometres of the position.
    func downloadAround() {
        guard let fix = fix else { return }
        let here = WorldPoint(lat: fix.lat, lon: fix.lon)
        var keys = MapStore.tiles(near: [here], radiusMetres: MapStore.offlineRadiusMetres, lat: fix.lat)
        // Nearest first within each level, so a download cut short still covers where you are.
        keys.sort { a, b in
            if a.z != b.z { return a.z < b.z }
            let scale = Double(1 << a.z)
            func distance(_ k: TileKey) -> Double {
                let dx = Double(k.x) + 0.5 - here.x * scale
                let dy = Double(k.y) + 0.5 - here.y * scale
                return dx * dx + dy * dy
            }
            return distance(a) < distance(b)
        }
        startDownload(keys)
    }

    /// Saves a strip of map along the route.
    func downloadAlongRoute() {
        guard route.count > 1 else { return }
        let lat = route[0].lat
        let step = MapStore.corridorMetres / MapStore.metresPerWorld(lat: lat)
        let points = MapStore.samples(route, every: step)
        startDownload(MapStore.tiles(near: points, radiusMetres: MapStore.corridorMetres, lat: lat))
    }

    func stopDownload() {
        downloadTimer?.invalidate()
        downloadTimer = nil
        queue = []
        queueHead = 0
        inFlight.removeAll()
        tries.removeAll()
        if downloadTotal != 0 { downloadTotal = 0 }
    }

    func clearOffline() {
        stopDownload()
        offlineCleared = true
        kept.removeAll()
        keptCount = 0
        keptBytes = 0
        downloadNote = ""
        memory.removeAll()
        recent.removeAll()
        provisional.removeAll()
        // Moved aside at once and deleted later: a download started right
        // after this writes into a fresh folder that nothing is about to wipe.
        let trash = keepFolder.deletingLastPathComponent()
            .appendingPathComponent("offline-trash-\(UUID().uuidString)", isDirectory: true)
        if (try? FileManager.default.moveItem(at: keepFolder, to: trash)) != nil {
            io.async {
                try? FileManager.default.removeItem(at: trash)
            }
        } else {
            let names = (try? FileManager.default.contentsOfDirectory(atPath: keepFolder.path)) ?? []
            for name in names {
                try? FileManager.default.removeItem(at: keepFolder.appendingPathComponent(name))
            }
        }
        try? FileManager.default.createDirectory(at: keepFolder, withIntermediateDirectories: true)
        revision += 1
    }

    private func startDownload(_ keys: [TileKey]) {
        stopDownload()
        var list = keys
        var note = ""
        if list.count > MapStore.offlineMaxTiles {
            list = Array(list.prefix(MapStore.offlineMaxTiles))
            note = "Vùng quá lớn: chỉ tải \(MapStore.offlineMaxTiles) ô đầu. "
        }
        queue = list
        downloadTotal = list.count
        downloadDone = 0
        downloadFailed = 0
        downloadNote = note
        guard !list.isEmpty else { return }
        downloadTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.pump()
        }
        pump()
    }

    /// Keeps three requests on their way at all times. Tiles already saved are
    /// skipped, and tiles in the cache are moved over instead of fetched again.
    private func pump() {
        guard downloadTotal > 0 else { return }
        let now = Date()
        for (key, since) in inFlight where now.timeIntervalSince(since) > 40 {
            inFlight[key] = nil
            retry(key)
        }
        while inFlight.count < 3 && queueHead < queue.count {
            let key = queue[queueHead]
            if kept.contains(key) {
                queueHead += 1
                downloadDone += 1
                continue
            }
            if onDisk.contains(key) && provisional[key] == nil {
                queueHead += 1
                adopt(key)
                continue
            }
            // Link down: wait here and carry on once it is back.
            guard requestTile?(key) == true else { return }
            queueHead += 1
            inFlight[key] = now
        }
        if queueHead >= queue.count && inFlight.isEmpty {
            let failed = downloadFailed
            stopDownload()
            downloadNote += failed > 0 ? "Xong, \(failed) ô không tải được." : "Xong."
        }
    }

    private func retry(_ key: TileKey) {
        let count = (tries[key] ?? 0) + 1
        tries[key] = count
        if count < 4 {
            queue.append(key)
        } else {
            downloadFailed += 1
            downloadDone += 1
        }
    }

    /// Saves a downloaded tile for offline use.
    private func keep(_ key: TileKey, _ data: Data, counted: Bool) {
        do {
            try data.write(to: keepFolder.appendingPathComponent(key.fileName), options: .atomic)
        } catch {
            stopDownload()
            downloadNote = "Không lưu được: watch hết chỗ trống?"
            return
        }
        if onDisk.remove(key) != nil {
            try? FileManager.default.removeItem(at: folder.appendingPathComponent(key.fileName))
        }
        if kept.insert(key).inserted {
            keptBytes += data.count
            keptCount = kept.count
        }
        if counted && downloadTotal > 0 { downloadDone += 1 }
    }

    /// Moves a tile from the cache into the offline folder.
    private func adopt(_ key: TileKey) {
        let from = folder.appendingPathComponent(key.fileName)
        let to = keepFolder.appendingPathComponent(key.fileName)
        let size = (try? from.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
        try? FileManager.default.removeItem(at: to)
        do {
            try FileManager.default.moveItem(at: from, to: to)
            onDisk.remove(key)
            kept.insert(key)
            keptBytes += size
            keptCount = kept.count
            downloadDone += 1
        } catch {
            // The cached file is gone or unreadable: fetch it like any other.
            onDisk.remove(key)
            queue.append(key)
        }
    }

    /// Every tile at the offline levels within `radiusMetres` of any of the points.
    static func tiles(near points: [WorldPoint], radiusMetres: Double, lat: Double) -> [TileKey] {
        let reach = radiusMetres / metresPerWorld(lat: lat)
        var seen = Set<TileKey>()
        var out: [TileKey] = []
        for level in offlineLevels {
            let scale = Double(1 << level)
            let limit = (1 << level) - 1
            for point in points {
                let x0 = max(Int(floor((point.x - reach) * scale)), 0)
                let x1 = min(Int(floor((point.x + reach) * scale)), limit)
                let y0 = max(Int(floor((point.y - reach) * scale)), 0)
                let y1 = min(Int(floor((point.y + reach) * scale)), limit)
                guard x0 <= x1, y0 <= y1 else { continue }
                for tx in x0...x1 {
                    for ty in y0...y1 {
                        let key = TileKey(z: level, x: tx, y: ty)
                        if seen.insert(key).inserted { out.append(key) }
                    }
                }
            }
        }
        return out
    }

    /// Points along a line, no farther apart than `step` (world units).
    static func samples(_ line: [WorldPoint], every step: Double) -> [WorldPoint] {
        guard var previous = line.first, step > 0 else { return line }
        var out = [previous]
        for point in line.dropFirst() {
            let length = hypot(point.x - previous.x, point.y - previous.y)
            let parts = max(Int(ceil(length / step)), 1)
            for i in 1...parts {
                let t = Double(i) / Double(parts)
                out.append(WorldPoint(x: previous.x + (point.x - previous.x) * t,
                                      y: previous.y + (point.y - previous.y) * t))
            }
            previous = point
        }
        return out
    }

    // MARK: Route matching

    /// A position farther than this from the route is off it and moves nothing.
    static let onRouteMetres = 35.0
    /// Segments looked at ahead of the last match before the rest of the route,
    /// so a road that passes the same place twice is not skipped.
    static let routeWindow = 80

    /// Matches the position to the route ahead and moves the cut forward.
    private func advanceRoute(_ fix: GeoFix) {
        guard route.count > 1, routeDone < route.count - 1 else { return }
        let here = WorldPoint(lat: fix.lat, lon: fix.lon)
        let worldMetres = MapStore.metresPerWorld(lat: fix.lat)
        guard worldMetres > 0 else { return }
        let reach = MapStore.onRouteMetres / worldMetres
        let last = route.count - 2

        func nearest(_ range: ClosedRange<Int>) -> (index: Int, t: Double, point: WorldPoint)? {
            var best: (index: Int, t: Double, point: WorldPoint)?
            var bestDistance = reach * reach
            for i in range {
                let a = route[i], b = route[i + 1]
                let dx = b.x - a.x, dy = b.y - a.y
                let length = dx * dx + dy * dy
                var t = length > 0 ? ((here.x - a.x) * dx + (here.y - a.y) * dy) / length : 0
                t = min(max(t, 0), 1)
                let p = WorldPoint(x: a.x + dx * t, y: a.y + dy * t)
                let d = (here.x - p.x) * (here.x - p.x) + (here.y - p.y) * (here.y - p.y)
                if d < bestDistance {
                    bestDistance = d
                    best = (i, t, p)
                }
            }
            return best
        }

        let windowEnd = min(last, routeDone + MapStore.routeWindow)
        var match = nearest(routeDone...windowEnd)
        if match == nil && windowEnd < last {
            match = nearest((windowEnd + 1)...last)
        }
        guard let found = match else { return }
        // Never backwards: GPS jitter must not bring the road behind back.
        if found.index < routeDone || (found.index == routeDone && found.t <= routeT) { return }
        routeDone = found.index
        routeT = found.t
        routeCut = found.point
    }

    /// Decodes a precision 6 encoded polyline into world coordinates.
    static func decode(_ encoded: String) -> [WorldPoint] {
        var out: [WorldPoint] = []
        let bytes = Array(encoded.utf8)
        var index = 0
        var lat = 0
        var lon = 0

        func next() -> Int? {
            var shift = 0
            var result = 0
            while index < bytes.count {
                let b = Int(bytes[index]) - 63
                index += 1
                result |= (b & 0x1f) << shift
                shift += 5
                if b < 0x20 {
                    return (result & 1) != 0 ? ~(result >> 1) : (result >> 1)
                }
            }
            return nil
        }

        while index < bytes.count {
            guard let dLat = next(), let dLon = next() else { break }
            lat += dLat
            lon += dLon
            out.append(WorldPoint(lat: Double(lat) / 1e6, lon: Double(lon) / 1e6))
        }
        return out
    }
}
