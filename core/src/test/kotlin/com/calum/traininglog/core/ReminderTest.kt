package com.calum.traininglog.core

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReminderTest {
    private val wed = LocalDate.of(2026, 11, 4) // Wednesday
    private fun profile(f: (UserSettings) -> UserSettings = { it }) =
        Profile(settings = f(UserSettings(reminderEnabled = true)))
    private fun rec(date: LocalDate, type: SessionType = SessionType.TREADMILL, feeling: Feeling = Feeling.GOOD, id: String = "r$date$type") =
        SessionRecord(id, date, type, 30, 30, feeling)

    @Test fun offByDefault() {
        assertFalse(UserSettings().reminderEnabled)
        assertFalse(Reminder.shouldNudge(emptyList(), Profile(), wed, 0))
    }

    @Test fun oldJsonDecodes() {
        val s = kotlinx.serialization.json.Json.decodeFromString<UserSettings>("""{"gymTarget":2}""")
        assertFalse(s.reminderEnabled)
        assertEquals(20, s.reminderHour)
        assertEquals(7, s.reminderDays.size)
        assertEquals(3, s.reminderMaxPerWeek)
    }

    @Test fun nudgesOnAPlainDay() = assertTrue(Reminder.shouldNudge(emptyList(), profile(), wed, 0))

    @Test fun notOnRestDay() =
        assertFalse(Reminder.shouldNudge(emptyList(), profile().copy(restWeekdays = setOf(DayOfWeek.WEDNESDAY)), wed, 0))

    @Test fun restDayOverriddenByTrainAnywayStillNudges() =
        assertTrue(Reminder.shouldNudge(emptyList(), profile().copy(restWeekdays = setOf(DayOfWeek.WEDNESDAY), workoutOverrides = setOf(wed)), wed, 0))

    @Test fun notWhenAlreadyLoggedToday() =
        assertFalse(Reminder.shouldNudge(listOf(rec(wed)), profile(), wed, 0))

    @Test fun notWhenGoodWeekMet() {
        val recs = listOf(
            rec(wed.minusDays(2), SessionType.GYM), rec(wed.minusDays(1), SessionType.GYM),
            rec(wed.minusDays(2), SessionType.TREADMILL, id = "w"), rec(wed.minusDays(1), SessionType.TREADMILL, id = "x"), rec(wed.minusDays(2), SessionType.TREADMILL, id = "y"),
        )
        assertFalse(Reminder.shouldNudge(recs, profile(), wed, 0))
        // A bigger target means the week is not met yet.
        assertTrue(Reminder.shouldNudge(recs, profile { it.copy(gymTarget = 3) }, wed, 0))
    }

    @Test fun notAfterRoughOrIllInLastTwoDays() {
        assertFalse(Reminder.shouldNudge(listOf(rec(wed.minusDays(1), feeling = Feeling.ROUGH)), profile(), wed, 0))
        assertFalse(Reminder.shouldNudge(listOf(rec(wed.minusDays(2), feeling = Feeling.ILL)), profile(), wed, 0))
        assertTrue(Reminder.shouldNudge(listOf(rec(wed.minusDays(3), feeling = Feeling.ILL)), profile(), wed, 0))
        // Feeling better since: the latest log wins.
        assertTrue(Reminder.shouldNudge(listOf(rec(wed.minusDays(2), feeling = Feeling.ROUGH), rec(wed.minusDays(1), feeling = Feeling.GOOD)), profile(), wed, 0))
    }

    @Test fun maxPerWeek() {
        assertTrue(Reminder.shouldNudge(emptyList(), profile(), wed, 2))
        assertFalse(Reminder.shouldNudge(emptyList(), profile(), wed, 3))
        assertFalse(Reminder.shouldNudge(emptyList(), profile { it.copy(reminderMaxPerWeek = 1) }, wed, 1))
    }

    @Test fun onlyChosenDays() {
        val p = profile { it.copy(reminderDays = setOf(DayOfWeek.MONDAY)) }
        assertFalse(Reminder.shouldNudge(emptyList(), p, wed, 0))
        assertTrue(Reminder.shouldNudge(emptyList(), p, wed.minusDays(2), 0))
    }

    @Test fun textIsDeterministicVariedAndGuiltFree() {
        val texts = (0L until 16).map { Reminder.phrase(wed.plusDays(it)) }
        assertEquals(8, texts.toSet().size)
        assertEquals(Reminder.phrase(wed), Reminder.phrase(wed))
        val banned = listOf("miss", "streak", "behind", "lost", "break", "should", "skipped", "haven't")
        Reminder.phrases.forEach { p -> banned.forEach { assertFalse(p.lowercase().contains(it), "$p / $it") } }
        val t = Reminder.nudgeText(emptyList(), profile(), wed, 0)
        assertNotNull(t)
        assertTrue(t.startsWith(Reminder.phrase(wed)))
        assertNull(Reminder.nudgeText(listOf(rec(wed)), profile(), wed, 0))
    }
}
