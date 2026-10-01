import SwiftUI

/// What the orb shows. The Measure screen builds one from the Measurer.
struct OrbInput: Equatable {
    var centre = "Start"
    var sub: String? = nil
    var caption: String? = nil
    var showsCamera = false
    var scanning = false
    var progress = 0.0
    var trace: [Double] = []
    var traceEnd = 0.0
    /// The finished reading's graph; stays until the next reading starts.
    var keptTrace: [Double] = []
    var keptTraceEnd = 0.0
    /// Run-relative time of the latest beat; drives the pulse.
    var lastBeat: Double? = nil
    var showsSweep = true
}

/// The status orb with the wide ring: static body, camera in the dome while a
/// scan reads the camera, then the grid, heartbeat arc and 5 s sweep on top.
struct OrbView<Dome: View>: View {
    @Environment(\.palette) private var palette
    let input: OrbInput
    /// Time zero of the sweep and of `input.trace`'s clock.
    let origin: Date
    @ViewBuilder var dome: () -> Dome

    var body: some View {
        GeometryReader { geo in
            let f = OrbFrame(size: geo.size)
            let domeSide = f.r(OrbGeometry.domeOuter) * 2
            ZStack {
                Canvas { ctx, size in
                    let frame = OrbFrame(size: size)
                    OrbPainter.drawBody(ctx, frame, palette)
                    if !input.showsCamera { OrbPainter.drawDomeFace(ctx, frame, palette) }
                }
                if input.showsCamera {
                    dome()
                        .frame(width: domeSide, height: domeSide)
                        .clipShape(Circle())
                        .position(f.c)
                        .transition(.opacity)
                }
                TimelineView(.animation) { timeline in
                    Canvas { ctx, size in
                        let frame = OrbFrame(size: size)
                        let now = timeline.date.timeIntervalSince(origin)
                        let angle = OrbGeometry.sweepAngle(atMs: now * 1000)
                        let strength = input.scanning ? 1.0 : 0.55
                        OrbPainter.drawGrid(ctx, frame, palette, sweep: angle, lit: input.showsSweep ? strength : 0)
                        if input.scanning {
                            let pulse = input.lastBeat.map { max(0, min(1, exp(-(now - $0) / 0.3))) } ?? 0
                            OrbPainter.drawTrace(ctx, frame, palette, trace: input.trace, end: input.traceEnd,
                                                 now: now, aged: true, pulse: pulse)
                        } else if !input.keptTrace.isEmpty {
                            OrbPainter.drawTrace(ctx, frame, palette, trace: input.keptTrace, end: input.keptTraceEnd,
                                                 now: 0, aged: false, pulse: 0)
                        }
                        if input.showsSweep {
                            OrbPainter.drawSweep(ctx, frame, palette, angle: angle, strength: strength)
                        }
                        OrbPainter.drawDomeFront(ctx, frame, palette, camera: input.showsCamera, progress: input.progress)
                    }
                }
                centreText(domeSide)
            }
            .animation(.easeOut(duration: 0.25), value: input.showsCamera)
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibility)
        .accessibilityAddTraits(.isButton)
    }

    private var accessibility: String {
        [input.centre, input.sub, input.caption].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: ", ")
    }

    @ViewBuilder
    private func centreText(_ side: CGFloat) -> some View {
        let shadow = input.showsCamera
        VStack(spacing: side * 0.02) {
            if !input.centre.isEmpty {
                Text(input.centre)
                    .font(.chakra(side * (input.sub == nil && input.caption == nil ? 0.27 : 0.34), .light))
                    .foregroundStyle(shadow ? Color.white : palette.readout)
                    .minimumScaleFactor(0.5).lineLimit(1)
            }
            if let caption = input.caption {
                Text(caption.uppercased())
                    .font(.rajdhani(side * 0.075, semibold: true))
                    .tracking(1.5)
                    .foregroundStyle(shadow ? Color.white.opacity(0.85) : palette.readoutSecondary)
                    .lineLimit(1).minimumScaleFactor(0.5)
            }
            if let sub = input.sub {
                Text(sub)
                    .font(.chakra(side * 0.105, .medium))
                    .foregroundStyle(shadow ? Color.white : palette.readout)
                    .minimumScaleFactor(0.5).lineLimit(1)
            }
        }
        .padding(.horizontal, side * 0.08)
        .frame(width: side, height: side)
        .shadow(color: shadow ? .black.opacity(0.8) : .clear, radius: 4)
        .allowsHitTesting(false)
    }
}
