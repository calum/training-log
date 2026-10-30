package com.calum.traininglog.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SettingsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val mon = LocalDate.of(2026, 10, 26)
    private fun rec(date: LocalDate, type: SessionType) = SessionRecord("$date-$type", date, type, 45, 45, Feeling.GOOD)

    private fun squatLog(kg: Double, rir: Int) =
        ExerciseLog("squat", List(3) { LoggedSet(kg, 5, 5, rir) })

    private fun squatRecord(date: LocalDate, kg: Double, rir: Int) =
        SessionRecord("g$date", date, SessionType.GYM, 60, 60, Feeling.GOOD, exercises = listOf(squatLog(kg, rir)))

    @Test fun defaultsMatchOriginalConstants() {
        val s = UserSettings()
        assertEquals(Recommender.GYM_TARGET, s.gymTarget)
        assertEquals(Recommender.TREADMILL_TARGET, s.treadmillTarget)
        assertEquals(WeekPlanner.STRETCH_GYM, s.gymStretch)
        assertEquals(WeekPlanner.STRETCH_TREADMILL, s.treadmillStretch)
        assertEquals(GymBuilder.TRAVEL_MIN_EACH_WAY, s.travelMinEachWay)
        assertEquals(GymBuilder.MIN_WORTHWHILE_LIFTING_MIN, s.minLiftingMin)
        assertEquals(Warmup.BAR_KG, s.barKg)
        assertEquals(TreadmillPlanner.WARMUP.speedKmh, s.warmupSpeedKmh)
        assertEquals(TreadmillPlanner.WARMUP.inclinePct, s.warmupInclinePct)
        assertEquals(Projection.DELOAD_EVERY_WEEKS, s.deloadWeeks)
        assertEquals(UserSettings(), UserSettings().sanitized())
        ExerciseLibrary.all.forEach { assertEquals(it, s.resolve(it)) }
    }

    @Test fun defaultsPlanLikeBefore() {
        val records = listOf(squatRecord(mon.minusDays(3), 80.0, 2))
        assertEquals(GymBuilder.build(60, records, mon), GymBuilder.build(60, records, mon, settings = UserSettings()))
        assertEquals(40, GymBuilder.liftingMinutes(60))
    }

    @Test fun overridesChangeStartIncrementAndRest() {
        val s = UserSettings().withLift("squat", LiftOverride(startKg = 60.0, incrementKg = 5.0, restSec = 180))
        val fresh = GymBuilder.plan(ExerciseLibrary.squat, emptyList(), mon, false, settings = s)
        assertEquals(60.0, fresh.prescription.weightKg)
        assertEquals(180, fresh.exercise.restSec)
        assertTrue(GymBuilder.estimateMinutes(fresh) > GymBuilder.estimateMinutes(GymBuilder.plan(ExerciseLibrary.squat, emptyList(), mon, false)))

        val records = listOf(squatRecord(mon.minusDays(3), 80.0, 2))
        assertEquals(82.5, GymBuilder.plan(ExerciseLibrary.squat, records, mon, false).prescription.weightKg)
        assertEquals(85.0, GymBuilder.plan(ExerciseLibrary.squat, records, mon, false, settings = s).prescription.weightKg)
        // Untouched lifts are unchanged.
        assertEquals(ExerciseLibrary.calf, s.resolve(ExerciseLibrary.calf))
    }

    @Test fun overrideEqualToDefaultIsDropped() {
        val s = UserSettings().withLift("squat", LiftOverride(startKg = 80.0))
        assertTrue(s.lifts.isEmpty())
    }

    @Test fun barWeightChangesWarmups() {
        val s = UserSettings(barKg = 15.0)
        assertEquals(15.0, Warmup.squatSets(80.0, s.barKg).first().weightKg)
        assertEquals(20.0, Warmup.squatSets(80.0).first().weightKg)
        val plan = GymBuilder.plan(ExerciseLibrary.squat, emptyList(), mon, false, settings = s)
        assertEquals(15.0, plan.warmups.first().weightKg)
    }

    @Test fun restartAndDeloadSettings() {
        val hist = listOf(HistoryEntry(mon.minusDays(30), squatLog(100.0, 2)))
        assertEquals(90.0, Progression.next(ExerciseLibrary.squat, hist, mon).weightKg)
        val s = UserSettings(restartFactor = 0.8)
        assertEquals(80.0, Progression.next(ExerciseLibrary.squat, hist, mon, settings = s).weightKg)
        // 30 days is not a break when the threshold is 45 days.
        assertEquals(102.5, Progression.next(ExerciseLibrary.squat, hist, mon, settings = UserSettings(restartAfterDays = 45)).weightKg)

        val dates = (0..8).map { mon.minusWeeks(it.toLong()) }
        assertTrue(Progression.deloadDue(dates, null, mon))
        assertFalse(Progression.deloadDue(dates, null, mon, settings = UserSettings(deloadWeeks = 12)))
    }

    @Test fun targetsFlowIntoWeekPlannerAndRing() {
        val records = listOf(rec(mon, SessionType.GYM), rec(mon.plusDays(1), SessionType.TREADMILL))
        val today = mon.plusDays(2)
        val def = WeekPlanner.plan(records, Profile(), today)
        assertEquals(1, def.gymLeft)
        assertEquals(5, def.goodWeekTotal)

        val s = UserSettings(gymTarget = 3, treadmillTarget = 1, gymStretch = 4)
        val v = WeekPlanner.plan(records, Profile(settings = s), today)
        assertEquals(2, v.gymLeft)
        assertEquals(0, v.treadmillLeft)
        assertEquals(3, v.gymStretchLeft)
        assertEquals(4, v.goodWeekTotal)
        assertEquals(2, v.goodWeekDone)
        assertEquals(v, WeekPlanner.plan(records, Profile(), today, s))

        val ring = Rewards.summary(records, today, s).ring
        assertEquals(3, ring.gymTarget)
        assertEquals(1, ring.treadmillTarget)
        assertEquals(0.5, ring.progress, 1e-9)
        assertEquals(2, Rewards.summary(records, today).ring.gymTarget)
    }

    @Test fun zeroTargetsDoNotDivideByZero() {
        val s = UserSettings(gymTarget = 0, treadmillTarget = 0, gymStretch = 0, treadmillStretch = 0)
        val ring = Rewards.summary(emptyList(), mon, s).ring
        assertEquals(1.0, ring.progress)
        assertEquals(0.0, ring.bonus)
    }

    @Test fun recommenderUsesTargetsAndTravel() {
        val base = RecommendationInput(minutes = 60, feeling = Feeling.GOOD, gymDoneThisWeek = 2, treadmillDoneThisWeek = 0)
        assertEquals(SessionType.TREADMILL, Recommender.recommend(base).primary.type)
        val wantMoreGym = base.copy(settings = UserSettings(gymTarget = 4))
        assertEquals(SessionType.GYM, Recommender.recommend(wantMoreGym).primary.type)

        // 45 minutes: 25 lifting with default travel (below the 30 minimum), 35 with no travel.
        val short = RecommendationInput(minutes = 45, feeling = Feeling.GOOD)
        assertNotEquals(SessionType.GYM, Recommender.recommend(short).primary.type)
        assertEquals(SessionType.GYM, Recommender.recommend(short.copy(settings = UserSettings(travelMinEachWay = 2))).primary.type)
        assertEquals(SessionType.GYM, Recommender.recommend(short.copy(settings = UserSettings(minLiftingMin = 20))).primary.type)
    }

    @Test fun travelChangesLiftingMinutes() {
        assertEquals(40, GymBuilder.liftingMinutes(60))
        assertEquals(50, GymBuilder.liftingMinutes(60, UserSettings(travelMinEachWay = 5)))
        assertEquals(50, UserSettings(travelMinEachWay = 5).liftingMinutes(60))
    }

    @Test fun treadmillWarmupFromSettings() {
        val plan = TreadmillPlanner.build(TreadmillStyle.STEADY, 30, settings = UserSettings(warmupSpeedKmh = 4.0, warmupInclinePct = 3.0))
        assertEquals(4.0, plan.segments.first().speedKmh)
        assertEquals(3.0, plan.segments.first().inclinePct)
        assertEquals(TreadmillPlanner.build(TreadmillStyle.STEADY, 30), TreadmillPlanner.build(TreadmillStyle.STEADY, 30, settings = UserSettings()))
    }

    @Test fun projectionUsesTargetsAndDeloadCadence() {
        val def = Projection.forecast(emptyList(), mon)
        assertEquals(def, Projection.forecast(emptyList(), mon, settings = UserSettings()))
        assertEquals(2, def.weekOutlook.gymTarget)
        val s = UserSettings(gymTarget = 3, treadmillTarget = 2, deloadWeeks = 4)
        val f = Projection.forecast(emptyList(), mon, settings = s)
        assertEquals(3, f.weekOutlook.gymTarget)
        val squat = f.lift("squat")!!.scenarios.getValue(Scenario.EXPECTED).weeks
        assertTrue(squat.first { it.weekOffset == 4 }.deload)
        assertFalse(squat.first { it.weekOffset == 6 }.deload)
        // Explicit counts still win over settings.
        assertEquals(1, Projection.forecast(emptyList(), mon, gymPerWeek = 1, settings = s).weekOutlook.gymTarget)
    }

    @Test fun sanitizedClampsRanges() {
        val s = UserSettings(
            gymTarget = 99, treadmillTarget = -1, travelMinEachWay = 500, deloadWeeks = 0, restartFactor = 5.0, barKg = 0.0,
        ).withLift("squat", LiftOverride(incrementKg = 50.0, restSec = 5)).sanitized()
        assertEquals(7, s.gymTarget)
        assertEquals(0, s.treadmillTarget)
        assertEquals(60, s.travelMinEachWay)
        assertEquals(3, s.deloadWeeks)
        assertEquals(1.0, s.restartFactor)
        assertEquals(5.0, s.barKg)
        assertEquals(10.0, s.liftOverride("squat").incrementKg)
        assertEquals(30, s.liftOverride("squat").restSec)
    }

    @Test fun jsonRoundTripAndOldJson() {
        val s = UserSettings(gymTarget = 3, barKg = 15.0).withLift("rdl", LiftOverride(startKg = 50.0, restSec = 100))
        assertEquals(s, json.decodeFromString<UserSettings>(json.encodeToString(s)))
        val p = Profile(settings = s)
        assertEquals(p, json.decodeFromString<Profile>(json.encodeToString(p)))
        // Profile saved before settings existed.
        assertEquals(UserSettings(), json.decodeFromString<Profile>("{}").settings)
        // Placeholder-era settings object.
        assertEquals(UserSettings(), json.decodeFromString<Profile>("""{"settings":{"version":1}}""").settings)
        // Partial settings keep defaults for the rest.
        val part = json.decodeFromString<UserSettings>("""{"gymTarget":4}""")
        assertEquals(4, part.gymTarget)
        assertEquals(3, part.treadmillTarget)
    }
}
