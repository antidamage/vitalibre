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

/// One label in the bottom bar: no plate of its own, the bar is the surface.
struct ConsoleButton: View {
    @Environment(\.palette) private var palette
    let title: String
    let symbol: String
    let active: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: symbol).font(.system(size: 18, weight: .regular))
                Text(title.uppercased()).font(.chakra(10, .medium)).tracking(1.2).lineLimit(1).minimumScaleFactor(0.7)
            }
            .foregroundStyle(active ? palette.led : palette.clock)
            .shadow(color: active ? palette.led.opacity(0.7) : .black.opacity(palette.isLight ? 0 : 0.6), radius: active ? 6 : 1, y: active ? 0 : 1)
            .frame(maxWidth: .infinity, minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
        .accessibilityLabel(title)
        .accessibilityAddTraits(active ? [.isSelected] : [])
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

/// A vertical groove cut into the bar: a dark line with a light line beside it, fading at the ends.
struct SunkenDivider: View {
    @Environment(\.palette) private var palette
    var body: some View {
        HStack(spacing: 0) {
            Rectangle().fill(.black.opacity(palette.isLight ? 0.28 : 0.85)).frame(width: 1)
            Rectangle().fill(.white.opacity(palette.isLight ? 0.95 : 0.14)).frame(width: 1)
        }
        .frame(height: 38)
        .mask(LinearGradient(colors: [.clear, .black, .black, .clear], startPoint: .top, endPoint: .bottom))
    }
}

/// A single bar across the whole width, square at the sides and bottom. Its top edge is a
/// rounded bullnose, shown by lighting: a lit crest, then falling away into shade.
struct ConsoleBar<Tab: Hashable>: View {
    @Environment(\.palette) private var palette
    struct Item { let tab: Tab; let title: String; let symbol: String }
    let items: [Item]
    @Binding var selection: Tab
    let onSelect: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items.indices, id: \.self) { i in
                if i > 0 { SunkenDivider() }
                ConsoleButton(title: items[i].title, symbol: items[i].symbol, active: items[i].tab == selection) {
                    onSelect()
                    selection = items[i].tab
                }
            }
        }
        .padding(.top, 10).padding(.bottom, 2)
        .frame(maxWidth: .infinity)
        .background(surface.ignoresSafeArea(edges: .bottom))
    }

    private var surface: some View {
        let base = palette.background.mixed(with: .black, palette.isLight ? 0.04 : 0.28)
        return Rectangle()
            .fill(LinearGradient(colors: [base.mixed(with: .white, palette.isLight ? 0.5 : 0.07), base,
                                          base.mixed(with: .black, palette.isLight ? 0.05 : 0.3)],
                                 startPoint: .top, endPoint: .bottom))
            .overlay(alignment: .top) {
                // The rounded top: a bright crest line, then a soft band curving away.
                VStack(spacing: 0) {
                    Rectangle().fill(.white.opacity(palette.isLight ? 1 : 0.22)).frame(height: 1)
                    LinearGradient(colors: [.white.opacity(palette.isLight ? 0.6 : 0.10), .clear],
                                   startPoint: .top, endPoint: .bottom).frame(height: 14)
                }
            }
            .overlay(alignment: .top) {
                Rectangle().fill(.black.opacity(palette.isLight ? 0.12 : 0.55)).frame(height: 1).offset(y: -1)
            }
            .shadow(color: .black.opacity(palette.isLight ? 0.18 : 0.7), radius: 9, y: -3)
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
