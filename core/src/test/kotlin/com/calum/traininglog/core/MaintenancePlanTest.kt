package com.calum.traininglog.core

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** After the race the plan rolls into a steady, easy, knee-friendly weekly plan, built from the same sessions. */
class MaintenancePlanTest {
    private val race = LocalDate.of(2027, 2, 27)
    private val tue = LocalDate.of(2027, 3, 2)   // the Tuesday after race week
    private val wedAfter = LocalDate.of(2027, 3, 10)
    private val cfg = RacePlanSettings(sessionsPerWeek = 4, maxWeeklyMinutes = 300)
    private fun profile(c: RacePlanSettings = cfg) = Profile(settings = UserSettings(racePlan = c))

    private fun walk(d: LocalDate, min: Int = 40) =
        SessionRecord("w-$d", d, SessionType.TREADMILL, min, min, Feeling.GOOD, treadmill = TreadmillLog(TreadmillStyle.STEADY, min * 60, min * 60))

    private fun plan(today: LocalDate = wedAfter, c: RacePlanSettings = cfg, records: List<SessionRecord> = emptyList()) =
        assertNotNull(Coach.racePlan(profile(c), records, today))

    @Test fun beforeAndDuringRaceWeekTheRacePlanIsUsedAfterwardsTheMaintenancePlan() {
        assertFalse(plan(LocalDate.of(2026, 11, 19)).maintenance)
        assertFalse(plan(race.minusDays(3)).maintenance)
        assertFalse(plan(race).maintenance)                      // race day itself
        assertFalse(plan(RacePlanner.raceWeekEnd(race)).maintenance) // the Sunday after: still race week
        assertTrue(plan(RacePlanner.raceWeekEnd(race).plusDays(1)).maintenance)
        assertTrue(plan(wedAfter).maintenance)
    }

    @Test fun theWeekHasTwoStrengthAndTwoEasySessionsAndNothingHard() {
        val w = assertNotNull(RacePlanner.thisWeek(plan(), wedAfter))
        val counted = RacePlanner.counted(w)
        assertEquals(RacePhase.MAINTAIN, w.phase)
        assertEquals(2, counted.count { it.kind == SessionKind.STRENGTH })
        assertEquals(2, counted.count { it.kind == SessionKind.EASY })
        assertEquals(4, counted.size)
        assertTrue(counted.all { it.hardMinutes == 0 })
        assertTrue(counted.none { it.kind == SessionKind.KEY || it.kind == SessionKind.SIM || it.isRaceDay })
        assertEquals(1.0, w.easyShare) // no hard minutes at all
    }

    @Test fun nothingHyroxSpecificAndNoRaceCountdownThings() {
        val w = assertNotNull(RacePlanner.thisWeek(plan(), wedAfter))
        val text = w.sessions.flatMap { it.detail + it.title }.joinToString(" ").lowercase()
        assertFalse("finisher" in text, text)
        assertFalse("simulation" in text, text)
        assertTrue(w.sessions.none { it.launch.sim != null })
    }

    @Test fun sessionsStayInTheWeekOffRestDaysAndStrengthIsNeverBackToBack() {
        val p = plan()
        val w = assertNotNull(RacePlanner.thisWeek(p, wedAfter))
        val rest = setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY) // the default rest days
        for (s in RacePlanner.counted(w)) {
            assertTrue(s.date in w.start..w.end, "${s.date} outside ${w.start}..${w.end}")
            assertTrue(s.date.dayOfWeek !in rest, "${s.title} on a rest day")
        }
        val strengthDays = RacePlanner.counted(w).filter { it.kind == SessionKind.STRENGTH }.map { it.date }.sorted()
        assertTrue(strengthDays.zipWithNext().all { (a, b) -> b.toEpochDay() - a.toEpochDay() >= 2 })
    }

    @Test fun weeklyMinutesFollowRecentLoggingInsideTheLimitsAndTheCap() {
        // Nothing logged: the default, inside the limits.
        val none = plan().weeks.first().plannedMinutes
        assertTrue(none in RacePlanner.MAINTENANCE_MIN..RacePlanner.MAINTENANCE_MAX, "$none")
        // A heavy recent month is capped at the maximum, and by a lower user cap.
        val heavy = (1..12).map { walk(wedAfter.minusDays(it * 2L), 90) }
        assertTrue(plan(records = heavy).weeks.first().plannedMinutes <= RacePlanner.MAINTENANCE_MAX)
        assertTrue(plan(c = cfg.copy(maxWeeklyMinutes = 150), records = heavy).weeks.first().plannedMinutes <= 150)
    }

    @Test fun fiveSessionsAddsALongEasyOne() {
        val w = assertNotNull(RacePlanner.thisWeek(plan(c = cfg.copy(sessionsPerWeek = 5)), wedAfter))
        assertEquals(1, RacePlanner.counted(w).count { it.kind == SessionKind.LONG_EASY })
        assertEquals(2, RacePlanner.counted(w).count { it.kind == SessionKind.STRENGTH })
    }

    @Test fun rollsForwardEightWeeksFromTheCurrentOne() {
        val p = plan()
        assertEquals(RacePlanner.MAINTENANCE_WEEKS, p.weeks.size)
        assertEquals(wedAfter.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), p.start)
        assertEquals(p.weeks.first(), RacePlanner.thisWeek(p, wedAfter))
        assertNull(p.leadIn)
    }

    @Test fun theNextUpLineAndTheGoodWeekComeFromTheMaintenancePlan() {
        val p = profile()
        val plan = assertNotNull(Coach.racePlan(p, emptyList(), wedAfter))
        val next = assertNotNull(Coach.nextPlanned(plan, emptyList(), wedAfter))
        val text = assertNotNull(Coach.nextUpText(plan, emptyList(), p, wedAfter))
        assertTrue(next.title in text, text)
        assertFalse("race plan" in text, text)
        val g = Coach.goodWeek(plan, emptyList(), p, wedAfter)
        assertTrue(g.fromPlan)
        assertEquals(RacePlanner.counted(assertNotNull(RacePlanner.thisWeek(plan, wedAfter))).size, g.total)
    }

    @Test fun aSkippedMaintenanceSessionIsHandledLikeAnyOther() {
        val p = profile()
        val plan = assertNotNull(Coach.racePlan(p, emptyList(), wedAfter))
        val s = RacePlanner.counted(assertNotNull(RacePlanner.thisWeek(plan, wedAfter))).first { it.date >= wedAfter }
        val skipped = RacePlanner.skip(p, s, SkipReason.NO_TIME, wedAfter)
        val after = assertNotNull(Coach.racePlan(skipped, emptyList(), wedAfter))
        val w = assertNotNull(RacePlanner.thisWeek(after, wedAfter))
        assertTrue(w.sessions.any { it.skipReason != null } || RacePlanner.counted(w).none { it.title == s.title && it.date == s.date })
    }
}
