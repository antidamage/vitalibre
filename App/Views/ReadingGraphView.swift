import SwiftUI
import UIKit

/// A reading's graph, full screen: the whole run at a scale where a single beat can be read.
/// One finger scrolls it, two fingers pinch it, and a tap anywhere goes back — or Done, which
/// is what VoiceOver activates, because a tap-only surface is not a way out for everyone.
struct ReadingGraphView: View {
    @Environment(\.palette) private var palette
    let reading: Reading
    let onDismiss: () -> Void

    private var trace: [Double] { reading.trace ?? [] }

    var body: some View {
        GeometryReader { screen in
            ZStack {
                palette.backgroundGradient.ignoresSafeArea()
                VStack(alignment: .leading, spacing: 0) {
                    header
                    Spacer(minLength: 12)
                    GraphScroller(trace: trace, palette: palette, onDismiss: onDismiss)
                        .frame(height: max(150, screen.size.height * 0.4))
                        .padding(.horizontal, 22)
                        .panel(radius: 12)
                    Spacer(minLength: 12)
                }
            }
            .contentShape(Rectangle())
            .onTapGesture(perform: onDismiss)
        }
        .accessibilityAddTraits(.isModal)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text("\(Int(reading.heartRate.rounded()))")
                    .font(.rajdhani(42)).monospacedDigit().foregroundStyle(palette.readout)
                Text("BPM").font(.chakra(11)).foregroundStyle(palette.readoutSecondary)
                Spacer()
                Button("Done", action: onDismiss)
                    .font(.chakra(14, .medium)).foregroundStyle(palette.led)
                    .accessibilityHint("Closes the graph")
            }
            Text(reading.date.formatted(date: .abbreviated, time: .shortened))
                .font(.rajdhani(16)).foregroundStyle(palette.readoutSecondary)
            Text("Blood pressure estimate \(reading.bp.text) mmHg")
                .font(.rajdhani(16)).foregroundStyle(palette.readoutSecondary)
            if let note = reading.note {
                Text(note).font(.chakra(12, .medium)).foregroundStyle(palette.led)
            }
        }
        .padding(.horizontal, 22).padding(.top, 22)
    }
}

/// The graph in a scroller of its own: a UIScrollView rather than SwiftUI's, because the zoom
/// has to keep the part of the graph under the fingers where it is, and that needs the offset
/// in hand. SwiftUI's scroll view keeps its offset numerically, so growing the width walks the
/// view sideways instead.
struct GraphScroller: UIViewRepresentable {
    let trace: [Double]
    let palette: Palette
    let onDismiss: () -> Void

    func makeUIView(context: Context) -> TraceScrollView {
        let scroller = TraceScrollView()
        scroller.onDismiss = onDismiss
        return scroller
    }

    func updateUIView(_ scroller: TraceScrollView, context: Context) {
        scroller.onDismiss = onDismiss
        scroller.show(trace: trace, palette: palette)
    }
}

final class TraceScrollView: UIScrollView, UIGestureRecognizerDelegate {
    var onDismiss: (() -> Void)?

    private let host = UIHostingController(rootView: TraceGraph(trace: [], palette: .dark))
    private var trace: [Double] = []
    private var palette: Palette = .dark
    private var zoom: CGFloat = TraceLine.startingZoom
    private var zoomAtPinchStart: CGFloat = TraceLine.startingZoom

    init() {
        super.init(frame: .zero)
        showsHorizontalScrollIndicator = false
        showsVerticalScrollIndicator = false
        alwaysBounceVertical = false
        // The orb is not on this screen: nothing here needs a delayed touch.
        delaysContentTouches = false
        host.view.backgroundColor = .clear
        addSubview(host.view)
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(pinch(_:)))
        pinch.delegate = self
        addGestureRecognizer(pinch)
        let tap = UITapGestureRecognizer(target: self, action: #selector(tap))
        tap.delegate = self
        addGestureRecognizer(tap)
    }

    required init?(coder: NSCoder) { fatalError("VitaLibre builds its own views") }

    /// The graph as the reading saved it, in the current palette and at the current zoom.
    func show(trace: [Double], palette: Palette) {
        self.trace = trace
        self.palette = palette
        host.rootView = TraceGraph(trace: trace, palette: palette)
        setNeedsLayout()
    }

    /// The graph's own width: the run's length at the drawing rate and the current zoom, never
    /// narrower than the viewport, so a short run still fills the panel.
    private var graphWidth: CGFloat {
        let seconds = Double(trace.count) / ScanSession.analysisRate
        return max(bounds.width, CGFloat(seconds) * TraceLine.pointsPerSecond * zoom)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard bounds.width > 1, bounds.height > 1 else { return }
        layOutGraph()
    }

    private func layOutGraph() {
        guard bounds.width > 1, bounds.height > 1 else { return }
        let size = CGSize(width: graphWidth, height: bounds.height)
        host.view.frame = CGRect(origin: .zero, size: size)
        contentSize = size
    }

    @objc private func pinch(_ recogniser: UIPinchGestureRecognizer) {
        let anchor = recogniser.location(in: self)
        switch recogniser.state {
        case .began:
            zoomAtPinchStart = zoom
        case .changed:
            // Where the graph under the fingers is now, as a share of its own width, before the
            // width changes.
            let oldWidth = graphWidth
            let fraction = oldWidth > 1 ? (contentOffset.x + anchor.x) / oldWidth : 0
            zoom = min(TraceLine.maxZoom, max(TraceLine.minZoom, zoomAtPinchStart * recogniser.scale))
            layOutGraph()
            let width = graphWidth
            contentOffset.x = min(max(0, width - bounds.width), max(0, fraction * width - anchor.x))
        default:
            break
        }
    }

    @objc private func tap() { onDismiss?() }

    /// The pinch, the pan and a tap have to be able to run together: a two-finger squeeze that
    /// drifts sideways is a zoom, not two gestures fighting.
    func gestureRecognizer(_ recogniser: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
}
