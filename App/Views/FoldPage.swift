import SwiftUI
import UIKit

/// The measure page's scroller: a scroll view whose first 80 points of upward pull are caught by
/// the fold's band (`Core/FoldBand.swift`) instead of moving the page 1:1 — nearly the same at
/// first, then not at all — before the band breaks, the page catches up to where the finger is and
/// scrolls freely. Brought back to its edge the pull re-locks, so the next one meets the band
/// again. Because it is the page's own offset the band fights, the whole screen above the line
/// slides up with it.
final class FoldScrollView: UIScrollView, UIScrollViewDelegate {
    /// Told whenever the fold opens or closes, so the line can light up and its triangle flip.
    var onOpenChange: ((Bool) -> Void)?
    /// Told when the viewport changes size, so the page can be rebuilt for it.
    var onViewportChange: ((CGSize) -> Void)?

    private var pull = FoldPull()
    private var inputBase: CGFloat = 0
    private var boost: CGFloat = 0
    private var boostStart: CFTimeInterval = 0
    private var link: CADisplayLink?
    private var applying = false
    private var springing = false
    private var lastViewport: CGSize = .zero

    override init(frame: CGRect) {
        super.init(frame: frame)
        delegate = self
        bounces = false
        showsVerticalScrollIndicator = false
        contentInsetAdjustmentBehavior = .never
        // The orb is tapped far more often than the page is dragged: its tap must land at once.
        delaysContentTouches = false
    }

    required init?(coder: NSCoder) { fatalError("VitaLibre builds its own views") }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard bounds.size != lastViewport else { return }
        lastViewport = bounds.size
        onViewportChange?(bounds.size)
    }

    /// Something past the line, with room for at least a row of it, or there is no fold.
    private var foldable: Bool { FoldBand.opens(overflow: contentSize.height - bounds.height) }

    private var maxOffset: CGFloat { max(0, contentSize.height - bounds.height) }

    /// The offset that brings the guarded area's top to the top of the viewport: what a tap opens
    /// to.
    private var openTarget: CGFloat { max(0, bounds.height - FoldMetrics.rest) }

    /// How far the finger has pulled up since the fold's band was handed this gesture.
    private var input: CGFloat { -panGestureRecognizer.translation(in: self).y - inputBase }

    // MARK: The band

    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
        endBoost()
        // A touch cancels whatever animation is in flight, and an interrupted
        // `scrollViewDidEndScrollingAnimation` would otherwise leave this latch set for good —
        // the band would never run again.
        springing = false
        guard !pull.open else { return }
        pull = FoldPull()
        inputBase = -panGestureRecognizer.translation(in: self).y
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        guard !applying, !springing, foldable else { return }
        if pull.open {
            // Broken: the page is the finger's, until it comes back to the fold's edge.
            if boost > 0, isDragging {
                apply(max(0, input - boost))
            } else if contentOffset.y <= 0, isDragging || isDecelerating {
                relock()
            }
            return
        }
        guard isDragging else { return }
        switch pull.move(up: input) {
        case .held(let caught): apply(caught)
        case .broke(let catchUp):
            // Sit the page where the band left it before the snap takes over, so the catch-up
            // starts from the band's own position rather than the last frame's.
            apply(max(0, input - catchUp))
            snap(by: catchUp)
        case .free, .springBack, .stay: break
        }
    }

    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        endBoost()
        guard !pull.open, !springing, contentOffset.y > 0 else { return }
        // Released inside the band: the page goes back to rest over 180ms, ease-out. There is no
        // resistance on the way back.
        spring(animated: !UIAccessibility.isReduceMotionEnabled)
    }

    func scrollViewDidEndScrollingAnimation(_ scrollView: UIScrollView) {
        springing = false
        if contentOffset.y <= 0 { relock() }
    }

    // MARK: What the fold does to the page

    private func apply(_ y: CGFloat) {
        let clamped = min(max(0, y), maxOffset)
        guard abs(contentOffset.y - clamped) > 0.01 else { return }
        applying = true
        setContentOffset(CGPoint(x: 0, y: clamped), animated: false)
        applying = false
    }

    /// The break: the page catches up every point the band absorbed, over 220ms with the
    /// dashboard's slight overshoot, so nothing jumps.
    private func snap(by catchUp: CGFloat) {
        boost = catchUp
        boostStart = CACurrentMediaTime()
        if link == nil {
            let link = CADisplayLink(target: self, selector: #selector(tick))
            link.add(to: .main, forMode: .common)
            self.link = link
        }
        onOpenChange?(true)
    }

    @objc private func tick() {
        if UIAccessibility.isReduceMotionEnabled {
            boost = 0
        } else {
            let t = (CACurrentMediaTime() - boostStart) / FoldScrollView.snapDuration
            boost = t >= 1 ? 0 : CGFloat(FoldBand.catchUp) * (1 - FoldScrollView.breakEase(t))
        }
        if boost == 0 { endBoost() }
        scrollViewDidScroll(self)
    }

    private func endBoost() {
        link?.invalidate()
        link = nil
        boost = 0
    }

    private func relock() {
        pull = FoldPull()
        inputBase = -panGestureRecognizer.translation(in: self).y
        onOpenChange?(false)
    }

    private func spring(animated: Bool) {
        guard animated else { apply(0); relock(); return }
        springing = true
        setContentOffset(.zero, animated: true)
    }

    /// Shuts the fold: what happens when the day's log empties while it is open, so the line is not
    /// left lit over nothing.
    func close() {
        guard pull.open else { return }
        pull = FoldPull()
        onOpenChange?(false)
        spring(animated: !UIAccessibility.isReduceMotionEnabled)
    }

    /// The tap: open the guarded area, or shut it again. This is also what VoiceOver activates.
    func toggle() {
        guard foldable else { return }
        if pull.open {
            pull = FoldPull()
            onOpenChange?(false)
            spring(animated: !UIAccessibility.isReduceMotionEnabled)
        } else {
            pull = FoldPull(open: true)
            onOpenChange?(true)
            guard !UIAccessibility.isReduceMotionEnabled else { apply(openTarget); return }
            springing = true
            setContentOffset(CGPoint(x: 0, y: min(openTarget, maxOffset)), animated: true)
        }
    }

    // MARK: The dashboard's break curve

    /// The dashboard's break is `cubic-bezier(0.2, 0.9, 0.3, 1.15)`: the catch-up eases out with a
    /// slight overshoot, so the page lands on the finger's position and settles rather than
    /// stopping short of it.
    static let snapDuration: Double = 0.22

    static func breakEase(_ t: Double) -> Double {
        let (x1, y1, x2, y2) = (0.2, 0.9, 0.3, 1.15)
        var low = 0.0, high = 1.0
        for _ in 0..<24 {
            let s = (low + high) / 2
            let x = 3 * (1 - s) * (1 - s) * s * x1 + 3 * (1 - s) * s * s * x2 + s * s * s
            if x < t { low = s } else { high = s }
        }
        let s = (low + high) / 2
        return 3 * (1 - s) * (1 - s) * s * y1 + 3 * (1 - s) * s * s * y2 + s * s * s
    }
}

/// The page the fold lives on: the whole measure screen in one scroller, so pulling it up slides
/// everything above the line away and brings the day's readings in from the foot.
struct FoldPage<Content: View>: UIViewRepresentable {
    /// Whether the fold is open, for the line's own state.
    @Binding var isOpen: Bool
    /// How the page's own line asks the scroller to open or shut.
    let commands: FoldCommands
    /// The page, built for the viewport it has.
    let content: (CGSize) -> Content

    func makeCoordinator() -> Coordinator { Coordinator(content) }

    func makeUIView(context: Context) -> FoldScrollView {
        let scroll = FoldScrollView()
        commands.scroll = scroll
        let host = context.coordinator.host
        host.view.translatesAutoresizingMaskIntoConstraints = false
        host.view.backgroundColor = .clear
        scroll.addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            host.view.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor),
        ])
        scroll.onOpenChange = { [coordinator = context.coordinator] open in
            coordinator.onOpenChange?(open)
        }
        scroll.onViewportChange = { [coordinator = context.coordinator] size in
            coordinator.layout(size)
        }
        return scroll
    }

    func updateUIView(_ scroll: FoldScrollView, context: Context) {
        context.coordinator.onOpenChange = { open in if isOpen != open { isOpen = open } }
        context.coordinator.update(content)
    }

    /// Carries the SwiftUI page inside the scroller and rebuilds it when the viewport changes.
    final class Coordinator {
        private var content: (CGSize) -> Content
        private var viewport: CGSize
        let host: UIHostingController<Content>
        /// Set on every update, so the scroll view always reports into the current state.
        var onOpenChange: ((Bool) -> Void)?

        init(_ content: @escaping (CGSize) -> Content) {
            self.content = content
            viewport = UIScreen.main.bounds.size
            host = UIHostingController(rootView: content(viewport))
            host.sizingOptions = [.intrinsicContentSize]
        }

        func update(_ content: @escaping (CGSize) -> Content) {
            self.content = content
            host.rootView = content(viewport)
        }

        func layout(_ size: CGSize) {
            guard size.width > 0, size.height > 0, size != viewport else { return }
            viewport = size
            host.rootView = content(size)
        }
    }
}
