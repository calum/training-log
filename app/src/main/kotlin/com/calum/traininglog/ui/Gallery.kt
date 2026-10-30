package com.calum.traininglog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Scrollable, interactive showcase of every component. Handy as a debug destination
 * (`GalleryScreen()` inside `TrainingTheme`) and as the big preview below.
 */
@Composable
fun GalleryScreen(modifier: Modifier = Modifier) {
    var weight by remember { mutableFloatStateOf(60f) }
    var reps by remember { mutableIntStateOf(8) }
    var done1 by remember { mutableStateOf(true) }
    var done2 by remember { mutableStateOf(false) }
    var rir by remember { mutableStateOf<Int?>(2) }
    var chip by remember { mutableIntStateOf(0) }
    var confetti by remember { mutableIntStateOf(0) }
    var rest by remember { mutableIntStateOf(90) }
    var progress by remember { mutableFloatStateOf(0.72f) }

    LaunchedEffect(rest) {
        if (rest > -2) { delay(1000); rest = if (rest <= 0) 90 else rest - 1 }
    }

    Box(modifier.fillMaxSize().background(AppColors.background)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            HeroCard {
                Text("GOOD EVENING, CALUM", style = AppType.eyebrow, color = AppColors.onHero.copy(alpha = 0.8f))
                Text("Week 3, nice rhythm", style = MaterialTheme.typography.headlineLarge, color = AppColors.onHero)
                Spacer(Modifier.height(Spacing.lg))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ProgressRing(progress, bonusProgress = (progress - 0.6f).coerceAtLeast(0f)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AnimatedNumber(progress * 100f, style = AppType.numberXL, color = AppColors.onHero)
                            Text("% of weekly goal", style = MaterialTheme.typography.bodySmall, color = AppColors.onHero.copy(alpha = 0.8f))
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Pill("5 day streak", color = AppColors.energy, filled = true)
                    Pill("Level 4", color = AppColors.reward, filled = true)
                }
            }

            SectionCard(title = "This week") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DayDot("M", DayState.Done); DayDot("T", DayState.Rest); DayDot("W", DayState.RestTrained)
                    DayDot("T", DayState.PastEmpty); DayDot("F", DayState.Today); DayDot("S", DayState.Open); DayDot("S", DayState.Rest)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                StatTile("Volume", "12.4", Modifier.weight(1f), unit = "t")
                StatTile("Distance", "18.2", Modifier.weight(1f), unit = "km", accent = AppColors.energy)
                StatTile("Sessions", "9", Modifier.weight(1f), accent = AppColors.reward)
            }

            XpBar(340, 500, 4, "Gym Regular")

            BigActionButton("Start workout", { confetti++ })
            SecondaryButton("Skip today", {}, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                listOf("Easy", "Good", "Hard").forEachIndexed { i, s -> SelectChip(s, chip == i, { chip = i }) }
            }

            SectionCard(title = "Bench press") {
                BigNumberStepper("%.1f".format(weight).removeSuffix(".0"), "kg", { weight -= 2.5f }, { weight += 2.5f }, label = "Weight")
                BigNumberStepper("$reps", "reps", { reps = (reps - 1).coerceAtLeast(1) }, { reps++ }, label = "Reps")
                SetRow(1, "60", "8", done1, { done1 = it; if (it) confetti++ }, rir = rir, onRirChange = { rir = it })
                SetRow(2, "%.1f".format(weight).removeSuffix(".0"), "$reps", done2, { done2 = it })
                RestTimerBar(rest, 90, onSkip = { rest = 90 })
            }

            SectionCard(title = "Treadmill") {
                TreadmillReadout(9.5f, 2f, elapsed = "12:34", distanceKm = 2.01f, phaseLabel = "Interval 2 of 6")
            }

            SectionCard(title = "Forecast") {
                LineChart(
                    listOf(
                        Series("Conservative", listOf(90f, 89f, 88f, 87.2f, 86.5f, 85.9f, 85.3f), AppColors.info, dashed = true),
                        Series(
                            "Expected", listOf(90f, 88.5f, 87f, 85.5f, 84.2f, 83f, 82f), AppColors.primary,
                            min = listOf(90f, 89f, 87.8f, 86.5f, 85.3f, 84.2f, 83.2f),
                            max = listOf(90f, 88f, 86.2f, 84.5f, 83f, 81.8f, 80.7f),
                        ),
                        Series("Best", listOf(90f, 87.8f, 85.6f, 83.7f, 82f, 80.4f, 79f), AppColors.energy, dashed = true),
                    ),
                    listOf("Now", "W4", "W8", "W12", "W16", "W20", "W24"),
                    yFormat = { "${it.roundToInt()}" },
                )
            }

            SectionCard(title = "Badges") {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    BadgeTile("First week", true, Modifier.weight(1f))
                    BadgeTile("10 sessions", false, Modifier.weight(1f), subtitle = "6 to go")
                    BadgeTile("Early bird", true, Modifier.weight(1f))
                }
            }

            SecondaryButton("Progress +10%", { progress = if (progress >= 1f) 0.2f else progress + 0.1f }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(Spacing.xxl))
        }
        ConfettiBurst(confetti)
    }
}

@Preview(name = "Gallery (dark)", widthDp = 400, heightDp = 2600)
@Composable
private fun GalleryDarkPreview() {
    TrainingTheme(darkTheme = true) { GalleryScreen() }
}

@Preview(name = "Gallery (light)", widthDp = 400, heightDp = 2600)
@Composable
private fun GalleryLightPreview() {
    TrainingTheme(darkTheme = false) { GalleryScreen() }
}
