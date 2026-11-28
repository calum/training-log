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
import com.calum.traininglog.core.Export
import com.calum.traininglog.core.ExerciseLog
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.GymBuilder
import com.calum.traininglog.core.LoggedSet
import com.calum.traininglog.core.PlannedSession
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.PublicExport
import com.calum.traininglog.core.RacePlanSettings
import com.calum.traininglog.core.RacePlanner
import com.calum.traininglog.core.SessionKind
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
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

/** Quick standing / seated calf swap and the "Today's session" overview card, through the real ViewModel, store and screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = Application::class)
class CalfSwapFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as Application
    private var today = LocalDate.of(2026, 11, 20) // a Friday
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

    /** His real gym history: standing calf raises at 65 kg, the last hinge was the deadlift (so the RDL is next). */
    private fun history() = listOf(
        gym(d(11, 12), ExerciseLog("squat", sets(75.0, 3, 5)), ExerciseLog("rdl", sets(65.0, 2, 8)), ExerciseLog("calf", sets(65.0, 3, 12)),
            ExerciseLog("machine_chest", sets(40.0, 3, 10)), ExerciseLog("db_shoulder", sets(12.5, 3, 10))),
        gym(d(11, 16), ExerciseLog("squat", sets(75.0, 3, 5)), ExerciseLog("deadlift", sets(95.0, 2, 3)), ExerciseLog("calf", sets(65.0, 3, 12)),
            ExerciseLog("lat_pulldown", sets(42.5, 3, 10)), ExerciseLog("cable_row", sets(42.5, 3, 10))),
    )

    private fun newVm(): AppViewModel {
        Fixtures.seed(app, history(), profile)
        return AppViewModel(app, clock = { today })
    }

    private fun startGym(vm: AppViewModel, minutes: Int = 75) {
        vm.setInputs { it.copy(minutes = minutes, feeling = Feeling.GOOD) }
        vm.begin(SessionType.GYM, minutes)
        assertEquals(Screen.Gym, vm.screen.value)
    }

    private fun kickoff(): PlannedSession =
        assertNotNull(RacePlanner.build(cfg, history(), today, profile).leadIn).sessions.first { it.kind == SessionKind.STRENGTH }

    private fun show(vm: AppViewModel) { rule.setContent { AppRoot(vm, inPip = false) }; rule.waitForIdle() }
    private fun press(text: String) { rule.onNodeWithText(text).performClick(); rule.waitForIdle(); idle() }

    private fun step(vm: AppViewModel) = GymGuide.step(vm.gym.value!!)
    private fun ids(vm: AppViewModel) = vm.gym.value!!.exercises.map { it.planned.exercise.id }

    /** Presses what the guided screen offers up to (not including) the calf's first working set. */
    private fun playToCalf(vm: AppViewModel) {
        repeat(200) {
            val g = vm.gym.value!!
            if (g.rest != null) vm.clearRest()
            val calfEi = ids(vm).indexOf("calf")
            when (val s = GymGuide.step(vm.gym.value!!)) {
                is GymStep.Intro -> vm.startExercise(s.ei)
                is GymStep.Warmup -> vm.completeWarmup(s.ei, s.wi)
                is GymStep.Work -> { if (s.ei == calfEi) return; vm.completeSet(s.ei, s.si); vm.setRir(s.ei, s.si, 2) }
                GymStep.Finish -> return
            }
        }
        error("never reached the calf")
    }

    private fun finishRest(vm: AppViewModel, rir: Int = 1) {
        repeat(100) {
            if (vm.gym.value!!.rest != null) vm.clearRest()
            when (val s = GymGuide.step(vm.gym.value!!)) {
                is GymStep.Intro -> vm.startExercise(s.ei)
                is GymStep.Warmup -> vm.completeWarmup(s.ei, s.wi)
                is GymStep.Work -> { vm.completeSet(s.ei, s.si); vm.setRir(s.ei, s.si, rir) }
                GymStep.Finish -> return
            }
        }
    }

    // ------------------------------------------------------------------------------------ the quick swap

    @Test fun swappingTheCalfAfterSetOneKeepsStandingSetOneAndPlansTheRestAsSeated() {
        val vm = newVm()
        startGym(vm)
        assertEquals(listOf("squat", "rdl", "calf"), ids(vm).take(3))
        playToCalf(vm)
        val ei = ids(vm).indexOf("calf")
        assertEquals(GymStep.Work(ei, 0), step(vm))
        val standingKg = vm.gym.value!!.exercises[ei].sets[0].kg
        assertEquals(67.5, standingKg)
        vm.completeSet(ei, 0); vm.setRir(ei, 0, 2); vm.clearRest()

        vm.swap(ei, "calf_seated")
        val g = vm.gym.value!!
        assertEquals("calf", g.exercises[ei].planned.exercise.id)
        assertTrue(g.exercises[ei].closed)
        assertEquals(1, g.exercises[ei].sets.size)
        val seated = g.exercises[ei + 1]
        assertEquals("calf_seated", seated.planned.exercise.id)
        assertEquals(listOf(50.0, 50.0), seated.sets.map { it.kg })
        assertEquals(listOf(12, 12), seated.sets.map { it.target })
        assertEquals(GymStep.Work(ei + 1, 0), step(vm), "straight on to the seated set, no intro card in between")
        assertEquals(1, g.exercises.sumOf { e -> e.sets.count { it.done } } - 0 - g.exercises.take(ei).sumOf { e -> e.sets.count { it.done } })

        finishRest(vm, rir = 1)
        assertEquals(GymStep.Finish, step(vm))
        vm.finishGym()
        val record = onDisk().records.last()
        val calfLogs = record.exercises.filter { it.exerciseId.startsWith("calf") }
        assertEquals(listOf("calf", "calf_seated"), calfLogs.map { it.exerciseId })
        assertEquals(listOf(67.5), calfLogs[0].sets.map { it.weightKg })
        assertEquals(listOf(50.0, 50.0), calfLogs[1].sets.map { it.weightKg })
        assertEquals(3, calfLogs.sumOf { it.sets.size }, "three calf sets in all")
        assertEquals(Screen.Home, vm.screen.value)

        // The next session pre-selects seated, at its own 50 kg (the last set felt hard, so it holds).
        today = today.plusDays(2)
        startGym(vm)
        val next = vm.gym.value!!.exercises.first { it.planned.exercise.movement == "calf" }
        assertEquals("calf_seated", next.planned.exercise.id)
        assertEquals(50.0, next.sets.first().kg)
        assertEquals(3, next.sets.size)
        // Standing's own history is just the standing sets: seated sets never leak into it.
        val standingHistory = GymBuilder.history("calf", onDisk().records)
        assertEquals(listOf(1, 3, 3), standingHistory.map { it.log.sets.size })
        assertTrue(standingHistory.all { e -> e.log.sets.all { it.weightKg >= 65.0 } })
        assertEquals(70.0, GymBuilder.planFor("calf", onDisk().records, today, false).prescription.weightKg, "standing carries on from its own 67.5 kg")
    }

    @Test fun swappingBeforeAnySetSimplyReplacesTheExercise() {
        val vm = newVm()
        startGym(vm)
        val ei = ids(vm).indexOf("calf")
        val before = vm.gym.value!!.exercises.size
        vm.swap(ei, "calf_seated")
        val g = vm.gym.value!!
        assertEquals(before, g.exercises.size)
        assertEquals("calf_seated", g.exercises[ei].planned.exercise.id)
        assertEquals(listOf(50.0, 50.0, 50.0), g.exercises[ei].sets.map { it.kg })
        assertFalse(g.exercises[ei].closed)
        vm.swap(ei, "calf_seated") // the same one again: nothing changes
        assertEquals(g.exercises, vm.gym.value!!.exercises)
        // ... and back, at the standing weight from its own history.
        vm.swap(ei, "calf")
        assertEquals(listOf(67.5, 67.5, 67.5), vm.gym.value!!.exercises[ei].sets.map { it.kg })
    }

    @Test fun swappingBackAfterSwappingKeepsOneLogPerExerciseAndRemembersTheLastVariant() {
        val vm = newVm()
        startGym(vm)
        playToCalf(vm)
        val ei = ids(vm).indexOf("calf")
        vm.completeSet(ei, 0); vm.clearRest()
        vm.swap(ei, "calf_seated")
        vm.completeSet(ei + 1, 0); vm.clearRest()
        vm.swap(ei + 1, "calf") // back to standing for the last set
        assertEquals(listOf("calf", "calf_seated", "calf"), ids(vm).drop(ei).take(3))
        finishRest(vm)
        vm.finishGym()
        val logs = onDisk().records.last().exercises.filter { it.exerciseId.startsWith("calf") }
        assertEquals(listOf("calf_seated", "calf"), logs.map { it.exerciseId })
        assertEquals(2, logs[1].sets.size)
        assertEquals(1, logs[0].sets.size)
        assertEquals("calf", GymBuilder.preferredFor("calf", onDisk().records).id, "standing was the last one used")
    }

    @Test fun listModeSwapMenuOffersTheOtherCalfAndKeepsTickedSets() {
        val vm = newVm()
        startGym(vm)
        vm.setGymGuided(false)
        val ei = ids(vm).indexOf("calf")
        assertEquals(listOf("calf_seated"), vm.swapOptions(vm.gym.value!!.exercises[ei].planned.exercise).map { it.id })
        vm.doneSet(ei, 0)
        vm.swap(ei, "calf_seated")
        assertEquals(listOf("calf", "calf_seated"), ids(vm).drop(ei).take(2))
        assertEquals(listOf(true), vm.gym.value!!.exercises[ei].sets.map { it.done })
        assertEquals(2, vm.gym.value!!.exercises[ei + 1].sets.size)
    }

    @Test fun leavingWithoutFinishingStillWorksAfterASwap() {
        val vm = newVm()
        startGym(vm)
        playToCalf(vm)
        val ei = ids(vm).indexOf("calf")
        vm.completeSet(ei, 0); vm.clearRest()
        vm.swap(ei, "calf_seated")
        vm.discardGym()
        assertEquals(Screen.Home, vm.screen.value)
        assertNull(vm.gym.value)
        assertEquals(history().size, onDisk().records.size, "nothing was saved")
    }

    @Test fun finishingEarlyAfterASwapSavesOnlyWhatWasDone() {
        val vm = newVm()
        startGym(vm)
        playToCalf(vm)
        val ei = ids(vm).indexOf("calf")
        vm.completeSet(ei, 0); vm.clearRest()
        vm.swap(ei, "calf_seated")
        vm.finishGym() // "Finish session now" with the seated sets not done
        val logs = onDisk().records.last().exercises
        assertEquals(1, logs.single { it.exerciseId == "calf" }.sets.size)
        assertTrue(logs.none { it.exerciseId == "calf_seated" })
        assertEquals("calf", GymBuilder.preferredFor("calf", onDisk().records).id)
    }

    @Test fun exportsNameTheSeatedCalfAndPublicExportTakesItsLibraryId() {
        val vm = newVm()
        startGym(vm)
        vm.swap(ids(vm).indexOf("calf"), "calf_seated")
        finishRest(vm)
        vm.finishGym()
        val records = onDisk().records
        val text = Export.summary(records, today.minusDays(1), today)
        assertTrue("Seated calf raise" in text, text)
        val files = PublicExport.files(records)
        val rows = files.filterKeys { it.startsWith("data/gym") }.values.joinToString("\n")
        assertTrue(rows.lines().count { ",calf_seated," in it } == 3, rows)
        assertTrue(files.getValue(PublicExport.SUMMARY_PATH).contains("\"calf_seated\": [["))
    }

    // ------------------------------------------------------------------------------------ the chips on screen

    @Test fun theCalfCardShowsStandingAndSeatedChipsAndOneTapSwaps() {
        val vm = newVm()
        startGym(vm)
        vm.startGuidedSession()
        playToCalf(vm)
        show(vm)
        rule.onNodeWithText("Standing").assertIsDisplayed()
        rule.onNodeWithText("Seated").assertIsDisplayed()
        rule.onNodeWithText("Done, next").assertIsDisplayed()
        press("Seated")
        val g = vm.gym.value!!
        val e = g.exercises[ids(vm).indexOf("calf_seated")]
        assertEquals(listOf(50.0, 50.0, 50.0), e.sets.map { it.kg })
        rule.onNodeWithText("Seated calf raise").assertIsDisplayed()
        rule.onNodeWithText("Done, next").assertIsDisplayed()
        press("Standing")
        assertEquals("calf", ids(vm).let { it[it.indexOf("calf")] })
        assertEquals(67.5, vm.gym.value!!.exercises.first { it.planned.exercise.id == "calf" }.sets.first().kg)
    }

    // ------------------------------------------------------------------------------------ the overview card

    @Test fun theOverviewListMatchesTheBuiltPlanExactly() {
        val vm = newVm()
        startGym(vm, 75)
        val g = vm.gym.value!!
        val o = GymGuide.overview(g)
        assertEquals(g.plan.exercises.size, o.rows.size)
        g.plan.exercises.forEachIndexed { i, p ->
            val load = if (p.prescription.weightKg <= 0.0) "bodyweight" else "${GymGuide.loadText(p.prescription.weightKg)}"
            assertEquals("${i + 1} ${p.exercise.name} ${p.prescription.sets} x ${p.prescription.reps} · $load", o.rows[i].line)
        }
        assertEquals(g.plan.estimatedMinutes, o.minutes)
        assertEquals("1 Squat 3 x 5 · 77.5 kg", o.rows[0].line)
        assertNull(o.trimNote, "a roomy window trims nothing")
        assertNull(o.prehabLine, "a normal gym session has no prehab")
    }

    @Test fun trimmedSetsAreInTheListAndNamedInOneLine() {
        val vm = newVm()
        startGym(vm, 45) // lifting window of 25 min: sets get trimmed
        val g = vm.gym.value!!
        val o = GymGuide.overview(g)
        assertTrue(g.plan.exercises.any { it.prescription.sets == 2 })
        assertEquals(g.plan.exercises.map { "${it.prescription.sets} x ${it.prescription.reps}" }, o.rows.map { it.detail.substringBefore(" ·") })
        // The squat is protected longest, and the RDL is already a 2-set lift: only the calf raise was trimmed.
        assertEquals("Trimmed to fit: Calf raise 2 sets.", o.trimNote?.substringBefore(" Left out"))
        assertTrue(o.trimNote!!.length < 160)
    }

    @Test fun theKickOffSessionOverviewListsSquatRdlAndCalfAndThePrehabLine() {
        val vm = newVm()
        vm.launchPlanned(kickoff())
        val o = GymGuide.overview(vm.gym.value!!)
        assertEquals(
            listOf("1 Squat 3 x 5 · 77.5 kg", "2 Romanian deadlift 2 x 8 · 67.5 kg", "3 Calf raise 3 x 12 · 67.5 kg"),
            o.rows.map { it.line }.take(3),
        )
        assertEquals("Then: hip prehab (about 10 min)", o.prehabLine)
        assertEquals(vm.gym.value!!.plan.estimatedMinutes, o.minutes)
        assertTrue(o.minutes in 15..30, "${o.minutes}")
    }

    @Test fun thePrehabLineFollowsTheSamePromptTheFinishUses() {
        val vm = newVm()
        vm.launchPlanned(kickoff())
        assertNotNull(GymGuide.overview(vm.gym.value!!).prehabLine)
        finishRest(vm); vm.finishGym(); idle()
        assertTrue(vm.gym.value!!.prehabOffered, "the prompt the overview promised")
        // A session with no planned prehab promises none and shows none.
        val plain = newVm().also { startGym(it) }
        assertNull(GymGuide.overview(plain.gym.value!!).prehabLine)
        finishRest(plain); plain.finishGym(); idle()
        assertNull(plain.gym.value, "no prompt follows")
    }

    @Test fun theGuidedScreenOpensOnTheOverviewAndStartGoesToTheFirstExercise() {
        val vm = newVm()
        vm.launchPlanned(kickoff())
        show(vm)
        rule.onNodeWithText("TODAY'S SESSION").assertIsDisplayed()
        rule.onNodeWithText("Squat").assertIsDisplayed()
        rule.onNodeWithText("3 x 5 · 77.5 kg").assertIsDisplayed()
        rule.onNodeWithText("Then: hip prehab (about 10 min)").assertIsDisplayed()
        press("Start")
        assertTrue(vm.gym.value!!.overviewSeen)
        rule.onNodeWithText("NEXT EXERCISE 1 OF ${vm.gym.value!!.exercises.size}").assertIsDisplayed()
        assertEquals(GymStep.Intro(0), step(vm), "nothing else changed: the first exercise is still next")
    }

    @Test fun listModeNeverShowsTheOverviewCard() {
        val vm = newVm()
        vm.launchPlanned(kickoff())
        vm.setGymGuided(false)
        show(vm)
        assertTrue(rule.onAllNodesWithTextCount("TODAY'S SESSION") == 0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
