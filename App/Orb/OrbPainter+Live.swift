import SwiftUI

/// The moving parts: the circular grid, the heartbeat arc and the sweep.
extension OrbPainter {
    /// `sweep` is the head angle in radians, clockwise from 12 o'clock.
    static func drawGrid(_ ctx: GraphicsContext, _ f: OrbFrame, _ p: Palette, sweep: Double, lit: Double) {
        let ink = p.gridInk
        let spokes = Int(360 / OrbGeometry.spokeStepDegrees)
        let baseAlpha = OrbGeometry.gridBaseAlpha, peak = OrbGeometry.gridMaxAlpha
        let reach = 30.0 * .pi / 180
        ctx.drawLayer { l in
            l.clip(to: annulus(f, OrbGeometry.ringInner, OrbGeometry.ringOuter), style: evenOdd)
            for i in 0..<spokes {
                let theta = Double(i) * OrbGeometry.spokeStepDegrees * .pi / 180
                var behind = (sweep - theta).truncatingRemainder(dividingBy: 2 * .pi)
                if behind < 0 { behind += 2 * .pi }
                let near = behind < reach ? 1 - behind / reach : 0
                var line = Path()
                line.move(to: f.point(OrbGeometry.ringInner, theta))
                line.addLine(to: f.point(OrbGeometry.ringOuter, theta))
                l.stroke(line, with: .color(ink.opacity(baseAlpha + (peak - baseAlpha) * near * lit)), lineWidth: 0.8)
            }
            for radius in OrbGeometry.gridCircleRadii {
                l.stroke(circle(f.c, f.r(radius)), with: .color(ink.opacity(baseAlpha * 1.3)), lineWidth: 0.7)
                l.stroke(arc(f, radius, from: sweep - reach, to: sweep), with: .color(ink.opacity(peak * lit)), lineWidth: 0.9)
            }
        }
    }

    /// Read-out colours by the trace's height (its radius), in one hue family. Dark: near-black
    /// maroon through deep red to a bright red. Light: the same shift in blue/green, as the ring does.
    /// The mode's own hue is always the default; light only moves the hue.
    static func traceColour(_ amplitude: Double, light: Bool) -> Color {
        let stops: [Color] = light
            ? [Color(hex: 0x06262B), Color(hex: 0x0B5F63), Color(hex: 0x129C8E), Color(hex: 0x4FE3C1)]
            : [Color(hex: 0x4A0709), Color(hex: 0x9E0F16), Color(hex: 0xE11D25), Color(hex: 0xFF5A4F)]
        let t = max(0, min(1, (amplitude + 1) / 2)) * Double(stops.count - 1)
        let i = min(stops.count - 2, Int(t))
        return stops[i].mixed(with: stops[i + 1], t - Double(i))
    }

    /// The PPG trace around the ring. `end` is seconds since the trace's zero; angle is time on
    /// the sweep's clock, so the newest sample sits under the head and (while `aged`) the last
    /// revolution fades behind it. `pulse` (0...1) brightens the whole trace on a beat.
    static func drawTrace(_ ctx: GraphicsContext, _ f: OrbFrame, _ p: Palette, trace: [Double], end: Double,
                          now: Double, aged: Bool, pulse: Double) {
        guard trace.count > 3 else { return }
        let rate = ScanSession.analysisRate
        let period = OrbGeometry.sweepPeriodMs / 1000
        func time(_ i: Int) -> Double { end - Double(trace.count - 1 - i) / rate }
        func pt(_ i: Int) -> CGPoint {
            f.point(OrbGeometry.arcRadius(amplitude: trace[i]), OrbGeometry.sweepAngle(atMs: time(i) * 1000))
        }
        let step = 3
        var segments: [(Path, Color, Double)] = []
        var i = 0
        while i < trace.count - 1 {
            let j = min(trace.count - 1, i + step)
            var path = Path(); path.move(to: pt(i))
            for k in (i + 1)...j { path.addLine(to: pt(k)) }
            let mean = trace[i...j].reduce(0, +) / Double(j - i + 1)
            let age = aged ? max(0, min(1, (now - time(j)) / period)) : 0
            // A wrap back to the start of the circle would draw a line across the ring; skip it.
            let jump = hypot(pt(i).x - pt(j).x, pt(i).y - pt(j).y)
            if jump < f.R * 0.5 { segments.append((path, traceColour(mean, light: p.isLight), 1 - 0.78 * age)) }
            i = j
        }
        let boost = 1 + 0.9 * pulse
        let crest = p.isLight ? Color(hex: 0x1FD6B8) : Color(hex: 0xFF2B2B)
        ctx.drawLayer { l in
            l.blendMode = .plusLighter
            l.drawLayer { g in
                g.addFilter(.blur(radius: 2.5 + 3 * pulse))
                for (path, colour, alpha) in segments {
                    g.stroke(path, with: .color(colour.opacity(min(1, 0.5 * alpha * boost))),
                             style: StrokeStyle(lineWidth: 3.5 + 2 * pulse, lineCap: .round))
                }
            }
            for (path, colour, alpha) in segments {
                l.stroke(path, with: .color(colour.mixed(with: crest, 0.6 * pulse).opacity(min(1, alpha * (0.85 + 0.15 * pulse)))),
                         style: StrokeStyle(lineWidth: 1.4 + 0.8 * pulse, lineCap: .round, lineJoin: .round))
            }
            // A lit crest on each local maximum, at least 0.35 s apart.
            var lastCrest = -1000
            let gap = Int(0.35 * rate)
            for k in 2..<(trace.count - 2) where trace[k] > 0.5 && trace[k] >= trace[k - 1] && trace[k] > trace[k + 1] && k - lastCrest > gap {
                lastCrest = k
                l.fill(circle(pt(k), 2.4 + 1.2 * pulse), with: .color(crest.opacity(0.95)))
            }
        }
    }

    /// Core line in the background colour, with a bright overlay glow trailing it.
    static func drawSweep(_ ctx: GraphicsContext, _ f: OrbFrame, _ p: Palette, angle: Double, strength: Double) {
        let head = OrbGeometry.sweepHeadDegrees * .pi / 180
        let lead = OrbGeometry.sweepLeadDegrees * .pi / 180
        let glow = p.background.mixed(with: .white, 0.82)

        func wedge(_ from: Double, _ to: Double, grow: Double = 0) -> Path {
            var path = arc(f, OrbGeometry.ringOuter + grow, from: from, to: to)
            let back = arc(f, OrbGeometry.ringInner - grow, from: to, to: from)
            path.addPath(back)
            path.closeSubpath()
            return path
        }
        // The conic gradient runs a full turn; squeeze it onto the head arc.
        let squeezed = GraphicsContext.Shading.conicGradient(
            Gradient(stops: [.init(color: glow.opacity(0), location: 0),
                             .init(color: glow.opacity(0.95 * strength), location: head / (2 * .pi)),
                             .init(color: glow.opacity(0), location: head / (2 * .pi) + 0.0001)]),
            center: f.c, angle: .radians(angle - head - .pi / 2))

        ctx.drawLayer { l in
            l.blendMode = .overlay
            l.fill(wedge(angle - head, angle), with: squeezed)
            l.fill(wedge(angle - lead, angle), with: .color(glow.opacity(0.9 * strength)))
        }
        ctx.drawLayer { l in
            l.blendMode = .plusLighter
            l.addFilter(.blur(radius: f.R * 0.03))
            l.fill(wedge(angle - head * 0.8, angle, grow: 0.02), with: squeezed)
        }
        var core = Path()
        core.move(to: f.point(OrbGeometry.ringInner, angle))
        core.addLine(to: f.point(OrbGeometry.ringOuter, angle))
        ctx.stroke(core, with: .color(p.background.opacity(0.95)), style: StrokeStyle(lineWidth: 2, lineCap: .butt))
    }
}
