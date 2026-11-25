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
}

/// Map state for the watch: the phone's position, the planned route and a
/// cache of map tiles. The watch has no internet, so every tile it lacks is
/// requested from the phone over Bluetooth and kept on disk afterwards.
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
    /// Every tile saved on disk, so drawing never has to probe the file system.
    private var onDisk = Set<TileKey>()
    private var recent: [TileKey] = []
    private var pending: [TileKey: Date] = [:]
    private var unavailable: [TileKey: Date] = [:]
    /// Tiles shown from a stand-in image, and since when.
    private var provisional: [TileKey: Date] = [:]
    private var fixAt: Date?
    private var fixAge = 0.0
    private var timer: Timer?
    private let folder: URL

    init() {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        folder = caches.appendingPathComponent("tiles", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let folder = self.folder
        DispatchQueue.global(qos: .utility).async { [weak self] in
            MapStore.prune(folder)
            let names = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
            // Tiles saved by earlier versions were the blurry kind: drop them.
            for name in names where TileKey(fileName: name) == nil {
                try? FileManager.default.removeItem(at: folder.appendingPathComponent(name))
            }
            let keys = Set(names.compactMap { TileKey(fileName: $0) })
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.onDisk.formUnion(keys)
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
        // Stale: the phone stopped reporting, or what it reports was measured long ago.
        var old = false
        if let since = fixAt {
            old = Date().timeIntervalSince(since) > 12 || fixAge > MapStore.oldFixSeconds
        }
        if old != stale { stale = old }
    }

    // MARK: Input from the phone

    /// `age` is how many seconds ago the phone measured this position.
    func update(fix: GeoFix, age: Double) {
        if self.fix != fix { self.fix = fix }
        if headingUp && fix.bearing >= 0 && rotation != fix.bearing {
            rotation = fix.bearing
        }
        fixAt = Date()
        fixAge = age
        let old = age > MapStore.oldFixSeconds
        if old != stale { stale = old }
        ensureTiles()
    }

    func setRoute(encoded: String, destination: (lat: Double, lon: Double)?) {
        route = MapStore.decode(encoded)
        if let destination = destination, !route.isEmpty {
            self.destination = WorldPoint(lat: destination.lat, lon: destination.lon)
        } else {
            self.destination = nil
        }
    }

    /// Payload: z, x (4 bytes), y (4 bytes), status, then the image (PNG or JPEG).
    func receive(tile payload: Data) {
        let bytes = [UInt8](payload.prefix(10))
        guard bytes.count == 10 else { return }
        let x = (Int(bytes[1]) << 24) | (Int(bytes[2]) << 16) | (Int(bytes[3]) << 8) | Int(bytes[4])
        let y = (Int(bytes[5]) << 24) | (Int(bytes[6]) << 16) | (Int(bytes[7]) << 8) | Int(bytes[8])
        let key = TileKey(z: Int(bytes[0]), x: x, y: y)
        pending[key] = nil
        let data = Data(payload.dropFirst(10))
        // Status: 0 = final, 1 = could not be downloaded, 2 = stand-in until the sharp one is ready.
        guard bytes[9] != 1, let image = UIImage(data: data) else {
            unavailable[key] = Date()
            if !tileProblem { tileProblem = true }
            return
        }
        if bytes[9] == 2 {
            // Shown now, not kept: the sharp tile is asked for again in a moment.
            provisional[key] = Date()
        } else {
            provisional[key] = nil
            try? data.write(to: folder.appendingPathComponent(key.fileName), options: .atomic)
            onDisk.insert(key)
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
    }

    // MARK: Tiles

    /// A tile from memory or disk, or nil while it is still missing. Never talks to the phone.
    func image(for key: TileKey) -> UIImage? {
        if let hit = memory[key] { return hit }
        guard onDisk.contains(key) else { return nil }
        guard let data = try? Data(contentsOf: folder.appendingPathComponent(key.fileName)),
              let image = UIImage(data: data) else {
            onDisk.remove(key)
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
            if memory[key] != nil || onDisk.contains(key) { return true }
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
                if pending[key] != nil { continue }
                // A stand-in is on screen: ask for the sharp tile once it has had time to be made.
                let standIn = provisional[key].map { now.timeIntervalSince($0) > MapStore.provisionalSeconds } ?? false
                if (memory[key] != nil || onDisk.contains(key)) && !standIn { continue }
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

    /// Keeps the tile cache from growing without bound.
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

    // MARK: Route

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
