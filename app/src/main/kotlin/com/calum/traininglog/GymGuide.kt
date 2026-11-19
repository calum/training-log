package com.calum.traininglog

import com.calum.traininglog.core.LoggedSet

/** The one thing the guided gym screen shows right now. Always derived from [GymLive], never stored. */
internal sealed interface GymStep {
    /** "Next exercise" card with a Start button. */
    data class Intro(val ei: Int) : GymStep
    /** Warm-up set [wi] of exercise [ei]. */
    data class Warmup(val ei: Int, val wi: Int) : GymStep
    /** Working set [si] of exercise [ei]. */
    data class Work(val ei: Int, val si: Int) : GymStep
    /** Every set is done (or skipped): finish. */
    data object Finish : GymStep
}

/**
 * Pure rules for the guided gym mode. The guided screen drives the same [AppViewModel] functions as the list
 * ([AppViewModel.doneSet], [AppViewModel.adjustSet], ...), so a guided session saves exactly what a list session would.
 */
internal object GymGuide {
    /** An exercise is settled when all its sets are done or its rest was skipped (an exercise with no sets counts too). */
    fun settled(e: LiveExercise) = e.closed || e.sets.all { it.done }

    /** Index of the exercise being worked on: the first one not settled, or null when the session is complete. */
    fun currentExercise(g: GymLive): Int? = g.exercises.indexOfFirst { !settled(it) }.takeIf { it >= 0 }

    /** Has this exercise been started (Start pressed, or anything on it already ticked in the list)? */
    fun begun(e: LiveExercise) = e.started || e.sets.any { it.done } || e.warmups.any { it.done }

    fun step(g: GymLive): GymStep {
        val ei = currentExercise(g) ?: return GymStep.Finish
        val e = g.exercises[ei]
        if (!begun(e)) return GymStep.Intro(ei)
        if (e.sets.none { it.done }) {
            val wi = e.warmups.indexOfFirst { !it.done }
            if (wi >= 0) return GymStep.Warmup(ei, wi)
        }
        return GymStep.Work(ei, e.sets.indexOfFirst { !it.done })
    }

    /** "72.5 kg x 5", "bodyweight x 12" (the load of a set, then its reps). */
    fun setLine(kg: Double, reps: Int): String = "${loadText(kg)} x $reps"

    fun loadText(kg: Double): String = if (kg <= 0.0) "bodyweight" else "${fmt(kg)} kg"

    /** The "Next up" preview shown during rest: the step that follows now. */
    fun nextUp(g: GymLive): String = when (val s = step(g)) {
        is GymStep.Intro -> "Next exercise: ${g.exercises[s.ei].planned.exercise.name}"
        is GymStep.Warmup -> {
            val e = g.exercises[s.ei]
            "${e.planned.exercise.name} warm-up ${s.wi + 1} of ${e.warmups.size} · ${setLine(e.warmups[s.wi].kg, e.warmups[s.wi].reps)}"
        }
        is GymStep.Work -> {
            val e = g.exercises[s.ei]
            "${e.planned.exercise.name} set ${s.si + 1} · ${setLine(e.sets[s.si].kg, e.sets[s.si].reps)}"
        }
        GymStep.Finish -> "That was the last set. Finish the session when you are ready."
    }

    /** "Last time: 72.5 kg x 5, 5, 5", or one load per set when the loads differed. */
    fun lastTime(sets: List<LoggedSet>): String {
        val same = sets.map { it.weightKg }.distinct().size == 1
        return if (same) "Last time: ${loadText(sets.first().weightKg)} x ${sets.joinToString(", ") { it.reps.toString() }}"
        else "Last time: " + sets.joinToString(", ") { setLine(it.weightKg, it.reps) }
    }

    /** Sets done and sets in play (a skipped exercise's unfinished sets no longer count towards the total). */
    fun progress(g: GymLive): Pair<Int, Int> {
        val done = g.exercises.sumOf { e -> e.sets.count { it.done } }
        val total = g.exercises.sumOf { e -> if (e.closed) e.sets.count { it.done } else e.sets.size }
        return done to total
    }
}
