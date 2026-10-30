package com.calum.traininglog.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpperProjectionTest {
    private val today = LocalDate.of(2026, 11, 4) // Wednesday
    private val upperIds = ExerciseLibrary.upper.map { it.id }.toSet()

    private fun gym(date: LocalDate, weights: Map<String, Double>) = SessionRecord(
        id = "g$date", date = date, type = SessionType.GYM, minutesAvailable = 60, minutesActual = 50,
        feeling = Feeling.GOOD,
        exercises = weights.map { (id, kg) ->
            val ex = ExerciseLibrary.get(id)
            ExerciseLog(id, List(ex.sets) { LoggedSet(kg, ex.repsLow, ex.repsLow, rir = 2) })
        },
    )

    private fun upperLifts(f: Forecast) = f.lifts.filter { it.exerciseId in upperIds }
    private fun LiftForecast.w(s: Scenario) = scenarios.getValue(s).weeks.map { it.weightKg }

    @Test fun emptyHistoryUsesLibraryDefaults() {
        val f = Projection.forecast(emptyList(), today)
        for (m in listOf("vertical-pull", "horizontal-pull", "vertical-push")) {
            val ex = ExerciseLibrary.firstFor(m)
            val lf = assertNotNull(f.lift(ex.id))
            assertEquals(ex.startKg, lf.baselineKg)
            assertEquals(ex.startKg, lf.w(Scenario.EXPECTED).first())
        }
        // push-ups are bodyweight (0 kg) and are not forecast
        assertNull(f.lift("pushup"))
    }

    @Test fun usesTheExerciseLastLoggedInTheMovement() {
        val rec = listOf(gym(today.minusDays(5), mapOf("db_row" to 25.0)))
        val f = Projection.forecast(rec, today)
        assertNotNull(f.lift("db_row"))
        assertNull(f.lift("cable_row"))
        assertEquals(25.0, f.lift("db_row")!!.lastLoggedKg)
    }

    @Test fun monotoneAndOrdered() {
        val rec = listOf(gym(today.minusDays(5), mapOf("lat_pulldown" to 45.0, "machine_chest" to 40.0)))
        val f = Projection.forecast(rec, today)
        assertTrue(upperLifts(f).size >= 3)
        for (lf in upperLifts(f)) {
            for (s in Scenario.values()) assertEquals(13, lf.w(s).size)
            for (s in Scenario.values()) assertEquals(lf.w(s).sorted(), lf.w(s), lf.exerciseId)
            val c = lf.w(Scenario.CONSERVATIVE)
            val e = lf.w(Scenario.EXPECTED)
            val b = lf.w(Scenario.BEST)
            for (i in c.indices) assertTrue(c[i] <= e[i] && e[i] <= b[i], "${lf.exerciseId} wk $i")
        }
    }

    @Test fun bestCaseGainsSlowly() {
        val lf = Projection.forecast(emptyList(), today, gymPerWeek = 2).lift("lat_pulldown")!!
        val best = lf.w(Scenario.BEST)
        assertTrue(best.last() > best.first())
        assertTrue(best.last() - best.first() <= 2.5 * 4)
    }

    @Test fun deloadWeeksKeepLoadAndCutSets() {
        val lf = Projection.forecast(emptyList(), today).lift("lat_pulldown")!!
        val pts = lf.scenarios.getValue(Scenario.EXPECTED).weeks
        val w6 = pts.first { it.weekOffset == 6 }
        assertTrue(w6.deload)
        assertEquals(pts.first { it.weekOffset == 5 }.weightKg, w6.weightKg)
        assertEquals(Progression.deloadSets(3), w6.sets)
    }

    @Test fun rotationGivesEachGroupAboutHalfTheSessions() {
        val f = Projection.forecast(emptyList(), today, gymPerWeek = 2)
        for (lf in upperLifts(f)) assertEquals(1.0, lf.exposuresPerWeek, 0.2)
        val three = Projection.forecast(emptyList(), today, gymPerWeek = 3)
        for (lf in upperLifts(three)) assertEquals(1.5, lf.exposuresPerWeek, 0.3)
    }

    @Test fun zeroGymMeansFlat() {
        val f = Projection.forecast(emptyList(), today, gymPerWeek = 0)
        for (lf in upperLifts(f)) for (s in Scenario.values()) assertEquals(1, lf.w(s).toSet().size)
    }

    @Test fun mainLiftsAndWordingUnchanged() {
        val f = Projection.forecast(emptyList(), today)
        assertEquals(listOf("squat", "deadlift", "rdl", "calf"), f.lifts.take(4).map { it.exerciseId })
        assertTrue(f.milestones.any { it.text.startsWith("Week 12: squat about") })
        assertTrue(f.weekOutlook.liftLines.all { l -> listOf("squat", "deadlift", "romanian").any { l.contains(it) } })
    }
}
