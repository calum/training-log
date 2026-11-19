package com.calum.traininglog.core

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.max

/*
 * Knee care: a short guided hip and calf routine (about 15 minutes) for the right ITB knee, doable at the end of any
 * day, after other exercise, or on a rest day. It is an OPTIONAL add-on to the race plan, never one of its sessions.
 *
 * Evidence, honestly (see docs/hyrox-plan-evidence.md, "Knee care and calf work"): for ITB syndrome the sources found
 * centre on HIP ABDUCTOR and glute strengthening, hip and trunk control, heavy-slow strength work and a graded return to
 * running. That is clinical consensus, not trial evidence. Calf raises are low-risk general lower-leg conditioning that
 * builds load tolerance; no trial found shows they specifically treat the ITB, so the app never claims that.
 * Not medical advice: if pain persists, see your physio.
 */

/** A saved knee care session is a CORE record labelled this way (see [KneeCareRecord]). */
val SessionRecord.isKneeCare: Boolean get() = type == SessionType.CORE && dayLabel == KneeCareRecord.DAY_LABEL

/**
 * The records without knee care sessions. Used wherever a knee care session must not count as a workout: it never uses
 * up a rest day, never counts towards the plan's planned minutes, the baseline or a quiet week, and never changes the
 * "how do you feel" signals that shape the plan.
 */
fun List<SessionRecord>.withoutKneeCare(): List<SessionRecord> = if (any { it.isKneeCare }) filterNot { it.isKneeCare } else this

/**
 * Knee care in a plan week: [done] add-ons logged so far, [calfExposures] = gym sessions with a calf raise plus [done],
 * and an optional calm [note]. Never counts towards the plan's minutes, balance or health.
 */
data class KneeCareStatus(
    val target: Int = KneeCare.WEEKLY_TARGET,
    val done: Int = 0,
    val calfExposures: Int = 0,
    val gymCalf: Int = 0,
    val note: String? = null,
) {
    val complete: Boolean get() = done >= target
    /** The target is "at least twice a week", not a cap: more is fine and is counted. */
    val label: String get() = if (done > target) "Knee care $done this week" else "Knee care $done of $target this week"
}

/**
 * HEAVY (the default): 3-4 slow sets of 6-10 (3 s up, a brief pause, 3 s down) at a challenging load, the way the user
 * already gets on with. STAMINA: 3 sets of 12-15. Both double-progress on the `calf_home` track (see [KneeCare.calfPlan]).
 */
enum class CalfMode(val label: String, val repsLow: Int, val repsHigh: Int, val tempo: String, val setSec: Int, val restSec: Int) {
    HEAVY("Heavy", 6, 10, "3 s up, brief pause, 3 s down", 60, 75),
    STAMINA("Higher reps", 12, 15, "2 s up, 3 s down", 65, 35);

    val range: String get() = "$repsLow-$repsHigh"
}

enum class CalfVariant(val label: String) {
    STRAIGHT("straight knee"),
    BENT("bent knee"),
}

/** How the knee score shapes the routine: [FULL], without the step-down, or gentle mobility only. */
enum class KneeGate { FULL, NO_STEP_DOWN, GENTLE_ONLY }

/**
 * One built routine. [steps] are ordinary [CoreStep]s (so [CoreRun] runs them); [calfSets] is how many weighted calf
 * sets are in it (0 for a gentle-only routine); [calfStartKg] is the weight to prefill (null: blank for the user to type).
 */
data class KneeCareSession(
    val minutes: Int,
    val variant: Int,
    val calfVariant: CalfVariant?,
    val gate: KneeGate,
    val physioLine: String?,
    val steps: List<CoreStep>,
    val calfSets: Int,
    val calfStartKg: Double?,
    val calfMode: CalfMode = CalfMode.HEAVY,
    /** Reps aimed for in each working calf set (from the progression). */
    val calfReps: Int = calfMode.repsLow,
    /** "Last time: 25 kg x 8, 8, 7" (null when there is no earlier log in this mode). */
    val lastTime: String? = null,
    /** True for "Just calf raises": the calf block only, with a warm-up set. */
    val calfOnly: Boolean = false,
) {
    val totalSec: Int get() = steps.sumOf { it.seconds }

    /** Ids of the work steps' exercises, in order, no repeats: pass as `last` for the next session. */
    val exerciseIds: List<String>
        get() = (steps.filter { it.kind != CoreKind.REST }.mapNotNull { it.exerciseId } +
            listOfNotNull(calfVariant?.let { if (it == CalfVariant.STRAIGHT) KneeCareLibrary.calfStraight.id else KneeCareLibrary.calfBent.id })).distinct()

    /** Names of the exercises in the routine in order, no repeats (warm-up and stretches included). */
    val names: List<String> get() = steps.filter { it.kind != CoreKind.REST }.map { it.name }.distinct()

    /** 0-based number of the calf set that step [index] belongs to (a calf set or the rest straight after it), else null. */
    fun calfSetAt(index: Int): Int? = KneeCareRoutine.calfSetAt(steps, index)

    /** Rep-based steps wait for "Done, next" instead of counting down (the calf sets and the calf warm-up set). */
    fun isRepStep(index: Int): Boolean = steps.getOrNull(index)?.let { KneeCareRoutine.isRepStep(it) } == true
}

object KneeCareLibrary {
    class Move(val id: String, val name: String, val cue: String, val unilateral: Boolean = false, val kneeLoad: Boolean = false)

    val hipCircles = Move("kc_hip_circles", "Hip circles", "Hands on hips, feet planted. Slow, easy circles with your pelvis, then reverse halfway.")
    val legSwings = Move("kc_leg_swings", "Leg swings", "Hold a wall. Swing one leg gently forward and back, small and relaxed. No kicking.", unilateral = true)

    val abduction = Move("kc_abduction", "Side-lying hip abduction", "Bottom knee bent. Lift the top leg straight, a little behind you, to hip height. Slow, no rolling back.", unilateral = true)
    val clamshell = Move("kc_clamshell", "Clamshell", "Side-lying, knees bent, heels together. Open the top knee, pelvis still. A light band if bodyweight is easy.", unilateral = true)
    val sidePlank = Move("kc_side_plank", "Side plank", "Elbow under shoulder, hips lifted in one line. Bottom knee down is fine. Breathe.", unilateral = true)
    val slBridge = Move("kc_sl_bridge", "Single-leg glute bridge", "One foot planted, the other leg long. Lift the hips level, pause, lower slowly.", unilateral = true)
    val lateralWalk = Move("kc_lateral_walk", "Lateral band walk", "Light band above the knees, soft knees, toes forward. Small steps sideways, then back. No band: bodyweight.")
    val hipHike = Move("kc_hip_hike", "Standing hip hike", "Stand on one leg on a step edge. Let the other hip drop, then lift it level. Slow, knee soft.", unilateral = true)
    val stepDown = Move("kc_step_down", "Slow step-down", "From a low step, lower the other heel for 3 s. Small height. Stop if the knee pinches.", unilateral = true, kneeLoad = true)

    val calfStraight = Move("kc_calf_straight", "Calf raise (straight knee)", "Weight in one hand, other hand on a wall. Up for 2 s, down for 3 s. Knees straight.")
    val calfBent = Move("kc_calf_bent", "Calf raise (bent knee)", "Knees softly bent, weight held at your hips. Up for 2 s, down for 3 s. Works the deeper calf.")

    val hipFlexor = Move("kc_hip_flexor", "Hip flexor stretch", "Half-kneeling on a pad. Tuck the pelvis under and lean forward gently. No bouncing.", unilateral = true)
    val figure4 = Move("kc_figure4", "Figure-4 glute stretch", "On your back, ankle over the opposite knee, draw the thigh in. Eases the outer hip. No foam rolling here.", unilateral = true)
    val kneeHug = Move("kc_knee_hug", "Knee hug", "On your back, hug one knee towards the chest. Keep the other leg long and relaxed.", unilateral = true)

    val all: List<Move> = listOf(
        hipCircles, legSwings, abduction, clamshell, sidePlank, slBridge, lateralWalk, hipHike, stepDown,
        calfStraight, calfBent, hipFlexor, figure4, kneeHug,
    )
    private val byId = all.associateBy { it.id }
    private val byName = all.associateBy { it.name }
    fun get(id: String): Move? = byId[id]

    /** Ids for the exercise names in a "Knee care: a, b, c" note (unknown names ignored). */
    fun idsFromNote(note: String?): List<String> {
        val t = note?.trim() ?: return emptyList()
        if (!t.startsWith(KneeCareRecord.NOTE_PREFIX)) return emptyList()
        return t.removePrefix(KneeCareRecord.NOTE_PREFIX).split(",").mapNotNull { byName[it.trim().substringBefore(" (felt").trim()]?.id }
    }

    /**
     * The hip block of each variant, in order of preference (the first few are used, fewer in a shorter routine). The
     * three differ in what they lead with, so consecutive sessions feel different.
     */
    val variants: List<List<Move>> = listOf(
        listOf(abduction, clamshell, sidePlank, slBridge, stepDown, lateralWalk, hipHike),
        listOf(sidePlank, abduction, slBridge, lateralWalk, stepDown, clamshell, hipHike),
        listOf(slBridge, clamshell, hipHike, abduction, sidePlank, stepDown, lateralWalk),
    )
}

object KneeCareRoutine {
    const val DEFAULT_MINUTES = 15
    const val MIN_MINUTES = 12
    const val MAX_MINUTES = 20
    const val VARIANTS = 3
    /** "Just calf raises": a light warm-up set, then the working sets. About 8 minutes in heavy mode. */
    const val CALF_ONLY_HEAVY_MINUTES = 8
    const val CALF_WARMUP_ID = "calf_warmup"
    private const val HIP_REST_SEC = 10
    private const val GET_SET_SEC = 15
    private const val MIN_HIP_STEP_SEC = 30
    const val STEP_DOWN_OFF_KNEE = 5
    const val GENTLE_ONLY_KNEE = 7

    const val PHYSIO_LINE = "Knee at 5/10 or more, or sore for more than a couple of weeks? Please see your physio. This app is not medical advice."

    /** Session note as saved with the record, e.g. "Knee care: Hip circles, Clamshell". Names never contain a comma. */
    fun note(names: List<String>, felt: String? = null): String =
        KneeCareRecord.NOTE_PREFIX + names.joinToString(", ") + (felt?.let { " (felt $it)" } ?: "")

    private fun label(s: CoreStep, steps: List<CoreStep>, i: Int): String {
        val base = CoreRoutine.label(s)
        val set = calfSetAt(steps, i)
        return if (set != null && s.kind == CoreKind.WORK) "$base, set ${set + 1}" else base
    }

    /** A calf set or the calf warm-up set: the user does the reps at their own pace and presses Done. */
    fun isRepStep(s: CoreStep): Boolean =
        s.kind == CoreKind.WORK && (s.exerciseId == ExerciseLibrary.CALF_HOME_ID || s.exerciseId == CALF_WARMUP_ID)

    internal fun calfSetAt(steps: List<CoreStep>, index: Int): Int? {
        val s = steps.getOrNull(index) ?: return null
        if (s.exerciseId != ExerciseLibrary.CALF_HOME_ID) return null
        val before = steps.subList(0, index + 1).count { it.kind == CoreKind.WORK && it.exerciseId == ExerciseLibrary.CALF_HOME_ID }
        return if (before > 0) before - 1 else null
    }

    private fun step(m: KneeCareLibrary.Move, sec: Int, kind: CoreKind, side: CoreSide? = null, reps: Int? = null) =
        CoreStep(m.name, m.cue, sec, kind, side, exerciseId = m.id, reps = reps)

    private fun rest(sec: Int, name: String = "Rest", cue: String = "Shake out and breathe.", exerciseId: String? = null) =
        CoreStep(name, cue, sec, CoreKind.REST, exerciseId = exerciseId)

    private fun sides(m: KneeCareLibrary.Move, sec: Int, kind: CoreKind): List<CoreStep> =
        if (m.unilateral) listOf(step(m, sec, kind, CoreSide.LEFT), step(m, sec, kind, CoreSide.RIGHT)) else listOf(step(m, sec, kind))

    /** The variant that follows the one [last] came from (by the best overlap of hip exercises), else [seed] picks one. */
    private fun variantFor(last: List<String>?, seed: Long): Int {
        val prev = last?.takeIf { l -> l.any { it.startsWith("kc_") } }?.let { l ->
            val hip = l.toSet()
            KneeCareLibrary.variants.indices.maxByOrNull { v -> KneeCareLibrary.variants[v].take(5).count { it.id in hip } * 10 - v }
        }
        val base = if (prev == null) 0 else prev + 1
        return Math.floorMod(base + seed, VARIANTS.toLong()).toInt()
    }

    /** Alternates with the calf variant of the previous session; straight knee first. */
    private fun calfVariantFor(last: List<String>?): CalfVariant = when {
        last == null -> CalfVariant.STRAIGHT
        KneeCareLibrary.calfStraight.id in last -> CalfVariant.BENT
        KneeCareLibrary.calfBent.id in last -> CalfVariant.STRAIGHT
        else -> CalfVariant.STRAIGHT
    }

    private fun calfMove(v: CalfVariant) = if (v == CalfVariant.STRAIGHT) KneeCareLibrary.calfStraight else KneeCareLibrary.calfBent

    private fun calfCue(base: String, mode: CalfMode) = if (mode == CalfMode.HEAVY) base.replace("Up for 2 s, down for 3 s.", "Up 3 s, pause, down 3 s.") else base

    /** The weighted calf steps: [sets] rep-based sets with a timed rest between them (none after the last set). */
    private fun calfBlock(move: KneeCareLibrary.Move, sets: Int, mode: CalfMode, reps: Int, restSec: Int = mode.restSec): List<CoreStep> = buildList {
        repeat(sets) { n ->
            add(CoreStep(move.name, calfCue(move.cue, mode), mode.setSec, CoreKind.WORK, exerciseId = ExerciseLibrary.CALF_HOME_ID, reps = reps))
            if (n < sets - 1) add(rest(restSec, "Rest", "Shake the legs out. Rate the set below if you like.", ExerciseLibrary.CALF_HOME_ID))
        }
    }

    /**
     * Builds the routine for [minutes] (12..20, default 15): an easy warm-up, a hip abductor and glute block, the
     * weighted calf block, and a short cool-down. Low impact only, nothing jumps. The calf block is HEAVY by default
     * (3-4 slow sets of 6-10) or [calfMode] STAMINA (3 x 12-15); its sets are rep-based (the user presses Done), the rest
     * is timed. The straight and bent knee variants alternate between sessions. [last] holds the exercise ids of the
     * previous session so this one differs; [kneePain] (0-10) at 5 or more drops the step-down and shows the physio
     * line, at 7 or more offers gentle mobility only; [calfStartKg] is the weight to prefill (null: blank for the user
     * to type), [calfReps] the reps aimed for per set and [lastTime] the "Last time" line. The steps add up to exactly
     * [minutes] x 60 seconds (rep-based sets count their usual time). Same inputs, same routine.
     */
    fun build(
        minutes: Int = DEFAULT_MINUTES,
        last: List<String>? = null,
        kneePain: Int? = null,
        calfStartKg: Double? = null,
        seed: Long = 0,
        calfMode: CalfMode = CalfMode.HEAVY,
        calfReps: Int? = null,
        lastTime: String? = null,
    ): KneeCareSession {
        val m = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
        val total = m * 60
        val gate = when {
            (kneePain ?: 0) >= GENTLE_ONLY_KNEE -> KneeGate.GENTLE_ONLY
            (kneePain ?: 0) >= STEP_DOWN_OFF_KNEE -> KneeGate.NO_STEP_DOWN
            else -> KneeGate.FULL
        }
        val physio = if (gate == KneeGate.FULL) null else PHYSIO_LINE
        val variant = variantFor(last, seed)
        if (gate == KneeGate.GENTLE_ONLY) return gentle(m, total, variant, physio)

        val reps = (calfReps ?: calfMode.repsLow).coerceIn(calfMode.repsLow, calfMode.repsHigh)
        val warmEach = when { m < 13 -> 30; m < 18 -> 40; else -> 50 }
        val coolEach = when { m < 13 -> 20; m < 18 -> 25; else -> 35 }
        val calfSets = if (calfMode == CalfMode.HEAVY && m >= 18) 4 else 3
        val calfVariant = calfVariantFor(last)
        val calfSteps = calfBlock(calfMove(calfVariant), calfSets, calfMode, reps)
        val calfSec = calfSteps.sumOf { it.seconds }

        val pool = KneeCareLibrary.variants[variant].filter { gate == KneeGate.FULL || !it.kneeLoad }
        fun fixed(hc: Int) = 3 * warmEach + 4 * coolEach + GET_SET_SEC + calfSec + (hc - 1) * HIP_REST_SEC
        fun work(hc: Int): Int = pool.take(hc).let { l -> l.size + l.count { it.unilateral } }
        // As many hip exercises as fit with each step at least 30 s (at most 6, at least 2).
        val hipCount = (6 downTo 2).firstOrNull { hc -> (total - fixed(hc)) / work(hc) >= MIN_HIP_STEP_SEC } ?: 2
        val hip = pool.take(hipCount)
        val nWork: Int = work(hipCount)
        val room = total - fixed(hipCount)
        val baseWork = (room / nWork / 5 * 5).coerceAtLeast(25)
        // Whole 5 s extras go to the first hip steps; the last few seconds (under 5) go to the final stretch.
        var extra = (room - baseWork * nWork).coerceAtLeast(0) / 5

        val raw = mutableListOf<CoreStep>()
        raw += step(KneeCareLibrary.hipCircles, warmEach, CoreKind.WARMUP)
        raw += sides(KneeCareLibrary.legSwings, warmEach, CoreKind.WARMUP)
        hip.forEachIndexed { hi, mv ->
            raw += sides(mv, baseWork, CoreKind.WORK).map { s -> if (extra > 0) { extra--; s.copy(seconds = s.seconds + 5) } else s }
            if (hi < hip.lastIndex) raw += rest(HIP_REST_SEC)
        }
        raw += rest(GET_SET_SEC, "Pick up your weight", "A dumbbell or kettlebell in one hand, or a loaded backpack. Stand by a wall.")
        raw += calfSteps
        raw += sides(KneeCareLibrary.hipFlexor, coolEach, CoreKind.COOLDOWN)
        raw += sides(KneeCareLibrary.figure4, coolEach, CoreKind.COOLDOWN)
        return finish(m, total, variant, calfVariant, gate, physio, calfSets, calfStartKg, raw, calfMode, reps, lastTime, exact = true)
    }

    /**
     * "Just calf raises": only the calf block, after a light warm-up set (about 8 minutes in heavy mode). For days the
     * user only wants that. The total is the sum of its parts rather than a fixed length.
     */
    fun buildCalfOnly(
        last: List<String>? = null,
        calfStartKg: Double? = null,
        calfMode: CalfMode = CalfMode.HEAVY,
        calfReps: Int? = null,
        lastTime: String? = null,
    ): KneeCareSession {
        val reps = (calfReps ?: calfMode.repsLow).coerceIn(calfMode.repsLow, calfMode.repsHigh)
        val sets = if (calfMode == CalfMode.HEAVY) 4 else 3
        val v = calfVariantFor(last)
        val raw = mutableListOf<CoreStep>()
        raw += rest(15, "Pick up your weight", "A dumbbell or kettlebell in one hand, or a loaded backpack. Stand by a wall.")
        raw += CoreStep(
            "Calf raise warm-up set", "Bodyweight or a light load, about 10 easy reps. Up 2 s, down 2 s. Then add your working weight.",
            40, CoreKind.WORK, exerciseId = CALF_WARMUP_ID, reps = 10,
        )
        raw += rest(30, "Rest", "Pick up your working weight.", CALF_WARMUP_ID)
        raw += calfBlock(calfMove(v), sets, calfMode, reps, minOf(calfMode.restSec, 60))
        val total = raw.sumOf { it.seconds }
        return finish((total + 30) / 60, total, 0, v, KneeGate.FULL, null, sets, calfStartKg, raw, calfMode, reps, lastTime, exact = false, calfOnly = true)
    }

    private fun gentle(m: Int, total: Int, variant: Int, physio: String?): KneeCareSession {
        val moves = listOf(
            step(KneeCareLibrary.hipCircles, 0, CoreKind.WARMUP),
            *sides(KneeCareLibrary.legSwings, 0, CoreKind.WARMUP).toTypedArray(),
            *sides(KneeCareLibrary.kneeHug, 0, CoreKind.COOLDOWN).toTypedArray(),
            *sides(KneeCareLibrary.figure4, 0, CoreKind.COOLDOWN).toTypedArray(),
            *sides(KneeCareLibrary.hipFlexor, 0, CoreKind.COOLDOWN).toTypedArray(),
        )
        val each = (total / moves.size / 5 * 5).coerceAtLeast(20)
        return finish(m, total, variant, null, KneeGate.GENTLE_ONLY, physio, 0, null, moves.map { it.copy(seconds = each) }.toMutableList(), CalfMode.HEAVY, CalfMode.HEAVY.repsLow, null, exact = true)
    }

    /** Sets next-up names and (when [exact]) hands any seconds left over to the last step, so the total is exactly [total]. */
    private fun finish(
        m: Int, total: Int, variant: Int, calfVariant: CalfVariant?, gate: KneeGate, physio: String?, calfSets: Int,
        calfStartKg: Double?, raw: MutableList<CoreStep>, mode: CalfMode, reps: Int, lastTime: String?, exact: Boolean, calfOnly: Boolean = false,
    ): KneeCareSession {
        val steps = raw.mapIndexed { i, s ->
            s.copy(nextName = raw.indices.drop(i + 1).firstOrNull { raw[it].kind != CoreKind.REST }?.let { label(raw[it], raw, it) })
        }.toMutableList()
        if (exact) steps[steps.lastIndex] = steps.last().copy(seconds = max(5, steps.last().seconds + total - steps.sumOf { it.seconds }))
        return KneeCareSession(m, variant, calfVariant, gate, physio, steps, calfSets, calfStartKg, mode, reps, lastTime, calfOnly)
    }
}

/** Builds the saved record of a knee care session (also used to edit it: the finish card re-builds it with the rating). */
object KneeCareRecord {
    const val DAY_LABEL = "Knee care"
    const val NOTE_PREFIX = "Knee care: "
    /** Small XP: knee care is an add-on, not a workout. */
    const val XP_BASE = 20
    const val XP_PER_MINUTE = 1.0
    const val XP_MINUTE_CAP = 20
    const val BADGE_COUNT = 10

    /**
     * A CORE record labelled "Knee care" whose calf work is logged as [ExerciseLibrary.CALF_HOME_ID], the home calf's own
     * weight track (the gym `calf` history is untouched). Null when under a minute was done (nothing is logged).
     */
    fun build(
        id: String, date: LocalDate, plannedMinutes: Int, completedSec: Int, names: List<String>, calfSets: List<LoggedSet>,
        feeling: Feeling = Feeling.GOOD, kneePain: Int? = null, felt: String? = null,
    ): SessionRecord? {
        val minutes = (completedSec + 30) / 60
        if (minutes < 1) return null
        return SessionRecord(
            id = id, date = date, type = SessionType.CORE,
            minutesAvailable = plannedMinutes, minutesActual = minutes, feeling = feeling, kneePain = kneePain,
            dayLabel = DAY_LABEL,
            exercises = if (calfSets.isEmpty()) emptyList() else listOf(ExerciseLog(exerciseId = ExerciseLibrary.CALF_HOME_ID, sets = calfSets, customName = null)),
            note = if (names.isEmpty()) NOTE_PREFIX + "warm-up and stretching" else KneeCareRoutine.note(names, felt),
        )
    }
}

/** Planning helpers for knee care: the home calf plan and the weekly status. */
object KneeCare {
    /** "At least twice a week" is the aim, not a cap: more is fine and counts. */
    const val WEEKLY_TARGET = 2
    const val NUDGE = "Calf and hip work: do one knee care session this week. Optional, whenever it suits."
    const val RACE_WEEK_NOTE = "Knee care keeps things loose."
    const val DONE_NOTE = "Knee care is done for the week. More is fine if it feels good."
    /** Loads below this step by 1 kg, heavier ones by 2.5 kg. */
    const val LIGHT_LOAD_KG = 10.0

    /** What a logged home calf set was: heavy sets aim for 10 or fewer reps. */
    fun modeOf(log: ExerciseLog): CalfMode =
        if ((log.sets.maxOfOrNull { it.targetReps } ?: 0) <= CalfMode.HEAVY.repsHigh) CalfMode.HEAVY else CalfMode.STAMINA

    /** The mode of the most recent home calf log (what the setup card remembers), else HEAVY. */
    fun lastMode(records: List<SessionRecord>): CalfMode =
        GymBuilder.history(ExerciseLibrary.CALF_HOME_ID, records).firstOrNull()?.let { modeOf(it.log) } ?: CalfMode.HEAVY

    private fun modeHistory(records: List<SessionRecord>, mode: CalfMode): List<HistoryEntry> =
        GymBuilder.history(ExerciseLibrary.CALF_HOME_ID, records).filter { modeOf(it.log) == mode }

    private fun fmt(x: Double) = if (x % 1.0 == 0.0) x.toInt().toString() else x.toString()

    /** "Last time: 25 kg x 8, 8, 7" for the most recent log in [mode]; null when there is none. */
    fun lastTime(records: List<SessionRecord>, mode: CalfMode): String? {
        val log = modeHistory(records, mode).firstOrNull()?.log ?: return null
        val kg = log.sets.maxOf { it.weightKg }
        val load = if (kg <= 0.0) "bodyweight" else "${fmt(kg)} kg"
        return "Last time: $load x ${log.sets.joinToString(", ") { it.reps.toString() }}"
    }

    /**
     * The home calf prescription for [mode]: the accessory double-progression rule on the `calf_home` track only (the gym
     * `calf` history is never read). Reach the top of the range on every set and the weight goes up (2.5 kg, or 1 kg when
     * the load is light) with reps back at the bottom; otherwise the same weight and one more rep. The start weight is the
     * last log in this mode, else blank (0.0) for the user to type. Both modes keep their own history, so a light
     * higher-reps day never lowers the heavy load.
     */
    fun calfPrescription(records: List<SessionRecord>, today: LocalDate, settings: UserSettings = UserSettings(), mode: CalfMode = CalfMode.HEAVY): Prescription {
        val history = modeHistory(records, mode)
        if (history.isEmpty()) {
            val how = if (mode == CalfMode.HEAVY) "Pick a load you can lift about 8 times with a couple of reps to spare." else "A light load you can lift 12-15 times."
            return Prescription(0.0, 3, mode.repsLow, "Type the weight you use (a dumbbell, kettlebell or loaded backpack). $how")
        }
        val lastKg = history.first().log.sets.maxOf { it.weightKg }
        val ex = ExerciseLibrary.calfHome.copy(
            repsLow = mode.repsLow, repsHigh = mode.repsHigh, sets = 3,
            incrementKg = if (lastKg < LIGHT_LOAD_KG) 1.0 else 2.5,
        )
        // A home set after a gap carries on from the last weight: the 90% restart is for the gym lifts.
        return Progression.next(ex, history, today, settings = settings.copy(restartAfterDays = Int.MAX_VALUE))
    }

    /** Weight to prefill for [mode]: the prescription's weight, or null (blank) when never logged in this mode. */
    fun calfStartKg(records: List<SessionRecord>, today: LocalDate, settings: UserSettings = UserSettings(), mode: CalfMode = CalfMode.HEAVY): Double? =
        if (modeHistory(records, mode).isEmpty()) null else calfPrescription(records, today, settings, mode).weightKg

    /** Knee care sessions logged in [from]..[to]. */
    fun doneIn(records: List<SessionRecord>, from: LocalDate, to: LocalDate): Int = records.count { it.isKneeCare && it.date in from..to }

    /** Gym sessions in [from]..[to] with at least one gym calf set logged. */
    fun gymCalfIn(records: List<SessionRecord>, from: LocalDate, to: LocalDate): Int =
        records.count { r -> r.type == SessionType.GYM && r.date in from..to && r.exercises.any { it.exerciseId == "calf" && it.sets.isNotEmpty() } }

    /**
     * Status for the week [from]..[to] as seen on [today]. Only the current week ever gets the nudge: it appears from
     * Thursday when gym calf sessions plus knee care add-ons are still under 2 (calm and optional, never alarming).
     * Race week only ever says [RACE_WEEK_NOTE]. Knee care skips are not tracked at all, so they can never count against
     * anyone or reach the plan's health.
     */
    fun status(from: LocalDate, to: LocalDate, records: List<SessionRecord>, today: LocalDate, raceWeek: Boolean): KneeCareStatus {
        val upTo = if (to < today) to else today
        val done = if (from > upTo) 0 else doneIn(records, from, upTo)
        val gym = if (from > upTo) 0 else gymCalfIn(records, from, upTo)
        val exposures = done + gym
        val current = today in from..to
        val note = when {
            raceWeek -> RACE_WEEK_NOTE
            current && exposures < WEEKLY_TARGET && today.dayOfWeek >= DayOfWeek.THURSDAY -> NUDGE
            current && done >= WEEKLY_TARGET -> DONE_NOTE
            else -> null
        }
        return KneeCareStatus(WEEKLY_TARGET, done, exposures, gym, note)
    }

    /**
     * A launchable stand-in for "start a knee care session now": not part of any week, never counted in planned minutes.
     * [AppViewModel.launchPlanned] opens the knee care screen for it.
     */
    fun session(date: LocalDate, minutes: Int = KneeCareRoutine.DEFAULT_MINUTES): PlannedSession = PlannedSession(
        date = date, kind = SessionKind.EASY, title = "Knee care", minutes = minutes, intensity = Intensity.STRENGTH,
        hardMinutes = 0, rpeTarget = 2..3, modalityOptions = listOf(Modality.GYM), detail = emptyList(),
        why = "Hip abductor and glute work, then weighted calf raises, then gentle stretches.",
        launch = LaunchHint(SessionType.CORE, minutes = minutes, kneeCare = true),
    )
}
