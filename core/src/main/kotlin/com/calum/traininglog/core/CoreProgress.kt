package com.calum.traininglog.core

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/*
 * Progression for the guided mat routine. The idea is plain double progression, kept gentle:
 *
 *   - Every exercise remembers the work seconds you completed last time (and which variant, if it has an easier or
 *     harder one) in [Profile.coreProgress].
 *   - Next time, if you finished it (not skipped) and it felt fine, the hold gets +5 s, up to a 60 s cap. At the cap a
 *     harder variant takes over at 30 s (plank from the knees -> plank -> long-lever plank).
 *   - If you skipped it, or called the session too hard, it steps back: -5 s, or an easier variant at 45 s.
 *   - A rough or ill day, a session ended early, or a long break (4+ weeks) just repeats what you did.
 *
 * Nothing here punishes: a step back is worded as "a touch easier today".
 */

/** What happens to an exercise's target next time. */
@Serializable
enum class CoreOutcome { PROGRESS, HOLD, BACK }

/** What was last done for one mat exercise: the seconds, the variant level (0 = the base move) and what to do about it. */
@Serializable
data class CoreProgressEntry(
    /** Work seconds of the step last time (0 = nothing recorded). */
    val workSec: Int = 0,
    /** Variant level done last time: 0 = the base exercise, negative = easier, positive = harder. */
    val level: Int = 0,
    @Serializable(with = LocalDateSerializer::class) val lastDone: LocalDate? = null,
    /** How last time went, which decides this time's target. */
    val outcome: CoreOutcome = CoreOutcome.HOLD,
)

/** One harder (or easier) version of a hold. [level] 0 is the base exercise in [CoreLibrary]. */
data class CoreVariant(val level: Int, val name: String, val cue: String)

object CoreVariants {
    private val chains: Map<String, List<CoreVariant>> = mapOf(
        "front_plank" to listOf(
            CoreVariant(-1, "Plank from the knees", "Forearms down, knees on the mat, one straight line from head to knees. Squeeze glutes, breathe."),
            CoreVariant(1, "Long-lever plank", "Forearms a hand-width further forward than usual, body long, ribs down. Squeeze glutes and keep breathing."),
        ),
        "side_plank" to listOf(
            CoreVariant(-1, "Side plank, knee down", "Elbow under shoulder, bottom knee on the mat, hips high in a line from head to knee."),
            CoreVariant(1, "Side plank, top leg up", "Hold the side plank with the top leg lifted to hip height. Keep the hips high and level."),
        ),
        "hollow_tuck" to listOf(
            CoreVariant(1, "Hollow hold, one leg long", "Back pressed down, shoulders lifted, one knee tucked and the other leg long and low. Switch halfway."),
        ),
        "bear_hold" to listOf(
            CoreVariant(1, "Bear hold, alternate hand lifts", "Knees hover a hand-width off the floor. Lift one hand briefly, switch, no rocking."),
        ),
        "cobra_hold" to listOf(
            CoreVariant(1, "Cobra hold, arms back", "Face down, chest lifted using your back, arms reaching back by your hips. Long neck."),
        ),
    )

    fun has(id: String) = id in chains

    fun minLevel(id: String): Int = chains[id]?.minOf { it.level }?.coerceAtMost(0) ?: 0
    fun maxLevel(id: String): Int = chains[id]?.maxOf { it.level }?.coerceAtLeast(0) ?: 0

    /** The variant at [level], or null for level 0 (the base exercise) and unknown levels. */
    fun variant(id: String, level: Int): CoreVariant? = if (level == 0) null else chains[id]?.firstOrNull { it.level == level }

    /** Every variant name, mapped to its base exercise id (so a saved "Core: Long-lever plank" note still reads back). */
    val namesToIds: Map<String, String> = chains.flatMap { (id, list) -> list.map { it.name to id } }.toMap()
}

/** One mat exercise's result in a finished (or ended) session. */
data class CoreWorkResult(
    val exerciseId: String,
    val sec: Int,
    val level: Int,
    /** Every step of it was done, none skipped. */
    val completed: Boolean,
    /** At least one step of it was skipped. */
    val skipped: Boolean,
)

/** A line for the setup card ("Progress: ...") and the finish card ("You progressed: ..."). */
data class CoreProgressNote(val exerciseId: String, val text: String, val up: Boolean)

object CoreProgression {
    const val STEP_SEC = 5
    const val MAX_SEC = 60
    const val MIN_SEC = 20
    /** The first target of a harder variant, and of an easier one. */
    const val HARDER_START_SEC = 30
    const val EASIER_START_SEC = 45
    const val BREAK_DAYS = 28

    data class Target(val sec: Int, val level: Int)

    /** What last time means for next time. A skipped exercise or "too hard" steps back; a rough day or an early end just repeats. */
    fun outcome(completed: Boolean, skipped: Boolean, effort: Effort?, feeling: Feeling?): CoreOutcome = when {
        skipped -> CoreOutcome.BACK
        !completed -> CoreOutcome.HOLD
        effort == Effort.TOO_HARD -> CoreOutcome.BACK
        feeling == Feeling.ROUGH || feeling == Feeling.ILL -> CoreOutcome.HOLD
        else -> CoreOutcome.PROGRESS
    }

    /**
     * The target for [id] now, from what was recorded. [capSec] is the longest hold the routine's work/rest cycle allows (it
     * is also held to [MAX_SEC]). A knee-sensitive routine never goes to an easier variant (the knees-down ones).
     */
    fun target(id: String, entry: CoreProgressEntry, capSec: Int, kneeSensitive: Boolean = false): Target {
        val cap = capSec.coerceIn(MIN_SEC, MAX_SEC)
        val minL = if (kneeSensitive) 0 else CoreVariants.minLevel(id)
        val maxL = CoreVariants.maxLevel(id)
        val level = entry.level.coerceIn(minL, maxL)
        val base = entry.workSec.coerceIn(MIN_SEC, cap)
        return when (entry.outcome) {
            CoreOutcome.HOLD -> Target(base, level)
            CoreOutcome.PROGRESS -> when {
                base + STEP_SEC <= cap -> Target(base + STEP_SEC, level)
                level < maxL -> Target(minOf(HARDER_START_SEC, cap), level + 1)
                else -> Target(base, level)
            }
            CoreOutcome.BACK -> when {
                base - STEP_SEC >= MIN_SEC -> Target(base - STEP_SEC, level)
                level > minL -> Target(minOf(EASIER_START_SEC, cap), level - 1)
                else -> Target(MIN_SEC, level)
            }
        }
    }

    /** The entry to store after [result]: what was done, and how it went. A long break never jumps straight to progress. */
    fun entryAfter(prev: CoreProgressEntry?, result: CoreWorkResult, effort: Effort?, feeling: Feeling?, today: LocalDate): CoreProgressEntry {
        var o = outcome(result.completed, result.skipped, effort, feeling)
        val gap = prev?.lastDone?.let { ChronoUnit.DAYS.between(it, today) } ?: 0L
        if (o == CoreOutcome.PROGRESS && gap >= BREAK_DAYS) o = CoreOutcome.HOLD
        return CoreProgressEntry(result.sec, result.level, today, o)
    }

    /** The updated map after a session. Exercises that were only partly done are left as they were. */
    fun update(
        map: Map<String, CoreProgressEntry>, results: List<CoreWorkResult>, effort: Effort?, feeling: Feeling?, today: LocalDate,
    ): Map<String, CoreProgressEntry> {
        var next = map
        results.filter { it.completed || it.skipped }.forEach { r ->
            next = next + (r.exerciseId to entryAfter(map[r.exerciseId], r, effort, feeling, today))
        }
        return next
    }

    private fun nameOf(id: String, level: Int): String =
        CoreVariants.variant(id, level)?.name ?: CoreLibrary.get(id)?.name ?: id

    /** The setup-card line for a target that differs from last time, or null when it is the same. */
    fun note(id: String, entry: CoreProgressEntry, t: Target): CoreProgressNote? {
        if (entry.workSec <= 0) return null
        val lastName = nameOf(id, entry.level)
        val name = nameOf(id, t.level)
        return when {
            t.level > entry.level -> CoreProgressNote(id, "$lastName to $name, ${t.sec} s", up = true)
            t.level < entry.level -> CoreProgressNote(id, "$lastName to $name, ${t.sec} s", up = false)
            t.sec > entry.workSec -> CoreProgressNote(id, "$name ${entry.workSec} s -> ${t.sec} s", up = true)
            t.sec < entry.workSec -> CoreProgressNote(id, "$name ${entry.workSec} s -> ${t.sec} s", up = false)
            else -> null
        }
    }
}

/** Work results of the run so far, one per mat exercise that was reached. Exercises not reached yet are left out. */
fun CoreRun.workResults(): List<CoreWorkResult> {
    val byEx = steps.withIndex().filter { it.value.kind == CoreKind.WORK && it.value.exerciseId != null }.groupBy { it.value.exerciseId!! }
    return byEx.mapNotNull { (id, list) ->
        val passed = list.filter { it.index < index }
        if (passed.isEmpty()) return@mapNotNull null
        val skippedAny = list.any { it.index in skipped }
        val first = list.first().value
        CoreWorkResult(id, first.seconds, first.level, completed = passed.size == list.size && !skippedAny, skipped = skippedAny)
    }
}
