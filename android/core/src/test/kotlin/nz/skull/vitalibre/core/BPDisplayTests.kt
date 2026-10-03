package nz.skull.vitalibre.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY = 86400.0
private const val NOW = 1_700_000_000.0

private fun point(daysAgo: Double, cs: Double = 120.0, cd: Double = 78.0) =
    CalibrationPoint(118.0, 76.0, cs, cd, NOW - daysAgo * DAY, cuffName = "test cuff")

class CalibrationValidityTests {
    @Test fun noCalibrationIsNotCurrent() = assertFalse(BPCalibration().isCurrent(NOW))

    @Test fun currentForThirtyDays() {
        assertTrue(BPCalibration().with(point(30.0)).isCurrent(NOW))
        assertFalse(BPCalibration().with(point(31.0)).isCurrent(NOW))
    }

    @Test fun newestPointDecides() {
        val c = BPCalibration().with(point(60.0)).with(point(2.0))
        assertTrue(c.isCurrent(NOW))
        assertEquals(NOW + 28 * DAY, c.expiryEpochSeconds(30)!!, 1.0)
    }

    @Test fun implausiblePointDoesNotCalibrate() {
        val c = BPCalibration().with(point(0.0, 130.0, 125.0))
        assertEquals(0, c.count)
        assertFalse(c.isCurrent(NOW))
    }

    @Test fun validityCanBeChanged() = assertFalse(BPCalibration().with(point(10.0)).isCurrent(NOW, 7))
}

class BPDisplayTests {
    @Test fun androidShowsItWhenSteadyWithoutCalibration() =
        assertNull(BPDisplay.hidden(Rhythm.STEADY, BPCalibration(), BPDisplay.Platform.ANDROID, "calibrated", NOW))

    @Test fun irregularShowsNoBPOnEitherPlatform() {
        val c = BPCalibration().with(point(1.0))
        assertEquals(BPHidden.IRREGULAR, BPDisplay.hidden(Rhythm.IRREGULAR, c, BPDisplay.Platform.IOS, "calibrated", NOW))
        assertEquals(BPHidden.IRREGULAR, BPDisplay.hidden(Rhythm.IRREGULAR, c, BPDisplay.Platform.ANDROID, "calibrated", NOW))
    }

    @Test fun iosHidesUntilCalibrated() {
        assertEquals(BPHidden.NEEDS_CALIBRATION, BPDisplay.hidden(Rhythm.STEADY, BPCalibration(), BPDisplay.Platform.IOS, "calibrated", NOW))
        assertNull(BPDisplay.hidden(Rhythm.STEADY, BPCalibration().with(point(1.0)), BPDisplay.Platform.IOS, "calibrated", NOW))
        assertEquals(BPHidden.DISABLED, BPDisplay.hidden(Rhythm.STEADY, BPCalibration().with(point(1.0)), BPDisplay.Platform.IOS, "never", NOW))
    }
}

class BPMarginTests {
    private val base = 120.0 to 78.0

    @Test fun populationErrorUntilThreePoints() {
        val text = BPMargin.text(testModel(), BPCalibration(), base, base)
        assertEquals("Average error about ±14 / ±9 mmHg; single readings can differ more.", text)
        assertTrue(BPMargin.text(testModel(), BPCalibration().with(point(0.0)), base, base).startsWith("Average error"))
    }

    @Test fun cuffMarginWithThreePoints() {
        var c = BPCalibration()
        for ((s, d) in listOf(118.0 to 76.0, 121.0 to 79.0, 125.0 to 81.0)) c = c.with(CalibrationPoint(120.0, 78.0, s, d, NOW))
        val text = BPMargin.text(testModel(), c, base, base)
        assertTrue(text.contains("3 cuff comparisons"), text)
        assertTrue(text.contains("±6 / ±4"), text)
    }

    @Test fun aWideSpreadIsNotCalledNinetyPercent() {
        // Residual SD about 12 systolic: 1.64 x 12 is well past the population 14, so the width shown is the population
        // error and the text must not claim the cuff spread.
        var c = BPCalibration()
        for ((s, d) in listOf(100.0 to 70.0, 125.0 to 85.0, 145.0 to 92.0, 110.0 to 66.0)) c = c.with(CalibrationPoint(120.0, 78.0, s, d, NOW))
        val text = BPMargin.text(testModel(), c, base, base)
        assertTrue(text.startsWith("Average error about ±14 / ±9"), text)
        assertFalse(text.contains("90%"), text)
    }
}

class FeedNoteTests {
    @Test fun poorFeedIsLowSignalWhateverTheRhythm() {
        assertEquals(ReadingNote.LOW_SIGNAL, ReadingNote.note(0.25, Rhythm.STEADY, QualityLevel.GOOD))
        assertEquals(ReadingNote.LOW_SIGNAL, ReadingNote.note(0.25, Rhythm.IRREGULAR, QualityLevel.GOOD))
    }

    @Test fun goodFeedWithUnevenBeatsIsIrregular() {
        assertEquals(ReadingNote.IRREGULAR, ReadingNote.note(0.9, Rhythm.IRREGULAR, QualityLevel.GOOD))
        assertNull(ReadingNote.note(0.9, Rhythm.STEADY, QualityLevel.GOOD))
    }

    @Test fun noFeedKeepsTheOldPhrase() {
        assertEquals(ReadingNote.LOW_QUALITY_OR_ARRHYTHMIA, ReadingNote.note(null, Rhythm.IRREGULAR, QualityLevel.GOOD))
        assertEquals(ReadingNote.LOW_QUALITY_OR_ARRHYTHMIA, ReadingNote.note(null, Rhythm.STEADY, QualityLevel.POOR))
        assertNull(ReadingNote.note(null, Rhythm.STEADY, QualityLevel.GOOD))
    }

    @Test fun escalatesOnlyOnSecondIrregularWithinThirtyMinutes() {
        val now = 10_000_000_000L
        val first = ReadingNote.Scan(now - 600_000, true)
        val steady = ReadingNote.Scan(now - 300_000, false)
        val second = ReadingNote.Scan(now, true)
        val all = listOf(first, steady, second)
        assertFalse(ReadingNote.irregularRepeated(first, all))
        assertTrue(ReadingNote.irregularRepeated(second, all))
        val late = ReadingNote.Scan(now + 7_200_000, true)
        assertFalse(ReadingNote.irregularRepeated(late, all + late))
        assertEquals(ReadingNote.IRREGULAR_REPEATED, ReadingNote.text(ReadingNote.IRREGULAR, true))
        assertEquals(ReadingNote.IRREGULAR, ReadingNote.text(ReadingNote.IRREGULAR, false))
    }
}

class ValidationExportTests {
    @Test fun carriesPairsAndReadingsAndHidesWhatIsNotShown() {
        val cal = BPCalibration().with(CalibrationPoint(118.0, 76.0, 124.0, 80.0, 1_700_000_000.0, "Pixel 9", 120.0, 78.0, "Cuff, \"arm\""))
        val r = ExportReading(1_700_000_100.0, "v1", 70.0, Rhythm.STEADY, 118.0, 74.0, 115, 72, false, 15.0, 15.0,
            listOf(0.9, 0.8), listOf("none", "noise"), null, listOf(ExcludedRange(3.0, 5.5)))
        val hidden = ValidationExport.csv(cal, listOf(r), "0.3 (1)", "v1")
        assertTrue(hidden.contains(ValidationExport.CALIBRATION_HEADER))
        assertTrue(hidden.contains("\"Cuff, \"\"arm\"\"\""), hidden)
        assertTrue(hidden.contains("calibration,2023-11-14T22:13:20Z,Pixel 9,0.3 (1),v1,124,80,118.0,76.0,120.0,78.0"), hidden)
        val line = hidden.lines().first { it.startsWith("reading,") }
        assertFalse(line.contains("118.0"), line)   // a hidden figure is hidden everywhere
        assertTrue(line.contains("reading,2023-11-14T22:15:00Z,v1,70.0,steady,,,,,"), line)
        assertTrue(line.contains("3.0-5.5"), line)
        assertTrue(line.endsWith(",0.90;0.80,none;noise"), line)
        assertTrue(ValidationExport.csv(cal, listOf(r.copy(showBP = true)), "0.3 (1)", "v1").contains(",118.0,74.0,115,72,"))
    }
}
