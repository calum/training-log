package com.calum.traininglog

import com.calum.traininglog.core.HyroxDivision
import com.calum.traininglog.core.HyroxStandards
import com.calum.traininglog.core.HyroxStation
import com.calum.traininglog.core.StationEntry
import com.calum.traininglog.core.StationLog
import com.calum.traininglog.core.StationPrescription
import com.calum.traininglog.core.StationWeekBest
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HyroxFormatTest {
    private val d = HyroxDivision.MEN_OPEN

    @Test fun standards() {
        assertEquals("50 m · 152 kg", HyroxFormat.standard(HyroxStation.SLED_PUSH, HyroxStandards.of(d, HyroxStation.SLED_PUSH)))
        assertEquals("100 reps · 6 kg", HyroxFormat.standard(HyroxStation.WALL_BALLS, HyroxStandards.of(d, HyroxStation.WALL_BALLS)))
        assertEquals("1000 m", HyroxFormat.standard(HyroxStation.ROW, HyroxStandards.of(d, HyroxStation.ROW)))
        assertEquals("200 m · 24 kg each", HyroxFormat.standard(HyroxStation.FARMERS_CARRY, HyroxStandards.of(d, HyroxStation.FARMERS_CARRY)))
        val o = mapOf("sled_push" to 142.5)
        assertEquals("50 m · 142.5 kg", HyroxFormat.standard(HyroxStation.SLED_PUSH, HyroxStandards.of(d, HyroxStation.SLED_PUSH, o)))
    }

    @Test fun prescriptionIsConcrete() {
        val p = StationPrescription(1, "x", 40.0, 12, null, 4, 90, "")
        assertEquals("4 x 12 m at 40 kg, rest 90 s, stop at effort 8/10", HyroxFormat.prescription(HyroxStation.SLED_PUSH, p))
        val one = StationPrescription(5, "x", null, 1000, null, 1, 0, "")
        assertEquals("1000 m, stop at effort 8/10", HyroxFormat.prescription(HyroxStation.ROW, one))
        val reps = StationPrescription(1, "x", 3.0, null, 10, 3, 60, "")
        assertEquals("3 x 10 reps at 3 kg, rest 60 s, stop at effort 8/10", HyroxFormat.prescription(HyroxStation.WALL_BALLS, reps))
    }

    @Test fun timeAndKg() {
        assertEquals("1:05", HyroxFormat.time(65))
        assertEquals("12.5", HyroxFormat.kg(12.5))
        assertEquals("40", HyroxFormat.kg(40.0))
    }

    @Test fun statusAndBest() {
        assertEquals("Not tried yet", HyroxFormat.status(HyroxStation.SLED_PUSH, emptyList(), 1))
        val e = listOf(
            StationEntry(LocalDate.of(2026, 1, 2), StationLog("sled_push", 40.0, 20, null, 60)),
            StationEntry(LocalDate.of(2026, 1, 9), StationLog("sled_push", 40.0, 20, null, 50)),
        )
        assertEquals("Level 2 of 6 · best 20 m in 0:50", HyroxFormat.status(HyroxStation.SLED_PUSH, e, 2))
    }

    @Test fun chartPrefersLoadWhenItChangesAndFillsGaps() {
        val w = LocalDate.of(2026, 1, 5)
        val weeks = listOf(
            StationWeekBest(w, null, null, null, 0),
            StationWeekBest(w.plusWeeks(1), 40.0, 60, 20, 1),
            StationWeekBest(w.plusWeeks(2), null, null, null, 0),
            StationWeekBest(w.plusWeeks(3), 50.0, 55, 20, 1),
        )
        val c = HyroxFormat.chart(HyroxStation.SLED_PUSH, weeks)!!
        assertTrue(c.byLoad)
        assertEquals(listOf(40f, 40f, 50f), c.values)
        assertEquals(listOf(1, 2, 3), c.weekIndexes)
        assertNull(HyroxFormat.chart(HyroxStation.ROW, weeks.take(1)))
        assertTrue(!HyroxFormat.chart(HyroxStation.ROW, weeks)!!.byLoad)
    }

    @Test fun divisionSummaryMentionsLoads() {
        assertTrue(HyroxFormat.divisionSummary(d).contains("push 152 kg"))
        assertTrue(HyroxFormat.approx(HyroxDivision.WOMEN_OPEN))
        assertTrue(!HyroxFormat.approx(d))
    }
}
