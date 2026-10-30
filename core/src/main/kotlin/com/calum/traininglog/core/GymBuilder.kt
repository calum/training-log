package com.calum.traininglog.core

import java.time.LocalDate

data class PlannedExercise(
    val exercise: Exercise,
    val prescription: Prescription,
    val warmups: List<LoggedSet>,
)

data class GymPlan(
    val dayLabel: String,
    val deload: Boolean,
    val exercises: List<PlannedExercise>,
    val dropped: List<Exercise>,
    val estimatedMinutes: Int,
)

object GymBuilder {
    const val TRAVEL_MIN_EACH_WAY = 10
    const val MIN_WORTHWHILE_LIFTING_MIN = 30

    internal val dayA = listOf("squat", "rdl", "calf", "vertical-pull", "horizontal-push")
    internal val dayB = listOf("deadlift", "squat", "calf", "horizontal-pull", "vertical-push")

    fun liftingMinutes(minutesAvailable: Int) = minutesAvailable - 2 * TRAVEL_MIN_EACH_WAY

    fun nextDayLabel(history: List<SessionRecord>): String =
        when (history.filter { it.type == SessionType.GYM }.maxByOrNull { it.date }?.dayLabel) {
            "A" -> "B"
            else -> "A"
        }

    /** Estimated minutes for an exercise: a set takes ~45 s plus its rest. */
    fun estimateMinutes(p: PlannedExercise): Double =
        p.prescription.sets * (p.exercise.restSec / 60.0 + 0.75) + p.warmups.size * 1.25

    /**
     * Build a gym session that fits [liftingMinutes], dropping accessories first.
     * [upperChoice] maps a movement to the exercise the user prefers (after swaps).
     */
    fun build(
        liftingMinutes: Int,
        dayLabel: String,
        records: List<SessionRecord>,
        today: LocalDate,
        deload: Boolean = false,
        upperChoice: Map<String, String> = emptyMap(),
        easedRestart: Boolean = false,
    ): GymPlan {
        val order = if (dayLabel == "A") dayA else dayB
        val lifts = order.map { key ->
            if (ExerciseLibrary.all.any { it.id == key }) ExerciseLibrary.get(key)
            else ExerciseLibrary.get(upperChoice[key] ?: ExerciseLibrary.firstFor(key).id)
        }
        val planned = lifts.map { ex -> plan(ex, records, today, deload, easedRestart) }.toMutableList()

        val dropped = mutableListOf<Exercise>()
        while (planned.size > 1 && planned.sumOf { estimateMinutes(it) } > liftingMinutes) {
            dropped += planned.removeLast().exercise
        }
        return GymPlan(dayLabel, deload, planned, dropped, planned.sumOf { estimateMinutes(it) }.toInt())
    }

    fun plan(ex: Exercise, records: List<SessionRecord>, today: LocalDate, deload: Boolean, easedRestart: Boolean = false): PlannedExercise {
        val history = history(ex.id, records)
        var rx = Progression.next(ex, history, today, easedRestart)
        if (deload) {
            rx = rx.copy(sets = Progression.deloadSets(rx.sets), note = "Deload week: fewer sets, same loads.")
        }
        val warmups = if (ex.warmup) Warmup.squatSets(rx.weightKg) else emptyList()
        return PlannedExercise(ex, rx, warmups)
    }

    fun history(exerciseId: String, records: List<SessionRecord>): List<HistoryEntry> =
        records.sortedByDescending { it.date }.mapNotNull { r ->
            r.exercises.firstOrNull { it.exerciseId == exerciseId && it.sets.isNotEmpty() }?.let { HistoryEntry(r.date, it) }
        }
}
