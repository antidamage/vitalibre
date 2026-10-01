import SwiftUI

@main
struct VitaLibreApp: App {
    @StateObject private var prefs = Preferences()
    @StateObject private var readings = ReadingStore()
    @StateObject private var measurer = Measurer()

    init() {
        Fonts.register()
        DialClick.shared.prepare()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(prefs)
                .environmentObject(readings)
                .environmentObject(measurer)
                .preferredColorScheme(prefs.themeMode.forcedScheme)
        }
    }
}
