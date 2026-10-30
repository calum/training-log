package com.calum.traininglog.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val d = LocalDate.of(2026, 11, 2)

private fun set(kg: Double, reps: Int, target: Int, rir: Int? = null) = LoggedSet(kg, reps, target, rir)
private fun entry(date: LocalDate, id: String, vararg sets: LoggedSet, miss: MissReason? = null) =
    HistoryEntry(date, ExerciseLog(id, sets.toList(), miss))

class WarmupTest {
    @Test fun squatPattern() {
        assertEquals(listOf(20.0, 40.0, 50.0, 60.0, 70.0), Warmup.squatSets(80.0).map { it.weightKg })
    }
    @Test fun lightWeights() {
        assertEquals(listOf(20.0), Warmup.squatSets(40.0).map { it.weightKg })
        assertTrue(Warmup.squatSets(20.0).isEmpty())
    }
}

class ProgressionTest {
    private val squat = ExerciseLibrary.squat

    @Test fun firstTimeUsesStartWeight() {
        assertEquals(squat.startKg, Progression.next(squat, emptyList(), d).weightKg)
    }
    @Test fun addsWeightWhenAllRepsHitWithReserve() {
        val h = listOf(entry(d.minusDays(3), "squat", set(80.0, 5, 5, 3), set(80.0, 5, 5, 3), set(80.0, 5, 5, 2)))
        assertEquals(82.5, Progression.next(squat, h, d).weightKg)
    }
    @Test fun repeatsWhenEffortWasHigh() {
        val h = listOf(entry(d.minusDays(3), "squat", set(80.0, 5, 5, 1), set(80.0, 5, 5, 1), set(80.0, 5, 5, 0)))
        assertEquals(80.0, Progression.next(squat, h, d).weightKg)
    }
    @Test fun roughDayHoldsWeight() {
        val h = listOf(entry(d.minusDays(3), "squat", set(80.0, 5, 5), set(80.0, 4, 5), miss = MissReason.ROUGH_DAY))
        assertEquals(80.0, Progression.next(squat, h, d).weightKg)
    }
    @Test fun atLimitDropsLoad() {
        val h = listOf(entry(d.minusDays(3), "squat", set(80.0, 5, 5), set(80.0, 3, 5), miss = MissReason.AT_LIMIT))
        assertEquals(75.0, Progression.next(squat, h, d).weightKg)
    }
    @Test fun twoFailuresTriggerBackoffAndFewerSets() {
        val f = { day: Long -> entry(d.minusDays(day), "squat", set(80.0, 4, 5), miss = MissReason.ROUGH_DAY) }
        val rx = Progression.next(squat, listOf(f(3), f(6)), d)
        assertEquals(75.0, rx.weightKg)
        assertEquals(2, rx.sets)
    }
    @Test fun longBreakRestartsAt90Percent() {
        val h = listOf(entry(d.minusDays(30), "squat", set(80.0, 5, 5, 3)))
        assertEquals(72.5, Progression.next(squat, h, d).weightKg)
    }
    @Test fun twoWeeksOffCarriesOn() {
        val h = listOf(entry(d.minusDays(14), "squat", set(80.0, 5, 5, 3)))
        assertEquals(82.5, Progression.next(squat, h, d).weightKg)
    }
    @Test fun doubleProgression() {
        val lat = ExerciseLibrary.get("lat_pulldown")
        val mid = listOf(entry(d.minusDays(3), "lat_pulldown", set(40.0, 10, 8), set(40.0, 9, 8), set(40.0, 9, 8)))
        assertEquals(10, Progression.next(lat, mid, d).reps)
        val top = listOf(entry(d.minusDays(3), "lat_pulldown", set(40.0, 12, 8), set(40.0, 12, 8), set(40.0, 12, 8)))
        val rx = Progression.next(lat, top, d)
        assertEquals(42.5, rx.weightKg)
        assertEquals(8, rx.reps)
    }
    @Test fun deloadDueAfterSixConsistentWeeks() {
        val dates = (0..7).map { LocalDate.of(2026, 9, 1).plusWeeks(it.toLong()) }
        assertTrue(Progression.deloadDue(dates, null, LocalDate.of(2026, 10, 27)))
        assertFalse(Progression.deloadDue(dates, null, LocalDate.of(2026, 9, 20)))
        assertFalse(Progression.deloadDue(dates, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 27)))
    }
}

class GymBuilderTest {
    private fun rec(daysAgo: Long, vararg ids: String, label: String? = null) = SessionRecord(
        "r$daysAgo-${ids.joinToString()}", d.minusDays(daysAgo), SessionType.GYM, 60, 55, Feeling.GOOD, dayLabel = label,
        exercises = ids.map { id ->
            val ex = ExerciseLibrary.find(id)
            ExerciseLog(id, List(3) { LoggedSet(ex?.startKg ?: 20.0, 8, 8, rir = 2) }, customName = if (ex == null) "Cable face pull" else null)
        },
    )
    private fun ids(p: GymPlan) = p.exercises.map { it.exercise.id }

    @Test fun fullSessionHasSquatHingeCalfAndTwoUpper() {
        val plan = GymBuilder.build(60, emptyList(), d)
        val ids = ids(plan)
        assertEquals(listOf("squat", "rdl", "calf"), ids.take(3))
        assertEquals(5, ids.size)
        assertTrue(plan.dropped.isEmpty())
        assertTrue(plan.exercises.first().warmups.isNotEmpty())
        assertEquals("Squat + RDL", plan.dayLabel)
    }
    @Test fun hingeAlternates() {
        assertEquals("rdl", GymBuilder.nextHinge(emptyList()).id)
        assertEquals("rdl", GymBuilder.nextHinge(listOf(rec(7, "squat"))).id)
        assertEquals("rdl", GymBuilder.nextHinge(listOf(rec(7, "squat", "deadlift"))).id)
        assertEquals("deadlift", GymBuilder.nextHinge(listOf(rec(7, "squat", "rdl"))).id)
        assertEquals("deadlift", GymBuilder.nextHinge(listOf(rec(14, "deadlift"), rec(7, "rdl"))).id)
        assertEquals("rdl", GymBuilder.nextHinge(listOf(rec(14, "rdl"), rec(7, "deadlift"))).id)
        val plan = GymBuilder.build(60, listOf(rec(7, "squat", "rdl")), d)
        assertEquals("Squat + Deadlift", plan.dayLabel)
        assertTrue("deadlift" in ids(plan) && "rdl" !in ids(plan))
    }
    @Test fun upperRotationPicksLeastRecentMovements() {
        assertEquals(listOf("vertical-pull", "horizontal-pull"), GymBuilder.nextUpperMovements(emptyList()))
        val history = listOf(rec(14, "lat_pulldown", "machine_chest"), rec(7, "cable_row", "db_shoulder"))
        // Never-trained none left; oldest first: vertical-pull and horizontal-push (14 days), tie by rotation order.
        assertEquals(listOf("vertical-pull", "horizontal-push"), GymBuilder.nextUpperMovements(history))
        val plan = GymBuilder.build(60, history, d)
        val upper = ids(plan).filter { it !in listOf("squat", "rdl", "deadlift", "calf") }
        assertEquals(listOf("lat_pulldown", "machine_chest"), upper)
    }
    @Test fun usesMostRecentlyUsedExercisePerMovement() {
        val history = listOf(rec(20, "lat_pulldown"), rec(10, "neutral_pulldown"), rec(1, "cable_row", "machine_chest", "db_shoulder"))
        assertEquals("neutral_pulldown", GymBuilder.preferredFor("vertical-pull", history).id)
        assertEquals("lat_pulldown", GymBuilder.preferredFor("vertical-pull", emptyList()).id)
        val plan = GymBuilder.build(60, history, d)
        assertTrue("neutral_pulldown" in ids(plan))
    }
    @Test fun timeFitDropsSecondUpperThenFirstUpperKeepingSquatAndCalf() {
        val full = GymBuilder.build(60, emptyList(), d)
        fun minutesOf(vararg keep: String) = full.exercises.filter { it.exercise.id in keep }.sumOf { GymBuilder.estimateMinutes(it) }
        val upper1 = ids(full)[3]
        val upper2 = ids(full)[4]
        val budget4 = minutesOf("squat", "rdl", "calf", upper1).toInt() + 1
        val p4 = GymBuilder.build(budget4, emptyList(), d)
        assertEquals(listOf("squat", "rdl", "calf", upper1), ids(p4))
        assertEquals(listOf(upper2), p4.dropped.map { it.id })
        val budget3 = minutesOf("squat", "rdl", "calf").toInt() + 1
        val p3 = GymBuilder.build(budget3, emptyList(), d)
        assertEquals(listOf("squat", "rdl", "calf"), ids(p3))
        assertEquals(listOf(upper2, upper1), p3.dropped.map { it.id })
        val tiny = GymBuilder.build(minutesOf("squat", "calf").toInt() + 1, emptyList(), d)
        assertEquals(listOf("squat", "calf"), ids(tiny))
        assertEquals("Squat + Calf", tiny.dayLabel)
        val zero = GymBuilder.build(1, emptyList(), d)
        assertEquals(listOf("squat", "calf"), ids(zero))
    }
    @Test fun otherHingeIsAnOptionalExtraWithBigBudget() {
        val big = GymBuilder.build(75, emptyList(), d)
        assertEquals("deadlift", ids(big).last())
        assertEquals(setOf("deadlift"), big.optionalIds)
        assertEquals(6, ids(big).size)
        val small = GymBuilder.build(74, emptyList(), d)
        assertTrue("deadlift" !in ids(small))
        assertTrue(small.optionalIds.isEmpty())
    }
    @Test fun extraIsDroppedBeforeFirstUpper() {
        val big = GymBuilder.build(120, emptyList(), d)
        val without = big.exercises.filter { it.exercise.id != "deadlift" }.sumOf { GymBuilder.estimateMinutes(it) }
        val p = GymBuilder.build(without.toInt() + 1, emptyList(), d)
        assertTrue("deadlift" !in ids(p))
        assertTrue(p.dropped.isEmpty())
    }
    @Test fun oldABRecordsStillWork() {
        val old = listOf(
            SessionRecord("1", d.minusDays(9), SessionType.GYM, 60, 55, Feeling.GOOD, dayLabel = "B",
                exercises = listOf(ExerciseLog("deadlift", listOf(LoggedSet(100.0, 3, 3, 2))), ExerciseLog("horizontal_missing_id", listOf(LoggedSet(5.0, 5, 5))))),
            SessionRecord("2", d.minusDays(12), SessionType.GYM, 60, 55, Feeling.GOOD, dayLabel = "A",
                exercises = listOf(ExerciseLog("rdl", listOf(LoggedSet(70.0, 8, 8, 2))))),
        )
        assertEquals("rdl", GymBuilder.nextHinge(old).id)
        assertEquals("Squat + RDL", GymBuilder.nextDayLabel(old))
        val plan = GymBuilder.build(60, old, d)
        assertEquals("Squat + RDL", plan.dayLabel)
        assertEquals("Squat + RDL", GymBuilder.nextDayLabel(emptyList()))
    }
    @Test fun swapStaysInMovementGroupIncludingHinge() {
        val swaps = ExerciseLibrary.swapsFor("lat_pulldown")
        assertTrue(swaps.isNotEmpty())
        assertTrue(swaps.all { it.movement == "vertical-pull" })
        assertEquals(listOf("rdl"), ExerciseLibrary.swapsFor("deadlift").map { it.id })
        assertEquals(listOf("deadlift"), ExerciseLibrary.swapsFor("rdl").map { it.id })
        assertTrue(ExerciseLibrary.swapsFor("squat").isEmpty())
        assertTrue(ExerciseLibrary.swapsFor("custom:whatever").isEmpty())
    }
    @Test fun deloadCutsSets() {
        val plan = GymBuilder.build(120, emptyList(), d, deload = true)
        assertEquals(2, plan.exercises.first().prescription.sets)
    }
    @Test fun customExercisePlanningDefaultsAndRepeats() {
        val fresh = GymBuilder.planFor("custom:cable_face_pull", emptyList(), d, deload = false)
        assertEquals(3, fresh.prescription.sets); assertEquals(10, fresh.prescription.reps); assertEquals(0.0, fresh.prescription.weightKg)
        assertEquals("cable face pull", fresh.exercise.name)
        val history = listOf(SessionRecord("c", d.minusDays(3), SessionType.GYM, 60, 50, Feeling.GOOD,
            exercises = listOf(ExerciseLog("custom:cable_face_pull", listOf(LoggedSet(20.0, 12, 10, 3), LoggedSet(20.0, 12, 10, 3), LoggedSet(20.0, 10, 10, 1)), customName = "Cable face pull"))))
        val again = GymBuilder.planFor("custom:cable_face_pull", history, d, deload = false)
        assertEquals(20.0, again.prescription.weightKg); assertEquals(12, again.prescription.reps); assertEquals("Cable face pull", again.exercise.name)
        // No progression even after a long gap or all reps hit with RIR.
        val later = GymBuilder.planFor("custom:cable_face_pull", history, d.plusDays(60), deload = false)
        assertEquals(20.0, later.prescription.weightKg)
        assertTrue(GymBuilder.addOptions(history).last().let { it.title == GymBuilder.YOUR_EXERCISES && it.exercises.single().id == "custom:cable_face_pull" })
        assertTrue(GymBuilder.addOptions(emptyList()).none { it.title == GymBuilder.YOUR_EXERCISES })
        assertEquals(ExerciseLibrary.all.size, GymBuilder.addOptions(emptyList()).sumOf { it.exercises.size })
        assertEquals("custom:cable_face_pull", ExerciseLibrary.customId("Cable face pull!"))
    }
    @Test fun customExercisesDoNotAffectSlots() {
        val history = listOf(rec(3, "squat", "custom:x"))
        assertEquals("rdl", GymBuilder.nextHinge(history).id)
        assertEquals(listOf("vertical-pull", "horizontal-pull"), GymBuilder.nextUpperMovements(history))
    }
}

class RecommenderTest {
    @Test fun ninetyMinutesPrefersGymWhenBehind() {
        val r = Recommender.recommend(RecommendationInput(90, Feeling.GOOD, daysSinceGym = 4))
        assertEquals(SessionType.GYM, r.primary.type)
        assertEquals(SessionType.TREADMILL, r.alternative?.type)
    }
    @Test fun gymNotOfferedWhenTravelEatsTheTime() {
        val r = Recommender.recommend(RecommendationInput(45, Feeling.GOOD))
        assertNotEquals(SessionType.GYM, r.primary.type)
        assertNotEquals(SessionType.GYM, r.alternative?.type)
    }
    @Test fun gymSpacedAfterRecentSession() {
        val r = Recommender.recommend(RecommendationInput(90, Feeling.GOOD, daysSinceGym = 1, gymDoneThisWeek = 1))
        assertEquals(SessionType.TREADMILL, r.primary.type)
    }
    @Test fun gymDoneForWeekFavoursTreadmill() {
        val r = Recommender.recommend(RecommendationInput(90, Feeling.GOOD, gymDoneThisWeek = 2, treadmillDoneThisWeek = 1, daysSinceGym = 3))
        assertEquals(SessionType.TREADMILL, r.primary.type)
    }
    @Test fun illMeansRest() {
        val r = Recommender.recommend(RecommendationInput(90, Feeling.ILL))
        assertNull(r.primary.type)
    }
    @Test fun roughOffersEasyWalkOrRest() {
        val r = Recommender.recommend(RecommendationInput(60, Feeling.ROUGH))
        assertEquals(TreadmillStyle.STEADY, r.primary.style)
        assertEquals(30, r.primary.minutes)
        assertNull(r.alternative?.type)
    }
    @Test fun highKneePainKeepsTreadmillSteady() {
        val r = Recommender.recommend(RecommendationInput(90, Feeling.GOOD, kneePain = 8, gymDoneThisWeek = 2, daysSinceGym = 3))
        assertEquals(TreadmillStyle.STEADY, r.primary.style)
    }
}

class TreadmillTest {
    @Test fun everyStyleAndLengthRespectsMinimumSegmentAndTotal() {
        for (style in TreadmillStyle.entries) for (m in listOf(20, 25, 30, 45, 60, 90, 120)) {
            val plan = TreadmillPlanner.build(style, m)
            assertEquals(m * 60, plan.totalSec, "$style $m total")
            plan.segments.forEach { assertTrue(it.seconds >= 120, "$style $m segment ${it.label} ${it.seconds}s") }
            plan.segments.forEach { assertTrue(it.inclinePct in 0.0..MAX_INCLINE) }
        }
    }
    @Test fun startsWithWarmupSettings() {
        val s = TreadmillPlanner.build(TreadmillStyle.PYRAMID, 40).segments.first()
        assertEquals(5.0, s.speedKmh); assertEquals(6.0, s.inclinePct)
    }
    @Test fun neverRepeatsLastStyle() {
        for (seed in 0L..30L) {
            assertNotEquals(TreadmillStyle.PYRAMID, TreadmillPlanner.chooseStyle(90, TreadmillStyle.PYRAMID, false, seed))
        }
    }
    @Test fun shortSessionsFallBackToSteady() {
        assertEquals(TreadmillStyle.STEADY, TreadmillPlanner.build(TreadmillStyle.LONG_INTERVALS, 20).style)
    }
    @Test fun calibrationMoves() {
        val c = Calibration()
        assertEquals(9.0, c.adjusted(Effort.TOO_EASY).inclinePct)
        assertEquals(7.0, c.adjusted(Effort.TOO_HARD).inclinePct)
    }
}

class TimelineTest {
    private val tl = Timeline(listOf(
        Segment(300, 5.0, 6.0, "a"), Segment(120, 5.5, 8.0, "b"), Segment(120, 5.5, 8.0, "same"), Segment(180, 4.0, 2.0, "c"),
    ))
    @Test fun stateTracksSegments() {
        val s = tl.stateAt(310)
        assertEquals(1, s.index); assertEquals(110, s.secondsLeft)
        assertEquals("same", s.next?.label)
        assertTrue(tl.stateAt(720).finished)
    }
    @Test fun cuesWarnBeforeRealChangesOnly() {
        val cues = tl.cuesBetween(-1, tl.totalSec)
        assertEquals(listOf(285 to CueType.PRE_WARNING, 300 to CueType.CHANGE, 420 to CueType.CHANGE, 525 to CueType.PRE_WARNING, 540 to CueType.CHANGE, 720 to CueType.DONE),
            cues.map { it.atSec to it.type })
    }
    @Test fun cuesAreNotRepeatedAcrossWindows() {
        val a = tl.cuesBetween(0, 290); val b = tl.cuesBetween(290, 400)
        assertEquals(1, a.size); assertEquals(1, b.size)
    }
    @Test fun adjustExtendsAndNeverBelow30() {
        assertEquals(750, tl.adjusted(1, 30).totalSec)
        assertEquals(30, tl.adjusted(1, -500).segments[1].seconds)
    }
}

class ExportStatsTest {
    private val rec = SessionRecord("1", d, SessionType.GYM, 60, 50, Feeling.OKAY, kneePain = 2, dayLabel = "A",
        exercises = listOf(ExerciseLog("squat", listOf(set(80.0, 5, 5, 3), set(80.0, 5, 5, 3), set(80.0, 4, 5, 1)), MissReason.ROUGH_DAY)))
    private val tread = SessionRecord("2", d.plusDays(1), SessionType.TREADMILL, 45, 30, Feeling.GOOD,
        treadmill = TreadmillLog(TreadmillStyle.PYRAMID, 2700, 1800, Effort.JUST_RIGHT))

    @Test fun exportIncludesPrivateFieldsAndMisses() {
        val text = Export.summary(listOf(rec, tread), d, d.plusDays(6))
        assertTrue("knee 2/10" in text); assertTrue("felt okay" in text)
        assertTrue("Squat 80kg x 5/5/4" in text); assertTrue("MISSED reps (rough day)" in text)
        assertTrue("30 of 45 min (shortened)" in text)
        assertTrue("Sessions done: 2" in text)
    }
    @Test fun exportFlagsCustomExercises() {
        val custom = SessionRecord("3", d, SessionType.GYM, 60, 50, Feeling.GOOD, dayLabel = "Squat + RDL",
            exercises = listOf(
                ExerciseLog("squat", listOf(set(80.0, 5, 5, 3))),
                ExerciseLog("custom:cable_face_pull", listOf(set(20.0, 12, 12, 2), set(20.0, 12, 12, 2), set(20.0, 10, 12, 1)), customName = "cable face pull"),
            ))
        val text = Export.summary(listOf(custom), d, d)
        assertTrue("CUSTOM: cable face pull 20kg x 12/12/10" in text, text)
        assertTrue(text.lines().last() == "Custom exercises logged (please add properly): cable face pull", text)
        assertFalse("Custom exercises logged" in Export.summary(listOf(rec), d, d))
    }
    @Test fun unknownAndCustomIdsDoNotCrash() {
        val weird = SessionRecord("4", d, SessionType.GYM, 60, 50, Feeling.GOOD,
            exercises = listOf(ExerciseLog("custom:lost", listOf(set(10.0, 5, 5, 2))), ExerciseLog("removed_exercise", listOf(set(10.0, 5, 5, 2)))))
        val records = listOf(weird, weird.copy(id = "5", date = d.plusDays(7), exercises = weird.exercises.map { it.copy(sets = listOf(set(15.0, 5, 5, 2))) }))
        assertTrue("CUSTOM: lost 10kg x 5" in Export.summary(records, d, d.plusDays(8)))
        assertTrue("removed_exercise" in Export.summary(records, d, d.plusDays(8)))
        val r = Rewards.summary(records, d.plusDays(8))
        Rewards.celebration(records.last(), records.dropLast(1))
        assertTrue(r.prs.none { it.exerciseId.startsWith("custom:") })
        Projection.forecast(records, d.plusDays(8), headlineTarget = HeadlineTarget("custom:lost", 50.0))
        assertEquals("lost", ExerciseLibrary.nameOf("custom:lost"))
        assertEquals("removed_exercise", ExerciseLibrary.nameOf("removed_exercise"))
        assertEquals(null, ExerciseLibrary.find("custom:lost"))
    }
    @Test fun oldJsonWithoutCustomNameDecodes() {
        val old = """{"exerciseId":"squat","sets":[{"weightKg":80.0,"reps":5,"targetReps":5}]}"""
        assertEquals(null, kotlinx.serialization.json.Json.decodeFromString(ExerciseLog.serializer(), old).customName)
    }
    @Test fun weekStatsCountDoneSessions() {
        val w = Stats.week(listOf(rec, tread), d)
        assertEquals(1, w.gym); assertEquals(1, w.treadmill)
        assertEquals(3, Stats.daysSince(SessionType.GYM, listOf(rec), d.plusDays(3)))
    }
    @Test fun recordsRoundTripThroughJson() {
        val json = kotlinx.serialization.json.Json
        val text = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SessionRecord.serializer()), listOf(rec, tread))
        assertEquals(listOf(rec, tread), json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SessionRecord.serializer()), text))
    }
}
