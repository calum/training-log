package com.calum.traininglog.core

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Home calf progression and the home kit for knee care.
 *
 * At home the load is limited (a 6 kg kettlebell), so the calf raise is made harder with other levers in a defined ladder
 * instead of weight: one leg, then the kettlebell, then a slower lowering with a pause at the top, then more reps, then a
 * longer pause (and a step edge for full range, if he owns one). The ladder is a DESIGN CHOICE, not research (see
 * docs/hyrox-plan-evidence.md). Heavy calf raises at the gym stay the physio-guided strength work; nothing here touches
 * the gym `calf` history except to read it once for a sensible start level.
 */

/** What he owns at home. The routine never includes an exercise that needs gear that is not ticked here. */
@Serializable
data class KneeCareKit(
    /** Kettlebell (or dumbbell) weight in kg, whole kilos; null means none. */
    val kettlebellKg: Double? = 6.0,
    val hasStep: Boolean = false,
    val hasBand: Boolean = false,
    val hasChair: Boolean = true,
    val hasMat: Boolean = true,
) {
    val hasKettlebell: Boolean get() = kettlebellKg != null

    /** Whole kilos from 1 to [MAX_KG]; anything under 1 means no kettlebell. */
    fun sanitized(): KneeCareKit = copy(kettlebellKg = kettlebellKg?.let { it.roundToInt().toDouble() }?.takeIf { it >= 1.0 }?.coerceAtMost(MAX_KG))

    /** Steps the kettlebell by [deltaKg] whole kilos (0 or less: none; from none a plus gives 1 kg). */
    fun withKettlebellStep(deltaKg: Int): KneeCareKit =
        copy(kettlebellKg = ((kettlebellKg ?: 0.0) + deltaKg).takeIf { it >= 1.0 }?.coerceAtMost(MAX_KG))

    companion object { const val MAX_KG = 40.0 }
}

/** One thing to have to hand: [forStep] says which exercises need it; [optional] ones only matter if the set feels easy. */
data class GearItem(val name: String, val forStep: String, val optional: Boolean = false)

/** The effort feedback after a rep-based set, as the reps left in the tank it stands for (too easy 3+, right 2, too hard 0). */
val Effort.rir: Int get() = when (this) { Effort.TOO_EASY -> 3; Effort.JUST_RIGHT -> 2; Effort.TOO_HARD -> 0 }

/** Reads a saved RIR back as the tap it came from (null when none was given). */
fun effortOf(rir: Int?): Effort? = when {
    rir == null -> null
    rir >= 3 -> Effort.TOO_EASY
    rir >= 1 -> Effort.JUST_RIGHT
    else -> Effort.TOO_HARD
}

/** What a calf set at one ladder level looks like for this kit. */
data class CalfSpec(
    val level: Int,
    val singleLeg: Boolean,
    /** Holding the kettlebell (false when none is owned: the level then stays bodyweight). */
    val loaded: Boolean,
    val repsLow: Int,
    val repsHigh: Int,
    val upSec: Int,
    val pauseSec: Int,
    val downSec: Int,
    /** Heels off a step or stair edge for full range (only when he owns a step). */
    val stepEdge: Boolean,
) {
    val slow: Boolean get() = downSec >= 4
    private val legs: Int get() = if (singleLeg) 2 else 1

    /** "single-leg with kettlebell, slow lowering". */
    val summary: String
        get() = buildList {
            add(if (singleLeg) (if (loaded) "single-leg with kettlebell" else "single-leg") else "two legs")
            if (slow) add("slow lowering")
            if (level >= 5) add(if (level == 6) "long pause" else "more reps")
            if (level == 6) add(if (stepEdge) "step edge" else "flat ground")
        }.joinToString(", ")

    /** "Level 4 of 6: single-leg with kettlebell, slow lowering". */
    val levelLine: String get() = "Level $level of ${CalfLadder.LEVELS}: $summary"

    val tempo: String
        get() = (if (singleLeg) "Each leg. " else "") + buildString {
            append("Up $upSec s")
            if (pauseSec > 0) append(", pause $pauseSec s")
            append(", down $downSec s")
        }

    /** Time for one set of [reps] (both legs when single-leg), rounded to 5 s. */
    fun setSec(reps: Int): Int = (((reps * (upSec + pauseSec + downSec) * legs + 5) + 4) / 5) * 5

    fun title(variant: CalfVariant?): String =
        (if (singleLeg) "Single-leg calf raise" else "Calf raise") + (variant?.let { " (${it.label})" } ?: "")

    fun cue(): String = buildList {
        add(if (singleLeg) "One hand on a wall, other foot off the floor." else "Both feet, a hand on a wall.")
        if (loaded) add("Kettlebell in the other hand.")
        if (stepEdge) add("Heels over a step edge.") else if (level == 6) add("Flat ground: full range you can.")
    }.joinToString(" ")
}

object CalfLadder {
    const val LEVELS = 6
    /** Gym calf work at or above this (kg) starts him at level 3, at [GYM_HEAVY_KG] at level 4. */
    const val GYM_START_KG = 40.0
    const val GYM_HEAVY_KG = 80.0
    /** A kettlebell heavier than this lets load rise before tempo. */
    const val LOAD_FIRST_KG = 12.0
    /** The load step and how far past the kit weight it may rise before the level goes up. */
    const val LOAD_STEP_KG = 2.5
    const val LOAD_ROOM_KG = 5.0
    /** HIGHER REPS aims this many reps above the top of the level's range. */
    const val STAMINA_EXTRA = 3
    const val REST_EASY_SEC = 30
    const val REST_HARD_EXTRA_SEC = 15

    private class Base(val singleLeg: Boolean, val loaded: Boolean, val lo: Int, val hi: Int, val up: Int, val pause: Int, val down: Int)

    private val table = listOf(
        Base(false, false, 15, 15, 2, 0, 3),   // L1 two-leg bodyweight 3 x 15
        Base(true, false, 12, 15, 2, 0, 3),    // L2 single-leg bodyweight 3 x 12-15
        Base(true, true, 12, 15, 2, 0, 3),     // L3 single-leg with the kettlebell 3 x 12-15
        Base(true, true, 10, 12, 1, 2, 4),     // L4 + 4 s lowering and 2 s pause, 3 x 10-12 (the heavy-slow style)
        Base(true, true, 15, 20, 1, 2, 4),     // L5 same at 3 x 15-20
        Base(true, true, 20, 25, 1, 3, 4),     // L6 3 x 20-25, pause 3 s, step edge if owned
    )

    fun clamp(level: Int) = level.coerceIn(1, LEVELS)

    fun spec(level: Int, kit: KneeCareKit = KneeCareKit()): CalfSpec {
        val l = clamp(level)
        val b = table[l - 1]
        return CalfSpec(l, b.singleLeg, b.loaded && kit.hasKettlebell, b.lo, b.hi, b.up, b.pause, b.down, stepEdge = l == LEVELS && kit.hasStep)
    }

    /** The reps aimed for in [mode]: HEAVY works up through the level's range, HIGHER REPS sits above its top. */
    fun targetReps(spec: CalfSpec, mode: CalfMode, lastTarget: Int? = null): Int =
        if (mode == CalfMode.STAMINA) spec.repsHigh + STAMINA_EXTRA else (lastTarget ?: spec.repsLow).coerceIn(spec.repsLow, spec.repsHigh)

    /** Load for a set at [spec]: the kettlebell when the level holds it, else bodyweight (0). */
    fun loadKg(spec: CalfSpec, kit: KneeCareKit): Double = if (spec.loaded) kit.kettlebellKg ?: 0.0 else 0.0

    /**
     * Where a user with no home ladder history starts, from his signals: gym calf raises at 40 kg or more (80 kg or more:
     * level 4) or a "too easy" report on the easy version mean single-leg with the kettlebell; a new user starts at level 1.
     */
    fun startLevel(records: List<SessionRecord>, reportedEasy: Boolean = false): Int {
        val gym = records.filter { it.type == SessionType.GYM }.flatMap { it.exercises }.filter { it.exerciseId == "calf" }
            .flatMap { it.sets }.maxOfOrNull { it.weightKg } ?: 0.0
        val fromGym = when { gym >= GYM_HEAVY_KG -> 4; gym >= GYM_START_KG -> 3; else -> 1 }
        return if (reportedEasy) max(fromGym, 3) else fromGym
    }

    /** True for a log made in HIGHER REPS mode (aimed above the top of its level's range). */
    fun isStamina(sets: List<LoggedSet>): Boolean {
        val lv = sets.firstNotNullOfOrNull { it.level } ?: return false
        return (sets.maxOfOrNull { it.targetReps } ?: 0) > table[clamp(lv) - 1].hi
    }

    /**
     * The level to start the NEXT session at, from the sets of one session (null for sets with no level, such as older
     * logs). A set that felt too easy moves its exercise up one level; all sets of one level at the top of its reps with
     * RIR 2 or more do the same; two too-hard sets in a row (RIR 0) drop one level. Never below 1 or above 6.
     */
    fun nextLevel(sets: List<LoggedSet>): Int? {
        val done = sets.filter { it.level != null }
        if (done.isEmpty()) return null
        var next = done.maxOf { clamp(it.level!!) + if ((it.rir ?: 0) >= 3) 1 else 0 }
        val top = done.maxOf { clamp(it.level!!) }
        val allOne = done.all { clamp(it.level!!) == top }
        if (allOne && !isStamina(done) && done.size >= 2 && done.all { it.reps >= table[top - 1].hi && (it.rir ?: -1) >= 2 }) next = max(next, top + 1)
        val hardPair = done.zipWithNext().lastOrNull { (a, b) -> a.rir == 0 && b.rir == 0 }
        if (hardPair != null) next = min(next, clamp(hardPair.second.level!!) - 1)
        return clamp(next)
    }

    /** The level the session began at (its first set's level), or null. */
    fun startedAt(sets: List<LoggedSet>): Int? = sets.firstNotNullOfOrNull { it.level }?.let { clamp(it) }

    /** True when the sets of this session take the next one up a level. */
    fun movedUp(sets: List<LoggedSet>): Boolean {
        val from = startedAt(sets) ?: return false
        return (nextLevel(sets) ?: from) > from
    }

    /** The rest after a calf set: [base] normally, [REST_EASY_SEC] when the set felt too easy, a little longer when too hard. */
    fun restSec(base: Int, effort: Effort?): Int = when (effort) {
        Effort.TOO_EASY -> min(base, REST_EASY_SEC)
        Effort.TOO_HARD -> base + REST_HARD_EXTRA_SEC
        else -> base
    }

    /** Normal rest between calf sets at [level] (a slow single-leg set earns a longer rest). */
    fun baseRestSec(level: Int, mode: CalfMode): Int = if (mode == CalfMode.STAMINA) 35 else if (level <= 3) 45 else 60

    /**
     * Applies a tap to the sets still to come: too easy adds the next lever (the next ladder level, at the top of the
     * ladder two more reps), just right keeps the level, too hard keeps it (one level down after two in a row). Returns the
     * (level, target reps) for a follower set of an entry done at [level] with [target] reps.
     */
    fun follower(level: Int, target: Int, effort: Effort?, previous: Effort?, kit: KneeCareKit, mode: CalfMode): Pair<Int, Int> {
        val spec = spec(level, kit)
        return when {
            effort == Effort.TOO_EASY && level < LEVELS -> {
                val up = spec(level + 1, kit)
                up.level to targetReps(up, mode)
            }
            effort == Effort.TOO_EASY -> level to (target + 2).coerceAtMost(spec.repsHigh + 8)
            effort == Effort.TOO_HARD && previous == Effort.TOO_HARD && level > 1 -> {
                val down = spec(level - 1, kit)
                down.level to targetReps(down, mode)
            }
            else -> level to target
        }
    }
}
