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
    /// The pickers start on 120/80, near the average, but the value only counts once the person has
    /// confirmed it with Done, so a default is never mistaken for a measurement.
    @Published var usualConfirmed: Bool { didSet { defaults.set(usualConfirmed, forKey: "usualConfirmed") } }
    static let defaultSystolic = 120
    static let defaultDiastolic = 80
    var usual: UsualBP? {
        let u = UsualBP(systolic: usualSystolic, diastolic: usualDiastolic)
        return usualConfirmed && u.isPlausible ? u : nil
    }
    /// Cuff readings paired with scans; shifts and narrows the blood pressure estimate.
    @Published var calibration: BPCalibration {
        didSet { defaults.set(try? JSONEncoder().encode(calibration), forKey: "bpCalibration") }
    }
    @Published var simulatedPulse: Bool { didSet { defaults.set(simulatedPulse, forKey: "simulatedPulse") } }
    /// The flash state the last reading settled on; a scan starts from it. nil = not known yet.
    @Published var workingFlash: Bool? {
        didSet {
            if let workingFlash { defaults.set(workingFlash, forKey: "workingFlash") }
            else { defaults.removeObject(forKey: "workingFlash") }
        }
    }

    init() {
        themeMode = ThemeMode(rawValue: defaults.string(forKey: "themeMode") ?? "") ?? .dark
        age = defaults.integer(forKey: "age")
        sex = Sex(rawValue: defaults.string(forKey: "sex") ?? "") ?? .unspecified
        let done = defaults.object(forKey: "onboardedAt") as? Double
        onboardedAt = done.map { Date(timeIntervalSince1970: $0) }
        let storedSystolic = defaults.integer(forKey: "usualSystolic")
        let storedDiastolic = defaults.integer(forKey: "usualDiastolic")
        usualSystolic = storedSystolic > 0 ? storedSystolic : Self.defaultSystolic
        usualDiastolic = storedDiastolic > 0 ? storedDiastolic : Self.defaultDiastolic
        // A value saved before confirmation existed was chosen by the person.
        usualConfirmed = defaults.object(forKey: "usualConfirmed") as? Bool ?? (storedSystolic > 0 && storedDiastolic > 0)
        calibration = (defaults.data(forKey: "bpCalibration").flatMap { try? JSONDecoder().decode(BPCalibration.self, from: $0) }) ?? BPCalibration()
        simulatedPulse = defaults.object(forKey: "simulatedPulse") as? Bool ?? true
        workingFlash = defaults.object(forKey: "workingFlash") as? Bool
    }
}
