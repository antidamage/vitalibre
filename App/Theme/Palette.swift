import SwiftUI
import UIKit

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: opacity)
    }

    /// Linear mix toward `other` in sRGB, `amount` 0...1.
    func mixed(with other: Color, _ amount: Double) -> Color {
        var a: (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0), b = a
        UIColor(self).getRed(&a.0, green: &a.1, blue: &a.2, alpha: &a.3)
        UIColor(other).getRed(&b.0, green: &b.1, blue: &b.2, alpha: &b.3)
        let t = CGFloat(max(0, min(1, amount)))
        return Color(.sRGB, red: Double(a.0 + (b.0 - a.0) * t), green: Double(a.1 + (b.1 - a.1) * t),
                     blue: Double(a.2 + (b.2 - a.2) * t), opacity: Double(a.3 + (b.3 - a.3) * t))
    }
}

/// Psionyk 1977, copied from the live dashboard theme (GET /api/theme,
/// 2026-09-30) as APPLIED colours: raw palette rgb x intensity / 100.
/// Nothing here reads from, or refers to, the dashboard at runtime.
/// See specs/ppg-vitals-app.md for the source table.
struct Palette {
    let isLight: Bool
    let background: Color
    let panel: Color
    let border: Color
    let borderOpacity: Double
    let accent: Color
    let highlight: Color
    let clock: Color
    /// Every highlight outside the graph (labels, links, lamps, glows). It is the graph's peak colour:
    /// dark `#FF5A4F`. Light uses the graph's third stop `#129C8E`, because the mint peak `#4FE3C1`
    /// is too pale to read as text on a light background.
    let led: Color
    let plate: Color              // orb gradientOuter
    let alert: Color
    /// Donation sparks. Dark: the highlight's raw colour, rgb(255,91,0). Light: the
    /// highlight applies to a pale grey that would vanish on a light background, so the LED colour stands in.
    let spark: Color
    /// The orb's own linework for the mode, as the dashboard applies it (raw rgb x intensity).
    /// Dark: the dashboard's (255,5,6)@94 and (255,0,44)@100, with its orange third stop (255,160,0)
    /// replaced by a red-pink (255,42,102) at the owner's request, painted at 80/90/80% so it reads as red, not washed out.
    /// Light: (0,245,255)@23, (0,255,115)@30, (0,143,255)@33, the deep green-blue, painted opaque.
    let ringStops: [Color]
    /// The same colours without the line opacities, for lifted grid ink, buttons and confetti.
    let ringInk: [Color]
    /// The heartbeat trace's core colour; the LED colour glows around it.
    let trace: Color
    /// Dome face treatment: the dark theme's orb uses the dark face, light the light face.
    let lightFace: Bool
    /// Legible readout colours. The dashboard's orb numerals are deliberately dim;
    /// a health reading needs more contrast, so these are lifted.
    let readout: Color
    let readoutSecondary: Color

    static let darkInk: [Color] = [Color(hex: 0xF00506), Color(hex: 0xFF002C), Color(hex: 0xFF2A66)]
    static let lightInk: [Color] = [Color(hex: 0x00383B), Color(hex: 0x004D23), Color(hex: 0x002F54)]

    static let dark = Palette(
        isLight: false, background: Color(hex: 0x121212), panel: Color(hex: 0x171717),
        border: Color(hex: 0x1F1F1F), borderOpacity: 0.15, accent: Color(hex: 0x42322A),
        highlight: Color(hex: 0x802E00), clock: Color(hex: 0x919191), led: Color(hex: 0xFF5A4F),
        plate: Color(hex: 0x1C1C1C), alert: Color(hex: 0xFF2F00), spark: Color(hex: 0xFF5A4F),
        ringStops: [darkInk[0].opacity(0.80), darkInk[1].opacity(0.90), darkInk[2].opacity(0.80)], ringInk: darkInk,
        trace: Color(hex: 0xFFE6D6),
        lightFace: false, readout: Color(hex: 0xE4E4E4), readoutSecondary: Color(hex: 0x919191))

    static let light = Palette(
        isLight: true, background: Color(hex: 0xE8E8E8), panel: Color(hex: 0xFFFFFF),
        border: Color(hex: 0xFFFFFF), borderOpacity: 0.75, accent: Color(hex: 0xFFFDFD),
        highlight: Color(hex: 0xC4C4C4), clock: Color(hex: 0xA1A1A1), led: Color(hex: 0x129C8E),
        plate: Color(hex: 0xFFFFFF), alert: Color(hex: 0x93FFF9), spark: Color(hex: 0x129C8E),
        ringStops: lightInk, ringInk: lightInk, trace: Color(hex: 0xFF7D5E),
        lightFace: true, readout: Color(hex: 0x4A4A4A), readoutSecondary: Color(hex: 0x626262))

    /// Top-lit background: a very slight lift at the top, then the flat colour.
    var backgroundGradient: LinearGradient {
        let lift = isLight ? Color.white : Color.white
        return LinearGradient(colors: [background.mixed(with: lift, isLight ? 0.35 : 0.035), background,
                                       background.mixed(with: .black, isLight ? 0.05 : 0.25)],
                              startPoint: .top, endPoint: .bottom)
    }

    /// Colour of ring hairlines and glow: the brightest ring stop lifted toward white.
    var gridInk: Color { ringInk[0].mixed(with: .white, 0.55) }
}

private struct PaletteKey: EnvironmentKey { static let defaultValue = Palette.dark }

extension EnvironmentValues {
    var palette: Palette {
        get { self[PaletteKey.self] }
        set { self[PaletteKey.self] = newValue }
    }
}

enum ThemeMode: String, CaseIterable, Identifiable {
    case auto, light, dark
    var id: String { rawValue }
    var label: String { rawValue.capitalized }
    var forcedScheme: ColorScheme? {
        switch self { case .auto: return nil; case .light: return .light; case .dark: return .dark }
    }
}
