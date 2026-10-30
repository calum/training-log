package com.calum.traininglog

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = Application::class)
class UpdateBannerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @After fun tearDown() { UpdateManager.setStateForTest(UpdateState.Idle) }

    @Test fun whatsNewShowsFirstSixBulletsBeforeUpdatingAndExpands() {
        val body = (1..8).joinToString("\n", prefix = "Intro line\n") { "- Thing number $it" }
        UpdateManager.setStateForTest(UpdateState.Available("0.6.0", "https://example.invalid/a.apk", 1, body))
        rule.setContent { UpdateBanner() }
        rule.onNodeWithText("Update available: v0.6.0").assertExists()
        rule.onNodeWithText("What's new").assertExists()
        rule.onNodeWithText("• Thing number 6").assertExists()
        rule.onNodeWithText("• Thing number 7").assertDoesNotExist()
        rule.onNodeWithText("Update now").assertExists()
        rule.onNodeWithText("Show all 8").performClick()
        rule.onNodeWithText("• Thing number 8").assertExists()
    }

    @Test fun noBulletsMeansNoWhatsNewSection() {
        UpdateManager.setStateForTest(UpdateState.Available("0.6.0", "https://example.invalid/a.apk", 1))
        rule.setContent { UpdateBanner() }
        rule.onNodeWithText("Update now").assertExists()
        rule.onNodeWithText("What's new").assertDoesNotExist()
    }
}
