package nz.skull.vitalibre.core

import kotlin.math.max
import kotlin.math.min

/**
 * Orb geometry in unit space, outer rim = 1.0 of the orb's radius. Bands are derived from the
 * outside in, so they cannot drift apart. Same numbers as the iOS build.
 */
object OrbGeometry {
    const val outerRadius = 1.0
    const val lipWidth = 0.034
    const val referenceChannelWidth = 0.152
    const val ringWidthRatio = 1.5
    const val unlockWidth = 0.053

    /** Margin outside the rim, as a fraction of the orb's radius, where the coving blends into the background. */
    const val covingWidth = 0.16

    val ringWidth get() = referenceChannelWidth * ringWidthRatio
    val ringOuter get() = outerRadius - lipWidth
    val ringInner get() = ringOuter - ringWidth
    val ringMid get() = (ringInner + ringOuter) / 2
    val unlockInner get() = ringInner - unlockWidth
    val domeOuter get() = unlockInner

    const val spokeStepDegrees = 5.0
    const val circlePitch = 0.038
    const val gridBaseAlpha = 0.10
    const val gridMaxAlpha = 0.24

    /** One revolution per reading (the scan's target length), from the clock, so frame rate cannot change it. */
    val sweepPeriodMs get() = ScanSession.TARGET_SECONDS * 1000
    const val sweepHeadDegrees = 24.0
    const val sweepLeadDegrees = 3.0

    /** Angle in radians for a time in milliseconds, wrapping every period. */
    fun sweepAngle(atMs: Double): Double {
        val period = sweepPeriodMs
        var phase = atMs % period
        if (phase < 0) phase += period
        return phase / period * 2 * Math.PI
    }

    /** Radius (unit space) for a normalised amplitude, clamped to the ring. */
    fun arcRadius(amplitude: Double): Double {
        val a = max(-1.0, min(1.0, amplitude))
        return ringMid + a * 0.5 * ringWidth * 0.9
    }

    val gridCircleRadii: List<Double>
        get() {
            val radii = mutableListOf<Double>()
            var r = ringInner + circlePitch
            while (r < ringOuter - 0.001) {
                radii.add(r)
                r += circlePitch
            }
            return radii
        }
}
