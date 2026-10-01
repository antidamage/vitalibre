import SwiftUI

/// A non-illuminating surface: panel colour, the border colour at its theme
/// opacity, and an inset shadow only. Never a gradient that reads as lit.
struct PanelStyle: ViewModifier {
    @Environment(\.palette) private var palette
    var radius: CGFloat = 16

    func body(content: Content) -> some View {
        content
            .background(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .fill(palette.panel)
                    .overlay(
                        RoundedRectangle(cornerRadius: radius, style: .continuous)
                            .stroke(LinearGradient(colors: [.black.opacity(palette.isLight ? 0.10 : 0.55), .clear],
                                                   startPoint: .top, endPoint: .bottom), lineWidth: 3)
                            .blur(radius: 2.5)
                            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
                    )
            )
            .overlay(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .stroke(palette.border.opacity(palette.borderOpacity), lineWidth: 1)
            )
            .shadow(color: .black.opacity(palette.isLight ? 0.10 : 0.35), radius: 10, y: 5)
    }
}

extension View {
    func panel(radius: CGFloat = 16) -> some View { modifier(PanelStyle(radius: radius)) }
}

struct SectionTitle: View {
    @Environment(\.palette) private var palette
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(.chakra(12, .medium)).tracking(2)
            .foregroundStyle(palette.clock)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Body copy in the secondary face.
struct BodyText: View {
    @Environment(\.palette) private var palette
    let text: String
    var body: some View {
        Text(text)
            .font(.rajdhani(16))
            .foregroundStyle(palette.readout.opacity(0.92))
            .lineSpacing(3)
            .frame(maxWidth: .infinity, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// Sinks a little when pressed, like a moulded key.
struct PressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.965 : 1)
            .brightness(configuration.isPressed ? -0.05 : 0)
            .animation(.easeOut(duration: 0.08), value: configuration.isPressed)
    }
}

/// The bottom navigation of the Codex build: console-style buttons with a symbol, a label and a small
/// indicator that lights for the current tab, on a surface with a lit top edge.
struct ConsoleBar<Tab: Hashable>: View {
    @Environment(\.palette) private var palette
    struct Item { let tab: Tab; let title: String; let symbol: String }
    let items: [Item]
    @Binding var selection: Tab
    let onSelect: () -> Void

    var body: some View {
        HStack(spacing: 5) {
            ForEach(items.indices, id: \.self) { i in
                let item = items[i]
                let selected = item.tab == selection
                Button {
                    onSelect()
                    selection = item.tab
                } label: {
                    VStack(spacing: 7) {
                        Image(systemName: item.symbol).font(.system(size: 17, weight: .light))
                        Text(item.title).font(.chakra(10)).lineLimit(1).minimumScaleFactor(0.7)
                        Capsule().fill(selected ? palette.led : palette.line).frame(width: 13, height: 2)
                    }.frame(maxWidth: .infinity)
                }
                .buttonStyle(ConsoleStyle(selected: selected))
                .accessibilityLabel(item.title)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(10)
        .background(palette.surface.ignoresSafeArea(edges: .bottom))
        .overlay(alignment: .top) { Rectangle().fill(palette.edge).frame(height: 1) }
    }
}

/// A pill button in the ring's colours (Save).
struct RingButton: View {
    @Environment(\.palette) private var palette
    let title: String
    var disabled = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title.uppercased())
                .font(.chakra(14, .semibold)).tracking(2)
                .foregroundStyle(Color.white.opacity(disabled ? 0.5 : 0.95))
                .padding(.horizontal, 34).padding(.vertical, 12)
                .background(
                    Capsule().fill(LinearGradient(colors: palette.ringStops.map { $0.mixed(with: .white, 0.08) } + [palette.ringStops[0]],
                                                  startPoint: .leading, endPoint: .trailing))
                        .overlay(Capsule().fill(LinearGradient(colors: [.white.opacity(0.22), .clear, .black.opacity(0.35)],
                                                               startPoint: .top, endPoint: .bottom)))
                )
                .overlay(Capsule().stroke(palette.gridInk.opacity(0.35), lineWidth: 1))
                .shadow(color: .black.opacity(0.35), radius: 6, y: 3)
        }
        .buttonStyle(PressStyle())
        .disabled(disabled)
    }
}
