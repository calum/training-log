package com.calum.traininglog

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.ExerciseLog
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.LoggedSet
import com.calum.traininglog.core.PlannedSession
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.RacePlanSettings
import com.calum.traininglog.core.RacePlanner
import com.calum.traininglog.core.SessionKind
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.TreadmillLog
import com.calum.traininglog.core.TreadmillStyle
import com.calum.traininglog.core.UserSettings
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "How much time do you have for lifting?": the time chips and the prehab switch on Today's session, through the real ViewModel, store and screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = Application::class)
class GymTimeChipsFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as Application
    private val today = LocalDate.of(2026, 11, 20) // a Friday
    private val cfg = RacePlanSettings(sessionsPerWeek = 4, maxWeeklyMinutes = 300)
    private val profile = Profile(settings = UserSettings(racePlan = cfg), restWeekdays = emptySet())
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun onDisk() = Store(app).load()

    @Before fun setUp() { TreadmillEngine.stop() }
    @After fun tearDown() { TreadmillEngine.stop() }

    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)
    private fun sets(kg: Double, n: Int, reps: Int) = List(n) { LoggedSet(kg, reps, reps, 2) }
    private fun gym(date: LocalDate, vararg logs: ExerciseLog) =
        SessionRecord("h-$date", date, SessionType.GYM, 40, 40, Feeling.GOOD, exercises = logs.toList())

    private fun walk(date: LocalDate, min: Int) = SessionRecord(
        "w-$date", date, SessionType.TREADMILL, min, min, Feeling.GOOD,
        treadmill = TreadmillLog(TreadmillStyle.STEADY, min * 60, min * 60, distanceM = min * 85),
    )

    /** His real gym sessions plus the walks around them (the plan's kick-off strength session is 35 min: 25 lifting + 10 prehab). */
    private fun history() = listOf(
        walk(d(10, 30), 30), walk(d(11, 2), 30), walk(d(11, 6), 45), walk(d(11, 8), 30), walk(d(11, 15), 20), walk(d(11, 18), 30), walk(d(11, 19), 30),
        gym(d(11, 1), ExerciseLog("squat", sets(72.5, 3, 5)), ExerciseLog("rdl", sets(62.5, 2, 8)), ExerciseLog("calf", sets(60.0, 3, 12)),
            ExerciseLog("lat_pulldown", sets(50.0, 3, 10)), ExerciseLog("cable_row", sets(47.5, 3, 10))),
        gym(d(11, 5), ExerciseLog("squat", sets(72.5, 3, 5)), ExerciseLog("deadlift", sets(90.0, 2, 3)), ExerciseLog("calf", sets(62.5, 3, 12))),
        gym(d(11, 12), ExerciseLog("squat", sets(75.0, 3, 5)), ExerciseLog("rdl", sets(65.0, 2, 8)), ExerciseLog("calf", sets(65.0, 3, 12)),
            ExerciseLog("machine_chest", sets(40.0, 3, 10)), ExerciseLog("db_shoulder", sets(12.5, 3, 10))),
        gym(d(11, 16), ExerciseLog("squat", sets(75.0, 3, 5)), ExerciseLog("deadlift", sets(95.0, 2, 3)), ExerciseLog("calf", sets(65.0, 3, 12)),
            ExerciseLog("lat_pulldown", sets(42.5, 3, 10)), ExerciseLog("cable_row", sets(42.5, 3, 10))),
    )

    private fun newVm(): AppViewModel {
        Fixtures.seed(app, history(), profile)
        return AppViewModel(app, clock = { today })
    }

    private fun kickoff(): PlannedSession =
        assertNotNull(RacePlanner.build(cfg, history(), today, profile).leadIn).sessions.first { it.kind == SessionKind.STRENGTH }

    private fun ids(vm: AppViewModel) = vm.gym.value!!.exercises.map { it.planned.exercise.id }
    private fun AppViewModel.doAllSets() = gym.value!!.exercises.forEachIndexed { ei, e -> e.sets.indices.forEach { si -> doneSet(ei, si) } }

    private fun startNormal(vm: AppViewModel, minutes: Int) {
        vm.setInputs { it.copy(minutes = minutes, feeling = Feeling.GOOD) }
        vm.begin(SessionType.GYM, minutes)
        assertEquals(Screen.Gym, vm.screen.value)
    }

    @Test fun plannedKickOffChipsFrom20To40BringAnUpperLiftBackAndUpdateTheOverview() {
        val vm = newVm()
        vm.launchPlanned(kickoff())
        var g = vm.gym.value!!
        assertEquals(25, g.windowMinutes)
        assertEquals(listOf("squat", "rdl", "calf"), ids(vm))
        var o = GymGuide.overview(g)
        val t = assertNotNull(o.time)
        assertEquals(25, t.selected); assertEquals(25, t.plannedWindow)
        assertEquals(listOf(20, 25, 30, 40, 50, 60), t.windows)
        assertEquals(10, t.prehabMinutes); assertTrue(t.prehabOn)
        assertNotNull(o.prehabLine)

        vm.setGymWindow(20)
        g = vm.gym.value!!
        o = GymGuide.overview(g)
        assertEquals(20, g.windowMinutes)
        assertEquals(listOf("squat", "rdl", "calf"), ids(vm))
        assertEquals(30, g.minutesAvailable, "20 lifting + 10 prehab")
        assertTrue(o.minutes <= 20)
        assertEquals(20, o.time!!.selected)
        assertEquals(25, o.time!!.plannedWindow, "the plan's window is still the labelled one")
        val at20 = o.rows.map { it.line }

        vm.setGymWindow(40)
        g = vm.gym.value!!
        o = GymGuide.overview(g)
        assertEquals(40, g.windowMinutes)
        assertEquals(5, ids(vm).size, "two upper lifts at 40")
        assertEquals(listOf("squat", "rdl", "calf"), ids(vm).take(3))
        assertEquals("Added: Machine chest press, Dumbbell shoulder press", o.time!!.change)
        assertNull(o.trimNote, "nothing is trimmed or left out at 40")
        assertTrue(o.minutes <= 40 && o.minutes > 30, "${o.minutes}")
        assertEquals(5, o.rows.size)
        assertTrue(o.rows.map { it.line }.take(3) != at20.take(3), "the sets x reps changed too")
        assertEquals(50, g.minutesAvailable)
        assertNotNull(o.prehabLine)
        assertEquals(40, g.windowMinutes)

        vm.setGymWindow(30)
        assertEquals(4, ids(vm).size, "30 minutes: squat, RDL, calf and one upper lift")
        assertEquals("Left out: Dumbbell shoulder press", GymGuide.overview(vm.gym.value!!).time!!.change)
        assertEquals(listOf(3, 2, 3), vm.gym.value!!.exercises.take(3).map { it.sets.size })
        vm.setGymWindow(60)
        assertEquals(5, ids(vm).size)
    }

    @Test fun switchingThePrehabOffGrowsTheWindowAndNoPrehabPromptFollows() {
        val vm = newVm()
        vm.launchPlanned(kickoff())
        assertEquals(25, vm.gym.value!!.windowMinutes)
        vm.setGymPrehab(false)
        var g = vm.gym.value!!
        assertEquals(35, g.windowMinutes)
        assertEquals(0, g.prehabMinutes)
        assertEquals(4, ids(vm).size, "35 lifting minutes bring one upper lift")
        var o = GymGuide.overview(g)
        assertNull(o.prehabLine, "the 'Then: hip prehab' line is gone")
        assertFalse(o.time!!.prehabOn)
        assertEquals(35, o.time!!.plannedWindow, "planned total, prehab off")
        assertEquals(35, g.minutesAvailable)

        vm.setGymPrehab(true)
        g = vm.gym.value!!
        assertEquals(25, g.windowMinutes); assertEquals(10, g.prehabMinutes)
        assertNotNull(GymGuide.overview(g).prehabLine)
        assertEquals(35, g.minutesAvailable)

        vm.setGymPrehab(false)
        vm.doAllSets()
        vm.finishGym(); idle()
        assertFalse(vm.gym.value?.prehabOffered ?: false, "no prehab prompt")
        assertEquals(Screen.Home, vm.screen.value)
        val rec = onDisk().records.last()
        assertEquals(SessionType.GYM, rec.type)
        assertEquals(35, rec.minutesAvailable)
    }

    @Test fun withThePrehabOnTheRecordKeepsWindowPlusPrehabAndThePromptStillFollows() {
        val vm = newVm()
        vm.launchPlanned(kickoff())
        vm.setGymWindow(40)
        vm.doAllSets()
        vm.finishGym(); idle()
        assertTrue(vm.gym.value!!.prehabOffered)
        assertEquals(50, onDisk().records.last().minutesAvailable)
        assertEquals(5, onDisk().records.last().exercises.size)
    }

    @Test fun chipsAreGoneAfterTheFirstSetAndATapChangesNothing() {
        val vm = newVm()
        vm.launchPlanned(kickoff())
        vm.setGymWindow(40)
        assertNotNull(GymGuide.timeControl(vm.gym.value!!))
        vm.startGuidedSession(); vm.startExercise(0)
        assertNull(GymGuide.timeControl(vm.gym.value!!), "started: no chips")
        vm.setGymWindow(20); vm.setGymPrehab(false)
        assertEquals(40, vm.gym.value!!.windowMinutes)
        assertEquals(5, ids(vm).size)
        assertTrue(vm.gym.value!!.prehabMinutes > 0)
    }

    @Test fun listModeShowsTheChipsUntilTheFirstSetIsLogged() {
        val vm = newVm()
        startNormal(vm, 60)
        vm.setGymGuided(false) // the Overview (all sets) list
        assertEquals(40, vm.gym.value!!.windowMinutes)
        rule.setContent { AppRoot(vm, inPip = false) }
        rule.waitForIdle()
        rule.onNodeWithText("Time for lifting").assertIsDisplayed()
        rule.onNodeWithText("25 min").performClick(); rule.waitForIdle(); idle()
        assertEquals(25, vm.gym.value!!.windowMinutes)
        vm.doneSet(0, 0)
        rule.waitForIdle()
        rule.onNodeWithText("Time for lifting").assertDoesNotExist()
    }

    @Test fun tappingAChipOnTheRealOverviewUpdatesTheCard() {
        val vm = newVm()
        vm.launchPlanned(kickoff())
        rule.setContent { AppRoot(vm, inPip = false) }
        rule.waitForIdle()
        rule.onNodeWithText("How much time do you have for lifting?").assertIsDisplayed()
        rule.onNodeWithText("25 min · planned").assertIsDisplayed()
        rule.onNodeWithText("Include the hip prehab").assertIsDisplayed()
        rule.onNodeWithText("Then: hip prehab (about 10 min)").assertIsDisplayed()
        rule.onNodeWithText("40 min").performClick(); rule.waitForIdle(); idle()
        assertEquals(40, vm.gym.value!!.windowMinutes)
        rule.onNodeWithText("Added: Machine chest press, Dumbbell shoulder press").assertIsDisplayed()
        rule.onNodeWithText("5 exercises, about ${vm.gym.value!!.plan.estimatedMinutes} min").assertIsDisplayed()
    }

    @Test fun startFromTheStartScreenLandsOnTheOverviewWithTheLiftingWindowSelectedAndItSavesLikeANormalSession() {
        val vm = newVm()
        startNormal(vm, 75) // 75 minutes less travel = 55 lifting
        assertTrue(GymGuide.showOverview(vm.gym.value!!))
        val t = GymGuide.overview(vm.gym.value!!).time!!
        assertEquals(55, t.selected); assertNull(t.plannedWindow); assertEquals(0, t.prehabMinutes)
        assertTrue(55 in t.windows)
        vm.setGymWindow(30)
        assertEquals(30, vm.gym.value!!.windowMinutes)
        assertEquals(50, vm.gym.value!!.minutesAvailable, "30 lifting + 20 travel")
        val chosen = vm.gym.value!!.exercises.map { it.planned.exercise.id to it.sets.map { s -> s.kg to s.reps } }

        // Identical to a session that was started for that window in the first place.
        val vm2 = newVm()
        startNormal(vm2, 50)
        assertEquals(chosen, vm2.gym.value!!.exercises.map { it.planned.exercise.id to it.sets.map { s -> s.kg to s.reps } })
        assertEquals(vm2.gym.value!!.plan.estimatedMinutes, vm.gym.value!!.plan.estimatedMinutes)

        vm.doAllSets(); vm.finishGym(); idle()
        val rec = onDisk().records.last()
        assertEquals(50, rec.minutesAvailable)
        assertEquals(SessionType.GYM, rec.type)
        assertEquals(chosen.map { it.first }, rec.exercises.map { it.exerciseId })
        assertEquals(chosen.map { c -> c.second.map { it.first } }, rec.exercises.map { e -> e.sets.map { it.weightKg } })
        assertEquals(Screen.Home, vm.screen.value)
        // Settings are untouched by a chip.
        assertEquals(profile.settings, onDisk().profile.settings)
    }

    @Test fun theChipsRebuildWithTheSameHistoryAsTheOriginalStart() {
        val vm = newVm()
        startNormal(vm, 120)
        val full = vm.gym.value!!
        vm.setGymWindow(60)
        val g = vm.gym.value!!
        // Same loads as the original start for the exercises both contain (same records, easing back in, settings).
        g.exercises.forEach { e ->
            val orig = full.exercises.firstOrNull { it.planned.exercise.id == e.planned.exercise.id } ?: return@forEach
            assertEquals(orig.sets.map { it.kg }, e.sets.map { it.kg })
        }
        assertEquals(full.plan.deload, g.plan.deload)
    }
}
