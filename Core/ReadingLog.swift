import Foundation

/// What a kept reading can be marked with. One phrase covers both causes, in the owner's
/// words (Adeline, 2026-10-04: "mark the reading as 'low quality or arrhythmia'"): the
/// numbers cannot tell a weak signal from an unsteady rhythm, so the app says both and
/// diagnoses neither.
enum ReadingNote {
    /// Readings filed before the feed-quality index existed, which cannot say which of the two it was.
    static let lowQualityOrArrhythmia = "Low quality or arrhythmia"
    /// The camera feed was poor. Says nothing about the heart.
    static let lowSignal = "Low signal quality"
    /// The feed was usable and the beats came unevenly. Never called arrhythmia: a fingertip camera cannot
    /// tell AF from ectopic beats or motion.
    static let irregular = "Irregular pulse"
    static let irregularRepeated = "Irregular pulse, consider checking with a clinician"
}

/// The numbers for a reading with stretches left out. Held beside the scan's own numbers so that clearing
/// the exclusions gives the original back exactly.
struct EditedResult: Codable, Equatable {
    var heartRate: Double
    var rhythm: Rhythm
    var bp: BPRange
    var rawSystolic: Double
    var rawDiastolic: Double
    var usedSeconds: Double
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
    /// The model's value before calibration, kept so a cuff reading can be paired with this scan and so a
    /// figure that was hidden when it was taken is still on file for validation.
    var rawSystolic: Double? = nil
    var rawDiastolic: Double? = nil
    /// Whether the blood-pressure figure was shown when the scan was taken (`BPDisplay`). nil for a reading
    /// filed before this existed, which counts as hidden on iOS.
    var bpShown: Bool? = nil
    /// Run seconds of the first trace sample, so `feedQuality` and `excluded` line up with the graph.
    var traceStart: Double? = nil
    /// Feed quality once a second from the second after cover (`FeedQuality.series`), two decimals, with the
    /// cause of each (`FeedCause` raw values).
    var feedQuality: [Double]? = nil
    var feedCauses: [String]? = nil
    /// Stretches the user has marked as not to be used, and the numbers without them.
    var excluded: [ExcludedRange]? = nil
    var edited: EditedResult? = nil

    var isSaved: Bool { saved ?? true }

    /// Whether the rhythm swung during the scan, counting any stretches the user left out.
    var isIrregular: Bool { displayRhythm == .irregular }

    var displayHeartRate: Double { edited?.heartRate ?? heartRate }
    var displayRhythm: Rhythm? { edited?.rhythm ?? rhythm }
    var displayBP: BPRange { edited?.bp ?? bp }

    /// Seconds of the run in use, when stretches are left out.
    var usedSeconds: Double { edited?.usedSeconds ?? duration }

    var feedMean: Double? {
        guard let f = feedQuality, !f.isEmpty else { return nil }
        return Stats.mean(f)
    }

    /// The one note a reading can carry, or nothing. Marked, not refused: the reading is
    /// the user's either way. With a feed series the two causes are told apart: a poor feed is
    /// "low signal quality" and says nothing about the heart; a usable feed with uneven beats is an
    /// "irregular pulse". A reading from before the series cannot tell, and keeps the old phrase.
    var note: String? {
        if let m = feedMean {
            if FeedQuality.level(m) == .poor { return ReadingNote.lowSignal }
            return isIrregular ? ReadingNote.irregular : nil
        }
        return (level == .poor || isIrregular) ? ReadingNote.lowQualityOrArrhythmia : nil
    }
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
    mutating func file(_ result: ScanResult, scanID: UUID, bpShown: Bool? = nil, at date: Date = Date()) -> Reading {
        if let i = readings.firstIndex(where: { $0.scanID == scanID }) {
            readings[i].heartRate = result.heartRate
            readings[i].bp = result.bp
            readings[i].quality = result.quality
            readings[i].level = result.level
            readings[i].rhythm = result.rhythm
            // A recalibration re-derives the figure and may now show it; it never hides what was shown.
            if let bpShown { readings[i].bpShown = (readings[i].bpShown ?? false) || bpShown }
            return readings[i]
        }
        let r = Reading(date: date, heartRate: result.heartRate, bp: result.bp, quality: result.quality,
                        level: result.level, duration: result.duration, modelVersion: result.modelVersion,
                        trace: Self.stored(result.trace), rhythm: result.rhythm,
                        saved: false, scanID: scanID,
                        rawSystolic: result.rawSystolic, rawDiastolic: result.rawDiastolic, bpShown: bpShown,
                        traceStart: result.traceStart,
                        feedQuality: result.feed.map { ($0.value * 100).rounded() / 100 },
                        feedCauses: result.feed.map { $0.cause.rawValue })
        readings.insert(r, at: 0)
        return r
    }

    /// Records the user's exclusions and the numbers recomputed without them. Empty ranges clear both, which
    /// gives the scan's own numbers back.
    mutating func setExclusions(_ id: UUID, _ ranges: [ExcludedRange], outcome: ReadingEdit.Outcome?) {
        guard let i = readings.firstIndex(where: { $0.id == id }) else { return }
        guard !ranges.isEmpty, let outcome else { readings[i].excluded = nil; readings[i].edited = nil; return }
        readings[i].excluded = ranges
        readings[i].edited = EditedResult(heartRate: outcome.heartRate, rhythm: outcome.rhythm, bp: outcome.bp,
                                          rawSystolic: outcome.rawSystolic, rawDiastolic: outcome.rawDiastolic,
                                          usedSeconds: outcome.usedSeconds)
    }

    /// Whether this reading is the second irregular pulse among the last three scans in 30 minutes. The
    /// wording escalates only then.
    func irregularRepeated(for reading: Reading) -> Bool {
        guard reading.isIrregular else { return false }
        let recent = readings.filter { $0.date <= reading.date && reading.date.timeIntervalSince($0.date) <= 1800 }
            .sorted { $0.date > $1.date }.prefix(3)
        return recent.filter { $0.isIrregular }.count >= 2
    }

    /// The note as shown, with the escalation applied.
    func noteText(for reading: Reading) -> String? {
        guard let note = reading.note else { return nil }
        return note == ReadingNote.irregular && irregularRepeated(for: reading) ? ReadingNote.irregularRepeated : note
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
    func exportText(showBP: (Reading) -> Bool = { _ in true }) -> String {
        let kept = savedReadings.sorted { $0.date < $1.date }
        let stamp = DateFormatter(); stamp.dateFormat = "yyyy-MM-dd HH:mm"
        var lines = ["VitaLibre readings (\(kept.count))",
                     "Heart rate in bpm; blood pressure in mmHg. Values are estimates. Not a medical device.", ""]
        for r in kept {
            // The note travels with the reading: a shared line that dropped it would be
            // quieter than the app, and the caveat is the part that matters.
            var line = "\(stamp.string(from: r.date))  HR \(Int(r.displayHeartRate.rounded()))"
            if showBP(r) { line += "  BP \(r.displayBP.text)" }
            line += "  quality \(r.level.rawValue)"
            if let note = noteText(for: r) { line += "  \(note)" }
            if let used = r.edited?.usedSeconds { line += "  \(Int(used.rounded())) of \(Int(r.duration.rounded())) s used" }
            if r.starred { line += "  starred" }
            lines.append(line)
        }
        return lines.joined(separator: "\n")
    }
}
