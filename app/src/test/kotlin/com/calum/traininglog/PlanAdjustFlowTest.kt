package com.calum.traininglog

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.HealthLevel
import com.calum.traininglog.core.PlanHealth
import com.calum.traininglog.core.RacePlanner
import com.calum.traininglog.core.ReplanExport
import com.calum.traininglog.core.SessionKind
import com.calum.traininglog.core.SkipReason
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Move, skip, time suggestion and the replan alert through the real ViewModel, disk and screens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h2000dp-xxhdpi", application = Application::class)
class PlanAdjustFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as Application
    private var today = LocalDate.of(2026, 11, 25) // Wednesday of week 1
    private fun newVm() = AppViewModel(app, clock = { today })
    private fun onDisk() = Store(app).load()

    @Before fun setUp() { TreadmillEngine.stop() }
    @After fun tearDown() { TreadmillEngine.stop() }

    private fun seed(profile: com.calum.traininglog.core.Profile = PlanAdjustFixtures.profile()) =
        Fixtures.seed(app, PlanAdjustFixtures.week1(), profile)

    private fun plan(vm: AppViewModel) = RacePlanner.build(PlanAdjustFixtures.mine, vm.data.value.records, today, vm.data.value.profile)

    @Test fun moveASessionAndItSurvivesARestart() {
        seed()
        val vm = newVm()
        val key = plan(vm).weeks[0].sessions.first { it.kind == SessionKind.KEY }
        val thu = today.plusDays(1)
        vm.updateProfile { RacePlanner.addMove(it, key, thu, today) }
        val moved = plan(vm).weeks[0].sessions.first { it.kind == SessionKind.KEY }
        assertEquals(thu, moved.date)
        assertEquals(today, moved.movedFrom)
        // Restart: a new ViewModel reads the same file.
        assertEquals(1, onDisk().profile.planAdjustments.moves.size)
        assertEquals(thu, plan(newVm()).weeks[0].sessions.first { it.kind == SessionKind.KEY }.date)
        vm.updateProfile { RacePlanner.undo(it, moved) }
        assertEquals(today, plan(vm).weeks[0].sessions.first { it.kind == SessionKind.KEY }.date)
        assertTrue(onDisk().profile.planAdjustments.moves.isEmpty())
    }

    @Test fun skipWithAReasonShowsTheReflowAndCanBeUndone() {
        seed()
        val vm = newVm()
        val key = plan(vm).weeks[0].sessions.first { it.kind == SessionKind.KEY }
        val (np, msg) = skipWithMessage(vm.data.value.profile, vm.data.value.records, today, key, SkipReason.NO_TIME)
        assertEquals("Skipped. Moved to Thursday.", msg)
        vm.updateProfile { np }
        val w = plan(vm).weeks[0]
        assertEquals(1, w.skippedSessions.size)
        assertEquals(SkipReason.NO_TIME, w.skippedSessions.single().skipReason)
        assertEquals(1, onDisk().profile.planAdjustments.skips.size)
        // Skipping the last session of the week: dropped, nothing carried over.
        val long = w.sessions.first { it.kind == SessionKind.LONG_EASY }
        val (np2, msg2) = skipWithMessage(vm.data.value.profile, vm.data.value.records, today, long, SkipReason.OTHER)
        assertEquals("Skipped. Not this week, no catching up.", msg2)
        vm.updateProfile { np2 }
        assertEquals(w.plannedMinutes - long.minutes, plan(vm).weeks[0].plannedMinutes)
        vm.updateProfile { RacePlanner.undo(RacePlanner.undo(it, w.skippedSessions.single()), plan(vm).weeks[0].skippedSessions.first { s -> s.kind == SessionKind.LONG_EASY }) }
        assertEquals(0, plan(vm).weeks[0].skippedSessions.size)
    }

    @Test fun timeBasedSuggestionStartsThePlannedSession() {
        seed()
        val vm = newVm()
        vm.go(Screen.Start)
        vm.setInputs { it.copy(minutes = 45, feeling = Feeling.GOOD) }
        assertEquals(null, vm.timeSuggestion.value)
        vm.recommend()
        val ts = assertNotNull(vm.timeSuggestion.value)
        assertEquals(SessionKind.KEY, ts.primary.kind)
        assertNotNull(vm.recommendation.value)   // the non-plan recommender still answers
        vm.launchPlanned(ts.primary)
        assertFalse(vm.screen.value == Screen.Start)
        // Feeling unwell: rest.
        vm.go(Screen.Start)
        vm.setInputs { it.copy(feeling = Feeling.ILL) }
        vm.recommend()
        assertTrue(vm.timeSuggestion.value!!.isRest)
    }

    @Test fun replanAlertAppearsCopiesAndCanBeDismissed() {
        today = LocalDate.of(2026, 12, 6)
        seed()
        val vm = newVm()
        val health = PlanHealth.assess(plan(vm), vm.data.value.records, vm.data.value.profile, today)
        assertEquals(HealthLevel.SUGGEST, health.level)
        val text = ReplanExport.text(plan(vm), vm.data.value.records, vm.data.value.profile, today)
        assertTrue(text.contains("Please re-plan my Hyrox prep from here for 27 Feb 2027"))
        assertTrue(text.contains("Rest weekdays: Tuesday and Friday"))

        rule.setContent { CompositionLocalProvider(LocalToday provides today) { AppRoot(vm, inPip = false) } }
        rule.waitForIdle()
        rule.onNodeWithTag("replan-copy", useUnmergedTree = true).performScrollTo().performClick()
        val clip = (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!
        assertTrue(clip.getItemAt(0).text.toString().startsWith("RE-PLAN REQUEST 2026-12-06") || clip.getItemAt(0).text.toString().contains("RE-PLAN REQUEST"))
        rule.onNodeWithTag("replan-dismiss", useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(today.plusDays(5), vm.data.value.profile.planAdjustments.replanDismissedUntil)
        assertEquals(today.plusDays(5), onDisk().profile.planAdjustments.replanDismissedUntil)
        assertEquals(HealthLevel.NONE, PlanHealth.assess(plan(vm), vm.data.value.records, vm.data.value.profile, today).level)
    }

    @Test fun skipThroughTheScreenAndRestDayChipsPersist() {
        seed()
        val vm = newVm()
        vm.go(Screen.RacePlan)
        rule.setContent { CompositionLocalProvider(LocalToday provides today) { AppRoot(vm, inPip = false) } }
        rule.waitForIdle()
        rule.onNodeWithTag("skip", useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithTag("reason-unwell", useUnmergedTree = true).performScrollTo().performClick()
        rule.onNodeWithTag("confirm-skip", useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()
        val skip = vm.data.value.profile.planAdjustments.skips.single()
        assertEquals(SkipReason.UNWELL, skip.reason)
        assertEquals(today, skip.recordedOn)
        assertEquals(1, onDisk().profile.planAdjustments.skips.size)
        // Rest-day chips: make Sunday a rest day too.
        rule.onNodeWithTag("restday-sun", useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(java.time.DayOfWeek.SUNDAY in onDisk().profile.restWeekdays)
        assertTrue(java.time.DayOfWeek.TUESDAY in newVm().data.value.profile.restWeekdays)
    }
}
