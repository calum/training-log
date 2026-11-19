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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.PlanAdjustments
import com.calum.traininglog.core.PlanSkip
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.RacePlanSettings
import com.calum.traininglog.core.SessionKind
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.SkipReason
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

/** Own fixtures for the flexibility screens: week 1 is Monday 23 Nov 2026, default rest days Tuesday and Friday. */
internal object PlanAdjustFixtures {
    private var n = 0
    private val d = { day: Int -> LocalDate.of(2026, if (day > 0) 11 else 12, if (day > 0) day else -day) }

    private fun gym(date: LocalDate, min: Int = 40, knee: Int? = 1) = SessionRecord("pa-${n++}", date, SessionType.GYM, min, min, Feeling.GOOD, kneePain = knee)
    private fun walk(date: LocalDate, min: Int = 40, knee: Int? = 1) = SessionRecord(
        "pa-${n++}", date, SessionType.TREADMILL, min, min, Feeling.GOOD, kneePain = knee,
        treadmill = TreadmillLog(TreadmillStyle.STEADY, min * 60, min * 60, distanceM = min * 85),
    )

    /** A few calm weeks before the plan, then Monday's strength in week 1. */
    fun week1(): List<SessionRecord> {
        n = 0
        return listOf(gym(d(2)), walk(d(5)), gym(d(9)), walk(d(12)), gym(d(16)), walk(d(18)), gym(d(23)))
    }

    /** The same, but nothing since: a rough stretch (Sunday 6 Dec). */
    fun roughStretch(): List<SessionRecord> {
        n = 0
        return listOf(gym(d(2)), walk(d(5)), gym(d(9)), walk(d(12)), gym(d(16)), walk(d(18)), gym(d(23)))
    }

    val mine = RacePlanSettings(sessionsPerWeek = 4, maxWeeklyMinutes = 300)

    fun profile(adj: PlanAdjustments = PlanAdjustments()) = Profile(settings = UserSettings(racePlan = mine), planAdjustments = adj)

    /** Wednesday's hard session skipped for lack of time (recorded that day): it moved to Thursday. */
    fun skippedKey(): Profile = profile(
        PlanAdjustments(skips = listOf(PlanSkip(d(25), SessionKind.KEY, "Aerobic intervals", SkipReason.NO_TIME, d(25)))),
    )
}

/** Renders the move, skip, 'I have some time' and replan screens to `planAdjust-*.png`. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = Application::class)
class PlanAdjustScreenshotTest(private val s: Case) {

    class Case(
        val name: String,
        val today: LocalDate,
        val records: () -> List<SessionRecord>,
        val profile: () -> Profile = { PlanAdjustFixtures.profile() },
        /** 'plan' (RacePlanContent) or 'home' (the whole app on Home). */
        val screen: String = "plan",
        /** Test tags tapped in order (each scrolled into view first). */
        val taps: List<String> = emptyList(),
        val scrollTo: String? = null,
        val tallDp: Int? = null,
    ) {
        override fun toString() = name
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capture() {
        s.tallDp?.let { RuntimeEnvironment.setQualifiers("w411dp-h${it}dp-xxhdpi") }
        val records = s.records()
        val profile = s.profile()
        val vm = if (s.screen == "plan") null else {
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            Fixtures.seed(ctx, records, profile)
            AppViewModel(ctx as Application, clock = { s.today })
        }
        rule.setContent {
            CompositionLocalProvider(LocalToday provides s.today) {
                if (vm == null) {
                    var p by remember { mutableStateOf(profile) }
                    TrainingTheme {
                        Surface(Modifier.fillMaxSize(), color = AppColors.background) {
                            RacePlanContent(
                                records, p, s.today,
                                onSettings = { f -> p = p.copy(settings = p.settings.copy(racePlan = f(p.settings.racePlan).sanitized())) },
                                onStart = {}, onBack = {}, onProfile = { f -> p = f(p) },
                            )
                        }
                    }
                } else AppRoot(vm, inPip = false)
            }
        }
        rule.waitForIdle()
        s.taps.forEach { rule.onNodeWithTag(it, useUnmergedTree = true).performScrollTo().performClick(); rule.waitForIdle() }
        s.scrollTo?.let { rule.onNodeWithTag(it, useUnmergedTree = true).performScrollTo(); rule.waitForIdle() }
        rule.onRoot().captureRoboImage(File(outDir(), "${s.name}.png").path)
    }

    companion object {
        private val fx = PlanAdjustFixtures
        private val wed = LocalDate.of(2026, 11, 25)
        private val thu = LocalDate.of(2026, 11, 26)

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(
            Case("planAdjust-week", wed, fx::week1, scrollTo = "week", tallDp = 1500),
            Case("planAdjust-move", wed, fx::week1, taps = listOf("move", "day-fri"), scrollTo = "move-panel", tallDp = 1500),
            Case("planAdjust-skip", wed, fx::week1, taps = listOf("skip", "reason-no_time"), scrollTo = "skip-panel", tallDp = 1500),
            Case("planAdjust-skipped", thu, fx::week1, profile = fx::skippedKey, scrollTo = "week", tallDp = 1700),
            Case("planAdjust-time", wed, fx::week1, taps = listOf("some-time", "minutes-30"), scrollTo = "time-panel", tallDp = 1700),
            Case("planAdjust-time-rough", wed, fx::week1, taps = listOf("some-time", "minutes-60", "feeling-rough"), scrollTo = "time-panel", tallDp = 1700),
            Case("planAdjust-replan", LocalDate.of(2026, 12, 6), fx::roughStretch, scrollTo = "replan", tallDp = 1300),
            Case("planAdjust-settings", wed, fx::week1, scrollTo = "rest-weekdays", tallDp = 1500),
            Case("planAdjust-home", wed, fx::week1, screen = "home"),
            Case("planAdjust-home-replan", LocalDate.of(2026, 12, 6), fx::roughStretch, screen = "home"),
        ).map { arrayOf(it) }
    }
}
