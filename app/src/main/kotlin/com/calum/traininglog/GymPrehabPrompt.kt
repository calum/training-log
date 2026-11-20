package com.calum.traininglog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.BigActionButton
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard
import com.calum.traininglog.ui.Spacing

/** What the hip prehab is, in plain words (shared by the prompt and the "next" note on the last gym screens). */
internal const val PREHAB_BLURB =
    "Side-lying hip abduction, single-leg glute bridge, side plank and lateral steps, then two short stretches. Only the kit you own."

/**
 * Shown on the gym screen after "Finish session" when the planned strength session includes hip prehab: the lifts are
 * already saved, so the next step is a clear choice between the guided hip-only Knee care routine and skipping it.
 * Skipping is always fine and nothing is penalised.
 */
@Composable
internal fun GymPrehabPrompt(vm: AppViewModel, live: GymLive) {
    GymPrehabPromptContent(live.prehabMinutes, onStart = { vm.startPrehab() }, onSkip = { vm.skipPrehab() })
}

@Composable
internal fun GymPrehabPromptContent(minutes: Int, onStart: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(AppColors.background).verticalScroll(rememberScrollState())
            .statusBarsPadding().padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Text("Lifts saved", style = MaterialTheme.typography.displaySmall, color = AppColors.textPrimary)
        Text(
            "Nice work. The plan has hip prehab after the lifting. It is optional, and skipping it is fine.",
            style = MaterialTheme.typography.titleMedium, color = AppColors.textMuted,
        )
        SectionCard(title = "Next: hip prehab (about $minutes min)") {
            Text(PREHAB_BLURB, style = MaterialTheme.typography.bodyMedium, color = AppColors.textPrimary)
            Text(
                "Guided, with a short list of what to have to hand. It counts as Knee care for the week.",
                style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted,
            )
        }
        BigActionButton("Start hip prehab", onStart)
        SecondaryButton("Skip", onSkip, Modifier.fillMaxWidth(), accent = AppColors.textMuted)
        Spacer(Modifier.height(Spacing.lg).navigationBarsPadding())
    }
}
