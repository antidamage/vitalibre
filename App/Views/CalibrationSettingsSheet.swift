import SwiftUI

/// Where the person's typical resting blood pressure is set (for example from a medical
/// record), and where saved cuff calibrations are counted and reset.
struct CalibrationSettingsSheet: View {
    @EnvironmentObject private var prefs: Preferences
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            palette.backgroundGradient.ignoresSafeArea()
            VStack(spacing: 16) {
                HStack {
                    Text("CALIBRATION").font(.chakra(15, .medium)).tracking(3).foregroundStyle(palette.clock)
                    Spacer()
                    Button("Done") { prefs.usualConfirmed = true; dismiss() }.font(.chakra(14, .medium)).foregroundStyle(palette.led)
                }
                VStack(alignment: .leading, spacing: 12) {
                    SectionTitle(text: "Typical resting blood pressure")
                    HStack(spacing: 16) {
                        picker("Systolic", $prefs.usualSystolic, 70...220)
                        picker("Diastolic", $prefs.usualDiastolic, 40...140)
                    }
                }
                .padding(16).panel()

                HStack {
                    Text("Cuff calibrations").font(.chakra(14, .medium)).foregroundStyle(palette.readout)
                    Spacer()
                    Text("\(prefs.calibration.count)").font(.chakra(14, .medium)).foregroundStyle(palette.readout)
                    if prefs.calibration.count > 0 || prefs.usualConfirmed {
                        Button("Reset") {
                            prefs.calibration = BPCalibration()
                            prefs.usualConfirmed = false
                            prefs.usualSystolic = Preferences.defaultSystolic
                            prefs.usualDiastolic = Preferences.defaultDiastolic
                        }
                            .font(.chakra(13, .medium)).foregroundStyle(palette.led).padding(.leading, 10)
                    }
                }
                .padding(16).panel()
                Spacer()
            }
            .padding(20)
        }
        .presentationDetents([.medium, .large])
    }

    private func picker(_ title: String, _ value: Binding<Int>, _ range: ClosedRange<Int>) -> some View {
        VStack(spacing: 4) {
            Text(title.uppercased()).font(.rajdhani(12, semibold: true)).tracking(1.5).foregroundStyle(palette.readoutSecondary)
            Picker(title, selection: value) {
                ForEach(range, id: \.self) { Text("\($0)").tag($0) }
            }
            .pickerStyle(.wheel).frame(height: 130)
        }
        .frame(maxWidth: .infinity)
    }
}
