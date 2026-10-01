import SwiftUI

/// The dome (centre) and the unlock band around it.
extension OrbPainter {
    /// Machined face used when no camera is showing: the knob face gradient and
    /// a fine knurl, in the dark or light treatment.
    static func drawDomeFace(_ ctx: GraphicsContext, _ f: OrbFrame, _ p: Palette) {
        let r = f.r(OrbGeometry.domeOuter)
        let colors: [Color] = p.lightFace
            ? [Color(white: 0.86), Color(white: 0.97), Color(white: 0.78)]
            : [Color(white: 0.26), Color(white: 0.13), .black]
        let focus = CGPoint(x: f.c.x - r * 0.14, y: f.c.y - r * 0.30)
        ctx.fill(circle(f.c, r), with: .radialGradient(Gradient(colors: colors), center: focus, startRadius: 0, endRadius: r * 1.25))
        let ink: Color = p.lightFace ? .black : .white
        for i in 0..<180 {
            let theta = Double(i) * .pi / 90
            var tick = Path()
            tick.move(to: f.point(OrbGeometry.domeOuter * 0.90, theta))
            tick.addLine(to: f.point(OrbGeometry.domeOuter * 0.985, theta))
            ctx.stroke(tick, with: .color(ink.opacity(i.isMultiple(of: 2) ? (p.lightFace ? 0.05 : 0.07) : (p.lightFace ? 0.02 : 0.025))), lineWidth: 0.5)
        }
    }

    /// Drawn over the camera preview: unlock band, vignette, bezel, progress.
    static func drawDomeFront(_ ctx: GraphicsContext, _ f: OrbFrame, _ p: Palette, camera: Bool, progress: Double) {
        let b = bounds(f)
        let r = f.r(OrbGeometry.domeOuter)

        if camera {
            ctx.fill(circle(f.c, r), with: .radialGradient(
                Gradient(stops: [.init(color: .clear, location: 0.62), .init(color: .black.opacity(0.6), location: 1)]),
                center: f.c, startRadius: 0, endRadius: r))
        }
        // Inner shadow where the dome sits in its well.
        ctx.drawLayer { l in
            l.clip(to: circle(f.c, r))
            l.addFilter(.blur(radius: f.R * 0.02))
            l.stroke(circle(f.c, r), with: .color(.black.opacity(0.55)), lineWidth: f.R * 0.05)
        }
        // Bezel highlight, as on the knob face.
        ctx.stroke(circle(f.c, r - 1), with: .linearGradient(
            Gradient(colors: [Color(white: 0.22), Color(white: 0.06), .black]), startPoint: b.tl, endPoint: b.br), lineWidth: 2)

        // Unlock band: a thin glass ring between dome and colour ring.
        let band = annulus(f, OrbGeometry.unlockInner, OrbGeometry.ringInner)
        ctx.fill(band, with: .linearGradient(
            Gradient(colors: [Color(white: 0.16), Color(white: 0.05), Color(white: 0.11)]),
            startPoint: b.tl, endPoint: b.br), style: evenOdd)
        for unit in [OrbGeometry.unlockInner, OrbGeometry.ringInner] {
            ctx.stroke(circle(f.c, f.r(unit)), with: .color(.black.opacity(0.45)), lineWidth: 0.8)
        }

        // Progress: lights up along the band, from 12 o'clock, only as usable signal accrues.
        if progress > 0.001 {
            let mid = (OrbGeometry.unlockInner + OrbGeometry.ringInner) / 2
            let path = arc(f, mid, from: 0, to: 2 * .pi * min(1, progress))
            ctx.drawLayer { l in
                l.addFilter(.blur(radius: 3))
                l.stroke(path, with: .color(p.led.opacity(0.8)), style: StrokeStyle(lineWidth: f.R * 0.03, lineCap: .round))
            }
            ctx.stroke(path, with: .color(p.led.mixed(with: .white, 0.3)), style: StrokeStyle(lineWidth: f.R * 0.016, lineCap: .round))
        }
    }
}
