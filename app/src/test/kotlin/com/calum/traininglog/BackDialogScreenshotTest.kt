package com.calum.traininglog

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.TreadmillStyle
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

private fun backDialogOutDir() = File(System.getProperty("roborazzi.output.dir") ?: "../docs/screenshots").also { it.mkdirs() }

/** The "Leave this session?" dialog that system back shows over a live gym session and a running treadmill, as `backDialog-*.png`. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = Application::class)
class BackDialogScreenshotTest(private val name: String) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @After fun tearDown() { TreadmillEngine.stop() }

    @Test
    fun capture() {
        TreadmillEngine.stop()
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        Fixtures.seed(ctx, emptyList(), Profile())
        val vm = AppViewModel(ctx)
        vm.go(Screen.Start)
        when (name) {
            "backDialog-gym" -> {
                vm.begin(SessionType.GYM, 60)
                vm.doneSet(0, 0); vm.setRir(0, 0, 2)
                vm.doneSet(0, 1); vm.setRir(0, 1, 2)
                vm.doneSet(1, 0); vm.setRir(1, 0, 2)
            }
            else -> {
                vm.setInputs { it.copy(minutes = 40) }
                vm.begin(SessionType.TREADMILL, 40, TreadmillStyle.PYRAMID)
                TreadmillEngine.start(vm.pendingTreadmill.value!!, android.os.SystemClock.elapsedRealtime())
                ShadowSystemClock.advanceBy(Duration.ofMillis(9 * 60_000L + 30_000L))
                TreadmillEngine.tick(android.os.SystemClock.elapsedRealtime())
                ShadowSystemClock.advanceBy(Duration.ofMillis(2_000L)) // let the change flash fade
                TreadmillEngine.tick(android.os.SystemClock.elapsedRealtime())
                vm.go(Screen.TreadmillPlayer)
            }
        }
        rule.setContent { AppRoot(vm, inPip = false) }
        rule.waitForIdle()
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        captureScreenRoboImage(File(backDialogOutDir(), "$name.png").path)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(arrayOf<Any>("backDialog-gym"), arrayOf<Any>("backDialog-treadmill"))
    }
}
