import SwiftUI

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
                if map.fix != nil {
                    Text("© OpenStreetMap")
                        .font(.system(size: 8))
                        .foregroundColor(.black)
                        .padding(.horizontal, 3)
                        .background(Color.white.opacity(0.7))
                }
            }
        }
        .padding(.horizontal, 4)
        .padding(.bottom, 2)
        .allowsHitTesting(false)
    }

    private func draw(_ context: inout GraphicsContext, size: CGSize, fix: GeoFix) {
        let tile = MapStore.tilePoints
        let zoom = map.zoom
        let scale = Double(1 << zoom)
        let here = WorldPoint(lat: fix.lat, lon: fix.lon)
        let cx = here.x * scale
        let cy = here.y * scale
        let midX = size.width / 2
        let midY = size.height / 2
        let limit = 1 << zoom

        // Screen position of a point given in tile units at this zoom.
        func screen(_ tx: Double, _ ty: Double) -> CGPoint {
            CGPoint(x: midX + CGFloat(tx - cx) * tile, y: midY + CGFloat(ty - cy) * tile)
        }

        context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(white: 0.16)))

        let reachX = Double(midX / tile)
        let reachY = Double(midY / tile)
        for tx in Int(floor(cx - reachX))...Int(floor(cx + reachX)) {
            for ty in Int(floor(cy - reachY))...Int(floor(cy + reachY)) {
                guard tx >= 0, ty >= 0, tx < limit, ty < limit else { continue }
                guard let image = map.image(for: TileKey(z: zoom, x: tx, y: ty)) else { continue }
                let origin = screen(Double(tx), Double(ty))
                // Half a point of overlap hides the seams between tiles.
                context.draw(Image(uiImage: image),
                             in: CGRect(x: origin.x, y: origin.y, width: tile + 0.5, height: tile + 0.5))
            }
        }

        if map.route.count > 1 {
            var line = Path()
            var started = false
            for point in map.route {
                let p = screen(point.x * scale, point.y * scale)
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
                let p = screen(destination.x * scale, destination.y * scale)
                context.fill(Path(ellipseIn: CGRect(x: p.x - 7, y: p.y - 7, width: 14, height: 14)),
                             with: .color(.white))
                context.fill(Path(ellipseIn: CGRect(x: p.x - 4.5, y: p.y - 4.5, width: 9, height: 9)),
                             with: .color(.red))
            }
        }

        // The phone's position: an arrow along the direction of travel, or a dot when standing still.
        let centre = CGPoint(x: midX, y: midY)
        let blue = Color(red: 0.0, green: 0.48, blue: 1.0)
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
            context.fill(shape, with: .color(blue))
        } else {
            let dot = Path(ellipseIn: CGRect(x: centre.x - 7, y: centre.y - 7, width: 14, height: 14))
            context.fill(dot, with: .color(blue))
            context.stroke(dot, with: .color(.white), lineWidth: 3)
        }
    }
}
