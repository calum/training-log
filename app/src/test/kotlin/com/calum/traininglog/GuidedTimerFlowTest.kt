package com.calum.traininglog

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.GuidedKind
import com.calum.traininglog.core.HyroxStation
import com.calum.traininglog.core.Intensity
import com.calum.traininglog.core.IntensityBalance
import com.calum.traininglog.core.LaunchHint
import com.calum.traininglog.core.Modality
import com.calum.traininglog.core.PlannedSession
import com.calum.traininglog.core.RacePlanSettings
import com.calum.traininglog.core.RacePlanner
import com.calum.traininglog.core.SessionKind
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.TimeSplit
import com.calum.traininglog.core.TreadmillStyle
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

/** Launching every kind of planned session and tracking a guided one end to end (fake clock, real ViewModel and disk). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GuidedTimerFlowTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>() as Application
    private val today = LocalDate.of(2026, 11, 25)
    private fun newVm() = AppViewModel(app, clock = { today })
    private fun onDisk() = Store(app).load()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before fun setUp() { TreadmillEngine.stop() }
    @After fun tearDown() { TreadmillEngine.stop() }

    private fun session(
        kind: SessionKind, minutes: Int, intensity: Intensity = Intensity.EASY, hard: Int = 0, rpe: IntRange = 2..4,
        mods: List<Modality> = listOf(Modality.ROW, Modality.SKIERG, Modality.TREADMILL_WALK, Modality.BIKE),
        detail: List<String> = emptyList(), launch: LaunchHint = LaunchHint(SessionType.TREADMILL, TreadmillStyle.STEADY, emptySet(), minutes),
        includesRunning: Boolean = false,
    ) = PlannedSession(today, kind, "Planned $kind", minutes, intensity, hard, rpe, mods, detail, "Why.", launch, includesRunning = includesRunning)

    private fun easy(minutes: Int = 45) = session(SessionKind.EASY, minutes, detail = listOf("Easy aerobic work, RPE 4 or less."))

    private fun vo2(minutes: Int = 40) = session(
        SessionKind.KEY, minutes, Intensity.HARD, 16, 8..9,
        detail = listOf("Warm up 10 minutes easy (RPE 3).", "4 x 4 minutes at RPE 8-9, 3 minutes easy between.", "Cool down 5 minutes easy."),
    )

    private fun runWalk(minutes: Int = 40) = session(
        SessionKind.EASY, minutes, mods = listOf(Modality.RUN_WALK, Modality.TREADMILL_WALK, Modality.BIKE), includesRunning = true,
        detail = listOf("Walk 5 minutes warm-up at 1% incline.", "Run-walk: 1 min run / 2 min walk x 8 at 1% incline, running at a pace where you could chat (RPE 3-4)."),
    )

    /** Plays the running session to its end on a fake clock (1 s ticks, as many as it takes). */
    private fun AppViewModel.runToEnd(skipFrom: Int = Int.MAX_VALUE) {
        var n = 0
        while (guided.ui.value.phase == GuidedPhase.RUNNING) { guided.tick(1000); n++; if (n > 10_000) error("never finished") }
        idle()
    }

    // ---------------------------------------------------------------------------------------- routing

    @Test fun everyKindOfPlannedSessionOpensItsFlow() {
        val vm = newVm()
        // Rower is the first option: guided timer, modality preselected.
        vm.launchPlanned(easy())
        assertEquals(Screen.GuidedTimer(easy()), vm.screen.value)
        assertEquals(Modality.ROW, vm.guided.ui.value.modality)
        assertEquals(45 * 60, vm.guided.ui.value.plan!!.totalSec)
        vm.guided.reset()

        // The user picked the SkiErg or the bike chip.
        val ski = easy(); vm.launchPlanned(ski, Modality.SKIERG)
        assertEquals(Modality.SKIERG, vm.guided.ui.value.modality)
        vm.launchPlanned(ski, Modality.BIKE)
        assertEquals(Modality.BIKE, vm.guided.ui.value.modality)
        vm.guided.reset()

        // Easy treadmill walk: steady incline walk at the calibrated setting, planned minutes.
        val walk = session(SessionKind.EASY, 40, mods = listOf(Modality.TREADMILL_WALK, Modality.ROW))
        vm.launchPlanned(walk)
        assertEquals(Screen.TreadmillSetup, vm.screen.value)
        val cal = vm.data.value.profile.treadmill
        val plan = vm.pendingTreadmill.value!!
        assertEquals(40 * 60, plan.totalSec)
        assertEquals(cal.speedKmh, plan.segments.single { it.label == "Steady" }.speedKmh)
        assertEquals(cal.inclinePct, plan.segments.single { it.label == "Steady" }.inclinePct)

        // Intervals on the treadmill keep their structure.
        vm.launchPlanned(vo2(), Modality.TREADMILL_WALK)
        assertEquals(Screen.TreadmillSetup, vm.screen.value)
        assertEquals(4, vm.pendingTreadmill.value!!.segments.count { it.label.startsWith("Hard") })

        // Run-walk.
        vm.launchPlanned(runWalk())
        assertEquals(Screen.TreadmillSetup, vm.screen.value)
        val rw = vm.pendingTreadmill.value!!
        assertEquals(40 * 60, rw.totalSec)
        assertEquals(8, rw.segments.count { it.label == "Run" })

        // Hyrox stations: the session with the focus and the planned minutes preselected.
        val focus = setOf(HyroxStation.SKIERG, HyroxStation.SLED_PUSH, HyroxStation.ROW, HyroxStation.WALL_BALLS)
        val sim = session(
            SessionKind.SIM, 60, Intensity.HARD, 27, 7..8, mods = listOf(Modality.HYROX_STATIONS, Modality.ROW),
            launch = LaunchHint(SessionType.HYROX, hyroxFocus = focus, minutes = 60),
        )
        // A simulation-kind session opens the true race-order simulation (a full one at race loads without a preset).
        vm.launchPlanned(sim)
        assertEquals(Screen.HyroxSim(null), vm.screen.value)
        assertEquals(com.calum.traininglog.core.SimFormat.FULL, vm.sim.ui.value.format)
        vm.sim.reset()
        // Any other Hyrox-station session still opens the normal guided session with its focus and minutes.
        val sharpen = sim.copy(kind = SessionKind.KEY)
        vm.launchPlanned(sharpen)
        assertEquals(Screen.HyroxSession(focus), vm.screen.value)
        assertEquals(60, vm.hyrox.ui.value.minutes)
        assertEquals(focus, vm.hyrox.ui.value.focus)
        vm.hyrox.reset()
        // ... or the same session on the rower instead.
        vm.launchPlanned(sim, Modality.ROW)
        assertTrue(vm.screen.value is Screen.GuidedTimer)
        assertTrue(vm.guided.ui.value.plan!!.workSteps >= 2)
        vm.guided.reset()

        // Strength: the gym builder, with the finisher shown.
        val strength = session(
            SessionKind.STRENGTH, 60, Intensity.STRENGTH, 0, 6..8, mods = listOf(Modality.GYM),
            detail = listOf("Warm up 5 minutes.", "Hyrox station finisher (about 10 min) at this phase's ladder level:", "  Sled Push, level 2: 4 x 12 m", "  Wall Balls, level 2: 4 x 10 reps"),
            launch = LaunchHint(SessionType.GYM, hyroxFocus = setOf(HyroxStation.SLED_PUSH, HyroxStation.WALL_BALLS), minutes = 60),
        )
        vm.launchPlanned(strength)
        assertEquals(Screen.Gym, vm.screen.value)
        assertNotNull(vm.gym.value)
        assertEquals(60, vm.inputs.value.minutes)
        assertEquals("Sled Push, level 2: 4 x 12 m", vm.gym.value!!.finisher[1])
        vm.go(Screen.Home)

        // Rest goes home.
        vm.launchPlanned(session(SessionKind.REST, 0, mods = listOf(Modality.REST)))
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test fun launchingTheRealPlansSessionsReachesARoute() {
        val vm = newVm()
        val plan = RacePlanner.build(RacePlanSettings(), emptyList(), LocalDate.of(2026, 11, 19))
        val seen = mutableSetOf<String>()
        for (w in plan.weeks + listOfNotNull(plan.leadIn)) for (s in w.sessions) {
            vm.hyrox.reset(); vm.guided.reset(); vm.sim.reset(); TreadmillEngine.stop()
            vm.go(Screen.Home)
            vm.launchPlanned(s)
            val r = vm.screen.value
            seen += r::class.simpleName!!
            when (s.kind) {
                SessionKind.REST -> assertEquals(Screen.Home, r)
                SessionKind.STRENGTH -> assertEquals(Screen.Gym, r)
                else -> assertTrue(r is Screen.GuidedTimer || r is Screen.HyroxSession || r is Screen.HyroxSim || r == Screen.TreadmillSetup, "${s.title}: $r")
            }
        }
        assertTrue(seen.containsAll(listOf("GuidedTimer", "Gym")), "$seen")
    }

    // ---------------------------------------------------------------------------------------- choosing the equipment

    @Test fun theEquipmentYouPickIsUsedAndRememberedForTheNextEasySession() {
        val vm = newVm()
        val s = easy(30)
        vm.launchPlanned(s)
        assertEquals(Modality.ROW, vm.guided.ui.value.modality) // nothing remembered yet: the plan's first option
        vm.chooseGuidedModality(s, Modality.BIKE)
        assertEquals(Modality.BIKE, vm.guided.ui.value.modality)
        vm.guided.reset()
        vm.launchPlanned(easy(30))
        assertEquals(Modality.BIKE, vm.guided.ui.value.modality) // remembered

        // The treadmill opens its own flow, and is remembered too.
        vm.guided.reset()
        vm.chooseGuidedModality(easy(30), Modality.TREADMILL_WALK)
        assertEquals(Screen.TreadmillSetup, vm.screen.value)
        vm.go(Screen.Home)
        vm.launchPlanned(easy(30))
        assertEquals(Screen.TreadmillSetup, vm.screen.value)
    }

    @Test fun aRememberedChoiceNeverChangesARunWalkOrAHardSession() {
        val vm = newVm()
        vm.launchPlanned(easy(), Modality.BIKE)
        vm.guided.reset()
        vm.launchPlanned(runWalk()) // running stays gated by the knee, whatever was picked before
        assertEquals(Screen.TreadmillSetup, vm.screen.value)
        vm.go(Screen.Home)
        vm.launchPlanned(vo2())
        assertEquals(Modality.ROW, vm.guided.ui.value.modality)
    }

    @Test fun theEquipmentCanBeCorrectedOnTheFinishCard() {
        val vm = newVm()
        vm.launchPlanned(easy(20))
        vm.guided.start()
        vm.runToEnd()
        assertEquals(HyroxStation.ROW.id, onDisk().records.single().stations.single().stationId)
        vm.guided.correctModality(Modality.SKIERG)
        vm.saveGuided()
        val saved = onDisk().records.single() // rewritten, not duplicated
        assertEquals(HyroxStation.SKIERG.id, saved.stations.single().stationId)
        assertTrue("skierg" in saved.note!!.lowercase(), saved.note)
    }

    // ---------------------------------------------------------------------------------------- guided easy

    @Test fun wholeEasySessionIsSavedWithTheRightStationLogAndCountsAsEasy() {
        val vm = newVm()
        vm.launchPlanned(easy(45))
        vm.guided.start()
        vm.runToEnd()
        assertEquals(GuidedPhase.DONE, vm.guided.ui.value.phase)

        // Saved the moment it ended, before the rating card.
        val saved = onDisk().records.single()
        assertEquals(SessionType.HYROX, saved.type)
        assertEquals(45, saved.minutesActual)
        assertEquals(45, saved.minutesAvailable)
        val st = saved.stations.single()
        assertEquals(HyroxStation.ROW.id, st.stationId)
        assertEquals(2700, st.seconds)
        assertEquals(3, st.rpe) // preselected with the target
        assertNull(st.distanceM)
        assertEquals("Race plan: easy 45 min, rower", saved.note)
        assertEquals(TimeSplit(45, 0, 0), IntensityBalance.classify(saved))
        assertNotNull(vm.celebration.value)

        // The rating card edits it: no duplicate.
        vm.guided.adjustDistance(100) // blank -> a sensible start
        val start = vm.guided.ui.value.distanceM!!
        assertTrue(start >= 1000, "$start")
        vm.guided.adjustDistance(500)
        vm.guided.setRpe(4); vm.guided.setFeeling(Feeling.OKAY)
        vm.saveGuided()
        val d = onDisk()
        assertEquals(1, d.records.size, "no duplicate record when the rating is added after the autosave")
        val r = d.records.single()
        assertEquals(saved.id, r.id)
        assertEquals(start + 500, r.stations.single().distanceM)
        assertEquals(4, r.stations.single().rpe)
        assertEquals(Feeling.OKAY, r.feeling)
        assertEquals(TimeSplit(45, 0, 0), IntensityBalance.classify(r))
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(GuidedPhase.SETUP, vm.guided.ui.value.phase)
    }

    @Test fun savingWithoutAnyAutosaveYetStillWritesOneRecord() {
        val vm = newVm()
        vm.launchPlanned(easy(20), Modality.SKIERG)
        vm.guided.start()
        repeat(5 * 60) { vm.guided.tick(1000) }
        vm.guided.end()
        // Rating tapped straight away (the collector may not have run yet): still exactly one record.
        vm.saveGuided()
        idle()
        val r = onDisk().records.single()
        assertEquals(HyroxStation.SKIERG.id, r.stations.single().stationId)
        assertEquals(5, r.minutesActual)
    }

    @Test fun endingUnderAMinuteLogsNothing() {
        val vm = newVm()
        vm.launchPlanned(easy(30))
        vm.guided.start()
        repeat(40) { vm.guided.tick(1000) }
        vm.guided.end()
        idle()
        assertEquals(emptyList(), onDisk().records)
        vm.saveGuided()
        assertEquals(emptyList(), onDisk().records)
    }

    @Test fun pausedTimeDoesNotCountAndSkipMovesOn() {
        val vm = newVm()
        vm.launchPlanned(easy(30))
        vm.guided.start()
        repeat(60) { vm.guided.tick(1000) }
        vm.guided.togglePause()
        repeat(600) { vm.guided.tick(1000) }
        assertEquals(60, vm.guided.ui.value.run!!.completedSec)
        vm.guided.togglePause()
        vm.guided.skip()
        assertEquals(1, vm.guided.ui.value.run!!.index)
        vm.guided.end()
        idle()
        assertEquals(1, onDisk().records.single().minutesActual) // the 60 s done, warm-up skipped
    }

    // ---------------------------------------------------------------------------------------- guided intervals

    @Test fun guidedIntervalSessionCountsItsHardMinutesAsHard() {
        val vm = newVm()
        vm.launchPlanned(vo2(40))
        val plan = vm.guided.ui.value.plan!!
        assertEquals(4, plan.workSteps)
        assertEquals(8, vm.guided.ui.value.rpe)
        vm.guided.start()
        vm.runToEnd()
        val r = onDisk().records.single()
        assertEquals(SessionType.HYROX, r.type)
        assertEquals(40, r.minutesActual)
        val split = IntensityBalance.classify(r)
        assertEquals(16, split.hardMin)
        assertEquals(24, split.easyMin)
        vm.guided.setRpe(9)
        vm.saveGuided()
        val after = onDisk().records.single()
        assertEquals(16, IntensityBalance.classify(after).hardMin)
        assertEquals(9, after.stations.last().rpe)
        // And across the week it sits in the balance as 16 hard / 24 easy.
        val week = IntensityBalance.of(onDisk().records, today)
        assertEquals(16, week.week.hardMin)
    }

    @Test fun skippingAHardStepCountsOnlyWhatWasDone() {
        val vm = newVm()
        vm.launchPlanned(vo2(40))
        vm.guided.start()
        repeat(600) { vm.guided.tick(1000) } // warm-up
        assertEquals(GuidedKind.WORK, vm.guided.ui.value.run!!.current!!.kind)
        repeat(120) { vm.guided.tick(1000) }
        vm.guided.skip() // 2 minutes of the first hard step
        vm.guided.end()
        idle()
        val r = onDisk().records.single()
        assertEquals(2, IntensityBalance.classify(r).hardMin)
    }

    @Test fun aBikeSessionIsALightRecord() {
        val vm = newVm()
        vm.launchPlanned(easy(30), Modality.BIKE)
        vm.guided.start()
        vm.runToEnd()
        val r = onDisk().records.single()
        assertEquals(SessionType.HIKE, r.type)
        assertEquals("bike", r.stations.single().stationId)
        assertEquals(TimeSplit(30, 0, 0), IntensityBalance.classify(r))
    }

    @Test fun modalityCanBeSwitchedBeforeStartingNotAfter() {
        val vm = newVm()
        vm.launchPlanned(easy(30))
        vm.guided.setModality(Modality.BIKE)
        assertEquals(Modality.BIKE, vm.guided.ui.value.modality)
        vm.guided.start()
        vm.guided.setModality(Modality.SKIERG)
        assertEquals(Modality.BIKE, vm.guided.ui.value.modality)
    }

    // ---------------------------------------------------------------------------------------- run-walk treadmill

    @Test fun runWalkTreadmillPlanIsSavedAsARunWithTheRunLabel() {
        val vm = newVm()
        vm.launchPlanned(runWalk(40))
        val plan = vm.pendingTreadmill.value!!
        assertEquals(40 * 60, plan.totalSec)
        assertTrue(plan.segments.all { it.seconds >= 60 })
        val labels = plan.segments.map { it.label }
        assertTrue(labels.all { it in setOf("Warm-up", "Run", "Walk", "Easy walk", "Cool-down") }, "$labels")
        val s = vm.data.value.profile.settings
        assertTrue(plan.segments.filter { it.label == "Run" }.all { it.speedKmh == s.hyroxRunSpeedKmh && it.inclinePct == s.hyroxRunInclinePct })
        // Played to the end it is saved at once as a treadmill record the run-stage logic recognises as a run.
        vm.startTreadmillForTest()
        val saved = onDisk().records.single()
        assertEquals(SessionType.TREADMILL, saved.type)
        assertEquals("Run-walk", saved.dayLabel)
        assertTrue(com.calum.traininglog.core.RunStageLogic.isRun(saved))
        assertEquals(TimeSplit(saved.minutesActual, 0, 0), IntensityBalance.classify(saved))
    }

    /** Starts the pending treadmill plan on the engine with a fake clock and plays it to the end. */
    private fun AppViewModel.startTreadmillForTest() {
        val plan = pendingTreadmill.value!!
        TreadmillEngine.start(plan, 0L)
        go(Screen.TreadmillPlayer)
        var now = 0L
        while (!TreadmillEngine.ui.value.finished) { now += 1000; TreadmillEngine.tick(now) }
        idle()
    }
}
