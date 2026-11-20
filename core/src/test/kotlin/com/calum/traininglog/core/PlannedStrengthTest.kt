package com.calum.traininglog.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Planned strength sessions: honest text, consistent minutes (lifting window + prehab) and the hip-only knee care routine. */
class PlannedStrengthTest {
    private val today = LocalDate.of(2026, 11, 20)
    private val cfg = RacePlanSettings(sessionsPerWeek = 4, maxWeeklyMinutes = 300)
    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)
    private fun gym(date: LocalDate, min: Int) = SessionRecord("g-$date", date, SessionType.GYM, min, min, Feeling.OKAY)
    private fun walk(date: LocalDate, min: Int) = SessionRecord("t-$date", date, SessionType.TREADMILL, min, min, Feeling.GOOD,
        treadmill = TreadmillLog(TreadmillStyle.STEADY, min * 60, min * 60, distanceM = min * 85))
    private val history = listOf(
        walk(d(10, 30), 30), gym(d(11, 1), 23), walk(d(11, 2), 30), gym(d(11, 5), 27), walk(d(11, 6), 45),
        gym(d(11, 12), 37), gym(d(11, 16), 40), walk(d(11, 18), 30),
    )
    private val plan = RacePlanner.build(cfg, history + walk(d(11, 19), 30), LocalDate.of(2026, 11, 19), NO_REST)
    private val strengths: List<PlannedSession> =
        (listOfNotNull(plan.leadIn) + plan.weeks).flatMap { it.sessions }.filter { it.kind == SessionKind.STRENGTH && it.skipReason == null }

    @Test fun everyPlannedStrengthSessionCarriesItsPrehabAndAConsistentLiftingWindow() {
        assertTrue(strengths.size > 10)
        for (s in strengths) {
            assertEquals(RacePlanner.PREHAB_MINUTES, s.launch.prehabMinutes, s.title)
            val window = GuidedPlan.liftingWindow(s)
            assertTrue(window >= GuidedPlan.MIN_LIFTING_WINDOW, "${s.date} $window")
            val finisher = GuidedPlan.finisherMinutes(s) ?: 0
            if (s.minutes >= 30) assertEquals(s.minutes - 10 - finisher, window, "${s.date} ${s.minutes}")
        }
    }

    @Test fun aThirtyFiveMinuteSessionIsATwentyFiveMinuteWindowPlusPrehab() {
        assertEquals(25, GuidedPlan.liftingWindow(35, 10))
        assertEquals(20, GuidedPlan.liftingWindow(30, 10))
        assertEquals(20, GuidedPlan.liftingWindow(25, 10), "never below the smallest worthwhile window")
        val kick = assertNotNull(plan.leadIn).sessions.first { it.kind == SessionKind.STRENGTH }
        assertEquals(GuidedPlan.liftingWindow(kick.minutes, 10), GuidedPlan.liftingWindow(kick))
        assertTrue(kick.detail.any { it.startsWith("Lifting (about ${GuidedPlan.liftingWindow(kick)} min)") }, kick.detail.toString())
    }

    @Test fun strengthTextPromisesOnlyWhatTheAppBuilds() {
        val banned = listOf("push", "pull", "press", "row", "2 x", "3 x", "banded", "calf raise, then")
        for (s in strengths) {
            val lifting = s.detail.first { it.startsWith("Lifting (about") }
            val low = lifting.lowercase()
            banned.forEach { assertFalse(it in low, "${s.title}: '$it' in $lifting") }
            assertTrue("the app builds it from your history" in low, lifting)
            val prehab = s.detail.first { it.startsWith("Hip-abductor") }
            assertTrue("about 10 min" in prehab && "using only the kit you own" in prehab, prehab)
            assertFalse("banded" in prehab.lowercase(), "band is optional")
        }
    }

    @Test fun theBuilderProducesTheLiftsTheTextNames() {
        // The generic text says squat, a hinge, calf raise and an upper lift when there is time: a 25 minute window has the first three.
        val p = GymBuilder.build(25, history, today)
        val ids = p.exercises.map { it.exercise.id }
        assertTrue("squat" in ids && "calf" in ids && ("rdl" in ids || "deadlift" in ids))
    }

    // ---------------------------------------------------------------- hip-only knee care

    private fun ids(s: KneeCareSession) = s.steps.mapNotNull { it.exerciseId }.toSet()

    @Test fun hipOnlyIsTenMinutesOfHipWorkAndAShortCooldown() {
        for (seed in 0L..5L) {
            val s = KneeCareRoutine.build(minutes = 10, hipOnly = true, seed = seed)
            assertTrue(s.hipOnly)
            assertEquals(600, s.totalSec, "seed $seed")
            assertEquals(10, s.minutes)
            assertEquals(0, s.calfSets)
            assertTrue(s.steps.none { it.kind == CoreKind.WARMUP }, "no warm-up repeated")
            assertTrue(s.steps.none { it.exerciseId == ExerciseLibrary.CALF_HOME_ID || it.exerciseId == KneeCareRoutine.CALF_WARMUP_ID }, "no calf block")
            assertTrue(s.steps.none { it.exerciseId in KneeCareLibrary.control.map { m -> m.id } }, "no knee control block")
            assertEquals(CoreKind.WORK, s.steps.first().kind)
            assertTrue(s.steps.any { it.kind == CoreKind.COOLDOWN })
            val hip = s.steps.filter { it.kind == CoreKind.WORK }.mapNotNull { it.exerciseId }.toSet()
            assertTrue(hip.size >= 3, "$hip")
            assertTrue(hip.all { it in setOf("kc_abduction", "kc_clamshell", "kc_side_plank", "kc_sl_bridge", "kc_lateral_walk", "kc_hip_hike", "kc_lateral_step") })
        }
    }

    @Test fun hipOnlyUsesOnlyTheKitHeOwns() {
        for (band in listOf(false, true)) for (step in listOf(false, true)) {
            val kit = KneeCareKit(hasBand = band, hasStep = step)
            for (seed in 0L..5L) {
                val s = KneeCareRoutine.build(minutes = 10, hipOnly = true, seed = seed, kit = kit)
                val got = ids(s)
                if (!band) assertFalse("kc_lateral_walk" in got, "band")
                if (!step) assertTrue("kc_hip_hike" !in got && "kc_step_down" !in got, "step")
                assertEquals(600, s.totalSec)
            }
        }
        val bare = KneeCareRoutine.build(minutes = 10, hipOnly = true, kit = KneeCareKit(hasBand = false, hasStep = false))
        assertTrue("kc_lateral_step" in ids(bare), "bodyweight lateral steps stand in for the banded walk")
        assertTrue(bare.gear.none { it.name.contains("band", ignoreCase = true) && !it.optional })
    }

    @Test fun hipOnlyEasesWithTheKneeAndTheNormalRoutineIsUnchanged() {
        val sore = KneeCareRoutine.build(minutes = 10, hipOnly = true, kneePain = 7)
        assertEquals(KneeGate.GENTLE_ONLY, sore.gate)
        assertTrue(sore.hipOnly && sore.calfSets == 0 && sore.totalSec == 600)
        val full = KneeCareRoutine.build(15)
        assertFalse(full.hipOnly)
        assertEquals(15 * 60, full.totalSec)
        assertTrue(full.calfSets > 0)
    }

    @Test fun hipOnlyRecordCountsAsKneeCare() {
        val s = KneeCareRoutine.build(minutes = 10, hipOnly = true)
        val rec = assertNotNull(KneeCareRecord.build("k", today, 10, 600, s.names, emptyList()))
        assertEquals(SessionType.CORE, rec.type)
        assertEquals("Knee care", rec.dayLabel)
        assertTrue(rec.isKneeCare)
        assertEquals(1, KneeCare.doneIn(listOf(rec), today.minusDays(1), today.plusDays(1)))
    }
}
