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

/**
 * Shown on the gym screen after "Finish session" when the strength day has a station finisher: the lifts are
 * already saved, so the next step is a clear choice between doing the finisher (the guided Hyrox session for those
 * stations) and skipping it. Skipping is always fine.
 */
@Composable
internal fun GymFinisherPrompt(vm: AppViewModel, live: GymLive) {
    GymFinisherPromptContent(live.finisher, live.finisherMinutes, onStart = { vm.startFinisher() }, onSkip = { vm.skipFinisher() })
}

@Composable
internal fun GymFinisherPromptContent(finisher: List<String>, minutes: Int, onStart: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(AppColors.background).verticalScroll(rememberScrollState())
            .statusBarsPadding().padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Text("Lifts saved", style = MaterialTheme.typography.displaySmall, color = AppColors.textPrimary)
        Text(
            "Nice work. There is a station finisher planned after the lifts. It is optional, and skipping it is fine.",
            style = MaterialTheme.typography.titleMedium, color = AppColors.textMuted,
        )
        SectionCard(title = "Station finisher") {
            finisher.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.textMuted) }
            finisher.drop(1).forEach { Text(it, style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary) }
        }
        BigActionButton("Do the station finisher (about $minutes min)", onStart)
        SecondaryButton("Skip finisher", onSkip, Modifier.fillMaxWidth(), accent = AppColors.textMuted)
        Spacer(Modifier.height(Spacing.lg).navigationBarsPadding())
    }
}
