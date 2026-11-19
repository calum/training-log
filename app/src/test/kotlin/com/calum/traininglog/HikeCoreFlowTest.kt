package com.calum.traininglog

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.CoreOutcome
import com.calum.traininglog.core.CoreRoutine
import com.calum.traininglog.core.Effort
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The guided hike / walk stopwatch (autosave, beeps, pause) and the mat routine's progression, through the real ViewModel and disk. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = Application::class)
class HikeCoreFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as Application
    private var today = LocalDate.of(2026, 6, 10)
    private fun newVm() = AppViewModel(app, clock = { today })
    private fun onDisk() = Store(app).load()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) { ShadowSystemClock.advanceBy(Duration.ofMillis(ms)) }
    private fun minutes(m: Int) = m * 60_000L

    @Before fun setUp() { TreadmillEngine.stop() }
    @After fun tearDown() { TreadmillEngine.stop() }

    private fun show(vm: AppViewModel) { rule.setContent { AppRoot(vm, inPip = false) }; rule.waitForIdle() }
    private fun systemBack() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }
    private fun tapDialog(text: String) { rule.onNode(hasText(text) and hasAnyAncestor(isDialog())).performClick(); rule.waitForIdle(); idle() }

    // ------------------------------------------------------------------------------------ hike

    @Test fun aGuidedHikeBeepsPausesAndIsSavedTheMomentItFinishes() {
        val vm = newVm()
        vm.begin(SessionType.HIKE)
        assertEquals(Screen.Light(SessionType.HIKE), vm.screen.value)
        assertEquals(HikePhase.SETUP, vm.hike.ui.value.phase)
        assertNull(vm.hike.ui.value.suggestion, "no earlier hike, no offer")
        vm.hike.setTarget(60)
        vm.hike.start()
        assertEquals(HikePhase.RUNNING, vm.hike.ui.value.phase)

        advance(minutes(29)); assertNull(vm.hike.tick())
        advance(minutes(2)); assertEquals(HikeCue.HALFWAY, vm.hike.tick())
        assertNull(vm.hike.tick(), "the halfway beep sounds once")

        // Paused time is not counted.
        vm.hike.togglePause()
        advance(minutes(10))
        assertEquals(minutes(31), vm.hike.elapsedMs())
        assertNull(vm.hike.tick())
        vm.hike.togglePause()
        advance(minutes(29))
        assertEquals(HikeCue.FINISH, vm.hike.tick())
        assertNull(vm.hike.tick())

        assertEquals(emptyList(), onDisk().records, "nothing saved while it is running")
        advance(minutes(5))
        vm.hike.finish()
        idle()
        // Saved at once, before any card is shown.
        val saved = onDisk().records.single()
        assertEquals(SessionType.HIKE, saved.type)
        assertEquals(65, saved.minutesActual)
        assertEquals(60, saved.minutesAvailable)
        assertNull(saved.note)
        assertEquals(HikePhase.DONE, vm.hike.ui.value.phase)
        assertEquals(saved.id, vm.hike.ui.value.savedId)
        assertEquals(minutes(65), vm.hike.elapsedMs()) // frozen

        // The finish card only edits that record: distance, climb, feeling, note.
        vm.hike.adjustDistance(8.0); vm.hike.adjustDistance(0.5); vm.hike.adjustDistance(-0.5)
        vm.hike.adjustClimb(450); vm.hike.adjustClimb(-50)
        vm.hike.setFeeling(Feeling.OKAY)
        vm.hike.setNote("Windy ridge")
        vm.saveHike()
        assertEquals(Screen.Home, vm.screen.value)
        val edited = onDisk().records.single()
        assertEquals(saved.id, edited.id)
        assertEquals("8 km, 400 m climb. Windy ridge", edited.note)
        assertEquals(Feeling.OKAY, edited.feeling)
        assertEquals(HikePhase.SETUP, vm.hike.ui.value.phase)
    }

    @Test fun finishingWithoutSavingStillKeepsTheHike() {
        val vm = newVm()
        vm.begin(SessionType.HIKE)
        vm.hike.start()
        advance(minutes(42))
        vm.hike.finish()
        idle()
        assertEquals(42, onDisk().records.single().minutesActual)
        // Leaving through system back on the finish card (without pressing Done) also adds no duplicate.
        vm.saveHike()
        assertEquals(1, onDisk().records.size)
    }

    @Test fun aHikeUnderAMinuteLogsNothing() {
        val vm = newVm()
        vm.begin(SessionType.HIKE)
        vm.hike.start()
        advance(30_000)
        vm.hike.finish()
        idle()
        vm.saveHike()
        assertEquals(emptyList(), onDisk().records)
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test fun noTargetMeansNoBeeps() {
        val vm = newVm()
        vm.begin(SessionType.HIKE)
        vm.hike.start()
        advance(minutes(200))
        assertNull(vm.hike.tick())
    }

    @Test fun theNextWalkIsOfferedAboutTenPercentLonger() {
        val vm = newVm()
        vm.saveManual(SessionType.HIKE, daysAgo = 6, minutes = 90, style = null, note = "")
        vm.begin(SessionType.HIKE)
        val s = assertNotNull(vm.hike.ui.value.suggestion)
        assertEquals(100, s.minutes)
        assertEquals(90, s.basedOnMinutes)
    }

    @Test fun leavingARunningHikeAsksAndEndAndSaveKeepsIt() {
        val vm = newVm()
        show(vm)
        vm.begin(SessionType.HIKE)
        vm.hike.start()
        rule.waitForIdle()
        advance(minutes(20))
        systemBack()
        rule.onNodeWithText("Leave this session?").assertExists()
        tapDialog("Keep going")
        assertEquals(HikePhase.RUNNING, vm.hike.ui.value.phase)
        systemBack()
        tapDialog("End and save")
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(20, onDisk().records.single().minutesActual)
    }

    @Test fun theQuickLogForAWalkAlreadyDoneStillWorks() {
        val vm = newVm()
        show(vm)
        vm.begin(SessionType.HIKE)
        rule.waitForIdle()
        rule.onNodeWithText("Already done it? Log it").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Log it").assertExists()
        vm.saveLight(SessionType.HIKE, 75, "Ridge")
        assertEquals(75, onDisk().records.single().minutesActual)
    }

    // ------------------------------------------------------------------------------------ core / mat progression

    /** Runs the prepared core session to its end on the controller's own clock. */
    private fun runCoreToTheEnd(vm: AppViewModel) {
        vm.core.start()
        while (vm.core.ui.value.phase == CorePhase.RUNNING) vm.core.tick(1000)
        assertEquals(CorePhase.DONE, vm.core.ui.value.phase)
    }

    @Test fun theMatRoutineProgressesAcrossTwoSessions() {
        val vm = newVm()
        vm.begin(SessionType.CORE, 20)
        val seed = vm.core.ui.value.seed
        val first = vm.core.ui.value.session!!
        assertTrue(first.progress.isEmpty(), "nothing to progress on a first session")
        runCoreToTheEnd(vm)
        vm.saveCore(Effort.JUST_RIGHT)
        idle()
        val progress = onDisk().profile.coreProgress
        assertEquals(first.exerciseIds.toSet(), progress.keys, "every finished exercise is remembered")
        assertTrue(progress.values.all { it.outcome == CoreOutcome.PROGRESS && it.workSec > 0 && it.lastDone == today })
        assertEquals(1, onDisk().records.size)

        // The same mix again: no hold is shorter, some are +5 s, and the session is still 20 minutes.
        val second = CoreRoutine.build(20, seed = seed, progress = progress)
        assertEquals(first.exerciseIds, second.exerciseIds)
        assertEquals(20 * 60, second.totalSec)
        val work = { s: com.calum.traininglog.core.CoreSession -> s.steps.filter { it.kind == com.calum.traininglog.core.CoreKind.WORK } }
        assertTrue(work(first).zip(work(second)).all { (a, b) -> b.seconds >= a.seconds })
        assertTrue(work(first).zip(work(second)).any { (a, b) -> b.seconds == a.seconds + 5 })
        assertTrue(second.progress.isNotEmpty() && second.progress.all { it.up })
    }

    @Test fun progressRunsThroughTheViewModelAndShowsOnTheNextSetup() {
        val vm = newVm()
        vm.begin(SessionType.CORE, 20)
        val first = vm.core.ui.value.session!!
        val pick = first.exerciseIds.first()
        val firstSec = first.steps.first { it.exerciseId == pick }.seconds
        // Last time this exercise was done at its current length, finished and fine: this time it gets +5 s (when the cycle allows).
        vm.updateProfile { p ->
            p.copy(coreProgress = mapOf(pick to com.calum.traininglog.core.CoreProgressEntry(firstSec, 0, today.minusDays(3), CoreOutcome.PROGRESS)))
        }
        vm.coreSetup(reshuffle = false)
        val s = vm.core.ui.value.session!!
        val sec = s.steps.first { it.exerciseId == pick }.seconds
        assertTrue(sec >= firstSec)
        assertEquals(20 * 60, s.totalSec)
        if (sec > firstSec) {
            val note = s.progress.single { it.exerciseId == pick }
            assertTrue(note.up && note.text.contains("$firstSec s -> $sec s"), note.text)
        }
    }

    @Test fun tooHardOrSkippedStepsBack() {
        val vm = newVm()
        vm.begin(SessionType.CORE, 20)
        val first = vm.core.ui.value.session!!
        vm.core.start()
        // Skip the first work step, finish the rest.
        val firstWorkIdx = first.steps.indexOfFirst { it.kind == com.calum.traininglog.core.CoreKind.WORK }
        repeat(firstWorkIdx) { vm.core.skip() } // warm-up steps skipped: harmless
        val skippedId = first.steps[firstWorkIdx].exerciseId!!
        vm.core.skip()
        while (vm.core.ui.value.phase == CorePhase.RUNNING) vm.core.tick(1000)
        vm.saveCore(Effort.TOO_HARD)
        idle()
        val p = onDisk().profile.coreProgress
        assertEquals(CoreOutcome.BACK, p.getValue(skippedId).outcome)
        assertTrue(p.values.all { it.outcome == CoreOutcome.BACK }, "too hard steps everything back: $p")
    }

    // ------------------------------------------------------------------------------------ autosave safety

    @Test fun aFinishedMatSessionIsSavedAtOnceAndTheCardOnlyEditsIt() {
        val vm = newVm()
        vm.begin(SessionType.CORE, 20)
        runCoreToTheEnd(vm)
        idle()
        val saved = onDisk().records.single()
        assertEquals(SessionType.CORE, saved.type)
        assertEquals(20, saved.minutesActual)
        assertEquals(saved.id, vm.core.ui.value.savedId)
        assertEquals(saved.id, vm.celebration.value!!.record.id)
        assertTrue(onDisk().profile.coreProgress.values.all { it.outcome == CoreOutcome.PROGRESS })

        // Force-close on the finish card: a new ViewModel from disk still has the session.
        assertEquals(1, newVm().data.value.records.size)

        vm.saveCore(Effort.TOO_HARD)
        val d = onDisk()
        assertEquals(1, d.records.size, "no duplicate")
        assertEquals(saved.id, d.records.single().id)
        assertTrue(d.records.single().note!!.contains("too hard"))
        assertTrue(d.profile.coreProgress.values.all { it.outcome == CoreOutcome.BACK }, "progression recomputed once, not stacked")
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test fun endingAMatSessionEarlyKeepsItAndUnderAMinuteDoesNot() {
        val vm = newVm()
        vm.begin(SessionType.CORE, 20)
        vm.core.start()
        repeat(150) { vm.core.tick(1000) }
        vm.core.end()
        idle()
        assertEquals(1, onDisk().records.size)
        vm.saveCore(null)
        assertEquals(1, onDisk().records.size)

        vm.begin(SessionType.CORE, 20)
        vm.core.start()
        repeat(30) { vm.core.tick(1000) }
        vm.core.end()
        idle()
        vm.saveCore(null)
        assertEquals(1, onDisk().records.size)
    }

    @Test fun forceClosingTheHikeFinishCardLeavesItInHistoryWithoutADuplicate() {
        val vm = newVm()
        vm.begin(SessionType.HIKE)
        vm.hike.start()
        advance(minutes(50))
        vm.hike.finish()
        idle()
        assertEquals(1, newVm().data.value.records.size)
        vm.saveHike()
        assertEquals(1, onDisk().records.size)
    }
}
