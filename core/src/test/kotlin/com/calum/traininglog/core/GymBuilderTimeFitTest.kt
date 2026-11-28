package com.calum.traininglog.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Calum's real gym history (Nov 2026): the builder must trim sets and warm-ups before it drops a main lift. */
class GymBuilderTimeFitTest {
    private val today = LocalDate.of(2026, 11, 20) // a Friday

    private fun lifts(kg: Double, sets: Int, reps: Int, target: Int) = List(sets) { LoggedSet(kg, reps, target, rir = 2) }
    private fun gym(day: Int, vararg logs: ExerciseLog) = SessionRecord(
        "h-$day", LocalDate.of(2026, 11, day), SessionType.GYM, 60, 50, Feeling.GOOD, exercises = logs.toList(),
    )
    private fun squat(kg: Double) = ExerciseLog("squat", lifts(kg, 3, 5, 5))
    private fun rdl(kg: Double) = ExerciseLog("rdl", lifts(kg, 2, 8, 8))
    private fun deadlift(kg: Double) = ExerciseLog("deadlift", lifts(kg, 2, 3, 3))
    private fun calf(kg: Double) = ExerciseLog("calf", lifts(kg, 3, 12, 12))
    private fun upper(id: String, kg: Double) = ExerciseLog(id, lifts(kg, 3, 10, 10))

    private val history = listOf(
        gym(1, squat(72.5), rdl(62.5), calf(60.0), upper("lat_pulldown", 40.0), upper("cable_row", 40.0)),
        gym(5, squat(72.5), deadlift(90.0), calf(62.5)),
        gym(12, squat(75.0), rdl(65.0), calf(65.0), upper("machine_chest", 40.0), upper("db_shoulder", 12.5)),
        gym(16, squat(75.0), deadlift(95.0), calf(65.0), upper("lat_pulldown", 42.5), upper("cable_row", 42.5)),
    )

    private fun ids(p: GymPlan) = p.exercises.map { it.exercise.id }
    private fun build(min: Int) = GymBuilder.build(min, history, today)

    private fun sets(p: GymPlan, id: String) = p.exercises.first { it.exercise.id == id }.prescription.sets

    /** Trim priority: the squat keeps 3 sets longest (hinge, then calf, then the squat warm-ups give way first). */
    @Test fun theSquatKeepsThreeSetsWhileTheHingeAndCalfGiveWayFirst() {
        val p20 = build(20); val p25 = build(25); val p30 = build(30)
        assertEquals(2, sets(p25, "rdl")); assertEquals(2, sets(p25, "calf")); assertEquals(3, sets(p25, "squat"))
        // 20 minutes: warm-ups are already trimmed and it still does not fit, so only now does the squat drop to 2 sets (the hinge stays).
        assertEquals(2, sets(p20, "squat")); assertEquals(2, sets(p20, "rdl")); assertEquals(2, sets(p20, "calf"))
        assertTrue(p20.exercises.first().warmups.size < 5)
        assertEquals(5, p25.exercises.first().warmups.size, "at 25 the full warm-up chain still fits")
        // 30 minutes: all three main lifts at their full sets (the RDL is a 2-set lift) plus one upper lift.
        assertEquals(3, sets(p30, "squat")); assertEquals(3, sets(p30, "calf")); assertEquals(2, sets(p30, "rdl"))
        assertEquals(4, ids(p30).size, ids(p30).toString())
    }

    /** 20 to 60 minute windows for his history: more time never takes a lift or a set away. */
    @Test fun windowsFrom20To60AreMonotonicAndSensible() {
        val plans = listOf(20, 25, 30, 40, 50, 60).map { it to build(it) }
        plans.forEach { (m, p) -> assertTrue(p.estimatedMinutes <= m, "$m: ${p.estimatedMinutes}") }
        plans.zipWithNext().forEach { (a, b) ->
            assertTrue(ids(b.second).containsAll(ids(a.second)), "${a.first} -> ${b.first}")
            assertTrue(b.second.estimatedMinutes >= a.second.estimatedMinutes)
            ids(a.second).forEach { id -> assertTrue(sets(b.second, id) >= sets(a.second, id), "$id sets ${a.first} -> ${b.first}") }
        }
        assertEquals(4, ids(build(30)).size)
        assertEquals(5, ids(build(40)).size, "two upper lifts from 40 minutes")
        assertEquals(5, ids(build(60)).size)
        assertEquals(listOf("squat", "rdl", "calf"), ids(build(20)))
    }

    @Test fun aTwentyFiveMinuteWindowKeepsSquatAtThreeSetsAndTrimsHingeAndCalf() {
        val p = build(25)
        assertEquals(listOf("squat", "rdl", "calf"), ids(p)) // deadlift was last (11-16), so the RDL is next
        // Earlier builds trimmed all three equally to 2 sets; the squat is now protected (heavy squats are the main work, calf helps the knee).
        assertEquals(listOf(3, 2, 2), p.exercises.map { it.prescription.sets })
        assertTrue(p.estimatedMinutes <= 25, "estimated ${p.estimatedMinutes}")
        assertTrue(p.dropped.none { it.id in listOf("squat", "rdl", "calf") }, "only the upper lifts are dropped")
        assertEquals("Squat + RDL", p.dayLabel)
    }

    @Test fun aTwentyMinuteWindowStillHasAllThreeWithAShortWarmUp() {
        val p = build(20)
        assertEquals(listOf("squat", "rdl", "calf"), ids(p))
        assertTrue(p.exercises.all { it.prescription.sets == 2 })
        assertTrue(p.estimatedMinutes <= 20, "estimated ${p.estimatedMinutes}")
        val warm = p.exercises.first().warmups
        assertTrue(warm.size in 2..4, "warm-ups ${warm.map { it.weightKg }}")
        assertEquals(20.0, warm.first().weightKg, "the bar stays")
        assertEquals(70.0, warm.last().weightKg, "the last ramp stays")
    }

    @Test fun thirtyFiveMinutesAddsOneUpperLift() {
        val p = build(35)
        assertEquals(listOf("squat", "rdl", "calf"), ids(p).take(3))
        assertEquals(4, ids(p).size, ids(p).toString())
        assertTrue(p.estimatedMinutes <= 35)
    }

    @Test fun trimmingNeverGoesBelowTwoSetsAndNeverTouchesAnAlreadyShorterLift() {
        val deloaded = GymBuilder.build(20, history, today, deload = true)
        assertTrue(deloaded.exercises.all { it.prescription.sets >= 2 || it.exercise.id == "calf" || it.exercise.id == "rdl" })
        val eased = GymBuilder.build(20, history, today, rebaseline = RebaselineStatus(true, 1, 0.9, -1, 3, "h", "n"))
        // Stage 1 already takes a set off (squat 2, rdl 1): trimming must not push anything lower than that.
        val full = GymBuilder.build(120, history, today, rebaseline = RebaselineStatus(true, 1, 0.9, -1, 3, "h", "n"))
        eased.exercises.forEach { e ->
            val before = full.exercises.first { it.exercise.id == e.exercise.id }.prescription.sets
            assertTrue(e.prescription.sets == before || e.prescription.sets >= 2, "${e.exercise.id} $before -> ${e.prescription.sets}")
        }
    }

    @Test fun theHingeGoesOnlyWhenEvenTrimmedSetsAndWarmUpsDoNotFit() {
        val p = build(10)
        assertEquals(listOf("squat", "calf"), ids(p))
        assertTrue("rdl" in p.dropped.map { it.id })
        assertEquals("Squat + Calf", p.dayLabel)
    }

    @Test fun largeWindowsAreUnchanged() {
        val p = build(60)
        assertEquals(listOf("squat", "rdl", "calf"), ids(p).take(3))
        assertEquals(5, ids(p).size)
        assertEquals(3, p.exercises.first { it.exercise.id == "squat" }.prescription.sets)
        assertEquals(5, p.exercises.first().warmups.size)
    }
}
