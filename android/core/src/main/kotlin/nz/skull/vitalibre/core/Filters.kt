package nz.skull.vitalibre.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/** One second-order section, direct form II transposed. */
class Biquad(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    fun run(x: DoubleArray): DoubleArray {
        var z1 = 0.0
        var z2 = 0.0
        return DoubleArray(x.size) { i ->
            val v = x[i]
            val y = b0 * v + z1
            z1 = b1 * v - a1 * y + z2
            z2 = b2 * v - a2 * y
            y
        }
    }

    companion object {
        /** 2nd-order Butterworth via the bilinear transform. */
        fun lowpass(cutoff: Double, fs: Double): Biquad {
            val k = tan(PI * cutoff / fs)
            val norm = 1 / (1 + sqrt(2.0) * k + k * k)
            return Biquad(
                k * k * norm, 2 * k * k * norm, k * k * norm,
                2 * (k * k - 1) * norm, (1 - sqrt(2.0) * k + k * k) * norm,
            )
        }

        fun highpass(cutoff: Double, fs: Double): Biquad {
            val k = tan(PI * cutoff / fs)
            val norm = 1 / (1 + sqrt(2.0) * k + k * k)
            return Biquad(
                norm, -2 * norm, norm,
                2 * (k * k - 1) * norm, (1 - sqrt(2.0) * k + k * k) * norm,
            )
        }
    }
}

object Filters {
    /** Zero-phase filtering: forward then backward, so peak times are not shifted. */
    fun filtfilt(x: DoubleArray, sections: List<Biquad>): DoubleArray {
        if (x.size <= 8) return x
        val pad = min(x.size - 1, 120)
        val padded = DoubleArray(x.size + 2 * pad)
        for (i in 0 until pad) padded[i] = 2 * x[0] - x[pad - i]
        x.copyInto(padded, pad)
        for (i in 1..pad) padded[pad + x.size - 1 + i] = 2 * x[x.size - 1] - x[x.size - 1 - i]
        var y = padded
        repeat(2) {
            for (s in sections) y = s.run(y)
            y.reverse()
        }
        return y.copyOfRange(pad, pad + x.size)
    }

    /** Band-pass 0.5-5 Hz by default (high-pass then low-pass, both 2nd order). */
    fun bandpass(x: DoubleArray, fs: Double, low: Double = 0.5, high: Double = 5.0): DoubleArray {
        val mean = if (x.isEmpty()) 0.0 else x.average()
        val centred = DoubleArray(x.size) { x[it] - mean }
        return filtfilt(centred, listOf(Biquad.highpass(low, fs), Biquad.lowpass(high, fs)))
    }

    /** Linear-interpolated resample onto a uniform grid. `times` must be ascending. */
    fun resample(times: DoubleArray, values: DoubleArray, rate: Double): DoubleArray {
        if (times.size <= 1 || times.size != values.size) return values
        val t0 = times[0]
        val t1 = times[times.size - 1]
        val n = ((t1 - t0) * rate).toInt() + 1
        val out = DoubleArray(n)
        var j = 0
        for (i in 0 until n) {
            val t = t0 + i / rate
            while (j < times.size - 2 && times[j + 1] < t) j++
            val span = times[j + 1] - times[j]
            val f = if (span > 0) min(1.0, max(0.0, (t - times[j]) / span)) else 0.0
            out[i] = values[j] + f * (values[j + 1] - values[j])
        }
        return out
    }

    fun movingAverage(x: DoubleArray, window: Int): DoubleArray {
        if (window <= 1 || x.isEmpty()) return x
        val prefix = DoubleArray(x.size + 1)
        for (i in x.indices) prefix[i + 1] = prefix[i] + x[i]
        val half = window / 2
        return DoubleArray(x.size) { i ->
            val lo = max(0, i - half)
            val hi = min(x.size, i + half + 1)
            (prefix[hi] - prefix[lo]) / (hi - lo)
        }
    }
}

object Stats {
    fun mean(x: DoubleArray): Double = if (x.isEmpty()) 0.0 else x.average()

    fun median(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        val s = x.sortedArray()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    fun std(x: DoubleArray): Double {
        if (x.size <= 1) return 0.0
        val m = mean(x)
        return sqrt(x.sumOf { (it - m) * (it - m) } / x.size)
    }

    fun skewness(x: DoubleArray): Double {
        val s = std(x)
        if (x.size <= 2 || s <= 1e-12) return 0.0
        val m = mean(x)
        return x.sumOf { ((it - m) / s).pow(3) } / x.size
    }

    fun percentile(x: DoubleArray, p: Double): Double {
        if (x.isEmpty()) return 0.0
        val s = x.sortedArray()
        return s[min(s.size - 1, max(0, (p / 100 * (s.size - 1) + 0.5).toInt()))]
    }

    fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val n = min(a.size, b.size)
        if (n <= 2) return 0.0
        val ma = mean(a.copyOfRange(0, n))
        val mb = mean(b.copyOfRange(0, n))
        var sab = 0.0
        var saa = 0.0
        var sbb = 0.0
        for (i in 0 until n) {
            val da = a[i] - ma
            val db = b[i] - mb
            sab += da * db
            saa += da * da
            sbb += db * db
        }
        return if (saa > 0 && sbb > 0) sab / sqrt(saa * sbb) else 0.0
    }

    fun abs(x: DoubleArray) = DoubleArray(x.size) { abs(x[it]) }
}
