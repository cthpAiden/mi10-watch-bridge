import SwiftUI
import UIKit

/// Where things land on screen: the phone's position sits in the middle and
/// everything else (tiles, route, destination) is placed relative to it at the
/// same zoom, so they always move and scale together.
private struct MapFrame {
    let zoom: Int
    /// The position in tile units at this zoom.
    let cx: Double
    let cy: Double
    let midX: CGFloat
    let midY: CGFloat
    let tile: CGFloat

    /// Screen position of a point given in tile units at this zoom.
    func screen(_ tx: Double, _ ty: Double) -> CGPoint {
        CGPoint(x: midX + CGFloat(tx - cx) * tile, y: midY + CGFloat(ty - cy) * tile)
    }
}

/// The live map: tiles, the route line, the phone's position in the middle,
/// and the next turn on top while a route is being followed.
struct MapPage: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var ble: BLEManager
    @EnvironmentObject var map: MapStore
    @State private var crown = 16.0

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
            } else {
                waiting
            }
            overlays
        }
        .focusable(true)
        .digitalCrownRotation(
            $crown, from: 13, through: 18, by: 1,
            sensitivity: .low, isContinuous: false, isHapticFeedbackEnabled: true
        )
        .onChange(of: crown) { value in
            map.zoom = Int(value.rounded())
        }
        .onAppear {
            // The page is rebuilt when it is swiped back to: start the crown at the zoom on screen.
            crown = Double(map.zoom)
        }
    }

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
        guard let fix = map.fix else { return nil }
        if !ble.connected { return "Mất kết nối điện thoại" }
        if map.tileProblem { return "Điện thoại chưa tải được bản đồ. Mở AW Bridge trên điện thoại để xem lý do." }
        if !map.hasMap(at: fix) { return "Đang tải bản đồ…" }
        if map.stale { return "Vị trí chưa cập nhật" }
        return nil
    }

    private var overlays: some View {
        VStack(spacing: 0) {
            if let nav = model.nav {
                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 6) {
                        if let icon = model.icon {
                            Image(uiImage: icon)
                                .renderingMode(.template)
                                .resizable()
                                .scaledToFit()
                                .frame(width: 26, height: 26)
                                .foregroundColor(.green)
                        }
                        Text(nav.distance)
                            .font(.system(size: 19, weight: .bold, design: .rounded))
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                    }
                    Text(nav.instruction)
                        .font(.system(size: 13, weight: .semibold))
                        .lineLimit(2)
                        .minimumScaleFactor(0.75)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 8)
                .padding(.vertical, 5)
                .background(Color.black.opacity(0.82))
                .cornerRadius(12)
            }
            Spacer(minLength: 0)
            if let notice = notice {
                Text(notice)
                    .font(.system(size: 11, weight: .semibold))
                    .multilineTextAlignment(.center)
                    .lineLimit(3)
                    .minimumScaleFactor(0.8)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.black.opacity(0.82))
                    .cornerRadius(10)
                    .padding(.bottom, 3)
            }
            HStack(alignment: .bottom) {
                if let nav = model.nav, !nav.eta.isEmpty {
                    Text(nav.eta)
                        .font(.system(size: 12, weight: .semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color.black.opacity(0.82))
                        .cornerRadius(10)
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
        }
        .padding(.horizontal, 4)
        .padding(.bottom, 2)
        .allowsHitTesting(false)
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

    private func draw(_ context: inout GraphicsContext, size: CGSize, fix: GeoFix) {
        let tile = MapStore.tilePoints
        let zoom = map.zoom
        let scale = Double(1 << zoom)
        let here = WorldPoint(lat: fix.lat, lon: fix.lon)
        let frame = MapFrame(zoom: zoom, cx: here.x * scale, cy: here.y * scale,
                             midX: size.width / 2, midY: size.height / 2, tile: tile)
        let limit = 1 << zoom

        context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(white: 0.16)))

        let reachX = Double(frame.midX / tile)
        let reachY = Double(frame.midY / tile)
        for tx in Int(floor(frame.cx - reachX))...Int(floor(frame.cx + reachX)) {
            for ty in Int(floor(frame.cy - reachY))...Int(floor(frame.cy + reachY)) {
                guard tx >= 0, ty >= 0, tx < limit, ty < limit else { continue }
                let origin = frame.screen(Double(tx), Double(ty))
                // Half a point of overlap hides the seams between tiles.
                let rect = CGRect(x: origin.x, y: origin.y, width: tile + 0.5, height: tile + 0.5)
                if let image = map.image(for: TileKey(z: zoom, x: tx, y: ty)) {
                    context.draw(Image(uiImage: image), in: rect)
                } else {
                    drawSubstitute(&context, frame: frame, tx: tx, ty: ty, rect: rect)
                }
            }
        }

        if map.route.count > 1 {
            var line = Path()
            var started = false
            for point in map.route {
                let p = frame.screen(point.x * scale, point.y * scale)
                if started {
                    line.addLine(to: p)
                } else {
                    line.move(to: p)
                    started = true
                }
            }
            let round = StrokeStyle(lineWidth: 9, lineCap: .round, lineJoin: .round)
            context.stroke(line, with: .color(.white), style: round)
            context.stroke(line, with: .color(Color(red: 0.12, green: 0.47, blue: 1.0)),
                           style: StrokeStyle(lineWidth: 5.5, lineCap: .round, lineJoin: .round))
            if let destination = map.destination {
                let p = frame.screen(destination.x * scale, destination.y * scale)
                context.fill(Path(ellipseIn: CGRect(x: p.x - 7, y: p.y - 7, width: 14, height: 14)),
                             with: .color(.white))
                context.fill(Path(ellipseIn: CGRect(x: p.x - 4.5, y: p.y - 4.5, width: 9, height: 9)),
                             with: .color(.red))
            }
        }

        // The phone's position: an arrow along the direction of travel, or a dot
        // when standing still. Grey when the position has stopped updating.
        let centre = CGPoint(x: frame.midX, y: frame.midY)
        let live = !map.stale && ble.connected
        let tint = live ? Color(red: 0.0, green: 0.48, blue: 1.0) : Color(white: 0.55)
        if fix.bearing >= 0 {
            var arrow = Path()
            arrow.move(to: CGPoint(x: 0, y: -16))
            arrow.addLine(to: CGPoint(x: 9.5, y: 8.5))
            arrow.addLine(to: CGPoint(x: 0, y: 3.5))
            arrow.addLine(to: CGPoint(x: -9.5, y: 8.5))
            arrow.closeSubpath()
            let transform = CGAffineTransform(translationX: centre.x, y: centre.y)
                .rotated(by: CGFloat(fix.bearing * .pi / 180))
            let shape = arrow.applying(transform)
            context.stroke(shape, with: .color(.white), style: StrokeStyle(lineWidth: 3.5, lineJoin: .round))
            context.fill(shape, with: .color(tint))
        } else {
            let dot = Path(ellipseIn: CGRect(x: centre.x - 7, y: centre.y - 7, width: 14, height: 14))
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
        let half = frame.tile / 2
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
                let span = frame.tile * CGFloat(1 << up)
                let origin = frame.screen(Double(px << up), Double(py << up))
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
