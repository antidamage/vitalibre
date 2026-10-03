import XCTest
@testable import VitaLibreCore

/// What a pull does to the fold before it breaks, and what a released one does.
/// The feel of the thing is a curve, so the curve is what these hold still: the
/// band's near-1:1 start, its exact break at 80, and the catch-up that lands the
/// content where a pull that never met the band would have put it.
final class FoldBandTests: XCTestCase {
    func testBandStartsNearlyOneToOne() {
        // Slope 0.7 at the start, and still over half a point of movement per point
        // of input a third of the way in: the pull has to feel alive before it fights.
        XCTAssertEqual(FoldBand.held(10), 6.5625, accuracy: 0.0001)
        XCTAssertEqual(FoldBand.held(40), 21, accuracy: 0.0001)
        XCTAssertEqual(FoldBand.held(20) - FoldBand.held(10), 5.6875, accuracy: 0.0001)
    }

    func testBandGivesNothingByItsTravel() {
        XCTAssertEqual(FoldBand.held(80), FoldBand.give, accuracy: 0.0001)
        XCTAssertEqual(FoldBand.held(120), FoldBand.give, accuracy: 0.0001)
        XCTAssertEqual(FoldBand.held(0), 0, accuracy: 0.0001)
    }

    func testBandNeverGoesBackwards() {
        var last = -1.0
        for pull in stride(from: 0.0, through: 100.0, by: 2.5) {
            let moved = FoldBand.held(pull)
            XCTAssertGreaterThanOrEqual(moved, last)
            last = moved
        }
    }

    func testDownwardInputMovesNothing() {
        XCTAssertEqual(FoldBand.held(-20), 0, accuracy: 0.0001)
    }

    func testBandBreaksExactlyAtItsTravel() {
        XCTAssertFalse(FoldBand.breaks(at: 79.999))
        XCTAssertTrue(FoldBand.breaks(at: 80))
        XCTAssertTrue(FoldBand.breaks(at: 200))
    }

    /// The break hands back every point the band absorbed: the content lands where
    /// a pull that had never met the band would have put it, which is what stops
    /// the snap from jumping.
    func testTheBreakLandsOnOneToOne() {
        XCTAssertEqual(FoldBand.catchUp, 52, accuracy: 0.0001)
        XCTAssertEqual(FoldBand.held(FoldBand.travel) + FoldBand.catchUp, FoldBand.travel, accuracy: 0.0001)
    }

    func testAFoldNeedsRoomForARow() {
        XCTAssertFalse(FoldBand.opens(overflow: 43))
        XCTAssertTrue(FoldBand.opens(overflow: 44))
        XCTAssertTrue(FoldBand.opens(overflow: 900))
        XCTAssertFalse(FoldBand.opens(overflow: 0))
    }

    func testAPullHoldsUntilItBreaksThenFollowsTheFinger() {
        var pull = FoldPull()
        XCTAssertEqual(pull.move(up: 20), .held(12.25))
        XCTAssertEqual(pull.move(up: 40), .held(21))
        XCTAssertFalse(pull.open)
        XCTAssertEqual(pull.move(up: 80), .broke(52))
        XCTAssertTrue(pull.open)
        // Broken: the rest of the gesture is the finger's, both ways.
        XCTAssertEqual(pull.move(up: 200), .free)
        XCTAssertEqual(pull.move(up: 10), .free)
    }

    func testAReleasedPullStaysOpenOnlyIfItBroke() {
        var held = FoldPull()
        XCTAssertEqual(held.move(up: 60), .held(FoldBand.held(60)))
        XCTAssertEqual(held.release(), .springBack)
        XCTAssertFalse(held.open)

        var broken = FoldPull()
        _ = broken.move(up: 80)
        XCTAssertEqual(broken.release(), .stay)
        XCTAssertTrue(broken.open)
    }

    /// Coming back to the fold's edge re-locks it: the next pull meets the band
    /// again rather than sliding straight through.
    func testARelockedFoldMeetsTheBandAgain() {
        var first = FoldPull()
        _ = first.move(up: 80)
        XCTAssertTrue(first.open)

        var again = FoldPull()
        XCTAssertEqual(again.move(up: 79), .held(FoldBand.held(79)))
        XCTAssertFalse(again.open)
    }

    /// A screen that reuses one pull for the life of the page re-locks it in place.
    func testAResetPullMeetsTheBandAgain() {
        var pull = FoldPull()
        _ = pull.move(up: 80)
        XCTAssertTrue(pull.open)

        pull.reset()
        XCTAssertFalse(pull.open)
        XCTAssertEqual(pull.move(up: 20), .held(12.25))
    }
}
