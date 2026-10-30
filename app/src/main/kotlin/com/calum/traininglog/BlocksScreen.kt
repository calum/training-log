package com.calum.traininglog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calum.traininglog.core.BlockExport
import com.calum.traininglog.core.BlockProgress
import com.calum.traininglog.core.BlockState
import com.calum.traininglog.core.BlockTarget
import com.calum.traininglog.core.Blocks
import com.calum.traininglog.core.ExerciseLibrary
import com.calum.traininglog.core.ExtraGoal
import com.calum.traininglog.core.ExtraKind
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.TrainingBlock
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.AppShapes
import com.calum.traininglog.ui.BigActionButton
import com.calum.traininglog.ui.BigNumberStepper
import com.calum.traininglog.ui.HeroCard
import com.calum.traininglog.ui.Pill
import com.calum.traininglog.ui.ProgressRing
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard
import com.calum.traininglog.ui.SelectChip
import com.calum.traininglog.ui.AppType
import com.calum.traininglog.ui.Spacing
import java.time.LocalDate
import java.util.UUID

private val liftChoices = listOf("squat" to "Squat", "deadlift" to "Deadlift", "rdl" to "RDL", "calf" to "Calf")

@Composable
fun BlocksScreen(vm: AppViewModel) {
    val data by vm.data.collectAsState()
    BlocksContent(
        state = data.profile.blocks,
        records = data.records,
        today = LocalDate.now(),
        onChange = { f -> vm.updateProfile { it.copy(blocks = f(it.blocks)) } },
        onBack = { vm.home() },
    )
}

/** Mutable form values for creating a block. */
private class BlockForm(b: TrainingBlock) {
    var name by mutableStateOf(b.name)
    var weeks by mutableIntStateOf(b.weeks)
    var lift by mutableStateOf(b.headline.exerciseId)
    var target by mutableStateOf(b.headline.targetKg)
    var sessionsOn by mutableStateOf(b.extras.any { it.kind == ExtraKind.SESSIONS_PER_WEEK })
    var sessions by mutableIntStateOf(b.extras.firstOrNull { it.kind == ExtraKind.SESSIONS_PER_WEEK }?.target ?: 2)
    var minutesOn by mutableStateOf(b.extras.any { it.kind == ExtraKind.TREADMILL_MINUTES })
    var minutes by mutableIntStateOf(b.extras.firstOrNull { it.kind == ExtraKind.TREADMILL_MINUTES }?.target ?: 240)
    var totalOn by mutableStateOf(b.extras.any { it.kind == ExtraKind.TOTAL_SESSIONS })
    var total by mutableIntStateOf(b.extras.firstOrNull { it.kind == ExtraKind.TOTAL_SESSIONS }?.target ?: 12)

    fun load(b: TrainingBlock) {
        name = b.name; weeks = b.weeks; lift = b.headline.exerciseId; target = b.headline.targetKg
        sessionsOn = b.extras.any { it.kind == ExtraKind.SESSIONS_PER_WEEK }
        b.extras.firstOrNull { it.kind == ExtraKind.SESSIONS_PER_WEEK }?.let { sessions = it.target }
        minutesOn = b.extras.any { it.kind == ExtraKind.TREADMILL_MINUTES }
        b.extras.firstOrNull { it.kind == ExtraKind.TREADMILL_MINUTES }?.let { minutes = it.target }
        totalOn = b.extras.any { it.kind == ExtraKind.TOTAL_SESSIONS }
        b.extras.firstOrNull { it.kind == ExtraKind.TOTAL_SESSIONS }?.let { total = it.target }
    }

    fun build(records: List<SessionRecord>, today: LocalDate): TrainingBlock = TrainingBlock(
        id = UUID.randomUUID().toString(),
        name = name.ifBlank { "Block" },
        startDate = today,
        weeks = weeks.coerceIn(Blocks.MIN_WEEKS, Blocks.MAX_WEEKS),
        headline = BlockTarget(lift, target, Blocks.currentBest(records, lift, today)),
        extras = buildList {
            if (sessionsOn) add(ExtraGoal(ExtraKind.SESSIONS_PER_WEEK, sessions))
            if (minutesOn) add(ExtraGoal(ExtraKind.TREADMILL_MINUTES, minutes))
            if (totalOn) add(ExtraGoal(ExtraKind.TOTAL_SESSIONS, total))
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BlocksContent(
    state: BlockState,
    records: List<SessionRecord>,
    today: LocalDate,
    onChange: ((BlockState) -> BlockState) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val cur = Blocks.current(state, today)
    val past = Blocks.finished(state, today)
    val form = remember { BlockForm(Blocks.suggestNext(state, records, today)) }
    var showForm by remember { mutableStateOf(cur == null) }
    var reviewId by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(AppColors.background).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text("Training blocks", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, color = AppColors.textPrimary)
            SecondaryButton("Back", onBack)
        }

        if (cur != null) {
            val p = remember(cur, records, today) { Blocks.progress(cur, records, today) }
            CurrentHero(p)
            WeekStrip(p)
            ProgressCard(p)
            SectionCard(title = "So far") {
                p.wins.forEach { WinLine(it) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SecondaryButton("Add a week", { onChange { Blocks.extend(it, today) } }, Modifier.weight(1f), enabled = cur.weeks < 12)
                SecondaryButton("Finish today", { onChange { Blocks.finishEarly(it, today) } }, Modifier.weight(1f))
            }
            SecondaryButton("Block review", { reviewId = cur.id }, Modifier.fillMaxWidth(), accent = AppColors.reward)
            if (!showForm) SecondaryButton("Start a new block instead", { showForm = true }, Modifier.fillMaxWidth())
        } else {
            val last = past.firstOrNull()
            SectionCard(title = if (last == null) "Your first block" else "Block done. Nicely done") {
                Text(
                    if (last == null) "A block is a short goal, usually 6 weeks. Short blocks keep goals fresh and wins coming."
                    else "${last.name} is finished. Review it, then pick the next goal while it is fresh.",
                    color = AppColors.textMuted, style = MaterialTheme.typography.bodyMedium,
                )
                if (last != null) SecondaryButton("Review ${last.name}", { reviewId = last.id }, Modifier.fillMaxWidth(), accent = AppColors.reward)
            }
        }

        if (showForm) {
            NewBlockCard(form, records, today, cur != null,
                onSuggest = { form.load(Blocks.suggestNext(state, records, today)) },
                onStart = {
                    onChange { s -> Blocks.start(s, form.build(records, today), today) }
                    showForm = false
                },
            )
        } else if (cur == null) {
            BigActionButton("Start a block", { showForm = true })
        }

        if (past.isNotEmpty()) {
            SectionCard(title = "Finished blocks") {
                past.forEach { b ->
                    val bp = Blocks.progress(b, records, today)
                    Column(
                        Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.surfaceRaised)
                            .padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            Text(b.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary)
                            Pill(if (bp.headlineReached) "Target reached" else "${(bp.headlineFraction * 100).toInt()}% there", color = if (bp.headlineReached) AppColors.primary else AppColors.info)
                        }
                        Text(
                            "${ExerciseLibrary.nameOf(b.headline.exerciseId)} ${fmt(b.headline.startKg)} to ${fmt(bp.currentKg)} kg. ${bp.sessionsDone} sessions.",
                            color = AppColors.textMuted, style = MaterialTheme.typography.bodyMedium,
                        )
                        bp.wins.take(2).forEach { WinLine(it) }
                        TextRow("Review") { reviewId = b.id }
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.xl))
    }

    val review = state.blocks.firstOrNull { it.id == reviewId }
    if (review != null) {
        val text = remember(review, records, today) { BlockExport.text(review, records, today) }
        ModalBottomSheet(onDismissRequest = { reviewId = null }, containerColor = AppColors.surface) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.screen),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text("Block review: ${review.name}", style = MaterialTheme.typography.titleLarge, color = AppColors.textPrimary)
                Text("Copy this into a chat with Claude to plan the next block. It includes private notes (knee, feelings), so keep it between you two.", color = AppColors.textMuted, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    BigActionButton("Copy", modifier = Modifier.weight(1f), onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Block review", text))
                    })
                    SecondaryButton("Share", modifier = Modifier.weight(1f), onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                        ctx.startActivity(Intent.createChooser(send, "Share block review"))
                    })
                }
                SectionCard {
                    SelectionContainer {
                        Text(text, color = AppColors.textPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(Spacing.xl))
            }
        }
    }
}

@Composable
private fun WinLine(text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Box(Modifier.padding(top = 8.dp).size(8.dp).clip(CircleShape).background(AppColors.primary))
        Text(text, color = AppColors.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TextRow(label: String, onClick: () -> Unit) {
    Text(
        label, color = AppColors.primary, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(AppShapes.chip).clickable(onClick = onClick).padding(vertical = Spacing.sm),
    )
}

@Composable
private fun CurrentHero(p: BlockProgress) {
    HeroCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            ProgressRing(p.headlineFraction.toFloat(), bonusProgress = 0f, size = 140.dp, strokeWidth = 12.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${(p.headlineFraction * 100).toInt()}%", style = AppType.numberM, color = AppColors.onHero)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(p.block.name, style = MaterialTheme.typography.titleLarge, color = AppColors.onHero)
                Text("Week ${p.weekNumber} of ${p.weeks}", style = MaterialTheme.typography.titleMedium, color = AppColors.onHero)
                Text(
                    "${ExerciseLibrary.nameOf(p.block.headline.exerciseId)} ${fmt(p.currentKg)} of ${fmt(p.targetKg)} kg",
                    style = MaterialTheme.typography.bodyMedium, color = AppColors.onHero,
                )
                Text(p.paceText, style = MaterialTheme.typography.bodyMedium, color = AppColors.onHero.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
private fun WeekStrip(p: BlockProgress) {
    SectionCard(title = "Weeks") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            p.sessionsPerWeek.forEachIndexed { i, n ->
                val isNow = i + 1 == p.weekNumber && !p.finished
                val future = i + 1 > p.weekNumber
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (n > 0) AppColors.primary.copy(alpha = 0.22f) else AppColors.surfaceRaised)
                        .padding(vertical = Spacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("W${i + 1}", style = MaterialTheme.typography.labelSmall, color = if (isNow) AppColors.primary else AppColors.textMuted)
                    Text(if (future) "" else "$n", style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary)
                }
            }
        }
        Text("Sessions per week. Quiet weeks are normal.", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
    }
}

@Composable
private fun ProgressCard(p: BlockProgress) {
    SectionCard(title = "Targets") {
        Text(
            "${ExerciseLibrary.nameOf(p.block.headline.exerciseId)}: ${fmt(p.startKg)} to ${fmt(p.currentKg)} kg, target ${fmt(p.targetKg)}",
            color = AppColors.textPrimary, style = MaterialTheme.typography.bodyLarge,
        )
        BlockBar(p.headlineFraction)
        p.extras.forEach {
            Text(it.label, color = AppColors.textPrimary, style = MaterialTheme.typography.bodyMedium)
            BlockBar(it.fraction, color = AppColors.reward)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewBlockCard(
    f: BlockForm,
    records: List<SessionRecord>,
    today: LocalDate,
    replacing: Boolean,
    onSuggest: () -> Unit,
    onStart: () -> Unit,
) {
    SectionCard(title = "New block") {
        if (replacing) Text("Starting a new block finishes the current one, keeping everything you did.", color = AppColors.textMuted, style = MaterialTheme.typography.bodySmall)
        androidx.compose.material3.OutlinedTextField(
            value = f.name, onValueChange = { f.name = it }, label = { Text("Name") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        BigNumberStepper(
            value = "${f.weeks}", unit = "weeks", label = "Length",
            onMinus = { f.weeks = (f.weeks - 1).coerceAtLeast(Blocks.MIN_WEEKS) },
            onPlus = { f.weeks = (f.weeks + 1).coerceAtMost(Blocks.MAX_WEEKS) },
        )
        Text("HEADLINE LIFT", color = AppColors.textMuted, style = AppType.eyebrow)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            liftChoices.forEach { (id, name) ->
                SelectChip(name, f.lift == id, {
                    f.lift = id
                    f.target = Blocks.defaultTarget(id, records, today)
                }, accent = AppColors.reward)
            }
        }
        BigNumberStepper(
            value = fmt(f.target), unit = "kg", label = "Target (now ${fmt(Blocks.currentBest(records, f.lift, today))} kg)",
            onMinus = { f.target = (f.target - 2.5).coerceAtLeast(2.5) },
            onPlus = { f.target = (f.target + 2.5).coerceAtMost(500.0) },
        )
        Text("OPTIONAL SMALLER GOALS", color = AppColors.textMuted, style = AppType.eyebrow)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SelectChip("Sessions a week", f.sessionsOn, { f.sessionsOn = !f.sessionsOn })
            SelectChip("Treadmill minutes", f.minutesOn, { f.minutesOn = !f.minutesOn })
            SelectChip("Total sessions", f.totalOn, { f.totalOn = !f.totalOn })
        }
        if (f.sessionsOn) BigNumberStepper(
            "${f.sessions}", "a week", label = "Sessions a week", onMinus = { f.sessions = (f.sessions - 1).coerceAtLeast(1) },
            onPlus = { f.sessions = (f.sessions + 1).coerceAtMost(7) },
        )
        if (f.minutesOn) BigNumberStepper(
            "${f.minutes}", "min", label = "Treadmill minutes (whole block)", onMinus = { f.minutes = (f.minutes - 30).coerceAtLeast(30) },
            onPlus = { f.minutes = (f.minutes + 30).coerceAtMost(3000) },
        )
        if (f.totalOn) BigNumberStepper(
            "${f.total}", "sessions", label = "Total sessions", onMinus = { f.total = (f.total - 1).coerceAtLeast(1) },
            onPlus = { f.total = (f.total + 1).coerceAtMost(100) },
        )
        SecondaryButton("Suggest next block", onSuggest, Modifier.fillMaxWidth(), accent = AppColors.reward)
        BigActionButton("Start block", onStart)
    }
}
