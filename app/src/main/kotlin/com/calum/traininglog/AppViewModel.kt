package com.calum.traininglog

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.calum.traininglog.core.TreadmillStatsCalc
import kotlinx.coroutines.launch
import com.calum.traininglog.core.CalfMode
import com.calum.traininglog.core.workResults
import com.calum.traininglog.core.Effort
import com.calum.traininglog.core.KneeCare
import com.calum.traininglog.core.KneeCareLibrary
import com.calum.traininglog.core.KneeCareRecord
import com.calum.traininglog.core.KneeCareRoutine
import com.calum.traininglog.core.KneeCareSession
import com.calum.traininglog.core.isKneeCare
import com.calum.traininglog.core.Exercise
import com.calum.traininglog.core.ExerciseLibrary
import com.calum.traininglog.core.ExerciseLog
import com.calum.traininglog.core.Export
import com.calum.traininglog.core.ExerciseGroup
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.GymBuilder
import com.calum.traininglog.core.GymPlan
import com.calum.traininglog.core.Rebaseline
import com.calum.traininglog.core.GuidedPlan
import com.calum.traininglog.core.GuidedRecord
import com.calum.traininglog.core.Modality
import com.calum.traininglog.core.PlannedSession
import com.calum.traininglog.core.PlannedTreadmill
import com.calum.traininglog.core.SessionKind
import com.calum.traininglog.core.LoggedSet
import com.calum.traininglog.core.MissReason
import com.calum.traininglog.core.PlannedExercise
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.RacePlanner
import com.calum.traininglog.core.TimeSuggestion
import com.calum.traininglog.core.Progression
import com.calum.traininglog.core.Rewards
import com.calum.traininglog.core.RunMode
import com.calum.traininglog.core.RunStageLogic
import com.calum.traininglog.core.SimFormat
import com.calum.traininglog.core.SimPreset
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
    /** Guided mat / core session: setup, player and finish all live behind this one route (see [CoreController]). */
    data object Core : Screen
    /** Guided knee care (hip and calf work): setup, player and finish all live behind this one route (see [KneeCareController]). */
    data object KneeCare : Screen
    data object Manual : Screen
    data object Plan : Screen
    data object Settings : Screen
    data object Blocks : Screen
    data object Trophies : Screen
    data object Progress : Screen
    data object Export : Screen
    data object Backup : Screen
    /** Hyrox hub: stations, division, your progress. */
    data object Hyrox : Screen
    data class HyroxStation(val station: com.calum.traininglog.core.HyroxStation) : Screen
    /** Guided station session; [focus] pre-selects stations (empty = recommended mix). */
    data class HyroxSession(val focus: Set<com.calum.traininglog.core.HyroxStation> = emptySet()) : Screen
    /** Quick log of a station already done; [station] pre-selects it. */
    data class HyroxLog(val station: com.calum.traininglog.core.HyroxStation? = null) : Screen
    /** Evidence-based Hyrox race plan: countdown, weeks, sessions, balance. */
    data object RacePlan : Screen
    /** Guided timer for an easy or interval session from the race plan (rower, SkiErg, bike, ...). */
    data class GuidedTimer(val session: com.calum.traininglog.core.PlannedSession) : Screen
    /** Hyrox simulation in true race order: setup, player and results all live behind this route (see [HyroxSimController]). */
    data class HyroxSim(val preset: com.calum.traininglog.core.SimPreset? = null) : Screen
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
    /** Guided mode: "Start" was pressed on this exercise's intro card. Has no effect on what is saved. */
    val started: Boolean = false,
    /** Guided mode: the rest of this exercise was skipped (sets not done are simply not logged). */
    val closed: Boolean = false,
)

/** [ei] and [si] say which set started the rest (-1 when unknown); the guided screen puts the effort row on it. */
data class Rest(val endsAt: Long, val totalSec: Int, val ei: Int = -1, val si: Int = -1)

/** Fired when a session is saved; the Home screen shows it once, then calls [AppViewModel.dismissCelebration]. */
data class Celebration(val record: SessionRecord, val messages: List<String>)

/** [startedAt] is `SystemClock.elapsedRealtime()` at the start; [windowMinutes] is the lifting time the plan was fitted to. */
data class GymLive(
    val plan: GymPlan,
    val exercises: List<LiveExercise>,
    val rest: Rest? = null,
    val startedAt: Long = 0L,
    val windowMinutes: Int = 0,
    /** 'Station finisher: ...' lines from a race-plan strength session (header first); empty for a normal session. */
    val finisher: List<String> = emptyList(),
    /** The stations and minutes of that finisher, for the "Do the station finisher" step after finishing the lifts. */
    val finisherFocus: Set<com.calum.traininglog.core.HyroxStation> = emptySet(),
    val finisherMinutes: Int = 0,
    /** True once the lifts are saved and the finisher is offered as the next step. */
    val finisherOffered: Boolean = false,
    /** Minutes of guided hip prehab planned after the lifts (0: none); it is offered once the lifts are saved. */
    val prehabMinutes: Int = 0,
    /** True once the lifts are saved and the hip prehab is offered as the next step (Start or Skip). */
    val prehabOffered: Boolean = false,
    /** Guided mode: "Start" was pressed on the "Today's session" overview card (shown once, before the first exercise). */
    val overviewSeen: Boolean = false,
    /** Minutes the whole session was chosen for (lifting window plus prehab/finisher/travel); saved as the record's `minutesAvailable` (0: use the Start inputs). */
    val minutesAvailable: Int = 0,
    /** Minutes that sit on top of the lifting window and prehab (finisher, or gym travel), constant for the session. */
    val overheadMinutes: Int = 0,
    /** The lifting window the session started with (the plan's own when [fromPlan]), with the hip prehab included as planned. */
    val startWindow: Int = 0,
    /** Hip prehab minutes the plan asked for (kept when the "Include the hip prehab" switch is off); [prehabMinutes] is 0 then. */
    val prehabPlanned: Int = 0,
    /** True when the session came from the plan (its window is "planned"). */
    val fromPlan: Boolean = false,
    /** What the last time change did to the list ("Added: Machine chest press"), null when nothing changed. */
    val windowChange: String? = null,
)

private const val MAX_BACK_STACK = 20

class AppViewModel @JvmOverloads constructor(
    app: Application,
    /** Today's date; tests inject a fixed one. */
    private val clock: () -> LocalDate = { LocalDate.now() },
    private val store: Store = Store(app),
) : AndroidViewModel(app) {

    private val _data = MutableStateFlow(store.load())
    private val _storeNote = MutableStateFlow(store.lastLoadNote)
    /** Set when data was restored from a safety copy or could not be opened; Home shows it once. */
    val storeNote: StateFlow<StoreNote?> = _storeNote.asStateFlow()
    fun dismissStoreNote() { store.dismissNote(); _storeNote.value = store.lastLoadNote }
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

    private val _timeSuggestion = MutableStateFlow<TimeSuggestion?>(null)
    /** The race plan's answer to 'what should I do?' for the chosen time and feeling (null when the plan is off or does not apply). */
    val timeSuggestion: StateFlow<TimeSuggestion?> = _timeSuggestion.asStateFlow()

    private val _gym = MutableStateFlow<GymLive?>(null)
    val gym: StateFlow<GymLive?> = _gym.asStateFlow()

    private val _pendingTreadmill = MutableStateFlow<TreadmillPlan?>(null)
    val pendingTreadmill: StateFlow<TreadmillPlan?> = _pendingTreadmill.asStateFlow()

    private var styleSeed = 0L
    private var gymStartedAt = 0L

    private val _gymGuided = MutableStateFlow(true)
    /** True while the gym screen shows one set at a time; false for the whole list. Starts from the "Guided gym mode" setting. */
    val gymGuided: StateFlow<Boolean> = _gymGuided.asStateFlow()
    fun setGymGuided(on: Boolean) { _gymGuided.value = on }

    /** Screens left behind, oldest first. Only screens that can be re-entered are ever pushed (see [pushable]). */
    private val backStack = ArrayDeque<Screen>()
    /** The screens system back would pass through, nearest last (for tests). */
    internal val backStackSnapshot: List<Screen> get() = backStack.toList()

    /**
     * Shows [s] and remembers the screen being left so [back] can return to it. Live and transient screens (a running
     * session, its setup and results) are never remembered: they cannot be re-entered once left. Going to the screen
     * that is already on top of the stack is a step back, not a new step.
     */
    fun go(s: Screen) {
        if (s == Screen.Home) { home(); return }
        val cur = _screen.value
        if (backStack.lastOrNull() == s) backStack.removeAt(backStack.lastIndex)
        else if (cur != s && pushable(cur) && backStack.lastOrNull() != cur) {
            backStack.addLast(cur)
            while (backStack.size > MAX_BACK_STACK) backStack.removeAt(0)
        }
        _screen.value = s
    }

    fun home() { backStack.clear(); _screen.value = Screen.Home }

    /** Steps back to the previous screen. False only on Home, where the system may close the app. */
    fun back(): Boolean {
        if (_screen.value == Screen.Home) return false
        _screen.value = if (backStack.isEmpty()) Screen.Home else backStack.removeAt(backStack.lastIndex)
        return true
    }

    /** Like [back], but with nothing to go back to (the screen was opened directly) it shows [fallback] instead of Home. */
    fun backOr(fallback: Screen) {
        if (backStack.isEmpty() && _screen.value != Screen.Home) go(fallback) else back()
    }

    private fun pushable(s: Screen) = when (s) {
        Screen.Gym, Screen.TreadmillSetup, Screen.TreadmillPlayer, Screen.TreadmillDone, Screen.Core, Screen.KneeCare,
        is Screen.HyroxSession, is Screen.GuidedTimer, is Screen.HyroxSim -> false
        else -> true
    }

    fun setInputs(f: (Inputs) -> Inputs) {
        _inputs.update(f)
        _recommendation.value = null
        _timeSuggestion.value = null
    }

    private val today get() = clock()

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
        // With the race plan on, the plan's own answer (fitted to the time, feeling and knee) is shown first.
        val d = _data.value
        val cfg = d.profile.settings.racePlan
        val plan = cfg.takeIf { it.enabled }?.let { RacePlanner.build(it, d.records, today, d.profile) }
        _timeSuggestion.value = plan?.takeIf { it.segmentOf(today) != null }
            ?.let { RacePlanner.suggestForTime(it, d.records, today, i.minutes, i.feeling, i.knee, d.profile) }
    }

    private fun lastTreadmillStyle(): TreadmillStyle? =
        _data.value.records.filter { it.treadmill != null }.maxByOrNull { it.date }?.treadmill?.style

    fun begin(type: SessionType?, minutes: Int = _inputs.value.minutes, style: TreadmillStyle? = null) {
        when (type) {
            SessionType.GYM -> startGym(minutes)
            SessionType.TREADMILL -> prepareTreadmill(minutes, style)
            SessionType.CORE -> prepareCore(minutes)
            SessionType.HIKE -> { hikePrepare(); go(Screen.Light(type)) }
            SessionType.HYROX -> { hyroxPrepare(emptySet(), minutes); go(Screen.HyroxSession(emptySet())) }
            null -> home()
        }
    }

    // ---- Gym ----

    /**
     * Opens the gym builder. [minutes] is the time available including the trip to the gym (travel is taken off unless
     * [atGym]); a non-null [liftingWindow] is the lifting time itself and is used as it is (planned strength sessions: no travel).
     */
    private fun startGym(minutes: Int, liftingWindow: Int? = null) {
        val lifting = liftingWindow ?: if (_inputs.value.atGym) minutes else maxOf(25, GymBuilder.liftingMinutes(minutes, _data.value.profile.settings))
        val plan = buildGymPlan(lifting)
        gymStartedAt = SystemClock.elapsedRealtime()
        _gymGuided.value = _data.value.profile.settings.guidedGym
        val overhead = if (liftingWindow != null || _inputs.value.atGym) 0 else 2 * _data.value.profile.settings.travelMinEachWay
        _gym.value = GymLive(
            plan, plan.exercises.map { live(it) }, startedAt = gymStartedAt, windowMinutes = lifting,
            minutesAvailable = minutes, overheadMinutes = overhead, startWindow = lifting,
        )
        go(Screen.Gym)
    }

    /** The one place a gym session is built, at the start and whenever the lifting time is changed: same records, deload, easing back in and settings. */
    private fun buildGymPlan(lifting: Int): GymPlan {
        val records = _data.value.records
        val gymDates = records.filter { it.type == SessionType.GYM }.map { it.date }
        val deload = Progression.deloadDue(gymDates, _data.value.profile.lastDeload, today, settings = _data.value.profile.settings)
        return GymBuilder.build(lifting, records, today, deload, rebaseline = Rebaseline.status(records, today), settings = _data.value.profile.settings)
    }

    /**
     * "How much time do you have for lifting?": rebuilds today's session for [minutes] of lifting. Only before any set is
     * done; it changes nothing in Settings and lasts for this session only. [prehabOn] is the state of the hip prehab switch.
     */
    private fun retime(minutes: Int, prehabOn: Boolean) {
        val g = _gym.value ?: return
        if (!GymGuide.canRetime(g)) return
        val window = minutes.coerceIn(GuidedPlan.MIN_LIFTING_WINDOW, 240)
        val plan = buildGymPlan(window)
        val before = g.exercises.map { it.planned.exercise }
        val after = plan.exercises.map { it.exercise }
        val added = after.filter { a -> before.none { it.id == a.id } }
        val dropped = before.filter { b -> after.none { it.id == b.id } }
        val note = listOfNotNull(
            added.takeIf { it.isNotEmpty() }?.let { "Added: ${it.joinToString(", ") { e -> e.name }}" },
            dropped.takeIf { it.isNotEmpty() }?.let { "Left out: ${it.joinToString(", ") { e -> e.name }}" },
        ).joinToString(". ").ifEmpty { null }
        val prehab = if (prehabOn) g.prehabPlanned else 0
        _gym.value = g.copy(
            plan = plan, exercises = plan.exercises.map { live(it) }, windowMinutes = window, prehabMinutes = prehab,
            minutesAvailable = window + g.overheadMinutes + prehab, windowChange = note,
        )
    }

    /** A time chip on the gym overview or list: lifting minutes for today. */
    fun setGymWindow(minutes: Int) { _gym.value?.let { retime(minutes, it.prehabMinutes > 0) } }

    /** The "Include the hip prehab" switch: off adds the prehab minutes to the lifting window (and no prehab prompt follows), on takes them back. */
    fun setGymPrehab(on: Boolean) {
        val g = _gym.value ?: return
        if (g.prehabPlanned <= 0 || (g.prehabMinutes > 0) == on) return
        val window = if (on) (g.windowMinutes - g.prehabPlanned).coerceAtLeast(GuidedPlan.MIN_LIFTING_WINDOW) else g.windowMinutes + g.prehabPlanned
        retime(window, on)
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
            editGym { it.copy(rest = Rest(SystemClock.elapsedRealtime() + rest * 1000L, rest, ei, si)) }
        }
    }

    fun setRir(ei: Int, si: Int, rir: Int) = editExercise(ei) { e ->
        e.copy(sets = e.sets.mapIndexed { i, s -> if (i == si) s.copy(rir = rir) else s })
    }

    fun setMiss(ei: Int, reason: MissReason) = editExercise(ei) { it.copy(missReason = reason, askMiss = false) }

    fun clearRest() = editGym { it.copy(rest = null) }

    // ---- Guided gym helpers (the saved record is built from the same sets as in the list, see finishGym) ----

    /** Guided "Done, next": marks the set done through [doneSet] (rest timer included), but a second quick tap never un-does it. */
    fun completeSet(ei: Int, si: Int) {
        if (_gym.value?.exercises?.getOrNull(ei)?.sets?.getOrNull(si)?.done == false) doneSet(ei, si)
    }

    /** Guided "Done, next" on a warm-up: ticks it (a second tap does not un-tick it). */
    fun completeWarmup(ei: Int, wi: Int) {
        if (_gym.value?.exercises?.getOrNull(ei)?.warmups?.getOrNull(wi)?.done == false) toggleWarmup(ei, wi)
    }

    /** "Start" on an exercise's intro card. */
    fun startExercise(ei: Int) = editExercise(ei) { it.copy(started = true) }

    /** "Skip the rest of this exercise": sets not done stay out of the record. */
    fun closeExercise(ei: Int) = editExercise(ei) { it.copy(closed = true, askMiss = false) }

    /** Ticks every warm-up of exercise [ei] (guided "Skip warm-ups"); warm-ups are never saved. */
    fun finishWarmups(ei: Int) = editExercise(ei) { e -> e.copy(warmups = e.warmups.map { it.copy(done = true) }) }

    /** "Last time: 72.5 kg x 5, 5, 5" for an exercise, or null when it was never logged. */
    fun lastTimeLine(exerciseId: String): String? {
        val last = GymBuilder.history(exerciseId, _data.value.records).firstOrNull()?.log?.sets?.takeIf { it.isNotEmpty() } ?: return null
        return GymGuide.lastTime(last)
    }

    /** "Start" on the guided "Today's session" overview card. */
    fun startGuidedSession() = editGym { it.copy(overviewSeen = true) }

    /**
     * Swap exercise [ei] for another one in the same movement group (incl. deadlift <-> RDL, standing <-> seated calf).
     * Before any set is done it simply replaces the exercise. Once sets are done they stay logged under the original
     * exercise (which is closed), and the new exercise takes the REMAINING sets with its own prescription (its weight
     * comes from its own history, or its start weight the first time) right after it.
     */
    fun swap(ei: Int, newId: String) {
        val g = _gym.value ?: return
        if (ei !in g.exercises.indices) return
        val cur = g.exercises[ei]
        if (cur.planned.exercise.id == newId) return
        val planned = GymBuilder.planFor(newId, _data.value.records, today, g.plan.deload, rebaseline = g.plan.rebaseline, settings = _data.value.profile.settings)
        val doneCount = cur.sets.count { it.done }
        if (doneCount == 0) {
            editExercise(ei) { live(planned).copy(started = it.started) }
            return
        }
        val remaining = cur.sets.size - doneCount
        if (remaining <= 0) return // everything is already done: nothing left to swap
        val rx = planned.prescription
        val next = live(planned).copy(
            warmups = emptyList(), started = true,
            sets = List(remaining) { LiveSet(rx.weightKg, rx.reps, rx.reps) },
        )
        editGym { gl ->
            val kept = cur.copy(sets = cur.sets.filter { it.done }, closed = true, askMiss = false)
            gl.copy(
                exercises = gl.exercises.toMutableList().also { it[ei] = kept; it.add(ei + 1, next) },
                rest = gl.rest?.let { r -> if (r.ei > ei) r.copy(ei = r.ei + 1) else r },
            )
        }
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
        val planned = GymBuilder.planFor(id, _data.value.records, today, g.plan.deload, rebaseline = g.plan.rebaseline, settings = _data.value.profile.settings)
        editGym { it.copy(exercises = it.exercises + live(planned)) }
    }

    /** Append a free-text exercise; [movement] is an optional group used only for swaps today. */
    fun addCustomExercise(name: String, movement: String? = null) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val g = _gym.value ?: return
        val ex = ExerciseLibrary.custom(clean, movement ?: GymBuilder.customMovementFor(ExerciseLibrary.customId(clean), _data.value.records))
        // Reuse the logged name/weights if this id was used before.
        val planned = GymBuilder.plan(ex, _data.value.records, today, g.plan.deload, rebaseline = g.plan.rebaseline, settings = _data.value.profile.settings)
        editGym { it.copy(exercises = it.exercises + live(planned)) }
    }

    fun swapOptions(ex: Exercise): List<Exercise> = GymBuilder.swapsFor(ex, _data.value.records)

    fun addOptions(): List<ExerciseGroup> = GymBuilder.addOptions(_data.value.records)

    fun finishGym() {
        val g = _gym.value ?: return
        if (g.finisherOffered || g.prehabOffered) return // already saved: a second tap must not save again
        val logs = g.exercises.mapNotNull { e ->
            val done = e.sets.filter { it.done }
            if (done.isEmpty()) null
            else ExerciseLog(
                e.planned.exercise.id, done.map { LoggedSet(it.kg, it.reps, it.target, it.rir) }, e.missReason,
                customName = if (ExerciseLibrary.isCustom(e.planned.exercise.id)) e.planned.exercise.name else null,
                customMovement = e.planned.exercise.movement.takeIf { ExerciseLibrary.isCustom(e.planned.exercise.id) && it != ExerciseLibrary.OTHER_MOVEMENT },
            )
        }.let(::mergeSameExercise)
        val minutes = ((SystemClock.elapsedRealtime() - gymStartedAt) / 60000).toInt().coerceAtLeast(1)
        if (logs.isNotEmpty()) {
            val record = SessionRecord(
                id = UUID.randomUUID().toString(), date = today, type = SessionType.GYM,
                minutesAvailable = g.minutesAvailable.takeIf { it > 0 } ?: _inputs.value.minutes, minutesActual = minutes, feeling = _inputs.value.feeling,
                kneePain = _inputs.value.knee, dayLabel = g.plan.dayLabel, deload = g.plan.deload, exercises = logs,
            )
            persist { d ->
                d.copy(
                    records = d.records + record,
                    profile = if (g.plan.deload) d.profile.copy(lastDeload = today) else d.profile,
                )
            }
            val took = "Took $minutes min (the plan was about ${g.plan.estimatedMinutes})."
            _celebration.update { it?.copy(messages = listOf(took) + it.messages) }
            // The lifts are saved. A planned hip prehab is offered first, then a station finisher (the gym screen shows each).
            if (g.prehabMinutes > 0) {
                _gym.update { it?.copy(prehabOffered = true) }
                return
            }
            if (g.finisherFocus.isNotEmpty() && g.finisher.isNotEmpty()) {
                _gym.update { it?.copy(finisherOffered = true) }
                return
            }
        }
        _gym.value = null
        home()
    }

    /**
     * One log per exercise: swapping back and forth can leave several parts of the same exercise in a session. Their sets
     * are joined in order and the log sits at the place of its LAST part, so the variant used last stays last.
     */
    private fun mergeSameExercise(logs: List<ExerciseLog>): List<ExerciseLog> {
        if (logs.map { it.exerciseId }.distinct().size == logs.size) return logs
        return logs.indices.filter { i -> logs.indexOfLast { it.exerciseId == logs[i].exerciseId } == i }.map { i ->
            val all = logs.filter { it.exerciseId == logs[i].exerciseId }
            logs[i].copy(sets = all.flatMap { it.sets }, missReason = all.firstNotNullOfOrNull { it.missReason })
        }
    }

    /** "Do the station finisher": the guided Hyrox session for those stations with the finisher's minutes. */
    fun startFinisher() {
        val g = _gym.value ?: return
        val focus = g.finisherFocus
        _gym.value = null
        hyrox.prepare(hyroxInput(), g.finisherMinutes.coerceAtLeast(20), focus)
        go(Screen.HyroxSession(focus))
    }

    /** Leaves the gym screen without saving (nothing logged, or "Discard"): the session is dropped, nothing is deleted from the history. */
    fun discardGym() {
        _gym.value = null
        home()
    }

    /** The gym state to show as the finisher prompt once the hip prehab is done or skipped (null: nothing planned after it). */
    private var afterPrehab: GymLive? = null

    private fun hasFinisher(g: GymLive) = g.finisherFocus.isNotEmpty() && g.finisher.isNotEmpty()

    /** "Start" on the hip prehab prompt: the Knee care routine in its hip-only 10 minute form. The lifts are already saved. */
    fun startPrehab() {
        val g = _gym.value ?: return
        afterPrehab = g.takeIf(::hasFinisher)?.copy(prehabOffered = false, finisherOffered = true)
        _gym.value = null
        launchKneeCare(hipOnly = true)
    }

    /** "Skip" on the hip prehab prompt: nothing is recorded or penalised; the finisher prompt follows when one is planned. */
    fun skipPrehab() {
        val g = _gym.value ?: return
        if (hasFinisher(g)) _gym.update { it?.copy(prehabOffered = false, finisherOffered = true) }
        else { _gym.value = null; home() }
    }

    /** Leaves the hip-only knee care routine (saved, discarded or backed out of): on to the finisher prompt when one is planned, else home. */
    private fun afterKneeCare() {
        val g = afterPrehab
        afterPrehab = null
        if (g != null) { _gym.value = g; go(Screen.Gym) } else home()
    }

    /** "Skip finisher": the lifts are already saved, so just go home. */
    fun skipFinisher() {
        _gym.value = null
        home()
    }

    // ---- Treadmill ----

    private fun prepareTreadmill(minutes: Int, forced: TreadmillStyle?) {
        val mins = minutes.coerceIn(20, 120)
        val style = forced ?: TreadmillPlanner.chooseStyle(mins, lastTreadmillStyle(), exciting = mins >= 60, seed = styleSeed)
        _pendingTreadmill.value = TreadmillPlanner.build(style, mins, _data.value.profile.treadmill, _data.value.profile.settings)
        treadmillDayLabel = null
        go(Screen.TreadmillSetup)
    }

    /** `dayLabel` for the treadmill record about to run: "Run-walk" / "Run" so the knee-gated run stage counts it. */
    private var treadmillDayLabel: String? = null

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
        autoSavedTreadmillId = null
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

    /** Id of the record saved automatically when the current treadmill session finished (null until then). */
    private var autoSavedTreadmillId: String? = null

    private fun treadmillRecord(plan: TreadmillPlan, completedSec: Int, effort: Effort?, id: String = UUID.randomUUID().toString()): SessionRecord {
        val stats = TreadmillStatsCalc.at(TreadmillEngine.segments(), completedSec, _data.value.profile.settings.bodyWeightKg)
        return SessionRecord(
            id = id, date = today, type = SessionType.TREADMILL,
            minutesAvailable = _inputs.value.minutes, minutesActual = completedSec / 60,
            feeling = _inputs.value.feeling, kneePain = _inputs.value.knee, dayLabel = treadmillDayLabel,
            treadmill = TreadmillLog(plan.style, plan.totalSec, completedSec, effort, stats.distanceM, stats.climbM, stats.kcal),
        )
    }

    private fun autosaveFinishedTreadmill(ui: TreadmillEngine.Ui) {
        val plan = _pendingTreadmill.value ?: return
        if (!ui.active || !ui.finished || autoSavedTreadmillId != null) return
        // Best effort: if this ever fails the normal save on the done screen still works.
        runCatching {
            val record = treadmillRecord(plan, TreadmillEngine.completedSec(), effort = null)
            persist { it.copy(records = it.records + record) }
            autoSavedTreadmillId = record.id
        }
    }

    fun saveTreadmill(effort: Effort?) {
        val plan = _pendingTreadmill.value
        val auto = autoSavedTreadmillId
        if (plan != null) {
            if (auto != null) {
                // Already saved when it finished: just attach the effort and tune the calibration.
                if (effort != null) {
                    persist { d ->
                        d.copy(
                            records = d.records.map { r -> if (r.id == auto) r.copy(treadmill = r.treadmill?.copy(effort = effort)) else r },
                            profile = d.profile.copy(treadmill = d.profile.treadmill.adjusted(effort)),
                        )
                    }
                }
            } else {
                val completed = TreadmillEngine.completedSec()
                if (completed >= 60) {
                    val record = treadmillRecord(plan, completed, effort)
                    persist { d ->
                        d.copy(
                            records = d.records + record,
                            profile = d.profile.copy(treadmill = effort?.let { d.profile.treadmill.adjusted(it) } ?: d.profile.treadmill),
                        )
                    }
                }
            }
        }
        autoSavedTreadmillId = null
        TreadmillEngine.stop()
        _pendingTreadmill.value = null
        home()
    }

    // ---- Core (guided mat session) ----

    val core = CoreController()

    private fun lastCoreIds(): List<String> =
        _data.value.records.filter { it.type == SessionType.CORE && !it.isKneeCare }.maxByOrNull { it.date }?.let { com.calum.traininglog.core.CoreLibrary.idsFromNote(it.note) }.orEmpty()

    /** Knee-sensitive by default when today's knee score, or else the last logged one, is 4 or more. */
    private fun kneeDefault(): Boolean =
        ((_inputs.value.knee ?: _data.value.records.filter { it.kneePain != null }.maxByOrNull { it.date }?.kneePain) ?: 0) >= 4

    private fun prepareCore(minutes: Int) {
        core.prepare(minutes, lastCoreIds(), kneeDefault(), _data.value.profile.coreProgress)
        go(Screen.Core)
    }

    fun coreSetup(minutes: Int? = null, kneeSensitive: Boolean? = null, reshuffle: Boolean = false) =
        core.rebuild(lastCoreIds(), minutes, kneeSensitive, reshuffle, _data.value.profile.coreProgress)

    /** Progress map as it was before this session's autosave, so the finish card can recompute it with the chosen effort (applied once). */
    private var coreProgressBefore: Map<String, com.calum.traininglog.core.CoreProgressEntry>? = null

    private fun coreNote(run: com.calum.traininglog.core.CoreRun, effort: Effort?): String {
        val names = run.doneWorkNames()
        val felt = when (effort) { Effort.TOO_EASY -> "too easy"; Effort.JUST_RIGHT -> "just right"; Effort.TOO_HARD -> "too hard"; null -> null }
        return if (names.isEmpty()) "Core: warm-up and stretching" else com.calum.traininglog.core.CoreRoutine.note(names, felt)
    }

    /**
     * Saves the finished (or ended) core session the moment it ends, so nothing is lost if the screen freezes or the app is
     * closed. The finish card then only edits that record. Under a minute logs nothing.
     */
    private fun autosaveCore(u: CoreUi) {
        if (u.phase != CorePhase.DONE || u.savedId != null) return
        val run = u.run ?: return
        val minutes = run.completedSec / 60
        if (minutes < 1) return
        runCatching {
            val record = SessionRecord(
                id = UUID.randomUUID().toString(), date = today, type = SessionType.CORE,
                minutesAvailable = _inputs.value.minutes, minutesActual = minutes, feeling = _inputs.value.feeling,
                kneePain = _inputs.value.knee, note = coreNote(run, null),
            )
            val results = run.workResults()
            val before = _data.value.profile.coreProgress
            coreProgressBefore = before
            core.markSaved(record.id)
            persist { d ->
                d.copy(
                    records = d.records + record,
                    profile = if (results.isEmpty()) d.profile else d.profile.copy(
                        coreProgress = com.calum.traininglog.core.CoreProgression.update(before, results, null, _inputs.value.feeling, today),
                    ),
                )
            }
        }
    }

    /** Finish card: puts the effort on the already saved record and recomputes the progression from the same starting point (no duplicate). */
    fun saveCore(effort: Effort?) {
        autosaveCore(core.ui.value)
        val u = core.ui.value
        val run = u.run
        val id = u.savedId
        if (id != null && run != null) {
            val before = coreProgressBefore ?: _data.value.profile.coreProgress
            val results = run.workResults()
            val note = coreNote(run, effort)
            persist { d ->
                d.copy(
                    records = d.records.map { r -> if (r.id == id) r.copy(note = note) else r },
                    profile = if (results.isEmpty()) d.profile else d.profile.copy(
                        coreProgress = com.calum.traininglog.core.CoreProgression.update(before, results, effort, _inputs.value.feeling, today),
                    ),
                )
            }
        }
        coreProgressBefore = null
        core.reset()
        home()
    }

    // ---- Knee care (guided hip and calf routine, an optional add-on to the plan) ----

    val kneeCare = KneeCareController()

    private fun lastKneeCareIds(): List<String> =
        _data.value.records.filter { it.isKneeCare }.sortedBy { it.date }.lastOrNull()?.let { KneeCareLibrary.idsFromNote(it.note) }.orEmpty()

    /** The last knee score logged (pre-fills the setup chips). */
    private fun lastKneeScore(): Int? = _data.value.records.filter { it.kneePain != null }.maxByOrNull { it.date }?.kneePain

    private fun buildKneeCare(u: KneeCareUi): KneeCareSession {
        val d = _data.value
        val settings = d.profile.settings
        val plan = KneeCare.calfPlan(d.records, settings, u.mode, u.levelAdjust)
        val last = lastKneeCareIds().ifEmpty { null }
        val lastTime = KneeCare.lastTime(d.records, u.mode)
        if (u.hipOnly) return KneeCareRoutine.build(
            minutes = KneeCareRoutine.HIP_ONLY_MINUTES, last = last, kneePain = u.knee, seed = u.seed, kit = settings.kneeKit, hipOnly = true,
        )
        return if (u.calfOnly) KneeCareRoutine.buildCalfOnly(last, plan.kg, u.mode, plan.reps, lastTime, settings.kneeKit, plan.level, plan.movedUp)
        else KneeCareRoutine.build(
            u.minutes, last, u.knee, plan.kg, u.seed, u.mode, plan.reps, lastTime,
            settings.kneeKit, plan.level, KneeCare.controlLevels(d.records), plan.movedUp,
        )
    }

    /** Saves the home kit (tick boxes and kettlebell weight on the setup card) and rebuilds the routine from it. */
    fun setKneeKit(kit: com.calum.traininglog.core.KneeCareKit) {
        updateProfile { it.copy(settings = it.settings.copy(kneeKit = kit.sanitized())) }
        kneeCare.setSession(buildKneeCare(kneeCare.ui.value))
    }

    /** Opens the knee care setup: the full routine, or [calfOnly] for just the calf raises. A running session is resumed. */
    fun launchKneeCare(calfOnly: Boolean = false, hipOnly: Boolean = false) {
        if (kneeCare.ui.value.phase.let { it == KneeCarePhase.RUNNING || it == KneeCarePhase.GET_READY }) { go(Screen.KneeCare); return }
        val mode = KneeCare.lastMode(_data.value.records)
        val knee = _inputs.value.knee ?: lastKneeScore()
        val minutes = if (hipOnly) KneeCareRoutine.HIP_ONLY_MINUTES else KneeCareRoutine.DEFAULT_MINUTES
        val u = KneeCareUi(minutes = minutes, calfOnly = calfOnly && !hipOnly, mode = mode, knee = knee, hipOnly = hipOnly)
        kneeCare.prepare(u.minutes, mode, knee, u.calfOnly, buildKneeCare(u), hipOnly)
        go(Screen.KneeCare)
    }

    fun kneeCareSetup(
        mode: CalfMode? = null, knee: Int? = null, clearKnee: Boolean = false, calfOnly: Boolean? = null, reshuffle: Boolean = false, levelStep: Int = 0,
    ) {
        kneeCare.configure(mode = mode, knee = knee, clearKnee = clearKnee, calfOnly = calfOnly, reshuffle = reshuffle, levelStep = levelStep)
        kneeCare.setSession(buildKneeCare(kneeCare.ui.value))
    }

    /** The record of the knee care session as it stands (null: under a minute, nothing to log). */
    private fun kneeCareRecord(u: KneeCareUi, id: String, date: LocalDate): SessionRecord? {
        val run = u.run ?: return null
        val s = u.session ?: return null
        return KneeCareRecord.build(
            id, date, s.minutes, run.completedSec, kneeCare.doneNames(u), kneeCare.doneCalfSets(u),
            feeling = u.feeling, kneePain = u.kneeAfter,
        )
    }

    /**
     * Saves the finished (or ended) knee care session the moment it ends, so nothing is lost if the screen freezes. The
     * finish card then only edits that record. Under a minute logs nothing.
     */
    private fun autosaveKneeCare(u: KneeCareUi) {
        if (u.phase != KneeCarePhase.DONE || u.savedId != null) return
        runCatching {
            val record = kneeCareRecord(u, UUID.randomUUID().toString(), today) ?: return
            kneeCare.markSaved(record.id)
            persist { it.copy(records = it.records + record) }
        }
    }

    /** Finish card "Save": puts the feeling and knee score on the already saved record (no duplicate), then goes home. */
    fun saveKneeCare() {
        autosaveKneeCare(kneeCare.ui.value)
        val u = kneeCare.ui.value
        val id = u.savedId
        if (id != null) {
            val old = _data.value.records.firstOrNull { it.id == id }
            val edited = old?.let { kneeCareRecord(u, id, it.date) }
            if (edited != null) persist { d -> d.copy(records = d.records.map { r -> if (r.id == id) edited else r }) }
        }
        kneeCare.reset()
        afterKneeCare()
    }

    /** Leaves the knee care setup without starting. */
    fun kneeCareBack() {
        if (kneeCare.ui.value.phase.let { it != KneeCarePhase.RUNNING && it != KneeCarePhase.GET_READY }) kneeCare.reset()
        if (afterPrehab != null) afterKneeCare() else backOr(Screen.Home)
    }

    /** Drops a running knee care session without saving. */
    fun kneeCareDiscard() {
        kneeCare.reset()
        afterKneeCare()
    }

    // ---- Hyrox (guided station session and quick log) ----

    val hyrox = HyroxController()

    private fun hyroxInput(): HyroxPlanInput {
        val settings = _data.value.profile.settings
        return HyroxPlanInput(_data.value.records, settings.hyroxDivision, settings)
    }

    /** Opens the Hyrox setup (a session already running or waiting to be saved is left alone). */
    fun hyroxPrepare(focus: Set<com.calum.traininglog.core.HyroxStation>, minutes: Int = 45) = hyrox.prepare(hyroxInput(), minutes, focus)

    fun hyroxSetup(
        minutes: Int? = null, focus: Set<com.calum.traininglog.core.HyroxStation>? = null, withRuns: Boolean? = null, shuffle: Boolean = false,
        runMetres: Int? = null,
    ) = hyrox.rebuild(hyroxInput(), minutes, focus, withRuns, shuffle, runMetres)

    /** Gym or Home & garden: remembered (it is the last place used) and the plan is rebuilt for its kit. */
    fun hyroxSetPlace(place: com.calum.traininglog.core.HyroxPlace) {
        updateProfile { it.copy(settings = it.settings.copy(hyroxPlace = place)) }
        hyroxSetup()
    }

    /** Today's date as the app sees it (tests inject a fixed one). */
    fun todayDate(): LocalDate = today

    /** Saves the finished (or ended early) guided Hyrox session as one record, then goes home. No stations done logs nothing. */
    fun saveHyroxSession(feeling: Feeling, kneePain: Int?) {
        val u = hyrox.ui.value
        val stations = u.results
        val stationCount = u.stationCount
        val minutes = ((hyrox.elapsedMs(u) + 30_000) / 60_000).toInt().coerceAtLeast(1)
        hyrox.reset()
        // Runs only follow a station, so a session with no station has nothing worth saving.
        if (stationCount == 0) { home(); return }
        val record = SessionRecord(
            id = UUID.randomUUID().toString(), date = today, type = SessionType.HYROX,
            minutesAvailable = u.minutes, minutesActual = minutes, feeling = feeling, kneePain = kneePain,
            stations = stations,
        )
        persist { it.copy(records = it.records + record) }
        _celebration.update { it?.copy(messages = listOf("$stationCount ${if (stationCount == 1) "station" else "stations"} in $minutes min.") + it.messages) }
        home()
    }

    /** Quick log of stations already done, [daysAgo] days back, as ONE record. */
    fun saveHyroxLog(daysAgo: Int, stations: List<com.calum.traininglog.core.StationLog>, feeling: Feeling = Feeling.GOOD) {
        if (stations.isEmpty()) { home(); return }
        val minutes = ((stations.sumOf { it.seconds ?: 0 } + 59) / 60).coerceAtLeast(1)
        val record = SessionRecord(
            id = UUID.randomUUID().toString(), date = today.minusDays(daysAgo.toLong()), type = SessionType.HYROX,
            minutesAvailable = minutes, minutesActual = minutes, feeling = feeling, stations = stations,
        )
        persist { it.copy(records = it.records + record) }
        home()
    }

    // ---- Hyrox simulation (true race order) ----

    val sim = HyroxSimController()

    private fun simInput(): SimInput {
        val settings = _data.value.profile.settings
        return SimInput(
            settings.hyroxDivision, settings, RunStageLogic.stage(_data.value.records, today),
            treadmillAtPlace = com.calum.traininglog.core.HyroxGear.TREADMILL in settings.hyroxGear,
        )
    }

    /** Opens the simulation for [preset] (null: a full one at race loads). A running simulation is resumed, not replaced. */
    fun simOpen(preset: SimPreset? = null) {
        if (sim.ui.value.phase == SimPhase.RUNNING) { go(Screen.HyroxSim(preset)); return }
        sim.reset()
        sim.prepare(simInput(), preset)
        go(Screen.HyroxSim(preset))
    }

    /** Makes sure the setup has legs (the route can be opened from a screen that did not call [simOpen]). */
    fun simEnsure(preset: SimPreset?) {
        val u = sim.ui.value
        if (u.phase == SimPhase.SETUP && u.plan == null) sim.prepare(simInput(), preset)
    }

    fun simSetup(
        format: SimFormat? = null, loadFactor: Double? = null, runMode: RunMode? = null, targetSec: Int? = null, clearTarget: Boolean = false,
    ) = sim.rebuild(simInput(), format, loadFactor, runMode, targetSec, clearTarget)

    /** The time allowed for each transition (10 to 180 s in steps of 5): remembered in the settings, then the legs are rebuilt. */
    fun simSetTransition(sec: Int) {
        updateProfile { it.copy(settings = it.settings.copy(hyroxTransitionSec = com.calum.traininglog.core.HyroxSim.snapTransition(sec))) }
        sim.rebuild(simInput())
    }

    fun simStepTarget(deltaMin: Int) = sim.stepTarget(simInput(), deltaMin)

    /** Looks at the results of a past simulation. */
    fun simView(recordId: String) { sim.view(recordId); go(Screen.HyroxSim(null)) }

    /**
     * Saves the simulation the moment it ends (all legs done, or ended early), so nothing is lost if the screen
     * freezes. The results card then only edits that record. A simulation with no leg done logs nothing.
     */
    private fun autosaveSim(u: HyroxSimUi) {
        if (u.phase != SimPhase.DONE || u.viewing || u.savedId != null) return
        runCatching {
            val record = HyroxSimController.record(u, UUID.randomUUID().toString(), today, sim.elapsedSec(u)) ?: return
            sim.markSaved(record.id)
            persist { it.copy(records = it.records + record) }
        }
    }

    /** Results card "Save": attaches how it felt (and the effort of the last leg) to the saved record, then goes home. */
    fun saveSim(feeling: Feeling, kneePain: Int?, lastRpe: Int? = null) {
        autosaveSim(sim.ui.value)
        val id = sim.ui.value.savedId
        if (id != null) {
            persist { d ->
                d.copy(records = d.records.map { r ->
                    if (r.id != id) r else r.copy(
                        feeling = feeling, kneePain = kneePain,
                        stations = if (lastRpe != null && r.stations.isNotEmpty()) r.stations.dropLast(1) + r.stations.last().copy(rpe = lastRpe) else r.stations,
                    )
                })
            }
        }
        sim.reset()
        home()
    }

    /** Leaves the simulation results (the record is already saved). */
    fun simClose(toHub: Boolean = false) {
        if (sim.ui.value.phase != SimPhase.RUNNING) sim.reset()
        if (toHub) backOr(Screen.Hyrox) else home()
    }

    /** Back on the results card: the record is already saved, so leave as the Save button does (without editing it). */
    fun simFinish() {
        autosaveSim(sim.ui.value)
        sim.reset()
        home()
    }

    /** Drops a running simulation without saving. */
    fun simDiscard() {
        sim.reset()
        home()
    }

    /** Drops a running guided Hyrox session without saving. */
    fun hyroxDiscard() {
        hyrox.reset()
        home()
    }

    /** Drops a running guided mat / core session without saving. */
    fun coreDiscard() {
        core.reset()
        home()
    }

    /** Drops a running guided timer without saving. */
    fun guidedDiscard() {
        guided.reset()
        home()
    }

    // ---- Hike (guided stopwatch, and the quick log for one already done) ----

    val hike = HikeController()

    /** Opens the hike setup with the next-length offer from the last hike (a running one is resumed, not replaced). */
    fun hikePrepare() {
        val d = _data.value
        hike.prepare(com.calum.traininglog.core.Hike.suggest(d.records, today), _inputs.value.feeling)
    }

    private fun hikeRecord(u: HikeUi, id: String, date: LocalDate): SessionRecord? {
        val minutes = (hike.elapsedMs(u) / 60_000).toInt()
        if (minutes < 1) return null
        return SessionRecord(
            id = id, date = date, type = SessionType.HIKE,
            minutesAvailable = u.targetMin ?: minutes, minutesActual = minutes, feeling = u.feeling,
            kneePain = _inputs.value.knee, note = com.calum.traininglog.core.Hike.note(u.distanceKm, u.climbM, u.note).ifBlank { null },
        )
    }

    /** Saves the hike the moment Finish is pressed, so nothing is lost if the screen freezes. The finish card only edits it. */
    private fun autosaveHike(u: HikeUi) {
        if (u.phase != HikePhase.DONE || u.savedId != null) return
        runCatching {
            val record = hikeRecord(u, UUID.randomUUID().toString(), today) ?: return
            hike.markSaved(record.id)
            persist { it.copy(records = it.records + record) }
        }
    }

    /** Finish card "Done": puts distance, climb, feeling and note on the already saved record (no duplicate), then goes home. */
    fun saveHike() {
        autosaveHike(hike.ui.value)
        val u = hike.ui.value
        val id = u.savedId
        if (id != null) {
            val old = _data.value.records.firstOrNull { it.id == id }
            val edited = old?.let { hikeRecord(u, id, it.date) }
            if (edited != null) persist { d -> d.copy(records = d.records.map { r -> if (r.id == id) edited else r }) }
        }
        hike.reset()
        home()
    }

    /** Drops a running hike without saving. */
    fun hikeDiscard() {
        hike.reset()
        home()
    }

    /** Leaves the hike setup without starting. */
    fun hikeBack() {
        if (hike.ui.value.phase == HikePhase.SETUP) hike.reset()
        backOr(Screen.Start)
    }

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

    // ---- Race plan: start and track a planned session ----

    val guided = GuidedTimerController()

    /**
     * Open the right flow for a planned race-plan session. [modality] is the one the user picked (the session's
     * first option when null): strength opens the gym builder (with the station finisher shown), Hyrox stations the
     * guided Hyrox session, the treadmill modalities the treadmill setup with a plan built for the planned minutes,
     * and rower / SkiErg / bike the guided timer. A rest day goes home.
     */
    fun launchPlanned(session: PlannedSession, modality: Modality? = null) {
        if (session.launch.kneeCare) { launchKneeCare(); return }
        val mod = modality ?: GuidedPlan.defaultModality(session)
        when {
            session.kind == SessionKind.REST || mod == Modality.REST -> home()
            session.kind == SessionKind.STRENGTH || mod == Modality.GYM -> {
                setInputs { it.copy(minutes = session.minutes.coerceAtLeast(20)) }
                // A planned session's minutes are the whole session: no gym travel comes off, only the prehab and finisher.
                val window = GuidedPlan.liftingWindow(session)
                startGym(session.minutes.coerceAtLeast(20), liftingWindow = window)
                val finisher = GuidedPlan.finisherLines(session)
                val finisherMin = if (finisher.isNotEmpty()) GuidedPlan.finisherMinutes(session) ?: 10 else 0
                _gym.update {
                    it?.copy(
                        prehabMinutes = session.launch.prehabMinutes, prehabPlanned = session.launch.prehabMinutes, fromPlan = true,
                        overheadMinutes = finisherMin, minutesAvailable = window + session.launch.prehabMinutes + finisherMin,
                    )
                }
                if (finisher.isNotEmpty()) {
                    _gym.update {
                        it?.copy(
                            finisher = finisher, finisherFocus = session.launch.hyroxFocus,
                            finisherMinutes = GuidedPlan.finisherMinutes(session) ?: 10,
                        )
                    }
                }
            }
            mod == Modality.HYROX_STATIONS && (session.kind == SessionKind.SIM || session.launch.sim != null) -> {
                simOpen(session.launch.sim)
            }
            mod == Modality.HYROX_STATIONS -> {
                val focus = session.launch.hyroxFocus
                hyrox.prepare(hyroxInput(), session.minutes, focus, withRuns = session.includesRunning)
                go(Screen.HyroxSession(focus))
            }
            mod == Modality.TREADMILL_WALK || mod == Modality.TREADMILL_RUN || mod == Modality.RUN_WALK -> {
                val p = _data.value.profile
                val plan = PlannedTreadmill.build(session, mod, p.treadmill, p.settings)
                _pendingTreadmill.value = plan.copy(title = plan.title ?: session.title)
                treadmillDayLabel = when (mod) { Modality.RUN_WALK -> "Run-walk"; Modality.TREADMILL_RUN -> "Run"; else -> null }
                go(Screen.TreadmillSetup)
            }
            else -> {
                guided.prepare(session, mod)
                go(Screen.GuidedTimer(session))
            }
        }
    }

    /**
     * Saves the finished guided session the moment it ends (and when it is ended early), so nothing is lost if the
     * screen freezes or the app closes. The rating card then only edits that record. Under a minute logs nothing.
     */
    private fun autosaveGuided(ui: GuidedUi) {
        if (ui.phase != GuidedPhase.DONE || ui.savedId != null) return
        val run = ui.run ?: return
        val s = ui.session ?: return
        runCatching {
            val record = GuidedRecord.build(
                UUID.randomUUID().toString(), today, s.minutes, ui.modality, run.completedSec, run.workSec, ui.distanceM, ui.rpe, ui.feeling,
            ) ?: return
            guided.markSaved(record.id)
            persist { it.copy(records = it.records + record) }
        }
    }

    /** Rating card "Save": attaches distance, effort and feeling to the already saved record, then returns to the plan. */
    fun saveGuided() {
        autosaveGuided(guided.ui.value)
        val ui = guided.ui.value
        val id = ui.savedId
        val run = ui.run
        val s = ui.session
        if (id != null && run != null && s != null) {
            val old = _data.value.records.firstOrNull { it.id == id }
            val edited = old?.let {
                GuidedRecord.build(id, it.date, s.minutes, ui.modality, run.completedSec, run.workSec, ui.distanceM, ui.rpe, ui.feeling)
            }
            if (edited != null) persist { d -> d.copy(records = d.records.map { r -> if (r.id == id) edited else r }) }
        }
        guided.reset()
        home()
    }

    /** Leaves the guided timer setup without starting. */
    fun guidedBack() {
        if (guided.ui.value.phase != GuidedPhase.RUNNING) guided.reset()
        backOr(Screen.RacePlan)
    }

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
            _celebration.value = Celebration(added, Rewards.celebration(added, before.records, before.profile.settings))
            // Never push a partial picture to the cloud while the real data is locked away.
            if (store.blocked) return
            val app = getApplication<Application>()
            SyncManager.request(app)
            DriveBackup.requestBackup(app, next)
        }
    }

    // Keep this LAST in the class: it runs during construction and reads properties declared above.
    init {
        // A finished treadmill session is saved the moment it finishes, before any screen is involved,
        // so nothing the user did can be lost to a stuck or closed screen. The effort question just edits it.
        viewModelScope.launch { TreadmillEngine.ui.collect { autosaveFinishedTreadmill(it) } }
        // Same for the guided timer: saved when its last step ends, the rating card just edits it.
        viewModelScope.launch { guided.ui.collect { autosaveGuided(it) } }
        // And the simulation: saved when its last leg ends (or it is ended early).
        viewModelScope.launch { sim.ui.collect { autosaveSim(it) } }
        // And knee care: saved when its last step ends (or it is ended early), the finish card just edits it.
        viewModelScope.launch { kneeCare.ui.collect { autosaveKneeCare(it) } }
        // And the mat routine: saved when its last step ends (or it is ended early); the finish card just edits it.
        viewModelScope.launch { core.ui.collect { autosaveCore(it) } }
        // And the hike: saved when Finish is pressed, the finish card just edits it.
        viewModelScope.launch { hike.ui.collect { autosaveHike(it) } }
    }
}
