import SwiftUI

/// Where the orb sits inside a Canvas: centre and outer radius (unit 1.0).
struct OrbFrame {
    let c: CGPoint
    let R: CGFloat

    init(size: CGSize) {
        c = CGPoint(x: size.width / 2, y: size.height / 2)
        // The orb body is smaller than its frame; the margin holds the coving.
        R = min(size.width, size.height) / 2 / CGFloat(1 + OrbGeometry.covingWidth)
    }

    /// Radius in points for a unit-space radius.
    func r(_ unit: Double) -> CGFloat { R * CGFloat(unit) }

    /// Point at unit-space radius and angle theta (0 = 12 o'clock, clockwise).
    func point(_ unit: Double, _ theta: Double) -> CGPoint {
        CGPoint(x: c.x + r(unit) * CGFloat(sin(theta)), y: c.y - r(unit) * CGFloat(cos(theta)))
    }
}

/// Static parts of the orb: plate, lip, colour ring. Geometry comes from
/// `OrbGeometry` (the 3x ring); paint copies the knob's ring-shade treatment.
enum OrbPainter {
    static let evenOdd = FillStyle(eoFill: true)

    static func circle(_ c: CGPoint, _ r: CGFloat) -> Path {
        Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r))
    }

    static func annulus(_ f: OrbFrame, _ inner: Double, _ outer: Double) -> Path {
        var p = circle(f.c, f.r(outer)); p.addPath(circle(f.c, f.r(inner))); return p
    }

    /// A polyline arc from theta0 to theta1 (clockwise from 12 o'clock) at a unit radius.
    static func arc(_ f: OrbFrame, _ unit: Double, from t0: Double, to t1: Double) -> Path {
        var p = Path()
        let steps = max(2, Int(abs(t1 - t0) / (.pi / 90)))
        for i in 0...steps {
            let pt = f.point(unit, t0 + (t1 - t0) * Double(i) / Double(steps))
            i == 0 ? p.move(to: pt) : p.addLine(to: pt)
        }
        return p
    }

    static func bounds(_ f: OrbFrame) -> (tl: CGPoint, br: CGPoint) {
        (CGPoint(x: f.c.x - f.R, y: f.c.y - f.R), CGPoint(x: f.c.x + f.R, y: f.c.y + f.R))
    }

    static func drawBody(_ ctx: GraphicsContext, _ f: OrbFrame, _ p: Palette) {
        let b = bounds(f)
        // Plate, then the moulded lip on top of it.
        ctx.fill(circle(f.c, f.R), with: .radialGradient(
            Gradient(colors: [p.plate.mixed(with: .white, p.isLight ? 0 : 0.05), p.plate.mixed(with: .black, p.isLight ? 0.08 : 0.4)]),
            center: CGPoint(x: f.c.x - f.R * 0.3, y: f.c.y - f.R * 0.35), startRadius: 0, endRadius: f.R * 1.4))
        drawCoving(ctx, f, p)

        // Colour ring: angular gradient through the three stops, inward fall-off,
        // a 160 degree shading pass and an inset shadow.
        ctx.drawLayer { l in
            l.clip(to: annulus(f, OrbGeometry.ringInner, OrbGeometry.ringOuter), style: evenOdd)
            let stops = p.ringStops + [p.ringStops[0]]
            l.fill(circle(f.c, f.R), with: .conicGradient(Gradient(colors: stops), center: f.c, angle: .degrees(-90)))
            l.fill(circle(f.c, f.R), with: .radialGradient(
                Gradient(stops: [
                    .init(color: .clear, location: 0),
                    .init(color: .black.opacity(0.5), location: OrbGeometry.ringInner),
                    .init(color: .clear, location: 0.74),
                    .init(color: .black.opacity(0.28), location: OrbGeometry.ringOuter),
                ]), center: f.c, startRadius: 0, endRadius: f.R))
            let a = 160.0 * .pi / 180
            let dx = CGFloat(sin(a)) * f.R, dy = -CGFloat(cos(a)) * f.R
            l.fill(circle(f.c, f.R), with: .linearGradient(
                Gradient(colors: [.white.opacity(p.isLight ? 0.10 : 0.16), .clear, .black.opacity(0.4)]),
                startPoint: CGPoint(x: f.c.x - dx, y: f.c.y - dy), endPoint: CGPoint(x: f.c.x + dx, y: f.c.y + dy)))
            l.drawLayer { s in
                s.addFilter(.blur(radius: f.R * 0.018))
                for unit in [OrbGeometry.ringInner, OrbGeometry.ringOuter] {
                    s.stroke(circle(f.c, f.r(unit)), with: .color(.black.opacity(0.7)), lineWidth: f.R * 0.035)
                }
            }
        }
    }
}

extension OrbPainter {
    /// A concave fillet from the ring's edge out into the background, with no hard lip.
    /// Concentric hairlines, darkest at the orb and fading to nothing at the background, each
    /// shaded top-left dark to bottom-right light (light from the top left falls on the far wall
    /// of a dip). Alpha-only, so it takes whatever the background is.
    static func drawCoving(_ ctx: GraphicsContext, _ f: OrbFrame, _ p: Palette) {
        let b = bounds(f)
        let inner = OrbGeometry.ringOuter, outer = OrbGeometry.outerRadius + OrbGeometry.covingWidth
        let steps = 40
        let width = (outer - inner) / Double(steps)
        for i in 0..<steps {
            let t = Double(i) / Double(steps - 1)            // 0 at the orb, 1 at the background
            let depth = pow(1 - t, 2.2)                       // steep at the orb, easing out
            let radius = inner + (Double(i) + 0.5) * width
            let shade = Gradient(colors: [
                .black.opacity((p.isLight ? 0.26 : 0.75) * depth),
                .black.opacity((p.isLight ? 0.05 : 0.18) * depth),
                .white.opacity((p.isLight ? 0.85 : 0.16) * depth),
            ])
            ctx.stroke(circle(f.c, f.r(radius)), with: .linearGradient(shade, startPoint: b.tl, endPoint: b.br),
                       lineWidth: f.R * CGFloat(width) * 1.4)
        }
    }
}
