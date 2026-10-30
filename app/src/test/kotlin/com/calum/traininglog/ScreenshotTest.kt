package com.calum.traininglog

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.Profile
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

/**
 * Renders every screen of the REAL app (real [AppRoot], real [AppViewModel], fixture data on disk) to PNG.
 *
 *   gradle :app:recordRoborazziDebug      ->  PNG files in docs/screenshots
 *
 * This only ever records; nothing is compared, so UI changes never fail a build. To add a screen, append one
 * [Shot] to [shots]: a name (becomes `<name>.png`) and a setup lambda that gets the ViewModel after the
 * fixture data is on disk. Pass `seed = ...` to start from different data, `pip = true` for the 320x180 PiP view.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application::class)
class ScreenshotTest(private val shot: Shot) {

    /** Everything a setup lambda can touch. [clock] is a fake monotonic clock (ms) for [TreadmillEngine]. */
    class Env(val vm: AppViewModel, val ctx: Context) {
        fun treadmillPlayer(style: TreadmillStyle, minutes: Int, tickToMs: List<Long>) {
            vm.setInputs { it.copy(minutes = minutes) }
            vm.begin(SessionType.TREADMILL, minutes, style)
            TreadmillEngine.start(vm.pendingTreadmill.value!!, 0L)
            tickToMs.forEach { TreadmillEngine.tick(it) }
            vm.go(Screen.TreadmillPlayer)
        }
    }

    class Shot(
        val name: String,
        val pip: Boolean = false,
        val seed: (() -> Pair<List<com.calum.traininglog.core.SessionRecord>, Profile>)? = null,
        val setup: Env.() -> Unit = {},
    ) {
        override fun toString() = name
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capture() {
        TreadmillEngine.stop()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val (records, profile) = (shot.seed ?: ::busy)()
        Fixtures.seed(ctx, records, profile)
        val vm = AppViewModel(ctx as android.app.Application)
        Env(vm, ctx).apply(shot.setup)
        rule.setContent {
            if (shot.pip) Box(Modifier.size(320.dp, 180.dp).testTag("shot")) { AppRoot(vm, inPip = true) }
            else AppRoot(vm, inPip = false)
        }
        rule.waitForIdle()
        val file = File(outputDir(), "${shot.name}.png")
        if (shot.pip) rule.onNodeWithTag("shot").captureRoboImage(file.path)
        else rule.onRoot().captureRoboImage(file.path)
        TreadmillEngine.stop()
    }

    companion object {
        private fun outputDir(): File =
            File(System.getProperty("roborazzi.output.dir") ?: "../docs/screenshots").also { it.mkdirs() }

        private fun busy() = Fixtures.busyRecords() to Fixtures.busyProfile()
        private fun empty() = emptyList<com.calum.traininglog.core.SessionRecord>() to Profile()

        /** THE list: add a line here to capture another screen/state. */
        private val shots = listOf(
            Shot("home-empty", seed = ::empty),
            Shot("home-busy"),
            Shot("home-celebration") { vm.saveManual(SessionType.TREADMILL, 0, 45, TreadmillStyle.PYRAMID, "") },
            Shot("start-default") { vm.go(Screen.Start) },
            Shot("start-recommendation") {
                vm.go(Screen.Start)
                vm.setInputs { it.copy(minutes = 60) }
                vm.recommend()
            },
            Shot("gym-mid-session") {
                vm.setInputs { it.copy(minutes = 75) }
                vm.begin(SessionType.GYM, 75)
                vm.gym.value!!.exercises[0].warmups.indices.forEach { vm.toggleWarmup(0, it) }
                vm.doneSet(0, 0); vm.setRir(0, 0, 3)
                vm.doneSet(0, 1); vm.setRir(0, 1, 2)
                vm.doneSet(1, 0); vm.setRir(1, 0, 3)
                vm.doneSet(1, 1) // rest timer starts here
            },
            Shot("treadmill-setup") { vm.setInputs { it.copy(minutes = 45) }; vm.begin(SessionType.TREADMILL, 45, TreadmillStyle.PYRAMID) },
            Shot("treadmill-player") { treadmillPlayer(TreadmillStyle.PYRAMID, 45, listOf(700_000, 701_500)) },
            Shot("treadmill-player-flashing") { treadmillPlayer(TreadmillStyle.PYRAMID, 45, listOf(284_000, 285_000)) },
            Shot("treadmill-player-pip", pip = true) { treadmillPlayer(TreadmillStyle.PYRAMID, 45, listOf(700_000, 701_500)) },
            Shot("treadmill-done") {
                treadmillPlayer(TreadmillStyle.INTERVALS, 40, listOf(2_400_000, 2_401_500))
                vm.endTreadmill()
            },
            Shot("manual-log") { vm.go(Screen.Manual) },
            Shot("light-hike") { vm.begin(SessionType.HIKE, 120) },
            Shot("light-core") { vm.begin(SessionType.CORE, 15) },
            Shot("export") { vm.go(Screen.Export) },
            Shot("plan") { vm.go(Screen.Plan) },
            Shot("plan-empty", seed = ::empty) { vm.go(Screen.Plan) },
            Shot("trophies") { vm.go(Screen.Trophies) },
            Shot("backup-sync") { vm.go(Screen.Backup) },
            Shot("settings") { vm.go(Screen.Settings) },
            Shot("blocks") { vm.go(Screen.Blocks) },
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = shots.map { arrayOf(it) }
    }
}
