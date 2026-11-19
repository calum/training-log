package com.calum.traininglog

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.HyroxSim
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.RunMode
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.SimFormat
import com.calum.traininglog.core.SimLeg
import com.calum.traininglog.core.SimLegKind
import com.calum.traininglog.core.SimMeta
import com.calum.traininglog.core.SimPreset
import com.calum.traininglog.core.StationLog
import com.calum.traininglog.core.UserSettings
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.TrainingTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.time.LocalDate

/** Own fixtures: finished simulations of known shape, so the hub, the results and the comparison have something to show. */
internal object HyroxSimFixtures {
    /** A finished simulation record with [secs] seconds per leg (stations and runs as the player would save them). */
    fun simRecord(
        id: String, date: LocalDate, format: SimFormat, mode: RunMode, load: Double = 1.0, target: Int? = null, minutes: Int? = null,
        secs: (SimLeg) -> Int,
    ): SessionRecord {
        val plan = HyroxSim.build(format, com.calum.traininglog.core.HyroxDivision.MEN_OPEN, UserSettings(), load, mode, targetTotalSec = target)
        val logs = plan.raceLegs.map { l ->
            if (l.kind == SimLegKind.RUN) StationLog("run", distanceM = l.distanceM, seconds = secs(l), rpe = 7)
            else StationLog(l.station!!.id, l.weightKg, l.distanceM, l.reps, secs(l), 8, level = 5, plannedWork = l.distanceM ?: l.reps)
        }
        // Transitions of 38 s to 52 s each, a little slower after the heavy stations.
        val trans = (0 until format.legCount - 1).map { k -> 38 + (k * 7) % 15 }
        val total = logs.sumOf { it.seconds ?: 0 } + trans.sum()
        return SessionRecord(
            id = id, date = date, type = SessionType.HYROX, minutesAvailable = plan.estimatedMinutes, minutesActual = minutes ?: ((total + 30) / 60),
            feeling = Feeling.GOOD, kneePain = 1, dayLabel = format.dayLabel, stations = logs,
            sim = SimMeta(format, load, mode, total, target, transitionSec = trans, allowanceSec = 45),
        )
    }

    /** Typical-ish times: runs about 6:30, stations by their usual proportions, a little slow on the sled pull. */
    fun typical(slowdown: Double = 1.0): (SimLeg) -> Int = { l ->
        val base = if (l.kind == SimLegKind.RUN) 390 else HyroxSim.typicalStationSec(l.station!!)
        (base * slowdown).toInt() + (if (l.station?.id == "sled_pull") (60 * slowdown).toInt() else 0)
    }

    fun withSims(today: LocalDate): List<SessionRecord> = HyroxFixtures.hyroxRecords(today) + listOf(
        simRecord("sim-a", today.minusDays(21), SimFormat.FIRST_HALF, RunMode.ROW, 0.9, secs = typical(1.12)),
        simRecord("sim-b", today.minusDays(7), SimFormat.FIRST_HALF, RunMode.ROW, 1.0, 2700, secs = typical(1.04)),
    )
}

/**
 * Renders the Hyrox simulation (setup, player, results), the hub card and the gym's station-finisher prompt to
 * `docs/screenshots/hyroxSim-*.png` and `gym-finisher-prompt.png`. Same pattern as [HyroxSessionScreenshotTest].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application::class)
class HyroxSimScreenshotTest(private val shot: Shot) {

    class Env(val vm: AppViewModel) {
        fun advance(ms: Long) { ShadowSystemClock.advanceBy(Duration.ofMillis(ms)) }
        fun idle() { shadowOf(Looper.getMainLooper()).idle() }

        fun open(
            format: SimFormat = SimFormat.FULL, load: Double = 1.0, mode: RunMode? = null, target: Int? = null,
        ) {
            vm.simOpen(SimPreset(format, load))
            if (mode != null) vm.simSetup(runMode = mode)
            if (target != null) vm.simSetup(targetSec = target)
        }

        fun startRace() { vm.sim.start(); advance(HyroxSimController.LEAD_IN_MS) }

        /** One run or station leg of [sec] seconds, then Done, then a transition of [trans] seconds and "Ready, go". */
        fun leg(sec: Int, trans: Int = 30) {
            advance(sec * 1000L); vm.sim.legDone()
            if (vm.sim.ui.value.inTransition) { advance(trans * 1000L); vm.sim.legDone() }
        }

        /** Runs [n] race legs (a different time per leg index when [secs] is given), each followed by its transition. */
        fun legs(n: Int, secs: (Int) -> Int = { 120 }) { repeat(n) { i -> leg(secs(i), 30 + (i * 7) % 15) } }

        fun finish(secs: (SimLeg) -> Int) {
            val plan = vm.sim.ui.value.plan!!
            plan.raceLegs.forEach { l -> leg(secs(l), 30 + (l.raceIndex * 7) % 15) }
            idle()
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
        private val withSims = { HyroxSimFixtures.withSims(LocalDate.now()) to Fixtures.busyProfile() }

        private val shots = listOf(
            // Setup: knee-gated default (rower), the full race.
            Shot("hyroxSim-setup-full", tallDp = 3000) { open() },
            // Setup: a half at 90% on the treadmill with a target time.
            Shot("hyroxSim-setup-half", tallDp = 2700) { open(SimFormat.FIRST_HALF, 0.9, RunMode.TREADMILL, 2700) },
            // Player: a treadmill run leg counting down.
            Shot("hyroxSim-player-run-treadmill") {
                open(SimFormat.FIRST_HALF, 1.0, RunMode.TREADMILL, 2700); startRace(); advance(131_000)
            },
            // Player: a station leg in progress, with the effort row after the previous leg.
            Shot("hyroxSim-player-station") {
                open(SimFormat.FIRST_HALF, 1.0, RunMode.ROW); startRace(); legs(1, { 380 }); advance(53_000)
            },
            // Player: far into a full simulation, on a run leg, as a stopwatch (rower), just after the start of the leg.
            Shot("hyroxSim-player-mid") {
                open(SimFormat.FULL, 0.9, RunMode.ROW); startRace(); legs(10, { 200 + it * 9 }); advance(14_000)
            },
            // Player: the transition after a run leg, within the allowance and then over it.
            Shot("hyroxSim-player-transition") {
                open(SimFormat.FIRST_HALF, 1.0, RunMode.TREADMILL, 2700); startRace(); advance(150_000); vm.sim.legDone(); advance(18_000)
            },
            Shot("hyroxSim-player-transition-over") {
                open(SimFormat.FIRST_HALF, 1.0, RunMode.ROW); startRace(); advance(370_000); vm.sim.legDone(); advance(57_000)
            },
            // Player: the 3-2-1 lead-in.
            Shot("hyroxSim-player-leadin") { open(SimFormat.FIRST_HALF); vm.sim.start(); advance(1_200) },
            Shot("hyroxSim-results-first", tallDp = 3200) {
                open(SimFormat.FIRST_HALF, 1.0, RunMode.ROW); startRace()
                finish(HyroxSimFixtures.typical(1.1))
            },
            Shot("hyroxSim-results-compare", tallDp = 3500, seed = withSims) {
                open(SimFormat.FIRST_HALF, 1.0, RunMode.ROW, 2700); startRace()
                finish(HyroxSimFixtures.typical(1.0))
            },
            Shot("hyroxSim-hub-card", tallDp = 1300, seed = withSims, content = { vm ->
                val data = vm.data.value
                TrainingTheme {
                    Box(Modifier.fillMaxSize().background(AppColors.background)) {
                        HyroxHubContent(data.records, data.profile.settings, {}, {}, {}, {}, {})
                    }
                }
            }),
            Shot("gym-finisher-prompt", content = {
                TrainingTheme {
                    Box(Modifier.fillMaxSize().background(AppColors.background)) {
                        GymFinisherPromptContent(
                            listOf("Hyrox station finisher (about 12 min) at this phase's ladder level", "Sled Push, level 3: 4 x 25 m at 107.5 kg", "Wall Balls, level 3: 4 x 15 reps at 5 kg"),
                            12, {}, {},
                        )
                    }
                }
            }),
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = shots.map { arrayOf(it) }
    }
}
