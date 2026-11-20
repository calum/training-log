package com.calum.traininglog

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.CoreOutcome
import com.calum.traininglog.core.CoreProgressEntry
import com.calum.traininglog.core.CoreRoutine
import com.calum.traininglog.core.CoreRun
import com.calum.traininglog.core.ExerciseLog
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.Hike
import com.calum.traininglog.core.LoggedSet
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.TrainingTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.time.LocalDate

private fun outDir() = File(System.getProperty("roborazzi.output.dir") ?: "../docs/screenshots").also { it.mkdirs() }

/** Own fixtures: two earlier gym sessions (so "Last time" and the progression note have something to say) and a 90 minute hike. */
private object GuidedFixtures {
    val today: LocalDate = LocalDate.of(2026, 11, 26)
    private var n = 0

    private fun sets(kg: Double, reps: List<Int>, target: Int, rir: List<Int?>) = reps.mapIndexed { i, r -> LoggedSet(kg, r, target, rir.getOrNull(i)) }

    private fun gym(daysAgo: Int, squatKg: Double, hinge: String, hingeKg: Double, calfKg: Double, upper: String, upperKg: Double) = SessionRecord(
        "gg-${n++}", today.minusDays(daysAgo.toLong()), SessionType.GYM, 75, 62, Feeling.GOOD, dayLabel = "Squat + RDL",
        exercises = listOf(
            ExerciseLog("squat", sets(squatKg, listOf(5, 5, 5), 5, listOf(3, 2, 2))),
            ExerciseLog(hinge, sets(hingeKg, listOf(8, 8), 8, listOf(3, 2))),
            ExerciseLog("calf", sets(calfKg, listOf(12, 12, 12), 12, listOf(3, 2, 2))),
            ExerciseLog(upper, sets(upperKg, listOf(8, 8, 8), 8, listOf(2, 2, 2))),
        ),
    )

    fun history(): List<SessionRecord> {
        n = 0
        return listOf(
            gym(10, 67.5, "rdl", 60.0, 55.0, "lat_pulldown", 40.0),
            gym(4, 70.0, "rdl", 62.5, 57.5, "lat_pulldown", 42.5),
            SessionRecord("gg-hike", today.minusDays(8), SessionType.HIKE, 90, 90, Feeling.GOOD, note = "8.2 km, 450 m climb"),
        )
    }

    /** A VM on [history] with today's gym session started 23 minutes ago. */
    fun gymVm(): AppViewModel {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Fixtures.seed(ctx, history(), Profile(restWeekdays = emptySet()))
        val vm = AppViewModel(ctx as Application, clock = { today })
        vm.setInputs { it.copy(minutes = 75, feeling = Feeling.GOOD) }
        vm.begin(SessionType.GYM, 75)
        vm.startGuidedSession() // past the "Today's session" overview card (CalfSwapScreenshotTest shows that)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(23))
        return vm
    }

    fun doWarmups(vm: AppViewModel, ei: Int = 0) {
        vm.startExercise(ei)
        vm.gym.value!!.exercises[ei].warmups.indices.forEach { vm.completeWarmup(ei, it) }
    }

    /** Squat is done (three sets), the rest is over and the next exercise's card is up. */
    fun squatDone(vm: AppViewModel, lastReps: Int = 5) {
        doWarmups(vm)
        vm.completeSet(0, 0); vm.setRir(0, 0, 2)
        vm.completeSet(0, 1); vm.setRir(0, 1, 2)
        if (lastReps != 5) vm.adjustSet(0, 2, dReps = lastReps - 5)
        vm.completeSet(0, 2); vm.setRir(0, 2, if (lastReps == 5) 2 else 0)
        vm.clearRest()
    }

    fun allDone(vm: AppViewModel) {
        repeat(300) {
            val g = vm.gym.value!!
            if (g.rest != null) vm.clearRest()
            when (val s = GymGuide.step(vm.gym.value!!)) {
                is GymStep.Intro -> vm.startExercise(s.ei)
                is GymStep.Warmup -> vm.completeWarmup(s.ei, s.wi)
                is GymStep.Work -> { vm.completeSet(s.ei, s.si); vm.setRir(s.ei, s.si, 2) }
                GymStep.Finish -> return
            }
        }
    }
}

/**
 * Renders the guided gym screens, the hike / walk timer and the mat routine's progress card to `guidedGym-*.png`,
 * `hikeGuided-*.png` and `coreProgress-*.png` in docs/screenshots. Own fixtures (see [GuidedFixtures]).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = Application::class)
class GuidedGymScreenshotTest(private val s: Case) {

    class Case(
        val name: String,
        val tallDp: Int? = null,
        /** Builds the ViewModel (before composition) for the gym scenes; null for the stateless screens. */
        val vm: (() -> AppViewModel)? = null,
        val content: @Composable (AppViewModel?) -> Unit,
    ) {
        override fun toString() = name
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capture() {
        s.tallDp?.let { RuntimeEnvironment.setQualifiers("w411dp-h${it}dp-xxhdpi") }
        val vm = s.vm?.invoke()
        rule.setContent {
            TrainingTheme { Surface(Modifier.fillMaxSize(), color = AppColors.background) { s.content(vm) } }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(outDir(), "${s.name}.png").path)
    }

    companion object {
        private val fx = GuidedFixtures

        private fun gym(name: String, tallDp: Int? = null, setup: (AppViewModel) -> Unit) =
            Case(name, tallDp, vm = { fx.gymVm().also(setup) }, content = { vm -> GymScreen(vm!!) })

        private fun coreSessionWithPlank(): Pair<Long, com.calum.traininglog.core.CoreSession> {
            val seed = (0L..400L).first { CoreRoutine.build(20, seed = it).exerciseIds.contains("front_plank") }
            return seed to CoreRoutine.build(20, seed = seed)
        }

        /** What a previous session left behind: the plank at its current length, plus two other moves, all finished and fine. */
        private fun coreProgress(base: com.calum.traininglog.core.CoreSession): Map<String, CoreProgressEntry> =
            base.exerciseIds.take(4).associateWith { id ->
                CoreProgressEntry(base.steps.first { it.exerciseId == id }.seconds, 0, fx.today.minusDays(3), CoreOutcome.PROGRESS)
            }

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(
            gym("guidedGym-intro") { },
            gym("guidedGym-warmup-step") { it.startExercise(0); it.completeWarmup(0, 0) },
            gym("guidedGym-working-set") { fx.doWarmups(it); it.completeSet(0, 0); it.setRir(0, 0, 2); it.clearRest() },
            gym("guidedGym-rest-step") { fx.doWarmups(it); it.completeSet(0, 0); it.setRir(0, 0, 2) },
            gym("guidedGym-next-exercise") { fx.squatDone(it) },
            gym("guidedGym-missed-reps", tallDp = 1100) { fx.squatDone(it, lastReps = 3) },
            gym("guidedGym-finish") { fx.allDone(it) },
            Case("hikeGuided-setup", tallDp = 1300) { _ ->
                val suggestion = Hike.suggest(fx.history(), fx.today)
                HikeSetupContent(HikeUi(suggestion = suggestion, targetMin = suggestion?.minutes), {}, {}, {}, {})
            },
            Case("hikeGuided-running") { _ ->
                HikeRunningContent(HikeUi(phase = HikePhase.RUNNING, targetMin = 100), 47 * 60_000L + 12_000L, false, {}, {})
            },
            Case("hikeGuided-finish", tallDp = 1300) { _ ->
                HikeDoneContent(
                    HikeUi(phase = HikePhase.DONE, targetMin = 100, endedMs = 101 * 60_000L, distanceKm = 9.0, climbM = 450, feeling = Feeling.GOOD),
                    {}, {}, {}, {}, {},
                )
            },
            Case("coreProgress-setup", tallDp = 2200) { _ ->
                val (seed, base) = coreSessionWithPlank()
                val s = CoreRoutine.build(20, seed = seed, progress = coreProgress(base))
                CoreSetupContent(CoreUi(20, false, seed, s), {}, {}, {}, {}, {})
            },
            Case("coreProgress-finish") { _ ->
                val (seed, base) = coreSessionWithPlank()
                val s = CoreRoutine.build(20, seed = seed, progress = coreProgress(base))
                CoreDoneScreen(
                    CoreUi(20, false, seed, s, CorePhase.DONE, CoreRun(s.steps, index = s.steps.size, totalElapsedMs = 20 * 60_000L)), {},
                )
            },
        ).map { arrayOf(it) }
    }
}
