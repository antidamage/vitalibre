import SwiftUI

// The instrument styling of the Codex build (CutCornerShape, console buttons, section panels, headings,
// data rows), adapted from its Controls.swift to this app's palette. CutCornerShape itself comes from
// NovaAppleTVDashboard/DashboardComponents.swift.

extension Palette {
    var soft: Color { isLight ? Color(hex: 0xF2F2F2) : Color(hex: 0x1F1F1F) }
    var line: Color { isLight ? Color(hex: 0xCCCCCC) : Color(hex: 0x3B3532) }
    var edge: LinearGradient {
        LinearGradient(colors: [.white.opacity(isLight ? 0.95 : 0.16), .black.opacity(isLight ? 0.08 : 0.8)],
                       startPoint: .topLeading, endPoint: .bottomTrailing)
    }
    var surface: LinearGradient { LinearGradient(colors: [soft, panel], startPoint: .topLeading, endPoint: .bottomTrailing) }
}

struct CutCornerShape: Shape {
    var cut: CGFloat = 12
    func path(in rect: CGRect) -> Path {
        Path { p in
            p.move(to: .init(x: rect.minX, y: rect.minY))
            p.addLine(to: .init(x: rect.maxX - cut, y: rect.minY))
            p.addLine(to: .init(x: rect.maxX, y: rect.minY + cut))
            p.addLine(to: .init(x: rect.maxX, y: rect.maxY))
            p.addLine(to: .init(x: rect.minX, y: rect.maxY)); p.closeSubpath()
        }
    }
}

struct ConsoleStyle: ButtonStyle {
    @Environment(\.palette) private var palette
    @Environment(\.isEnabled) private var enabled
    var selected = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.chakra(13)).foregroundStyle(selected ? palette.readout : palette.readoutSecondary)
            .padding(.horizontal, 12).padding(.vertical, 13).frame(minHeight: 46)
            .background {
                RoundedRectangle(cornerRadius: 9).fill(palette.surface)
                    .shadow(color: .black.opacity(palette.isLight ? 0.10 : 0.4), radius: 3, y: 2)
                    .overlay(RoundedRectangle(cornerRadius: 9).stroke(palette.edge, lineWidth: 1))
                    .overlay(RoundedRectangle(cornerRadius: 9).stroke(selected ? palette.led.opacity(0.55) : .clear, lineWidth: 1))
            }
            .opacity(enabled ? 1 : 0.45)
            .scaleEffect(configuration.isPressed ? 0.975 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

struct SectionPanel<Content: View>: View {
    @Environment(\.palette) private var palette
    let title: String
    let symbol: String
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 10) {
                Image(systemName: symbol).foregroundStyle(palette.led)
                Text(title.uppercased()).tracking(2)
                Spacer()
                RoundedRectangle(cornerRadius: 1).fill(palette.led.opacity(0.7)).frame(width: 16, height: 2)
            }.font(.chakra(12))
            Rectangle().fill(palette.line).frame(height: 1)
            content
        }
        .padding(20).background(palette.surface, in: CutCornerShape())
        .overlay(CutCornerShape().stroke(palette.edge, lineWidth: 1))
    }
}

struct ScreenHeading: View {
    @Environment(\.palette) private var palette
    let title: String
    let subtitle: String
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title).font(.chakra(28)).foregroundStyle(palette.readout)
            Text(subtitle).font(.rajdhani(17)).foregroundStyle(palette.readoutSecondary)
        }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 12)
    }
}

struct DataRow: View {
    @Environment(\.palette) private var palette
    let title: String
    let value: String
    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack { Text(title).foregroundStyle(palette.readoutSecondary); Spacer(); Text(value).foregroundStyle(palette.readout) }
            VStack(alignment: .leading, spacing: 4) { Text(title).foregroundStyle(palette.readoutSecondary); Text(value).foregroundStyle(palette.readout) }
        }.font(.rajdhani(17)).accessibilityElement(children: .combine)
    }
}
