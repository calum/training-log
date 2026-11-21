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
}
