package com.calum.traininglog.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TreadmillStatsTest {
    private val flat = listOf(Segment(3600, 6.0, 0.0, "steady"))

    @Test fun flatHourAtSixKmh() {
        val s = TreadmillStatsCalc.at(flat, 3600, 70.0)
        assertEquals(6000, s.distanceM)
        assertEquals(0, s.climbM)
        // ACSM: VO2 = 0.1 x 100 + 3.5 = 13.5 ml/kg/min -> 13.5 x 70 / 1000 x 5 = 4.725 kcal/min
        assertEquals(284, s.kcal)
    }

    @Test fun inclineAddsClimbAndCalories() {
        val hill = listOf(Segment(1800, 5.5, 8.0, "hill"))
        val s = TreadmillStatsCalc.at(hill, 1800, 80.0)
        assertEquals(2750, s.distanceM)
        assertEquals(220, s.climbM) // 2750 m x 8%
        val flatSame = TreadmillStatsCalc.at(listOf(Segment(1800, 5.5, 0.0, "flat")), 1800, 80.0)
        assertTrue(s.kcal > flatSame.kcal)
    }

    @Test fun partialSegmentsCountInProportion() {
        val segs = listOf(Segment(600, 6.0, 0.0, "a"), Segment(600, 6.0, 10.0, "b"))
        val half = TreadmillStatsCalc.at(segs, 900, 70.0)
        assertEquals(1500, half.distanceM)
        assertEquals(50, half.climbM) // only the last 300 s are uphill: 500 m x 10%
    }

    @Test fun nothingBeforeStartAndCappedAtTheEnd() {
        assertEquals(TreadmillStats(0, 0, 0), TreadmillStatsCalc.at(flat, 0, 70.0))
        assertEquals(TreadmillStatsCalc.at(flat, 3600, 70.0), TreadmillStatsCalc.at(flat, 99999, 70.0))
        assertEquals(TreadmillStats(0, 0, 0), TreadmillStatsCalc.at(emptyList(), 100, 70.0))
    }

    @Test fun heavierBodyBurnsMore() {
        assertTrue(TreadmillStatsCalc.at(flat, 1800, 90.0).kcal > TreadmillStatsCalc.at(flat, 1800, 60.0).kcal)
    }

    @Test fun exportShowsTheNewNumbers() {
        val rec = SessionRecord(
            "1", java.time.LocalDate.of(2026, 11, 1), SessionType.TREADMILL, 30, 30, Feeling.GOOD,
            treadmill = TreadmillLog(TreadmillStyle.LADDER, 1800, 1800, Effort.JUST_RIGHT, 2750, 220, 311),
        )
        val text = Export.summary(listOf(rec), java.time.LocalDate.of(2026, 11, 1), java.time.LocalDate.of(2026, 11, 1))
        assertTrue("2750 m, 220 m climb, about 311 kcal" in text, text)
    }
}
