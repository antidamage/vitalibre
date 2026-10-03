package nz.skull.vitalibre.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class QualityLevel { POOR, FAIR, GOOD;

    val label get() = name.lowercase()
}

/**
 * What a kept reading can be marked with. One phrase covers both causes, in the owner's words
 * (Adeline, 2026-10-04: "mark the reading as 'low quality or arrhythmia'"): the numbers cannot tell a
 * weak signal from an unsteady rhythm, so the app says both and diagnoses neither.
 */
object ReadingNote {
    const val LOW_QUALITY_OR_ARRHYTHMIA = "Low quality or arrhythmia"
}

data class SignalQuality(
    val skewness: Double,
    val templateCorrelation: Double,
    /** Percent, from the raw channel's AC/DC. Gates but does not rank. */
    val perfusionIndex: Double,
) {
    /** 0..1. Skewness and template correlation rank; perfusion only gates. */
    val score: Double
        get() {
            if (perfusionIndex < MIN_PERFUSION_INDEX) return 0.0
            val skew = max(0.0, min(1.0, skewness / 0.6))
            val corr = max(0.0, min(1.0, (templateCorrelation - 0.5) / 0.4))
            return 0.4 * skew + 0.6 * corr
        }

    val level: QualityLevel get() = if (score >= 0.6) QualityLevel.GOOD else if (score >= 0.35) QualityLevel.FAIR else QualityLevel.POOR

    companion object {
        const val MIN_PERFUSION_INDEX = 0.05

        /** `filtered` is upward-positive at `fs`; `raw` is the unfiltered channel over the same window. */
        fun measure(filtered: DoubleArray, raw: DoubleArray, beats: List<Beat>, fs: Double): SignalQuality {
            val ac = Stats.percentile(filtered, 95.0) - Stats.percentile(filtered, 5.0)
            val dc = abs(Stats.mean(raw))
            val pi = if (dc > 0) ac / dc * 100 else 0.0
            return SignalQuality(Stats.skewness(filtered), templateCorrelation(filtered, beats, fs), pi)
        }

        /** Mean Pearson r of each beat's segment against the average segment. */
        fun templateCorrelation(y: DoubleArray, beats: List<Beat>, fs: Double): Double {
            val pre = (0.25 * fs).toInt()
            val post = (0.45 * fs).toInt()
            val segs = beats.mapNotNull { b ->
                if (b.index - pre >= 0 && b.index + post < y.size) y.copyOfRange(b.index - pre, b.index + post + 1) else null
            }
            if (segs.size < 3) return 0.0
            val len = segs[0].size
            val template = DoubleArray(len)
            for (s in segs) for (i in 0 until len) template[i] += s[i] / segs.size
            return segs.map { Stats.correlation(it, template) }.average()
        }
    }
}
