package com.calum.traininglog.core

import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreProgressTest {
    private val today = LocalDate.of(2026, 11, 20)
    private fun result(id: String = "front_plank", sec: Int = 30, level: Int = 0, completed: Boolean = true, skipped: Boolean = false) =
        CoreWorkResult(id, sec, level, completed, skipped)

    // ---- outcome

    @Test fun finishedAndFeelingFineProgresses() {
        assertEquals(CoreOutcome.PROGRESS, CoreProgression.outcome(true, false, Effort.JUST_RIGHT, Feeling.GOOD))
        assertEquals(CoreOutcome.PROGRESS, CoreProgression.outcome(true, false, Effort.TOO_EASY, Feeling.OKAY))
        assertEquals(CoreOutcome.PROGRESS, CoreProgression.outcome(true, false, null, null))
    }

    @Test fun skippedOrTooHardStepsBack() {
        assertEquals(CoreOutcome.BACK, CoreProgression.outcome(false, true, Effort.TOO_EASY, Feeling.GOOD))
        assertEquals(CoreOutcome.BACK, CoreProgression.outcome(true, false, Effort.TOO_HARD, Feeling.GOOD))
    }

    @Test fun roughDayOrEarlyEndJustRepeats() {
        assertEquals(CoreOutcome.HOLD, CoreProgression.outcome(true, false, null, Feeling.ROUGH))
        assertEquals(CoreOutcome.HOLD, CoreProgression.outcome(false, false, null, Feeling.GOOD))
    }

    // ---- target

    @Test fun plusFiveSecondsEachTime() {
        val t = CoreProgression.target("front_plank", CoreProgressEntry(30, 0, today, CoreOutcome.PROGRESS), capSec = 55)
        assertEquals(CoreProgression.Target(35, 0), t)
    }

    @Test fun holdKeepsAndBackSubtracts() {
        assertEquals(CoreProgression.Target(30, 0), CoreProgression.target("front_plank", CoreProgressEntry(30, 0, today, CoreOutcome.HOLD), 55))
        assertEquals(CoreProgression.Target(25, 0), CoreProgression.target("front_plank", CoreProgressEntry(30, 0, today, CoreOutcome.BACK), 55))
    }

    @Test fun neverAboveSixtySeconds() {
        val t = CoreProgression.target("bird_dog", CoreProgressEntry(60, 0, today, CoreOutcome.PROGRESS), capSec = 85)
        assertEquals(60, t.sec) // no harder variant: stays at the cap
        val u = CoreProgression.target("bird_dog", CoreProgressEntry(58, 0, today, CoreOutcome.PROGRESS), capSec = 85)
        assertTrue(u.sec <= 60)
    }

    @Test fun capOfTheCycleIsRespected() {
        val t = CoreProgression.target("bird_dog", CoreProgressEntry(35, 0, today, CoreOutcome.PROGRESS), capSec = 35)
        assertEquals(35, t.sec)
    }

    @Test fun atTheCapAHarderVariantTakesOverAtThirtySeconds() {
        val t = CoreProgression.target("front_plank", CoreProgressEntry(60, 0, today, CoreOutcome.PROGRESS), capSec = 85)
        assertEquals(CoreProgression.Target(30, 1), t)
        assertEquals("Long-lever plank", CoreVariants.variant("front_plank", 1)?.name)
    }

    @Test fun backAtTheFloorGoesToTheEasierVariant() {
        val t = CoreProgression.target("front_plank", CoreProgressEntry(20, 0, today, CoreOutcome.BACK), capSec = 85)
        assertEquals(CoreProgression.Target(45, -1), t)
    }

    @Test fun backAtTheFloorOfTheEasiestStaysPut() {
        val t = CoreProgression.target("front_plank", CoreProgressEntry(20, -1, today, CoreOutcome.BACK), capSec = 85)
        assertEquals(CoreProgression.Target(20, -1), t)
    }

    @Test fun kneeSensitiveNeverGoesToTheKneesDownVariant() {
        val t = CoreProgression.target("front_plank", CoreProgressEntry(20, 0, today, CoreOutcome.BACK), capSec = 85, kneeSensitive = true)
        assertEquals(CoreProgression.Target(20, 0), t)
    }

    // ---- entries

    @Test fun entryRecordsWhatWasDone() {
        val e = CoreProgression.entryAfter(null, result(sec = 35), Effort.JUST_RIGHT, Feeling.GOOD, today)
        assertEquals(CoreProgressEntry(35, 0, today, CoreOutcome.PROGRESS), e)
    }

    @Test fun aLongBreakRepeatsInsteadOfProgressing() {
        val prev = CoreProgressEntry(30, 0, today.minusDays(40), CoreOutcome.PROGRESS)
        assertEquals(CoreOutcome.HOLD, CoreProgression.entryAfter(prev, result(), null, Feeling.GOOD, today).outcome)
    }

    @Test fun updateLeavesPartlyDoneExercisesAlone() {
        val start = mapOf("front_plank" to CoreProgressEntry(30, 0, today.minusDays(3), CoreOutcome.PROGRESS))
        val out = CoreProgression.update(start, listOf(result(completed = false, skipped = false)), null, Feeling.GOOD, today)
        assertEquals(start, out)
    }

    @Test fun noteSaysWhatMoved() {
        val entry = CoreProgressEntry(30, 0, today, CoreOutcome.PROGRESS)
        val n = assertNotNull(CoreProgression.note("front_plank", entry, CoreProgression.target("front_plank", entry, 55)))
        assertEquals("Front plank 30 s -> 35 s", n.text)
        assertTrue(n.up)
        assertNull(CoreProgression.note("front_plank", entry.copy(outcome = CoreOutcome.HOLD), CoreProgression.target("front_plank", entry.copy(outcome = CoreOutcome.HOLD), 55)))
    }

    // ---- through the routine

    private fun plankIn(s: CoreSession): CoreStep? = s.steps.firstOrNull { it.exerciseId == "front_plank" }

    /** A seed whose 20-minute mix contains the front plank. */
    private fun seedWithPlank(): Long = (0L..400L).first { seed ->
        CoreRoutine.build(20, seed = seed).exerciseIds.contains("front_plank")
    }

    @Test fun buildAppliesTheTargetAndKeepsTheTotal() {
        val seed = seedWithPlank()
        val base = CoreRoutine.build(20, seed = seed)
        val baseSec = plankIn(base)!!.seconds
        val progress = mapOf("front_plank" to CoreProgressEntry(baseSec, 0, today.minusDays(3), CoreOutcome.PROGRESS))
        val s = CoreRoutine.build(20, seed = seed, progress = progress)
        assertEquals(base.exerciseIds, s.exerciseIds)
        assertTrue(plankIn(s)!!.seconds >= baseSec)
        assertEquals(20 * 60, s.totalSec)
        assertTrue(s.steps.all { it.seconds > 0 })
        if (plankIn(s)!!.seconds > baseSec) {
            assertEquals("Front plank $baseSec s -> ${plankIn(s)!!.seconds} s", s.progress.single { it.exerciseId == "front_plank" }.text)
        }
    }

    @Test fun buildWithAHarderVariantRenamesTheStep() {
        val seed = seedWithPlank()
        val progress = mapOf("front_plank" to CoreProgressEntry(60, 0, today.minusDays(3), CoreOutcome.PROGRESS))
        // Sessions whose cycle is at least 65 s can hold 60 s; otherwise the cap (cycle - 5) already triggers the harder variant.
        val s = CoreRoutine.build(20, seed = seed, progress = progress)
        val step = plankIn(s)!!
        assertEquals(1, step.level)
        assertEquals("Long-lever plank", step.name)
        assertEquals(20 * 60, s.totalSec)
    }

    @Test fun withoutProgressTheRoutineIsUnchanged() {
        assertEquals(CoreRoutine.build(20, seed = 4), CoreRoutine.build(20, seed = 4, progress = emptyMap()))
        assertTrue(CoreRoutine.build(20, seed = 4).progress.isEmpty())
    }

    @Test fun savedNoteWithAVariantNameStillReadsBack() {
        assertEquals(listOf("front_plank", "bird_dog"), CoreLibrary.idsFromNote("Core: Long-lever plank, Bird dog (felt just right)"))
    }

    @Test fun workResultsFromARun() {
        val seed = seedWithPlank()
        val s = CoreRoutine.build(20, seed = seed)
        // Finished, nothing skipped: every exercise completed.
        val done = CoreRun(s.steps, index = s.steps.size)
        val results = done.workResults()
        assertEquals(s.exerciseIds.toSet(), results.map { it.exerciseId }.toSet())
        assertTrue(results.all { it.completed && !it.skipped })

        // Skip the first work step: that exercise is flagged, others are not.
        val firstWork = s.steps.indexOfFirst { it.kind == CoreKind.WORK }
        val skippedRun = done.copy(skipped = setOf(firstWork))
        val first = skippedRun.workResults().single { it.exerciseId == s.steps[firstWork].exerciseId }
        assertTrue(first.skipped && !first.completed)

        // Ended early: exercises not reached are absent.
        val early = CoreRun(s.steps, index = firstWork + 1)
        assertEquals(1, early.workResults().size)
    }

    @Test fun twoSessionsInARowProgress() {
        val seed = seedWithPlank()
        var map = emptyMap<String, CoreProgressEntry>()
        var date = today
        var lastSec = 0
        repeat(3) { i ->
            val s = CoreRoutine.build(20, seed = seed, progress = map)
            val sec = plankIn(s)!!.seconds
            if (i > 0) assertTrue(sec >= lastSec, "session $i: $sec vs $lastSec")
            lastSec = sec
            map = CoreProgression.update(map, CoreRun(s.steps, index = s.steps.size).workResults(), Effort.JUST_RIGHT, Feeling.GOOD, date)
            date = date.plusDays(3)
        }
        assertTrue(lastSec > CoreRoutine.build(20, seed = seed).steps.first { it.exerciseId == "front_plank" }.seconds - 1)
        assertNotNull(map["front_plank"])
    }

    // ---- storage

    @Test fun oldProfileJsonDecodesAndNewOneRoundTrips() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = json.decodeFromString<Profile>("""{"headlineKg":90.0}""")
        assertTrue(old.coreProgress.isEmpty())
        assertTrue(old.settings.guidedGym)
        val p = Profile(coreProgress = mapOf("front_plank" to CoreProgressEntry(35, 1, today, CoreOutcome.PROGRESS)))
        assertEquals(p, json.decodeFromString<Profile>(json.encodeToString(Profile.serializer(), p)))
    }

    @Test fun settingsWithoutGuidedGymDecodeAsOn() {
        val json = Json { ignoreUnknownKeys = true }
        assertTrue(json.decodeFromString<UserSettings>("""{"gymTarget":3}""").guidedGym)
        assertTrue(!json.decodeFromString<UserSettings>("""{"guidedGym":false}""").guidedGym)
        assertTrue(!UserSettings(guidedGym = false).sanitized().guidedGym)
    }
}
