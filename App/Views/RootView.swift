import SwiftUI

enum AppTab: Hashable { case measure, readings, help, about, donate }

struct RootView: View {
    @EnvironmentObject private var prefs: Preferences
    @EnvironmentObject private var measurer: Measurer
    @EnvironmentObject private var readings: ReadingStore
    @Environment(\.colorScheme) private var scheme
    @State private var tab: AppTab = .measure
    @State private var showSettings = false

    private var palette: Palette { scheme == .dark ? .dark : .light }

    private let items: [ConsoleBar<AppTab>.Item] = [
        .init(tab: .measure, title: "Measure", symbol: "waveform.path.ecg"),
        .init(tab: .readings, title: "Readings", symbol: "list.bullet.rectangle"),
        .init(tab: .help, title: "Help", symbol: "questionmark.circle"),
        .init(tab: .about, title: "About", symbol: "info.circle"),
        .init(tab: .donate, title: "Donate", symbol: "heart"),
    ]

    var body: some View {
        ZStack {
            palette.backgroundGradient.ignoresSafeArea()
            VStack(spacing: 0) {
                header
                Group {
                    switch tab {
                    case .measure: MeasureView()
                    case .readings: ReadingsView()
                    case .help: HelpView()
                    case .about: AboutView()
                    case .donate: DonateView()
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                ConsoleBar(items: items, selection: $tab) { DialClick.shared.play() }
            }
        }
        .environment(\.palette, palette)
        .fullScreenCover(isPresented: Binding(get: { prefs.onboardedAt == nil }, set: { _ in })) {
            IntroView().environment(\.palette, palette)
        }
        .sheet(isPresented: $showSettings) {
            SettingsView()
                .environment(\.palette, palette)
                .presentationDetents([.large])
        }
        // Filed here, not on the measure screen: that screen is rebuilt on every
        // tab change, and a scan that finishes while another tab is showing has to
        // land in the log just the same. `file` is keyed by the scan, so seeing the
        // same result again cannot add a second reading.
        .onChange(of: measurer.phase) { _, phase in
            if case .result(let r) = phase { readings.file(r, scanID: measurer.scanID) }
        }
    }

    private var header: some View {
        HStack {
            Text("VITALIBRE")
                .font(.chakra(15, .medium)).tracking(3)
                .foregroundStyle(palette.clock)
            Spacer()
            Button {
                DialClick.shared.play()
                showSettings = true
            } label: {
                Image(systemName: "gearshape").font(.system(size: 19))
                    .foregroundStyle(palette.clock)
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel("Settings")
        }
        .padding(.horizontal, 20).padding(.top, 2)
    }
}
