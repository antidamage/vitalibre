import XCTest
@testable import VitaLibreCore

final class BPDisplayTests: XCTestCase {
    private let day = 86400.0
    private func point(daysAgo: Double, now: Date, cuffSystolic: Double = 120, cuffDiastolic: Double = 78) -> CalibrationPoint {
        CalibrationPoint(rawSystolic: 118, rawDiastolic: 76, cuffSystolic: cuffSystolic, cuffDiastolic: cuffDiastolic,
                         date: now.addingTimeInterval(-daysAgo * day), cuffName: "test cuff")
    }

    // MARK: Calibration validity

    func testNoCalibrationIsNotCurrent() {
        XCTAssertFalse(BPCalibration().isCurrent())
    }

    func testACalibrationIsCurrentForThirtyDays() {
        let now = Date()
        var c = BPCalibration(); c.add(point(daysAgo: 30, now: now))
        XCTAssertTrue(c.isCurrent(now: now))
        var old = BPCalibration(); old.add(point(daysAgo: 31, now: now))
        XCTAssertFalse(old.isCurrent(now: now))
    }

    func testTheNewestPointDecides() {
        let now = Date()
        var c = BPCalibration(); c.add(point(daysAgo: 60, now: now)); c.add(point(daysAgo: 2, now: now))
        XCTAssertTrue(c.isCurrent(now: now))
        XCTAssertEqual(c.expiry(validDays: 30)!.timeIntervalSince(now), 28 * day, accuracy: 1)
    }

    func testAnImplausiblePointDoesNotCalibrate() {
        var c = BPCalibration(); c.add(point(daysAgo: 0, now: Date(), cuffSystolic: 130, cuffDiastolic: 125))
        XCTAssertEqual(c.count, 0)
        XCTAssertFalse(c.isCurrent())
    }

    func testTheValidityCanBeChanged() {
        let now = Date()
        var c = BPCalibration(); c.add(point(daysAgo: 10, now: now))
        XCTAssertFalse(c.isCurrent(now: now, validDays: 7))
    }

    func testACuffNameSurvivesTheFile() throws {
        var c = BPCalibration(); c.add(point(daysAgo: 0, now: Date()))
        let back = try JSONDecoder().decode(BPCalibration.self, from: JSONEncoder().encode(c))
        XCTAssertEqual(back.points.first?.cuffName, "test cuff")
    }

    // MARK: When the figure is shown

    func testIOSHidesBPUntilCalibrated() {
        XCTAssertEqual(BPDisplay.hidden(rhythm: .steady, calibration: BPCalibration(), platform: .ios, mode: "calibrated"),
                       .needsCalibration)
        var c = BPCalibration(); c.add(point(daysAgo: 1, now: Date()))
        XCTAssertNil(BPDisplay.hidden(rhythm: .steady, calibration: c, platform: .ios, mode: "calibrated"))
    }

    func testTheSwitchHidesItEverywhereOnIOS() {
        var c = BPCalibration(); c.add(point(daysAgo: 1, now: Date()))
        XCTAssertEqual(BPDisplay.hidden(rhythm: .steady, calibration: c, platform: .ios, mode: "never"), .disabled)
    }

    func testAndroidAlwaysShowsItWhenSteady() {
        XCTAssertNil(BPDisplay.hidden(rhythm: .steady, calibration: BPCalibration(), platform: .android, mode: "calibrated"))
    }

    func testAnIrregularPulseShowsNoBPOnEitherPlatform() {
        var c = BPCalibration(); c.add(point(daysAgo: 1, now: Date()))
        XCTAssertEqual(BPDisplay.hidden(rhythm: .irregular, calibration: c, platform: .ios, mode: "calibrated"), .irregular)
        XCTAssertEqual(BPDisplay.hidden(rhythm: .irregular, calibration: c, platform: .android, mode: "calibrated"), .irregular)
    }

    // MARK: The margin

    func testTheMarginIsThePopulationErrorUntilThreePoints() {
        let model = testModel(), base = (systolic: 120.0, diastolic: 78.0)
        var c = BPCalibration()
        XCTAssertEqual(BPMargin.text(model: model, calibration: c, base: base, legacy: base),
                       "Average error about ±14 / ±9 mmHg; single readings can differ more.")
        c.add(point(daysAgo: 0, now: Date()))
        XCTAssertTrue(BPMargin.text(model: model, calibration: c, base: base, legacy: base).hasPrefix("Average error"))
    }

    func testTheMarginComesFromTheCuffWithThreePoints() {
        let model = testModel(), base = (systolic: 120.0, diastolic: 78.0)
        var c = BPCalibration()
        for (s, d) in [(118.0, 76.0), (121.0, 79.0), (125.0, 81.0)] {
            c.add(CalibrationPoint(rawSystolic: 120, rawDiastolic: 78, cuffSystolic: s, cuffDiastolic: d, date: Date()))
        }
        let text = BPMargin.text(model: model, calibration: c, base: base, legacy: base)
        XCTAssertTrue(text.contains("3 cuff comparisons"), text)
        XCTAssertTrue(text.contains("±6 / ±4"), text)   // the floors: the spread is smaller than that
    }

    /// Past the population error the width shown is the population error; "90% of your readings" would be false.
    func testAWideSpreadIsNotCalledNinetyPercent() {
        let model = testModel(), base = (systolic: 120.0, diastolic: 78.0)
        var c = BPCalibration()
        for (s, d) in [(100.0, 70.0), (125.0, 85.0), (145.0, 92.0), (110.0, 66.0)] {
            c.add(CalibrationPoint(rawSystolic: 120, rawDiastolic: 78, cuffSystolic: s, cuffDiastolic: d, date: Date()))
        }
        let text = BPMargin.text(model: model, calibration: c, base: base, legacy: base)
        XCTAssertTrue(text.hasPrefix("Average error about ±14 / ±9"), text)
        XCTAssertFalse(text.contains("90%"), text)
    }
}

final class FeedNoteTests: XCTestCase {
    private func reading(feed: [Double]?, rhythm: Rhythm, level: QualityLevel = .good, date: Date = Date()) -> Reading {
        Reading(date: date, heartRate: 72,
                bp: BPRange(systolicLow: 105, systolicHigh: 125, diastolicLow: 65, diastolicHigh: 80,
                            systolic: 115, diastolic: 72, calibrated: true),
                quality: 0.5, level: level, duration: 15, modelVersion: "test", rhythm: rhythm,
                feedQuality: feed, feedCauses: feed.map { $0.map { _ in "none" } })
    }

    func testAPoorFeedIsLowSignalQualityWhateverTheRhythm() {
        XCTAssertEqual(reading(feed: [0.2, 0.3, 0.2], rhythm: .steady).note, ReadingNote.lowSignal)
        XCTAssertEqual(reading(feed: [0.2, 0.3, 0.2], rhythm: .irregular).note, ReadingNote.lowSignal)
    }

    func testAGoodFeedWithUnevenBeatsIsAnIrregularPulse() {
        XCTAssertEqual(reading(feed: [0.9, 0.9, 0.8], rhythm: .irregular).note, ReadingNote.irregular)
        XCTAssertNil(reading(feed: [0.9, 0.9, 0.8], rhythm: .steady).note)
    }

    func testAReadingWithNoFeedKeepsTheOldPhrase() {
        XCTAssertEqual(reading(feed: nil, rhythm: .irregular).note, ReadingNote.lowQualityOrArrhythmia)
        XCTAssertEqual(reading(feed: nil, rhythm: .steady, level: .poor).note, ReadingNote.lowQualityOrArrhythmia)
        XCTAssertNil(reading(feed: nil, rhythm: .steady).note)
    }

    func testTheWordArrhythmiaIsNotUsedWhenTheFeedCanSeparateTheCauses() {
        for r in [reading(feed: [0.9], rhythm: .irregular), reading(feed: [0.2], rhythm: .irregular)] {
            XCTAssertFalse((r.note ?? "").lowercased().contains("arrhythmia"))
        }
    }

    func testTheWordingEscalatesOnlyOnTheSecondIrregularScanWithinThirtyMinutes() {
        let now = Date()
        let first = reading(feed: [0.9], rhythm: .irregular, date: now.addingTimeInterval(-600))
        let steady = reading(feed: [0.9], rhythm: .steady, date: now.addingTimeInterval(-300))
        let second = reading(feed: [0.9], rhythm: .irregular, date: now)
        let log = ReadingLog([first, steady, second])
        XCTAssertEqual(log.noteText(for: first), ReadingNote.irregular)
        XCTAssertEqual(log.noteText(for: second), ReadingNote.irregularRepeated)
        let late = reading(feed: [0.9], rhythm: .irregular, date: now.addingTimeInterval(7200))
        XCTAssertEqual(ReadingLog([first, steady, second, late]).noteText(for: late), ReadingNote.irregular)
    }

    func testClearingTheExclusionsGivesTheScansOwnNumbersBack() {
        var log = ReadingLog()
        let r = reading(feed: [0.9], rhythm: .irregular)
        log = ReadingLog([r])
        let bp = BPRange(systolicLow: 90, systolicHigh: 110, diastolicLow: 60, diastolicHigh: 75, systolic: 100, diastolic: 68, calibrated: true)
        let out = ReadingEdit.Outcome(heartRate: 64, rhythm: .steady, bp: bp, rawSystolic: 99, rawDiastolic: 67, usedSeconds: 10)
        log.setExclusions(r.id, [ExcludedRange(start: 3, end: 8)], outcome: out)
        let edited = log.reading(r.id)!
        XCTAssertEqual(edited.displayHeartRate, 64)
        XCTAssertEqual(edited.displayRhythm, .steady)
        XCTAssertNil(edited.note)
        XCTAssertEqual(edited.usedSeconds, 10)
        log.setExclusions(r.id, [], outcome: nil)
        let back = log.reading(r.id)!
        XCTAssertEqual(back.displayHeartRate, 72)
        XCTAssertEqual(back.displayRhythm, .irregular)
        XCTAssertNil(back.excluded)
        XCTAssertEqual(back.usedSeconds, 15)
    }

    func testAnOlderFileStillDecodes() throws {
        let json = #"[{"id":"7B3F5C0E-4D62-4B83-8D6C-0D5E5C1F0A11","date":"2026-10-01T10:00:00Z","heartRate":70,"bp":{"systolicLow":100,"systolicHigh":120,"diastolicLow":65,"diastolicHigh":80,"systolic":110,"diastolic":72},"quality":0.7,"level":"good","duration":15,"modelVersion":"v1","starred":false}]"#
        let log = ReadingLog.decoded(from: Data(json.utf8))
        XCTAssertEqual(log.readings.count, 1)
        XCTAssertNil(log.readings[0].feedQuality)
        XCTAssertNil(log.readings[0].bpShown)
        XCTAssertNil(log.readings[0].edited)
    }
}

final class ValidationExportTests: XCTestCase {
    func testTheExportCarriesPairsReadingsAndHidesWhatIsNotShown() {
        var cal = BPCalibration()
        cal.add(CalibrationPoint(rawSystolic: 118, rawDiastolic: 76, cuffSystolic: 124, cuffDiastolic: 80, date: Date(timeIntervalSince1970: 1_700_000_000),
                                 device: "iPhone18,2", baseSystolic: 120, baseDiastolic: 78, cuffName: "Cuff, \"arm\""))
        var r = Reading(date: Date(timeIntervalSince1970: 1_700_000_100), heartRate: 70,
                        bp: BPRange(systolicLow: 105, systolicHigh: 125, diastolicLow: 65, diastolicHigh: 80, systolic: 115, diastolic: 72, calibrated: true),
                        quality: 0.7, level: .good, duration: 15, modelVersion: "v1", rhythm: .steady,
                        rawSystolic: 118, rawDiastolic: 74, feedQuality: [0.9, 0.8])
        r.excluded = [ExcludedRange(start: 3, end: 5.5)]
        let hidden = ValidationExport.csv(calibration: cal, readings: [r], appVersion: "0.3 (1)", modelVersion: "v1", showBP: { _ in false })
        XCTAssertTrue(hidden.contains(ValidationExport.calibrationHeader))
        XCTAssertTrue(hidden.contains("\"Cuff, \"\"arm\"\"\""), hidden)       // quoted and escaped
        XCTAssertTrue(hidden.contains("calibration,2023-11-14T22:13:20Z,iPhone18,2,0.3 (1),v1,124,80,118.0,76.0,120.0,78.0"))
        let line = hidden.split(separator: "\n").first { $0.hasPrefix("reading,") }!
        XCTAssertFalse(line.contains("118.0"), String(line))                   // a hidden figure is hidden everywhere
        XCTAssertTrue(line.contains("reading,2023-11-14T22:15:00Z,v1,70.0,steady,,,,,"), String(line))
        XCTAssertTrue(line.contains("3.0-5.5"), String(line))
        let shown = ValidationExport.csv(calibration: cal, readings: [r], appVersion: "0.3 (1)", modelVersion: "v1")
        XCTAssertTrue(shown.contains(",118.0,74.0,115,72,"), shown)
        // With the publisher's switch at "never" no model blood-pressure value leaves the app, raw or shown; the cuff's
        // own numbers do.
        let never = ValidationExport.csv(calibration: cal, readings: [r], appVersion: "0.3 (1)", modelVersion: "v1", includeRaw: false)
        XCTAssertTrue(never.contains("calibration,2023-11-14T22:13:20Z,iPhone18,2,0.3 (1),v1,124,80,,,,,"), never)
        let line2 = never.split(separator: "\n").first { $0.hasPrefix("reading,") }!
        XCTAssertFalse(line2.contains("118.0"), String(line2))
        XCTAssertFalse(line2.contains("115"), String(line2))
    }
}
