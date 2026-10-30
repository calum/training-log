package com.calum.traininglog.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectionTest {
    // Wednesday
    private val today = LocalDate.of(2026, 11, 4)
    private val ids = listOf("squat", "deadlift", "rdl", "calf")

    private fun gym(date: LocalDate, label: String, weights: Map<String, Double>) = SessionRecord(
        id = "g$date", date = date, type = SessionType.GYM, minutesAvailable = 60, minutesActual = 50,
        feeling = Feeling.GOOD, dayLabel = label,
        exercises = weights.map { (id, kg) ->
            val ex = ExerciseLibrary.get(id)
            ExerciseLog(id, List(ex.sets) { LoggedSet(kg, ex.repsLow, ex.repsLow, rir = 2) })
        },
    )

    private fun treadmill(date: LocalDate, minutes: Int) = SessionRecord(
        id = "t$date", date = date, type = SessionType.TREADMILL, minutesAvailable = 60, minutesActual = minutes,
        feeling = Feeling.GOOD,
    )

    private val recentHistory = listOf(
        gym(today.minusDays(9), "B", mapOf("deadlift" to 100.0, "squat" to 85.0, "calf" to 60.0)),
        gym(today.minusDays(12), "A", mapOf("squat" to 82.5, "rdl" to 70.0, "calf" to 60.0)),
    )

    private fun LiftForecast.w(s: Scenario) = scenarios.getValue(s).weeks.map { it.weightKg }

    @Test fun emptyHistoryUsesStartWeights() {
        val f = Projection.forecast(emptyList(), today)
        for (id in ids) {
            val lf = f.lift(id)!!
            assertEquals(ExerciseLibrary.get(id).startKg, lf.baselineKg)
            assertNull(lf.lastLoggedKg)
            assertFalse(lf.restartApplied)
            for (s in Scenario.values()) assertEquals(ExerciseLibrary.get(id).startKg, lf.scenarios.getValue(s).weeks.first().weightKg)
        }
    }

    @Test fun shapeIsWeekZeroPlusHorizon() {
        val f = Projection.forecast(recentHistory, today, weeks = 8)
        assertEquals(9, f.lift("squat")!!.scenarios.getValue(Scenario.EXPECTED).weeks.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8), f.lift("squat")!!.scenarios.getValue(Scenario.BEST).weeks.map { it.weekOffset })
        assertEquals(8, f.treadmill.weeks.size)
    }

    @Test fun monotoneAndOrderedAcrossScenarios() {
        for (history in listOf(emptyList(), recentHistory)) {
            for (gymPerWeek in 1..3) {
                val f = Projection.forecast(history, today, weeks = 16, gymPerWeek = gymPerWeek)
                for (lf in f.lifts) {
                    for (s in Scenario.values()) {
                        val w = lf.w(s)
                        assertTrue(w.zipWithNext().all { (a, b) -> b >= a }, "${lf.exerciseId} $s not monotone: $w")
                    }
                    val c = lf.w(Scenario.CONSERVATIVE)
                    val e = lf.w(Scenario.EXPECTED)
                    val b = lf.w(Scenario.BEST)
                    for (i in c.indices) {
                        assertTrue(b[i] >= e[i] && e[i] >= c[i], "${lf.exerciseId} week $i: $c $e $b")
                    }
                }
            }
        }
    }

    @Test fun bestCaseSquatAddsIncrementPerExposureOutsideDeload() {
        val f = Projection.forecast(emptyList(), today, weeks = 5, gymPerWeek = 2)
        val best = f.lift("squat")!!.scenarios.getValue(Scenario.BEST).weeks
        // Two sessions a week, both containing squat, +2.5 kg each: +5 kg per week.
        assertEquals(listOf(80.0, 85.0, 90.0, 95.0, 100.0, 105.0), best.map { it.weightKg })
        // Deadlift only on day B: one session a week, +5 kg.
        val dl = f.lift("deadlift")!!.scenarios.getValue(Scenario.BEST).weeks.map { it.weightKg }
        assertEquals(listOf(100.0, 105.0, 110.0, 115.0, 120.0, 125.0), dl)
    }

    @Test fun deloadWeeksDoNotRaiseWeights() {
        val f = Projection.forecast(recentHistory, today, weeks = 14, gymPerWeek = 3)
        for (lf in f.lifts) for (s in Scenario.values()) {
            val pts = lf.scenarios.getValue(s).weeks
            for (p in pts.filter { it.deload }) {
                assertEquals(0, p.weekOffset % 6)
                assertEquals(pts[p.weekOffset - 1].weightKg, p.weightKg, "${lf.exerciseId} $s week ${p.weekOffset}")
                assertEquals(Progression.deloadSets(ExerciseLibrary.get(lf.exerciseId).sets), p.sets)
            }
            assertEquals(listOf(6, 12), pts.filter { it.deload }.map { it.weekOffset })
        }
    }

    @Test fun longGapTriggersRestart() {
        val old = listOf(gym(today.minusDays(30), "A", mapOf("squat" to 100.0, "rdl" to 80.0, "calf" to 60.0)))
        val f = Projection.forecast(old, today)
        val sq = f.lift("squat")!!
        assertTrue(sq.restartApplied)
        assertEquals(100.0, sq.lastLoggedKg)
        assertEquals(90.0, sq.baselineKg)
        assertEquals(72.5, f.lift("rdl")!!.baselineKg) // 72
        assertFalse(Projection.forecast(recentHistory, today).lift("squat")!!.restartApplied)
        // Not-restarting case builds on the last weight.
        assertEquals(87.5, Projection.forecast(recentHistory, today).lift("squat")!!.baselineKg) // 85 + 2.5
    }

    @Test fun headlineTargetEta() {
        val target = HeadlineTarget("squat", 100.0)
        val f = Projection.forecast(emptyList(), today, weeks = 12, headlineTarget = target)
        val h = assertNotNull(f.headline)
        assertEquals(target, h.target)
        val best = assertNotNull(h.weeksToTarget.getValue(Scenario.BEST))
        val exp = assertNotNull(h.weeksToTarget.getValue(Scenario.EXPECTED))
        val cons = h.weeksToTarget.getValue(Scenario.CONSERVATIVE)
        assertEquals(4, best) // 80 + 5/week
        assertTrue(exp >= best)
        assertTrue(cons == null || cons >= exp)
        // The ETA week really is the first at/above target.
        val pts = f.lift("squat")!!.scenarios.getValue(Scenario.EXPECTED).weeks
        assertTrue(pts[exp].weightKg >= 100.0)
        assertTrue(pts[exp - 1].weightKg < 100.0)
        assertEquals(exp, f.lift("squat")!!.scenarios.getValue(Scenario.EXPECTED).weeksToTarget)
        assertNull(f.lift("deadlift")!!.scenarios.getValue(Scenario.BEST).weeksToTarget)
    }

    @Test fun headlineBeyondHorizonIsNull() {
        val f = Projection.forecast(emptyList(), today, weeks = 4, headlineTarget = HeadlineTarget("squat", 150.0))
        assertTrue(f.headline!!.weeksToTarget.values.all { it == null })
        assertTrue(f.milestones.any { it.text.contains("past this window") })
    }

    @Test fun headlineAlreadyReachedIsWeekZero() {
        val f = Projection.forecast(recentHistory, today, headlineTarget = HeadlineTarget("squat", 80.0))
        assertEquals(0, f.headline!!.weeksToTarget.getValue(Scenario.CONSERVATIVE))
    }

    @Test fun outputsAreMultiplesOfTwoPointFive() {
        val f = Projection.forecast(recentHistory, today, weeks = 12, gymPerWeek = 3)
        for (lf in f.lifts) for (s in Scenario.values()) for (p in lf.scenarios.getValue(s).weeks) {
            assertEquals(p.weightKg, roundTo(p.weightKg, 2.5))
        }
    }

    @Test fun deterministic() {
        assertEquals(Projection.forecast(recentHistory, today), Projection.forecast(recentHistory, today))
    }

    @Test fun treadmillDefaultsAndAverages() {
        val none = Projection.forecast(emptyList(), today, treadmillPerWeek = 3).treadmill
        assertEquals(40, none.minutesPerSession)
        assertEquals(120, none.weeklyMinutes)
        assertEquals(0, none.basedOnSessions)
        assertEquals(listOf(120, 240, 360), none.weeks.take(3).map { it.cumulativeMinutes })

        val recs = listOf(treadmill(today.minusDays(2), 30), treadmill(today.minusDays(4), 50), treadmill(today.minusDays(6), 40))
        val t = Projection.forecast(recs, today, treadmillPerWeek = 4).treadmill
        assertEquals(40, t.minutesPerSession)
        assertEquals(160, t.weeklyMinutes)
        assertEquals(3, t.basedOnSessions)

        // Old sessions are ignored.
        val old = Projection.forecast(listOf(treadmill(today.minusDays(200), 90)), today).treadmill
        assertEquals(40, old.minutesPerSession)
    }

    @Test fun weekOutlookCounts() {
        // today is Wed 2026-11-04; week starts Mon 2026-11-02.
        val recs = listOf(
            gym(LocalDate.of(2026, 11, 2), "A", mapOf("squat" to 82.5, "rdl" to 70.0, "calf" to 60.0)),
            treadmill(LocalDate.of(2026, 11, 3), 40),
            treadmill(LocalDate.of(2026, 11, 4), 40),
            treadmill(LocalDate.of(2026, 10, 30), 40), // last week
            gym(LocalDate.of(2026, 10, 28), "B", mapOf("deadlift" to 100.0, "squat" to 80.0, "calf" to 60.0)),
        )
        val o = Projection.forecast(recs, today).weekOutlook
        assertEquals(LocalDate.of(2026, 11, 2), o.weekStart)
        assertEquals(1, o.gymDone)
        assertEquals(2, o.gymTarget)
        assertEquals(3, o.gymStretch)
        assertEquals(2, o.treadmillDone)
        assertEquals(3, o.treadmillTarget)
        assertEquals(4, o.treadmillStretch)
        assertFalse(o.targetMet)
        assertTrue(o.summary.contains("1 of 2 gym"))
    }

    @Test fun weekOutlookTargetMetAndNoGuilt() {
        val recs = listOf(
            gym(LocalDate.of(2026, 11, 2), "A", mapOf("squat" to 82.5, "rdl" to 70.0, "calf" to 60.0)),
            gym(LocalDate.of(2026, 11, 3), "B", mapOf("deadlift" to 100.0, "squat" to 85.0, "calf" to 60.0)),
            treadmill(LocalDate.of(2026, 11, 2), 40), treadmill(LocalDate.of(2026, 11, 3), 40), treadmill(LocalDate.of(2026, 11, 4), 40),
        )
        val f = Projection.forecast(recs, today)
        assertTrue(f.weekOutlook.targetMet)
        assertTrue(f.weekOutlook.summary.contains("bonus"))
        val banned = listOf("miss", "behind", "fail", "lazy", "guilt", "should have", "only ")
        val text = (f.milestones.map { it.text } + f.weekOutlook.summary + f.weekOutlook.liftLines).joinToString(" ").lowercase()
        banned.forEach { assertFalse(text.contains(it), "found '$it' in: $text") }
    }

    @Test fun outlookSaysWhatCompletingTheWeekDoes() {
        val f = Projection.forecast(emptyList(), today)
        val sq = f.weekOutlook.lifts.first { it.exerciseId == "squat" }
        assertEquals(80.0, sq.fromKg)
        assertEquals(f.lift("squat")!!.scenarios.getValue(Scenario.EXPECTED).weeks[1].weightKg, sq.toKg)
        assertTrue(sq.toKg > sq.fromKg)
        assertTrue(f.weekOutlook.liftLines.any { it.startsWith("Hit 2 gym sessions this week and squat moves from 80 to about ") })
    }

    @Test fun remainingSessionsOnlyCountTowardCurrentWeek() {
        // Both gym sessions already logged this week: week 1 adds no new exposures.
        val recs = listOf(
            gym(LocalDate.of(2026, 11, 2), "A", mapOf("squat" to 82.5, "rdl" to 70.0, "calf" to 60.0)),
            gym(LocalDate.of(2026, 11, 3), "B", mapOf("deadlift" to 100.0, "squat" to 85.0, "calf" to 60.0)),
        )
        val sq = Projection.forecast(recs, today).lift("squat")!!.scenarios.getValue(Scenario.BEST).weeks
        assertEquals(sq[0].weightKg, sq[1].weightKg)
        assertEquals(87.5, sq[0].weightKg)
        assertEquals(92.5, sq[2].weightKg)
    }

    @Test fun squatGetsMoreExposuresThanDeadlift() {
        val f = Projection.forecast(emptyList(), today, gymPerWeek = 2)
        assertEquals(2.0, f.lift("squat")!!.exposuresPerWeek)
        assertEquals(1.0, f.lift("deadlift")!!.exposuresPerWeek)
        assertEquals(1.0, f.lift("rdl")!!.exposuresPerWeek)
        assertEquals(2.0, f.lift("calf")!!.exposuresPerWeek)
    }

    @Test fun milestonesAreReadableEstimates() {
        val f = Projection.forecast(emptyList(), today, headlineTarget = HeadlineTarget("squat", 100.0))
        assertTrue(f.milestones.isNotEmpty())
        assertEquals(f.milestones.map { it.weekOffset }, f.milestones.map { it.weekOffset }.sorted())
        val w4 = f.milestones.first { it.text.startsWith("Week 4: squat") }
        assertTrue(w4.text.contains("about") && w4.text.endsWith("(expected)"))
        assertTrue(f.milestones.all { it.weekOffset in 0..12 })
    }

    @Test fun zeroGymSessionsKeepsWeightsFlat() {
        val f = Projection.forecast(recentHistory, today, gymPerWeek = 0)
        for (lf in f.lifts) for (s in Scenario.values()) assertEquals(1, lf.w(s).toSet().size)
    }
}
