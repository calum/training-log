package com.calum.traininglog

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.Export
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.HyroxDivision
import com.calum.traininglog.core.HyroxProgression
import com.calum.traininglog.core.HyroxSim
import com.calum.traininglog.core.HyroxStation
import com.calum.traininglog.core.SimLegKind
import com.calum.traininglog.core.Intensity
import com.calum.traininglog.core.IntensityBalance
import com.calum.traininglog.core.LaunchHint
import com.calum.traininglog.core.Modality
import com.calum.traininglog.core.PlannedSession
import com.calum.traininglog.core.PublicExport
import com.calum.traininglog.core.PublicImport
import com.calum.traininglog.core.Rewards
import com.calum.traininglog.core.RunMode
import com.calum.traininglog.core.RunStage
import com.calum.traininglog.core.RunStageLogic
import com.calum.traininglog.core.SessionKind
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.SimFormat
import com.calum.traininglog.core.SimPreset
import com.calum.traininglog.core.SimResult
import com.calum.traininglog.core.StationEntry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Hyrox simulation and the stepped station finisher, end to end: controller on the (shadow) clock, real ViewModel and disk. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HyroxSimFlowTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>() as Application
    private val today = LocalDate.of(2027, 1, 18)
    private fun newVm() = AppViewModel(app, clock = { today })
    private fun onDisk() = Store(app).load()
    private fun advance(ms: Long) { ShadowSystemClock.advanceBy(Duration.ofMillis(ms)) }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before fun setUp() { TreadmillEngine.stop() }
    @After fun tearDown() { TreadmillEngine.stop() }

    /** Starts the sim and waits out the 3-2-1 lead-in. */
    private fun AppViewModel.startRace() {
        sim.start()
        advance(HyroxSimController.LEAD_IN_MS)
    }

    /** One run or station leg: [sec] seconds on the clock, then Done, then a transition of [trans] seconds and "Ready, go". */
    private fun AppViewModel.leg(sec: Int, trans: Int = 20) {
        advance(sec * 1000L); sim.legDone()
        if (sim.ui.value.inTransition) { advance(trans * 1000L); sim.legDone() }
    }

    private fun finishAll(vm: AppViewModel, secs: (Int) -> Int = { 100 + it * 10 }) {
        val n = vm.sim.ui.value.plan!!.raceLegs.size
        for (i in 0 until n) vm.leg(secs(i))
        idle()
    }

    // ------------------------------------------------------------------------------------ whole simulation

    @Test fun wholeSimOnTheClockWithCountdownRunsAutoAdvance() {
        val vm = newVm()
        vm.simOpen(SimPreset(SimFormat.FIRST_HALF))
        vm.simSetup(runMode = RunMode.TREADMILL)
        vm.simSetup(targetSec = 2700)
        val plan = vm.sim.ui.value.plan!!
        val run1 = plan.legs[0].targetSec!!

        // Done during the lead-in does nothing.
        vm.sim.start()
        assertTrue(vm.sim.inLeadIn())
        assertEquals(3, vm.sim.leadInLeft())
        vm.sim.legDone()
        assertEquals(0, vm.sim.ui.value.legIndex)
        assertTrue(vm.sim.ui.value.results.isEmpty())
        advance(HyroxSimController.LEAD_IN_MS)
        assertFalse(vm.sim.inLeadIn())

        // Leg 1, a treadmill run: +10 s extends it, the countdown ends it by itself and lands in the TRANSITION.
        assertEquals(run1, vm.sim.ui.value.countdownSec)
        vm.sim.addTime()
        assertEquals(run1 + 10, vm.sim.ui.value.countdownSec)
        advance((run1 * 1000L) - 1); assertFalse(vm.sim.tick(), "not yet: the extra 10 s")
        advance(10_001); assertTrue(vm.sim.tick(), "auto advance at zero")
        assertEquals(SimLegKind.TRANSITION, vm.sim.ui.value.leg!!.kind)
        assertEquals(1, vm.sim.ui.value.legIndex)
        assertEquals(run1 + 10, vm.sim.ui.value.results.single().seconds)
        assertTrue(vm.sim.ui.value.autoAdvanced)
        // Nothing starts by itself out of a transition: a long wait just counts up until "Ready, go".
        advance(30_000); assertFalse(vm.sim.tick())
        assertEquals(1, vm.sim.ui.value.legIndex)
        vm.sim.legDone()
        assertEquals(2, vm.sim.ui.value.legIndex)
        assertEquals(30, vm.sim.ui.value.results.last().seconds)
        assertFalse(vm.sim.ui.value.autoAdvanced)

        // Leg 2 (SkiErg) is a stopwatch: tick never moves it on. Effort chips: the default is the previous value, then a tap.
        assertNull(vm.sim.ui.value.countdownSec)
        advance(500_000); assertFalse(vm.sim.tick())
        assertEquals(2, vm.sim.ui.value.legIndex)
        vm.sim.rate(0, 6)
        vm.sim.legDone()
        assertEquals(listOf(6, 6), vm.sim.ui.value.results.mapNotNull { it.rpe }) // the SkiErg took the previous value by default
        vm.sim.rate(2, 8)
        advance(20_000); vm.sim.legDone() // its transition
        // Race legs 3..8: 100 s each (the next treadmill runs done early), 20 s transitions.
        for (i in 2 until 8) vm.leg(100)
        idle()

        assertEquals(SimPhase.DONE, vm.sim.ui.value.phase)
        val rec = onDisk().records.single()
        assertEquals(SessionType.HYROX, rec.type)
        assertEquals(today, rec.date)
        assertEquals("Simulation: first half", rec.dayLabel)
        assertEquals(listOf("run", "skierg", "run", "sled_push", "run", "sled_pull", "run", "burpee_broad_jump"), rec.stations.map { it.stationId })
        assertEquals(listOf(run1 + 10, 500, 100, 100, 100, 100, 100, 100), rec.stations.map { it.seconds })
        val meta = rec.sim!!
        assertEquals(SimFormat.FIRST_HALF, meta.format)
        assertEquals(1.0, meta.loadFactor)
        assertEquals(RunMode.TREADMILL, meta.runMode)
        assertEquals(2700, meta.targetSec)
        assertTrue(meta.skipped.isEmpty()); assertFalse(meta.endedEarly)
        // Seven timed transitions, kept per transition, and the total includes them (the clock never stops).
        assertEquals(listOf(30, 20, 20, 20, 20, 20, 20), meta.transitionSec)
        assertEquals(45, meta.allowanceSec)
        assertEquals(rec.stations.sumOf { it.seconds!! } + meta.transitionSec.sum(), meta.totalSec)
        assertEquals((meta.totalSec + 30) / 60, rec.minutesActual)
        // Loads, distances and effort on the legs.
        val push = rec.stations[3]
        assertEquals(152.0, push.weightKg); assertEquals(50, push.distanceM); assertEquals(8, push.rpe)
        assertEquals(1000, rec.stations[0].distanceM)
        assertEquals(5, push.level)
        assertEquals(103.0, rec.stations[5].weightKg)
        assertEquals(80, rec.stations[7].distanceM)

        // Saved by the time it ended: celebrated, route is still the results; the rating card only edits it.
        assertNotNull(vm.celebration.value)
        assertEquals(rec.id, vm.sim.ui.value.savedId)
        vm.saveSim(Feeling.OKAY, 2, lastRpe = 9)
        val after = onDisk().records.single() // no duplicate
        assertEquals(rec.id, after.id)
        assertEquals(Feeling.OKAY, after.feeling); assertEquals(2, after.kneePain); assertEquals(9, after.stations.last().rpe)
        assertEquals(rec.stations.dropLast(1), after.stations.dropLast(1))
        assertEquals(meta, after.sim)
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(SimPhase.SETUP, vm.sim.ui.value.phase)
        assertEquals(true, vm.sim.ui.value.plan == null)

        // The station legs feed the normal Hyrox progression; run legs never count as stations.
        val hist = after.stations.filter { it.stationId == "sled_push" }.map { StationEntry(after.date, it) }
        assertEquals(6, HyroxProgression.next(HyroxStation.SLED_PUSH, HyroxDivision.MEN_OPEN, hist).level)
    }

    @Test fun aTransitionIsTimedThenTheNextStationStartsOnReady() {
        val vm = newVm()
        vm.simOpen(SimPreset(SimFormat.FIRST_HALF)) // knee-gated: rower legs
        vm.simSetTransition(30)
        assertEquals(30, vm.data.value.profile.settings.hyroxTransitionSec) // remembered
        assertEquals(30, vm.sim.ui.value.plan!!.transitionSec)
        assertTrue(vm.sim.ui.value.plan!!.legs.filter { it.kind == SimLegKind.TRANSITION }.all { it.allowanceSec == 30 })
        vm.startRace()
        advance(400_000); vm.sim.legDone() // run 1 ends
        val ui = vm.sim.ui.value
        assertTrue(vm.sim.ui.value.inTransition)
        assertEquals("Transition · next: SkiErg", HyroxSim.legTitle(ui.plan!!, ui.leg!!))
        assertEquals("Reset the SkiErg monitor and check the damper", ui.leg!!.prep)
        // Within the allowance, then over it: the stopwatch keeps counting, nothing is penalised or started for him.
        advance(25_000)
        assertEquals(25, vm.sim.legSec())
        advance(17_000)
        assertEquals(42, vm.sim.legSec()) // 12 s over the 30 s allowance, shown as +0:12
        assertFalse(vm.sim.tick())
        assertTrue(vm.sim.ui.value.inTransition)
        // The race clock and the leg clock both keep running through a pause, and pause works here too.
        vm.sim.pause(); advance(60_000); assertEquals(42, vm.sim.legSec()); vm.sim.resume()
        vm.sim.legDone()
        assertEquals(SimLegKind.STATION, vm.sim.ui.value.leg!!.kind)
        assertEquals(HyroxStation.SKIERG, vm.sim.ui.value.leg!!.station)
        assertEquals(400 + 42, vm.sim.elapsedSec())
        assertEquals(0, vm.sim.legSec())
        // Skipping a transition's wait is the same as "Ready, go".
        advance(300_000); vm.sim.legDone() // SkiErg ends: transition
        assertTrue(vm.sim.ui.value.inTransition)
        advance(5_000); vm.sim.skipLeg()
        assertEquals(SimLegKind.RUN, vm.sim.ui.value.leg!!.kind)
        assertEquals(listOf(400, 42, 300, 5), vm.sim.ui.value.results.map { it.seconds })
        for (i in 2 until 8) vm.leg(100, trans = 10)
        idle()
        val rec = onDisk().records.single()
        assertEquals(listOf(42, 5, 10, 10, 10, 10, 10), rec.sim!!.transitionSec)
        assertEquals(30, rec.sim!!.allowanceSec)
        assertEquals(rec.stations.sumOf { it.seconds!! } + rec.sim!!.transitionSec.sum(), rec.sim!!.totalSec)
        // Export carries the transition times; the result shows the Roxzone share.
        val text = Export.summary(listOf(rec), today, today)
        assertTrue("transition: 0:42" in text && "transitions (Roxzone) 1:37" in text && "allowance 30 s each" in text, text)
        val res = SimResult.from(rec)!!
        assertEquals(97, res.roxSec)
        assertTrue("Roxzone" in res.shareText!!, res.shareText)
    }

    @Test fun pauseCutsTheTimeOutOfBothClocks() {
        val vm = newVm()
        vm.simOpen(SimPreset(SimFormat.FIRST_HALF))
        vm.startRace()
        advance(30_000)
        vm.sim.pause()
        advance(600_000) // ten minutes on the phone
        assertEquals(30, vm.sim.legSec()); assertEquals(30, vm.sim.elapsedSec())
        vm.sim.legDone(); assertEquals(0, vm.sim.ui.value.legIndex) // ignored while paused
        vm.sim.resume()
        advance(15_000)
        assertEquals(45, vm.sim.legSec()); assertEquals(45, vm.sim.elapsedSec())
        vm.sim.legDone()
        assertEquals(45, vm.sim.ui.value.results.single().seconds)
        // Leg 1 is over, so this is its transition: pausing and resuming work there too.
        assertTrue(vm.sim.ui.value.inTransition)
        vm.sim.pause(); advance(1000); vm.sim.resume()
        advance(20_000); vm.sim.legDone()
        assertEquals(listOf(45, 20), vm.sim.ui.value.results.map { it.seconds })
        assertEquals(65, vm.sim.elapsedSec())
    }

    @Test fun endingEarlyKeepsWhatWasDone() {
        val vm = newVm()
        vm.simOpen(SimPreset(SimFormat.SECOND_HALF))
        vm.startRace()
        vm.leg(300); vm.leg(240); vm.leg(310)
        advance(50_000) // part way through leg 4, which is not recorded
        vm.sim.endEarly()
        idle()
        assertEquals(SimPhase.DONE, vm.sim.ui.value.phase)
        val rec = onDisk().records.single()
        assertEquals("Simulation: second half", rec.dayLabel)
        assertEquals(listOf("run", "row", "run"), rec.stations.map { it.stationId })
        assertTrue(rec.sim!!.endedEarly)
        assertTrue(rec.sim!!.skipped.isEmpty())
        assertEquals(300 + 240 + 310 + 3 * 20 + 50, rec.sim!!.totalSec) // the race clock to the moment it ended, transitions included
        assertEquals(listOf(20, 20, 20, 0, 0, 0, 0), rec.sim!!.transitionSec)
        assertFalse(rec.sim!!.complete)
        val res = SimResult.from(rec)!!
        assertEquals(3, res.legsDone)
        assertEquals(8, res.legs.size)
        assertTrue(res.legs.drop(3).all { it.seconds == null })
        // A second tap on End early or Done changes nothing.
        vm.sim.endEarly(); vm.sim.legDone(); idle()
        assertEquals(1, onDisk().records.size)

        // Ending before any leg is done logs nothing at all.
        val vm2 = newVm()
        vm2.simOpen(null)
        vm2.startRace(); advance(20_000); vm2.sim.endEarly(); idle()
        assertEquals(1, onDisk().records.size)
        assertNull(vm2.sim.ui.value.savedId)
        vm2.saveSim(Feeling.GOOD, null)
        assertEquals(1, onDisk().records.size)
        assertEquals(Screen.Home, vm2.screen.value)
    }

    @Test fun skippingALegIsRecordedAsSkippedWithNoPenalty() {
        val vm = newVm()
        vm.simOpen(SimPreset(SimFormat.FIRST_HALF))
        vm.startRace()
        vm.leg(200)         // run 1
        vm.sim.skipLeg()    // SkiErg skipped: on to its transition
        assertEquals(3, vm.sim.ui.value.legIndex)
        assertTrue(vm.sim.ui.value.inTransition)
        assertNull(vm.sim.ui.value.ratingIndex, "no effort question for a skipped leg")
        advance(10_000); vm.sim.legDone()
        for (i in 2 until 8) vm.leg(150)
        idle()
        val rec = onDisk().records.single()
        assertEquals(listOf(1), rec.sim!!.skipped)
        assertFalse(rec.sim!!.endedEarly)
        assertEquals(listOf("run", "run", "sled_push", "run", "sled_pull", "run", "burpee_broad_jump"), rec.stations.map { it.stationId })
        val res = SimResult.from(rec)!!
        assertEquals(7, res.legsDone)
        assertNull(res.legs[1].seconds)
        assertEquals(1, res.legs.count { !it.done })
        val export = Export.summary(listOf(rec), today, today)
        assertTrue("2. SkiErg: skipped or not done" in export, export)
        // Skipping the very last leg still ends and saves it.
        val vm2 = newVm()
        vm2.simOpen(SimPreset(SimFormat.FIRST_HALF)); vm2.startRace()
        for (i in 0 until 7) vm2.leg(100)
        vm2.sim.skipLeg(); idle()
        assertEquals(SimPhase.DONE, vm2.sim.ui.value.phase)
        assertEquals(listOf(7), onDisk().records.last().sim!!.skipped)
    }

    // ------------------------------------------------------------------------------------ knee gating

    @Test fun theKneeGatedDefaultSubstitutesTheRunLegs() {
        val vm = newVm() // no running logged: the knee stage is "walking only"
        assertEquals(RunStage.NONE, RunStageLogic.stage(vm.data.value.records, today))
        vm.simOpen(SimPreset(SimFormat.FIRST_HALF))
        val ui = vm.sim.ui.value
        assertEquals(RunMode.ROW, ui.runMode)
        assertEquals(RunStage.NONE, ui.stage)
        assertTrue(ui.plan!!.legs.filter { it.runMode != null }.all { it.runMode == RunMode.ROW && it.distanceM == 1000 })
        assertTrue(com.calum.traininglog.core.HyroxSim.runModeNote(ui.runMode, ui.stage).startsWith("Running is not ready yet, so the run legs are a rower 1000 m"))
        // Changing the format keeps the default; a chosen mode survives later changes.
        vm.simSetup(format = SimFormat.FULL)
        assertEquals(RunMode.ROW, vm.sim.ui.value.runMode)
        assertEquals(16, vm.sim.ui.value.plan!!.raceLegs.size)
        assertEquals(31, vm.sim.ui.value.plan!!.legs.size)
        vm.simSetup(runMode = RunMode.BIKE)
        assertEquals(3000, vm.sim.ui.value.plan!!.legs.first().distanceM)
        vm.simSetup(format = SimFormat.SECOND_HALF, loadFactor = 0.8)
        assertEquals(RunMode.BIKE, vm.sim.ui.value.runMode)
        assertEquals(0.8, vm.sim.ui.value.loadFactor)
        assertEquals(15.0, vm.sim.ui.value.plan!!.legs.first { it.station == HyroxStation.SANDBAG_LUNGES }.weightKg)

        // Done twice with a calm knee: the substitute runs never move the knee stage on.
        vm.simSetup(runMode = RunMode.ROW, format = SimFormat.FIRST_HALF, loadFactor = 1.0)
        vm.startRace(); finishAll(vm); vm.saveSim(Feeling.GOOD, 1)
        vm.simOpen(SimPreset(SimFormat.FIRST_HALF)); vm.simSetup(runMode = RunMode.ROW)
        vm.startRace(); finishAll(vm); vm.saveSim(Feeling.GOOD, 1)
        val recs = onDisk().records
        assertEquals(2, recs.size)
        assertTrue(recs.all { it.sim!!.runMode == RunMode.ROW })
        assertEquals(RunStage.NONE, RunStageLogic.stage(recs, today.plusDays(1)))
        assertTrue(recs.all { RunStageLogic.runSeconds(it) == 0 })
    }

    // ------------------------------------------------------------------------------------ comparison, rewards, exports

    @Test fun aSecondSimIsComparedWithTheFirstAndShowsInTheExports() {
        val vm = newVm()
        vm.simOpen(SimPreset(SimFormat.FIRST_HALF)); vm.simSetup(runMode = RunMode.ROW)
        vm.startRace(); finishAll(vm) { 300 }
        vm.saveSim(Feeling.GOOD, null)
        val first = onDisk().records.single()
        assertEquals(2400 + 7 * 20, first.sim!!.totalSec)

        val later = LocalDate.of(2027, 1, 25)
        val vm2 = AppViewModel(app, clock = { later })
        vm2.simOpen(SimPreset(SimFormat.FIRST_HALF)); vm2.simSetup(runMode = RunMode.ROW)
        vm2.startRace(); finishAll(vm2) { if (it == 1) 250 else 290 }
        vm2.saveSim(Feeling.GOOD, null)
        val recs = onDisk().records
        assertEquals(2, recs.size)
        val second = recs.single { it.id != first.id }
        assertEquals(later, second.date)

        val prev = SimResult.previousOf(recs, second)!!
        assertEquals(first.id, prev.id)
        val cmp = SimResult.compare(SimResult.from(second)!!, SimResult.from(prev))
        assertEquals(8, cmp.legsCompared)
        assertEquals(-(50 + 7 * 10), cmp.totalDeltaSec)
        assertEquals("2:00 quicker than last time.", cmp.totalText)
        assertEquals("0:50 quicker", cmp.legs.first { it.index == 1 }.text)

        // A PR for the format, the badge, the XP bonus.
        val summary = Rewards.summary(recs, later)
        assertTrue(summary.prs.any { it.exerciseId == "hyrox_sim:first_half" && it.sessionId == second.id })
        assertTrue(summary.badges.last().unlocked)
        assertEquals(Rewards.HYROX_SIM_BONUS, summary.sessionXp.getValue(second.id).sim)

        // Claude export: a readable split table. Public export: whitelisted, in its own file, and it imports back.
        val text = vm2.exportText(null)
        assertTrue("Simulation (first half), loads 100%, run legs: rower, total 40:20" in text, text)
        assertTrue("2. SkiErg: 1000 m, 4:10" in text, text)
        val files = PublicExport.files(recs)
        val csv = files.getValue("data/hyrox-sim/2027-01.csv")
        assertEquals(PublicExport.SIM_HEADER, csv.lines().first())
        assertEquals(2 * 8, csv.lines().filter { it.isNotBlank() }.size - 1)
        assertFalse(files.keys.any { it.startsWith("data/hyrox/") })
        assertFalse(files.values.any { it.contains("feeling", true) || it.contains("knee", true) || it.contains("Simulation:", true) })
        val back = PublicImport.records(files).filter { it.sim != null }
        assertEquals(2, back.size)
        assertEquals(recs.map { it.sim!!.totalSec }.sorted(), back.map { it.sim!!.totalSec }.sorted())
        // The mixed hard/moderate classification counts the minutes without crashing.
        val split = IntensityBalance.classify(second)
        assertEquals(second.minutesActual, split.easyMin + split.hardMin)
        assertTrue(split.hardMin in 1..second.minutesActual)
    }

    @Test fun aPastSimulationCanBeViewedAndNothingRuns() {
        val vm = newVm()
        vm.simOpen(SimPreset(SimFormat.FIRST_HALF)); vm.startRace(); finishAll(vm); vm.saveSim(Feeling.GOOD, null)
        val rec = onDisk().records.single()
        vm.go(Screen.Hyrox) // the past simulation is opened from the hub, so closing it returns there
        vm.simView(rec.id)
        assertEquals(Screen.HyroxSim(null), vm.screen.value)
        assertEquals(SimPhase.DONE, vm.sim.ui.value.phase)
        assertTrue(vm.sim.ui.value.viewing)
        idle()
        assertEquals(1, onDisk().records.size) // viewing never saves again
        vm.simClose(toHub = true)
        assertEquals(Screen.Hyrox, vm.screen.value)
        assertEquals(SimPhase.SETUP, vm.sim.ui.value.phase)
        // A running simulation is resumed, not replaced, when the route is opened again.
        vm.simOpen(null); vm.startRace()
        vm.simOpen(SimPreset(SimFormat.SECOND_HALF))
        assertEquals(SimPhase.RUNNING, vm.sim.ui.value.phase)
        assertEquals(SimFormat.FULL, vm.sim.ui.value.format)
    }

    // ------------------------------------------------------------------------------------ routing

    private fun simSession(kind: SessionKind, preset: SimPreset?, mods: List<Modality> = listOf(Modality.HYROX_STATIONS, Modality.ROW)) = PlannedSession(
        today, kind, "Sim", 90, Intensity.HARD, 36, 7..8, mods, emptyList(), "Why.",
        LaunchHint(SessionType.HYROX, hyroxFocus = HyroxStation.entries.toSet(), minutes = 90, sim = preset), includesRunning = false,
    )

    @Test fun launchPlannedOpensTheSimulationForSimKindOrAPreset() {
        val vm = newVm()
        vm.launchPlanned(simSession(SessionKind.SIM, SimPreset(SimFormat.FULL, 0.9)))
        assertEquals(Screen.HyroxSim(SimPreset(SimFormat.FULL, 0.9)), vm.screen.value)
        assertEquals(0.9, vm.sim.ui.value.loadFactor)
        assertEquals(SimFormat.FULL, vm.sim.ui.value.format)
        vm.sim.reset()
        vm.launchPlanned(simSession(SessionKind.KEY, SimPreset(SimFormat.SECOND_HALF)))
        assertEquals(Screen.HyroxSim(SimPreset(SimFormat.SECOND_HALF)), vm.screen.value)
        assertEquals(SimFormat.SECOND_HALF, vm.sim.ui.value.format)
        vm.sim.reset()
        // The user picked the rower for the same session: the guided timer, as before.
        vm.launchPlanned(simSession(SessionKind.SIM, SimPreset(SimFormat.FIRST_HALF)), Modality.ROW)
        assertTrue(vm.screen.value is Screen.GuidedTimer)
        // The real plan's three simulation sessions carry their presets.
        val plan = com.calum.traininglog.core.RacePlanner.build(com.calum.traininglog.core.RacePlanSettings(), emptyList(), LocalDate.of(2026, 11, 19))
        val sims = plan.weeks.flatMap { it.sessions }.mapNotNull { it.launch.sim }
        assertEquals(listOf(SimFormat.FIRST_HALF, SimFormat.SECOND_HALF, SimFormat.FULL), sims.map { it.format })
        assertEquals(listOf(1.0, 1.0, 0.9), sims.map { it.loadFactor })
    }

    // ------------------------------------------------------------------------------------ stepped station finisher

    private fun strengthWithFinisher() = PlannedSession(
        today, SessionKind.STRENGTH, "Strength A + station finisher", 60, Intensity.STRENGTH, 0, 6..8, listOf(Modality.GYM),
        listOf(
            "Warm up 5 minutes.", "Hyrox station finisher (about 12 min) at this phase's ladder level:",
            "  Sled Push, level 2: 4 x 12 m", "  Wall Balls, level 2: 4 x 10 reps",
        ),
        "Why.", LaunchHint(SessionType.GYM, hyroxFocus = setOf(HyroxStation.SLED_PUSH, HyroxStation.WALL_BALLS), minutes = 60),
    )

    @Test fun finishingTheLiftsOffersTheFinisherThenStartsTheGuidedSession() {
        val vm = newVm()
        vm.launchPlanned(strengthWithFinisher())
        assertEquals(Screen.Gym, vm.screen.value)
        assertEquals(12, vm.gym.value!!.finisherMinutes)
        assertEquals(setOf(HyroxStation.SLED_PUSH, HyroxStation.WALL_BALLS), vm.gym.value!!.finisherFocus)
        vm.doneSet(0, 0)
        vm.finishGym()
        // The gym session is saved first, and the screen stays with the next-step prompt.
        assertEquals(1, onDisk().records.size)
        assertEquals(SessionType.GYM, onDisk().records.single().type)
        assertEquals(Screen.Gym, vm.screen.value)
        assertTrue(vm.gym.value!!.finisherOffered)
        vm.finishGym() // a second tap never saves twice
        assertEquals(1, onDisk().records.size)

        vm.startFinisher()
        assertEquals(Screen.HyroxSession(setOf(HyroxStation.SLED_PUSH, HyroxStation.WALL_BALLS)), vm.screen.value)
        assertNull(vm.gym.value)
        val ui = vm.hyrox.ui.value
        assertEquals(setOf(HyroxStation.SLED_PUSH, HyroxStation.WALL_BALLS), ui.focus)
        assertEquals(HyroxPhase.SETUP, ui.phase)
        assertTrue(ui.plan!!.blocks.isNotEmpty() && ui.plan!!.blocks.all { it.station in ui.focus })
        vm.hyrox.reset()
    }

    @Test fun skippingTheFinisherGoesHomeWithTheLiftsSaved() {
        val vm = newVm()
        vm.launchPlanned(strengthWithFinisher())
        vm.doneSet(0, 0); vm.finishGym()
        vm.skipFinisher()
        assertEquals(Screen.Home, vm.screen.value)
        assertNull(vm.gym.value)
        assertEquals(1, onDisk().records.size)
        assertNull(vm.hyrox.ui.value.plan)
    }

    @Test fun aStrengthDayWithoutAFinisherGoesStraightHome() {
        val vm = newVm()
        val plain = strengthWithFinisher().copy(detail = listOf("Warm up 5 minutes."), launch = LaunchHint(SessionType.GYM, minutes = 60))
        vm.launchPlanned(plain)
        vm.doneSet(0, 0); vm.finishGym()
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(1, onDisk().records.size)
    }
}
