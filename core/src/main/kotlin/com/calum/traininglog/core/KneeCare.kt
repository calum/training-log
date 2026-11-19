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
 * HEAVY (the default): the home calf ladder level as it stands, the hardest the kit allows (single-leg, kettlebell, slow
 * lowering as the levels go up, see [CalfLadder]). STAMINA ("Higher reps"): the same exercise at higher reps. Both
 * work on the `calf_home` track; the reps and tempo here are the legacy defaults, the ladder level sets the real ones.
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
    /** What he owns; the routine uses nothing outside it. */
    val kit: KneeCareKit = KneeCareKit(),
    /** The home calf ladder level (1-6) this session starts at. */
    val calfLevel: Int = 1,
    /** True when the last session took him up a level ("You moved up a level"). */
    val levelMoved: Boolean = false,
) {
    val calfSpec: CalfSpec get() = CalfLadder.spec(calfLevel, kit)

    /** Everything to have to hand before step 1 (derived from the steps and the kit). */
    val gear: List<GearItem> get() = KneeCareRoutine.gear(steps, kit, calfLevel)

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
    enum class Need { STEP, BAND, CHAIR }

    class Move(val id: String, val name: String, val cue: String, val unilateral: Boolean = false, val kneeLoad: Boolean = false, val needs: Need? = null)

    val hipCircles = Move("kc_hip_circles", "Hip circles", "Hands on hips, feet planted. Slow, easy circles with your pelvis, then reverse halfway.")
    val legSwings = Move("kc_leg_swings", "Leg swings", "Hold a wall. Swing one leg gently forward and back, small and relaxed. No kicking.", unilateral = true)

    val abduction = Move("kc_abduction", "Side-lying hip abduction", "Bottom knee bent. Lift the top leg straight, a little behind you, to hip height. Slow, no rolling back.", unilateral = true)
    val clamshell = Move("kc_clamshell", "Clamshell", "Side-lying, knees bent, heels together. Open the top knee, pelvis still. A light band if bodyweight is easy.", unilateral = true)
    val sidePlank = Move("kc_side_plank", "Side plank", "Elbow under shoulder, hips lifted in one line. Bottom knee down is fine. Breathe.", unilateral = true)
    val slBridge = Move("kc_sl_bridge", "Single-leg glute bridge", "One foot planted, the other leg long. Lift the hips level, pause, lower slowly.", unilateral = true)
    val lateralWalk = Move("kc_lateral_walk", "Lateral band walk", "Light band above the knees, soft knees, toes forward. Small steps sideways, then back.", needs = Need.BAND)
    val hipHike = Move("kc_hip_hike", "Standing hip hike", "Stand on one leg on a step edge. Let the other hip drop, then lift it level. Slow, knee soft.", unilateral = true, needs = Need.STEP)
    val stepDown = Move("kc_step_down", "Slow step-down", "From a low step, lower the other heel for 3 s. Small height. Stop if the knee pinches.", unilateral = true, kneeLoad = true, needs = Need.STEP)

    /** The no-step knee control block: hip stability (single-leg RDL) and a slow single-leg squat to a chair. */
    val slRdl = Move("kc_sl_rdl", "Single-leg Romanian deadlift", "Hinge on one leg, back flat, lower for 3 s. 3 x 8 each side.", unilateral = true)
    val pistol = Move("kc_pistol", "Slow single-leg squat to chair", "Lower 3 s to a light chair touch, knee over middle toes. 3 x 5 per leg. Stop if it pinches.", unilateral = true, kneeLoad = true, needs = Need.CHAIR)

    val calfStraight = Move("kc_calf_straight", "Calf raise (straight knee)", "Weight in one hand, other hand on a wall. Up for 2 s, down for 3 s. Knees straight.")
    val calfBent = Move("kc_calf_bent", "Calf raise (bent knee)", "Knees softly bent, weight held at your hips. Up for 2 s, down for 3 s. Works the deeper calf.")

    val hipFlexor = Move("kc_hip_flexor", "Hip flexor stretch", "Half-kneeling on a pad. Tuck the pelvis under and lean forward gently. No bouncing.", unilateral = true)
    val figure4 = Move("kc_figure4", "Figure-4 glute stretch", "On your back, ankle over the opposite knee, draw the thigh in. Eases the outer hip. No foam rolling here.", unilateral = true)
    val kneeHug = Move("kc_knee_hug", "Knee hug", "On your back, hug one knee towards the chest. Keep the other leg long and relaxed.", unilateral = true)

    val all: List<Move> = listOf(
        hipCircles, legSwings, abduction, clamshell, sidePlank, slBridge, lateralWalk, hipHike, stepDown, slRdl, pistol,
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

    /** The knee control moves, in the order the sessions rotate through them (the step-down only with a step). */
    val control: List<Move> = listOf(slRdl, pistol, stepDown)

    fun allowed(m: Move, kit: KneeCareKit): Boolean = when (m.needs) {
        Need.STEP -> kit.hasStep
        Need.BAND -> kit.hasBand
        Need.CHAIR -> kit.hasChair
        null -> true
    }
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
    const val CALF_SETS = 3
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
            // The leading hip moves tell the variants apart, the first one most.
            KneeCareLibrary.variants.indices.maxByOrNull { v -> KneeCareLibrary.variants[v].take(3).withIndex().sumOf { (i, m) -> if (m.id in hip) 3 - i else 0 } * 10 - v }
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

    /** The calf steps: [sets] rep-based sets with a timed rest between them (none after the last set). */
    private fun calfBlock(move: KneeCareLibrary.Move, sets: Int, spec: CalfSpec, reps: Int, restSec: Int): List<CoreStep> = buildList {
        repeat(sets) { n ->
            add(CoreStep(move.name, spec.cue(), spec.setSec(reps), CoreKind.WORK, exerciseId = ExerciseLibrary.CALF_HOME_ID, reps = reps, level = spec.level))
            if (n < sets - 1) add(rest(restSec, "Rest", "Shake the legs out. Tell us how the set felt if you like.", ExerciseLibrary.CALF_HOME_ID))
        }
    }

    private fun getSetStep(spec: CalfSpec, kit: KneeCareKit): CoreStep =
        if (spec.loaded) rest(GET_SET_SEC, "Pick up your kettlebell", "The ${kgText(kit.kettlebellKg ?: 0.0)} kettlebell in one hand. Stand by a wall or chair.")
        else rest(GET_SET_SEC, "Get set for calf raises", "Stand by a wall or chair. A kettlebell is only needed if a set feels too easy.")

    private fun kgText(kg: Double) = if (kg % 1.0 == 0.0) "${kg.toInt()} kg" else "$kg kg"

    /** The cue of a knee control move at [level] (0 = the challenging start; slower lowering, the kettlebell and reps follow). */
    fun controlCue(m: KneeCareLibrary.Move, level: Int, kit: KneeCareKit): String {
        val l = level.coerceIn(0, 3)
        return when (m.id) {
            KneeCareLibrary.slRdl.id -> {
                val hold = kit.kettlebellKg?.let { "Hold the ${kgText(it)} kettlebell. " } ?: ""
                "${hold}Hinge on one leg, back flat, ${if (l >= 1) 4 else 3} s down. 3 x ${if (l >= 2) 10 else 8} each side."
            }
            KneeCareLibrary.pistol.id -> {
                val chest = if (l >= 1 && kit.hasKettlebell) "Kettlebell at your chest. " else ""
                "${chest}Lower ${if (l >= 2) 4 else 3} s to a light chair touch, knee over middle toes. 3 x ${if (l >= 3) 6 else 5} per leg. Stop if it pinches."
            }
            else -> m.cue
        }
    }

    /**
     * What to have to hand for [steps]: one item per piece of kit (mat or towel, kettlebell with its weight, chair or wall,
     * band, step) with the exercises that use it, so nothing has to be fetched mid-routine. The kettlebell for the calf raises
     * is optional until the calf level holds it (a set that feels easy may need it next).
     */
    fun gear(steps: List<CoreStep>, kit: KneeCareKit = KneeCareKit(), calfLevel: Int = 1): List<GearItem> {
        val uses = LinkedHashMap<String, MutableList<String>>()
        val required = HashMap<String, Boolean>()
        fun add(item: String, step: String, optional: Boolean = false) {
            val l = uses.getOrPut(item) { mutableListOf() }
            if (step !in l) l += step
            required[item] = (required[item] ?: false) || !optional
        }
        val floor = if (kit.hasMat) "Mat" else "Towel or carpet to lie on"
        val kb = kit.kettlebellKg?.let { "Kettlebell (${kgText(it)})" }
        val calf = CalfLadder.spec(calfLevel, kit)
        for (st in steps) {
            val id = st.exerciseId ?: continue
            if (st.kind == CoreKind.REST) continue
            val name = KneeCareLibrary.get(id)?.name ?: "Calf raises"
            when (id) {
                KneeCareLibrary.legSwings.id -> add("Wall or chair for balance", name)
                KneeCareLibrary.abduction.id, KneeCareLibrary.sidePlank.id, KneeCareLibrary.slBridge.id,
                KneeCareLibrary.hipFlexor.id, KneeCareLibrary.figure4.id, KneeCareLibrary.kneeHug.id -> add(floor, name)
                KneeCareLibrary.clamshell.id -> { add(floor, name); if (kit.hasBand) add("Light band", name, optional = true) }
                KneeCareLibrary.lateralWalk.id -> add("Light band", name)
                KneeCareLibrary.hipHike.id, KneeCareLibrary.stepDown.id -> add("Low step", name)
                KneeCareLibrary.slRdl.id -> if (kb != null) add(kb, name)
                KneeCareLibrary.pistol.id -> { add("Chair", name); if (kb != null && st.level >= 1) add(kb, name) }
                ExerciseLibrary.CALF_HOME_ID, CALF_WARMUP_ID -> {
                    add("Wall or chair for balance", name)
                    if (kb != null) add(kb, name, optional = !calf.loaded)
                    if (calf.stepEdge) add("Step or stair edge", name, optional = true)
                }
            }
        }
        return uses.map { (item, l) -> GearItem(item, l.joinToString(", "), optional = required[item] != true) }
    }

    /**
     * Builds the routine for [minutes] (12..20, default 15): an easy warm-up, a hip abductor and glute block with the knee
     * control moves, the calf block and a short cool-down. Low impact only, nothing jumps. It uses only the gear in [kit]:
     * no step means the single-leg Romanian deadlift and the slow single-leg squat to a chair replace the step-down; no
     * band or chair drops the moves that need them. The calf block follows [calfLevel] of the home ladder ([CalfLadder]),
     * 3 sets, rep-based (the user presses Done), the rest is timed. [calfMode] STAMINA aims for higher reps. The straight and
     * bent knee variants alternate between sessions. [last] holds the exercise ids of the previous session so this one
     * differs; [kneePain] (0-10) at 5 or more drops the squat variants and the step-down and shows the physio line, at 7 or
     * more offers gentle mobility only. [controlLevels] maps a control move id to its level (0 = the challenging start).
     * Same inputs, same routine. The steps add up to exactly [minutes] x 60 seconds, unless the calf block is so long that
     * the hips would be squeezed: the routine then runs longer, in whole minutes ([KneeCareSession.minutes] says so).
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
        kit: KneeCareKit = KneeCareKit(),
        calfLevel: Int = 1,
        controlLevels: Map<String, Int> = emptyMap(),
        levelMoved: Boolean = false,
    ): KneeCareSession {
        val requested = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
        val gate = when {
            (kneePain ?: 0) >= GENTLE_ONLY_KNEE -> KneeGate.GENTLE_ONLY
            (kneePain ?: 0) >= STEP_DOWN_OFF_KNEE -> KneeGate.NO_STEP_DOWN
            else -> KneeGate.FULL
        }
        val physio = if (gate == KneeGate.FULL) null else PHYSIO_LINE
        val variant = variantFor(last, seed)
        if (gate == KneeGate.GENTLE_ONLY) return gentle(requested, requested * 60, variant, physio, kit)

        val spec = CalfLadder.spec(calfLevel, kit)
        val reps = calfReps ?: CalfLadder.targetReps(spec, calfMode)
        val warmEach = when { requested < 13 -> 30; requested < 18 -> 40; else -> 50 }
        val coolEach = when { requested < 13 -> 20; requested < 18 -> 25; else -> 35 }
        val calfSets = CALF_SETS
        val calfVariant = calfVariantFor(last)
        val calfSteps = calfBlock(calfMove(calfVariant), calfSets, spec, reps, CalfLadder.baseRestSec(spec.level, calfMode))
        val calfSec = calfSteps.sumOf { it.seconds }

        fun ok(mv: KneeCareLibrary.Move) = KneeCareLibrary.allowed(mv, kit) && (gate == KneeGate.FULL || !mv.kneeLoad)
        val controls = KneeCareLibrary.control.filter(::ok).let { c -> if (c.isEmpty()) c else List(c.size) { c[Math.floorMod(it + variant, c.size)] } }
        val others = KneeCareLibrary.variants[variant].filter { it !in KneeCareLibrary.control && ok(it) }
        // Two hip moves lead, then the knee control moves, then the rest.
        val pool = others.take(2) + controls + others.drop(2)
        fun weight(mv: KneeCareLibrary.Move) = if (mv in controls) 2 else 1
        fun units(l: List<KneeCareLibrary.Move>) = l.sumOf { mv -> (if (mv.unilateral) 2 else 1) * weight(mv) }
        fun fixed(hc: Int) = 3 * warmEach + 4 * coolEach + GET_SET_SEC + calfSec + (hc - 1) * HIP_REST_SEC
        val minHip = minOf(3, pool.size)
        var total = requested * 60
        var hipCount = (6 downTo minHip).firstOrNull { hc -> hc <= pool.size && (total - fixed(hc)) / units(pool.take(hc)) >= MIN_HIP_STEP_SEC }
        if (hipCount == null) {
            hipCount = minHip
            val need = fixed(hipCount) + units(pool.take(hipCount)) * MIN_HIP_STEP_SEC
            total = (need + 59) / 60 * 60
        }
        val m = total / 60
        val hip = pool.take(hipCount)
        val nUnits = units(hip)
        val room = total - fixed(hipCount)
        val baseWork = (room / nUnits / 5 * 5).coerceAtLeast(25)
        // Whole 5 s extras go to the first hip steps; the last few seconds (under 5) go to the final stretch.
        var extra = (room - baseWork * nUnits).coerceAtLeast(0) / 5

        val raw = mutableListOf<CoreStep>()
        raw += step(KneeCareLibrary.hipCircles, warmEach, CoreKind.WARMUP)
        raw += sides(KneeCareLibrary.legSwings, warmEach, CoreKind.WARMUP)
        hip.forEachIndexed { hi, mv ->
            val w = weight(mv)
            val lvl = controlLevels[mv.id] ?: 0
            raw += sides(mv, baseWork * w, CoreKind.WORK).map { st ->
                val withCue = if (w == 2) st.copy(cue = controlCue(mv, lvl, kit), level = lvl.coerceIn(0, 3)) else st
                if (extra > 0) { extra--; withCue.copy(seconds = withCue.seconds + 5) } else withCue
            }
            if (hi < hip.lastIndex) raw += rest(HIP_REST_SEC)
        }
        raw += getSetStep(spec, kit)
        raw += calfSteps
        raw += sides(KneeCareLibrary.hipFlexor, coolEach, CoreKind.COOLDOWN)
        raw += sides(KneeCareLibrary.figure4, coolEach, CoreKind.COOLDOWN)
        val startKg = calfStartKg ?: CalfLadder.loadKg(spec, kit)
        return finish(m, total, variant, calfVariant, gate, physio, calfSets, startKg, raw, calfMode, reps, lastTime, exact = true, kit = kit, calfLevel = spec.level, levelMoved = levelMoved)
    }

    /**
     * "Just calf raises": only the calf block, after a light warm-up set. For days the user only wants that. The total is
     * the sum of its parts rather than a fixed length (it grows with the ladder level: single-leg sets take both legs).
     */
    fun buildCalfOnly(
        last: List<String>? = null,
        calfStartKg: Double? = null,
        calfMode: CalfMode = CalfMode.HEAVY,
        calfReps: Int? = null,
        lastTime: String? = null,
        kit: KneeCareKit = KneeCareKit(),
        calfLevel: Int = 1,
        levelMoved: Boolean = false,
    ): KneeCareSession {
        val spec = CalfLadder.spec(calfLevel, kit)
        val reps = calfReps ?: CalfLadder.targetReps(spec, calfMode)
        val v = calfVariantFor(last)
        val raw = mutableListOf<CoreStep>()
        raw += getSetStep(spec, kit)
        raw += CoreStep(
            "Calf raise warm-up set", "Two legs, bodyweight, about 10 easy reps. Up 2 s, down 2 s. Then the working sets.",
            40, CoreKind.WORK, exerciseId = CALF_WARMUP_ID, reps = 10,
        )
        raw += rest(30, "Rest", "Get ready for your first working set.", CALF_WARMUP_ID)
        raw += calfBlock(calfMove(v), CALF_SETS, spec, reps, minOf(CalfLadder.baseRestSec(spec.level, calfMode), 60))
        val total = raw.sumOf { it.seconds }
        val startKg = calfStartKg ?: CalfLadder.loadKg(spec, kit)
        return finish((total + 30) / 60, total, 0, v, KneeGate.FULL, null, CALF_SETS, startKg, raw, calfMode, reps, lastTime, exact = false, calfOnly = true, kit = kit, calfLevel = spec.level, levelMoved = levelMoved)
    }

    private fun gentle(m: Int, total: Int, variant: Int, physio: String?, kit: KneeCareKit): KneeCareSession {
        val moves = listOf(
            step(KneeCareLibrary.hipCircles, 0, CoreKind.WARMUP),
            *sides(KneeCareLibrary.legSwings, 0, CoreKind.WARMUP).toTypedArray(),
            *sides(KneeCareLibrary.kneeHug, 0, CoreKind.COOLDOWN).toTypedArray(),
            *sides(KneeCareLibrary.figure4, 0, CoreKind.COOLDOWN).toTypedArray(),
            *sides(KneeCareLibrary.hipFlexor, 0, CoreKind.COOLDOWN).toTypedArray(),
        )
        val each = (total / moves.size / 5 * 5).coerceAtLeast(20)
        return finish(m, total, variant, null, KneeGate.GENTLE_ONLY, physio, 0, null, moves.map { it.copy(seconds = each) }.toMutableList(), CalfMode.HEAVY, CalfMode.HEAVY.repsLow, null, exact = true, kit = kit)
    }

    /** Sets next-up names and (when [exact]) hands any seconds left over to the last step, so the total is exactly [total]. */
    private fun finish(
        m: Int, total: Int, variant: Int, calfVariant: CalfVariant?, gate: KneeGate, physio: String?, calfSets: Int,
        calfStartKg: Double?, raw: MutableList<CoreStep>, mode: CalfMode, reps: Int, lastTime: String?, exact: Boolean, calfOnly: Boolean = false,
        kit: KneeCareKit = KneeCareKit(), calfLevel: Int = 1, levelMoved: Boolean = false,
    ): KneeCareSession {
        val steps = raw.mapIndexed { i, s ->
            s.copy(nextName = raw.indices.drop(i + 1).firstOrNull { raw[it].kind != CoreKind.REST }?.let { label(raw[it], raw, it) })
        }.toMutableList()
        if (exact) steps[steps.lastIndex] = steps.last().copy(seconds = max(5, steps.last().seconds + total - steps.sumOf { it.seconds }))
        return KneeCareSession(m, variant, calfVariant, gate, physio, steps, calfSets, calfStartKg, mode, reps, lastTime, calfOnly, kit, calfLevel, levelMoved)
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

    /** The plain statement shown with the ladder: why the home work progresses the way it does. */
    const val HOME_NOTE = "At home the load is limited, so we make it harder with one leg, a slower lowering and more reps. Your heavy calf raises at the gym stay the strength work."

    /** What a logged home calf set was: HIGHER REPS when aimed above the top of its level's range (older logs: over 10 reps). */
    fun modeOf(log: ExerciseLog): CalfMode =
        if (log.sets.any { it.level != null }) (if (CalfLadder.isStamina(log.sets)) CalfMode.STAMINA else CalfMode.HEAVY)
        else if ((log.sets.maxOfOrNull { it.targetReps } ?: 0) <= CalfMode.HEAVY.repsHigh) CalfMode.HEAVY else CalfMode.STAMINA

    /** The mode of the most recent home calf log (what the setup card remembers), else HEAVY. */
    fun lastMode(records: List<SessionRecord>): CalfMode =
        GymBuilder.history(ExerciseLibrary.CALF_HOME_ID, records).firstOrNull()?.let { modeOf(it.log) } ?: CalfMode.HEAVY

    private fun modeHistory(records: List<SessionRecord>, mode: CalfMode): List<HistoryEntry> =
        GymBuilder.history(ExerciseLibrary.CALF_HOME_ID, records).filter { modeOf(it.log) == mode }

    private fun fmt(x: Double) = if (x % 1.0 == 0.0) x.toInt().toString() else x.toString()

    /** "Last time: 6 kg x 12, 12, 11" for the most recent log in [mode]; null when there is none. */
    fun lastTime(records: List<SessionRecord>, mode: CalfMode): String? {
        val log = modeHistory(records, mode).firstOrNull()?.log ?: return null
        val kg = log.sets.maxOf { it.weightKg }
        val load = if (kg <= 0.0) "bodyweight" else "${fmt(kg)} kg"
        val level = CalfLadder.startedAt(log.sets)?.let { " (level $it)" } ?: ""
        return "Last time$level: $load x ${log.sets.joinToString(", ") { it.reps.toString() }}"
    }

    /** What the next home calf session is: the ladder level, its reps and load, and whether the last session moved him up. */
    data class CalfPlan(
        val level: Int,
        val spec: CalfSpec,
        val reps: Int,
        val kg: Double,
        val movedUp: Boolean,
        /** True when there is no ladder history and the level came from his signals (gym calf work). */
        val fromStart: Boolean,
    )

    /**
     * The home calf plan on the `calf_home` track only (the gym `calf` history is read once, for the start level of a new
     * user). The level comes from the last ladder session ([CalfLadder.nextLevel]), else [CalfLadder.startLevel]; [adjust]
     * moves it by hand from the setup card. HEAVY aims for the bottom of the level's range and adds a rep each session it
     * was reached; HIGHER REPS aims above the top. A kettlebell over [CalfLadder.LOAD_FIRST_KG] lets the load rise first.
     */
    fun calfPlan(records: List<SessionRecord>, settings: UserSettings = UserSettings(), mode: CalfMode = CalfMode.HEAVY, adjust: Int = 0, reportedEasy: Boolean = false): CalfPlan {
        val kit = settings.kneeKit
        val lastLog = GymBuilder.history(ExerciseLibrary.CALF_HOME_ID, records).map { it.log }.firstOrNull { l -> l.sets.any { it.level != null } }
        val start = CalfLadder.startLevel(records, reportedEasy)
        var level = lastLog?.let { CalfLadder.nextLevel(it.sets) } ?: start
        val movedUp = lastLog != null && CalfLadder.movedUp(lastLog.sets)
        val lastSpecLevel = lastLog?.let { l -> l.sets.maxOf { CalfLadder.clamp(it.level ?: 1) } }
        var extraKg = 0.0
        val lastKg = lastLog?.sets?.maxOfOrNull { it.weightKg } ?: 0.0
        // Heavier kit: raise the load before the tempo (up to LOAD_ROOM_KG over the kit weight), then go up a level.
        val kb = kit.kettlebellKg
        if (lastLog != null && kb != null && kb > CalfLadder.LOAD_FIRST_KG && lastSpecLevel != null && level > lastSpecLevel &&
            CalfLadder.spec(lastSpecLevel, kit).loaded && lastKg < kb + CalfLadder.LOAD_ROOM_KG
        ) {
            level = lastSpecLevel
            extraKg = max(lastKg, kb) + CalfLadder.LOAD_STEP_KG
        }
        level = CalfLadder.clamp(level + adjust)
        val spec = CalfLadder.spec(level, kit)
        val sameLevel = lastLog != null && lastSpecLevel == level && !CalfLadder.isStamina(lastLog.sets)
        val lastTarget = lastLog?.takeIf { sameLevel }?.let { l ->
            val here = l.sets.filter { CalfLadder.clamp(it.level ?: 1) == level }
            val t = here.maxOf { it.targetReps }
            if (here.all { it.reps >= t && (it.rir ?: 2) >= 1 }) t + 1 else t
        }
        val reps = CalfLadder.targetReps(spec, mode, lastTarget)
        val base = CalfLadder.loadKg(spec, kit)
        val kg = if (!spec.loaded) 0.0 else max(base, if (extraKg > 0) extraKg else if (sameLevel) lastKg else 0.0)
        return CalfPlan(level, spec, reps, kg, movedUp, fromStart = lastLog == null)
    }

    /**
     * The level of a knee control move (0 = the challenging start, up to 3): one level for every two sessions it was done,
     * nudged up when the last session's calf sets felt too easy and down when they felt too hard or the knee was sore after.
     */
    fun controlLevel(records: List<SessionRecord>, moveId: String): Int {
        val knee = records.filter { it.isKneeCare }.sortedBy { it.date }
        val times = knee.count { moveId in KneeCareLibrary.idsFromNote(it.note) }
        var level = times / 2
        val sets = knee.lastOrNull()?.exercises?.firstOrNull { it.exerciseId == ExerciseLibrary.CALF_HOME_ID }?.sets.orEmpty()
        val rated = sets.mapNotNull { it.rir }
        if (rated.isNotEmpty() && rated.count { it >= 3 } * 2 > rated.size) level++
        if ((rated.isNotEmpty() && rated.count { it == 0 } * 2 > rated.size) || (knee.lastOrNull()?.kneePain ?: 0) >= KneeCareRoutine.STEP_DOWN_OFF_KNEE) level--
        return level.coerceIn(0, 3)
    }

    /** The control levels for the moves in the library (those the routine can pick). */
    fun controlLevels(records: List<SessionRecord>): Map<String, Int> = KneeCareLibrary.control.associate { it.id to controlLevel(records, it.id) }

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
        why = "Hip abductor and glute work, a single-leg knee control block, calf raises, then gentle stretches.",
        launch = LaunchHint(SessionType.CORE, minutes = minutes, kneeCare = true),
    )
}
