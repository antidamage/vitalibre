import XCTest
@testable import VitaLibreCore

/// What a finished scan does to the day's log. This is the part of the fold that
/// must never file one scan twice and never drop a reading the user kept.
final class ReadingLogTests: XCTestCase {
    private func scan(heartRate: Double = 72) -> ScanResult {
        ScanResult(
            heartRate: heartRate,
            bp: BPRange(systolicLow: 105, systolicHigh: 125, diastolicLow: 65, diastolicHigh: 80,
                        systolic: 115, diastolic: 72, calibrated: true),
            quality: 0.8, level: .good, duration: 15, intervals: [0.83, 0.84], modelVersion: "test",
            features: BPFeatures(heartRate: heartRate, intervalCV: 0.05, crestFraction: 0.2,
                                 skewness: 0.1, reflectionIndex: 0.3),
            rawSystolic: 118, rawDiastolic: 74, baseSystolic: 120, baseDiastolic: 78,
            trace: [0, 0.5, 0], traceEnd: 15)
    }

    /// The scan's identity used to live in the measure screen's own state, so coming
    /// back to the tab filed the same scan again. It lives with the scan now.
    func testFilingOneScanTwiceLeavesOneReading() {
        var log = ReadingLog()
        let scanID = UUID()
        let first = log.file(scan(), scanID: scanID)
        let second = log.file(scan(), scanID: scanID)
        XCTAssertEqual(log.readings.count, 1)
        XCTAssertEqual(first.id, second.id)
    }

    /// A recalibration lands on the reading that scan already has.
    func testFilingAgainRefreshesTheEstimateInPlace() {
        var log = ReadingLog()
        let scanID = UUID()
        let filed = log.file(scan(heartRate: 72), scanID: scanID)
        log.file(scan(heartRate: 81), scanID: scanID)
        XCTAssertEqual(log.readings.count, 1)
        XCTAssertEqual(log.readings[0].heartRate, 81)
        XCTAssertEqual(log.readings[0].id, filed.id)
    }

    /// Save keeps the reading the scan already filed; it does not add another.
    func testKeepingAFiledScanAndSeeingItAgainLeavesOne() {
        var log = ReadingLog()
        let scanID = UUID()
        let filed = log.file(scan(), scanID: scanID)
        log.setSaved(filed.id, true)
        log.file(scan(), scanID: scanID)
        XCTAssertEqual(log.readings.count, 1)
        XCTAssertEqual(log.savedReadings.count, 1)
        XCTAssertTrue(log.readings[0].isSaved)
    }

    func testTwoScansAreTwoReadingsNewestFirst() {
        var log = ReadingLog()
        let older = log.file(scan(), scanID: UUID(), at: Date(timeIntervalSince1970: 1_000))
        let newer = log.file(scan(), scanID: UUID(), at: Date(timeIntervalSince1970: 2_000))
        XCTAssertEqual(log.readings.map(\.id), [newer.id, older.id])
    }

    /// The day is over for an unkept reading from an earlier one, and only for that.
    func testPruneDropsOnlyAnEarlierDaysUnkept() {
        let now = Date()
        let yesterday = now.addingTimeInterval(-26 * 3600)
        var log = ReadingLog()
        let keptYesterday = log.file(scan(), scanID: UUID(), at: yesterday)
        log.setSaved(keptYesterday.id, true)
        let looseYesterday = log.file(scan(), scanID: UUID(), at: yesterday)
        let today = log.file(scan(), scanID: UUID(), at: now)

        log.prune(now: now)

        XCTAssertEqual(Set(log.readings.map(\.id)), Set([keptYesterday.id, today.id]))
        XCTAssertFalse(log.readings.contains { $0.id == looseYesterday.id })
        XCTAssertEqual(log.todaysReadings.count, 1)
    }

    /// A file written before the day's log existed has neither `saved` nor `scanID`.
    func testAFileWrittenBeforeTheLogExistedReadsAsKept() {
        let json = """
        [{"id":"6B0E3F1E-0F1A-4E3B-9E6A-2A1B7C4D5E6F","date":"2026-10-01T20:15:00Z","heartRate":71,
          "bp":{"systolicLow":104,"systolicHigh":124,"diastolicLow":64,"diastolicHigh":79,
                "systolic":114,"diastolic":71,"calibrated":true},
          "quality":0.7,"level":"fair","duration":15,"modelVersion":"v1","starred":true}]
        """
        let log = ReadingLog.decoded(from: Data(json.utf8))
        XCTAssertEqual(log.readings.count, 1)
        XCTAssertTrue(log.readings[0].isSaved)
        XCTAssertNil(log.readings[0].scanID)
        XCTAssertEqual(log.savedReadings.count, 1)
    }

    /// The scan key has to survive the file, or relaunching and Save would file the
    /// same scan a second time.
    func testTheScanKeySurvivesTheFile() {
        var log = ReadingLog()
        let scanID = UUID()
        let filed = log.file(scan(), scanID: scanID)
        guard let data = log.encoded() else { return XCTFail("the log did not encode") }
        let reloaded = ReadingLog.decoded(from: data)
        XCTAssertEqual(reloaded.readings.count, 1)
        XCTAssertEqual(reloaded.reading(forScan: scanID)?.id, filed.id)
    }
}
