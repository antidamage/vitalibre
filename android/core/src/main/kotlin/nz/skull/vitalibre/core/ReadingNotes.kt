package nz.skull.vitalibre.core

/**
 * What a kept reading can be marked with. With a feed-quality series the two causes are told apart: a poor
 * feed is low signal quality and says nothing about the heart; a usable feed with uneven beats is an
 * irregular pulse. A reading from before the series cannot tell, and keeps the old phrase.
 */
object ReadingNote {
    /** Readings filed before the feed-quality index existed. */
    const val LOW_QUALITY_OR_ARRHYTHMIA = "Low quality or arrhythmia"
    const val LOW_SIGNAL = "Low signal quality"
    /** Never called arrhythmia: a fingertip camera cannot tell AF from ectopic beats or motion. */
    const val IRREGULAR = "Irregular pulse"
    const val IRREGULAR_REPEATED = "Irregular pulse, consider checking with a clinician"

    /** [feedMean] is the mean of the reading's feed-quality series, null for a reading without one. */
    fun note(feedMean: Double?, rhythm: Rhythm?, level: QualityLevel): String? {
        val irregular = rhythm == Rhythm.IRREGULAR
        if (feedMean != null) {
            if (FeedQuality.level(feedMean) == QualityLevel.POOR) return LOW_SIGNAL
            return if (irregular) IRREGULAR else null
        }
        return if (level == QualityLevel.POOR || irregular) LOW_QUALITY_OR_ARRHYTHMIA else null
    }

    /** One scan as [irregularRepeated] needs it. */
    data class Scan(val epochMillis: Long, val irregular: Boolean)

    /**
     * Whether [current] is the second irregular pulse among the last three scans (itself included) within
     * 30 minutes. [all] may be in any order.
     */
    fun irregularRepeated(current: Scan, all: List<Scan>): Boolean {
        if (!current.irregular) return false
        val recent = all.filter { it.epochMillis <= current.epochMillis && (current.epochMillis - it.epochMillis) <= 1_800_000L }
            .sortedByDescending { it.epochMillis }.take(3)
        return recent.count { it.irregular } >= 2
    }

    /** The note with the escalation applied. */
    fun text(note: String?, repeated: Boolean): String? =
        if (note == IRREGULAR && repeated) IRREGULAR_REPEATED else note
}
