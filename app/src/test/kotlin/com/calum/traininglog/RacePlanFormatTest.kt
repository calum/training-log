package com.calum.traininglog

import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.RacePhase
import com.calum.traininglog.core.RacePlanSettings
import com.calum.traininglog.core.RacePlanner
import com.calum.traininglog.core.RunStage
import com.calum.traininglog.core.SessionKind
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.TreadmillLog
import com.calum.traininglog.core.TreadmillStyle
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RacePlanFormatTest {
    private val nov19 = LocalDate.of(2026, 11, 19)
    private val plan = RacePlanner.build(RacePlanSettings(), emptyList(), nov19)

    private fun gym(date: LocalDate, id: String = "g$date") = SessionRecord(id, date, SessionType.GYM, 50, 50, Feeling.GOOD)
    private fun walk(date: LocalDate, id: String = "w$date") = SessionRecord(
        id, date, SessionType.TREADMILL, 40, 40, Feeling.GOOD, treadmill = TreadmillLog(TreadmillStyle.STEADY, 2400, 2400),
    )

    @Test fun countdownWording() {
        assertEquals("100 days to go", RacePlanFormat.countdown(100))
        assertEquals("1 day to go", RacePlanFormat.countdown(1))
        assertEquals("Race day", RacePlanFormat.countdown(0))
        assertEquals("Race done", RacePlanFormat.countdown(-3))
        assertEquals("100", RacePlanFormat.countdownNumber(100))
        assertNull(RacePlanFormat.countdownNumber(0))
        assertEquals(100L, plan.daysToGo(nov19))
        assertEquals("Saturday 27 February 2027", RacePlanFormat.raceDateText(plan.race))
    }

    @Test fun weekLabels() {
        assertEquals("Week 1 of 14 · Base", RacePlanFormat.weekHeadline(plan, nov19))
        assertEquals("Week 1 of 14 · Base", RacePlanFormat.weekHeadline(plan, LocalDate.of(2026, 11, 24)))
        assertEquals("Week 14 of 14 · Race week", RacePlanFormat.weekHeadline(plan, LocalDate.of(2027, 2, 23)))
        assertEquals("Plan complete", RacePlanFormat.weekHeadline(plan, LocalDate.of(2027, 3, 8)))
        assertEquals("Week 1 Base", RacePlanFormat.weekShort(plan, nov19))
        assertEquals("23-29 Nov", RacePlanFormat.weekRange(plan.week(1)!!))
        assertEquals("28 Dec - 3 Jan", RacePlanFormat.weekRange(plan.week(6)!!))
        RacePhase.entries.forEach { assertTrue(RacePlanFormat.phasePurpose(it).isNotBlank()) }
    }

    @Test fun doneMatchingIsForgivingAndUsesEachRecordOnce() {
        val w = plan.week(1)!!
        val strength = RacePlanFormat.counted(w).filter { it.kind == SessionKind.STRENGTH }
        assertEquals(2, strength.size)
        val today = w.end
        // One gym log on the day of the first strength session, nothing else.
        val one = RacePlanFormat.doneSessions(w, listOf(gym(strength[0].date)), today)
        assertEquals(setOf(strength[0]), one)
        // A gym log a day off the plan still counts, but one record never covers two sessions.
        val off = RacePlanFormat.doneSessions(w, listOf(gym(strength[0].date.plusDays(1))), today)
        assertEquals(1, off.size)
        // A walk never counts as strength.
        assertTrue(RacePlanFormat.doneSessions(w, listOf(walk(strength[0].date)), today).none { it.kind == SessionKind.STRENGTH })
        // Logs from the future do not count.
        assertTrue(RacePlanFormat.doneSessions(w, listOf(gym(strength[0].date)), strength[0].date.minusDays(1)).isEmpty())
    }

    @Test fun pastUndoneSessionsAreOptionalNeverMissed() {
        val w = plan.week(1)!!
        val first = RacePlanFormat.counted(w).minBy { it.date }
        assertEquals(SessionState.OPTIONAL, RacePlanFormat.stateOf(first, emptySet(), w.end))
        assertEquals(SessionState.UPCOMING, RacePlanFormat.stateOf(first, emptySet(), first.date))
        assertEquals(SessionState.DONE, RacePlanFormat.stateOf(first, setOf(first), w.end))
        assertEquals(first, RacePlanFormat.nextSession(w, emptySet(), w.start))
        assertFalse(RacePlanFormat.weekProgressText(0, 5, 0, 215, true).lowercase().contains("miss"))
    }

    @Test fun progressWordsAreEncouraging() {
        assertEquals("Nice work: 3 of 5 done, 138 of 215 planned minutes. The rest are optional.", RacePlanFormat.weekProgressText(3, 5, 138, 215, false))
        assertTrue(RacePlanFormat.weekProgressText(0, 5, 0, 215, false).startsWith("Fresh week"))
        assertTrue(RacePlanFormat.weekProgressText(5, 5, 215, 215, false).startsWith("All done"))
        assertTrue(RacePlanFormat.weekProgressText(2, 5, 90, 215, true).contains("optional"))
    }

    @Test fun todaysOpenSessionIsNullOnRestDaysAndOnceDone() {
        val w = plan.week(1)!!
        val first = RacePlanFormat.counted(w).minBy { it.date }
        assertEquals(first, RacePlanFormat.todaysOpenSession(plan, emptyList(), first.date))
        val rec = if (first.kind == SessionKind.STRENGTH) gym(first.date) else walk(first.date)
        assertNull(RacePlanFormat.todaysOpenSession(plan, listOf(rec), first.date))
        assertNull(RacePlanFormat.todaysOpenSession(plan, emptyList(), nov19)) // before the plan
        val noSession = w.start.let { s -> generateSequence(s) { it.plusDays(1) }.take(7).first { d -> w.sessions.none { it.date == d } } }
        assertNull(RacePlanFormat.todaysOpenSession(plan, emptyList(), noSession))
        assertNull(RacePlanFormat.todaysOpenSession(plan, emptyList(), plan.race)) // race day itself is not a "start" session
    }

    @Test fun timeCapSteps() {
        assertEquals("No limit", RacePlanFormat.capLabel(null))
        assertEquals("300 min", RacePlanFormat.capLabel(300))
        assertEquals("4 h 15 min", RacePlanFormat.capHours(255))
        assertEquals("5 h", RacePlanFormat.capHours(300))
        assertEquals(600, RacePlanFormat.capDown(null))
        assertEquals(120, RacePlanFormat.capDown(120))
        assertEquals(285, RacePlanFormat.capDown(300))
        assertNull(RacePlanFormat.capUp(600))
        assertNull(RacePlanFormat.capUp(null))
        assertEquals(315, RacePlanFormat.capUp(300))
    }

    @Test fun runStageTexts() {
        RunStage.entries.forEach { assertTrue(RacePlanFormat.stageBlurb(it).isNotBlank()) }
        assertNull(RacePlanFormat.rungText(RunStage.NONE, 0))
        assertEquals("Step 1 of 4", RacePlanFormat.rungText(RunStage.RUN_WALK, 0)!!.first)
        assertNotNull(RacePlanFormat.rungText(RunStage.RACE_RUNS, 9))
        assertTrue(RacePlanFormat.keySession(plan.week(1)!!).isNotBlank())
    }
}
