import SwiftUI

/// What the fold owns, shared so the screen it sits on can size it against the
/// space it has without repeating these numbers.
enum FoldMetrics {
    /// The upward travel the band absorbs, and the most it gives while holding.
    static let band: CGFloat = 80
    static let give: CGFloat = 28
    /// The divider's own height: a caption, a gap, and the sunken line.
    static let rest: CGFloat = 30
    /// Less than a row of its content and there is nothing worth opening into.
    static let minReveal: CGFloat = 44
    /// The room the fold holds at the foot of the screen: its own line plus the space the
    /// panel opens into. It is a height in the layout, held open whether the fold is open or
    /// shut, so opening moves nothing and the panel can never reach the controls above it.
    static let room: CGFloat = 150
    /// What the panel may open into: that room, less the line.
    static var maxReveal: CGFloat { room - rest }
}

/// The fold: a line that rests just above the bottom menu, pulled up to reveal
/// what it guards. The mechanism is the dashboard's advanced fold
/// (nova-ha-dashboard/specs/advanced-fold.md), applied to a phone screen.
///
/// 80pt of upward travel is caught by a quadratic band, d(p) = 28(1 - (1 - p/80)^2):
/// nearly 1:1 at first, no movement at all by 80pt. Released inside the band the
/// region springs back over 180ms, ease-out. At 80pt it breaks and the region
/// follows the finger 1:1 from there. Pulling back down to nothing re-locks it,
/// with no resistance on the way back. With nothing past the line it does not
/// open at all. A tap opens or closes it, which is also what VoiceOver uses.
struct FoldBand<Content: View>: View {
    @Environment(\.palette) private var palette
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    let label: String
    var count = 0
    let maxReveal: CGFloat
    @ViewBuilder var content: () -> Content

    @State private var reveal: CGFloat = 0
    @State private var pull: CGFloat = 0
    @State private var broken = false
    @State private var grab: CGFloat? = nil
    @State private var moved = false

    /// The travel the band is asked to absorb, and the most it gives while holding.
    private static var band: CGFloat { FoldMetrics.band }
    private static var give: CGFloat { FoldMetrics.give }
    /// The divider's own height: a caption, a gap, and the sunken line.
    private static var restHeight: CGFloat { FoldMetrics.rest }

    private var isOpen: Bool { reveal > 0 }
    /// It needs something to show and somewhere to show it: a fold that opened
    /// into less than one row would be worse than one that does not open.
    private var canOpen: Bool { count > 0 && maxReveal >= FoldMetrics.minReveal }

    private func rubber(_ p: CGFloat) -> CGFloat {
        let f = min(1, p / Self.band)
        return Self.give * (1 - (1 - f) * (1 - f))
    }

    var body: some View {
        VStack(spacing: 0) {
            if isOpen {
                content()
                    .frame(height: reveal, alignment: .top)
                    .clipped()
            }
            divider
        }
        .frame(maxWidth: .infinity)
        .onChange(of: count) { _, new in
            if new == 0, isOpen { close(animated: false) }
        }
    }

    // MARK: The line

    private var divider: some View {
        VStack(spacing: 4) {
            HStack(spacing: 7) {
                Spacer(minLength: 12)
                Text(label.uppercased())
                    .font(.chakra(11, .medium)).tracking(1.8)
                    .foregroundStyle(isOpen ? palette.led : palette.readoutSecondary)
                if count > 0 {
                    Text("\(count)").font(.rajdhani(12, semibold: true)).foregroundStyle(palette.led)
                }
                Image(systemName: "arrowtriangle.up.fill")
                    .font(.system(size: 8))
                    .foregroundStyle(isOpen ? palette.led : palette.readoutSecondary)
                    .rotationEffect(.degrees(isOpen ? 180 : 0))
            }
            // The line in the theme's accent, with the accent's lit edge under it: the
            // dashboard's sunken bevel (advanced-fold.css), scaled up for a small screen
            // where the accent's own 18% edge would not read.
            VStack(spacing: 0) {
                Rectangle().fill(palette.line).frame(height: 1)
                Rectangle().fill(palette.line.opacity(palette.isLight ? 0.9 : 0.45)).frame(height: 1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .bottom)
        .frame(height: Self.restHeight, alignment: .bottom)
        .contentShape(Rectangle())
        .gesture(drag)
        .accessibilityElement()
        .accessibilityLabel(label)
        .accessibilityValue(isOpen ? "open" : "closed")
        .accessibilityAddTraits(.isButton)
        .accessibilityHint(canOpen ? "Opens and closes the list"
                                   : (count == 0 ? "Nothing taken yet today" : "Not enough room to open"))
        .accessibilityAction { toggle() }
    }

    // MARK: The gesture

    private var drag: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { g in
                if grab == nil { grab = reveal; pull = 0; moved = false }
                let dy = g.translation.height
                if abs(dy) > 4 { moved = true }
                if broken || reveal > 0 {
                    // Open: it tracks the finger both ways, with the cap as the limit.
                    reveal = min(maxReveal, max(0, (grab ?? 0) - dy))
                    if reveal <= 0 { broken = false; pull = 0 }
                } else if canOpen {
                    pull = max(0, -dy)
                    if pull >= Self.band {
                        broken = true
                        let target = min(maxReveal, pull)
                        if reduceMotion { reveal = target } else {
                            withAnimation(.timingCurve(0.2, 0.9, 0.3, 1.15, duration: 0.22)) { reveal = target }
                        }
                    } else {
                        reveal = rubber(pull)
                    }
                }
            }
            .onEnded { _ in
                let tapped = !moved
                grab = nil; moved = false
                if tapped { toggle(); return }
                if broken {
                    if reveal <= 0 { broken = false; pull = 0 }
                } else {
                    close(animated: !reduceMotion)
                }
            }
    }

    private func close(animated: Bool) {
        broken = false; pull = 0
        if animated { withAnimation(.easeOut(duration: 0.18)) { reveal = 0 } } else { reveal = 0 }
    }

    private func toggle() {
        guard canOpen else { return }
        DialClick.shared.play()
        let target: CGFloat = isOpen ? 0 : maxReveal
        broken = target > 0
        pull = 0
        if reduceMotion { reveal = target } else {
            withAnimation(.timingCurve(0.2, 0.9, 0.3, 1.15, duration: 0.22)) { reveal = target }
        }
    }
}
