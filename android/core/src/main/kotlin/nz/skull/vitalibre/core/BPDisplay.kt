package nz.skull.vitalibre.core

import kotlin.math.roundToInt

/** Why a blood-pressure figure is not shown. A null from [BPDisplay.hidden] means show it. */
enum class BPHidden(val line: String) {
    /** iOS shows a figure only while a cuff calibration is current. */
    NEEDS_CALIBRATION("Blood pressure is hidden until you calibrate with a cuff reading."),

    /** An irregular pulse corrupts the interval features, so the reading carries heart rate and no BP. */
    IRREGULAR("Blood pressure is not shown when the pulse is irregular."),

    /** `iosBpDisplay` is "never". */
    DISABLED("Blood pressure is not shown on this device."),
}

/**
 * The rules for when a blood-pressure figure is shown (specs/ppg-vitals-app.md, "Blood pressure display").
 * The figure is always computed and stored; this only decides what the screen, the share text and the
 * export carry.
 */
object BPDisplay {
    enum class Platform { IOS, ANDROID }

    fun hidden(
        rhythm: Rhythm?, calibration: BPCalibration, platform: Platform, mode: String,
        nowEpochSeconds: Double, validDays: Int = BPCalibration.DEFAULT_VALID_DAYS,
    ): BPHidden? {
        if (rhythm == Rhythm.IRREGULAR) return BPHidden.IRREGULAR
        if (platform != Platform.IOS) return null
        if (mode == "never") return BPHidden.DISABLED
        return if (calibration.isCurrent(nowEpochSeconds, validDays)) null else BPHidden.NEEDS_CALIBRATION
    }
}

/** The small print under a figure: how far it can be out. */
object BPMargin {
    fun text(model: BPModel, calibration: BPCalibration, base: Pair<Double, Double>, legacy: Pair<Double, Double>): String {
        val (ws, wd) = calibration.halfWidths(model.halfWidthSystolic, model.halfWidthDiastolic, base, legacy)
        val s = ws.roundToInt()
        val d = wd.roundToInt()
        // Shown only when the cuff spread is what the width is made of: if it is wider than the population error on
        // either component the shown width is the population error, and "90% of your readings" would be false.
        val m = calibration.cuffSpread(base, legacy)
        if (m != null && m.first <= model.halfWidthSystolic && m.second <= model.halfWidthDiastolic) {
            return "±$s / ±$d mmHg: about 90% of your ${calibration.count} cuff comparisons fall inside. Measured on the same readings, so a little optimistic."
        }
        return "Average error about ±$s / ±$d mmHg; single readings can differ more."
    }
}
