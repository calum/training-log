package com.calum.traininglog

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.HyroxGear
import com.calum.traininglog.core.HyroxPlace
import com.calum.traininglog.core.HyroxStation
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.StationLog
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

/** Fixtures for the Hyrox screens: a busy history plus a few earlier Hyrox sessions so the plan is not a blank slate. */
internal object HyroxFixtures {
    fun hyroxRecords(today: LocalDate = LocalDate.now()): List<SessionRecord> = Fixtures.busyRecords(today) + listOf(
        hyroxRecord("hx-1", today.minusDays(9), listOf(
            StationLog("sled_push", 15.0, 40, null, 95, 7, 2, 1),
            StationLog("row", null, 1500, null, 380, 6, 3, 3),
        )),
        hyroxRecord("hx-2", today.minusDays(4), listOf(
            StationLog("sled_push", 25.0, 80, null, 110, 7, 3, 2),
            StationLog("wall_balls", 4.0, null, 30, 150, 8, 2, 1),
            StationLog("farmers_carry", 12.0, 100, null, 120, 7, 2, 1),
        )),
    )

    private fun hyroxRecord(id: String, date: LocalDate, stations: List<StationLog>) = SessionRecord(
        id = id, date = date, type = SessionType.HYROX, minutesAvailable = 45, minutesActual = 42, feeling = Feeling.GOOD,
        kneePain = 1, stations = stations,
    )
}

/**
 * Renders the guided Hyrox session, the quick log and the Start tile to `docs/screenshots/hyrox-session*.png`.
 * Same pattern as [ScreenshotTest]; the controller is driven on the (Robolectric) device clock.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application::class)
class HyroxSessionScreenshotTest(private val shot: Shot) {

    class Env(val vm: AppViewModel) {
        fun advance(ms: Long) { ShadowSystemClock.advanceBy(Duration.ofMillis(ms)) }

        fun open(minutes: Int = 45, runs: Boolean = false, focus: Set<HyroxStation> = emptySet(), runMetres: Int? = null) {
            vm.begin(SessionType.HYROX, minutes)
            if (focus.isNotEmpty()) vm.hyroxSetup(focus = focus)
            if (runs) vm.hyroxSetup(withRuns = true, runMetres = runMetres)
        }

        /** Runs the current block's laps (20 s each, rests waited out) until the rating step. */
        fun lapsToRating() {
            val c = vm.hyrox
            while (c.ui.value.step != HyroxStep.RATE) {
                if (c.ui.value.step == HyroxStep.READY) { c.go(); advance(20_000); c.lapDone() }
                if (c.ui.value.step == HyroxStep.REST) { advance(c.ui.value.restTotalSec * 1000L + 500); c.tick() }
            }
        }

        fun rateAndSave(rpe: Int = 8, technique: Int = 3) {
            vm.hyrox.setRpe(rpe); vm.hyrox.setTechnique(technique); vm.hyrox.saveBlock()
        }
    }

    class Shot(
        val name: String,
        val tallDp: Int? = null,
        val seed: () -> Pair<List<SessionRecord>, Profile> = { HyroxFixtures.hyroxRecords() to Fixtures.busyProfile() },
        val content: (@androidx.compose.runtime.Composable (AppViewModel) -> Unit)? = null,
        val setup: Env.() -> Unit = {},
    ) {
        override fun toString() = name
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capture() {
        TreadmillEngine.stop()
        shot.tallDp?.let { org.robolectric.RuntimeEnvironment.setQualifiers("w411dp-h${it}dp-xxhdpi") }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val (records, profile) = shot.seed()
        Fixtures.seed(ctx, records, profile)
        val vm = AppViewModel(ctx as android.app.Application)
        Env(vm).apply(shot.setup)
        rule.setContent {
            if (shot.content != null) shot.content.invoke(vm) else AppRoot(vm, inPip = false)
        }
        rule.waitForIdle()
        val file = File(System.getProperty("roborazzi.output.dir") ?: "../docs/screenshots", "${shot.name}.png").also { it.parentFile?.mkdirs() }
        rule.onAllNodes(isRoot()).let { it[it.fetchSemanticsNodes().size - 1] }.captureRoboImage(file.path)
    }

    companion object {
        private val kneeSore = {
            val r = HyroxFixtures.hyroxRecords()
            r.dropLast(1) + r.last().copy(kneePain = 5) to Fixtures.busyProfile()
        }

        /** Home and garden with a wall ball, a sandbag and the treadmill (what he will own after the shopping trip). */
        private val homeKit = {
            val p = Fixtures.busyProfile()
            HyroxFixtures.hyroxRecords() to p.copy(
                settings = p.settings.copy(
                    hyroxPlace = HyroxPlace.HOME, hyroxHomeGear = setOf(HyroxGear.TREADMILL, HyroxGear.WALL_BALL, HyroxGear.SANDBAG),
                ),
            )
        }

        /** Home today: only the treadmill so far. */
        private val homeBare = {
            val p = Fixtures.busyProfile()
            HyroxFixtures.hyroxRecords() to p.copy(settings = p.settings.copy(hyroxPlace = HyroxPlace.HOME))
        }

        /** Home with no treadmill ticked: runs are a stopwatch. */
        private val homeNoTreadmill = {
            val p = Fixtures.busyProfile()
            HyroxFixtures.hyroxRecords() to p.copy(
                settings = p.settings.copy(hyroxPlace = HyroxPlace.HOME, hyroxHomeGear = setOf(HyroxGear.WALL_BALL, HyroxGear.SANDBAG)),
            )
        }

        private fun form(station: HyroxStation, f: (HyroxLogForm) -> HyroxLogForm = { it }): HyroxLogForm =
            f(HyroxLogForm.start(HyroxLogForm.defaults(station, HyroxFixtures.hyroxRecords(), com.calum.traininglog.core.HyroxDivision.MEN_OPEN)))

        private fun logShot(name: String, form: HyroxLogForm, tallDp: Int? = null) = Shot(name, tallDp = tallDp, content = { vm ->
            TrainingTheme {
                Box(Modifier.fillMaxSize().background(AppColors.background)) {
                    HyroxLogContent(form, LocalDate.now(), {}, { HyroxLogForm.defaults(it, HyroxFixtures.hyroxRecords(), com.calum.traininglog.core.HyroxDivision.MEN_OPEN) }, {}, {})
                }
            }
        })

        private val shots = listOf(
            Shot("hyrox-session-setup") { open() },
            Shot("hyrox-session-setup-full", tallDp = 2300) { open() },
            Shot("hyrox-session-setup-runs", tallDp = 2300) { open(runs = true) },
            Shot("hyrox-session-setup-knee", seed = kneeSore) { open() },
            Shot("hyrox-session-setup-gym", tallDp = 2300) { open() },
            Shot("hyrox-session-setup-home", tallDp = 2300, seed = homeKit) { open(60) },
            Shot("hyrox-session-setup-home-bare", tallDp = 2300, seed = homeBare) { open(45) },
            Shot("hyrox-session-setup-home-runs", tallDp = 2500, seed = homeKit) { open(60, runs = true, runMetres = 600) },
            Shot("hyrox-session-setup-home-stopwatch", tallDp = 2500, seed = homeNoTreadmill) { open(60, runs = true) },
            Shot("hyrox-session-player-ready") { open(); vm.hyrox.start() },
            Shot("hyrox-session-player-lap") { open(); vm.hyrox.start(); vm.hyrox.go(); advance(18_000) },
            Shot("hyrox-session-player-rest") { open(); vm.hyrox.start(); vm.hyrox.go(); advance(21_000); vm.hyrox.lapDone(); advance(30_000) },
            Shot("hyrox-session-player-rate", tallDp = 1500) { open(); vm.hyrox.start(); lapsToRating() },
            Shot("hyrox-session-player-run", seed = { HyroxFixtures.hyroxRecords() to Fixtures.busyProfile() }) {
                open(runs = true); vm.hyrox.start(); lapsToRating(); rateAndSave(); vm.hyrox.runGo(); advance(95_000)
            },
            Shot("hyrox-session-player-run-ready", tallDp = 1300, seed = homeKit) {
                open(60, runs = true, runMetres = 600); vm.hyrox.start(); lapsToRating(); rateAndSave()
            },
            Shot("hyrox-session-player-run-treadmill", tallDp = 1300, seed = homeKit) {
                open(60, runs = true, runMetres = 600); vm.hyrox.start(); lapsToRating(); rateAndSave()
                vm.hyrox.runGo(); advance(95_000); vm.hyrox.setRunRpe(7)
            },
            Shot("hyrox-session-player-run-stopwatch", tallDp = 1300, seed = homeNoTreadmill) {
                open(60, runs = true); vm.hyrox.start(); lapsToRating(); rateAndSave(); vm.hyrox.runGo(); advance(95_000)
            },
            Shot("hyrox-session-finish-runs", tallDp = 1700, seed = homeKit) {
                open(45, runs = true)
                vm.hyrox.start()
                while (vm.hyrox.ui.value.phase == HyroxPhase.RUNNING) {
                    lapsToRating(); rateAndSave()
                    vm.hyrox.runGo(); advance(150_000); vm.hyrox.runDone()
                }
            },
            Shot("hyrox-session-finish", tallDp = 1500) {
                open(); vm.hyrox.start()
                while (vm.hyrox.ui.value.phase == HyroxPhase.RUNNING) { lapsToRating(); rateAndSave() }
            },
            logShot("hyrox-session-log-empty", form(HyroxStation.SLED_PUSH).copy(current = StationLog("sled_push", 25.0, 80, level = 2)), tallDp = 1700),
            logShot(
                "hyrox-session-log-filled",
                form(HyroxStation.SLED_PUSH) { f ->
                    f.setDaysAgo(1).setSeconds(72).setRpe(8).setTechnique(3)
                        .addAnother { HyroxLogForm.defaults(it, HyroxFixtures.hyroxRecords(), com.calum.traininglog.core.HyroxDivision.MEN_OPEN) }
                        .adjustWeight(1).setSeconds(130).setRpe(7).setTechnique(2)
                },
                tallDp = 2300,
            ),
            logShot("hyrox-session-log-run", form(HyroxStation.SLED_PUSH).copy(current = HyroxLogForm.runDefaults()).setSeconds(151).setRpe(7), tallDp = 1500),
            Shot("hyrox-session-log-route") { vm.go(Screen.HyroxLog(HyroxStation.WALL_BALLS)) },
            Shot("hyrox-session-start-tile") { vm.go(Screen.Start) },
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = shots.map { arrayOf(it) }
    }
}
