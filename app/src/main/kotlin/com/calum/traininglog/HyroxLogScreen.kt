package com.calum.traininglog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.calum.traininglog.core.HyroxDivision
import com.calum.traininglog.core.HyroxProgression
import com.calum.traininglog.core.HyroxRuns
import com.calum.traininglog.core.HyroxStation as Station
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.StationEntry
import com.calum.traininglog.core.StationKind
import com.calum.traininglog.core.StationLog
import com.calum.traininglog.core.UserSettings
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.AppType
import com.calum.traininglog.ui.BigActionButton
import com.calum.traininglog.ui.BigNumberStepper
import com.calum.traininglog.ui.SecondaryButton
import com.calum.traininglog.ui.SectionCard
import com.calum.traininglog.ui.SelectChip
import com.calum.traininglog.ui.Spacing
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Pure state (unit-tested without any UI)
// ---------------------------------------------------------------------------------------------

/** "1:12" -> 72, "72" -> 72, "12:5" -> null (seconds must be two digits). Null when it cannot be read. */
fun parseClock(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val parts = t.split(":")
    return when (parts.size) {
        1 -> t.toIntOrNull()?.takeIf { it >= 0 }
        2 -> {
            val m = parts[0].trim().toIntOrNull() ?: return null
            val s = parts[1].trim()
            if (s.length != 2) return null
            val sec = s.toIntOrNull() ?: return null
            if (m < 0 || sec !in 0..59) null else m * 60 + sec
        }
        else -> null
    }
}

/**
 * The quick log in progress: stations already added, the one being edited, and how many days back it was.
 * [dirty] is false right after "Add another station", so an untouched new editor is not saved by accident.
 * A [StationLog.seconds] of null (or 0) means no time was entered.
 */
data class HyroxLogForm(
    val done: List<StationLog> = emptyList(),
    val current: StationLog,
    val dirty: Boolean = true,
    val daysAgo: Int = 0,
) {
    fun edit(f: (StationLog) -> StationLog) = copy(current = f(current), dirty = true)

    fun pick(defaults: StationLog) = copy(current = defaults, dirty = true)

    fun adjustWeight(steps: Int) = edit { s -> s.weightKg?.let { s.copy(weightKg = (it + steps * HyroxController.WEIGHT_STEP).coerceAtLeast(0.0)) } ?: s }

    fun adjustDistance(steps: Int): HyroxLogForm {
        val step = if (isRun) RUN_STEP else HyroxController.distanceStep(Station.byId(current.stationId) ?: return this)
        return edit { s -> s.distanceM?.let { s.copy(distanceM = (it + steps * step).coerceAtLeast(0)) } ?: s }
    }

    /** True while the editor is on a run (done outside the app) rather than a station. */
    val isRun: Boolean get() = current.stationId == HyroxRuns.ID

    fun adjustReps(steps: Int) = edit { s -> s.reps?.let { s.copy(reps = (it + steps * HyroxController.REPS_STEP).coerceAtLeast(0)) } ?: s }

    /** Time in 10 second steps; going down to 0 clears it. */
    fun adjustSeconds(steps: Int) = edit { s -> s.copy(seconds = ((s.seconds ?: 0) + steps * TIME_STEP).coerceAtLeast(0)) }

    fun setSeconds(sec: Int?) = edit { it.copy(seconds = sec?.coerceAtLeast(0)) }

    fun setRpe(v: Int) = edit { it.copy(rpe = if (it.rpe == v) null else v) }
    fun setTechnique(v: Int) = edit { it.copy(technique = if (it.technique == v) null else v) }
    fun setDaysAgo(d: Int) = copy(daysAgo = d.coerceIn(0, MAX_DAYS_BACK))

    /** Commits the current station and opens a fresh editor for the next one not added yet. */
    fun addAnother(defaultsFor: (Station) -> StationLog): HyroxLogForm {
        val used = (done + current).mapNotNull { Station.byId(it.stationId) }.toSet()
        val next = Station.entries.firstOrNull { it !in used } ?: Station.byId(current.stationId) ?: Station.SKIERG
        return copy(done = done + clean(current), current = defaultsFor(next), dirty = false)
    }

    fun removeDone(i: Int) = copy(done = done.filterIndexed { idx, _ -> idx != i })

    /** Everything to save: the added stations plus the editor (unless it is an untouched fresh one). */
    fun toSave(): List<StationLog> = done + (if (dirty || done.isEmpty()) listOf(clean(current)) else emptyList())

    private fun clean(s: StationLog) = s.copy(seconds = s.seconds?.takeIf { it > 0 })

    companion object {
        const val TIME_STEP = 10
        const val RUN_STEP = 100

        /** A run logged by hand: 400 m to start, time and effort optional. */
        fun runDefaults() = StationLog(stationId = HyroxRuns.ID, distanceM = HyroxRuns.DEFAULT_DISTANCE_M)
        const val MAX_DAYS_BACK = 14

        fun start(defaults: StationLog) = HyroxLogForm(current = defaults)

        /**
         * What the editor starts with for [station]: the last thing logged there (weight and total), else the
         * ladder's next prescription. Time starts empty.
         */
        fun defaults(station: Station, records: List<SessionRecord>, division: HyroxDivision, settings: UserSettings = UserSettings()): StationLog {
            val history = records.filter { it.type == SessionType.HYROX }
                .flatMap { r -> r.stations.filter { it.stationId == station.id }.map { StationEntry(r.date, it) } }
            val last = history.maxByOrNull { it.date }?.log
            val p = HyroxProgression.next(station, division, history, settings.hyroxWeightOverrides)
            val dist = station.kind == StationKind.DISTANCE
            return StationLog(
                stationId = station.id,
                weightKg = (last?.weightKg ?: p.weightKg).takeIf { station.weighted },
                distanceM = if (dist) (last?.distanceM ?: p.totalWork) else null,
                reps = if (!dist) (last?.reps ?: p.totalWork) else null,
                level = p.level,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------------------------

@Composable
fun HyroxLogScreen(vm: AppViewModel, station: Station?) {
    val data by vm.data.collectAsState()
    val settings = data.profile.settings
    val defaultsFor = { s: Station -> HyroxLogForm.defaults(s, data.records, settings.hyroxDivision, settings) }
    var form by remember { mutableStateOf(HyroxLogForm.start(defaultsFor(station ?: Station.SKIERG)).copy(dirty = station != null)) }
    HyroxLogContent(
        form, vm.todayDate(),
        onForm = { form = it }, defaultsFor = defaultsFor,
        onSave = { vm.saveHyroxLog(form.daysAgo, form.toSave()) },
        onBack = { vm.back() },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HyroxLogContent(
    form: HyroxLogForm,
    today: LocalDate,
    onForm: (HyroxLogForm) -> Unit,
    defaultsFor: (Station) -> StationLog,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val cur = form.current
    val st = Station.byId(cur.stationId) ?: Station.SKIERG
    val isRun = form.isRun
    val total = form.toSave().size
    Column(
        Modifier.fillMaxSize().background(AppColors.background).verticalScroll(rememberScrollState())
            .statusBarsPadding().padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text("Log a station", style = MaterialTheme.typography.headlineLarge, color = AppColors.textPrimary)
            Text("Already done it? Add it in a few taps. Everything except the station is optional.", style = MaterialTheme.typography.titleMedium, color = AppColors.textMuted)
        }

        if (form.done.isNotEmpty()) {
            SectionCard(title = "Added so far") {
                form.done.forEachIndexed { i, log ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(HyroxText.summary(log), style = MaterialTheme.typography.bodyLarge, color = AppColors.textPrimary, modifier = Modifier.weight(1f))
                        SecondaryButton("Remove", { onForm(form.removeDone(i)) }, Modifier.heightIn(min = 48.dp), accent = AppColors.textMuted)
                    }
                }
            }
        }

        FieldLabel("STATION")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Station.entries.forEach { s ->
                SelectChip(s.displayName, !isRun && s == st, { if (isRun || s != st) onForm(form.pick(defaultsFor(s))) })
            }
            SelectChip("Run", isRun, { if (!isRun) onForm(form.pick(HyroxLogForm.runDefaults())) })
        }

        FieldLabel("WHEN")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SelectChip("Today", form.daysAgo == 0, { onForm(form.setDaysAgo(0)) })
            SelectChip("Yesterday", form.daysAgo == 1, { onForm(form.setDaysAgo(1)) })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SecondaryButton("-", { onForm(form.setDaysAgo(form.daysAgo - 1)) }, Modifier.heightIn(min = 56.dp), enabled = form.daysAgo > 0)
            Text(
                dayLabel(today, form.daysAgo), style = MaterialTheme.typography.titleMedium, color = AppColors.textPrimary,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            SecondaryButton("+", { onForm(form.setDaysAgo(form.daysAgo + 1)) }, Modifier.heightIn(min = 56.dp), enabled = form.daysAgo < HyroxLogForm.MAX_DAYS_BACK)
        }

        SectionCard {
            cur.weightKg?.let { w ->
                BigNumberStepper(fmt(w), "kg", { onForm(form.adjustWeight(-1)) }, { onForm(form.adjustWeight(1)) }, label = if (st == Station.FARMERS_CARRY) "Weight per hand" else "Weight")
            }
            cur.distanceM?.let { d -> BigNumberStepper("$d", "m", { onForm(form.adjustDistance(-1)) }, { onForm(form.adjustDistance(1)) }, label = if (isRun) "Run distance" else "Total distance") }
            cur.reps?.let { r -> BigNumberStepper("$r", "reps", { onForm(form.adjustReps(-1)) }, { onForm(form.adjustReps(1)) }, label = "Total reps") }
            TimeEditor(form, onForm)
        }

        FieldLabel("EFFORT (6 EASY, 10 ALL OUT)")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
            (6..10).forEach { v -> SelectChip("$v", cur.rpe == v, { onForm(form.setRpe(v)) }, Modifier.weight(1f).heightIn(min = 56.dp)) }
        }
        if (!isRun) {
            FieldLabel("TECHNIQUE")
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                listOf(1 to "Needs work", 2 to "OK", 3 to "Smooth").forEach { (v, label) ->
                    TapChip(label, cur.technique == v, { onForm(form.setTechnique(v)) }, Modifier.weight(1f))
                }
            }
        }

        SecondaryButton("Add another station", { onForm(form.addAnother(defaultsFor)) }, Modifier.fillMaxWidth())
        BigActionButton(if (total > 1) "Save $total stations" else "Save", onSave)
        SecondaryButton("Back", onBack, Modifier.fillMaxWidth(), accent = AppColors.textMuted)
        Spacer(Modifier.height(Spacing.lg).navigationBarsPadding())
    }
}

@Composable
private fun TimeEditor(form: HyroxLogForm, onForm: (HyroxLogForm) -> Unit) {
    var typing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val sec = form.current.seconds ?: 0
    BigNumberStepper(
        HyroxText.clock(sec), "min:sec", { onForm(form.adjustSeconds(-1)) }, { onForm(form.adjustSeconds(1)) },
        label = "Time (optional, 10 s steps)",
        onValueClick = { typing = !typing; text = if (sec > 0) HyroxText.clock(sec) else "" },
    )
    if (typing) {
        OutlinedTextField(
            value = text,
            onValueChange = { t -> text = t; parseClock(t)?.let { onForm(form.setSeconds(it)) } },
            label = { Text("Type a time, like 1:12") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = text.isNotBlank() && parseClock(text) == null,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        Text("Tap the time to type it instead.", style = MaterialTheme.typography.bodySmall, color = AppColors.textMuted)
    }
}

private fun dayLabel(today: LocalDate, daysAgo: Int): String {
    val d = today.minusDays(daysAgo.toLong())
    val date = d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
    return when (daysAgo) { 0 -> "Today, $date"; 1 -> "Yesterday, $date"; else -> "$daysAgo days ago, $date" }
}
