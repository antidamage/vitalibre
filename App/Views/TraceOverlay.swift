import SwiftUI

/// What the saved reading's graph draws over the waveform: the feed-quality line, the stretches it suggests
/// leaving out, the stretches the user has left out, and the stretch being dragged. All times are seconds
/// from the start of the covered run, which is the axis the stored trace, the feed series and the exclusions
/// share (feed point `k` is the window ending `k + 1` s into the run).
struct TraceOverlay: Equatable {
    var traceStart: Double
    var feed: [Double]?
    var excluded: [ExcludedRange] = []
    var selection: ExcludedRange?

    /// Draws into `size`, where `seconds` of trace span the whole width.
    func draw(in size: CGSize, seconds: Double, _ palette: Palette, _ ctx: inout GraphicsContext) {
        guard seconds > 0 else { return }
        func x(_ t: Double) -> CGFloat { CGFloat((t - traceStart) / seconds) * size.width }

        // Seconds along the foot, a label every five.
        var s = Int(traceStart.rounded(.up))
        while Double(s) <= traceStart + seconds {
            let px = x(Double(s))
            var tick = Path(); tick.move(to: CGPoint(x: px, y: size.height)); tick.addLine(to: CGPoint(x: px, y: size.height - (s % 5 == 0 ? 6 : 3)))
            ctx.stroke(tick, with: .color(palette.readoutSecondary.opacity(0.5)), lineWidth: 0.5)
            if s % 5 == 0 {
                ctx.draw(Text("\(s)s").font(.rajdhani(10)).foregroundColor(palette.readoutSecondary),
                         at: CGPoint(x: px + 3, y: size.height - 9), anchor: .leading)
            }
            s += 1
        }

        if let feed {
            // Seconds the index calls below fair are shaded as a suggestion. Nothing is left out for the user.
            for (k, v) in feed.enumerated() where v < FeedQuality.fair {
                let t1 = Double(k + 1)
                ctx.fill(Path(CGRect(x: x(t1 - 1), y: 0, width: x(t1) - x(t1 - 1), height: size.height)),
                         with: .color(palette.led.opacity(0.12)))
            }
            // The index itself: thin, half opacity, 0 at the foot and 1 at the top.
            var line = Path()
            for (k, v) in feed.enumerated() {
                let p = CGPoint(x: x(Double(k + 1)), y: size.height - 2 - CGFloat(max(0, min(1, v))) * (size.height - 4))
                if k == 0 { line.move(to: p) } else { line.addLine(to: p) }
            }
            ctx.stroke(line, with: .color(palette.readout.opacity(0.5)), lineWidth: 1)
        }

        for r in excluded {
            let rect = CGRect(x: x(r.start), y: 0, width: x(r.end) - x(r.start), height: size.height)
            ctx.fill(Path(rect), with: .color(palette.background.opacity(0.72)))
            for edge in [rect.minX, rect.maxX] {
                var e = Path(); e.move(to: CGPoint(x: edge, y: 0)); e.addLine(to: CGPoint(x: edge, y: size.height))
                ctx.stroke(e, with: .color(palette.led.opacity(0.6)), style: StrokeStyle(lineWidth: 1, dash: [3, 3]))
            }
        }

        if let sel = selection {
            ctx.fill(Path(CGRect(x: x(sel.start), y: 0, width: x(sel.end) - x(sel.start), height: size.height)),
                     with: .color(palette.led.opacity(0.25)))
        }
    }
}
