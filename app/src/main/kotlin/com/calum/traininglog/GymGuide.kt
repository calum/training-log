package com.calum.traininglog

import com.calum.traininglog.core.ExerciseLibrary
import com.calum.traininglog.core.GymBuilder
import com.calum.traininglog.core.LoggedSet
import com.calum.traininglog.core.Exercise

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

    /** One line of the overview: "1 Squat 2 x 5 · 77.5 kg" is [line]; [name] and [detail] are its two halves. */
    data class OverviewRow(val number: Int, val name: String, val detail: String, val optional: Boolean = false) {
        val line: String get() = "$number $name $detail"
    }

    /** The "Today's session" card: everything the session holds, from the same sets the guided steps use. */
    data class Overview(
        val rows: List<OverviewRow>,
        val minutes: Int,
        /** One line when sets were trimmed (or exercises left out) to fit the time; null otherwise. */
        val trimNote: String?,
        /** "Then: hip prehab (about 10 min)" only when a prehab prompt follows the lifting; null otherwise. */
        val prehabLine: String?,
        /** The "How much time do you have for lifting?" control; null once a set is logged. */
        val time: TimeControl? = null,
    )

    /** State of the time control: chip values are LIFTING minutes. */
    data class TimeControl(
        val windows: List<Int>,
        val selected: Int,
        /** The chip that is the plan's own window (planned sessions only), else null. */
        val plannedWindow: Int?,
        /** Minutes of hip prehab the plan asked for (0: no prehab switch). */
        val prehabMinutes: Int,
        val prehabOn: Boolean,
        /** "Added: Machine chest press" after a change, else null. */
        val change: String?,
    )

    val TIME_CHIPS = listOf(20, 25, 30, 40, 50, 60)

    /** The time can be changed (and the session rebuilt) only while no set, warm-up or exercise has been started or closed. */
    fun canRetime(g: GymLive): Boolean =
        g.exercises.none { begun(it) || it.closed } && g.rest == null && !g.prehabOffered && !g.finisherOffered

    fun timeControl(g: GymLive): TimeControl? {
        if (!canRetime(g)) return null
        val on = g.prehabMinutes > 0
        val planned = if (g.fromPlan) g.startWindow + if (g.prehabPlanned > 0 && !on) g.prehabPlanned else 0 else null
        val windows = (TIME_CHIPS + g.windowMinutes + listOfNotNull(planned)).distinct().sorted()
        return TimeControl(windows, g.windowMinutes, planned, g.prehabPlanned, on, g.windowChange)
    }

    fun overview(g: GymLive): Overview {
        val rows = g.exercises.mapIndexed { i, e ->
            val first = e.sets.firstOrNull()
            val detail = if (first == null) "no sets" else "${e.sets.size} x ${first.target} · ${loadText(first.kg)}"
            OverviewRow(i + 1, e.planned.exercise.name, detail, optional = e.planned.exercise.id in g.plan.optionalIds)
        }
        val trimmed = g.plan.exercises.filter { GymBuilder.TRIM_NOTE in it.prescription.note }
        val notes = listOfNotNull(
            trimmed.takeIf { it.isNotEmpty() }?.let { "Trimmed to fit: ${it.joinToString(", ") { e -> "${e.exercise.name} ${e.prescription.sets} sets" }}." },
            g.plan.dropped.takeIf { it.isNotEmpty() }?.let { "Left out to fit your time: ${it.joinToString(", ") { d -> d.name }}." },
        )
        return Overview(
            rows, g.plan.estimatedMinutes, notes.joinToString(" ").ifEmpty { null },
            if (g.prehabMinutes > 0) "Then: hip prehab (about ${g.prehabMinutes} min)" else null,
            time = timeControl(g),
        )
    }

    /** True while the guided screen should show "Today's session" instead of the first exercise. */
    fun showOverview(g: GymLive): Boolean =
        !g.overviewSeen && g.rest == null && g.exercises.isNotEmpty() && g.exercises.none { begun(it) || it.closed }

    /** Short label for a swap chip: "Standing" / "Seated" for the calf raises, else the exercise name. */
    fun variantLabel(ex: Exercise): String = when (ex.id) {
        ExerciseLibrary.calf.id -> "Standing"
        ExerciseLibrary.calfSeated.id -> "Seated"
        else -> ex.name
    }

    /** The exercise itself plus its swap options, in library order (so "Standing | Seated" never changes places). */
    fun variants(current: Exercise, options: List<Exercise>): List<Exercise> =
        (options + current).sortedBy { ExerciseLibrary.all.indexOfFirst { l -> l.id == it.id }.let { i -> if (i < 0) Int.MAX_VALUE else i } }
}
