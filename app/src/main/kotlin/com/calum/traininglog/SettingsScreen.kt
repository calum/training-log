package com.calum.traininglog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.calum.traininglog.ui.AppShapes
import com.calum.traininglog.ui.AppType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.calum.traininglog.ui.SelectChip
import java.time.DayOfWeek
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.calum.traininglog.core.Calibration
import com.calum.traininglog.core.Exercise
import com.calum.traininglog.core.ExerciseLibrary
import com.calum.traininglog.core.LiftOverride
import com.calum.traininglog.core.UserSettings
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.BigNumberStepper
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard
import com.calum.traininglog.ui.Spacing
import kotlin.math.roundToInt

private enum class Group(val title: String) {
    LIFTS("Lifts"),
    TARGETS("Weekly targets"),
    TRAVEL("Travel & time"),
    TREADMILL("Treadmill"),
    PROGRESSION("Progression"),
}

private fun round1(x: Double) = Math.round(x * 10) / 10.0

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val data by vm.data.collectAsState()
    val settings = data.profile.settings
    val calibration = data.profile.treadmill
    var resetting by remember { mutableStateOf<Group?>(null) }
    var openLift by remember { mutableStateOf<String?>(null) }
    var openRow by remember { mutableStateOf<String?>(null) }
    var openGroups by remember { mutableStateOf(setOf<String>()) }
    fun toggleGroup(key: String) { openGroups = if (key in openGroups) openGroups - key else openGroups + key; openRow = null }
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // Keep the evening nudge scheduled (or cancelled) in step with the setting.
    LaunchedEffect(settings.reminderEnabled, settings.reminderHour, settings.reminderMinute) {
        runCatching { ReminderManager.schedule(context, settings) }
    }

    // Every change is saved straight away and kept inside its allowed range.
    fun update(f: (UserSettings) -> UserSettings) = vm.updateProfile { it.copy(settings = f(it.settings).sanitized()) }
    fun updateCalibration(f: (Calibration) -> Calibration) = vm.updateProfile {
        val c = f(it.treadmill)
        it.copy(treadmill = Calibration(round1(c.speedKmh.coerceIn(UserSettings.SPEED_RANGE)), c.inclinePct.coerceIn(0.0, com.calum.traininglog.core.MAX_INCLINE)))
    }

    val defaults = UserSettings()
    fun reset(g: Group) {
        when (g) {
            Group.LIFTS -> update { it.copy(lifts = defaults.lifts) }
            Group.TARGETS -> update {
                it.copy(
                    gymTarget = defaults.gymTarget, treadmillTarget = defaults.treadmillTarget,
                    gymStretch = defaults.gymStretch, treadmillStretch = defaults.treadmillStretch,
                )
            }
            Group.TRAVEL -> update { it.copy(travelMinEachWay = defaults.travelMinEachWay, minLiftingMin = defaults.minLiftingMin) }
            Group.TREADMILL -> {
                update { it.copy(warmupSpeedKmh = defaults.warmupSpeedKmh, warmupInclinePct = defaults.warmupInclinePct) }
                updateCalibration { Calibration() }
            }
            Group.PROGRESSION -> update {
                it.copy(
                    deloadWeeks = defaults.deloadWeeks, restartAfterDays = defaults.restartAfterDays,
                    restartFactor = defaults.restartFactor, barKg = defaults.barKg,
                )
            }
        }
    }

    @Composable
    fun ResetButton(g: Group) = SecondaryButton("Reset to defaults", onClick = { resetting = g }, modifier = Modifier.fillMaxWidth())

    Page {
        Title("Settings")
        Muted("Changes save as you make them. They only affect future plans; your history stays as it is.")

        // ---- Lifts ----
        GroupCard(Group.LIFTS.title, "${ExerciseLibrary.all.size} lifts, ${settings.lifts.size} customised", open = "lifts" in openGroups, onToggle = { toggleGroup("lifts") }) {
            Muted("Starting weight is used the first time you do a lift. Increment is how much is added when it goes well. Rest is the timer between sets.")
            ExerciseLibrary.all.forEach { base ->
                val ex = settings.resolve(base)
                LiftRow(
                    ex = ex,
                    changed = settings.lifts.containsKey(base.id),
                    expanded = openLift == base.id,
                    onToggle = { openLift = if (openLift == base.id) null else base.id },
                ) {
                    fun set(o: LiftOverride) = update { it.withLift(base.id, o) }
                    fun cur() = LiftOverride(ex.startKg, ex.incrementKg, ex.restSec)
                    BigNumberStepper(
                        fmt(ex.startKg), "kg", label = "Starting weight",
                        onMinus = { set(cur().copy(startKg = ex.startKg - 2.5)) },
                        onPlus = { set(cur().copy(startKg = ex.startKg + 2.5)) },
                    )
                    BigNumberStepper(
                        fmt(ex.incrementKg), "kg", label = "Increment",
                        onMinus = { set(cur().copy(incrementKg = ex.incrementKg - 0.5)) },
                        onPlus = { set(cur().copy(incrementKg = ex.incrementKg + 0.5)) },
                    )
                    BigNumberStepper(
                        "${ex.restSec}", "sec", label = "Rest between sets",
                        onMinus = { set(cur().copy(restSec = ex.restSec - 15)) },
                        onPlus = { set(cur().copy(restSec = ex.restSec + 15)) },
                    )
                    TextButton(onClick = { update { it.copy(lifts = it.lifts - base.id) } }) {
                        Text("Reset ${base.name}", color = AppColors.primary)
                    }
                }
            }
            ResetButton(Group.LIFTS)
        }

        // ---- Weekly targets ----
        GroupCard(Group.TARGETS.title, "Gym ${settings.gymTarget} · Treadmill ${settings.treadmillTarget} a week", open = "targets" in openGroups, onToggle = { toggleGroup("targets") }) {
            Muted("A good week is the first number. The stretch is a bonus, never a requirement. Use 0 for a session type you don't want counted.")
            SettingRow(
                "${settings.gymTarget}", "gym", label = "Good week: gym", open = openRow, onOpen = { openRow = it },
                onMinus = { update { it.copy(gymTarget = it.gymTarget - 1) } },
                onPlus = { update { it.copy(gymTarget = it.gymTarget + 1, gymStretch = maxOf(it.gymStretch, it.gymTarget + 1)) } },
            )
            SettingRow(
                "${settings.treadmillTarget}", "treadmill", label = "Good week: treadmill", open = openRow, onOpen = { openRow = it },
                onMinus = { update { it.copy(treadmillTarget = it.treadmillTarget - 1) } },
                onPlus = { update { it.copy(treadmillTarget = it.treadmillTarget + 1, treadmillStretch = maxOf(it.treadmillStretch, it.treadmillTarget + 1)) } },
            )
            SettingRow(
                "${settings.effectiveGymStretch}", "gym", label = "Stretch: gym", open = openRow, onOpen = { openRow = it },
                onMinus = { update { it.copy(gymStretch = maxOf(it.gymTarget, it.effectiveGymStretch - 1)) } },
                onPlus = { update { it.copy(gymStretch = it.effectiveGymStretch + 1) } },
            )
            SettingRow(
                "${settings.effectiveTreadmillStretch}", "treadmill", label = "Stretch: treadmill", open = openRow, onOpen = { openRow = it },
                onMinus = { update { it.copy(treadmillStretch = maxOf(it.treadmillTarget, it.effectiveTreadmillStretch - 1)) } },
                onPlus = { update { it.copy(treadmillStretch = it.effectiveTreadmillStretch + 1) } },
            )
            ResetButton(Group.TARGETS)
        }

        // ---- Travel & time ----
        GroupCard(Group.TRAVEL.title, "${settings.travelMinEachWay} min each way · ${settings.minLiftingMin} min lifting at least", open = "travel" in openGroups, onToggle = { toggleGroup("travel") }) {
            Muted("Travel time is taken off your free time before planning the lifting. A gym session is only suggested when at least the minimum is left.")
            SettingRow(
                "${settings.travelMinEachWay}", "min each way", label = "Travel to the gym", open = openRow, onOpen = { openRow = it },
                onMinus = { update { it.copy(travelMinEachWay = it.travelMinEachWay - 1) } },
                onPlus = { update { it.copy(travelMinEachWay = it.travelMinEachWay + 1) } },
            )
            SettingRow(
                "${settings.minLiftingMin}", "min", label = "Shortest worthwhile lifting", open = openRow, onOpen = { openRow = it },
                onMinus = { update { it.copy(minLiftingMin = it.minLiftingMin - 5) } },
                onPlus = { update { it.copy(minLiftingMin = it.minLiftingMin + 5) } },
            )
            ResetButton(Group.TRAVEL)
        }

        // ---- Treadmill ----
        GroupCard(Group.TREADMILL.title, "Start ${String.format("%.1f", calibration.speedKmh)} km/h · ${fmt(calibration.inclinePct)}%", open = "treadmill" in openGroups, onToggle = { toggleGroup("treadmill") }) {
            Muted("Where your sessions start. Speed and incline for a steady walk also adjust themselves from your \"too easy / too hard\" feedback.")
            SettingRow(
                String.format("%.1f", calibration.speedKmh), "km/h", label = "Starting speed", open = openRow, onOpen = { openRow = it },
                onMinus = { updateCalibration { it.copy(speedKmh = it.speedKmh - 0.1) } },
                onPlus = { updateCalibration { it.copy(speedKmh = it.speedKmh + 0.1) } },
            )
            SettingRow(
                fmt(calibration.inclinePct), "% incline", label = "Starting incline", open = openRow, onOpen = { openRow = it },
                onMinus = { updateCalibration { it.copy(inclinePct = it.inclinePct - 1) } },
                onPlus = { updateCalibration { it.copy(inclinePct = it.inclinePct + 1) } },
            )
            SettingRow(
                String.format("%.1f", settings.warmupSpeedKmh), "km/h", label = "Warm-up speed", open = openRow, onOpen = { openRow = it },
                onMinus = { update { it.copy(warmupSpeedKmh = round1(it.warmupSpeedKmh - 0.1)) } },
                onPlus = { update { it.copy(warmupSpeedKmh = round1(it.warmupSpeedKmh + 0.1)) } },
            )
            SettingRow(
                fmt(settings.warmupInclinePct), "% incline", label = "Warm-up incline", open = openRow, onOpen = { openRow = it },
                onMinus = { update { it.copy(warmupInclinePct = it.warmupInclinePct - 1) } },
                onPlus = { update { it.copy(warmupInclinePct = it.warmupInclinePct + 1) } },
            )
            SettingRow(
                fmt(settings.bodyWeightKg), "kg", label = "Body weight (for calories)", open = openRow, onOpen = { openRow = it },
                onMinus = { update { it.copy(bodyWeightKg = it.bodyWeightKg - 1) } },
                onPlus = { update { it.copy(bodyWeightKg = it.bodyWeightKg + 1) } },
            )
            Muted("Only used for the calorie estimate on the treadmill screen; it is never shared or synced.")
            ResetButton(Group.TREADMILL)
        }

        // ---- Progression ----
        GroupCard(Group.PROGRESSION.title, "Lighter week every ${settings.deloadWeeks} weeks", open = "progression" in openGroups, onToggle = { toggleGroup("progression") }) {
            Muted("A lighter week is suggested after this many steady weeks. After a longer break, lifts restart a bit lighter so you ease back in.")
            SettingRow(
                "${settings.deloadWeeks}", "weeks", label = "Lighter week every", open = openRow, onOpen = { openRow = it },
                onMinus = { update { it.copy(deloadWeeks = it.deloadWeeks - 1) } },
                onPlus = { update { it.copy(deloadWeeks = it.deloadWeeks + 1) } },
            )
            SettingRow(
                "${settings.restartAfterDays / 7}", "weeks off", label = "Counts as a break after", open = openRow, onOpen = { openRow = it },
                onMinus = { update { it.copy(restartAfterDays = it.restartAfterDays - 7) } },
                onPlus = { update { it.copy(restartAfterDays = it.restartAfterDays + 7) } },
            )
            SettingRow(
                "${(settings.restartFactor * 100).roundToInt()}", "% of last weight", label = "Restart at", open = openRow, onOpen = { openRow = it },
                onMinus = { update { it.copy(restartFactor = round1(it.restartFactor * 20 - 1) / 20.0) } },
                onPlus = { update { it.copy(restartFactor = round1(it.restartFactor * 20 + 1) / 20.0) } },
            )
            SettingRow(
                fmt(settings.barKg), "kg", label = "Bar weight (warm-ups)", open = openRow, onOpen = { openRow = it },
                onMinus = { update { it.copy(barKg = it.barKg - 2.5) } },
                onPlus = { update { it.copy(barKg = it.barKg + 2.5) } },
            )
            ResetButton(Group.PROGRESSION)
        }

        // ---- Gentle reminder ----
        GroupCard("Gentle reminder", if (settings.reminderEnabled) "On, around ${String.format("%02d:%02d", settings.reminderHour, settings.reminderMinute)}" else "Off", open = "reminder" in openGroups, onToggle = { toggleGroup("reminder") }) {
            Muted("Totally optional and off by default. One quiet evening nudge on days you haven't trained, never on rest days, and never about missed sessions. Nothing is lost if you ignore it.")
            Row(Modifier.fillMaxWidth().heightIn(min = Spacing.minTap), verticalAlignment = Alignment.CenterVertically) {
                Text("Evening nudge", color = AppColors.textPrimary, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.reminderEnabled,
                    onCheckedChange = { on ->
                        update { it.copy(reminderEnabled = on) }
                        if (on && Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                )
            }
            if (settings.reminderEnabled) {
                val minutes = settings.reminderHour * 60 + settings.reminderMinute
                fun setTime(m: Int) {
                    val t = Math.floorMod(m, 24 * 60)
                    update { it.copy(reminderHour = t / 60, reminderMinute = t % 60) }
                }
                SettingRow(
                    String.format("%02d:%02d", settings.reminderHour, settings.reminderMinute), "time", label = "Remind me around", open = openRow, onOpen = { openRow = it },
                    onMinus = { setTime(minutes - 15) },
                    onPlus = { setTime(minutes + 15) },
                )
                Muted("On these days")
                fun dayChip(d: DayOfWeek): @Composable () -> Unit = {
                    SelectChip(
                        d.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()),
                        selected = d in settings.reminderDays,
                        onClick = {
                            update { it.copy(reminderDays = if (d in it.reminderDays) it.reminderDays - d else it.reminderDays + d) }
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    DayOfWeek.entries.take(4).forEach { dayChip(it)() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    DayOfWeek.entries.drop(4).forEach { dayChip(it)() }
                }
                SettingRow(
                    "${settings.reminderMaxPerWeek}", "a week", label = "At most", open = openRow, onOpen = { openRow = it },
                    onMinus = { update { it.copy(reminderMaxPerWeek = it.reminderMaxPerWeek - 1) } },
                    onPlus = { update { it.copy(reminderMaxPerWeek = it.reminderMaxPerWeek + 1) } },
                )
            }
        }

        SecondaryButton("Back", onClick = { vm.home() }, modifier = Modifier.fillMaxWidth())
    }

    resetting?.let { g ->
        AlertDialog(
            onDismissRequest = { resetting = null },
            title = { Text("Reset ${g.title.lowercase()}?") },
            text = { Text("This puts ${g.title.lowercase()} back to the original values. Your history is not touched.") },
            confirmButton = { TextButton({ reset(g); resetting = null }) { Text("Reset") } },
            dismissButton = { TextButton({ resetting = null }) { Text("Keep my settings") } },
        )
    }
}

@Composable
private fun Chevron(open: Boolean, color: Color = AppColors.textMuted) {
    val angle by animateFloatAsState(if (open) 180f else 0f, label = "chev")
    Canvas(Modifier.size(20.dp).rotate(angle)) {
        val sw = 2.5.dp.toPx()
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * 0.15f, size.height * 0.35f); lineTo(size.width * 0.5f, size.height * 0.68f); lineTo(size.width * 0.85f, size.height * 0.35f)
        }
        drawPath(path, color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Collapsible settings group: tappable header with a one-line summary, content only while open. */
@Composable
private fun GroupCard(title: String, summary: String, open: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    SectionCard(title = title, action = { Chevron(open) }, onClick = onToggle) {
        if (!open) Text(summary, style = MaterialTheme.typography.bodyLarge, color = AppColors.textMuted)
        else content()
    }
}

/** A setting: label left, current value in large type right; tap it to open an inline stepper. */
@Composable
private fun SettingRow(
    value: String, unit: String, label: String, open: String?, onOpen: (String?) -> Unit,
    onMinus: () -> Unit, onPlus: () -> Unit,
) {
    val isOpen = open == label
    Column(
        Modifier.fillMaxWidth().clip(AppShapes.medium).background(AppColors.surfaceRaised)
            .border(if (isOpen) 2.dp else 0.dp, if (isOpen) AppColors.primary.copy(alpha = 0.6f) else Color.Transparent, AppShapes.medium),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { onOpen(if (isOpen) null else label) }
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = AppColors.textPrimary, modifier = Modifier.weight(1f))
            Text(value, style = AppType.numberS, color = AppColors.primary, maxLines = 1)
            Text(unit, style = MaterialTheme.typography.labelMedium, color = AppColors.textMuted, modifier = Modifier.widthIn(max = 96.dp))
            Chevron(isOpen)
        }
        if (isOpen) {
            Box(Modifier.padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md)) {
                BigNumberStepper(value, unit, onMinus = onMinus, onPlus = onPlus)
            }
        }
    }
}

@Composable
private fun LiftRow(
    ex: Exercise,
    changed: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    body: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(AppShapes.medium).background(AppColors.surfaceRaised)
                .clickable(onClick = onToggle).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(ex.name, color = if (changed) AppColors.primary else AppColors.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Start ${fmt(ex.startKg)} kg  |  +${fmt(ex.incrementKg)} kg  |  rest ${ex.restSec} s",
                    color = AppColors.textMuted, style = MaterialTheme.typography.bodySmall,
                )
            }
            Chevron(expanded)
        }
        if (expanded) {
            body()
            Spacer(Modifier.height(Spacing.xs))
        }
    }
}
