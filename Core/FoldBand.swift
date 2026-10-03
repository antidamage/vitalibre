import Foundation

/// The fold's band, pure: what a pull does before it breaks and what happens to a
/// released one. It is the dashboard's advanced fold
/// (`nova-ha-dashboard/specs/advanced-fold.md`) reduced to its numbers and its two
/// rules, so one source carries the band on both platforms. The iOS measure screen
/// asks this file today; the Android measure screen still derives its own numbers
/// in the reveal fold until its page-wide slide lands. Nothing in Core imports
/// UIKit or SwiftUI; this file has no view in it.
///
/// While the band holds, `pull` points of upward input move the content
/// `d(p) = give × (1 − (1 − p / travel)²)` — nearly 1:1 at first (slope 0.7 at the
/// start), moving nothing at all by `travel`. At `travel` the band breaks: the
/// content catches up `catchUp` points to where a 1:1 pull would have put it, and
/// follows the finger from there. Pulling back to nothing re-locks it, with no
/// resistance on the way back.
public enum FoldBand {
    /// The upward input the band absorbs before it breaks.
    public static let travel: Double = 80
    /// The most the content moves while the band holds.
    public static let give: Double = 28
    /// What the content gains at the break: the input the band has been denying it.
    public static let catchUp: Double = travel - give
    /// A fold opens into at least this much, or it does not open: less than a row
    /// of what it guards would be worse than no fold.
    public static let minReveal: Double = 44

    /// How far the content moves for `pull` points of input while the band holds.
    public static func held(_ pull: Double) -> Double {
        let f = min(1, max(0, pull) / travel)
        return give * (1 - (1 - f) * (1 - f))
    }

    /// The band breaks on the input that reaches its travel.
    public static func breaks(at pull: Double) -> Bool { pull >= travel }

    /// A fold needs something past its line, and room for at least a row of it.
    public static func opens(overflow: Double, minReveal: Double = FoldBand.minReveal) -> Bool {
        overflow >= minReveal
    }
}

/// What the fold wants the content to do next.
public enum FoldDrive: Equatable {
    /// The band still holds: the content sits this far past the fold's edge.
    case held(Double)
    /// The band just broke: move the content forward by this, then follow the finger.
    case broke(Double)
    /// Already broken: the content follows the finger, and scrolls freely.
    case free
    /// Released while the band held: the content goes back to rest.
    case springBack
    /// Released open: the content stays where it is.
    case stay
}

/// A pull in progress. One of these lives for the length of a gesture: feed it the
/// upward input since the gesture began and it says where the content goes. A fold
/// that has come back to its edge re-locks by taking a fresh `FoldPull`.
public struct FoldPull {
    /// Whether the band has broken: the fold is open and the content scrolls freely.
    public private(set) var open: Bool

    public init(open: Bool = false) { self.open = open }

    /// Feeds the gesture's upward input (positive is a pull up) and answers.
    public mutating func move(up: Double) -> FoldDrive {
        if open { return .free }
        let input = max(0, up)
        if FoldBand.breaks(at: input) {
            open = true
            return .broke(FoldBand.catchUp)
        }
        return .held(FoldBand.held(input))
    }

    /// The gesture ended with the content where the fold left it.
    public mutating func release() -> FoldDrive { open ? .stay : .springBack }

    /// A fold that has come back to its edge re-locks: this is what the next pull starts from.
    public mutating func reset() { open = false }
}
