package nz.skull.vitalibre.trainer

import nz.skull.vitalibre.core.BPModel
import nz.skull.vitalibre.core.Biquad
import nz.skull.vitalibre.core.Filters
import nz.skull.vitalibre.core.PPGSample
import nz.skull.vitalibre.core.ScanFailure
import nz.skull.vitalibre.core.ScanOutcome
import nz.skull.vitalibre.core.ScanSession
import nz.skull.vitalibre.core.Sex
import nz.skull.vitalibre.core.Stats
import java.io.File
import java.util.Random

/**
 * Turns dataset waveform segments (written by tools/train/extract.py, systolic upstroke upward) into the
 * five BPEstimator features by running them through the app's own ScanSession.analyse, as if a phone
 * camera had produced them: resampled to 30 fps, carried as a green channel that falls at systole (the
 * app negates it), optionally with camera noise added. No feature is computed here.
 *
 * args: <segments.tsv> <out.csv> [--window 12] [--noise 0.0007] [--perf 0.01] [--seed 1]
 */
private const val CAMERA_FPS = 30.0
private const val DC = 100.0
private const val SKIP_SECONDS = 2.0

class Segment(val subject: String, val dataset: String, val tag: String, val age: Int, val sex: String,
              val sbp: Double, val dbp: Double, val fs: Double, val values: DoubleArray)

fun readSegments(file: File): List<Segment> = file.useLines { lines ->
    lines.filter { it.isNotBlank() }.map { line ->
        val f = line.split('\t')
        Segment(f[0], f[1], f[2], f[3].toInt(), f[4], f[5].toDouble(), f[6].toDouble(), f[7].toDouble(),
            f[8].split(' ').filter { it.isNotEmpty() }.map { it.toDouble() }.toDoubleArray())
    }.toList()
}

/** The camera's frame stream: band-limit below the new Nyquist, then resample to 30 fps on a uniform clock. */
fun toCameraRate(v: DoubleArray, fs: Double): DoubleArray {
    if (fs <= CAMERA_FPS + 1e-9) return v
    val limited = Filters.filtfilt(v, listOf(Biquad.lowpass(12.0, fs), Biquad.lowpass(12.0, fs)))
    val t = DoubleArray(limited.size) { it / fs }
    return Filters.resample(t, limited, CAMERA_FPS)
}

class Row(val seg: Segment, val start: Double, val noisy: Boolean, val f: nz.skull.vitalibre.core.BPFeatures,
          val quality: Double, val rhythm: String)

fun main(args: Array<String>) {
    val input = File(args[0])
    val output = File(args[1])
    fun opt(name: String, def: Double) = args.indexOf("--$name").let { if (it >= 0) args[it + 1].toDouble() else def }
    val window = opt("window", 12.0)
    val noise = opt("noise", 0.0007)
    val perf = opt("perf", 0.01)
    val seed = opt("seed", 1.0).toLong()

    val model = BPModel.prior1
    val failures = HashMap<String, Int>()
    val rows = ArrayList<Row>()
    var windows = 0
    for (seg in readSegments(input)) {
        val cam = toCameraRate(seg.values, seg.fs)
        val n = (window * CAMERA_FPS).toInt()
        // A segment too short for a second window is analysed whole (BUT PPG records are 10 s long).
        val starts = if (cam.size >= ((window + SKIP_SECONDS) * CAMERA_FPS).toInt())
            generateSequence(SKIP_SECONDS) { it + window }.takeWhile { ((it + window) * CAMERA_FPS).toInt() <= cam.size }.toList()
        else listOf(0.0)
        for (s0 in starts) {
            val lo = (s0 * CAMERA_FPS).toInt()
            val x = cam.copyOfRange(lo, if (starts.size == 1 && s0 == 0.0 && cam.size < n) cam.size else minOf(cam.size, lo + n))
            // Pulse scale: p95-p5 of the band-passed window becomes one unit, so `perf` is the AC/DC of the green channel.
            val span = Stats.percentile(Filters.bandpass(x, CAMERA_FPS), 95.0) - Stats.percentile(Filters.bandpass(x, CAMERA_FPS), 5.0)
            if (span <= 0 || !span.isFinite()) { failures.merge("flat", 1, Int::plus); continue }
            val mean = Stats.mean(x)
            windows++
            for (noisy in listOf(false, true)) {
                val rnd = Random(seed * 1_000_003 + (seg.subject + seg.tag).hashCode() * 31L + (s0 * 10).toLong() * 7 + if (noisy) 1 else 0)
                val session = ScanSession()
                for (i in x.indices) {
                    val up = (x[i] - mean) / span
                    val g = DC * (1 - perf * up) + if (noisy) rnd.nextGaussian() * noise * DC else 0.0
                    session.add(PPGSample(i / CAMERA_FPS, 220.0, g, 100.0, 0.0))
                }
                val sex = if (seg.sex == "M") Sex.MALE else Sex.FEMALE
                when (val o = session.analyse(model, seg.age, sex)) {
                    is ScanOutcome.Success -> rows.add(Row(seg, s0, noisy, o.result.features, o.result.quality, o.result.rhythm.name))
                    is ScanOutcome.Failure -> failures.merge(seg.dataset + (if (noisy) "/noisy/" else "/clean/") + o.failure::class.simpleName, 1, Int::plus)
                }
            }
        }
    }
    output.printWriter().use { w ->
        w.println("subject_id,dataset,window_start,age,sex,sbp,dbp,heartRate,intervalCV,crestFraction,skewness,reflectionIndex,segment,noisy,quality,rhythm")
        for (r in rows) with(r) {
            w.println(listOf(seg.subject, seg.dataset, "%.1f".format(start), seg.age, seg.sex, seg.sbp, seg.dbp,
                f.heartRate, f.intervalCV, f.crestFraction, f.skewness, f.reflectionIndex, seg.tag, if (noisy) 1 else 0,
                "%.3f".format(quality), rhythm).joinToString(","))
        }
    }
    println("windows cut: $windows; rows written: ${rows.size} (clean + noisy variants); subjects: ${rows.map { it.seg.subject }.distinct().size}")
    println("failures: " + failures.toSortedMap())
}
