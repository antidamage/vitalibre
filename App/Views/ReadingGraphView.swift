import SwiftUI
import UIKit

/// A reading's graph, full screen: the whole run at a scale where a single beat can be read, with the
/// camera feed's quality drawn over it on the same time axis. One finger scrolls it, two fingers pinch it,
/// and a tap anywhere goes back — or Done, which is what VoiceOver activates, because a tap-only surface is
/// not a way out for everyone.
///
/// "Leave out stretches" turns the one-finger drag into a selection instead of a scroll: drag across a
/// stretch to leave it out of the numbers, tap a left-out stretch to put it back. A left-out stretch stays
/// on the graph, dimmed, and in the file; nothing is deleted and nothing is left out automatically.
struct ReadingGraphView: View {
    @EnvironmentObject private var store: ReadingStore
    @EnvironmentObject private var prefs: Preferences
    @EnvironmentObject private var measurer: Measurer
    @Environment(\.palette) private var palette
    let readingID: UUID
    let onDismiss: () -> Void
    @State private var editing = false
    @State private var message: String?

    private var reading: Reading? { store.reading(readingID) }

    var body: some View {
        GeometryReader { screen in
            ZStack {
                palette.backgroundGradient.ignoresSafeArea()
                if let reading {
                    VStack(alignment: .leading, spacing: 0) {
                        header(reading)
                        Spacer(minLength: 12)
                        GraphScroller(trace: reading.trace ?? [], palette: palette, overlay: overlay(reading), editing: editing,
                                      onDismiss: onDismiss, onExclude: { exclude($0, in: reading) }, onRestore: { restore($0, in: reading) })
                            .frame(height: max(150, screen.size.height * 0.4))
                            .padding(.horizontal, 22)
                            .panel(radius: 12)
                        controls(reading)
                        Spacer(minLength: 12)
                    }
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { if !editing { onDismiss() } }
        }
        .accessibilityAddTraits(.isModal)
    }

    private func overlay(_ r: Reading) -> TraceOverlay {
        TraceOverlay(traceStart: r.traceStart ?? ScanSession.settleSeconds, feed: r.feedQuality, excluded: r.excluded ?? [])
    }

    private func header(_ r: Reading) -> some View {
        let hidden = r.bpHidden(prefs)
        return VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text("\(Int(r.displayHeartRate.rounded()))")
                    .font(.rajdhani(42)).monospacedDigit().foregroundStyle(palette.readout)
                Text("BPM").font(.chakra(11)).foregroundStyle(palette.readoutSecondary)
                Spacer()
                Button("Done", action: onDismiss)
                    .font(.chakra(14, .medium)).foregroundStyle(palette.led)
                    .accessibilityHint("Closes the graph")
            }
            Text(r.date.formatted(date: .abbreviated, time: .shortened))
                .font(.rajdhani(16)).foregroundStyle(palette.readoutSecondary)
            if let hidden {
                Text(hidden.line).font(.rajdhani(14)).foregroundStyle(palette.readoutSecondary)
            } else {
                Text("Blood pressure estimate \(r.displayBP.text) mmHg").font(.rajdhani(16)).foregroundStyle(palette.readoutSecondary)
                Text(BPPresentation.margin(prefs, model: measurer.bpModel)).font(.rajdhani(12)).foregroundStyle(palette.readoutSecondary)
                Text(Publisher.policy.bpCaveat).font(.rajdhani(12)).foregroundStyle(palette.readoutSecondary)
            }
            if let note = store.noteText(for: r) {
                Text(note).font(.chakra(12, .medium)).foregroundStyle(palette.led)
            }
            if r.edited != nil {
                Text("\(Int(r.usedSeconds.rounded())) of \(Int(r.duration.rounded())) s used")
                    .font(.chakra(12, .medium)).foregroundStyle(palette.readout)
            }
        }
        .padding(.horizontal, 22).padding(.top, 22)
    }

    private func controls(_ r: Reading) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 14) {
                Button {
                    DialClick.shared.play()
                    editing.toggle()
                    message = nil
                } label: {
                    Text(editing ? "Finish leaving out" : "Leave out stretches")
                        .font(.chakra(13, .medium)).foregroundStyle(palette.led)
                }
                if r.excluded?.isEmpty == false {
                    Button("Put all back") { apply([], in: r) }
                        .font(.chakra(13, .medium)).foregroundStyle(palette.readoutSecondary)
                }
                Spacer()
                if r.feedQuality != nil {
                    HStack(spacing: 6) {
                        Rectangle().fill(palette.readout.opacity(0.5)).frame(width: 18, height: 1)
                        Text("Camera feed quality").font(.rajdhani(12)).foregroundStyle(palette.readoutSecondary)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
            Text(editing
                 ? "Drag across a stretch to leave it out of the numbers. Tap a dimmed stretch to put it back."
                 : "Shaded seconds are where the camera feed was poor. Leaving them out is your choice; nothing is deleted.")
                .font(.rajdhani(12)).foregroundStyle(palette.readoutSecondary)
            if let message { Text(message).font(.rajdhani(13, semibold: true)).foregroundStyle(palette.led) }
        }
        .padding(.horizontal, 22).padding(.top, 10)
    }

    // MARK: Leaving a stretch out

    private func exclude(_ range: ExcludedRange, in r: Reading) { apply((r.excluded ?? []) + [range], in: r) }

    private func restore(_ index: Int, in r: Reading) {
        var ranges = r.excluded ?? []
        guard ranges.indices.contains(index) else { return }
        ranges.remove(at: index)
        apply(ranges, in: r)
    }

    /// Recomputes the reading without these stretches. If too little is left, or no pulse can be found in what
    /// is left, nothing changes and the user is told why.
    private func apply(_ ranges: [ExcludedRange], in r: Reading) {
        guard let trace = r.trace else { return }
        let start = r.traceStart ?? ScanSession.settleSeconds
        let end = start + Double(trace.count - 1) / ScanSession.analysisRate
        let merged = ReadingEdit.merged(ranges, from: start, to: end)
        if merged.isEmpty { store.setExclusions(r.id, [], outcome: nil); message = nil; return }
        let age = prefs.age > 0 ? prefs.age : nil
        switch ReadingEdit.recompute(trace: trace, traceStart: start, excluded: merged, model: measurer.bpModel,
                                     age: age, sex: prefs.sex, usual: prefs.usual, calibration: prefs.calibration) {
        case .success(let outcome):
            store.setExclusions(r.id, merged, outcome: outcome); message = nil
        case .failure(.tooLittleSignalLeft):
            message = "Too little signal left: at least \(Int(ReadingEdit.minKeptSeconds)) s is needed. The previous result stays."
        case .failure(.noPulse):
            message = "No pulse could be found in what is left. The previous result stays."
        }
    }
}

/// The graph in a scroller of its own: a UIScrollView rather than SwiftUI's, because the zoom
/// has to keep the part of the graph under the fingers where it is, and that needs the offset
/// in hand. SwiftUI's scroll view keeps its offset numerically, so growing the width walks the
/// view sideways instead.
struct GraphScroller: UIViewRepresentable {
    let trace: [Double]
    let palette: Palette
    var overlay: TraceOverlay?
    var editing = false
    let onDismiss: () -> Void
    var onExclude: (ExcludedRange) -> Void = { _ in }
    var onRestore: (Int) -> Void = { _ in }

    func makeUIView(context: Context) -> TraceScrollView { TraceScrollView() }

    func updateUIView(_ scroller: TraceScrollView, context: Context) {
        scroller.onDismiss = onDismiss
        scroller.onExclude = onExclude
        scroller.onRestore = onRestore
        scroller.editing = editing
        scroller.show(trace: trace, palette: palette, overlay: overlay)
    }
}

final class TraceScrollView: UIScrollView, UIGestureRecognizerDelegate {
    var onDismiss: (() -> Void)?
    var onExclude: ((ExcludedRange) -> Void)?
    var onRestore: ((Int) -> Void)?
    /// Leaving stretches out: the one-finger drag selects instead of scrolling.
    var editing = false {
        didSet {
            guard editing != oldValue else { return }
            isScrollEnabled = !editing
            selectPan.isEnabled = editing
            if !editing { selection = nil; render() }
        }
    }

    private let host = UIHostingController(rootView: TraceGraph(trace: [], palette: .dark))
    private var trace: [Double] = []
    private var palette: Palette = .dark
    private var overlay: TraceOverlay?
    private var selection: ExcludedRange?
    private var selectStart: Double?
    private var zoom: CGFloat = TraceLine.startingZoom
    private var zoomAtPinchStart: CGFloat = TraceLine.startingZoom
    private let selectPan = UIPanGestureRecognizer()

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
        let tap = UITapGestureRecognizer(target: self, action: #selector(tap(_:)))
        tap.delegate = self
        addGestureRecognizer(tap)
        selectPan.addTarget(self, action: #selector(dragSelect(_:)))
        selectPan.maximumNumberOfTouches = 1
        selectPan.isEnabled = false
        selectPan.delegate = self
        addGestureRecognizer(selectPan)
    }

    required init?(coder: NSCoder) { fatalError("VitaLibre builds its own views") }

    /// The graph as the reading saved it, in the current palette and at the current zoom.
    func show(trace: [Double], palette: Palette, overlay: TraceOverlay?) {
        self.trace = trace
        self.palette = palette
        self.overlay = overlay
        render()
        setNeedsLayout()
    }

    private func render() {
        var o = overlay
        o?.selection = selection
        host.rootView = TraceGraph(trace: trace, palette: palette, overlay: o)
    }

    /// The run's length in seconds, as the graph draws it.
    private var spanSeconds: Double { Double(max(1, trace.count - 1)) / ScanSession.analysisRate }

    /// The graph's own width: the run's length at the drawing rate and the current zoom, never
    /// narrower than the viewport, so a short run still fills the panel.
    private var graphWidth: CGFloat {
        let seconds = Double(trace.count) / ScanSession.analysisRate
        return max(bounds.width, CGFloat(seconds) * TraceLine.pointsPerSecond * zoom)
    }

    /// Seconds from the start of the covered run at a point `x` across the graph.
    private func runTime(atX x: CGFloat) -> Double {
        let start = overlay?.traceStart ?? 0
        return start + Double(max(0, min(graphWidth, x)) / graphWidth) * spanSeconds
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

    /// Outside editing a tap goes back; while editing it puts a left-out stretch back.
    @objc private func tap(_ recogniser: UITapGestureRecognizer) {
        guard editing else { onDismiss?(); return }
        let t = runTime(atX: recogniser.location(in: host.view).x)
        if let i = overlay?.excluded.firstIndex(where: { $0.start <= t && t <= $0.end }) { onRestore?(i) }
    }

    @objc private func dragSelect(_ recogniser: UIPanGestureRecognizer) {
        let t = runTime(atX: recogniser.location(in: host.view).x)
        switch recogniser.state {
        case .began:
            selectStart = t
            selection = ExcludedRange(start: t, end: t)
        case .changed:
            if let a = selectStart { selection = ExcludedRange(start: min(a, t), end: max(a, t)) }
        case .ended:
            if let s = selection, s.length >= 0.3 { onExclude?(s) }
            selection = nil; selectStart = nil
        default:
            selection = nil; selectStart = nil
        }
        render()
    }

    /// The pinch, the pan and a tap have to be able to run together: a two-finger squeeze that
    /// drifts sideways is a zoom, not two gestures fighting.
    func gestureRecognizer(_ recogniser: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
}
