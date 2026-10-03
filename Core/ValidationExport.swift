import Foundation

/// The paired validation export: every cuff comparison and every reading's raw model values, as CSV, so
/// the app's error against a named cuff can be worked out outside the app (Bland-Altman bias and SD).
/// Shared through the ordinary Share action; nothing is transmitted anywhere.
enum ValidationExport {
    static let calibrationHeader = "type,date,device,app_version,model_version,cuff_systolic,cuff_diastolic,raw_systolic,raw_diastolic,base_systolic,base_diastolic,cuff_name"
    static let readingHeader = "type,date,model_version,heart_rate,rhythm,raw_systolic,raw_diastolic,shown_systolic,shown_diastolic,duration_s,used_s,feed_mean,note,excluded_ranges,feed_series,feed_causes"

    /// A reading whose figure is hidden leaves its model blood-pressure values out too (raw and shown): a hidden figure
    /// is hidden everywhere. Cuff pairings keep their raw and base values, which is what a validation needs, unless
    /// `includeRaw` is false (the publisher's `iosBpDisplay` is "never"): then no model value leaves the app at all.
    static func csv(calibration: BPCalibration, readings: [Reading], appVersion: String, modelVersion: String,
                    includeRaw: Bool = true, showBP: (Reading) -> Bool = { _ in true }) -> String {
        let iso = ISO8601DateFormatter()
        func field(_ s: String) -> String {
            s.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" }) ? "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\"" : s
        }
        func num(_ v: Double?, _ places: Int = 1) -> String { v.map { String(format: "%.\(places)f", $0) } ?? "" }
        var lines = ["# VitaLibre validation export. Estimates only. Not a medical device.", calibrationHeader]
        for p in calibration.points.sorted(by: { $0.date < $1.date }) {
            lines.append(["calibration", iso.string(from: p.date), p.device ?? "", appVersion, modelVersion,
                          num(p.cuffSystolic, 0), num(p.cuffDiastolic, 0), includeRaw ? num(p.rawSystolic) : "", includeRaw ? num(p.rawDiastolic) : "",
                          includeRaw ? num(p.baseSystolic) : "", includeRaw ? num(p.baseDiastolic) : "", field(p.cuffName ?? "")].joined(separator: ","))
        }
        lines += ["", readingHeader]
        for r in readings.sorted(by: { $0.date < $1.date }) {
            let shown = showBP(r)
            let ranges = (r.excluded ?? []).map { String(format: "%.1f-%.1f", $0.start, $0.end) }.joined(separator: ";")
            lines.append(["reading", iso.string(from: r.date), r.modelVersion, num(r.displayHeartRate),
                          r.displayRhythm?.rawValue ?? "", includeRaw && shown ? num(r.edited?.rawSystolic ?? r.rawSystolic) : "", includeRaw && shown ? num(r.edited?.rawDiastolic ?? r.rawDiastolic) : "",
                          shown && includeRaw ? "\(r.displayBP.systolic)" : "", shown && includeRaw ? "\(r.displayBP.diastolic)" : "",
                          num(r.duration), num(r.usedSeconds), num(r.feedMean, 2), field(r.note ?? ""), field(ranges),
                          (r.feedQuality ?? []).map { String(format: "%.2f", $0) }.joined(separator: ";"),
                          (r.feedCauses ?? []).joined(separator: ";")]
                .joined(separator: ","))
        }
        return lines.joined(separator: "\n")
    }
}
