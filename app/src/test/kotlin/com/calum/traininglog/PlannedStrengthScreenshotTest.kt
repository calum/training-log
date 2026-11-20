package com.calum.traininglog

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
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
import com.calum.traininglog.core.TreadmillLog
import com.calum.traininglog.core.TreadmillStyle
import com.calum.traininglog.core.UserSettings
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
import java.io.File
import java.time.LocalDate

private fun outDir() = File(System.getProperty("roborazzi.output.dir") ?: "../docs/screenshots").also { it.mkdirs() }

/** Own fixtures: Calum's real November history (squat, RDL / deadlift, calf, upper) and the default 4-session plan around it. */
private object PlannedStrengthFixtures {
    val today: LocalDate = LocalDate.of(2026, 11, 20)
    val cfg = RacePlanSettings(sessionsPerWeek = 4, maxWeeklyMinutes = 300)
    val profile = Profile(settings = UserSettings(racePlan = cfg), restWeekdays = emptySet())

    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)
    private fun sets(kg: Double, n: Int, reps: Int) = List(n) { LoggedSet(kg, reps, reps, 2) }
    private fun gym(date: LocalDate, min: Int, vararg logs: ExerciseLog) =
        SessionRecord("ps-$date", date, SessionType.GYM, min, min, Feeling.GOOD, exercises = logs.toList())
    private fun walk(date: LocalDate, min: Int) = SessionRecord(
        "pw-$date", date, SessionType.TREADMILL, min, min, Feeling.GOOD,
        treadmill = TreadmillLog(TreadmillStyle.STEADY, min * 60, min * 60, distanceM = min * 85),
    )

    fun history() = listOf(
        walk(d(10, 30), 30),
        gym(d(11, 1), 23, ExerciseLog("squat", sets(72.5, 3, 5)), ExerciseLog("rdl", sets(62.5, 2, 8)), ExerciseLog("calf", sets(60.0, 3, 12)),
            ExerciseLog("lat_pulldown", sets(40.0, 3, 10)), ExerciseLog("cable_row", sets(40.0, 3, 10))),
        walk(d(11, 2), 30),
        gym(d(11, 5), 27, ExerciseLog("squat", sets(72.5, 3, 5)), ExerciseLog("deadlift", sets(90.0, 2, 3)), ExerciseLog("calf", sets(62.5, 3, 12))),
        walk(d(11, 6), 45), walk(d(11, 8), 30),
        gym(d(11, 12), 37, ExerciseLog("squat", sets(75.0, 3, 5)), ExerciseLog("rdl", sets(65.0, 2, 8)), ExerciseLog("calf", sets(65.0, 3, 12)),
            ExerciseLog("machine_chest", sets(40.0, 3, 10)), ExerciseLog("db_shoulder", sets(12.5, 3, 10))),
        walk(d(11, 15), 20),
        gym(d(11, 16), 40, ExerciseLog("squat", sets(75.0, 3, 5)), ExerciseLog("deadlift", sets(95.0, 2, 3)), ExerciseLog("calf", sets(65.0, 3, 12)),
            ExerciseLog("lat_pulldown", sets(42.5, 3, 10)), ExerciseLog("cable_row", sets(42.5, 3, 10))),
        walk(d(11, 18), 30), walk(d(11, 19), 30),
    )

    /** A VM on [history] with the kick-off strength session launched from the plan. */
    fun vm(): AppViewModel {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Fixtures.seed(ctx, history(), profile)
        val vm = AppViewModel(ctx as Application, clock = { today })
        val plan = RacePlanner.build(cfg, history(), today, profile)
        vm.launchPlanned(plan.leadIn!!.sessions.first { it.kind == SessionKind.STRENGTH })
        return vm
    }

    fun finishLifts(vm: AppViewModel) {
        val g = vm.gym.value!!
        g.exercises.forEachIndexed { ei, e -> e.sets.indices.forEach { si -> vm.doneSet(ei, si) } }
        vm.finishGym()
    }
}

/** Renders the planned strength session: the plan card, the guided gym intro, the prehab prompt and the hip-only Knee care setup to `plannedStrength-*.png`. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = Application::class)
class PlannedStrengthScreenshotTest(private val s: Case) {

    class Case(
        val name: String,
        val tallDp: Int? = null,
        val scrollTo: String? = null,
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
            CompositionLocalProvider(LocalToday provides PlannedStrengthFixtures.today) {
                TrainingTheme { Surface(Modifier.fillMaxSize(), color = AppColors.background) { s.content(vm) } }
            }
        }
        rule.waitForIdle()
        s.scrollTo?.let { rule.onNodeWithTag(it).performScrollTo(); rule.waitForIdle() }
        rule.onRoot().captureRoboImage(File(outDir(), "${s.name}.png").path)
    }

    companion object {
        private val fx = PlannedStrengthFixtures

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(
            Case("plannedStrength-kickoff-card", tallDp = 1400, scrollTo = "week") {
                RacePlanContent(
                    fx.history(), fx.profile, fx.today, onSettings = {}, onStart = {}, onBack = {},
                )
            },
            Case("plannedStrength-gym-intro", vm = { fx.vm() }) { vm -> GymScreen(vm!!) },
            Case("plannedStrength-prehab-prompt", vm = { fx.vm().also { fx.finishLifts(it) } }) { vm -> GymScreen(vm!!) },
            Case("plannedStrength-prehab-setup", tallDp = 2000, vm = { fx.vm().also { fx.finishLifts(it); it.startPrehab() } }) { vm -> KneeCareScreen(vm!!) },
        ).map { arrayOf(it) }
    }
}
