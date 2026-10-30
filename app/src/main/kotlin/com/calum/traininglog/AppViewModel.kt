package com.calum.traininglog

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.calum.traininglog.core.Effort
import com.calum.traininglog.core.Exercise
import com.calum.traininglog.core.ExerciseLibrary
import com.calum.traininglog.core.ExerciseLog
import com.calum.traininglog.core.Export
import com.calum.traininglog.core.ExerciseGroup
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.GymBuilder
import com.calum.traininglog.core.GymPlan
import com.calum.traininglog.core.LoggedSet
import com.calum.traininglog.core.MissReason
import com.calum.traininglog.core.PlannedExercise
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.Progression
import com.calum.traininglog.core.Rewards
import com.calum.traininglog.core.Recommendation
import com.calum.traininglog.core.RecommendationInput
import com.calum.traininglog.core.Recommender
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.Stats
import com.calum.traininglog.core.TreadmillLog
import com.calum.traininglog.core.TreadmillPlan
import com.calum.traininglog.core.TreadmillPlanner
import com.calum.traininglog.core.TreadmillStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.util.UUID

sealed interface Screen {
    data object Home : Screen
    data object Start : Screen
    data object Gym : Screen
    data object TreadmillSetup : Screen
    data object TreadmillPlayer : Screen
    data object TreadmillDone : Screen
    data class Light(val type: SessionType) : Screen
    data object Manual : Screen
    data object Plan : Screen
    data object Settings : Screen
    data object Blocks : Screen
    data object Trophies : Screen
    data object Export : Screen
    data object Backup : Screen
}

data class Inputs(
    val minutes: Int = 60,
    val feeling: Feeling = Feeling.GOOD,
    val knee: Int? = null,
    val atGym: Boolean = false,
)

data class LiveSet(val kg: Double, val reps: Int, val target: Int, val done: Boolean = false, val rir: Int? = null)

data class LiveExercise(
    val planned: PlannedExercise,
    val warmups: List<LiveSet>,
    val sets: List<LiveSet>,
    val missReason: MissReason? = null,
    val askMiss: Boolean = false,
)

data class Rest(val endsAt: Long, val totalSec: Int)

/** Fired when a session is saved; the Home screen shows it once, then calls [AppViewModel.dismissCelebration]. */
data class Celebration(val record: SessionRecord, val messages: List<String>)

data class GymLive(val plan: GymPlan, val exercises: List<LiveExercise>, val rest: Rest? = null)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)

    private val _data = MutableStateFlow(store.load())
    init { SyncManager.onAppStart(app) }
    val data: StateFlow<AppData> = _data.asStateFlow()

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _celebration = MutableStateFlow<Celebration?>(null)
    val celebration: StateFlow<Celebration?> = _celebration.asStateFlow()

    private val _inputs = MutableStateFlow(Inputs())
    val inputs: StateFlow<Inputs> = _inputs.asStateFlow()

    private val _recommendation = MutableStateFlow<Recommendation?>(null)
    val recommendation: StateFlow<Recommendation?> = _recommendation.asStateFlow()

    private val _gym = MutableStateFlow<GymLive?>(null)
    val gym: StateFlow<GymLive?> = _gym.asStateFlow()

    private val _pendingTreadmill = MutableStateFlow<TreadmillPlan?>(null)
    val pendingTreadmill: StateFlow<TreadmillPlan?> = _pendingTreadmill.asStateFlow()

    private var styleSeed = 0L
    private var gymStartedAt = 0L

    fun go(s: Screen) { _screen.value = s }
    fun home() { _screen.value = Screen.Home }

    fun setInputs(f: (Inputs) -> Inputs) {
        _inputs.update(f)
        _recommendation.value = null
    }

    private val today get() = LocalDate.now()

    // ---- Recommendation ----

    fun recommend() {
        val i = _inputs.value
        val records = _data.value.records
        val week = Stats.week(records, today)
        _recommendation.value = Recommender.recommend(
            RecommendationInput(
                minutes = i.minutes, feeling = i.feeling, kneePain = i.knee,
                nearGym = true, gymDoneThisWeek = week.gym, treadmillDoneThisWeek = week.treadmill,
                daysSinceGym = Stats.daysSince(SessionType.GYM, records, today),
                lastTreadmillStyle = lastTreadmillStyle(),
                restDay = com.calum.traininglog.core.RestDays.isRestDay(_data.value.profile, today),
                settings = _data.value.profile.settings,
            ),
            seed = System.currentTimeMillis() / 60000,
        )
    }

    private fun lastTreadmillStyle(): TreadmillStyle? =
        _data.value.records.filter { it.treadmill != null }.maxByOrNull { it.date }?.treadmill?.style

    fun begin(type: SessionType?, minutes: Int = _inputs.value.minutes, style: TreadmillStyle? = null) {
        when (type) {
            SessionType.GYM -> startGym(minutes)
            SessionType.TREADMILL -> prepareTreadmill(minutes, style)
            SessionType.CORE, SessionType.HIKE -> go(Screen.Light(type))
            null -> home()
        }
    }

    // ---- Gym ----

    private fun startGym(minutes: Int) {
        val records = _data.value.records
        val lifting = if (_inputs.value.atGym) minutes else maxOf(25, GymBuilder.liftingMinutes(minutes, _data.value.profile.settings))
        val gymDates = records.filter { it.type == SessionType.GYM }.map { it.date }
        val deload = Progression.deloadDue(gymDates, _data.value.profile.lastDeload, today, settings = _data.value.profile.settings)
        val plan = GymBuilder.build(lifting, records, today, deload, settings = _data.value.profile.settings)
        gymStartedAt = SystemClock.elapsedRealtime()
        _gym.value = GymLive(plan, plan.exercises.map { live(it) })
        go(Screen.Gym)
    }

    private fun live(p: PlannedExercise) = LiveExercise(
        planned = p,
        warmups = p.warmups.map { LiveSet(it.weightKg, it.reps, it.targetReps) },
        sets = List(p.prescription.sets) { LiveSet(p.prescription.weightKg, p.prescription.reps, p.prescription.reps) },
    )

    private fun editGym(f: (GymLive) -> GymLive) = _gym.update { it?.let(f) }

    private fun editExercise(ei: Int, f: (LiveExercise) -> LiveExercise) = editGym { g ->
        g.copy(exercises = g.exercises.mapIndexed { i, e -> if (i == ei) f(e) else e })
    }

    fun adjustSet(ei: Int, si: Int, dKg: Double = 0.0, dReps: Int = 0) = editExercise(ei) { e ->
        e.copy(sets = e.sets.mapIndexed { i, s ->
            if (i == si) s.copy(kg = maxOf(0.0, s.kg + dKg), reps = maxOf(0, s.reps + dReps)) else s
        })
    }

    fun toggleWarmup(ei: Int, wi: Int) = editExercise(ei) { e ->
        e.copy(warmups = e.warmups.mapIndexed { i, s -> if (i == wi) s.copy(done = !s.done) else s })
    }

    fun doneSet(ei: Int, si: Int) {
        val g = _gym.value ?: return
        val ex = g.exercises[ei]
        val nowDone = !ex.sets[si].done
        editExercise(ei) { e ->
            val sets = e.sets.mapIndexed { i, s -> if (i == si) s.copy(done = nowDone, rir = if (nowDone) s.rir else null) else s }
            val finished = sets.all { it.done }
            e.copy(sets = sets, askMiss = finished && sets.any { it.reps < it.target } && e.missReason == null)
        }
        if (nowDone) {
            val rest = ex.planned.exercise.restSec
            editGym { it.copy(rest = Rest(SystemClock.elapsedRealtime() + rest * 1000L, rest)) }
        }
    }

    fun setRir(ei: Int, si: Int, rir: Int) = editExercise(ei) { e ->
        e.copy(sets = e.sets.mapIndexed { i, s -> if (i == si) s.copy(rir = rir) else s })
    }

    fun setMiss(ei: Int, reason: MissReason) = editExercise(ei) { it.copy(missReason = reason, askMiss = false) }

    fun clearRest() = editGym { it.copy(rest = null) }

    /** Replace exercise [ei] with another one (any exercise in the same movement group, incl. deadlift <-> RDL). */
    fun swap(ei: Int, newId: String) {
        val g = _gym.value ?: return
        if (ei !in g.exercises.indices) return
        val planned = GymBuilder.planFor(newId, _data.value.records, today, g.plan.deload, settings = _data.value.profile.settings)
        editExercise(ei) { live(planned) }
    }

    /** Skip an exercise for today. */
    fun removeExercise(ei: Int) = editGym { g ->
        if (ei !in g.exercises.indices) g else g.copy(exercises = g.exercises.filterIndexed { i, _ -> i != ei })
    }

    /** Move exercise [ei] up (delta -1) or down (delta +1). */
    fun moveExercise(ei: Int, delta: Int) = editGym { g ->
        val to = ei + delta
        if (ei !in g.exercises.indices || to !in g.exercises.indices) g
        else g.copy(exercises = g.exercises.toMutableList().also { it.add(to, it.removeAt(ei)) })
    }

    /** Append a library exercise (or one of your earlier custom ones) to today's session. */
    fun addExercise(id: String) {
        val g = _gym.value ?: return
        val planned = GymBuilder.planFor(id, _data.value.records, today, g.plan.deload, settings = _data.value.profile.settings)
        editGym { it.copy(exercises = it.exercises + live(planned)) }
    }

    /** Append a free-text exercise; [movement] is an optional group used only for swaps today. */
    fun addCustomExercise(name: String, movement: String? = null) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val g = _gym.value ?: return
        val ex = ExerciseLibrary.custom(clean, movement)
        // Reuse the logged name/weights if this id was used before.
        val planned = GymBuilder.plan(ex, _data.value.records, today, g.plan.deload, settings = _data.value.profile.settings)
        editGym { it.copy(exercises = it.exercises + live(planned)) }
    }

    fun swapOptions(ex: Exercise): List<Exercise> = ExerciseLibrary.swapsFor(ex)

    fun addOptions(): List<ExerciseGroup> = GymBuilder.addOptions(_data.value.records)

    fun finishGym() {
        val g = _gym.value ?: return
        val logs = g.exercises.mapNotNull { e ->
            val done = e.sets.filter { it.done }
            if (done.isEmpty()) null
            else ExerciseLog(
                e.planned.exercise.id, done.map { LoggedSet(it.kg, it.reps, it.target, it.rir) }, e.missReason,
                customName = if (ExerciseLibrary.isCustom(e.planned.exercise.id)) e.planned.exercise.name else null,
            )
        }
        val minutes = ((SystemClock.elapsedRealtime() - gymStartedAt) / 60000).toInt().coerceAtLeast(1)
        if (logs.isNotEmpty()) {
            val record = SessionRecord(
                id = UUID.randomUUID().toString(), date = today, type = SessionType.GYM,
                minutesAvailable = _inputs.value.minutes, minutesActual = minutes, feeling = _inputs.value.feeling,
                kneePain = _inputs.value.knee, dayLabel = g.plan.dayLabel, deload = g.plan.deload, exercises = logs,
            )
            persist { d ->
                d.copy(
                    records = d.records + record,
                    profile = if (g.plan.deload) d.profile.copy(lastDeload = today) else d.profile,
                )
            }
        }
        _gym.value = null
        home()
    }

    // ---- Treadmill ----

    private fun prepareTreadmill(minutes: Int, forced: TreadmillStyle?) {
        val mins = minutes.coerceIn(20, 120)
        val style = forced ?: TreadmillPlanner.chooseStyle(mins, lastTreadmillStyle(), exciting = mins >= 60, seed = styleSeed)
        _pendingTreadmill.value = TreadmillPlanner.build(style, mins, _data.value.profile.treadmill, _data.value.profile.settings)
        go(Screen.TreadmillSetup)
    }

    fun otherStyle() {
        val plan = _pendingTreadmill.value ?: return
        styleSeed++
        val mins = plan.totalSec / 60
        val pool = TreadmillStyle.entries.filter { it != plan.style }
        val style = TreadmillPlanner.chooseStyle(mins, plan.style, exciting = false, seed = styleSeed).takeIf { it != plan.style } ?: pool.first()
        _pendingTreadmill.value = TreadmillPlanner.build(style, mins, _data.value.profile.treadmill, _data.value.profile.settings)
    }

    fun startTreadmill() {
        val plan = _pendingTreadmill.value ?: return
        TreadmillEngine.start(plan, SystemClock.elapsedRealtime())
        val ctx = getApplication<Application>()
        ContextCompat.startForegroundService(ctx, Intent(ctx, TreadmillService::class.java))
        go(Screen.TreadmillPlayer)
    }

    fun togglePause() {
        val now = SystemClock.elapsedRealtime()
        if (TreadmillEngine.ui.value.paused) TreadmillEngine.resume(now) else TreadmillEngine.pause(now)
    }

    fun adjustSegment(deltaSec: Int) = TreadmillEngine.adjustCurrent(deltaSec, SystemClock.elapsedRealtime())

    fun endTreadmill() = go(Screen.TreadmillDone)

    fun saveTreadmill(effort: Effort?) {
        val plan = _pendingTreadmill.value
        if (plan != null) {
            val completed = TreadmillEngine.completedSec()
            if (completed >= 60) {
                val record = SessionRecord(
                    id = UUID.randomUUID().toString(), date = today, type = SessionType.TREADMILL,
                    minutesAvailable = _inputs.value.minutes, minutesActual = completed / 60,
                    feeling = _inputs.value.feeling, kneePain = _inputs.value.knee,
                    treadmill = TreadmillLog(plan.style, plan.totalSec, completed, effort),
                )
                persist { d ->
                    d.copy(
                        records = d.records + record,
                        profile = d.profile.copy(treadmill = effort?.let { d.profile.treadmill.adjusted(it) } ?: d.profile.treadmill),
                    )
                }
            }
        }
        TreadmillEngine.stop()
        _pendingTreadmill.value = null
        home()
    }

    // ---- Core / hike ----

    fun saveLight(type: SessionType, minutes: Int, note: String) {
        val record = SessionRecord(
            id = UUID.randomUUID().toString(), date = today, type = type,
            minutesAvailable = _inputs.value.minutes, minutesActual = minutes, feeling = _inputs.value.feeling,
            kneePain = _inputs.value.knee, note = note.ifBlank { null },
        )
        persist { it.copy(records = it.records + record) }
        home()
    }

    /** Add a session that already happened, without running a live activity. */
    fun saveManual(type: SessionType, daysAgo: Int, minutes: Int, style: TreadmillStyle?, note: String) {
        val date = today.minusDays(daysAgo.toLong())
        val record = SessionRecord(
            id = UUID.randomUUID().toString(), date = date, type = type,
            minutesAvailable = minutes, minutesActual = minutes, feeling = Feeling.GOOD,
            treadmill = if (type == SessionType.TREADMILL) {
                TreadmillLog(style ?: TreadmillStyle.STEADY, minutes * 60, minutes * 60, null)
            } else null,
            note = note.ifBlank { null },
        )
        persist { it.copy(records = it.records + record) }
        home()
    }

    // ---- History / export ----

    fun deleteRecord(id: String) = persist { d -> d.copy(records = d.records.filterNot { it.id == id }) }

    fun exportText(days: Int?): String {
        val to = today
        val from = if (days == null) LocalDate.of(2000, 1, 1) else to.minusDays(days.toLong() - 1)
        return Export.summary(_data.value.records, from, to)
    }

    /** Rest days, headline targets and other profile settings. */
    fun updateProfile(f: (Profile) -> Profile) = persist { it.copy(profile = f(it.profile)) }

    fun dismissCelebration() { _celebration.value = null }

    /** Replace everything, e.g. after restoring a backup. */
    fun replaceData(d: AppData) {
        _data.value = d
        store.save(d)
    }

    private fun persist(f: (AppData) -> AppData) {
        val before = _data.value
        val next = f(before)
        _data.value = next
        store.save(next)
        val added = next.records.lastOrNull { r -> before.records.none { it.id == r.id } }
        if (added != null) {
            _celebration.value = Celebration(added, Rewards.celebration(added, before.records))
            val app = getApplication<Application>()
            SyncManager.request(app)
            DriveBackup.requestBackup(app, next)
        }
    }
}
