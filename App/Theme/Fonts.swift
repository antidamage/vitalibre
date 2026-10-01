import SwiftUI
import CoreText

/// Chakra Petch (UI, numerals) and Rajdhani (secondary), both OFL, bundled.
/// Registered at runtime so no Info.plist font list is needed. A missing file
/// falls back to the system font rather than failing.
enum Fonts {
    static func register() {
        guard let files = FileManager.default.enumerator(at: Bundle.main.bundleURL, includingPropertiesForKeys: nil) else { return }
        for case let url as URL in files where url.pathExtension.lowercased() == "ttf" {
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }
}

extension Font {
    enum Face { case light, regular, medium, semibold }

    static func chakra(_ size: CGFloat, _ weight: Face = .regular) -> Font {
        let name: String
        switch weight {
        case .light: name = "ChakraPetch-Light"
        case .regular: name = "ChakraPetch-Regular"
        case .medium: name = "ChakraPetch-Medium"
        case .semibold: name = "ChakraPetch-SemiBold"
        }
        return .custom(name, size: size)
    }

    static func rajdhani(_ size: CGFloat, semibold: Bool = false) -> Font {
        .custom(semibold ? "Rajdhani-SemiBold" : "Rajdhani-Medium", size: size)
    }
}
