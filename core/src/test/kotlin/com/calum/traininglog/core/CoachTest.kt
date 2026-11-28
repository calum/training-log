package com.calum.traininglog.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Every surface that says "what next" gets the same answer, from the race plan when it is on. */
class CoachTest {
    private val today = LocalDate.of(2026, 11, 19) // Thursday, inside the kick-off
    private val cfg = RacePlanSettings(sessionsPerWeek = 4, maxWeeklyMinutes = 300)
    private fun profile(planOn: Boolean = true) =
        Profile(settings = UserSettings(racePlan = cfg.copy(enabled = planOn), reminderEnabled = true))

    @Test fun nextUpNamesTheRacePlansSessionNeverTheWeeklyPlannersGuess() {
        val p = profile()
        val plan = assertNotNull(Coach.racePlan(p, emptyList(), today))
        val next = assertNotNull(Coach.nextPlanned(plan, emptyList(), today))
        val text = assertNotNull(Coach.nextUpText(emptyList(), p, today))
        assertTrue(next.title in text, text)
        assertTrue("${next.minutes} min" in text, text)
        assertTrue("Treadmill day" !in text, text)
    }

    @Test fun withoutARacePlanTheWeeklyTextIsUsedAsBefore() {
        val p = profile(planOn = false)
        assertEquals(null, Coach.racePlan(p, emptyList(), today))
        val view = WeekPlanner.plan(emptyList(), p, today, p.settings)
        assertEquals(NextUp.describe(emptyList(), view, today), Coach.nextUpText(emptyList(), p, today))
    }

    @Test fun theReminderUsesTheSameAnswerAndNamesNoActivityOfItsOwn() {
        val p = profile()
        val plan = assertNotNull(Coach.racePlan(p, emptyList(), today))
        val next = assertNotNull(Coach.nextPlanned(plan, emptyList(), today))
        val text = assertNotNull(Reminder.nudgeText(emptyList(), p, today, 0))
        assertTrue(Reminder.planPhrases.any { text.startsWith(it) }, text)
        assertTrue(next.title in text, text)
        assertTrue(Reminder.planPhrases.none { "treadmill" in it.lowercase() || "walk" in it.lowercase() })
    }

    @Test fun theReminderKeepsItsOldWordingWhenThereIsNoRacePlan() {
        val p = profile(planOn = false)
        val text = assertNotNull(Reminder.nudgeText(emptyList(), p, today, 0))
        assertTrue(Reminder.phrases.any { text.startsWith(it) }, text)
    }

    // ---- the good week follows the plan ----

    private val sun = LocalDate.of(2026, 11, 22)
    private val kickoffRecords: (RacePlan) -> List<SessionRecord> = { plan ->
        val w = assertNotNull(RacePlanner.thisWeek(plan, today))
        RacePlanner.counted(w).mapIndexed { i, s ->
            val type = if (s.kind == SessionKind.STRENGTH) SessionType.GYM else SessionType.TREADMILL
            SessionRecord("k$i", minOf(s.date, sun.minusDays(1)), type, s.minutes, s.minutes, Feeling.GOOD)
        }
    }

    @Test fun whileThePlanIsOnTheGoodWeekIsThePlansSessionsNotGym2Treadmill3() {
        val p = profile()
        val plan = assertNotNull(Coach.racePlan(p, emptyList(), today))
        val total = assertNotNull(Coach.weekProgress(plan, emptyList(), sun)).total
        assertTrue(total > 0)
        val none = Coach.goodWeek(plan, emptyList(), p, sun)
        assertTrue(none.fromPlan)
        assertEquals(0, none.done)
        assertEquals(total, none.total)
        assertEquals(0.0, none.progress)

        val all = Coach.goodWeek(plan, kickoffRecords(plan), p, sun)
        assertEquals(total, all.done)
        assertEquals(1.0, all.progress)
        assertTrue(all.reached)
        assertEquals(0.0, all.bonus) // nothing beyond the plan yet
    }

    @Test fun extraSessionsBeyondThePlanAreABonusNeverARequirement() {
        val p = profile()
        val plan = assertNotNull(Coach.racePlan(p, emptyList(), today))
        val extra = SessionRecord("x", sun.minusDays(2), SessionType.CORE, 20, 20, Feeling.GOOD) // a mat session: not one of the planned ones
        val g = Coach.goodWeek(plan, kickoffRecords(plan) + extra, p, sun)
        assertEquals(1.0, g.progress)
        assertTrue(g.bonus > 0.0)
        // Knee care is an add-on, never counted as a session at all.
        val knee = extra.copy(id = "kc", dayLabel = KneeCareRecord.DAY_LABEL)
        assertEquals(0.0, Coach.goodWeek(plan, kickoffRecords(plan) + knee, p, sun).bonus)
    }

    @Test fun withoutARacePlanTheGoodWeekIsStillTheWeeklyTargets() {
        val p = profile(planOn = false)
        val g = Coach.goodWeek(null, emptyList(), p, sun)
        val view = WeekPlanner.plan(emptyList(), p, sun, p.settings)
        assertEquals(false, g.fromPlan)
        assertEquals(view.goodWeekTotal, g.total)
        assertEquals(view.goodWeekDone, g.done)
    }

    @Test fun theHeadlineWordingIsTheSameForBothSources() {
        assertEquals("2 of 5 done, 5 open days left", GoodWeek.headline(2, 5, 5))
        assertEquals("1 of 2 done, 1 open day left", GoodWeek(1, 2, 0.5, 0.0, true).headline(1))
        assertTrue(GoodWeek.headline(2, 2, 3).contains("bonus"))
        assertEquals("1 of 2 done. Rest up, fresh week soon.", GoodWeek.headline(1, 2, 0))
    }
}
