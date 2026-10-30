package com.calum.traininglog.core

import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val today = LocalDate.of(2026, 11, 2)

private fun gym(daysAgo: Long, vararg logs: ExerciseLog) = SessionRecord(
    "g$daysAgo", today.minusDays(daysAgo), SessionType.GYM, 60, 50, Feeling.GOOD, exercises = logs.toList(),
)
private fun squatLog(kg: Double, reps: Int = 5, rir: Int? = 3, n: Int = 3) =
    ExerciseLog("squat", List(n) { LoggedSet(kg, reps, 5, rir) })

class RebaselineTest {
    @Test fun noHistoryIsStageOne() {
        val s = Rebaseline.status(emptyList(), today)
        assertTrue(s.active); assertEquals(1, s.stage)
    }
    @Test fun thirtyDaysAgoIsStageOneWithEasing() {
        val s = Rebaseline.status(List(10) { gym(30L + it * 3) }, today)
        assertTrue(s.active)
        assertEquals(1, s.stage); assertEquals(0.9, s.weightFactor); assertEquals(-1, s.setsDelta)
        assertTrue(s.targetRir >= 3); assertTrue(s.headline.contains("session 1 of 3"))
    }
    @Test fun sessionTwoAndThreeBack() {
        val base = List(10) { gym(60L + it * 3) }
        val s2 = Rebaseline.status(base + gym(5), today)
        assertEquals(2, s2.stage); assertEquals(0.95, s2.weightFactor); assertEquals(0, s2.setsDelta); assertEquals(2, s2.targetRir)
        val s3 = Rebaseline.status(base + gym(10) + gym(4), today)
        assertEquals(3, s3.stage); assertEquals(1.0, s3.weightFactor)
    }
    @Test fun inactiveAfterStageThreeAndForNormalGap() {
        val base = List(10) { gym(60L + it * 3) }
        assertFalse(Rebaseline.status(base + gym(14) + gym(8) + gym(3), today).active)
        assertFalse(Rebaseline.status(List(10) { gym(7L + it * 4) }, today).active)
        assertFalse(Rebaseline.status(List(10) { gym(20L + it * 4) }, today).active) // 20 days is not a break
    }
    @Test fun builderStageOneEasesSetsAndSkipsDeload() {
        val rec = List(5) { gym(30L + it * 3, squatLog(100.0)) }
        val st = Rebaseline.status(rec, today)
        val normal = GymBuilder.build(90, rec, today)
        val eased = GymBuilder.build(90, rec, today, deload = true, rebaseline = st)
        val n = normal.exercises.first { it.exercise.id == "squat" }.prescription
        val e = eased.exercises.first { it.exercise.id == "squat" }.prescription
        assertEquals(90.0, e.weightKg) // 90% applied once, not twice
        assertEquals(n.weightKg, e.weightKg)
        assertEquals(n.sets - 1, e.sets)
        assertFalse(eased.deload)
        assertEquals(st, eased.rebaseline)
        assertNull(normal.rebaseline)
    }
    @Test fun neverDoneExerciseUsesFactorOnStartWeight() {
        val p = GymBuilder.plan(ExerciseLibrary.squat, emptyList(), today, false, rebaseline = Rebaseline.status(emptyList(), today))
        assertEquals(72.5, p.prescription.weightKg)
        assertEquals(2, p.prescription.sets)
    }
    @Test fun nextSessionProgressesFromEasedWeight() {
        // Session 1 back: 90 kg, reps hit with 3 in reserve -> progress normally from 90.
        val rec = List(5) { gym(60L + it * 3, squatLog(100.0)) } + gym(3, squatLog(90.0, n = 2))
        val p = Progression.next(ExerciseLibrary.squat, GymBuilder.history("squat", rec), today)
        assertEquals(92.5, p.weightKg)
    }
}

class CustomMovementTest {
    private fun custom(daysAgo: Long, movement: String?) = gym(
        daysAgo, ExerciseLog("custom:face_pull", listOf(LoggedSet(20.0, 12, 10)), customName = "Face pull", customMovement = movement),
    )

    @Test fun movementDerivedFromMostRecentLog() {
        val rec = listOf(custom(10, "horizontal-pull"), custom(3, null))
        assertEquals("horizontal-pull", GymBuilder.customMovementFor("custom:face_pull", rec))
        assertEquals("horizontal-pull", GymBuilder.customFromHistory(rec).single().movement)
        assertEquals(ExerciseLibrary.OTHER_MOVEMENT, GymBuilder.customFromHistory(listOf(custom(3, null))).single().movement)
    }
    @Test fun swapListsIncludeCustomBothWays() {
        val rec = listOf(custom(3, "horizontal-pull"))
        val mine = GymBuilder.customFromHistory(rec).single()
        assertTrue(GymBuilder.swapsFor(ExerciseLibrary.get("cable_row"), rec).any { it.id == mine.id })
        assertTrue(GymBuilder.swapsFor(mine, rec).any { it.id == "cable_row" })
        assertFalse(GymBuilder.swapsFor(mine, rec).any { it.id == mine.id })
        assertFalse(GymBuilder.swapsFor(ExerciseLibrary.get("lat_pulldown"), rec).any { it.id == mine.id })
    }
    @Test fun addOptionsGroupsCustomUnderMovement() {
        val rec = listOf(custom(3, "horizontal-pull"))
        val groups = GymBuilder.addOptions(rec)
        assertTrue(groups.first { it.title == "Horizontal pull" }.exercises.any { it.id == "custom:face_pull" })
        assertTrue(groups.none { it.title == GymBuilder.YOUR_EXERCISES })
        assertTrue(GymBuilder.addOptions(listOf(custom(3, null))).last().title == GymBuilder.YOUR_EXERCISES)
    }
    @Test fun oldJsonWithoutCustomMovementDecodes() {
        val log = Json { ignoreUnknownKeys = true }.decodeFromString<ExerciseLog>(
            """{"exerciseId":"custom:x","sets":[],"customName":"X"}""",
        )
        assertNull(log.customMovement)
    }
}
