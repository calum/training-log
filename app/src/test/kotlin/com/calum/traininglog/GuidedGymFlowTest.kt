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
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.ExerciseLibrary
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.Intensity
import com.calum.traininglog.core.HyroxStation
import com.calum.traininglog.core.LaunchHint
import com.calum.traininglog.core.MissReason
import com.calum.traininglog.core.Modality
import com.calum.traininglog.core.PlannedSession
import com.calum.traininglog.core.SessionKind
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A whole GUIDED gym session (one set at a time) through the real [AppViewModel], [Store] and, where it matters, the real
 * screen. The helper [playGuided] presses what the screen's buttons press (the same ViewModel calls), so what is saved is
 * what a person would get.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = Application::class)
class GuidedGymFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as Application
    private var today = LocalDate.of(2026, 6, 10)
    private fun newVm() = AppViewModel(app, clock = { today })
    private fun onDisk() = Store(app).load()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) { ShadowSystemClock.advanceBy(Duration.ofMillis(ms)) }

    @Before fun setUp() { TreadmillEngine.stop() }
    @After fun tearDown() { TreadmillEngine.stop() }

    private fun show(vm: AppViewModel) { rule.setContent { AppRoot(vm, inPip = false) }; rule.waitForIdle() }
    /** Presses a button of the guided screen (the steps do not scroll). */
    private fun press(text: String) { rule.onNodeWithText(text).performClick(); rule.waitForIdle(); idle() }
    private fun tap(text: String) { rule.onNodeWithText(text).performScrollTo().performClick(); rule.waitForIdle(); idle() }
    private fun tapDialog(text: String) { rule.onNode(hasText(text) and hasAnyAncestor(isDialog())).performClick(); rule.waitForIdle(); idle() }
    private fun systemBack() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun startGym(vm: AppViewModel, minutes: Int = 75) {
        vm.setInputs { it.copy(minutes = minutes, feeling = Feeling.GOOD) }
        vm.begin(SessionType.GYM, minutes)
        assertEquals(Screen.Gym, vm.screen.value)
    }

    private fun step(vm: AppViewModel) = GymGuide.step(vm.gym.value!!)

    /**
     * Presses what the guided screen offers, step by step: Start on each intro card, Done, next on every warm-up and set (with
     * the optional effort [rir] after each set), and Skip rest. Stops at the finish step, or when [until] says so.
     */
    private fun playGuided(vm: AppViewModel, rir: Int? = 2, until: (GymStep) -> Boolean = { false }) {
        repeat(400) {
            val g = vm.gym.value ?: return
            if (g.rest != null) vm.clearRest()
            val s = GymGuide.step(vm.gym.value!!)
            if (until(s)) return
            when (s) {
                is GymStep.Intro -> vm.startExercise(s.ei)
                is GymStep.Warmup -> vm.completeWarmup(s.ei, s.wi)
                is GymStep.Work -> { vm.completeSet(s.ei, s.si); if (rir != null) vm.setRir(s.ei, s.si, rir) }
                GymStep.Finish -> return
            }
        }
        error("the guided session never reached its end")
    }

    /** Ticks every set of the list screen the way the list does: Done, then the effort chip. */
    private fun playList(vm: AppViewModel, rir: Int) {
        vm.gym.value!!.exercises.forEachIndexed { ei, e -> e.sets.indices.forEach { si -> vm.doneSet(ei, si); vm.setRir(ei, si, rir) } }
    }

    // ------------------------------------------------------------------------------------ same record as the list

    @Test fun aGuidedSessionSavesExactlyWhatAListSessionSaves() {
        val vm = newVm()
        startGym(vm)
        assertTrue(vm.gymGuided.value, "guided is the default")
        vm.setGymGuided(false)
        playList(vm, rir = 2)
        vm.finishGym()
        val listRecord = onDisk().records.single()

        vm.replaceData(vm.data.value.copy(records = emptyList()))
        startGym(vm)
        assertTrue(vm.gymGuided.value)
        playGuided(vm, rir = 2)
        assertEquals(GymStep.Finish, step(vm))
        vm.finishGym()
        val guidedRecord = onDisk().records.single()

        assertNotEquals(listRecord.id, guidedRecord.id)
        assertEquals(listRecord.copy(id = "", minutesActual = 0), guidedRecord.copy(id = "", minutesActual = 0))
        assertTrue(guidedRecord.exercises.all { e -> e.sets.all { it.rir == 2 } })
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test fun theSwitchBetweenGuidedAndListKeepsEverythingTicked() {
        val vm = newVm()
        startGym(vm)
        playGuided(vm) { it is GymStep.Work && it.ei == 0 && it.si == 1 } // squat: warm-ups and set 1 done
        vm.setGymGuided(false)
        assertTrue(vm.gym.value!!.exercises[0].sets[0].done)
        vm.doneSet(0, 1) // tick set 2 in the list ...
        vm.setGymGuided(true)
        assertEquals(GymStep.Intro(1), step(vm)) // ... which was the last squat set, so the guided screen moves to the next exercise
        val squatSets = vm.gym.value!!.exercises[0].sets.size
        vm.setGymGuided(false)
        vm.finishGym()
        assertEquals(squatSets, onDisk().records.single().exercises.first { it.exerciseId == "squat" }.sets.size)
    }

    @Test fun nextSessionAddsWeightWhenEffortWasLeftInTheTank() {
        val vm = newVm()
        startGym(vm)
        val squat0 = vm.gym.value!!.exercises.first { it.planned.exercise.id == "squat" }.sets.first().kg
        playGuided(vm, rir = 2)
        vm.finishGym()
        today = today.plusDays(2)
        startGym(vm)
        val squat1 = vm.gym.value!!.exercises.first { it.planned.exercise.id == "squat" }.sets.first().kg
        assertEquals(squat0 + ExerciseLibrary.squat.incrementKg, squat1)
        // The intro card of the next session says so.
        assertTrue(vm.gym.value!!.exercises.first { it.planned.exercise.id == "squat" }.planned.prescription.note.contains("add", ignoreCase = true))
    }

    @Test fun lowEffortKeepsTheSameWeight() {
        val vm = newVm()
        startGym(vm)
        val kg = vm.gym.value!!.exercises.first { it.planned.exercise.id == "squat" }.sets.first().kg
        playGuided(vm, rir = 0)
        vm.finishGym()
        today = today.plusDays(2)
        startGym(vm)
        assertEquals(kg, vm.gym.value!!.exercises.first { it.planned.exercise.id == "squat" }.sets.first().kg)
    }

    // ------------------------------------------------------------------------------------ steps

    @Test fun theStepsFollowTheOrderIntroWarmupsSetsThenTheNextExercise() {
        val vm = newVm()
        startGym(vm)
        val squat = vm.gym.value!!.exercises[0]
        assertEquals("squat", squat.planned.exercise.id)
        assertEquals(GymStep.Intro(0), step(vm))
        vm.startExercise(0)
        squat.warmups.indices.forEach { wi ->
            assertEquals(GymStep.Warmup(0, wi), step(vm))
            vm.completeWarmup(0, wi)
        }
        assertTrue(squat.warmups.size >= 3, "a squat warm-up ladder")
        squat.sets.indices.forEach { si ->
            assertEquals(GymStep.Work(0, si), step(vm))
            vm.completeSet(0, si)
            vm.clearRest()
        }
        assertEquals(GymStep.Intro(1), step(vm))
        // Warm-ups are never saved: only the working sets are.
        vm.finishGym()
        assertEquals(squat.sets.size, onDisk().records.single().exercises.single().sets.size)
    }

    @Test fun nextUpNamesTheNextSetWarmupOrExercise() {
        val vm = newVm()
        startGym(vm)
        vm.startExercise(0)
        val g0 = vm.gym.value!!
        assertTrue(GymGuide.nextUp(g0).startsWith("Squat warm-up 1 of ${g0.exercises[0].warmups.size}"), GymGuide.nextUp(g0))
        playGuided(vm) { it == GymStep.Work(0, 0) }
        vm.completeSet(0, 0)
        assertEquals("Squat set 2 · 72.5 kg x 5", GymGuide.nextUp(vm.gym.value!!))
        vm.completeSet(0, 1); vm.completeSet(0, 2)
        assertEquals("Next exercise: ${vm.gym.value!!.exercises[1].planned.exercise.name}", GymGuide.nextUp(vm.gym.value!!))
        playGuided(vm)
        assertEquals(GymStep.Finish, step(vm))
        assertTrue(GymGuide.nextUp(vm.gym.value!!).startsWith("That was the last set"))
    }

    @Test fun lastTimeShowsWhatWasLogged() {
        val vm = newVm()
        startGym(vm)
        assertNull(vm.lastTimeLine("squat"))
        playGuided(vm); vm.finishGym()
        today = today.plusDays(3)
        val sets = onDisk().records.single().exercises.first { it.exerciseId == "squat" }.sets
        assertEquals("Last time: 72.5 kg x ${sets.joinToString(", ") { it.reps.toString() }}", vm.lastTimeLine("squat"))
    }

    // ------------------------------------------------------------------------------------ rest

    @Test fun doneStartsTheExercisesRestAndAQuickSecondTapDoesNotUndoIt() {
        val vm = newVm()
        startGym(vm)
        playGuided(vm) { it == GymStep.Work(0, 0) }
        assertNull(vm.gym.value!!.rest)
        vm.completeSet(0, 0)
        vm.completeSet(0, 0) // a sweaty double tap
        val g = vm.gym.value!!
        assertTrue(g.exercises[0].sets[0].done)
        val rest = assertNotNull(g.rest)
        assertEquals(ExerciseLibrary.squat.restSec, rest.totalSec)
        assertEquals(0, rest.ei); assertEquals(0, rest.si)
        assertTrue(rest.endsAt > android.os.SystemClock.elapsedRealtime())
        vm.clearRest() // Skip rest
        assertNull(vm.gym.value!!.rest)
        assertEquals(GymStep.Work(0, 1), step(vm))
    }

    @Test fun theRestEndsOnItsOwnWithTheScreenOpen() {
        val vm = newVm()
        show(vm)
        startGym(vm)
        rule.waitForIdle()
        playGuided(vm) { it == GymStep.Work(0, 0) }
        vm.completeSet(0, 0)
        rule.waitForIdle()
        rule.onNodeWithText("Skip rest").assertExists()
        rule.onNodeWithText("NEXT UP").assertExists()
        val total = vm.gym.value!!.rest!!.totalSec
        advance(total * 1000L + 1500)
        rule.mainClock.advanceTimeBy(2000)
        rule.waitUntil(timeoutMillis = 5000) { vm.gym.value?.rest == null }
        rule.waitForIdle()
        rule.onNodeWithText("Skip rest").assertDoesNotExist()
    }

    // ------------------------------------------------------------------------------------ swap, skip, move, add

    @Test fun swappingAnExerciseInGuidedModeShowsTheNewOneNext() {
        val vm = newVm()
        startGym(vm)
        playGuided(vm) { it is GymStep.Intro && it.ei == 1 }
        val before = vm.gym.value!!.exercises[1].planned.exercise
        val alt = vm.swapOptions(before).firstOrNull()
        assertNotNull(alt, "the second exercise can be swapped")
        vm.swap(1, alt.id)
        assertEquals(GymStep.Intro(1), step(vm))
        assertEquals(alt.id, vm.gym.value!!.exercises[1].planned.exercise.id)
        playGuided(vm); vm.finishGym()
        val ids = onDisk().records.single().exercises.map { it.exerciseId }
        assertTrue(alt.id in ids && before.id !in ids, "saved the swapped-in lift only: $ids")
    }

    @Test fun skippingAnExerciseDropsItFromTheSession() {
        val vm = newVm()
        startGym(vm)
        val names = vm.gym.value!!.exercises.map { it.planned.exercise.id }
        playGuided(vm) { it is GymStep.Intro && it.ei == 1 }
        vm.removeExercise(1)
        assertEquals(names.size - 1, vm.gym.value!!.exercises.size)
        assertEquals(GymStep.Intro(1), step(vm)) // the one after it takes its place
        playGuided(vm); vm.finishGym()
        assertEquals(names - names[1], onDisk().records.single().exercises.map { it.exerciseId })
    }

    @Test fun skippingTheRestOfAnExerciseKeepsTheSetsAlreadyDone() {
        val vm = newVm()
        startGym(vm)
        playGuided(vm) { it == GymStep.Work(0, 1) } // set 1 done
        vm.closeExercise(0)
        assertEquals(GymStep.Intro(1), step(vm))
        val g = vm.gym.value!!
        assertEquals(1 to g.exercises.sumOf { it.sets.size } - (g.exercises[0].sets.size - 1), GymGuide.progress(g)) // the skipped sets no longer count
        playGuided(vm); vm.finishGym()
        assertEquals(1, onDisk().records.single().exercises.first { it.exerciseId == "squat" }.sets.size)
    }

    @Test fun movingAndAddingExercisesWorksInGuidedMode() {
        val vm = newVm()
        startGym(vm)
        val second = vm.gym.value!!.exercises[1].planned.exercise.id
        vm.moveExercise(1, -1) // "Do it earlier"
        assertEquals(second, vm.gym.value!!.exercises[0].planned.exercise.id)
        assertEquals(GymStep.Intro(0), step(vm))
        playGuided(vm)
        assertEquals(GymStep.Finish, step(vm))
        vm.addExercise("lat_pulldown")
        assertEquals(GymStep.Intro(vm.gym.value!!.exercises.size - 1), step(vm))
        playGuided(vm); vm.finishGym()
        assertEquals("lat_pulldown", onDisk().records.single().exercises.last().exerciseId)
    }

    @Test fun undoTheLastSetPutsYouBackOnIt() {
        val vm = newVm()
        startGym(vm)
        playGuided(vm) { it == GymStep.Work(0, 1) }
        assertEquals(0 to 0, lastDoneSet(vm.gym.value!!))
        vm.doneSet(0, 0); vm.clearRest() // the menu's "Undo the last set"
        assertEquals(GymStep.Work(0, 0), step(vm))
        assertNull(lastDoneSet(vm.gym.value!!))
    }

    // ------------------------------------------------------------------------------------ missed reps

    @Test fun missedRepsAskHowItFeltWithoutBlockingAndTheAnswerIsSaved() {
        val vm = newVm()
        startGym(vm)
        val kg = vm.gym.value!!.exercises[0].sets[0].kg
        val last = vm.gym.value!!.exercises[0].sets.lastIndex
        playGuided(vm) { it == GymStep.Work(0, last) } // the last squat set
        vm.adjustSet(0, last, dReps = -1)               // only 4 reps
        vm.completeSet(0, last); vm.setRir(0, last, 0)
        vm.clearRest()
        assertTrue(vm.gym.value!!.exercises[0].askMiss, "the question is waiting")
        assertEquals(GymStep.Intro(1), step(vm)) // ... and does not block the next exercise
        vm.setMiss(0, MissReason.AT_LIMIT)
        assertFalse(vm.gym.value!!.exercises[0].askMiss)
        playGuided(vm); vm.finishGym()
        val squat = onDisk().records.single().exercises.first { it.exerciseId == "squat" }
        assertEquals(MissReason.AT_LIMIT, squat.missReason)
        assertEquals(List(last) { 5 } + 4, squat.sets.map { it.reps })

        // The next session backs off (about 7.5%) instead of adding weight.
        today = today.plusDays(2)
        startGym(vm)
        val next = vm.gym.value!!.exercises.first { it.planned.exercise.id == "squat" }.sets.first().kg
        assertTrue(next < kg, "$next < $kg")
    }

    @Test fun theMissedRepsQuestionShowsAsTwoLargeButtonsOnTheRestScreen() {
        val vm = newVm()
        show(vm)
        startGym(vm)
        rule.waitForIdle()
        val last = vm.gym.value!!.exercises[0].sets.lastIndex
        playGuided(vm) { it == GymStep.Work(0, last) }
        vm.adjustSet(0, last, dReps = -2)
        vm.completeSet(0, last)
        rule.waitForIdle()
        rule.onNodeWithText("Rough day").assertExists()
        tap("At my limit")
        assertEquals(MissReason.AT_LIMIT, vm.gym.value!!.exercises[0].missReason)
        rule.onNodeWithText("At my limit").assertDoesNotExist()
    }

    // ------------------------------------------------------------------------------------ the real screen

    @Test fun thePressedButtonsWalkThroughTheScreen() {
        val vm = newVm()
        show(vm)
        startGym(vm)
        rule.waitForIdle()
        rule.onNodeWithText("TODAY'S SESSION").assertExists() // the whole session first ...
        press("Start")
        rule.onNodeWithText("Squat").assertExists()
        rule.onNodeWithText("Start").assertExists()          // ... then the next-exercise card
        press("Start")
        val warmups = vm.gym.value!!.exercises[0].warmups.size
        val sets = vm.gym.value!!.exercises[0].sets.size
        rule.onNodeWithText("WARM-UP 1 OF $warmups").assertExists()
        repeat(warmups) { press("Done, next") }
        rule.onNodeWithText("SET 1 OF $sets").assertExists()
        rule.onNodeWithText("72.5 kg").assertExists()
        press("Done, next")
        assertTrue(vm.gym.value!!.exercises[0].sets[0].done)
        press("Skip rest")
        rule.onNodeWithText("SET 2 OF $sets").assertExists()
        repeat(sets - 1) { press("Done, next"); press("Skip rest") }
        rule.onNodeWithText("NEXT EXERCISE 2 OF ${vm.gym.value!!.exercises.size}").assertExists()
    }

    @Test fun theLeaveDialogStillGuardsAGuidedSession() {
        val vm = newVm()
        show(vm)
        startGym(vm)
        playGuided(vm) { it == GymStep.Work(0, 1) }
        rule.waitForIdle()
        systemBack()
        rule.onNodeWithText("Leave this session?").assertExists()
        tapDialog("Keep going")
        rule.onNodeWithText("Leave this session?").assertDoesNotExist()
        assertEquals(Screen.Gym, vm.screen.value)
        assertEquals(emptyList(), onDisk().records)
        systemBack()
        tapDialog("Finish and save")
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(1, onDisk().records.single().exercises.single().sets.size)
    }

    @Test fun theOverviewSwitchesToTheListAndBack() {
        val vm = newVm()
        show(vm)
        startGym(vm)
        rule.waitForIdle()
        vm.setGymGuided(false)
        rule.waitForIdle()
        rule.onNodeWithText("Add exercise").assertExists() // the list screen
        tap("Guided mode: one set at a time")
        assertTrue(vm.gymGuided.value)
        rule.onNodeWithText("Start").assertExists()
    }

    @Test fun theSettingTurnsGuidedOffForNewSessions() {
        val vm = newVm()
        vm.updateProfile { it.copy(settings = it.settings.copy(guidedGym = false)) }
        startGym(vm)
        assertFalse(vm.gymGuided.value)
        assertFalse(onDisk().profile.settings.copy().guidedGym)
        vm.discardGym()
        vm.updateProfile { it.copy(settings = it.settings.copy(guidedGym = true)) }
        startGym(vm)
        assertTrue(vm.gymGuided.value)
    }

    // ------------------------------------------------------------------------------------ finisher

    private fun strengthWithFinisher() = PlannedSession(
        today, SessionKind.STRENGTH, "Strength A + station finisher", 60, Intensity.STRENGTH, 0, 6..8, listOf(Modality.GYM),
        listOf(
            "Warm up 5 minutes.", "Hyrox station finisher (about 12 min) at this phase's ladder level:",
            "  Sled Push, level 2: 4 x 12 m", "  Wall Balls, level 2: 4 x 10 reps",
        ),
        "Why.", LaunchHint(SessionType.GYM, hyroxFocus = setOf(HyroxStation.SLED_PUSH, HyroxStation.WALL_BALLS), minutes = 60),
    )

    @Test fun afterTheLastSetFinishSavesAndOffersTheStationFinisher() {
        val vm = newVm()
        show(vm)
        vm.launchPlanned(strengthWithFinisher())
        rule.waitForIdle()
        assertTrue(vm.gymGuided.value)
        playGuided(vm)
        rule.waitForIdle()
        rule.onNodeWithText("Station finisher next (optional)").assertExists()
        press("Finish session")
        rule.mainClock.advanceTimeBy(1500)
        rule.waitUntil(timeoutMillis = 5000) { vm.gym.value?.finisherOffered == true }
        rule.waitForIdle()
        rule.onNodeWithText("Lifts saved").assertExists()
        assertEquals(1, onDisk().records.size)
        vm.skipFinisher()
        assertEquals(Screen.Home, vm.screen.value)
    }
}
