import SwiftUI

/// What the fold's line owns: its own height. The band's numbers live with the logic that uses
/// them, in `Core/FoldBand.swift`, so both apps' screens cannot drift apart on them.
enum FoldMetrics {
    /// The divider's own height: a caption, a gap, and the sunken line.
    static let rest: CGFloat = 30
}

/// Lets the page's own line ask its scroller to open or shut, so the two do not have to know each
/// other. The line is inside the page the scroller hosts, so it cannot hold the scroller itself.
final class FoldCommands {
    weak var scroll: FoldScrollView?
    func toggle() { scroll?.toggle() }
}

/// The fold's line: the caption, the count of what it guards, a triangle pointing the way it
/// opens, and the sunken bevel in the theme's accent. It rests on the bottom of the screen area,
/// just above the bottom bar, and rides up with the page when the fold is pulled open.
struct FoldDivider: View {
    @Environment(\.palette) private var palette
    let label: String
    var count = 0
    let isOpen: Bool
    let canOpen: Bool
    let onToggle: () -> Void

    var body: some View {
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
        .frame(height: FoldMetrics.rest, alignment: .bottom)
        .contentShape(Rectangle())
        .onTapGesture(perform: onToggle)
        .accessibilityElement()
        .accessibilityLabel(label)
        .accessibilityValue(isOpen ? "open" : "closed")
        .accessibilityAddTraits(.isButton)
        .accessibilityHint(canOpen ? "Opens and closes the list"
                                   : (count == 0 ? "Nothing taken yet today" : "Not enough room to open"))
        .accessibilityAction { onToggle() }
    }
}
