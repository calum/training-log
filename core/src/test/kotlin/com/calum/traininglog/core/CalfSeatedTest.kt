package com.calum.traininglog.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Standing and seated calf raises: two exercises in one movement group, each with its own history and weight. */
class CalfSeatedTest {
    private val today = LocalDate.of(2026, 11, 20)

    private fun sets(kg: Double, n: Int = 3, reps: Int = 12, rir: Int = 2) = List(n) { LoggedSet(kg, reps, 12, rir) }
    private fun gym(day: Int, vararg logs: ExerciseLog) =
        SessionRecord("g-$day", LocalDate.of(2026, 11, day), SessionType.GYM, 60, 50, Feeling.GOOD, exercises = logs.toList())
    private fun standing(kg: Double) = ExerciseLog("calf", sets(kg))
    private fun seated(kg: Double, rir: Int = 2) = ExerciseLog("calf_seated", sets(kg, rir = rir))
    private fun squat(kg: Double) = ExerciseLog("squat", sets(kg, 3, 5))

    private val standingHistory = listOf(gym(5, squat(72.5), standing(62.5)), gym(12, squat(75.0), standing(65.0)))
    private fun calfSlot(records: List<SessionRecord>, liftingMin: Int = 75) =
        GymBuilder.build(liftingMin, records, today).exercises.first { it.exercise.movement == "calf" }

    @Test fun theLibraryHasTheSeatedCalfAsAMainLiftWithTheSameScheme() {
        val c = ExerciseLibrary.calfSeated
        assertEquals("calf_seated", c.id)
        assertEquals("Seated calf raise", c.name)
        assertEquals(Role.MAIN, c.role)
        assertEquals("calf", c.movement)
        assertEquals(50.0, c.startKg)
        assertEquals(2.5, c.incrementKg)
        assertEquals(listOf(3, 12, 12, 90), listOf(c.sets, c.repsLow, c.repsHigh, c.restSec))
        assertTrue(ExerciseLibrary.all.any { it.id == "calf_seated" })
    }

    @Test fun eachCalfIsTheOthersOnlySwap() {
        assertEquals(listOf("calf_seated"), ExerciseLibrary.swapsFor("calf").map { it.id })
        assertEquals(listOf("calf"), ExerciseLibrary.swapsFor("calf_seated").map { it.id })
        assertEquals(listOf("calf_seated"), GymBuilder.swapsFor(ExerciseLibrary.calf, standingHistory).map { it.id })
        val group = GymBuilder.addOptions(emptyList()).first { it.title == "Calves" }
        assertEquals(listOf("calf", "calf_seated"), group.exercises.map { it.id })
    }

    @Test fun withNoHistoryTheStandingCalfIsPreferredAndTheSeatedOneStartsAtFiftyKg() {
        assertEquals("calf", GymBuilder.preferredFor("calf", emptyList()).id)
        assertEquals("calf", calfSlot(standingHistory).exercise.id)
        assertEquals(50.0, GymBuilder.planFor("calf_seated", standingHistory, today, false).prescription.weightKg)
    }

    @Test fun afterASeatedSessionTheNextBuildUsesSeatedAtItsOwnWeightAndStandingHistoryIsUntouched() {
        val records = standingHistory + gym(16, squat(75.0), seated(50.0))
        val slot = calfSlot(records)
        assertEquals("calf_seated", slot.exercise.id)
        assertEquals(52.5, slot.prescription.weightKg, "seated progresses on its own: 50 + 2.5")
        assertEquals(3, slot.prescription.sets)
        // Standing is exactly what it would have been without the seated session.
        assertEquals(
            GymBuilder.planFor("calf", standingHistory, today, false).prescription,
            GymBuilder.planFor("calf", records, today, false).prescription,
        )
        assertEquals(67.5, GymBuilder.planFor("calf", records, today, false).prescription.weightKg)
    }

    @Test fun seatedStaysAtItsWeightWhenTheLastSetWasHard() {
        val records = standingHistory + gym(16, squat(75.0), seated(50.0, rir = 1))
        assertEquals(50.0, calfSlot(records).prescription.weightKg)
    }

    @Test fun swappingBackToStandingBringsStandingBackAtItsOwnWeight() {
        val records = standingHistory + gym(16, seated(50.0)) + gym(19, squat(77.5), standing(67.5))
        val slot = calfSlot(records)
        assertEquals("calf", slot.exercise.id)
        assertEquals(70.0, slot.prescription.weightKg)
        assertEquals(52.5, GymBuilder.planFor("calf_seated", records, today, false).prescription.weightKg)
    }

    @Test fun aSessionWithBothVariantsRemembersTheOneDoneLast() {
        val mixed = gym(16, ExerciseLog("calf", sets(65.0, 1)), seated(50.0))
        assertEquals("calf_seated", GymBuilder.preferredFor("calf", standingHistory + mixed).id)
    }

    @Test fun theBuildIsDeterministic() {
        val records = standingHistory + gym(16, seated(50.0))
        assertEquals(GymBuilder.build(60, records, today), GymBuilder.build(60, records, today))
    }

    @Test fun theSeatedCalfKeepsItsPlaceWhenTheWindowIsShort() {
        val records = standingHistory + gym(16, seated(50.0))
        val p = GymBuilder.build(25, records, today)
        val calf = p.exercises.first { it.exercise.movement == "calf" }
        assertEquals("calf_seated", calf.exercise.id)
        assertTrue(calf.prescription.sets in 2..3, "within the squat-protecting tolerance the calf may keep 3 sets")
        assertTrue(p.dropped.none { it.movement == "calf" })
    }

    @Test fun kneeCareStartLevelUsesTheBetterOfTheTwoCalfHistories() {
        assertEquals(3, CalfLadder.startLevel(listOf(gym(16, seated(50.0)))))
        assertEquals(3, CalfLadder.startLevel(listOf(gym(5, standing(30.0)), gym(16, seated(45.0)))))
        assertEquals(4, CalfLadder.startLevel(listOf(gym(5, standing(80.0)), gym(16, seated(50.0)))))
        assertEquals(1, CalfLadder.startLevel(emptyList()))
    }

    @Test fun gymCalfCountsEitherVariantForTheKneeCareWeek() {
        val from = LocalDate.of(2026, 11, 1)
        val n = KneeCare.gymCalfIn(listOf(gym(5, standing(60.0)), gym(16, seated(50.0)), gym(17, squat(70.0))), from, LocalDate.of(2026, 11, 30))
        assertEquals(2, n)
    }

    @Test fun theForecastFollowsTheVariantInUse() {
        val standingIds = Projection.forecast(standingHistory, today).lifts.map { it.exerciseId }
        assertTrue("calf" in standingIds && "calf_seated" !in standingIds, standingIds.toString())
        val f = Projection.forecast(standingHistory + gym(16, seated(50.0)), today)
        val ids = f.lifts.map { it.exerciseId }
        assertTrue("calf_seated" in ids && "calf" !in ids, ids.toString())
        val first = f.lifts.first { it.exerciseId == "calf_seated" }.scenarios.getValue(Scenario.EXPECTED).weeks.first()
        assertEquals(52.5, first.weightKg)
    }

    @Test fun personalRecordsAndTheProgressListTreatThemAsSeparateLifts() {
        val records = standingHistory + gym(16, seated(50.0)) + gym(19, seated(52.5))
        val prs = Rewards.summary(records, today).prs
        assertTrue(prs.any { it.exerciseId == "calf_seated" && "Seated calf raise" in it.text }, prs.toString())
        assertTrue(prs.none { it.exerciseId == "calf" && it.date == LocalDate.of(2026, 11, 19) })
        val lifts = ProgressStats.build(records, today).lifts.map { it.exerciseId }
        assertTrue("calf" in lifts && "calf_seated" in lifts, lifts.toString())
    }

    @Test fun exportsNameTheSeatedCalfAndTheWhitelistAcceptsItsId() {
        val records = listOf(gym(16, seated(50.0)))
        assertTrue("Seated calf raise" in Export.summary(records, LocalDate.of(2026, 11, 1), today), "readable export")
        val files = PublicExport.files(records)
        assertTrue(files.values.any { "calf_seated" in it }, "public export carries the library id")
        assertTrue(files.getValue(PublicExport.SUMMARY_PATH).contains("\"calf_seated\""))
    }
}
