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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.time.LocalDate

private fun outDir() = File(System.getProperty("roborazzi.output.dir") ?: "../docs/screenshots").also { it.mkdirs() }

/** Own fixtures: his gym history and the walks around it; the planned kick-off strength session is 25 lifting minutes plus 10 prehab. */
private object GymTimeFixtures {
    val today: LocalDate = LocalDate.of(2026, 11, 20)
    private val cfg = RacePlanSettings(sessionsPerWeek = 4, maxWeeklyMinutes = 300)
    private val profile = Profile(settings = UserSettings(racePlan = cfg), restWeekdays = emptySet())

    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)
    private fun sets(kg: Double, n: Int, reps: Int) = List(n) { LoggedSet(kg, reps, reps, 2) }
    private fun gym(date: LocalDate, vararg logs: ExerciseLog) =
        SessionRecord("gt-$date", date, SessionType.GYM, 60, 45, Feeling.GOOD, exercises = logs.toList())
    private fun walk(date: LocalDate, min: Int) = SessionRecord(
        "gw-$date", date, SessionType.TREADMILL, min, min, Feeling.GOOD,
        treadmill = TreadmillLog(TreadmillStyle.STEADY, min * 60, min * 60, distanceM = min * 85),
    )

    fun history() = listOf(
        walk(d(10, 30), 30), walk(d(11, 2), 30), walk(d(11, 6), 45), walk(d(11, 8), 30), walk(d(11, 15), 20), walk(d(11, 18), 30), walk(d(11, 19), 30),
        gym(d(11, 1), ExerciseLog("squat", sets(72.5, 3, 5)), ExerciseLog("rdl", sets(62.5, 2, 8)), ExerciseLog("calf", sets(60.0, 3, 12)),
            ExerciseLog("lat_pulldown", sets(50.0, 3, 10)), ExerciseLog("cable_row", sets(47.5, 3, 10))),
        gym(d(11, 5), ExerciseLog("squat", sets(72.5, 3, 5)), ExerciseLog("deadlift", sets(90.0, 2, 3)), ExerciseLog("calf", sets(62.5, 3, 12))),
        gym(d(11, 12), ExerciseLog("squat", sets(75.0, 3, 5)), ExerciseLog("rdl", sets(65.0, 2, 8)), ExerciseLog("calf", sets(65.0, 3, 12)),
            ExerciseLog("machine_chest", sets(40.0, 3, 10)), ExerciseLog("db_shoulder", sets(12.5, 3, 10))),
        gym(d(11, 16), ExerciseLog("squat", sets(75.0, 3, 5)), ExerciseLog("deadlift", sets(95.0, 2, 3)), ExerciseLog("calf", sets(65.0, 3, 12)),
            ExerciseLog("lat_pulldown", sets(42.5, 3, 10)), ExerciseLog("cable_row", sets(42.5, 3, 10))),
    )

    private fun vm(): AppViewModel {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Fixtures.seed(ctx, history(), profile)
        return AppViewModel(ctx as Application, clock = { today })
    }

    /** The planned kick-off strength session just opened (25 min lifting, hip prehab to follow). */
    fun planned(): AppViewModel = vm().also {
        val session = RacePlanner.build(cfg, history(), today, profile).leadIn!!.sessions.first { s -> s.kind == SessionKind.STRENGTH }
        it.launchPlanned(session)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
    }

    /** A normal session started from the Start screen with 75 minutes (55 lifting). */
    fun normal(): AppViewModel = vm().also {
        it.setInputs { i -> i.copy(minutes = 75, feeling = Feeling.GOOD) }
        it.begin(SessionType.GYM, 75)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
    }
}

/** Renders the time chips on Today's session (and in the gym list) to `gymTime-*.png` in docs/screenshots. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = Application::class)
class GymTimeChipsScreenshotTest(private val s: Case) {

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
        private val fx = GymTimeFixtures

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(
            Case("gymTime-overview-20") { fx.planned().also { vm -> vm.setGymWindow(20) } },
            Case("gymTime-overview-40") { fx.planned().also { vm -> vm.setGymWindow(40) } },
            Case("gymTime-prehab-off") { fx.planned().also { vm -> vm.setGymPrehab(false) } },
            Case("gymTime-list") { fx.normal().also { vm -> vm.setGymGuided(false); vm.setGymWindow(40) } },
        ).map { arrayOf(it) }
    }
}
