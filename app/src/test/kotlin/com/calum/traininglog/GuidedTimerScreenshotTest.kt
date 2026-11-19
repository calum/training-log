package com.calum.traininglog

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.Intensity
import com.calum.traininglog.core.LaunchHint
import com.calum.traininglog.core.Modality
import com.calum.traininglog.core.PlannedSession
import com.calum.traininglog.core.SessionKind
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.TreadmillStyle
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/**
 * Renders the guided timer (setup, interval work and rest, easy block with the halfway check-in, finish card) and
 * the run-walk treadmill setup to `docs/screenshots/guided-*.png`. Same pattern as [HyroxSessionScreenshotTest];
 * the controller is driven with explicit tick deltas.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application::class)
class GuidedTimerScreenshotTest(private val shot: Shot) {

    class Env(val vm: AppViewModel) {
        fun tick(sec: Int) { repeat(sec) { vm.guided.tick(1000) } }
        fun finish() { while (vm.guided.ui.value.phase == GuidedPhase.RUNNING) vm.guided.tick(1000) }
    }

    class Shot(val name: String, val tallDp: Int? = null, val setup: Env.() -> Unit) {
        override fun toString() = name
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capture() {
        TreadmillEngine.stop()
        shot.tallDp?.let { org.robolectric.RuntimeEnvironment.setQualifiers("w411dp-h${it}dp-xxhdpi") }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        Fixtures.seed(ctx, Fixtures.busyRecords(), Fixtures.busyProfile())
        val vm = AppViewModel(ctx as android.app.Application)
        Env(vm).apply(shot.setup)
        rule.setContent { AppRoot(vm, inPip = false) }
        rule.waitForIdle()
        val file = File(System.getProperty("roborazzi.output.dir") ?: "../docs/screenshots", "${shot.name}.png").also { it.parentFile?.mkdirs() }
        rule.onAllNodes(isRoot()).let { it[it.fetchSemanticsNodes().size - 1] }.captureRoboImage(file.path)
    }

    companion object {
        private val today = LocalDate.now()

        private fun session(
            title: String, kind: SessionKind, minutes: Int, intensity: Intensity, hard: Int, rpe: IntRange,
            mods: List<Modality>, detail: List<String>, why: String, running: Boolean = false,
        ) = PlannedSession(
            today, kind, title, minutes, intensity, hard, rpe, mods, detail, why,
            LaunchHint(SessionType.TREADMILL, if (hard > 0) TreadmillStyle.INTERVALS else TreadmillStyle.STEADY, emptySet(), minutes), includesRunning = running,
        )

        /** The Build-phase VO2 session as the planner writes it. */
        private val vo2 = session(
            "VO2 intervals: 4 x 4 min", SessionKind.KEY, 40, Intensity.HARD, 16, 8..9,
            listOf(Modality.ROW, Modality.SKIERG, Modality.TREADMILL_WALK, Modality.BIKE),
            listOf(
                "Warm up 10 minutes easy (RPE 3) with 3 short pick-ups.",
                "4 x 4 minutes at RPE 8-9 (hard, last rep still controlled), 3 minutes easy between.",
                "Cool down 5 minutes easy.",
                "On the SkiErg (alternate with the other one) or the bike. Treadmill running only comes in once your knee stage allows it.",
            ),
            "Threshold and VO2 intervals are the 'hard 20%'; the 2024 meta-analysis found modest VO2peak gains from polarized training with no clear performance edge, so they stay short and everything else stays easy.",
        )

        private val easy = session(
            "Easy aerobic: 45 min", SessionKind.EASY, 45, Intensity.EASY, 0, 2..4,
            listOf(Modality.ROW, Modality.SKIERG, Modality.TREADMILL_WALK, Modality.BIKE),
            listOf(
                "Easy aerobic work, RPE 4 or less: split the time across rower, SkiErg, incline walk (4-8%) and bike.",
                "No running in this one yet. Your knee gets calm, strong hips first (the strength days), then graded run-walk when the knee has been calm for about two weeks.",
            ),
            "Easy aerobic time is the bulk of the plan (about 80% of endurance time); the staged run-walk return is practitioner consensus for ITB, not trial-proven.",
        )

        private val runWalk = session(
            "Easy aerobic: 40 min", SessionKind.EASY, 40, Intensity.EASY, 0, 2..4,
            listOf(Modality.RUN_WALK, Modality.TREADMILL_WALK, Modality.BIKE),
            listOf(
                "Walk 5 minutes warm-up at 1% incline.",
                "Run-walk: 1 min run / 2 min walk x 8 at 1% incline, running at a pace where you could chat (RPE 3-4). Knee 2/10 or less the whole time; if it climbs, switch to walking.",
                "Fill the rest of the time with easy rower, bike or incline walking.",
            ),
            "Easy aerobic time is the bulk of the plan (about 80% of endurance time); the staged run-walk return is practitioner consensus for ITB, not trial-proven.", running = true,
        )

        private val shots = listOf(
            Shot("guided-setup", tallDp = 1300) { vm.launchPlanned(vo2) },
            Shot("guided-setup-easy", tallDp = 1300) { vm.launchPlanned(easy) },
            Shot("guided-interval-work") { vm.launchPlanned(vo2); vm.guided.start(); tick(600 + 3 * 60 + 15) }, // warm-up, then 3:15 into rep 1
            Shot("guided-rest") { vm.launchPlanned(vo2); vm.guided.start(); tick(600 + 240 + 50) },
            Shot("guided-easy-checkin") { vm.launchPlanned(easy); vm.guided.start(); tick(300 + 1110 + 25) },
            Shot("guided-finish", tallDp = 1700) { vm.launchPlanned(vo2); vm.guided.start(); finish(); vm.guided.adjustDistance(100); vm.guided.adjustDistance(500) },
            Shot("guided-runwalk-setup", tallDp = 1500) { vm.launchPlanned(runWalk) },
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = shots.map { arrayOf(it) }
    }
}
