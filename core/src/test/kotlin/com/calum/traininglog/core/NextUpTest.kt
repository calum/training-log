package com.calum.traininglog.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextUpTest {
    private val today = LocalDate.of(2026, 11, 4)

    private fun view(suggested: SessionType?) = WeekView(
        listOf(DayPlan(today, DayStatus.TODAY, emptyList(), suggested)), 0, 0, 2, 3, 3, 4, 3, WeekOutlook.ON_TRACK,
    )

    private fun gym(date: LocalDate, vararg ids: String) = SessionRecord(
        id = "g$date", date = date, type = SessionType.GYM, minutesAvailable = 60, minutesActual = 50, feeling = Feeling.GOOD,
        exercises = ids.map { ExerciseLog(it, listOf(LoggedSet(40.0, 8, 8, 2))) },
    )

    private fun tread(date: LocalDate, style: TreadmillStyle, min: Int) = SessionRecord(
        id = "t$date", date = date, type = SessionType.TREADMILL, minutesAvailable = 60, minutesActual = min, feeling = Feeling.GOOD,
        treadmill = TreadmillLog(style, min * 60, min * 60),
    )

    @Test fun noSuggestionIsNull() {
        assertNull(NextUp.describe(emptyList(), view(null), today))
        assertNull(NextUp.describe(emptyList(), WeekView(emptyList(), 0, 0, 0, 0, 0, 0, 0, WeekOutlook.ON_TRACK), today))
    }

    @Test fun freshGymDayIsRdlWithPullPull() {
        val t = NextUp.describe(emptyList(), view(SessionType.GYM), today)!!
        assertTrue(t.startsWith("Squat + RDL day"), t)
        assertTrue("vertical + horizontal pull" in t, t)
    }

    @Test fun gymDayFollowsBuilderChoices() {
        val rec = listOf(gym(today.minusDays(3), "squat", "rdl", "lat_pulldown", "cable_row"))
        val t = NextUp.describe(rec, view(SessionType.GYM), today)!!
        assertTrue(t.startsWith("Squat + Deadlift day"), t)
        assertTrue("pull" !in t.substringAfter("with"), t)
        assertEquals(GymBuilder.nextDayLabel(rec) + " day", t.substringBefore(","))
    }

    @Test fun upperLabels() {
        assertEquals("pull + push", NextUp.upperLabel(listOf("vertical-pull", "horizontal-push")))
        assertEquals("horizontal + vertical push", NextUp.upperLabel(listOf("horizontal-push", "vertical-push")))
    }

    @Test fun treadmillDoesNotRepeatLastStyleAndUsesTypicalLength() {
        val rec = (1..4).map { tread(today.minusDays(it * 2L), TreadmillStyle.PYRAMID, 30) }
        val t = NextUp.describe(rec, view(SessionType.TREADMILL), today)!!
        assertTrue("about 30 min" in t, t)
        assertTrue("pyramid" !in t, t)
    }

    @Test fun treadmillWithNoHistoryUsesDefaultLength() {
        val t = NextUp.describe(emptyList(), view(SessionType.TREADMILL), today)!!
        assertTrue("about ${Projection.DEFAULT_TREADMILL_MIN} min" in t, t)
    }
}
