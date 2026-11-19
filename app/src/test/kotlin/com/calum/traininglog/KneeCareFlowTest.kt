package com.calum.traininglog

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.CalfMode
import com.calum.traininglog.core.CoreKind
import com.calum.traininglog.core.Effort
import com.calum.traininglog.core.KneeCareKit
import com.calum.traininglog.core.ExerciseLibrary
import com.calum.traininglog.core.ExerciseLog
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.GymBuilder
import com.calum.traininglog.core.KneeCare
import com.calum.traininglog.core.KneeCareRoutine
import com.calum.traininglog.core.LoggedSet
import com.calum.traininglog.core.RacePlanner
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.WeekPlanner
import com.calum.traininglog.core.DayStatus
import com.calum.traininglog.core.isKneeCare
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Knee care end to end: a whole routine on a fake clock with the real ViewModel and disk. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class KneeCareFlowTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>() as Application
    private val today = LocalDate.of(2026, 11, 25) // a Wednesday
    private fun newVm(day: LocalDate = today) = AppViewModel(app, clock = { day })
    private fun onDisk() = Store(app).load()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before fun setUp() { TreadmillEngine.stop() }
    @After fun tearDown() { TreadmillEngine.stop() }

    /** Plays the session to its end: timed steps tick by seconds, rep steps take [repSeconds] and then "Done, next". */
    private fun AppViewModel.runToEnd(repSeconds: Int = 40, beforeDone: (Int) -> Unit = {}) {
        var n = 0
        while (kneeCare.ui.value.phase == KneePhaseRunning) {
            val u = kneeCare.ui.value
            val run = u.run!!
            val s = u.session!!
            if (s.isRepStep(run.index)) {
                repeat(repSeconds) { kneeCare.tick(1000) }
                beforeDone(run.index)
                kneeCare.next()
            } else kneeCare.tick(1000)
            if (n++ > 20_000) error("never finished")
        }
        idle()
    }

    private val KneePhaseRunning = KneeCarePhase.RUNNING

    @Test fun wholeRoutineAutosavesAndTheRatingEditsWithoutDuplicates() {
        val vm = newVm()
        vm.launchKneeCare()
        assertEquals(Screen.KneeCare, vm.screen.value)
        assertEquals(KneeCarePhase.SETUP, vm.kneeCare.ui.value.phase)
        val session = vm.kneeCare.ui.value.session!!
        assertEquals(15 * 60, session.totalSec)
        assertEquals(0.0, session.calfStartKg, "level 1 is bodyweight")
        assertEquals(1, session.calfLevel)
        vm.kneeCare.start(); vm.kneeCare.ready()
        // Set the load on the first calf set: it carries to the sets still to come.
        vm.kneeCare.setCalfWeight(0, 22.5)
        vm.runToEnd(beforeDone = { i ->
            val set = session.calfSetAt(i)
            if (set == 2) vm.kneeCare.setCalf(2) { it.copy(reps = 7, rir = 1) }
        })
        assertEquals(KneeCarePhase.DONE, vm.kneeCare.ui.value.phase)

        // Saved the moment it ended, before any card was touched.
        val saved = onDisk().records.single()
        assertTrue(saved.isKneeCare)
        assertEquals(SessionType.CORE, saved.type)
        assertEquals("Knee care", saved.dayLabel)
        assertTrue(saved.note!!.startsWith("Knee care: "))
        val calf = saved.exercises.single()
        assertEquals(ExerciseLibrary.CALF_HOME_ID, calf.exerciseId)
        assertEquals(3, calf.sets.size)
        assertTrue(calf.sets.all { it.weightKg == 22.5 })
        assertEquals(listOf(15, 15, 7), calf.sets.map { it.reps })
        assertTrue(calf.sets.all { it.level == 1 })
        assertEquals(1, calf.sets.last().rir)
        assertTrue(saved.minutesActual >= 12)

        // The finish card only edits that record.
        vm.kneeCare.setFeeling(Feeling.OKAY)
        vm.kneeCare.setKneeAfter(2)
        vm.saveKneeCare()
        idle()
        val after = onDisk().records.single()
        assertEquals(saved.id, after.id)
        assertEquals(Feeling.OKAY, after.feeling)
        assertEquals(2, after.kneePain)
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(KneeCarePhase.SETUP, vm.kneeCare.ui.value.phase)
    }

    @Test fun calfHistoryFeedsTheNextLevelAndLeavesTheGymCalfAlone() {
        val vm = newVm()
        val gymCalf = SessionRecord(
            "gym-1", today.minusDays(4), SessionType.GYM, 60, 55, Feeling.GOOD,
            exercises = listOf(ExerciseLog("calf", (1..3).map { LoggedSet(60.0, 12, 12, 2) })),
        )
        vm.replaceData(vm.data.value.copy(records = listOf(gymCalf)))
        val gymBefore = GymBuilder.plan(ExerciseLibrary.calf, vm.data.value.records, today, deload = false).prescription

        vm.launchKneeCare()
        // Gym calf history of 40 kg or more starts him single-leg with the kettlebell (level 3).
        assertEquals(3, vm.kneeCare.ui.value.session!!.calfLevel)
        assertEquals(6.0, vm.kneeCare.ui.value.session!!.calfStartKg)
        vm.kneeCare.start(); vm.kneeCare.ready()
        vm.runToEnd(beforeDone = { i -> vm.kneeCare.ui.value.session!!.calfSetAt(i)?.let { n ->
            vm.kneeCare.setCalf(n) { it.copy(reps = 15) }
            vm.kneeCare.rate(n, Effort.JUST_RIGHT)
        } })
        vm.saveKneeCare()
        idle()

        val records = onDisk().records
        assertEquals(2, records.size)
        assertEquals(listOf(2, 2, 2), records.first { it.isKneeCare }.exercises.single().sets.map { it.rir })
        // Top reps with 2 in the tank on every set: next time is level 4 at the bottom of its range, with the note.
        val vm2 = newVm(today.plusDays(2))
        vm2.launchKneeCare()
        val s = vm2.kneeCare.ui.value.session!!
        assertEquals(4, s.calfLevel)
        assertTrue(s.levelMoved)
        assertEquals(10, s.calfReps)
        assertEquals(6.0, s.calfStartKg)
        assertEquals("Last time (level 3): 6 kg x 15, 15, 15", s.lastTime)
        assertEquals(CalfMode.HEAVY, vm2.kneeCare.ui.value.mode)
        val gymAfter = GymBuilder.plan(ExerciseLibrary.calf, records, today.plusDays(2), deload = false).prescription
        assertEquals(gymBefore.weightKg, gymAfter.weightKg)
        assertEquals(gymBefore.reps, gymAfter.reps)
        assertTrue(s.steps.filter { it.kind == CoreKind.WORK && it.exerciseId == ExerciseLibrary.CALF_HOME_ID }.all { it.reps == 10 })
        vm2.kneeCare.reset()
    }

    @Test fun lastModeIsRememberedAndTheToggleRebuilds() {
        val vm = newVm()
        vm.launchKneeCare()
        vm.kneeCareSetup(mode = CalfMode.STAMINA)
        assertEquals(CalfMode.STAMINA, vm.kneeCare.ui.value.mode)
        assertEquals(KneeCareRoutine.build(15, calfMode = CalfMode.STAMINA).calfMode, vm.kneeCare.ui.value.session!!.calfMode)
        assertEquals(18, vm.kneeCare.ui.value.session!!.calfReps)
        vm.kneeCare.start(); vm.kneeCare.ready()
        vm.kneeCare.setCalfWeight(0, 8.0)
        vm.runToEnd(repSeconds = 60)
        vm.saveKneeCare()
        idle()
        val vm2 = newVm(today.plusDays(1))
        vm2.launchKneeCare()
        assertEquals(CalfMode.STAMINA, vm2.kneeCare.ui.value.mode, "remembers the last mode")
        assertEquals(0.0, vm2.kneeCare.ui.value.session!!.calfStartKg)
        vm2.kneeCare.reset()
    }

    @Test fun justCalfRaisesRunsAndLogsOnlyCalfWork() {
        val vm = newVm()
        vm.launchKneeCare(calfOnly = true)
        val s = vm.kneeCare.ui.value.session!!
        assertTrue(s.calfOnly)
        assertEquals(3, s.calfSets)
        vm.kneeCare.start(); vm.kneeCare.ready()
        vm.kneeCare.setCalfWeight(0, 30.0)
        vm.runToEnd(repSeconds = 50)
        vm.saveKneeCare()
        idle()
        val r = onDisk().records.single()
        assertEquals(3, r.exercises.single().sets.size)
        assertEquals(30.0, r.exercises.single().sets.first().weightKg)
        assertTrue(r.isKneeCare)
        assertTrue(r.note!!.contains("Calf raise"), r.note)
    }

    @Test fun skippingASetDoesNotLogIt() {
        val vm = newVm()
        vm.launchKneeCare(calfOnly = true)
        vm.kneeCare.start(); vm.kneeCare.ready()
        vm.kneeCare.setCalfWeight(0, 20.0)
        var skipped = false
        vm.runToEnd(beforeDone = { })
        // (run to the end with every set done first, then check a skip separately)
        vm.saveKneeCare(); idle()
        assertEquals(3, onDisk().records.single().exercises.single().sets.size)

        val vm2 = newVm(today.plusDays(1))
        vm2.launchKneeCare(calfOnly = true)
        vm2.kneeCare.start(); vm2.kneeCare.ready()
        // Past the warm-up set and its rest, then skip the first working set.
        while (!vm2.kneeCare.ui.value.let { u -> u.session!!.calfSetAt(u.run!!.index) == 0 && u.session!!.isRepStep(u.run!!.index) }) {
            val u = vm2.kneeCare.ui.value
            if (u.session!!.isRepStep(u.run!!.index)) vm2.kneeCare.next() else vm2.kneeCare.tick(1000)
        }
        vm2.kneeCare.skip(); skipped = true
        vm2.runToEnd()
        assertTrue(skipped)
        assertEquals(2, vm2.kneeCare.doneCalfSets(vm2.kneeCare.ui.value).size)
        vm2.kneeCare.reset()
    }

    @Test fun underAMinuteLogsNothing() {
        val vm = newVm()
        vm.launchKneeCare()
        vm.kneeCare.start(); vm.kneeCare.ready()
        repeat(10) { vm.kneeCare.tick(1000) }
        vm.kneeCare.end()
        vm.saveKneeCare()
        idle()
        assertEquals(emptyList(), onDisk().records)
    }

    @Test fun homeLaunchAndPlannedLaunchOpenTheScreen() {
        val vm = newVm()
        vm.launchPlanned(KneeCare.session(today))
        assertEquals(Screen.KneeCare, vm.screen.value)
        vm.kneeCare.reset()
        vm.launchKneeCare(calfOnly = true)
        assertTrue(vm.kneeCare.ui.value.calfOnly)
        vm.kneeCare.reset()
    }

    @Test fun aKneeScoreOfFiveDropsTheStepDownAndGentleOnlyAtSeven() {
        val vm = newVm()
        vm.launchKneeCare()
        vm.kneeCareSetup(knee = 5)
        assertNotNull(vm.kneeCare.ui.value.session!!.physioLine)
        assertTrue(vm.kneeCare.ui.value.session!!.steps.none { it.exerciseId == "kc_step_down" })
        vm.kneeCareSetup(knee = 8)
        assertEquals(0, vm.kneeCare.ui.value.session!!.calfSets)
        vm.kneeCareSetup(clearKnee = true)
        assertEquals(15 * 60, vm.kneeCare.ui.value.session!!.totalSec)
        vm.kneeCare.reset()
    }

    @Test fun kneeCareOnARestDayDoesNotBreakRestDayLogic() {
        val tue = LocalDate.of(2026, 11, 24) // a default rest day
        val vm = newVm(tue)
        vm.launchKneeCare()
        vm.kneeCare.start(); vm.kneeCare.ready()
        vm.runToEnd()
        vm.saveKneeCare(); idle()
        val d = onDisk()
        assertEquals(1, d.records.size)
        val view = WeekPlanner.plan(d.records, d.profile, tue)
        assertEquals(DayStatus.REST, view.days.first { it.date == tue }.status)
        assertTrue(com.calum.traininglog.core.RestDays.isRestDay(d.profile, tue))
        val plan = RacePlanner.build(d.profile.settings.racePlan, d.records, tue, d.profile)
        assertEquals(1, RacePlanner.thisWeek(plan, tue)!!.kneeCare.done)
    }

    // ---------------------------------------------------------------------------------- home kit, gear and feedback

    @Test fun theChecklistAndReadyStepComeBeforeStepOneAndNothingRunsUntilReady() {
        val vm = newVm()
        vm.launchKneeCare()
        val ui = vm.kneeCare.ui.value
        val s = ui.session!!
        // Calum's kit: 6 kg kettlebell, chair, mat; no step, no band.
        val names = s.gear.map { it.name }
        assertTrue("Mat" in names && "Kettlebell (6 kg)" in names && "Wall or chair for balance" in names, "$names")
        assertTrue(names.none { it == "Low step" || it == "Light band" })
        assertTrue(s.steps.none { it.exerciseId == "kc_step_down" })
        // Ticking is memory only.
        vm.kneeCare.toggleGear("Mat")
        assertEquals(setOf("Mat"), vm.kneeCare.ui.value.checked)
        vm.kneeCare.start()
        assertEquals(KneeCarePhase.GET_READY, vm.kneeCare.ui.value.phase)
        repeat(30) { vm.kneeCare.tick(1000) }
        assertEquals(0, vm.kneeCare.ui.value.run!!.index)
        assertEquals(0L, vm.kneeCare.ui.value.run!!.elapsedMs, "the warm-up has not started")
        vm.kneeCare.backToSetup()
        assertEquals(KneeCarePhase.SETUP, vm.kneeCare.ui.value.phase)
        vm.kneeCare.start()
        vm.kneeCare.ready()
        assertEquals(KneeCarePhase.RUNNING, vm.kneeCare.ui.value.phase)
        vm.kneeCare.tick(2000)
        assertEquals(2000L, vm.kneeCare.ui.value.run!!.elapsedMs)
        vm.kneeCare.reset()
    }

    @Test fun editingTheHomeKitRebuildsTheRoutineAndNeverUsesMissingGear() {
        val vm = newVm()
        vm.launchKneeCare()
        vm.setKneeKit(KneeCareKit(kettlebellKg = null, hasChair = false))
        idle()
        val s = vm.kneeCare.ui.value.session!!
        assertTrue(s.gear.none { it.name.startsWith("Kettlebell") })
        assertTrue(s.steps.none { it.exerciseId == "kc_pistol" || it.exerciseId == "kc_step_down" })
        assertEquals(KneeCareKit(kettlebellKg = null, hasChair = false), onDisk().profile.settings.kneeKit, "the kit is saved")
        assertEquals(0.0, s.calfStartKg)
        vm.setKneeKit(KneeCareKit(hasStep = true).withKettlebellStep(1))
        assertEquals(7.0, vm.kneeCare.ui.value.session!!.kit.kettlebellKg)
        assertTrue(vm.kneeCare.ui.value.session!!.gear.any { it.name == "Kettlebell (7 kg)" })
        vm.kneeCare.reset()
    }

    @Test fun tooEasyUpgradesTheNextSetShortensTheRestAndMovesTheExerciseUpNextSession() {
        val vm = newVm()
        vm.launchKneeCare()
        val session = vm.kneeCare.ui.value.session!!
        assertEquals(1, session.calfLevel)
        vm.kneeCare.start(); vm.kneeCare.ready()
        val seen = mutableMapOf<Int, Pair<Int, Int>>()
        vm.runToEnd(repSeconds = 40, beforeDone = { i ->
            val n = session.calfSetAt(i)!!
            val u = vm.kneeCare.ui.value
            seen[n] = u.calf[n].level to u.calf[n].targetReps
            if (n == 0) {
                vm.kneeCare.rate(0, Effort.TOO_EASY)
                // The rest after it is now 30 s, the original was longer.
                val restIdx = i + 1
                assertEquals(30, vm.kneeCare.ui.value.run!!.steps[restIdx].seconds)
                assertTrue(session.steps[restIdx].seconds > 30)
                assertEquals(2 to 12, vm.kneeCare.ui.value.calf[1].level to vm.kneeCare.ui.value.calf[1].targetReps)
            }
            if (n == 1) vm.kneeCare.rate(1, Effort.JUST_RIGHT)
        })
        // Set 0 at level 1 (two legs, 15), sets 1 and 2 at level 2 (single leg, 12).
        assertEquals(mapOf(0 to (1 to 15), 1 to (2 to 12), 2 to (2 to 12)), seen)
        val saved = onDisk().records.single()
        val sets = saved.exercises.single().sets
        assertEquals(listOf(1, 2, 2), sets.map { it.level })
        assertEquals(listOf(3, 2, null), sets.map { it.rir })
        assertEquals(listOf(15, 12, 12), sets.map { it.targetReps })
        // Autosaved once; the finish card only edits it.
        vm.saveKneeCare(); idle()
        assertEquals(1, onDisk().records.size)
        // Next session: the exercise moved up a level (a too easy tap at level 1 means 2 at least).
        val vm2 = newVm(today.plusDays(2))
        vm2.launchKneeCare()
        assertEquals(2, vm2.kneeCare.ui.value.session!!.calfLevel)
        assertEquals(13, vm2.kneeCare.ui.value.session!!.calfReps, "level 2 reached 12 on both sets: one more rep")
        vm2.kneeCare.reset()
    }

    @Test fun twoTooHardSetsInARowDropALevelAndALittleMoreRest() {
        val vm = newVm()
        vm.launchKneeCare(); vm.kneeCareSetup(levelStep = 3)
        val session = vm.kneeCare.ui.value.session!!
        assertEquals(4, session.calfLevel)
        vm.kneeCare.start(); vm.kneeCare.ready()
        vm.runToEnd(beforeDone = { i ->
            val n = session.calfSetAt(i)!!
            if (n <= 1) {
                vm.kneeCare.rate(n, Effort.TOO_HARD)
                if (n == 0) assertEquals(session.steps[i + 1].seconds + 15, vm.kneeCare.ui.value.run!!.steps[i + 1].seconds)
            }
            if (n == 2) assertEquals(3, vm.kneeCare.ui.value.calf[2].level, "dropped one level after two in a row")
        })
        vm.saveKneeCare(); idle()
        val vm2 = newVm(today.plusDays(2))
        vm2.launchKneeCare()
        assertEquals(3, vm2.kneeCare.ui.value.session!!.calfLevel)
        vm2.kneeCare.reset()
    }

    @Test fun skippedRestsAndUnratedSetsLeaveTheRecordAndProgressionIntact() {
        val vm = newVm()
        vm.launchKneeCare()
        vm.kneeCare.start(); vm.kneeCare.ready()
        var guard = 0
        while (vm.kneeCare.ui.value.phase == KneeCarePhase.RUNNING) {
            val u = vm.kneeCare.ui.value
            val run = u.run!!
            val s = u.session!!
            val st = run.current!!
            when {
                s.isRepStep(run.index) -> { repeat(30) { vm.kneeCare.tick(1000) }; vm.kneeCare.next() }
                st.kind == CoreKind.REST -> vm.kneeCare.skip()
                else -> vm.kneeCare.tick(1000)
            }
            if (guard++ > 20_000) error("never finished")
        }
        idle()
        val saved = onDisk().records.single()
        val sets = saved.exercises.single().sets
        assertEquals(3, sets.size)
        assertTrue(sets.all { it.rir == null && it.level == 1 })
        assertTrue(saved.minutesActual >= 1)
        // No feedback never moves a level, but the next target adds a rep only when every set was met.
        vm.saveKneeCare(); idle()
        val vm2 = newVm(today.plusDays(1))
        vm2.launchKneeCare()
        assertEquals(1, vm2.kneeCare.ui.value.session!!.calfLevel)
        vm2.kneeCare.reset()
    }
}
