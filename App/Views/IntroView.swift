import SwiftUI

/// Shown on first load, and again from Settings. Three steps, large text, one big button,
/// with the disclaimer at the bottom.
struct IntroView: View {
    @EnvironmentObject private var prefs: Preferences
    @Environment(\.palette) private var palette
    @State private var showCalibration = false

    private var seconds: Int { Int(ScanSession.targetSeconds) }

    var body: some View {
        ZStack {
            palette.backgroundGradient.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 0) {
                Text("VITALIBRE").font(.chakra(15, .medium)).tracking(3).foregroundStyle(palette.clock)
                    .padding(.top, 8)
                Spacer(minLength: 16)
                VStack(alignment: .leading, spacing: 26) {
                    step(1) {
                        Text("Set your typical resting blood pressure by tapping ")
                            + Text("Calibration").foregroundColor(palette.led).underline()
                            + Text(". This can be updated at any time.")
                    } action: { showCalibration = true }
                    step(2) {
                        Text("Press Start and cover the rear camera with your finger. Rest your hand on something so that you stay as still as possible.")
                    }
                    step(3) {
                        Text("The reading will take \(seconds) seconds once you begin. Results are an estimate.")
                    }
                }
                Spacer(minLength: 16)
                BigButton(title: "Get started") {
                    DialClick.shared.play()
                    prefs.onboardedAt = Date()
                }
                Text(Publisher.policy.disclaimer).font(.chakra(12, .medium)).foregroundStyle(palette.readout)
                    .padding(.top, 18)
                Text(Publisher.policy.disclaimerBody).font(.rajdhani(12)).foregroundStyle(palette.readoutSecondary)
                    .padding(.top, 4)
            }
            .padding(.horizontal, 24).padding(.bottom, 12)
        }
        .sheet(isPresented: $showCalibration) {
            CalibrationSettingsSheet().environment(\.palette, palette)
        }
    }

    private func step(_ n: Int, @ViewBuilder _ text: () -> Text, action: (() -> Void)? = nil) -> some View {
        Button { action?() } label: {
            HStack(alignment: .top, spacing: 16) {
                Text("\(n)").font(.chakra(22, .medium)).foregroundStyle(palette.led)
                    .shadow(color: palette.led.opacity(0.6), radius: 5)
                    .frame(width: 42, height: 42)
                    .background(
                        Circle().fill(RadialGradient(colors: [palette.panel.mixed(with: .white, palette.isLight ? 0 : 0.10),
                                                              palette.panel.mixed(with: .black, palette.isLight ? 0.08 : 0.45)],
                                                     center: UnitPoint(x: 0.35, y: 0.28), startRadius: 0, endRadius: 34))
                    )
                    .overlay(Circle().strokeBorder(LinearGradient(colors: [Color(white: palette.isLight ? 1 : 0.30), .clear, .black.opacity(0.7)],
                                                                  startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 1.5))
                    .shadow(color: .black.opacity(palette.isLight ? 0.25 : 0.7), radius: 6, y: 3)
                text().font(.chakra(21, .regular)).foregroundStyle(palette.readout)
                    .multilineTextAlignment(.leading).fixedSize(horizontal: false, vertical: true)
            }
        }
        .buttonStyle(.plain)
        .disabled(action == nil)
    }
}

/// A large confirm button in the ring's colours.
struct BigButton: View {
    @Environment(\.palette) private var palette
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title.uppercased())
                .font(.chakra(20, .semibold)).tracking(3)
                .foregroundStyle(Color.white)
                .frame(maxWidth: .infinity, minHeight: 66)
                .background(
                    Capsule().fill(LinearGradient(colors: palette.ringStops.map { $0.mixed(with: .white, 0.08) } + [palette.ringStops[0]],
                                                  startPoint: .leading, endPoint: .trailing))
                        .overlay(Capsule().fill(LinearGradient(colors: [.white.opacity(0.22), .clear, .black.opacity(0.35)],
                                                               startPoint: .top, endPoint: .bottom)))
                )
                .overlay(Capsule().stroke(palette.gridInk.opacity(0.35), lineWidth: 1))
                .shadow(color: .black.opacity(0.35), radius: 8, y: 4)
        }
        .buttonStyle(PressStyle())
    }
}
