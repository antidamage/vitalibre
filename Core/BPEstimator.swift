import Foundation

struct BPFeatures: Equatable {
    var heartRate: Double
    var intervalCV: Double
    /// Foot-to-peak time as a fraction of the beat interval.
    var crestFraction: Double
    var skewness: Double
    /// Secondary (reflected) peak height over the main peak; 0 when none is found.
    var reflectionIndex: Double
}

struct BPRange: Equatable, Codable {
    var systolicLow: Int, systolicHigh: Int
    var diastolicLow: Int, diastolicHigh: Int
    var systolic: Int, diastolic: Int
    /// True once a cuff reading has been paired with the model. Optional so readings saved before it existed still decode.
    var calibrated: Bool? = nil

    /// A single figure per component once calibrated; the population range until then.
    var text: String {
        calibrated == true ? "\(systolic) / \(diastolic)"
            : "\(systolicLow)–\(systolicHigh) / \(diastolicLow)–\(diastolicHigh)"
    }
}

/// Versioned weights, loaded from `bp-model.json` so they can be replaced
/// without a code change. v1 ships a population prior plus small capped
/// feature adjustments and is NOT validated (see specs/ppg-vitals-app.md).
struct BPModel: Codable, Equatable {
    struct Term: Codable, Equatable {
        var feature: String
        var mean: Double, scale: Double
        var systolic: Double, diastolic: Double
    }
    var version: String
    var validated: Bool
    var baseSystolic: Double, baseDiastolic: Double
    var ageSystolicPerYear: Double, ageDiastolicPerYear: Double
    var referenceAge: Double
    var maleSystolicOffset: Double, maleDiastolicOffset: Double
    var terms: [Term]
    var maxSystolicAdjust: Double, maxDiastolicAdjust: Double
    var halfWidthSystolic: Double, halfWidthDiastolic: Double

    static func load(from data: Data) throws -> BPModel { try JSONDecoder().decode(BPModel.self, from: data) }
}

enum Sex: String, Codable, CaseIterable { case unspecified, female, male }

/// The person's own resting blood pressure, e.g. from a medical record. Replaces the age and sex prior.
struct UsualBP: Codable, Equatable {
    var systolic: Int
    var diastolic: Int
    var isPlausible: Bool { CalibrationPoint.isPlausible(systolic: Double(systolic), diastolic: Double(diastolic)) }
}

enum BPEstimator {
    static func value(_ name: String, _ f: BPFeatures) -> Double {
        switch name {
        case "heartRate": return f.heartRate
        case "intervalCV": return f.intervalCV
        case "crestFraction": return f.crestFraction
        case "skewness": return f.skewness
        case "reflectionIndex": return f.reflectionIndex
        default: return 0
        }
    }

    /// The model's own value before any calibration.
    static func raw(_ f: BPFeatures, model: BPModel, age: Int?, sex: Sex, usual: UsualBP? = nil) -> (systolic: Double, diastolic: Double) {
        var sys = model.baseSystolic, dia = model.baseDiastolic
        if let usual, usual.isPlausible {
            sys = Double(usual.systolic); dia = Double(usual.diastolic)
        } else if let age {
            let years = Double(max(18, min(90, age))) - model.referenceAge
            sys += years * model.ageSystolicPerYear
            dia += years * model.ageDiastolicPerYear
        }
        if usual?.isPlausible != true {
            if sex == .male { sys += model.maleSystolicOffset; dia += model.maleDiastolicOffset }
            if sex == .female { sys -= model.maleSystolicOffset; dia -= model.maleDiastolicOffset }
        }

        var dSys = 0.0, dDia = 0.0
        for t in model.terms {
            let z = (value(t.feature, f) - t.mean) / t.scale
            dSys += z * t.systolic; dDia += z * t.diastolic
        }
        sys += max(-model.maxSystolicAdjust, min(model.maxSystolicAdjust, dSys))
        dia += max(-model.maxDiastolicAdjust, min(model.maxDiastolicAdjust, dDia))
        return (sys, dia)
    }

    static func estimate(_ f: BPFeatures, model: BPModel, age: Int?, sex: Sex, usual: UsualBP? = nil,
                         calibration: BPCalibration = BPCalibration()) -> BPRange {
        let r = raw(f, model: model, age: age, sex: sex, usual: usual), off = calibration.offset
        let s = Int((r.systolic + off.systolic).rounded()), d = Int((r.diastolic + off.diastolic).rounded())
        let w = calibration.halfWidths(fallback: (model.halfWidthSystolic, model.halfWidthDiastolic))
        return BPRange(systolicLow: s - Int(w.systolic.rounded()), systolicHigh: s + Int(w.systolic.rounded()),
                       diastolicLow: d - Int(w.diastolic.rounded()), diastolicHigh: d + Int(w.diastolic.rounded()),
                       systolic: s, diastolic: d, calibrated: calibration.count > 0 || usual?.isPlausible == true)
    }

    /// Features from the filtered signal and its accepted beats.
    static func features(filtered y: [Double], beats: [Beat], hr: HeartRateEstimate, fs: Double) -> BPFeatures {
        let cv = Stats.std(hr.intervals) / max(1e-9, Stats.mean(hr.intervals))
        var crest: [Double] = [], reflect: [Double] = []
        for (i, b) in beats.enumerated() {
            let interval = i + 1 < beats.count ? beats[i + 1].time - b.time : (i > 0 ? b.time - beats[i - 1].time : 0)
            guard interval >= HeartRate.minInterval, interval <= HeartRate.maxInterval else { continue }
            // Foot: lowest sample in the 0.4 s before the peak.
            let lo = max(0, b.index - Int(0.4 * fs))
            var foot = lo
            for k in lo...b.index where y[k] < y[foot] { foot = k }
            crest.append(Double(b.index - foot) / fs / interval)
            // Reflected peak: highest local maximum 0.15-0.55 of the interval after the main peak.
            let from = b.index + Int(0.15 * interval * fs), to = min(y.count - 2, b.index + Int(0.55 * interval * fs))
            var second = 0.0
            if from < to, b.amplitude > 0 {
                for k in max(1, from)...to where y[k] > y[k - 1] && y[k] >= y[k + 1] { second = max(second, y[k]) }
            }
            reflect.append(max(0, second / b.amplitude))
        }
        return BPFeatures(heartRate: hr.bpm, intervalCV: cv, crestFraction: Stats.median(crest),
                          skewness: Stats.skewness(y), reflectionIndex: Stats.median(reflect))
    }
}

extension BPModel {
    /// Same numbers as App/Resources/bp-model.json (a test keeps them equal).
    /// Used only if the bundled file is missing, so a build can never lose its model.
    static let prior1 = BPModel(
        version: "prior-1", validated: false, baseSystolic: 118, baseDiastolic: 76,
        ageSystolicPerYear: 0.5, ageDiastolicPerYear: 0.15, referenceAge: 30,
        maleSystolicOffset: 2, maleDiastolicOffset: 1,
        terms: [
            .init(feature: "heartRate", mean: 70, scale: 12, systolic: 2.0, diastolic: 1.5),
            .init(feature: "crestFraction", mean: 0.30, scale: 0.06, systolic: -2.5, diastolic: -1.5),
            .init(feature: "skewness", mean: 0.60, scale: 0.40, systolic: -1.0, diastolic: -0.5),
            .init(feature: "reflectionIndex", mean: 0.15, scale: 0.10, systolic: 2.0, diastolic: 1.0),
        ],
        maxSystolicAdjust: 8, maxDiastolicAdjust: 5, halfWidthSystolic: 14, halfWidthDiastolic: 9)
}
