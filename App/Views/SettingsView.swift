import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var prefs: Preferences
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @State private var showCalibration = false

    private var isSimulator: Bool {
        #if targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
    }

    var body: some View {
        ZStack {
            palette.backgroundGradient.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 14) {
                    HStack {
                        Text("SETTINGS").font(.chakra(15, .medium)).tracking(3).foregroundStyle(palette.clock)
                        Spacer()
                        Button("Done") { dismiss() }
                            .font(.chakra(14, .medium)).foregroundStyle(palette.led)
                    }
                    theme
                    profile
                    calibration
                    disclaimer
                    if isSimulator { simulator }
                }
                .padding(16)
            }
        }
    }

    private var theme: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionTitle(text: "Theme")
            HStack(spacing: 0) {
                ForEach(ThemeMode.allCases) { mode in
                    Button {
                        DialClick.shared.play()
                        prefs.themeMode = mode
                    } label: {
                        Text(mode.label).font(.chakra(14, .medium))
                            .foregroundStyle(prefs.themeMode == mode ? palette.led : palette.clock)
                            .frame(maxWidth: .infinity, minHeight: 38)
                            .background(RoundedRectangle(cornerRadius: 9, style: .continuous)
                                .fill(prefs.themeMode == mode ? palette.background.mixed(with: .black, palette.isLight ? 0.06 : 0.35) : .clear))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(3).background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(palette.background.mixed(with: .black, palette.isLight ? 0.03 : 0.15)))
        }
        .padding(16).panel()
    }

    private var profile: some View {
        VStack(alignment: .leading, spacing: 12) {
            row("Age") {
                Picker("Age", selection: $prefs.age) {
                    Text("Not set").tag(0)
                    ForEach(18...100, id: \.self) { Text("\($0)").tag($0) }
                }
                .pickerStyle(.menu).tint(palette.led)
            }
            row("Sex") {
                Picker("Sex", selection: $prefs.sex) {
                    ForEach(Sex.allCases, id: \.self) { Text($0 == .unspecified ? "Not set" : $0.rawValue.capitalized).tag($0) }
                }
                .pickerStyle(.menu).tint(palette.led)
            }
        }
        .padding(16).panel()
    }

    private var calibration: some View {
        Button {
            showCalibration = true
        } label: {
            row("Calibration") {
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .medium)).foregroundStyle(palette.clock)
            }
        }
        .buttonStyle(.plain)
        .padding(16).panel()
        .sheet(isPresented: $showCalibration) {
            CalibrationSettingsSheet().environment(\.palette, palette)
        }
    }

    private var disclaimer: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionTitle(text: "Not a medical device")
            BodyText(text: Publisher.policy.disclaimerBody)
            Button("Show the intro again") { prefs.onboardedAt = nil; dismiss() }
                .font(.chakra(12, .medium)).foregroundStyle(palette.led)
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading).panel()
    }

    private var simulator: some View {
        Toggle(isOn: $prefs.simulatedPulse) {
            Text("Simulated pulse").font(.chakra(14, .medium)).foregroundStyle(palette.readout)
        }
        .tint(palette.led)
        .padding(16).panel()
    }

    private func row<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
        HStack {
            Text(title).font(.chakra(14, .medium)).foregroundStyle(palette.readout)
            Spacer()
            content()
        }
    }
}
