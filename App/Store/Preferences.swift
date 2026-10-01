import SwiftUI

/// User preferences, on the device only.
final class Preferences: ObservableObject {
    private let defaults = UserDefaults.standard

    @Published var themeMode: ThemeMode { didSet { defaults.set(themeMode.rawValue, forKey: "themeMode") } }
    /// 0 = not given.
    @Published var age: Int { didSet { defaults.set(age, forKey: "age") } }
    @Published var sex: Sex { didSet { defaults.set(sex.rawValue, forKey: "sex") } }
    /// When the first-load screen was confirmed; nil = show it.
    @Published var onboardedAt: Date? {
        didSet { defaults.set(onboardedAt?.timeIntervalSince1970, forKey: "onboardedAt") }
    }
    /// Usual resting blood pressure from a medical record; 0 = not set.
    @Published var usualSystolic: Int { didSet { defaults.set(usualSystolic, forKey: "usualSystolic") } }
    @Published var usualDiastolic: Int { didSet { defaults.set(usualDiastolic, forKey: "usualDiastolic") } }
    var usual: UsualBP? {
        let u = UsualBP(systolic: usualSystolic, diastolic: usualDiastolic)
        return usualSystolic > 0 && usualDiastolic > 0 && u.isPlausible ? u : nil
    }
    /// Cuff readings paired with scans; shifts and narrows the blood pressure estimate.
    @Published var calibration: BPCalibration {
        didSet { defaults.set(try? JSONEncoder().encode(calibration), forKey: "bpCalibration") }
    }
    @Published var simulatedPulse: Bool { didSet { defaults.set(simulatedPulse, forKey: "simulatedPulse") } }

    init() {
        themeMode = ThemeMode(rawValue: defaults.string(forKey: "themeMode") ?? "") ?? .dark
        age = defaults.integer(forKey: "age")
        sex = Sex(rawValue: defaults.string(forKey: "sex") ?? "") ?? .unspecified
        let done = defaults.object(forKey: "onboardedAt") as? Double
        onboardedAt = done.map { Date(timeIntervalSince1970: $0) }
        usualSystolic = defaults.integer(forKey: "usualSystolic")
        usualDiastolic = defaults.integer(forKey: "usualDiastolic")
        calibration = (defaults.data(forKey: "bpCalibration").flatMap { try? JSONDecoder().decode(BPCalibration.self, from: $0) }) ?? BPCalibration()
        simulatedPulse = defaults.object(forKey: "simulatedPulse") as? Bool ?? true
    }
}
