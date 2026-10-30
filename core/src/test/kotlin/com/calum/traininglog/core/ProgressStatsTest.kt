package com.calum.traininglog.core

import java.time.LocalDate
import java.time.temporal.IsoFields
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressStatsTest {
    private var n = 0
    private fun set(kg: Double, reps: Int) = LoggedSet(kg, reps, reps)
    private fun gym(date: LocalDate, vararg ex: Pair<String, List<LoggedSet>>) = SessionRecord(
        "g${n++}", date, SessionType.GYM, 60, 55, Feeling.GOOD, exercises = ex.map { ExerciseLog(it.first, it.second) },
    )
    private fun tread(date: LocalDate, min: Int, style: TreadmillStyle = TreadmillStyle.STEADY) = SessionRecord(
        "t${n++}", date, SessionType.TREADMILL, min, min, Feeling.GOOD, treadmill = TreadmillLog(style, min * 60, min * 60),
    )
    private fun core(date: LocalDate) = SessionRecord("c${n++}", date, SessionType.CORE, 15, 15, Feeling.OKAY)

    private val today = LocalDate.of(2026, 1, 7) // Wednesday, ISO week 2 of 2026

    @Test fun emptyHistory() {
        val d = ProgressStats.build(emptyList(), today, 12)
        assertTrue(d.isEmpty)
        assertEquals(12, d.weekly.size)
        assertTrue(d.weekly.all { it.sessions == 0 })
        assertTrue(d.lifts.isEmpty())
        assertEquals(0.0, d.totals.volumeKg)
        assertTrue(d.prs.isEmpty())
        assertNull(d.firstSessionDate)
    }

    @Test fun weeksAreIsoMondaysAcrossYearBoundary() {
        val d = ProgressStats.build(emptyList(), today, 3)
        assertEquals(listOf(LocalDate.of(2025, 12, 22), LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 5)), d.weekStarts)
        assertEquals(1, LocalDate.of(2026, 1, 1).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
        assertEquals(2026, LocalDate.of(2025, 12, 29).get(IsoFields.WEEK_BASED_YEAR))
    }

    @Test fun sessionsBucketAcrossYearEnd() {
        val recs = listOf(
            gym(LocalDate.of(2025, 12, 28), "squat" to listOf(set(80.0, 5))), // Sunday, week of Dec 22
            gym(LocalDate.of(2025, 12, 29), "squat" to listOf(set(82.5, 5))), // Monday, week of Dec 29
            tread(LocalDate.of(2026, 1, 1), 30),                                // Thursday, same ISO week
            core(LocalDate.of(2026, 1, 4)),                                     // Sunday, same ISO week
            tread(LocalDate.of(2026, 1, 5), 45),                                // Monday, new week
        )
        val d = ProgressStats.build(recs, today, 3)
        assertEquals(1, d.weekly[0].gym)
        assertEquals(0, d.weekly[0].treadmill)
        assertEquals(1, d.weekly[1].gym); assertEquals(1, d.weekly[1].treadmill); assertEquals(1, d.weekly[1].other)
        assertEquals(30, d.weekly[1].treadmillMinutes)
        assertEquals(1, d.weekly[2].treadmill); assertEquals(45, d.weekly[2].treadmillMinutes)
    }

    @Test fun futureRecordsIgnored() {
        val d = ProgressStats.build(listOf(tread(today.plusDays(3), 30)), today, 4)
        assertTrue(d.isEmpty)
    }

    @Test fun carryForwardThroughQuietWeeks() {
        val recs = listOf(
            gym(today.minusWeeks(9), "squat" to listOf(set(80.0, 5), set(85.0, 5))),
            gym(today.minusWeeks(2), "squat" to listOf(set(90.0, 5))),
        )
        val sq = ProgressStats.build(recs, today, 12).lift("squat")!!
        assertEquals(12, sq.topKg.size)
        assertNull(sq.topKg[0]) // before first logged
        assertEquals(85.0, sq.topKg[2]) // week of first session
        assertEquals(85.0, sq.topKg[5]) // quiet weeks carry, never drop to zero
        assertEquals(90.0, sq.topKg[9])
        assertEquals(90.0, sq.topKg[11])
        assertTrue(sq.topKg.filterNotNull().all { it > 0 })
    }

    @Test fun carryForwardSeedsFromBeforeWindow() {
        val recs = listOf(gym(today.minusWeeks(30), "deadlift" to listOf(set(100.0, 3))))
        val dl = ProgressStats.build(recs, today, 12).lift("deadlift")!!
        assertTrue(dl.topKg.all { it == 100.0 })
        assertEquals(100.0, dl.firstKg); assertEquals(100.0, dl.bestEverKg)
    }

    @Test fun changeSinceFirstAndBest() {
        val recs = listOf(
            gym(today.minusWeeks(20), "squat" to listOf(set(75.0, 5))),
            gym(today.minusWeeks(10), "squat" to listOf(set(95.0, 5))),
            gym(today.minusWeeks(1), "squat" to listOf(set(90.0, 5))),
        )
        val sq = ProgressStats.build(recs, today, 26).lift("squat")!!
        assertEquals(75.0, sq.firstKg)
        assertEquals(95.0, sq.bestEverKg)
        assertEquals(90.0, sq.lastKg)
        assertEquals(15.0, sq.changeKg)
        assertEquals(today.minusWeeks(20), sq.firstDate)
        assertEquals(3, sq.sessions)
    }

    @Test fun epleyEstimate() {
        assertEquals(100.0, ProgressStats.e1rm(100.0, 1))
        assertEquals(100.0 * (1 + 5 / 30.0), ProgressStats.e1rm(100.0, 5), 1e-9)
        val recs = listOf(gym(today, "squat" to listOf(set(90.0, 5), set(80.0, 10))))
        val sq = ProgressStats.build(recs, today, 4).lift("squat")!!
        assertEquals(maxOf(90.0 * (1 + 5 / 30.0), 80.0 * (1 + 10 / 30.0)), sq.e1rmKg.last()!!, 1e-9)
    }

    @Test fun upperBodyIncludedCustomListedNotCharted() {
        val recs = listOf(
            gym(today, "lat_pulldown" to listOf(set(45.0, 10)), "custom:cable_face_pull" to listOf(set(20.0, 12)), "pushup" to listOf(set(0.0, 15))),
        )
        val d = ProgressStats.build(recs, today, 12)
        assertNotNull(d.lift("lat_pulldown"))
        assertNull(d.lift("pushup")) // bodyweight only: no weight to chart
        assertTrue(d.lifts.none { ExerciseLibrary.isCustom(it.exerciseId) })
        assertEquals(1, d.customExercises.size)
    }

    @Test fun volumeMaths() {
        val recs = listOf(
            gym(today, "squat" to listOf(set(100.0, 5), set(100.0, 5)), "calf" to listOf(set(60.0, 12))),
            gym(today.minusWeeks(30), "squat" to listOf(set(50.0, 10))),
        )
        val d = ProgressStats.build(recs, today, 12)
        assertEquals(1000.0 + 720.0 + 500.0, d.totals.volumeKg, 1e-9)
        assertEquals(1720.0, d.windowTotals.volumeKg, 1e-9)
        assertEquals(2.22, d.totals.tonnes, 1e-9)
        assertEquals(110, d.totals.minutes)
        assertEquals(2, d.totals.sessions)
    }

    @Test fun treadmillMinutesAndStyles() {
        val recs = listOf(tread(today, 40, TreadmillStyle.PYRAMID), tread(today.minusDays(1), 20, TreadmillStyle.PYRAMID), tread(today.minusDays(2), 30))
        val d = ProgressStats.build(recs, today, 4)
        assertEquals(90, d.windowTotals.treadmillMinutes)
        assertEquals(2, d.stylesTried[TreadmillStyle.PYRAMID])
        assertEquals(1, d.stylesTried[TreadmillStyle.STEADY])
    }

    @Test fun prsReuseRewards() {
        val recs = listOf(
            gym(today.minusWeeks(3), "squat" to listOf(set(80.0, 5))),
            gym(today, "squat" to listOf(set(85.0, 5))),
        )
        assertEquals(Rewards.summary(recs, today).prs.sortedByDescending { it.date }, ProgressStats.build(recs, today).prs)
        assertEquals(1, ProgressStats.build(recs, today).prs.size)
    }
}
