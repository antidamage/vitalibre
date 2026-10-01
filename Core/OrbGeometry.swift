import Foundation

/// Orb geometry in unit space, outer rim = 1.0 of the orb's radius.
///
/// The dashboard's rotary knob (see specs/ppg-vitals-app.md) has a colour
/// channel `referenceChannelWidth` wide. This orb's ring is `ringWidthRatio`
/// times that (it was 3x, then halved to 1.5x), so the thickness is a number, not a comment. Bands are derived from the outside in, so they cannot drift apart.
enum OrbGeometry {
    static let outerRadius = 1.0
    static let lipWidth = 0.034
    static let referenceChannelWidth = 0.152
    static let ringWidthRatio = 1.5
    static let unlockWidth = 0.053
    /// Margin outside the rim, as a fraction of the orb's radius, where the coving blends into the background.
    static let covingWidth = 0.16

    static var ringWidth: Double { referenceChannelWidth * ringWidthRatio }
    static var ringOuter: Double { outerRadius - lipWidth }
    static var ringInner: Double { ringOuter - ringWidth }
    static var ringMid: Double { (ringInner + ringOuter) / 2 }
    static var unlockInner: Double { ringInner - unlockWidth }
    static var domeOuter: Double { unlockInner }

    /// Grid: a spoke every 5 degrees, concentric circles at this pitch.
    static let spokeStepDegrees = 5.0
    static let circlePitch = 0.038
    static let gridBaseAlpha = 0.10
    static let gridMaxAlpha = 0.24

    /// Sweep: one revolution per reading (the scan's target length), from the clock, so frame rate cannot change it.
    static var sweepPeriodMs: Double { ScanSession.targetSeconds * 1000 }
    static let sweepHeadDegrees = 24.0
    static let sweepLeadDegrees = 3.0

    /// Angle in radians for a time in milliseconds, wrapping every period.
    static func sweepAngle(atMs ms: Double) -> Double {
        let phase = ms.truncatingRemainder(dividingBy: sweepPeriodMs)
        let wrapped = phase < 0 ? phase + sweepPeriodMs : phase
        return wrapped / sweepPeriodMs * 2 * .pi
    }

    /// Radius (unit space) for a normalised amplitude in -1...1, clamped to the ring.
    static func arcRadius(amplitude: Double) -> Double {
        let a = max(-1, min(1, amplitude))
        return ringMid + a * 0.5 * ringWidth * 0.9
    }

    /// Radii of the concentric grid circles inside the ring.
    static var gridCircleRadii: [Double] {
        var radii: [Double] = []
        var r = ringInner + circlePitch
        while r < ringOuter - 0.001 { radii.append(r); r += circlePitch }
        return radii
    }
}
