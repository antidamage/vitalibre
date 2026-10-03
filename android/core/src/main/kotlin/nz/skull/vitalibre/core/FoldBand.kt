package nz.skull.vitalibre.core

/**
 * The fold's band, pure: what a pull does before it breaks and what happens to a released one.
 *
 * It is the dashboard's advanced fold (nova-ha-dashboard/specs/advanced-fold.md) reduced to its
 * numbers and its two rules, and this is the Kotlin port of `Core/FoldBand.swift`, with the same
 * tests holding on both sides. The iOS measure screen asks that file today; the Android measure
 * screen still derives its own numbers in the reveal fold until its page-wide slide lands.
 *
 * While the band holds, `pull` points of upward input move the content
 * `d(p) = GIVE * (1 - (1 - p / TRAVEL)^2)` — nearly 1:1 at first (slope 0.7 at the start), moving
 * nothing at all by `TRAVEL`. At `TRAVEL` the band breaks: the content catches up `CATCH_UP` points
 * to where a 1:1 pull would have put it, and follows the finger from there. Pulling back to nothing
 * re-locks it, with no resistance on the way back.
 */
object FoldBand {
    /** The upward input the band absorbs before it breaks. */
    const val TRAVEL = 80.0

    /** The most the content moves while the band holds. */
    const val GIVE = 28.0

    /** What the content gains at the break: the input the band has been denying it. */
    const val CATCH_UP = TRAVEL - GIVE

    /**
     * A fold opens into at least this much, or it does not open: less than a row of what it guards
     * would be worse than no fold.
     */
    const val MIN_REVEAL = 44.0

    /** How far the content moves for `pull` points of input while the band holds. */
    fun held(pull: Double): Double {
        val f = pull.coerceIn(0.0, TRAVEL) / TRAVEL
        return GIVE * (1 - (1 - f) * (1 - f))
    }

    /** The band breaks on the input that reaches its travel. */
    fun breaks(pull: Double): Boolean = pull >= TRAVEL

    /** A fold needs something past its line, and room for at least a row of it. */
    fun opens(overflow: Double, minReveal: Double = MIN_REVEAL): Boolean = overflow >= minReveal
}

/** What the fold wants the content to do next. */
sealed interface FoldDrive {
    /** The band still holds: the content sits this far past the fold's edge. */
    data class Held(val offset: Double) : FoldDrive

    /** The band just broke: move the content forward by this, then follow the finger. */
    data class Broke(val catchUp: Double) : FoldDrive

    /** Already broken: the content follows the finger, and scrolls freely. */
    data object Free : FoldDrive

    /** Released while the band held: the content goes back to rest. */
    data object SpringBack : FoldDrive

    /** Released open: the content stays where it is. */
    data object Stay : FoldDrive
}

/**
 * A pull in progress. One of these lives for the length of a gesture: feed it the upward input
 * since the gesture began and it says where the content goes. A fold that has come back to its edge
 * re-locks by taking a fresh `FoldPull`.
 */
class FoldPull(open: Boolean = false) {
    /** Whether the band has broken: the fold is open and the content scrolls freely. */
    var open: Boolean = open
        private set

    /** Feeds the gesture's upward input (positive is a pull up) and answers. */
    fun move(up: Double): FoldDrive {
        if (open) return FoldDrive.Free
        val input = maxOf(0.0, up)
        if (FoldBand.breaks(input)) {
            open = true
            return FoldDrive.Broke(FoldBand.CATCH_UP)
        }
        return FoldDrive.Held(FoldBand.held(input))
    }

    /** The gesture ended with the content where the fold left it. */
    fun release(): FoldDrive = if (open) FoldDrive.Stay else FoldDrive.SpringBack

    /** A fold that has come back to its edge re-locks: this is what the next pull starts from. */
    fun reset() { open = false }
}
