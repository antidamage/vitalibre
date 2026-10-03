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

    private func symbol(_ mode: ThemeMode) -> String {
        switch mode { case .auto: return "circle.lefthalf.filled"; case .light: return "sun.max"; case .dark: return "moon" }
    }

    var body: some View {
        ZStack {
            palette.backgroundGradient.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    HStack(alignment: .top) {
                        ScreenHeading(title: "Settings", subtitle: "Make yourself comfortable.")
                        Button("Done") { dismiss() }.font(.chakra(14, .medium)).foregroundStyle(palette.led).padding(.top, 18)
                    }
                    appearance
                    aboutYou
                    SectionPanel(title: "Calibration", symbol: "slider.horizontal.3") {
                        if let line = BPPresentation.expiryLine(prefs) {
                            Text(line).font(.rajdhani(15)).foregroundStyle(palette.readoutSecondary)
                        } else if BPPresentation.mode != "never" {
                            Text("Blood pressure is hidden until you calibrate with a cuff reading.")
                                .font(.rajdhani(15)).foregroundStyle(palette.readoutSecondary)
                        }
                        Button { showCalibration = true } label: {
                            HStack { Text("Typical pressure and cuff readings"); Spacer(); Image(systemName: "chevron.right") }
                                .frame(maxWidth: .infinity)
                        }.buttonStyle(ConsoleStyle())
                    }
                    SectionPanel(title: "Your data", symbol: "lock.shield") {
                        DataRow(title: "Storage", value: "On this device")
                        DataRow(title: "Account", value: "Not required")
                        Text("Readings stay here. Nothing is sent to the developer or third parties. You choose what to share using the share sheet.")
                            .font(.rajdhani(16)).foregroundStyle(palette.readoutSecondary)
                    }
                    SectionPanel(title: "Not a medical device", symbol: "info.circle") {
                        Text(Publisher.policy.disclaimerBody).font(.rajdhani(16)).foregroundStyle(palette.readoutSecondary)
                        Text(Publisher.policy.regulatory).font(.rajdhani(16)).foregroundStyle(palette.readoutSecondary)
                        Button("Show the intro again") { prefs.onboardedAt = nil; dismiss() }.buttonStyle(ConsoleStyle())
                    }
                    if isSimulator {
                        SectionPanel(title: "Simulator", symbol: "play.circle") {
                            Toggle(isOn: $prefs.simulatedPulse) { Text("Simulated pulse").font(.chakra(14, .medium)) }.tint(palette.led)
                        }
                    }
                }
                .padding(.horizontal, 22).padding(.bottom, 24).frame(maxWidth: 640).frame(maxWidth: .infinity)
            }
        }
        .sheet(isPresented: $showCalibration) {
            CalibrationSettingsSheet().environment(\.palette, palette)
        }
    }

    private var appearance: some View {
        SectionPanel(title: "Appearance", symbol: "circle.lefthalf.filled") {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("Colour theme").font(.chakra(18))
                    Text("Auto follows your device.").font(.rajdhani(16)).foregroundStyle(palette.readoutSecondary)
                }
                Spacer()
                HStack(spacing: 4) {
                    ForEach(Array(palette.ringInk.enumerated()), id: \.offset) { _, color in
                        Circle().fill(color).frame(width: 7, height: 7)
                    }
                }.accessibilityHidden(true)
            }
            HStack(spacing: 7) {
                ForEach(ThemeMode.allCases) { mode in
                    Button { prefs.themeMode = mode; DialClick.shared.play() } label: {
                        VStack(spacing: 11) {
                            Image(systemName: symbol(mode)).font(.system(size: 19, weight: .light))
                            Text(mode.label)
                            Capsule().fill(prefs.themeMode == mode ? palette.led : palette.line).frame(width: 14, height: 2)
                        }.frame(maxWidth: .infinity)
                    }
                    .buttonStyle(ConsoleStyle(selected: prefs.themeMode == mode))
                    .accessibilityLabel(mode.label + " theme")
                    .accessibilityAddTraits(prefs.themeMode == mode ? .isSelected : [])
                }
            }.padding(7).background(palette.background, in: RoundedRectangle(cornerRadius: 12))
        }
    }

    /// Age is chosen on the built-in number wheel, not a drop-down.
    private var aboutYou: some View {
        SectionPanel(title: "About you", symbol: "person") {
            HStack(alignment: .center) {
                Text("Age").font(.chakra(18))
                Spacer()
                Picker("Age", selection: $prefs.age) {
                    Text("Not set").tag(0)
                    ForEach(18...100, id: \.self) { Text("\($0)").tag($0) }
                }
                .pickerStyle(.wheel).frame(width: 150, height: 110).clipped()
            }
            HStack(spacing: 7) {
                ForEach(Sex.allCases, id: \.self) { sex in
                    let on = prefs.sex == sex
                    Button { prefs.sex = sex; DialClick.shared.play() } label: {
                        VStack(spacing: 9) {
                            Text(sex == .unspecified ? "Not set" : sex.rawValue.capitalized)
                            Capsule().fill(on ? palette.led : palette.line).frame(width: 14, height: 2)
                        }.frame(maxWidth: .infinity)
                    }
                    .buttonStyle(ConsoleStyle(selected: on))
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }.padding(7).background(palette.background, in: RoundedRectangle(cornerRadius: 12))
        }
    }
}
