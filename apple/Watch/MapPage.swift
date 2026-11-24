import SwiftUI
import UIKit
import WatchKit

/// Where things land on screen. The middle of the screen shows one point of
/// the map (the phone's position, or wherever the map was dragged to) and
/// everything else (tiles, route, destination) is placed relative to it at the
/// same zoom, so they always move and scale together.
private struct MapFrame {
    let zoom: Int
    /// The point in the middle of the screen, in tile units at this zoom.
    let cx: Double
    let cy: Double
    let midX: Double
    let midY: Double
    let tile: Double
    /// Shift that puts the tile corners on whole pixels, so an unturned map is
    /// copied to the screen pixel for pixel instead of being resampled (blurred).
    let snapX: Double
    let snapY: Double
    /// The compass direction at the top of the screen, in radians clockwise from north.
    let turn: Double

    init(zoom: Int, centre: WorldPoint, size: CGSize, rotation: Double) {
        self.zoom = zoom
        let scale = Double(1 << zoom)
        cx = centre.x * scale
        cy = centre.y * scale
        midX = Double(size.width) / 2
        midY = Double(size.height) / 2
        tile = Double(MapStore.tilePoints)
        turn = rotation * .pi / 180
        if rotation == 0 {
            let pixels = Double(WKInterfaceDevice.current().screenScale)
            let left = midX - cx * tile
            let top = midY - cy * tile
            snapX = (left * pixels).rounded() / pixels - left
            snapY = (top * pixels).rounded() / pixels - top
        } else {
            snapX = 0
            snapY = 0
        }
    }

    /// Position of a point given in tile units, before the map is turned.
    /// Tiles and the route are drawn here, inside a turned drawing context.
    func flat(_ tx: Double, _ ty: Double) -> CGPoint {
        CGPoint(x: midX + (tx - cx) * tile + snapX, y: midY + (ty - cy) * tile + snapY)
    }

    /// Where that point ends up on the screen once the map is turned.
    func screen(_ tx: Double, _ ty: Double) -> CGPoint {
        let dx = (tx - cx) * tile + snapX
        let dy = (ty - cy) * tile + snapY
        // Turning the map by -turn puts the compass direction `turn` at the top.
        let c = cos(-turn)
        let s = sin(-turn)
        return CGPoint(x: midX + dx * c - dy * s, y: midY + dx * s + dy * c)
    }
}

/// The live map: tiles, the route line, the phone's position, and the next
/// turn on top while a route is being followed. The crown zooms or turns the
/// map, and in move mode a finger drags it.
struct MapPage: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var ble: BLEManager
    @EnvironmentObject var map: MapStore

    /// The crown only reports a running total; what it does is worked out from the change.
    @State private var crown = 0.0
    @State private var lastCrown = 0.0
    @State private var zoomTurn = 0.0
    /// The crown turns the map instead of zooming.
    @State private var crownTurns = false
    /// A finger drags the map. Off by default, because the same swipe changes pages.
    @State private var moving = false
    @State private var dragStart: WorldPoint?

    /// Degrees the map turns for the crown movement that zooms by one level.
    private static let degreesPerStep = 30.0

    var body: some View {
        ZStack {
            if let fix = map.fix {
                // `revision` changes when a tile arrives, which redraws the canvas.
                let revision = map.revision
                Canvas { context, size in
                    _ = revision
                    draw(&context, size: size, fix: fix)
                }
                .ignoresSafeArea()
                .gesture(
                    DragGesture(minimumDistance: 2)
                        .onChanged { value in
                            drag(by: value.translation, fix: fix)
                        }
                        .onEnded { _ in
                            dragStart = nil
                        },
                    including: moving ? .all : .none
                )
            } else {
                waiting
            }
            overlays
            if map.fix != nil {
                controls
            }
        }
        .focusable(true)
        .digitalCrownRotation(
            $crown, from: -100_000.0, through: 100_000.0,
            sensitivity: .low, isContinuous: true, isHapticFeedbackEnabled: false
        )
        .onChange(of: crown) { value in
            crownMoved(to: value)
        }
        .onAppear {
            lastCrown = crown
            zoomTurn = 0
        }
    }

    // MARK: Crown and finger

    private func crownMoved(to value: Double) {
        let change = value - lastCrown
        lastCrown = value
        // A jump this large is the counter wrapping around, not the crown.
        guard abs(change) < 50 else { return }
        if crownTurns {
            map.headingUp = false
            var next = (map.rotation + change * MapPage.degreesPerStep).truncatingRemainder(dividingBy: 360)
            if next < 0 { next += 360 }
            map.rotation = next
            return
        }
        zoomTurn += change
        while zoomTurn >= 1 {
            zoomTurn -= 1
            step(zoom: 1)
        }
        while zoomTurn <= -1 {
            zoomTurn += 1
            step(zoom: -1)
        }
    }

    private func step(zoom change: Int) {
        let next = map.zoom + change
        guard MapStore.zoomRange.contains(next) else {
            zoomTurn = 0
            return
        }
        map.zoom = next
        WKInterfaceDevice.current().play(.click)
    }

    /// Moves the map under the finger: the point that was touched stays under it.
    private func drag(by translation: CGSize, fix: GeoFix) {
        let start = dragStart ?? map.viewPoint ?? WorldPoint(lat: fix.lat, lon: fix.lon)
        if dragStart == nil { dragStart = start }
        // The finger moved on the screen; turn that back into map directions.
        let turn = map.rotation * .pi / 180
        let tx = Double(translation.width)
        let ty = Double(translation.height)
        let dx = tx * cos(turn) - ty * sin(turn)
        let dy = tx * sin(turn) + ty * cos(turn)
        let world = Double(MapStore.tilePoints) * Double(1 << map.tileZoom)
        map.center = WorldPoint(x: min(max(start.x - dx / world, 0), 1),
                                y: min(max(start.y - dy / world, 0), 1))
    }

    private func recentre() {
        dragStart = nil
        map.center = nil
        moving = false
    }

    /// North up and travel direction up, in turn. A map turned by hand goes back to north first.
    private func compassTapped() {
        if map.rotation != 0 || map.headingUp {
            map.headingUp = false
            map.rotation = 0
        } else {
            map.headingUp = true
            if let bearing = map.fix?.bearing, bearing >= 0 {
                map.rotation = bearing
            }
        }
    }

    // MARK: Views on top of the map

    private var waiting: some View {
        VStack(spacing: 6) {
            Image(systemName: ble.connected ? "location.circle" : "antenna.radiowaves.left.and.right")
                .font(.title2)
                .foregroundColor(ble.connected ? .blue : .orange)
            Text(ble.connected ? "Đang chờ vị trí từ điện thoại" : ble.status)
                .font(.footnote)
                .multilineTextAlignment(.center)
            if ble.connected {
                Text("Bật GPS và cấp quyền vị trí cho AW Bridge trên điện thoại")
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(.horizontal, 6)
    }

    /// One line that says why the map is not complete, or nil when all is well.
    private var notice: String? {
        guard map.fix != nil else { return nil }
        if !ble.connected { return "Mất kết nối điện thoại" }
        if map.tileProblem { return "Điện thoại chưa tải được bản đồ. Mở AW Bridge trên điện thoại để xem lý do." }
        if !map.hasMap() { return "Đang tải bản đồ…" }
        if map.stale { return "Vị trí chưa cập nhật" }
        if model.nav != nil && map.route.isEmpty {
            return "Chưa có đường xanh: trong Google Maps bấm Chia sẻ > AW Bridge"
        }
        return nil
    }

    private var overlays: some View {
        VStack(spacing: 3) {
            // Small things at the top: arrival time and the ruler.
            HStack(alignment: .top) {
                if let nav = model.nav, !nav.eta.isEmpty {
                    Text(nav.eta)
                        .font(.system(size: 11, weight: .semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.black.opacity(0.7))
                        .cornerRadius(8)
                }
                Spacer(minLength: 0)
                if let fix = map.fix {
                    VStack(alignment: .trailing, spacing: 1) {
                        scaleBar(lat: fix.lat)
                        Text("© OpenStreetMap")
                            .font(.system(size: 8))
                            .foregroundColor(.black)
                    }
                    .padding(.horizontal, 3)
                    .padding(.vertical, 1)
                    .background(Color.white.opacity(0.7))
                }
            }
            if let notice = notice {
                Text(notice)
                    .font(.system(size: 10, weight: .semibold))
                    .multilineTextAlignment(.center)
                    .lineLimit(3)
                    .minimumScaleFactor(0.8)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Color.black.opacity(0.75))
                    .cornerRadius(9)
            }
            Spacer(minLength: 0)
            if let nav = model.nav {
                // One slim row at the bottom: the road ahead is at the top of the
                // screen and stays clear. The turns after this one are on the
                // directions page.
                HStack(spacing: 5) {
                    if let icon = model.icon {
                        Image(uiImage: icon)
                            .renderingMode(.template)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 22, height: 22)
                            .foregroundColor(.green)
                    }
                    Text(nav.distance)
                        .font(.system(size: 16, weight: .bold, design: .rounded))
                        .lineLimit(1)
                        .fixedSize()
                    Text(nav.instruction)
                        .font(.system(size: 11, weight: .semibold))
                        .lineLimit(2)
                        .minimumScaleFactor(0.8)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 7)
                .padding(.vertical, 3)
                .background(Color.black.opacity(0.7))
                .cornerRadius(10)
            }
        }
        .padding(.horizontal, 4)
        .padding(.bottom, 2)
        .allowsHitTesting(false)
    }

    /// Two small columns of buttons above the bottom row. Left: where the map
    /// looks (back to my position, drag mode). Right: which way is up and what
    /// the crown does.
    private var controls: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 0)
            HStack(alignment: .bottom, spacing: 0) {
                VStack(spacing: 0) {
                    if map.center != nil {
                        control("location.fill", on: true) {
                            recentre()
                        }
                    }
                    control("arrow.up.and.down.and.arrow.left.and.right", on: moving) {
                        moving.toggle()
                    }
                }
                Spacer(minLength: 0)
                VStack(spacing: 0) {
                    control("location.north.fill", on: map.headingUp, turned: -map.rotation) {
                        compassTapped()
                    }
                    control(crownTurns ? "arrow.triangle.2.circlepath" : "plus.magnifyingglass", on: crownTurns) {
                        crownTurns.toggle()
                    }
                }
            }
        }
        // Above the turn banner while it is there.
        .padding(.bottom, model.nav != nil ? 40 : 4)
    }

    private func control(_ symbol: String, on: Bool, turned degrees: Double = 0,
                         action: @escaping () -> Void) -> some View {
        Image(systemName: symbol)
            .font(.system(size: 13, weight: .bold))
            .rotationEffect(.degrees(degrees))
            .foregroundColor(on ? .white : .black)
            .frame(width: 29, height: 29)
            .background(Circle().fill(on ? Color.blue : Color.white.opacity(0.9)))
            .overlay(Circle().stroke(Color.black.opacity(0.3), lineWidth: 0.5))
            // The touch area is larger than the circle that is drawn.
            .frame(width: 36, height: 34)
            .contentShape(Rectangle())
            .onTapGesture {
                WKInterfaceDevice.current().play(.click)
                action()
            }
    }

    /// A ruler for the current zoom, so a turn of the crown shows what it did.
    private func scaleBar(lat: Double) -> some View {
        let metresPerPoint = 156_543.034 * cos(lat * .pi / 180) / Double(1 << map.zoom)
        let steps: [Double] = [10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000]
        let length = steps.last(where: { $0 / metresPerPoint <= 60 }) ?? 10
        let label = length >= 1000 ? "\(Int(length / 1000)) km" : "\(Int(length)) m"
        return HStack(spacing: 3) {
            Text(label)
                .font(.system(size: 9, weight: .semibold))
                .foregroundColor(.black)
            Rectangle()
                .fill(Color.black)
                .frame(width: CGFloat(length / metresPerPoint), height: 2)
        }
    }

    // MARK: Drawing

    private func draw(_ context: inout GraphicsContext, size: CGSize, fix: GeoFix) {
        // Tiles are one level finer than the zoom the user picked and drawn at
        // half the size: the same map, pixel for pixel on this screen.
        let zoom = map.tileZoom
        let scale = Double(1 << zoom)
        let here = WorldPoint(lat: fix.lat, lon: fix.lon)
        let frame = MapFrame(zoom: zoom, centre: map.viewPoint ?? here, size: size, rotation: map.rotation)
        let limit = 1 << zoom
        let turned = map.rotation != 0

        context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(white: 0.16)))

        // The map itself is drawn turned around the middle of the screen.
        var ground = context
        if turned {
            ground.translateBy(x: CGFloat(frame.midX), y: CGFloat(frame.midY))
            ground.rotate(by: .radians(-frame.turn))
            ground.translateBy(x: CGFloat(-frame.midX), y: CGFloat(-frame.midY))
        }

        // A turned screen reaches as far as half its diagonal in every direction.
        let diagonal = (frame.midX * frame.midX + frame.midY * frame.midY).squareRoot()
        let reachX = (turned ? diagonal : frame.midX) / frame.tile
        let reachY = (turned ? diagonal : frame.midY) / frame.tile
        // Unturned tiles sit exactly on the pixel grid and need no overlap. Turned
        // ones are resampled anyway, and half a point of overlap hides the seams.
        let side = CGFloat(frame.tile) + (turned ? 0.5 : 0)
        for tx in Int(floor(frame.cx - reachX))...Int(floor(frame.cx + reachX)) {
            for ty in Int(floor(frame.cy - reachY))...Int(floor(frame.cy + reachY)) {
                guard tx >= 0, ty >= 0, tx < limit, ty < limit else { continue }
                let origin = frame.flat(Double(tx), Double(ty))
                let rect = CGRect(x: origin.x, y: origin.y, width: side, height: side)
                if let image = map.image(for: TileKey(z: zoom, x: tx, y: ty)) {
                    ground.draw(Image(uiImage: image), in: rect)
                } else {
                    drawSubstitute(&ground, frame: frame, tx: tx, ty: ty, rect: rect)
                }
            }
        }

        if map.route.count > 1 {
            var line = Path()
            var started = false
            for point in map.route {
                let p = frame.flat(point.x * scale, point.y * scale)
                if started {
                    line.addLine(to: p)
                } else {
                    line.move(to: p)
                    started = true
                }
            }
            let round = StrokeStyle(lineWidth: 9, lineCap: .round, lineJoin: .round)
            ground.stroke(line, with: .color(.white), style: round)
            ground.stroke(line, with: .color(Color(red: 0.12, green: 0.47, blue: 1.0)),
                          style: StrokeStyle(lineWidth: 5.5, lineCap: .round, lineJoin: .round))
            if let destination = map.destination {
                let p = frame.flat(destination.x * scale, destination.y * scale)
                ground.fill(Path(ellipseIn: CGRect(x: p.x - 7, y: p.y - 7, width: 14, height: 14)),
                            with: .color(.white))
                ground.fill(Path(ellipseIn: CGRect(x: p.x - 4.5, y: p.y - 4.5, width: 9, height: 9)),
                            with: .color(.red))
            }
        }

        // The phone's position: an arrow along the direction of travel, or a dot
        // when standing still. Grey when the position has stopped updating.
        let spot = frame.screen(here.x * scale, here.y * scale)
        let live = !map.stale && ble.connected
        let tint = live ? Color(red: 0.0, green: 0.48, blue: 1.0) : Color(white: 0.55)
        if fix.bearing >= 0 {
            var arrow = Path()
            arrow.move(to: CGPoint(x: 0, y: -16))
            arrow.addLine(to: CGPoint(x: 9.5, y: 8.5))
            arrow.addLine(to: CGPoint(x: 0, y: 3.5))
            arrow.addLine(to: CGPoint(x: -9.5, y: 8.5))
            arrow.closeSubpath()
            let transform = CGAffineTransform(translationX: spot.x, y: spot.y)
                .rotated(by: CGFloat((fix.bearing - map.rotation) * .pi / 180))
            let shape = arrow.applying(transform)
            context.stroke(shape, with: .color(.white), style: StrokeStyle(lineWidth: 3.5, lineJoin: .round))
            context.fill(shape, with: .color(tint))
        } else {
            let dot = Path(ellipseIn: CGRect(x: spot.x - 7, y: spot.y - 7, width: 14, height: 14))
            context.fill(dot, with: .color(tint))
            context.stroke(dot, with: .color(.white), lineWidth: 3)
        }
    }

    /// Fills the place of a tile that has not arrived yet with what is already
    /// cached at another zoom, at the right size and position, so the map
    /// stays under the route and the position marker while the crown is turned.
    private func drawSubstitute(_ context: inout GraphicsContext, frame: MapFrame,
                                tx: Int, ty: Int, rect: CGRect) {
        // Finer tiles, shrunk: four of them cover this one. Sharper than magnifying, so preferred when complete.
        let half = CGFloat(frame.tile / 2)
        var finer: [(dx: Int, dy: Int, image: UIImage)] = []
        for dx in 0...1 {
            for dy in 0...1 {
                let key = TileKey(z: frame.zoom + 1, x: tx * 2 + dx, y: ty * 2 + dy)
                if let image = map.image(for: key) {
                    finer.append((dx, dy, image))
                }
            }
        }
        func drawFiner(_ context: inout GraphicsContext) {
            for part in finer {
                context.draw(Image(uiImage: part.image),
                             in: CGRect(x: rect.minX + CGFloat(part.dx) * half,
                                        y: rect.minY + CGFloat(part.dy) * half,
                                        width: half + 0.5, height: half + 0.5))
            }
        }
        if finer.count == 4 {
            drawFiner(&context)
            return
        }
        // A coarser tile that covers this one, magnified and cut to this tile's square.
        var up = 1
        while up <= MapStore.magnifyLevels && frame.zoom - up >= 0 {
            let px = tx >> up
            let py = ty >> up
            if let image = map.image(for: TileKey(z: frame.zoom - up, x: px, y: py)) {
                let span = CGFloat(frame.tile) * CGFloat(1 << up)
                let origin = frame.flat(Double(px << up), Double(py << up))
                var layer = context
                layer.clip(to: Path(rect))
                layer.draw(Image(uiImage: image),
                           in: CGRect(x: origin.x, y: origin.y, width: span + 0.5, height: span + 0.5))
                return
            }
            up += 1
        }
        // Nothing coarser either: show the finer tiles that are there.
        drawFiner(&context)
    }
}
