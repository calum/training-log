package com.calum.traininglog

import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.HyroxStation
import com.calum.traininglog.core.isKneeCare
import com.calum.traininglog.core.Intensity
import com.calum.traininglog.core.LaunchHint
import com.calum.traininglog.core.Modality
import com.calum.traininglog.core.PlannedSession
import com.calum.traininglog.core.SessionKind
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.SimFormat
import com.calum.traininglog.core.SimPreset
import com.calum.traininglog.core.TreadmillStyle
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * System back (swipe or button) through the real [AppRoot]: it steps back through the stack on ordinary screens,
 * exits only from Home, and asks before leaving any live session.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = Application::class)
class BackNavTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as Application
    private val today = LocalDate.of(2026, 6, 10)
    private fun newVm() = AppViewModel(app, clock = { today })
    private fun onDisk() = Store(app).load()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) { ShadowSystemClock.advanceBy(Duration.ofMillis(ms)) }

    @Before fun setUp() { TreadmillEngine.stop() }
    @After fun tearDown() { TreadmillEngine.stop() }

    private fun show(vm: AppViewModel) { rule.setContent { AppRoot(vm, inPip = false) }; rule.waitForIdle() }

    /** The system back gesture / key, as the activity's dispatcher sees it. */
    private fun systemBack() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun hasBackHandler(): Boolean {
        var enabled = false
        rule.activityRule.scenario.onActivity { enabled = it.onBackPressedDispatcher.hasEnabledCallbacks() }
        return enabled
    }

    private fun assertDialogShown() { rule.onNodeWithText("Leave this session?").assertExists() }
    private fun assertNoDialog() { rule.onNodeWithText("Leave this session?").assertDoesNotExist() }
    private fun tap(text: String) { rule.onNodeWithText(text).performScrollTo().performClick(); rule.waitForIdle(); idle() }
    /** A button of the leave dialog (the screen behind it can have a button with the same label). */
    private fun tapDialog(text: String) {
        rule.onNode(hasText(text) and hasAnyAncestor(isDialog())).performClick(); rule.waitForIdle(); idle()
    }

    // ------------------------------------------------------------------------------------ plain navigation

    @Test fun backWalksUpTheStackAndHomeIsLeftToTheSystem() {
        val vm = newVm()
        show(vm)
        assertFalse(hasBackHandler(), "Home: the system default (exit) applies")

        vm.go(Screen.Hyrox)
        rule.waitForIdle()
        assertTrue(hasBackHandler())
        vm.go(Screen.HyroxStation(HyroxStation.ROW))
        rule.waitForIdle()
        assertEquals(listOf(Screen.Home, Screen.Hyrox), vm.backStackSnapshot)

        systemBack()
        assertEquals(Screen.Hyrox, vm.screen.value)
        systemBack()
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(emptyList(), vm.backStackSnapshot)

        assertFalse(hasBackHandler(), "nothing left to handle: a further back is not consumed")
        var finishing = false
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed(); finishing = it.isFinishing }
        assertTrue(finishing, "the unhandled back closes the activity")
        assertFalse(vm.back(), "back() reports false on Home")
    }

    @Test fun everyPlainScreenGoesBackToWhereItWasOpenedFrom() {
        val vm = newVm()
        show(vm)
        for (s in listOf(Screen.Start, Screen.Manual, Screen.Export, Screen.Plan, Screen.Progress, Screen.Trophies, Screen.Blocks, Screen.Settings, Screen.Backup, Screen.Hyrox, Screen.RacePlan)) {
            vm.go(s); rule.waitForIdle()
            assertEquals(s, vm.screen.value)
            systemBack()
            assertEquals(Screen.Home, vm.screen.value, "back from $s")
        }
        vm.go(Screen.Hyrox)
        vm.go(Screen.HyroxLog(null)); rule.waitForIdle()
        systemBack()
        assertEquals(Screen.Hyrox, vm.screen.value)
        vm.go(Screen.HyroxSession(emptySet())); vm.hyroxPrepare(emptySet()); rule.waitForIdle()
        systemBack() // the setup of a session type is an ordinary screen
        assertEquals(Screen.Hyrox, vm.screen.value)
    }

    @Test fun racePlanOpenedFromHomeReturnsToHomeAndOnScreenBackAgrees() {
        val vm = newVm()
        show(vm)
        vm.go(Screen.RacePlan); rule.waitForIdle()
        systemBack()
        assertEquals(Screen.Home, vm.screen.value)
        vm.go(Screen.Start); vm.go(Screen.RacePlan); rule.waitForIdle()
        systemBack()
        assertEquals(Screen.Start, vm.screen.value, "a plan opened from Start goes back to Start")
        tap("Back") // the on-screen button and the stack agree
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test fun goingToTheScreenJustBelowIsAStepBackNotANewStep() {
        val vm = newVm()
        vm.go(Screen.Hyrox)
        vm.go(Screen.HyroxStation(HyroxStation.ROW))
        vm.go(Screen.Hyrox) // e.g. an on-screen "Hyrox hub" link
        assertEquals(listOf(Screen.Home), vm.backStackSnapshot)
        vm.go(Screen.Settings); vm.go(Screen.Settings)
        assertEquals(listOf(Screen.Home, Screen.Hyrox), vm.backStackSnapshot)
        repeat(40) { i -> vm.go(if (i % 2 == 0) Screen.Plan else Screen.Progress) }
        assertTrue(vm.backStackSnapshot.size <= 20)
    }

    @Test fun celebrationOnHomeIsDismissedFirst() {
        val vm = newVm()
        vm.saveManual(SessionType.HIKE, 1, 60, null, "")
        assertNotNull(vm.celebration.value)
        show(vm)
        assertTrue(hasBackHandler())
        systemBack()
        assertNull(vm.celebration.value)
        assertEquals(Screen.Home, vm.screen.value)
        assertFalse(hasBackHandler())
    }

    // ------------------------------------------------------------------------------------ gym

    private fun startGym(vm: AppViewModel) {
        vm.go(Screen.Start)
        vm.begin(SessionType.GYM, 60)
        assertEquals(Screen.Gym, vm.screen.value)
    }

    @Test fun gymWithNoSetsLeavesQuietly() {
        val vm = newVm()
        show(vm)
        startGym(vm)
        rule.waitForIdle()
        systemBack()
        assertEquals(Screen.Home, vm.screen.value)
        assertNull(vm.gym.value)
        assertEquals(emptyList(), onDisk().records)
    }

    @Test fun liveGymAsksAndKeepGoingChangesNothing() {
        val vm = newVm()
        show(vm)
        startGym(vm)
        vm.doneSet(0, 0)
        rule.waitForIdle()
        val before = vm.gym.value
        systemBack()
        assertDialogShown()
        assertEquals(Screen.Gym, vm.screen.value)
        tapDialog("Keep going")
        assertNoDialog()
        assertEquals(Screen.Gym, vm.screen.value)
        assertEquals(before?.exercises, vm.gym.value?.exercises)
        assertEquals(emptyList(), onDisk().records)
        // Back on the dialog itself also just keeps going.
        systemBack(); assertDialogShown()
        systemBack(); assertNoDialog()
        assertEquals(Screen.Gym, vm.screen.value)
    }

    @Test fun liveGymDiscardGoesHomeWithoutARecord() {
        val vm = newVm()
        show(vm)
        startGym(vm)
        vm.doneSet(0, 0)
        rule.waitForIdle()
        systemBack()
        tapDialog("Discard")
        assertEquals(Screen.Home, vm.screen.value)
        assertNull(vm.gym.value)
        assertEquals(emptyList(), onDisk().records)
        assertEquals(emptyList(), vm.backStackSnapshot)
        assertNull(vm.celebration.value)
    }

    @Test fun liveGymFinishAndSaveSaves() {
        val vm = newVm()
        show(vm)
        startGym(vm)
        vm.doneSet(0, 0); vm.setRir(0, 0, 2)
        rule.waitForIdle()
        systemBack()
        tapDialog("Finish and save")
        assertEquals(Screen.Home, vm.screen.value)
        val rec = onDisk().records.single()
        assertEquals(SessionType.GYM, rec.type)
        assertEquals(1, rec.exercises.first().sets.size)
        // Back from Home after finishing never re-enters the finished session.
        rule.waitForIdle()
        assertNull(vm.gym.value)
        assertEquals(emptyList(), vm.backStackSnapshot)
        assertFalse(vm.back())
    }

    // ------------------------------------------------------------------------------------ treadmill

    private fun startTreadmillPlayer(vm: AppViewModel) {
        vm.go(Screen.Start)
        vm.begin(SessionType.TREADMILL, 30, TreadmillStyle.STEADY)
        assertEquals(Screen.TreadmillSetup, vm.screen.value)
        TreadmillEngine.start(vm.pendingTreadmill.value!!, SystemClock.elapsedRealtime())
        vm.go(Screen.TreadmillPlayer)
    }

    @Test fun runningTreadmillAsksAndOnlyEndsWhenConfirmed() {
        val vm = newVm()
        startTreadmillPlayer(vm)
        assertEquals(listOf(Screen.Home, Screen.Start), vm.backStackSnapshot, "setup and player are never pushed")
        show(vm)
        systemBack()
        assertDialogShown()
        assertTrue(TreadmillEngine.ui.value.active && !TreadmillEngine.ui.value.finished)
        tapDialog("Keep going")
        assertEquals(Screen.TreadmillPlayer, vm.screen.value)
        assertTrue(TreadmillEngine.ui.value.active)

        // Paused counts as live too.
        vm.togglePause(); rule.waitForIdle()
        systemBack(); assertDialogShown()
        tapDialog("Keep going")
        assertTrue(TreadmillEngine.ui.value.paused)

        systemBack()
        tapDialog("End session")
        assertEquals(Screen.TreadmillDone, vm.screen.value)
        assertTrue(TreadmillEngine.ui.value.active, "the engine is stopped by saving, as with the End button")
    }

    @Test fun doneScreenBackIsSkipAndDoesNotDuplicateTheAutosave() {
        val vm = newVm()
        vm.setInputs { it.copy(minutes = 30) }
        vm.go(Screen.Start)
        vm.begin(SessionType.TREADMILL, 30, TreadmillStyle.LADDER)
        val plan = vm.pendingTreadmill.value!!
        TreadmillEngine.start(plan, 0L)
        vm.go(Screen.TreadmillPlayer)
        var now = 0L
        while (!TreadmillEngine.ui.value.finished) { now += 1000; TreadmillEngine.tick(now) }
        idle()
        assertEquals(1, onDisk().records.size, "autosaved the moment it finished")
        vm.endTreadmill()
        show(vm)
        assertEquals(Screen.TreadmillDone, vm.screen.value)
        systemBack()
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(1, onDisk().records.size, "no duplicate")
        assertFalse(TreadmillEngine.ui.value.active)
        assertNull(vm.pendingTreadmill.value)
        assertEquals(emptyList(), vm.backStackSnapshot)
        assertFalse(vm.back())
    }

    @Test fun finishedTreadmillOnThePlayerGoesToTheDoneScreen() {
        val vm = newVm()
        vm.begin(SessionType.TREADMILL, 30, TreadmillStyle.STEADY)
        val plan = vm.pendingTreadmill.value!!
        TreadmillEngine.start(plan, 0L)
        vm.go(Screen.TreadmillPlayer)
        var now = 0L
        while (!TreadmillEngine.ui.value.finished) { now += 1000; TreadmillEngine.tick(now) }
        idle()
        show(vm)
        systemBack()
        assertNoDialog()
        assertEquals(Screen.TreadmillDone, vm.screen.value)
        assertEquals(1, onDisk().records.size)
    }

    // ------------------------------------------------------------------------------------ guided timer

    private fun easy(minutes: Int = 45) = PlannedSession(
        today, SessionKind.EASY, "Easy row", minutes, Intensity.EASY, 0, 2..4,
        listOf(Modality.ROW, Modality.SKIERG, Modality.BIKE), listOf("Easy aerobic work, RPE 4 or less."), "Why.",
        LaunchHint(SessionType.TREADMILL, TreadmillStyle.STEADY, emptySet(), minutes),
    )

    @Test fun guidedTimerSetupGoesBackToThePlanAndRunningAsks() {
        val vm = newVm()
        show(vm)
        vm.go(Screen.RacePlan)
        vm.launchPlanned(easy())
        rule.waitForIdle()
        assertEquals(Screen.GuidedTimer(easy()), vm.screen.value)
        systemBack()
        assertEquals(Screen.RacePlan, vm.screen.value)
        assertEquals(GuidedPhase.SETUP, vm.guided.ui.value.phase)

        vm.launchPlanned(easy())
        vm.guided.start()
        repeat(5 * 60) { vm.guided.tick(1000) }
        rule.waitForIdle()
        systemBack()
        assertDialogShown()
        tapDialog("Keep going")
        assertEquals(GuidedPhase.RUNNING, vm.guided.ui.value.phase)
        assertEquals(emptyList(), onDisk().records)

        systemBack()
        tapDialog("End and save")
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(1, onDisk().records.size)
        assertEquals(GuidedPhase.SETUP, vm.guided.ui.value.phase)
    }

    @Test fun guidedTimerDiscardSavesNothingAndDoneScreenBackSaves() {
        val vm = newVm()
        show(vm)
        vm.launchPlanned(easy())
        vm.guided.start()
        repeat(5 * 60) { vm.guided.tick(1000) }
        rule.waitForIdle()
        systemBack()
        tapDialog("Discard")
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(emptyList(), onDisk().records)

        vm.launchPlanned(easy(10))
        vm.guided.start()
        var n = 0
        while (vm.guided.ui.value.phase == GuidedPhase.RUNNING && n++ < 10_000) vm.guided.tick(1000)
        idle(); rule.waitForIdle()
        assertEquals(1, onDisk().records.size, "autosaved when it ended")
        systemBack() // the rating card: like Save
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(1, onDisk().records.size)
    }

    @Test fun kneeCareSetupGoesBackAndRunningAsks() {
        val vm = newVm()
        show(vm)
        vm.launchKneeCare()
        rule.waitForIdle()
        assertEquals(Screen.KneeCare, vm.screen.value)
        systemBack()
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(KneeCarePhase.SETUP, vm.kneeCare.ui.value.phase)

        vm.launchKneeCare()
        vm.kneeCare.start()
        repeat(5 * 60) { vm.kneeCare.tick(1000) }
        rule.waitForIdle()
        systemBack()
        assertDialogShown()
        tapDialog("Keep going")
        assertEquals(KneeCarePhase.RUNNING, vm.kneeCare.ui.value.phase)
        assertEquals(emptyList(), onDisk().records)

        systemBack()
        tapDialog("Discard")
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(emptyList(), onDisk().records)

        vm.launchKneeCare()
        vm.kneeCare.start()
        repeat(5 * 60) { vm.kneeCare.tick(1000) }
        rule.waitForIdle()
        systemBack()
        tapDialog("End and save")
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(1, onDisk().records.size)
        assertTrue(onDisk().records.single().isKneeCare)
        assertEquals(KneeCarePhase.SETUP, vm.kneeCare.ui.value.phase)
    }

    // ------------------------------------------------------------------------------------ hyrox session

    private fun HyroxController.lapsToRating() {
        while (ui.value.step != HyroxStep.RATE) {
            if (ui.value.step == HyroxStep.READY) { go(); advance(20_000); lapDone() }
            if (ui.value.step == HyroxStep.REST) { advance(ui.value.restTotalSec * 1000L); tick() }
        }
    }

    @Test fun hyroxSessionAsksAndEndAndSaveKeepsTheStationsDone() {
        val vm = newVm()
        show(vm)
        vm.go(Screen.Start)
        vm.begin(SessionType.HYROX, 45)
        vm.hyrox.start()
        vm.hyrox.lapsToRating(); vm.hyrox.setRpe(7); vm.hyrox.setTechnique(3); vm.hyrox.saveBlock()
        rule.waitForIdle()
        assertEquals(HyroxPhase.RUNNING, vm.hyrox.ui.value.phase)
        systemBack()
        assertDialogShown()
        tapDialog("Keep going")
        assertEquals(HyroxPhase.RUNNING, vm.hyrox.ui.value.phase)
        assertEquals(emptyList(), onDisk().records)

        systemBack()
        tapDialog("End and save")
        assertEquals(Screen.Home, vm.screen.value)
        val rec = onDisk().records.single()
        assertEquals(SessionType.HYROX, rec.type)
        assertEquals(1, rec.stations.size)
        assertEquals(HyroxPhase.SETUP, vm.hyrox.ui.value.phase)
        assertEquals(emptyList(), vm.backStackSnapshot)
    }

    @Test fun hyroxSessionDiscardSavesNothing() {
        val vm = newVm()
        show(vm)
        vm.begin(SessionType.HYROX, 45)
        vm.hyrox.start()
        vm.hyrox.lapsToRating(); vm.hyrox.setRpe(7); vm.hyrox.setTechnique(3); vm.hyrox.saveBlock()
        rule.waitForIdle()
        systemBack()
        tapDialog("Discard")
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(emptyList(), onDisk().records)
        assertEquals(HyroxPhase.SETUP, vm.hyrox.ui.value.phase)
    }

    @Test fun hyroxSessionResultsBackSavesLikeTheSaveButton() {
        val vm = newVm()
        show(vm)
        vm.begin(SessionType.HYROX, 45)
        vm.hyrox.start()
        while (vm.hyrox.ui.value.phase == HyroxPhase.RUNNING) {
            vm.hyrox.lapsToRating(); vm.hyrox.setRpe(7); vm.hyrox.setTechnique(3); vm.hyrox.saveBlock()
        }
        advance(60_000)
        rule.waitForIdle()
        assertEquals(HyroxPhase.DONE, vm.hyrox.ui.value.phase)
        systemBack()
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(1, onDisk().records.size)
    }

    // ------------------------------------------------------------------------------------ hyrox simulation

    private fun AppViewModel.startRace() { sim.start(); advance(HyroxSimController.LEAD_IN_MS) }
    private fun AppViewModel.leg(sec: Int) {
        advance(sec * 1000L); sim.legDone()
        if (sim.ui.value.inTransition) { advance(20_000); sim.legDone() }
    }

    @Test fun runningSimulationAsksAndEndAndSaveShowsTheSavedResults() {
        val vm = newVm()
        show(vm)
        vm.go(Screen.Hyrox)
        vm.simOpen(SimPreset(SimFormat.FIRST_HALF))
        vm.startRace(); vm.leg(100); vm.leg(200)
        idle(); rule.waitForIdle()
        assertEquals(SimPhase.RUNNING, vm.sim.ui.value.phase)
        systemBack()
        assertDialogShown()
        tapDialog("Keep going")
        assertEquals(SimPhase.RUNNING, vm.sim.ui.value.phase)

        systemBack()
        tapDialog("End and save")
        assertEquals(SimPhase.DONE, vm.sim.ui.value.phase)
        assertEquals(Screen.HyroxSim(SimPreset(SimFormat.FIRST_HALF)), vm.screen.value)
        assertEquals(1, onDisk().records.size)
        systemBack() // results card: the record is already saved
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(1, onDisk().records.size)
        assertEquals(SimPhase.SETUP, vm.sim.ui.value.phase)
        assertEquals(emptyList(), vm.backStackSnapshot)
    }

    @Test fun runningSimulationDiscardSavesNothingAndSetupGoesBackToTheHub() {
        val vm = newVm()
        show(vm)
        vm.go(Screen.Hyrox)
        vm.simOpen(SimPreset(SimFormat.FIRST_HALF))
        rule.waitForIdle()
        systemBack()
        assertEquals(Screen.Hyrox, vm.screen.value, "the setup is an ordinary screen")
        assertEquals(listOf(Screen.Home), vm.backStackSnapshot)

        vm.simOpen(SimPreset(SimFormat.FIRST_HALF))
        vm.startRace(); vm.leg(100)
        idle(); rule.waitForIdle()
        systemBack()
        tapDialog("Discard")
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(emptyList(), onDisk().records)
        assertEquals(SimPhase.SETUP, vm.sim.ui.value.phase)
    }
}
