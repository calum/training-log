package com.calum.traininglog

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.HyroxStation
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.StationLog
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
import java.io.File
import java.time.LocalDate

private fun outDir() = File(System.getProperty("roborazzi.output.dir") ?: "../docs/screenshots").also { it.mkdirs() }

private object HyroxFixtures {
    private var n = 0
    private fun rec(today: LocalDate, daysAgo: Int, vararg st: StationLog) = SessionRecord(
        id = "hx-${n++}", date = today.minusDays(daysAgo.toLong()), type = SessionType.HYROX,
        minutesAvailable = 45, minutesActual = 40, feeling = Feeling.GOOD, stations = st.toList(),
    )

    /** A few weeks of logs: sled push climbing, wall balls timed, a row and a farmers carry. */
    fun history(today: LocalDate): List<SessionRecord> {
        n = 0
        return listOf(
            rec(today, 38, StationLog("sled_push", 45.0, 40, null, 95, 7, 2, 1), StationLog("wall_balls", 3.0, null, 30, 150, 7, 2, 1)),
            rec(today, 31, StationLog("sled_push", 76.0, 50, null, 70, 8, 2, 2), StationLog("row", null, 1000, null, 262, 8, 3, 4)),
            rec(today, 24, StationLog("wall_balls", 4.0, null, 40, 190, 7, 2, 2), StationLog("farmers_carry", 12.0, 100, null, 80, 7, 3, 3)),
            rec(today, 17, StationLog("sled_push", 102.0, 75, null, 118, 8, 2, 3), StationLog("wall_balls", 6.0, null, 60, 290, 8, 2, 3)),
            rec(today, 10, StationLog("wall_balls", 6.0, null, 80, 360, 8, 2, 4), StationLog("sled_pull", 52.0, 50, null, 60, 6, 2, 2)),
            rec(today, 3, StationLog("sled_push", 129.0, 50, null, 85, 9, 2, 4), StationLog("wall_balls", 6.0, null, 100, 450, 8, 2, 5)),
        )
    }
}

/** Renders the Hyrox hub and station screens to `hyrox-*.png` in docs/screenshots. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application::class)
class HyroxHubScreenshotTest(private val s: Case) {

    class Case(val name: String, val station: HyroxStation?, val history: Boolean, val pages: Int = 1, val settings: UserSettings = UserSettings()) {
        override fun toString() = name
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capture() {
        val today = LocalDate.now()
        val records = if (s.history) HyroxFixtures.history(today) else emptyList()
        rule.setContent {
            TrainingTheme {
                Surface(Modifier.fillMaxSize(), color = AppColors.background) {
                    if (s.station == null) HyroxHubContent(records, s.settings, {}, {}, {}, {}, {})
                    else HyroxStationContent(s.station, records, s.settings, today, {}, {}, {}, {})
                }
            }
        }
        rule.waitForIdle()
        val dir = outDir()
        rule.onRoot().captureRoboImage(File(dir, "${s.name}.png").path)
        for (page in 2..s.pages) {
            rule.onRoot().performTouchInput { swipeUp(startY = height * 0.92f, endY = height * 0.08f, durationMillis = 600) }
            rule.waitForIdle()
            rule.onRoot().captureRoboImage(File(dir, "${s.name}-$page.png").path)
        }
    }

    companion object {
        private val edited = UserSettings(hyroxWeightOverrides = mapOf("sled_push" to 142.5))

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(
            Case("hyrox-hub-empty", null, false, pages = 3),
            Case("hyrox-hub", null, true, pages = 3),
            Case("hyrox-station-sled-push-new", HyroxStation.SLED_PUSH, false, pages = 4),
            Case("hyrox-station-wall-balls", HyroxStation.WALL_BALLS, true, pages = 4),
            Case("hyrox-station-row", HyroxStation.ROW, true, pages = 3),
            Case("hyrox-station-sled-push-edited", HyroxStation.SLED_PUSH, true, pages = 4, settings = edited),
        ).map { arrayOf(it) }
    }
}

/** One tall capture of a whole station page. */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h3400dp-xxhdpi", application = android.app.Application::class)
class HyroxTallScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capture() {
        val today = LocalDate.now()
        rule.setContent {
            TrainingTheme {
                Surface(Modifier.fillMaxSize(), color = AppColors.background) {
                    HyroxStationContent(HyroxStation.SANDBAG_LUNGES, HyroxFixtures.history(today), UserSettings(), today, {}, {}, {}, {})
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(outDir(), "hyrox-station-tall.png").path)
    }
}
