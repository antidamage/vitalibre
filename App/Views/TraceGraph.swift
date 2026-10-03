import SwiftUI

/// The saved graph on a flat surface: the orb's own trace, unwound.
///
/// The orb wraps its trace around the ring; this is the same filtered waveform on a straight
/// axis. One painter serves both places a reading's graph appears — the band under its row and
/// the full-screen view — and it is drawn with the orb's own treatment: one 1 pt hairline over
/// two narrow additive strokes (2.6 pt at 10%, 1.8 pt at 16%), butt caps, coloured by height in
/// the same per-mode ramp. Segments run sample to sample with nothing smoothed or overshot, so
/// what is on screen is the data that arrived.
enum TraceLine {
    /// How wide the expanded graph is drawn, in points per second of the run: a 15 s reading is
    /// 900 pt, about three phone widths, which is what makes a single beat legible. The band
    /// uses the row's width instead.
    static let pointsPerSecond: CGFloat = 60
    /// The band's height under a reading.
    static let bandHeight: CGFloat = 44
    /// One stroke per this many samples: a smooth line without a stroke per sample on a
    /// 900-sample graph.
    static let samplesPerSegment = 3
    /// The zoom the expanded view opens at, and its range.
    static let minZoom: CGFloat = 0.5, maxZoom: CGFloat = 8, startingZoom: CGFloat = 1

    /// Where sample `i` sits in `size`.
    static func point(_ i: Int, _ trace: [Double], _ size: CGSize) -> CGPoint {
        let step = size.width / CGFloat(max(1, trace.count - 1))
        let middle = size.height / 2
        let reach = max(1, size.height / 2 - 1)
        return CGPoint(x: CGFloat(i) * step, y: middle - CGFloat(max(-1, min(1, trace[i]))) * reach)
    }

    /// The run in strokes, each carrying the colour of its own height.
    static func segments(_ trace: [Double], _ size: CGSize, light: Bool) -> [(Path, Color)] {
        guard trace.count > 1, size.width > 1, size.height > 1 else { return [] }
        var out: [(Path, Color)] = []
        out.reserveCapacity(trace.count / samplesPerSegment + 1)
        var i = 0
        while i < trace.count - 1 {
            let j = min(trace.count - 1, i + samplesPerSegment)
            var path = Path()
            path.move(to: point(i, trace, size))
            for k in (i + 1)...j { path.addLine(to: point(k, trace, size)) }
            let mean = trace[i...j].reduce(0, +) / Double(j - i + 1)
            out.append((path, OrbPainter.traceColour(mean, light: light)))
            i = j
        }
        return out
    }

    static func draw(_ trace: [Double], in size: CGSize, _ palette: Palette, _ ctx: inout GraphicsContext) {
        let strokes = segments(trace, size, light: palette.isLight)
        guard strokes.count > 1 else { return }
        ctx.drawLayer { glow in
            glow.blendMode = .plusLighter
            for (width, alpha) in [(2.6, 0.10), (1.8, 0.16)] {
                for (path, colour) in strokes {
                    glow.stroke(path, with: .color(colour.opacity(alpha)),
                                style: StrokeStyle(lineWidth: width, lineCap: .butt, lineJoin: .round))
                }
            }
        }
        for (path, colour) in strokes {
            ctx.stroke(path, with: .color(colour), style: StrokeStyle(lineWidth: 1, lineCap: .butt, lineJoin: .round))
        }
    }
}

/// The graph a reading keeps, drawn at whatever size it is given. Only the picture: what a
/// tap on it does belongs to the screen it sits on.
struct TraceGraph: View {
    let trace: [Double]
    let palette: Palette
    /// Only the saved reading's full-screen graph carries one; the band and the orb draw the line alone.
    var overlay: TraceOverlay? = nil

    var body: some View {
        Canvas { ctx, size in
            TraceLine.draw(trace, in: size, palette, &ctx)
            overlay?.draw(in: size, seconds: Double(max(1, trace.count - 1)) / ScanSession.analysisRate, palette, &ctx)
            // The graph's own zero, so a trace that sits high or low reads as high or low.
            var middle = Path()
            middle.move(to: CGPoint(x: 0, y: size.height / 2))
            middle.addLine(to: CGPoint(x: size.width, y: size.height / 2))
            ctx.stroke(middle, with: .color(palette.line.opacity(0.6)), lineWidth: 0.5)
        }
        .background(palette.background.opacity(0.55))
        .clipShape(CutCornerShape(cut: 8))
    }
}

/// A reading's graph as the horizontal band under its row: the whole run across the row's own
/// width. Decorative — the row's button carries the label and the tap.
struct TraceBand: View {
    @Environment(\.palette) private var palette
    let trace: [Double]
    var height: CGFloat = TraceLine.bandHeight

    var body: some View {
        TraceGraph(trace: trace, palette: palette)
            .frame(height: height)
            .frame(maxWidth: .infinity)
            .accessibilityHidden(true)
    }
}
