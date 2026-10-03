package nz.skull.vitalibre.core

import kotlin.math.max
import kotlin.math.min

data class BPFeatures(
    val heartRate: Double,
    val intervalCV: Double,
    /** Foot-to-peak time as a fraction of the beat interval. */
    val crestFraction: Double,
    val skewness: Double,
    /** Secondary (reflected) peak height over the main peak; 0 when none is found. */
    val reflectionIndex: Double,
)

data class BPRange(
    val systolicLow: Int, val systolicHigh: Int,
    val diastolicLow: Int, val diastolicHigh: Int,
    val systolic: Int, val diastolic: Int,
    /** True once a cuff reading or a usual resting pressure is in play. */
    val calibrated: Boolean = false,
) {
    /** A single figure per component once calibrated; the population range until then. */
    val text: String
        get() = if (calibrated) "$systolic / $diastolic" else "$systolicLow–$systolicHigh / $diastolicLow–$diastolicHigh"
}

/** The person's own resting blood pressure, e.g. from a medical record. Replaces the age and sex prior. */
data class UsualBP(val systolic: Int, val diastolic: Int) {
    val isPlausible get() = CalibrationPoint.isPlausible(systolic.toDouble(), diastolic.toDouble())
}

enum class Sex { UNSPECIFIED, FEMALE, MALE }

data class BPTerm(val feature: String, val mean: Double, val scale: Double, val systolic: Double, val diastolic: Double)

/**
 * Versioned weights, loaded from bp-model.json so they can be replaced without a code change. v1 ships
 * a population prior plus small capped feature adjustments and is NOT validated.
 */
data class BPModel(
    val version: String,
    val validated: Boolean,
    val baseSystolic: Double, val baseDiastolic: Double,
    val ageSystolicPerYear: Double, val ageDiastolicPerYear: Double,
    val referenceAge: Double,
    val maleSystolicOffset: Double, val maleDiastolicOffset: Double,
    val terms: List<BPTerm>,
    val maxSystolicAdjust: Double, val maxDiastolicAdjust: Double,
    val halfWidthSystolic: Double, val halfWidthDiastolic: Double,
) {
    companion object {
        fun fromJson(text: String): BPModel {
            val o = MiniJson.parse(text) as Map<*, *>
            fun num(k: String) = (o[k] as Number).toDouble()
            val terms = (o["terms"] as List<*>).map {
                val t = it as Map<*, *>
                BPTerm(t["feature"] as String, (t["mean"] as Number).toDouble(), (t["scale"] as Number).toDouble(),
                    (t["systolic"] as Number).toDouble(), (t["diastolic"] as Number).toDouble())
            }
            return BPModel(
                o["version"] as String, o["validated"] as Boolean,
                num("baseSystolic"), num("baseDiastolic"), num("ageSystolicPerYear"), num("ageDiastolicPerYear"),
                num("referenceAge"), num("maleSystolicOffset"), num("maleDiastolicOffset"), terms,
                num("maxSystolicAdjust"), num("maxDiastolicAdjust"), num("halfWidthSystolic"), num("halfWidthDiastolic"),
            )
        }

        /** Same numbers as bp-model.json (a test keeps them equal); used only if the file is missing. */
        val prior1 = BPModel(
            "prior-1", false, 118.0, 76.0, 0.5, 0.15, 30.0, 2.0, 1.0,
            listOf(
                BPTerm("heartRate", 70.0, 12.0, 2.0, 1.5),
                BPTerm("crestFraction", 0.30, 0.06, -2.5, -1.5),
                BPTerm("skewness", 0.60, 0.40, -1.0, -0.5),
                BPTerm("reflectionIndex", 0.15, 0.10, 2.0, 1.0),
            ),
            8.0, 5.0, 14.0, 9.0,
        )
    }
}

object BPEstimator {
    private fun value(name: String, f: BPFeatures) = when (name) {
        "heartRate" -> f.heartRate
        "intervalCV" -> f.intervalCV
        "crestFraction" -> f.crestFraction
        "skewness" -> f.skewness
        "reflectionIndex" -> f.reflectionIndex
        else -> 0.0
    }

    /** The starting point before any pulse adjustment: the typical resting pressure if given, else the age and sex prior. */
    fun baseline(model: BPModel, age: Int?, sex: Sex, usual: UsualBP? = null): Pair<Double, Double> {
        var sys = model.baseSystolic
        var dia = model.baseDiastolic
        val haveUsual = usual != null && usual.isPlausible
        if (haveUsual) {
            sys = usual!!.systolic.toDouble(); dia = usual.diastolic.toDouble()
        } else if (age != null) {
            val years = max(18, min(90, age)).toDouble() - model.referenceAge
            sys += years * model.ageSystolicPerYear
            dia += years * model.ageDiastolicPerYear
        }
        if (!haveUsual) {
            if (sex == Sex.MALE) { sys += model.maleSystolicOffset; dia += model.maleDiastolicOffset }
            if (sex == Sex.FEMALE) { sys -= model.maleSystolicOffset; dia -= model.maleDiastolicOffset }
        }
        return sys to dia
    }

    /** The model's own value before any calibration: the starting point plus the capped pulse adjustment. */
    fun raw(f: BPFeatures, model: BPModel, age: Int?, sex: Sex, usual: UsualBP? = null): Pair<Double, Double> {
        var (sys, dia) = baseline(model, age, sex, usual)
        var dSys = 0.0
        var dDia = 0.0
        for (t in model.terms) {
            val z = (value(t.feature, f) - t.mean) / t.scale
            dSys += z * t.systolic
            dDia += z * t.diastolic
        }
        sys += max(-model.maxSystolicAdjust, min(model.maxSystolicAdjust, dSys))
        dia += max(-model.maxDiastolicAdjust, min(model.maxDiastolicAdjust, dDia))
        return sys to dia
    }

    fun estimate(
        f: BPFeatures, model: BPModel, age: Int?, sex: Sex, usual: UsualBP? = null,
        calibration: BPCalibration = BPCalibration(),
    ): BPRange {
        val base = baseline(model, age, sex, usual)
        val legacy = model.baseSystolic to model.baseDiastolic
        val (rs, rd) = raw(f, model, age, sex, usual)
        val (os, od) = calibration.offset(base, legacy)
        val s = Math.round(rs + os).toInt()
        val d = Math.round(rd + od).toInt()
        val (ws, wd) = calibration.halfWidths(model.halfWidthSystolic, model.halfWidthDiastolic, base, legacy)
        val ls = Math.round(ws).toInt()
        val ld = Math.round(wd).toInt()
        return BPRange(s - ls, s + ls, d - ld, d + ld, s, d, calibration.count > 0 || usual?.isPlausible == true)
    }

    /**
     * Features from the filtered signal and its accepted beats. [groups] and [kept] are for a reading with
     * stretches discarded: beat shapes are read within each kept group (an interval is never taken across a
     * cut) and skewness over the kept samples only.
     */
    fun features(
        y: DoubleArray, beats: List<Beat>, hr: HeartRateEstimate, fs: Double,
        groups: List<List<Beat>>? = null, kept: DoubleArray? = null,
    ): BPFeatures {
        val cv = Stats.std(hr.intervals) / max(1e-9, Stats.mean(hr.intervals))
        val crest = mutableListOf<Double>()
        val reflect = mutableListOf<Double>()
        for (g in groups ?: listOf(beats)) beatShapes(y, g, fs, crest, reflect)
        return BPFeatures(hr.bpm, cv, Stats.median(crest.toDoubleArray()), Stats.skewness(kept ?: y), Stats.median(reflect.toDoubleArray()))
    }

    private fun beatShapes(y: DoubleArray, beats: List<Beat>, fs: Double, crest: MutableList<Double>, reflect: MutableList<Double>) {
        for ((i, b) in beats.withIndex()) {
            val interval = if (i + 1 < beats.size) beats[i + 1].time - b.time else if (i > 0) b.time - beats[i - 1].time else 0.0
            if (interval < HeartRate.MIN_INTERVAL || interval > HeartRate.MAX_INTERVAL) continue
            // Foot: lowest sample in the 0.4 s before the peak.
            val lo = max(0, b.index - (0.4 * fs).toInt())
            var foot = lo
            for (k in lo..b.index) if (y[k] < y[foot]) foot = k
            crest.add((b.index - foot) / fs / interval)
            // Reflected peak: highest local maximum 0.15-0.55 of the interval after the main peak.
            val from = b.index + (0.15 * interval * fs).toInt()
            val to = min(y.size - 2, b.index + (0.55 * interval * fs).toInt())
            var second = 0.0
            if (from < to && b.amplitude > 0) {
                for (k in max(1, from)..to) if (y[k] > y[k - 1] && y[k] >= y[k + 1]) second = max(second, y[k])
            }
            reflect.add(max(0.0, second / b.amplitude))
        }
    }
}
