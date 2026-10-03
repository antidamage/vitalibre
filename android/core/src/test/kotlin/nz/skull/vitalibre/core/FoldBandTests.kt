package nz.skull.vitalibre.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The port of `Tests/VitaLibreCoreTests/FoldBandTests.swift`: the fold's feel is a curve, so the
 * curve is what these hold still — the band's near-1:1 start, its exact break at 80, and the
 * catch-up that lands the content where a pull that never met the band would have put it.
 */
class FoldBandTests {
    @Test
    fun bandStartsNearlyOneToOne() {
        assertEquals(6.5625, FoldBand.held(10.0), 0.0001)
        assertEquals(21.0, FoldBand.held(40.0), 0.0001)
        assertEquals(5.6875, FoldBand.held(20.0) - FoldBand.held(10.0), 0.0001)
    }

    @Test
    fun bandGivesNothingByItsTravel() {
        assertEquals(FoldBand.GIVE, FoldBand.held(80.0), 0.0001)
        assertEquals(FoldBand.GIVE, FoldBand.held(120.0), 0.0001)
        assertEquals(0.0, FoldBand.held(0.0), 0.0001)
    }

    @Test
    fun bandNeverGoesBackwards() {
        var last = -1.0
        var pull = 0.0
        while (pull <= 100.0) {
            val moved = FoldBand.held(pull)
            assertTrue(moved >= last, "the band went backwards at $pull")
            last = moved
            pull += 2.5
        }
    }

    @Test
    fun downwardInputMovesNothing() {
        assertEquals(0.0, FoldBand.held(-20.0), 0.0001)
    }

    @Test
    fun bandBreaksExactlyAtItsTravel() {
        assertFalse(FoldBand.breaks(79.999))
        assertTrue(FoldBand.breaks(80.0))
        assertTrue(FoldBand.breaks(200.0))
    }

    /** The break hands back every point the band absorbed, so the snap cannot jump. */
    @Test
    fun theBreakLandsOnOneToOne() {
        assertEquals(52.0, FoldBand.CATCH_UP, 0.0001)
        assertEquals(FoldBand.TRAVEL, FoldBand.held(FoldBand.TRAVEL) + FoldBand.CATCH_UP, 0.0001)
    }

    @Test
    fun aFoldNeedsRoomForARow() {
        assertFalse(FoldBand.opens(43.0))
        assertTrue(FoldBand.opens(44.0))
        assertTrue(FoldBand.opens(900.0))
        assertFalse(FoldBand.opens(0.0))
    }

    @Test
    fun aPullHoldsUntilItBreaksThenFollowsTheFinger() {
        val pull = FoldPull()
        assertEquals(FoldDrive.Held(12.25), pull.move(up = 20.0))
        assertEquals(FoldDrive.Held(21.0), pull.move(up = 40.0))
        assertFalse(pull.open)
        assertEquals(FoldDrive.Broke(52.0), pull.move(up = 80.0))
        assertTrue(pull.open)
        assertEquals(FoldDrive.Free, pull.move(up = 200.0))
        assertEquals(FoldDrive.Free, pull.move(up = 10.0))
    }

    @Test
    fun aReleasedPullStaysOpenOnlyIfItBroke() {
        val held = FoldPull()
        assertEquals(FoldDrive.Held(FoldBand.held(60.0)), held.move(up = 60.0))
        assertEquals(FoldDrive.SpringBack, held.release())
        assertFalse(held.open)

        val broken = FoldPull()
        broken.move(up = 80.0)
        assertEquals(FoldDrive.Stay, broken.release())
        assertTrue(broken.open)
    }

    /** Coming back to the fold's edge re-locks it: the next pull meets the band again. */
    @Test
    fun aRelockedFoldMeetsTheBandAgain() {
        val first = FoldPull()
        first.move(up = 80.0)
        assertTrue(first.open)

        val again = FoldPull()
        assertEquals(FoldDrive.Held(FoldBand.held(79.0)), again.move(up = 79.0))
        assertFalse(again.open)
    }

    /** A screen that reuses one pull for the life of the page re-locks it in place. */
    @Test
    fun aResetPullMeetsTheBandAgain() {
        val pull = FoldPull()
        pull.move(up = 80.0)
        assertTrue(pull.open)

        pull.reset()
        assertFalse(pull.open)
        assertEquals(FoldDrive.Held(12.25), pull.move(up = 20.0))
    }
}
