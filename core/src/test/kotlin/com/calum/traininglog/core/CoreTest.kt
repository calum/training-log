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
    @Test fun fullSessionKeepsEverythingWithPlentyOfTime() {
        val plan = GymBuilder.build(120, "A", emptyList(), d)
        assertEquals(5, plan.exercises.size)
        assertTrue(plan.dropped.isEmpty())
        assertEquals("squat", plan.exercises.first().exercise.id)
        assertTrue(plan.exercises.first().warmups.isNotEmpty())
    }
    @Test fun tightTimeDropsAccessoriesFirst() {
        val plan = GymBuilder.build(30, "A", emptyList(), d)
        assertTrue(plan.exercises.size < 5)
        assertTrue(plan.dropped.all { it.role == Role.ACCESSORY || it.id == "calf" || it.id == "rdl" })
        assertEquals("squat", plan.exercises.first().exercise.id)
        assertTrue(plan.dropped.first().role == Role.ACCESSORY)
    }
    @Test fun alternatesDayLabels() {
        assertEquals("A", GymBuilder.nextDayLabel(emptyList()))
        val rec = SessionRecord("1", d, SessionType.GYM, 60, 55, Feeling.GOOD, dayLabel = "A")
        assertEquals("B", GymBuilder.nextDayLabel(listOf(rec)))
    }
    @Test fun swapKeepsMovement() {
        val swaps = ExerciseLibrary.swapsFor("lat_pulldown")
        assertTrue(swaps.isNotEmpty())
        assertTrue(swaps.all { it.movement == "vertical-pull" })
        val plan = GymBuilder.build(120, "A", emptyList(), d, upperChoice = mapOf("vertical-pull" to "neutral_pulldown"))
        assertTrue(plan.exercises.any { it.exercise.id == "neutral_pulldown" })
    }
    @Test fun deloadCutsSets() {
        val plan = GymBuilder.build(120, "A", emptyList(), d, deload = true)
        assertEquals(2, plan.exercises.first().prescription.sets)
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
