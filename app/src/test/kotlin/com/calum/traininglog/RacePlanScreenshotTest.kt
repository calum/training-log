package com.calum.traininglog

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.RacePlanSettings
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

/** Own fixtures: a logged history around the default plan (week 1 = Monday 23 Nov 2026, race Saturday 27 Feb 2027). */
private object RacePlanFixtures {
    private var n = 0
    private fun gym(date: LocalDate, min: Int = 50, knee: Int? = null) =
        SessionRecord("rp-${n++}", date, SessionType.GYM, min, min, Feeling.GOOD, kneePain = knee)

    private fun walk(date: LocalDate, min: Int = 40, knee: Int? = null) =
        SessionRecord(
            "rp-${n++}", date, SessionType.TREADMILL, min, min, Feeling.GOOD, kneePain = knee,
            treadmill = TreadmillLog(TreadmillStyle.STEADY, min * 60, min * 60, distanceM = min * 85),
        )

    val d = { m: Int, day: Int -> LocalDate.of(if (m < 6) 2027 else 2026, m, day) }

    /** Four calm, knee-rated weeks before the plan: the knee allows run-walk. */
    fun calmKnee(): List<SessionRecord> {
        n = 0
        return listOf(
            gym(d(11, 2), knee = 1), walk(d(11, 5), knee = 1), gym(d(11, 9), knee = 1), walk(d(11, 12), knee = 0),
            gym(d(11, 16), knee = 1), walk(d(11, 18), knee = 1),
        )
    }

    /** Calum's real history on Thursday 19 Nov 2026: about 100 minutes a week, walking and gym, a ladder logged today. */
    fun real(): List<SessionRecord> {
        n = 0
        return listOf(
            walk(d(10, 30), 30), gym(d(11, 1), 23), walk(d(11, 2), 30), gym(d(11, 5), 27), walk(d(11, 6), 45), walk(d(11, 8), 30),
            gym(d(11, 12), 37), walk(d(11, 15), 20), gym(d(11, 16), 40), walk(d(11, 18), 30),
            SessionRecord(
                "rp-${n++}", d(11, 19), SessionType.TREADMILL, 30, 30, Feeling.GOOD,
                treadmill = TreadmillLog(TreadmillStyle.LADDER, 1800, 1800, distanceM = 2550),
            ),
        )
    }

    /** Week 1 already under way (Tuesday 24 Nov): Monday's strength is logged. */
    fun week1(): List<SessionRecord> = calmKnee() + gym(d(11, 23), knee = 1)

    /** Week 6 (Build, Wed 30 Dec): weeks 1-4 were solid, week 5 was a quiet one, so week 6 repeats its targets. */
    fun midPlan(): List<SessionRecord> {
        val out = calmKnee().toMutableList()
        var day = d(11, 23)
        repeat(4) {
            out += gym(day, 55, 1); out += walk(day.plusDays(1), 45, 1); out += walk(day.plusDays(3), 50, 1)
            out += gym(day.plusDays(4), 55, 1); out += walk(day.plusDays(5), 60, 1)
            day = day.plusWeeks(1)
        }
        out += walk(d(12, 22), 30, 1) // the quiet week 5
        out += gym(d(12, 28), 50, 1); out += walk(d(12, 29), 40, 1) // week 6 so far
        return out
    }

    /** Race week (Tue 23 Feb): the taper has been followed. */
    fun raceWeek(): List<SessionRecord> = midPlan() + walk(d(2, 15), 45, 1) + gym(d(2, 16), 45, 1) + walk(d(2, 22), 25, 1)
}

/** Renders the race plan screen, Home and Start with the plan to `racePlan-*.png`, `home-race*.png`, `start-race*.png`. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = Application::class)
class RacePlanScreenshotTest(private val s: Case) {

    class Case(
        val name: String,
        val today: LocalDate,
        val records: () -> List<SessionRecord>,
        val settings: RacePlanSettings = RacePlanSettings(),
        /** Which part of the app: the plan screen, Home or the Start screen. */
        val screen: String = "plan",
        val scrollTo: String? = null,
        val tallDp: Int? = null,
        val allWeeksOpen: Boolean = false,
        val tapText: String? = null,
    ) {
        override fun toString() = name
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capture() {
        s.tallDp?.let { RuntimeEnvironment.setQualifiers("w411dp-h${it}dp-xxhdpi") }
        val records = s.records()
        val profile = Profile(settings = UserSettings(racePlan = s.settings))
        val vm = if (s.screen == "plan") null else {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            Fixtures.seed(ctx, records, profile)
            AppViewModel(ctx as Application).also { v -> if (s.screen == "start") v.go(Screen.Start) }
        }
        rule.setContent {
            CompositionLocalProvider(LocalToday provides s.today) {
                if (vm == null) {
                    var cfg by remember { mutableStateOf(s.settings) }
                    TrainingTheme {
                        Surface(Modifier.fillMaxSize(), color = AppColors.background) {
                            RacePlanContent(
                                records, profile.copy(settings = profile.settings.copy(racePlan = cfg)), s.today,
                                onSettings = { f -> cfg = f(cfg).sanitized() }, onStart = {}, onBack = {},
                                allWeeksOpen = s.allWeeksOpen,
                            )
                        }
                    }
                } else AppRoot(vm, inPip = false)
            }
        }
        rule.waitForIdle()
        s.tapText?.let { rule.onNodeWithText(it).performScrollTo().performClick(); rule.waitForIdle() }
        s.scrollTo?.let { rule.onNodeWithTag(it).performScrollTo(); rule.waitForIdle() }
        rule.onRoot().captureRoboImage(File(outDir(), "${s.name}.png").path)
    }

    companion object {
        private val fx = RacePlanFixtures
        private val capped = RacePlanSettings(sessionsPerWeek = 4, maxWeeklyMinutes = 240)
        private val mine = RacePlanSettings(sessionsPerWeek = 4, maxWeeklyMinutes = 300)

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(
            Case("racePlan-top", LocalDate.of(2026, 11, 19), { emptyList() }),
            Case("racePlan-kickoff-top", LocalDate.of(2026, 11, 19), fx::real, settings = mine),
            Case("racePlan-kickoff", LocalDate.of(2026, 11, 19), fx::real, settings = mine, scrollTo = "week", tallDp = 1300),
            Case("racePlan-kickoff-weeks", LocalDate.of(2026, 11, 19), fx::real, settings = mine, scrollTo = "weeks", allWeeksOpen = true, tallDp = 2000),
            Case("racePlan-week1",LocalDate.of(2026, 11, 24), fx::week1, scrollTo = "week"),
            Case("racePlan-midplan", LocalDate.of(2026, 12, 30), fx::midPlan),
            Case("racePlan-midplan-week", LocalDate.of(2026, 12, 30), fx::midPlan, scrollTo = "week"),
            Case("racePlan-midplan-balance", LocalDate.of(2026, 12, 30), fx::midPlan, scrollTo = "balance"),
            Case("racePlan-raceweek", LocalDate.of(2027, 2, 23), fx::raceWeek),
            Case("racePlan-raceweek-week", LocalDate.of(2027, 2, 23), fx::raceWeek, scrollTo = "week", tallDp = 1500),
            Case("racePlan-runstage", LocalDate.of(2026, 11, 19), fx::calmKnee, scrollTo = "running"),
            Case("racePlan-weeks", LocalDate.of(2026, 12, 30), fx::midPlan, scrollTo = "weeks", allWeeksOpen = true, tallDp = 1800),
            Case("racePlan-settings", LocalDate.of(2026, 11, 19), { emptyList() }, settings = capped, scrollTo = "settings"),
            Case("racePlan-evidence", LocalDate.of(2026, 11, 19), { emptyList() }, scrollTo = "evidence"),
            Case("racePlan-paused", LocalDate.of(2026, 11, 19), { emptyList() }, settings = RacePlanSettings(enabled = false)),
            Case("home-race", LocalDate.of(2026, 12, 30), fx::midPlan, screen = "home"),
            Case("home-race-today", LocalDate.of(2026, 12, 31), fx::midPlan, screen = "home"),
            Case("home-race-prestart", LocalDate.of(2026, 11, 19), fx::calmKnee, screen = "home"),
            Case("home-race-kickoff", LocalDate.of(2026, 11, 19), fx::real, settings = mine, screen = "home"),
            Case("start-race-kickoff", LocalDate.of(2026, 11, 19), fx::real, settings = mine, screen = "start"),
            Case("start-race",LocalDate.of(2026, 12, 31), fx::midPlan, screen = "start"),
            // After race week the maintenance plan takes over (Wednesday 10 March 2027).
            Case("racePlan-maintenance", LocalDate.of(2027, 3, 10), fx::midPlan, settings = mine),
            Case("home-maintenance", LocalDate.of(2027, 3, 10), fx::midPlan, settings = mine, screen = "home"),
        ).map { arrayOf(it) }
    }
}
