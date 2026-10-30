package com.calum.traininglog

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.ExerciseLog
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.LoggedSet
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
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

/** Renders the Progress screen (first viewport and scrolled pages) to `progress*.png` in docs/screenshots. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application::class)
class ProgressScreenshotTest(private val s: Case) {

    class Case(
        val name: String,
        val records: (LocalDate) -> List<SessionRecord>,
        val weeks: Int = 12,
        val lift: String? = null,
        val estimate: Boolean = false,
        val pages: Int = 4,
    ) {
        override fun toString() = name
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capture() {
        val today = LocalDate.now()
        rule.setContent {
            TrainingTheme {
                Surface(Modifier.fillMaxSize(), color = AppColors.background) {
                    ProgressContent(s.records(today), today, onBack = {}, initialWeeks = s.weeks, initialLift = s.lift, initialEstimate = s.estimate)
                }
            }
        }
        rule.waitForIdle()
        val dir = File(System.getProperty("roborazzi.output.dir") ?: "../docs/screenshots").also { it.mkdirs() }
        rule.onRoot().captureRoboImage(File(dir, "${s.name}.png").path)
        for (page in 2..s.pages) {
            rule.onRoot().performTouchInput { swipeUp(startY = height * 0.92f, endY = height * 0.08f, durationMillis = 600) }
            rule.waitForIdle()
            rule.onRoot().captureRoboImage(File(dir, "${s.name}-$page.png").path)
        }
    }

    companion object {
        private var n = 0
        private fun gym(today: LocalDate, weeksAgo: Int, squat: Double, dl: Double, row: Double) = SessionRecord(
            id = "pg-${n++}", date = today.minusWeeks(weeksAgo.toLong()).minusDays(1), type = SessionType.GYM,
            minutesAvailable = 75, minutesActual = 60, feeling = Feeling.GOOD,
            exercises = listOf(
                ExerciseLog("squat", listOf(LoggedSet(squat, 5, 5, 2), LoggedSet(squat, 5, 5, 2), LoggedSet(squat, 5, 5, 1))),
                ExerciseLog("deadlift", listOf(LoggedSet(dl, 3, 3, 2), LoggedSet(dl, 3, 3, 2))),
                ExerciseLog("cable_row", listOf(LoggedSet(row, 10, 8, 2), LoggedSet(row, 9, 8, 1))),
            ),
        )

        /** Busy fixture plus an earlier 14-week stretch of steadily rising weights (so 6-month and 1-year views have a story). */
        private fun long(today: LocalDate): List<SessionRecord> {
            n = 0
            val early = (10..24).filter { it % 3 != 0 }.map { w ->
                val k = 24 - w
                gym(today, w + 8, 60.0 + 1.5 * k, 80.0 + 2.5 * k, 30.0 + k)
            }
            return Fixtures.busyRecords(today) + early
        }

        private val cases = listOf(
            Case("progress", { Fixtures.busyRecords(it) }, pages = 4),
            Case("progress-6m-estimate", ::long, weeks = 26, lift = "squat", estimate = true, pages = 1),
            Case("progress-1y-upper", ::long, weeks = 52, lift = "cable_row", pages = 1),
            Case("progress-empty", { emptyList() }, pages = 1),
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = cases.map { arrayOf(it) }
    }
}
