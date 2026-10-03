import Foundation

/// What a kept reading can be marked with. One phrase covers both causes, in the owner's
/// words (Adeline, 2026-10-04: "mark the reading as 'low quality or arrhythmia'"): the
/// numbers cannot tell a weak signal from an unsteady rhythm, so the app says both and
/// diagnoses neither.
enum ReadingNote {
    static let lowQualityOrArrhythmia = "Low quality or arrhythmia"
}

/// One reading: what the technique measured, plus what the user has since done
/// with it. A value type with no file and no view, so the rules that decide what
/// is kept and what is dropped can be tested without a simulator.
struct Reading: Identifiable, Codable, Equatable {
    var id = UUID()
    var date: Date
    var heartRate: Double
    var bp: BPRange
    var quality: Double
    var level: QualityLevel
    var duration: Double
    var modelVersion: String
    var starred = false
    /// The scan's filtered waveform, normalised -1...1 at the analysis rate, rounded to
    /// three decimals — the graph the reading keeps. Optional so a reading filed before
    /// this existed decodes with none, and such a reading simply has no graph.
    var trace: [Double]? = nil
    /// How evenly the beats came. Optional for the same reason as `saved`, and `steady` is
    /// the honest reading of an older file: every reading in one passed the stability test
    /// that was in place then.
    var rhythm: Rhythm? = nil
    /// Kept, i.e. listed in Readings. A reading taken today and not kept lives in
    /// the day's log on Measure only. Optional so a file written before the log
    /// existed still decodes: nothing in such a file was ever dropped, so a
    /// missing `saved` means kept.
    var saved: Bool? = nil
    /// The scan this reading came from, which is what stops one scan being filed
    /// twice. Optional for the same reason: a file written before the log existed
    /// has none, and such a reading is only ever matched by its id.
    var scanID: UUID? = nil

    var isSaved: Bool { saved ?? true }

    /// Whether the rhythm swung during the scan.
    var isIrregular: Bool { rhythm == .irregular }

    /// The one note a reading can carry, or nothing. Marked, not refused: the reading is
    /// the user's either way.
    var note: String? { (level == .poor || isIrregular) ? ReadingNote.lowQualityOrArrhythmia : nil }
}

/// The user's readings, newest first, and the rules a finished scan runs into.
///
/// The file holds two kinds of reading: the ones the user kept, which Readings
/// lists, and the ones taken today but not kept, which only the session fold on
/// Measure shows. A finished scan is filed straight away, so a reading is never
/// lost by not saving it.
struct ReadingLog: Equatable {
    private(set) var readings: [Reading]

    init(_ readings: [Reading] = []) {
        self.readings = readings.sorted { $0.date > $1.date }
    }

    /// What Readings lists: the readings the user kept.
    var savedReadings: [Reading] { readings.filter(\.isSaved) }

    /// Today's log, kept or not, newest first.
    var todaysReadings: [Reading] { readings.filter { Calendar.current.isDateInToday($0.date) } }

    func reading(_ id: UUID?) -> Reading? {
        guard let id = id else { return nil }
        return readings.first { $0.id == id }
    }

    /// The reading filed for a scan, if one has been.
    func reading(forScan scanID: UUID?) -> Reading? {
        guard let scanID = scanID else { return nil }
        return readings.first { $0.scanID == scanID }
    }

    /// Files a finished scan in today's log, not kept, so nothing is lost by not
    /// saving it.
    ///
    /// Keyed by the scan, which is what makes it safe to call again: a return to
    /// Measure, a Save, or a recalibration lands on the one reading that scan
    /// already has and refreshes its estimate, instead of adding a second copy.
    @discardableResult
    mutating func file(_ result: ScanResult, scanID: UUID, at date: Date = Date()) -> Reading {
        if let i = readings.firstIndex(where: { $0.scanID == scanID }) {
            readings[i].heartRate = result.heartRate
            readings[i].bp = result.bp
            readings[i].quality = result.quality
            readings[i].level = result.level
            readings[i].rhythm = result.rhythm
            return readings[i]
        }
        let r = Reading(date: date, heartRate: result.heartRate, bp: result.bp, quality: result.quality,
                        level: result.level, duration: result.duration, modelVersion: result.modelVersion,
                        trace: Self.stored(result.trace), rhythm: result.rhythm,
                        saved: false, scanID: scanID)
        readings.insert(r, at: 0)
        return r
    }

    /// The graph as it is kept: three decimals, which is finer than a phone screen can
    /// show and about half the JSON of the raw doubles (a 15 s run is some 6 KB rather than
    /// 12). The rate is not reduced: the expanded view's zoom has to show what arrived.
    static func stored(_ trace: [Double]) -> [Double] {
        trace.map { ($0 * 1000).rounded() / 1000 }
    }

    mutating func setSaved(_ id: UUID?, _ saved: Bool) {
        guard let id = id, let i = readings.firstIndex(where: { $0.id == id }) else { return }
        readings[i].saved = saved
    }

    mutating func toggleSaved(_ id: UUID) {
        guard let r = reading(id) else { return }
        setSaved(id, !r.isSaved)
    }

    mutating func toggleStar(_ id: UUID) {
        guard let i = readings.firstIndex(where: { $0.id == id }) else { return }
        readings[i].starred.toggle()
    }

    mutating func delete(_ ids: [UUID]) {
        readings.removeAll { ids.contains($0.id) }
    }

    /// The day is over for anything from an earlier one that was not kept. Run
    /// before every write, so an unkept reading is held for the day it was taken
    /// and no longer.
    mutating func prune(now: Date = Date(), calendar: Calendar = .current) {
        readings.removeAll { !$0.isSaved && !calendar.isDate($0.date, inSameDayAs: now) }
    }

    // MARK: The file

    /// A readings file, or an empty log if it is absent or unreadable. A file
    /// written before the log existed has no `saved` and no `scanID`, and decodes
    /// with those absent, which reads as kept.
    static func decoded(from data: Data) -> ReadingLog {
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        return ReadingLog((try? decoder.decode([Reading].self, from: data)) ?? [])
    }

    func encoded() -> Data? {
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        return try? encoder.encode(readings)
    }

    /// The kept readings, oldest first, as plain text one reading per line.
    func exportText() -> String {
        let kept = savedReadings.sorted { $0.date < $1.date }
        let stamp = DateFormatter(); stamp.dateFormat = "yyyy-MM-dd HH:mm"
        var lines = ["VitaLibre readings (\(kept.count))",
                     "Heart rate in bpm; blood pressure in mmHg. Values are estimates. Not a medical device.", ""]
        for r in kept {
            // The note travels with the reading: a shared line that dropped it would be
            // quieter than the app, and the caveat is the part that matters.
            var line = "\(stamp.string(from: r.date))  HR \(Int(r.heartRate.rounded()))  BP \(r.bp.text)  quality \(r.level.rawValue)"
            if let note = r.note { line += "  \(note)" }
            if r.starred { line += "  starred" }
            lines.append(line)
        }
        return lines.joined(separator: "\n")
    }
}
