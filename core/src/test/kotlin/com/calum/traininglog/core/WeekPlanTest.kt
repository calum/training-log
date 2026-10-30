package com.calum.traininglog.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeekPlanTest {
    // 2026-10-26 is a Monday.
    private val mon = LocalDate.of(2026, 10, 26)
    private fun d(n: Long) = mon.plusDays(n)
    private fun rec(date: LocalDate, type: SessionType, id: String = "$date-$type") =
        SessionRecord(id, date, type, 45, 45, Feeling.GOOD)
    private fun status(v: WeekView, date: LocalDate) = v.days.first { it.date == date }.status

    @Test fun restWeekdayAndOneOffDate() {
        var p = RestDays.toggleWeekday(Profile(), DayOfWeek.WEDNESDAY)
        assertTrue(RestDays.isRestDay(p, d(2)))
        assertFalse(RestDays.isRestDay(p, d(3)))
        p = RestDays.toggleDate(p, d(4))
        assertTrue(RestDays.isRestDay(p, d(4)))
        p = RestDays.toggleDate(p, d(4))
        assertFalse(RestDays.isRestDay(p, d(4)))
        p = RestDays.toggleWeekday(p, DayOfWeek.WEDNESDAY)
        assertFalse(RestDays.isRestDay(p, d(2)))
    }

    @Test fun overrideMakesRestDayWorkable() {
        val p = RestDays.trainAnyway(RestDays.toggleWeekday(Profile(), DayOfWeek.SUNDAY), d(6))
        assertFalse(RestDays.isRestDay(p, d(6)))
        assertTrue(RestDays.isRestDay(p, d(6).plusDays(7)))
        assertEquals(DayStatus.TODAY, status(WeekPlanner.plan(emptyList(), p, d(6)), d(6)))
    }

    @Test fun restButTrainedIsCelebrated() {
        val p = RestDays.trainAnyway(RestDays.toggleWeekday(Profile(), DayOfWeek.TUESDAY), d(1))
        val v = WeekPlanner.plan(listOf(rec(d(1), SessionType.GYM)), p, d(3))
        assertEquals(DayStatus.REST_BUT_TRAINED, status(v, d(1)))
        val v2 = WeekPlanner.plan(listOf(rec(d(1), SessionType.GYM)), RestDays.toggleWeekday(Profile(), DayOfWeek.TUESDAY), d(3))
        assertEquals(DayStatus.REST_BUT_TRAINED, status(v2, d(1)))
        assertEquals(1, v2.gymDone)
    }

    @Test fun pastEmptyIsNeutralAndHeadlineNeverNegative() {
        val v = WeekPlanner.plan(emptyList(), Profile(), d(5))
        assertEquals(DayStatus.PAST_EMPTY, status(v, d(0)))
        assertEquals(DayStatus.TODAY, status(v, d(5)))
        assertEquals(DayStatus.OPEN, status(v, d(6)))
        val banned = listOf("miss", "behind", "fail", "skipped", "lost")
        for (day in 0L..6L) for (n in 0..6) {
            val recs = (0 until n).map { rec(d(0), SessionType.TREADMILL, "r$it") }
            val h = WeekPlanner.plan(recs, Profile(), d(day)).headline().lowercase()
            assertTrue(banned.none { it in h }, h)
        }
    }

    @Test fun headlineWording() {
        val v = WeekPlanner.plan(listOf(rec(d(0), SessionType.GYM), rec(d(1), SessionType.TREADMILL)), Profile(), d(2))
        assertEquals("2 of 5 done, 5 open days left", v.headline())
    }

    @Test fun reachabilityStates() {
        assertEquals(WeekOutlook.ON_TRACK, WeekPlanner.plan(emptyList(), Profile(), d(0)).outlook)
        // Saturday with everything but Saturday as rest: 5 needed, 1 open + 1 rest left -> not reachable.
        val allRest = Profile(restWeekdays = DayOfWeek.values().toSet() - DayOfWeek.SATURDAY)
        assertEquals(WeekOutlook.BONUS_WEEK, WeekPlanner.plan(emptyList(), allRest, d(5)).outlook)
        val base = listOf(rec(d(0), SessionType.GYM), rec(d(1), SessionType.GYM), rec(d(2), SessionType.TREADMILL))
        // 2 treadmill left, only Saturday open, Sunday is rest -> doable with a push.
        val push = WeekPlanner.plan(base, Profile(restWeekdays = setOf(DayOfWeek.SUNDAY)), d(5))
        assertEquals(2, push.treadmillLeft)
        assertEquals(1, push.openDaysLeft)
        assertEquals(WeekOutlook.DOABLE_WITH_PUSH, push.outlook)
        // 1 treadmill left, one open day -> on track.
        val track = WeekPlanner.plan(base + rec(d(3), SessionType.TREADMILL), Profile(), d(5))
        assertEquals(WeekOutlook.ON_TRACK, track.outlook)
        // Target met.
        val met = WeekPlanner.plan(base + rec(d(3), SessionType.TREADMILL) + rec(d(4), SessionType.TREADMILL), Profile(), d(5))
        assertEquals(WeekOutlook.BONUS_WEEK, met.outlook)
        assertEquals(0, met.gymLeft)
        assertEquals(1, met.gymStretchLeft)
        assertEquals(0, met.treadmillLeft)
        assertEquals(1, met.treadmillStretchLeft)
        assertTrue(met.headline().contains("bonus"))
    }

    @Test fun gymSpacedAtLeastTwoDaysAndNoSuggestionOnRest() {
        val p = Profile(restWeekdays = setOf(DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY))
        for (day in 0L..6L) for (gymFirst in listOf(false, true)) {
            val recs = if (gymFirst && day > 0) listOf(rec(d(day - 1), SessionType.GYM)) else emptyList()
            val v = WeekPlanner.plan(recs, p, d(day))
            val gym = v.days.filter { it.suggested == SessionType.GYM || SessionType.GYM in it.done }.map { it.date }
            for (a in gym) for (b in gym) if (a != b) assertTrue(abs(a.toEpochDay() - b.toEpochDay()) >= 2, "$gym")
            v.days.filter { it.status == DayStatus.REST }.forEach { assertNull(it.suggested) }
            v.days.filter { it.date < d(day) }.forEach { assertNull(it.suggested) }
        }
        val full = WeekPlanner.plan(emptyList(), p, d(0))
        assertEquals(2, full.days.count { it.suggested == SessionType.GYM })
        assertEquals(3, full.days.count { it.suggested == SessionType.TREADMILL })
    }

    @Test fun jsonRoundTripAndOldJson() {
        val json = Json { ignoreUnknownKeys = true }
        val p = Profile(
            lastDeload = d(0),
            restWeekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
            restDates = setOf(d(3)),
            workoutOverrides = setOf(d(4)),
        )
        val s = json.encodeToString(p)
        assertTrue(s.contains("\"MONDAY\""))
        assertEquals(p, json.decodeFromString<Profile>(s))
        val old = json.decodeFromString<Profile>("""{"lastDeload":"2026-10-01"}""")
        assertEquals(emptySet(), old.restWeekdays)
        assertEquals(emptySet(), old.restDates)
        assertEquals(emptySet(), old.workoutOverrides)
        assertEquals(Profile(), json.decodeFromString<Profile>("{}"))
    }

    @Test fun recommenderRestDayPrefix() {
        val ok = Recommender.recommend(RecommendationInput(45, Feeling.OKAY, restDay = true))
        assertTrue(ok.reason.startsWith("Rest day, but if you have time: "))
        assertNotNull(ok.primary.type)
        val rough = Recommender.recommend(RecommendationInput(45, Feeling.ROUGH, restDay = true))
        assertFalse(rough.reason.startsWith("Rest day"))
        val normal = Recommender.recommend(RecommendationInput(45, Feeling.OKAY))
        assertEquals(normal.primary, ok.primary)
    }
}
