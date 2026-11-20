package com.calum.traininglog

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.ExerciseLog
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.LoggedSet
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.RacePlanSettings
import com.calum.traininglog.core.RacePlanner
import com.calum.traininglog.core.SessionKind
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.UserSettings
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.TrainingTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.time.LocalDate

private fun outDir() = File(System.getProperty("roborazzi.output.dir") ?: "../docs/screenshots").also { it.mkdirs() }

/** Own fixtures: two earlier gym sessions with standing calf raises at 65 kg; the last hinge was the deadlift, so the RDL is next. */
private object CalfFixtures {
    val today: LocalDate = LocalDate.of(2026, 11, 20)
    private val cfg = RacePlanSettings(sessionsPerWeek = 4, maxWeeklyMinutes = 300)
    private val profile = Profile(settings = UserSettings(racePlan = cfg), restWeekdays = emptySet())

    private fun sets(kg: Double, n: Int, reps: Int) = List(n) { LoggedSet(kg, reps, reps, 2) }
    private fun gym(day: Int, vararg logs: ExerciseLog) =
        SessionRecord("cs-$day", LocalDate.of(2026, 11, day), SessionType.GYM, 60, 45, Feeling.GOOD, exercises = logs.toList())

    fun history() = listOf(
        gym(12, ExerciseLog("squat", sets(75.0, 3, 5)), ExerciseLog("rdl", sets(65.0, 2, 8)), ExerciseLog("calf", sets(65.0, 3, 12)),
            ExerciseLog("machine_chest", sets(40.0, 3, 10)), ExerciseLog("db_shoulder", sets(12.5, 3, 10))),
        gym(16, ExerciseLog("squat", sets(75.0, 3, 5)), ExerciseLog("deadlift", sets(95.0, 2, 3)), ExerciseLog("calf", sets(65.0, 3, 12)),
            ExerciseLog("lat_pulldown", sets(42.5, 3, 10)), ExerciseLog("cable_row", sets(42.5, 3, 10))),
    )

    private fun vm(): AppViewModel {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Fixtures.seed(ctx, history(), profile)
        return AppViewModel(ctx as Application, clock = { today })
    }

    /** A normal gym session (75 minutes) just opened. */
    fun plainVm(): AppViewModel = vm().also {
        it.setInputs { i -> i.copy(minutes = 75, feeling = Feeling.GOOD) }
        it.begin(SessionType.GYM, 75)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
    }

    /** The planned "kick-off" strength session (trimmed to fit its window, hip prehab to follow). */
    fun plannedVm(): AppViewModel = vm().also {
        val session = RacePlanner.build(cfg, history(), today, profile).leadIn!!.sessions.first { s -> s.kind == SessionKind.STRENGTH }
        it.launchPlanned(session)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
    }

    /** Past the overview, squat and RDL done: the calf's first card is up. */
    fun atCalf(vm: AppViewModel): AppViewModel {
        vm.startGuidedSession()
        repeat(200) {
            if (vm.gym.value!!.rest != null) vm.clearRest()
            val calfEi = vm.gym.value!!.exercises.indexOfFirst { it.planned.exercise.id == "calf" }
            when (val s = GymGuide.step(vm.gym.value!!)) {
                is GymStep.Intro -> { if (s.ei == calfEi) return vm; vm.startExercise(s.ei) }
                is GymStep.Warmup -> vm.completeWarmup(s.ei, s.wi)
                is GymStep.Work -> { vm.completeSet(s.ei, s.si); vm.setRir(s.ei, s.si, 2) }
                GymStep.Finish -> return vm
            }
        }
        return vm
    }

    fun calfEi(vm: AppViewModel) = vm.gym.value!!.exercises.indexOfFirst { it.planned.exercise.id == "calf" }
}

/** Renders the Today's session overview and the standing / seated calf swap to `calfSwap-*.png` in docs/screenshots. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = Application::class)
class CalfSwapScreenshotTest(private val s: Case) {

    class Case(val name: String, val vm: () -> AppViewModel) {
        override fun toString() = name
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capture() {
        val vm = s.vm()
        rule.setContent {
            TrainingTheme { Surface(Modifier.fillMaxSize(), color = AppColors.background) { GymScreen(vm) } }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(outDir(), "${s.name}.png").path)
    }

    companion object {
        private val fx = CalfFixtures

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(
            Case("calfSwap-overview") { fx.plainVm() },
            Case("calfSwap-overview-prehab") { fx.plannedVm() },
            Case("calfSwap-calf-intro") { fx.plainVm().also { vm -> vm.startGuidedSession(); fx.atCalf(vm) } },
            Case("calfSwap-calf-set") { fx.atCalf(fx.plainVm()).also { vm -> vm.startExercise(fx.calfEi(vm)) } },
            Case("calfSwap-seated-set") {
                fx.atCalf(fx.plainVm()).also { vm ->
                    val ei = fx.calfEi(vm)
                    vm.startExercise(ei); vm.completeSet(ei, 0); vm.setRir(ei, 0, 2); vm.clearRest()
                    vm.swap(ei, "calf_seated")
                }
            },
        ).map { arrayOf(it) }
    }
}
