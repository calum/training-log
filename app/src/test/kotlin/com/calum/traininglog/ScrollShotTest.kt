package com.calum.traininglog

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.SessionRecord
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Captures the parts of long screens below the first viewport: `<name>-2.png`, `<name>-3.png`, ... after swiping up.
 * Append a [Scroll] to [scrolls] to cover another screen.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application::class)
class ScrollShotTest(private val s: Scroll) {

    class Scroll(val name: String, val screen: Screen, val empty: Boolean = false, val pages: Int = 3) {
        override fun toString() = name
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capture() {
        TreadmillEngine.stop()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val (records, profile) =
            if (s.empty) emptyList<SessionRecord>() to Profile() else Fixtures.busyRecords() to Fixtures.busyProfile()
        Fixtures.seed(ctx, records, profile)
        val vm = AppViewModel(ctx as android.app.Application)
        vm.go(s.screen)
        rule.setContent { AppRoot(vm, inPip = false) }
        rule.waitForIdle()
        val dir = File(System.getProperty("roborazzi.output.dir") ?: "../docs/screenshots").also { it.mkdirs() }
        for (page in 2..s.pages) {
            rule.onRoot().performTouchInput { swipeUp(startY = height * 0.92f, endY = height * 0.08f, durationMillis = 600) }
            rule.waitForIdle()
            rule.onRoot().captureRoboImage(File(dir, "${s.name}-$page.png").path)
        }
        TreadmillEngine.stop()
    }

    companion object {
        private val scrolls = listOf(
            Scroll("plan", Screen.Plan, pages = 5),
            Scroll("plan-empty", Screen.Plan, empty = true, pages = 4),
            Scroll("trophies", Screen.Trophies, pages = 4),
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = scrolls.map { arrayOf(it) }
    }
}
