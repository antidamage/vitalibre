import Foundation

/// The user's own readings, one JSON file in Application Support. Written
/// atomically; never read by anything but this app, never transmitted.
///
/// The rules live in `ReadingLog`, which is pure and unit-tested; this is the
/// file and the observable wrapper around them.
final class ReadingStore: ObservableObject {
    @Published private(set) var log = ReadingLog()
    private let url: URL

    init(directory: URL? = nil) {
        let base = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("VitaLibre", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        url = base.appendingPathComponent("readings.json")
        if let data = try? Data(contentsOf: url) { log = ReadingLog.decoded(from: data) }
    }

    private func commit() {
        log.prune()
        if let data = log.encoded() { try? data.write(to: url, options: .atomic) }
    }

    /// What Readings lists: the readings the user kept.
    var savedReadings: [Reading] { log.savedReadings }

    /// Today's log, kept or not, newest first.
    var todaysReadings: [Reading] { log.todaysReadings }

    func reading(_ id: UUID?) -> Reading? { log.reading(id) }

    /// The reading filed for a scan, if one has been.
    func reading(forScan scanID: UUID?) -> Reading? { log.reading(forScan: scanID) }

    /// Files a finished scan in today's log, not kept, so nothing is lost by not
    /// saving it. Keyed by the scan: filing the same scan again refreshes that one
    /// reading rather than adding another.
    @discardableResult
    func file(_ result: ScanResult, scanID: UUID, bpShown: Bool? = nil, at date: Date = Date()) -> Reading {
        let r = log.file(result, scanID: scanID, bpShown: bpShown, at: date)
        commit()
        return r
    }

    func setSaved(_ id: UUID?, _ saved: Bool) {
        log.setSaved(id, saved)
        commit()
    }

    func toggleSaved(_ id: UUID) {
        log.toggleSaved(id)
        commit()
    }

    func toggleStar(_ id: UUID) {
        log.toggleStar(id)
        commit()
    }

    func delete(_ ids: [UUID]) {
        log.delete(ids)
        commit()
    }

    /// The user's exclusions for a reading, with the numbers recomputed without them (nil clears both).
    func setExclusions(_ id: UUID, _ ranges: [ExcludedRange], outcome: ReadingEdit.Outcome?) {
        log.setExclusions(id, ranges, outcome: outcome)
        commit()
    }

    func noteText(for reading: Reading) -> String? { log.noteText(for: reading) }

    func exportText(showBP: (Reading) -> Bool) -> String { log.exportText(showBP: showBP) }

    /// Every cuff comparison and every reading's raw model values, for working out the app's error against a cuff.
    func validationCSV(calibration: BPCalibration, appVersion: String, modelVersion: String, showBP: (Reading) -> Bool) -> String {
        ValidationExport.csv(calibration: calibration, readings: log.savedReadings, appVersion: appVersion,
                             modelVersion: modelVersion, includeRaw: BPPresentation.mode != "never", showBP: showBP)
    }
}
