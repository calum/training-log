package com.calum.traininglog.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class Prescription(
    val weightKg: Double,
    val sets: Int,
    val reps: Int,
    val note: String,
)

/** One past appearance of an exercise, newest first when passed in a list. */
data class HistoryEntry(val date: LocalDate, val log: ExerciseLog)

object Progression {
    private const val LIMIT_DROP = 0.925
    private const val RIR_TO_PROGRESS = 2

    fun next(
        exercise: Exercise,
        history: List<HistoryEntry>,
        today: LocalDate,
        easedRestart: Boolean = false,
        settings: UserSettings = UserSettings(),
    ): Prescription {
        val ex = settings.resolve(exercise)
        val last = history.firstOrNull()
            ?: return Prescription(ex.startKg, ex.sets, ex.repsLow, "First time: starting weight, adjust as needed.")
        val lastKg = last.log.sets.maxOf { it.weightKg }
        val gapDays = ChronoUnit.DAYS.between(last.date, today)

        if (gapDays >= settings.restartAfterDays || easedRestart) {
            val kg = roundTo(lastKg * settings.restartFactor, 2.5)
            val why = if (gapDays >= settings.restartAfterDays) "Back after ${gapDays / 7} weeks" else "Easing back in"
            return Prescription(kg, ex.sets, ex.repsLow, "$why: ${Math.round(settings.restartFactor * 100)}% of last working weight.")
        }

        return if (ex.role == Role.MAIN) mainNext(ex, history, lastKg) else accessoryNext(ex, last, lastKg)
    }

    private fun failed(log: ExerciseLog) = log.sets.any { it.reps < it.targetReps }

    private fun mainNext(ex: Exercise, history: List<HistoryEntry>, lastKg: Double): Prescription {
        val last = history.first().log
        if (!failed(last)) {
            val rir = last.sets.lastOrNull()?.rir
            return if (rir != null && rir >= RIR_TO_PROGRESS) {
                Prescription(lastKg + ex.incrementKg, ex.sets, ex.repsLow, "All reps hit with reps in reserve: add ${fmt(ex.incrementKg)} kg.")
            } else {
                Prescription(lastKg, ex.sets, ex.repsLow, "Repeat this weight until the last set feels manageable.")
            }
        }
        val failsInARow = history.takeWhile { failed(it.log) && it.log.sets.maxOf { s -> s.weightKg } == lastKg }.size
        if (failsInARow >= 2) {
            return Prescription(
                roundTo(lastKg * LIMIT_DROP, 2.5), max(1, ceil(ex.sets * 0.6).toInt()), ex.repsLow,
                "Two misses in a row: back-off and a lighter week.",
            )
        }
        return when (last.missReason) {
            MissReason.AT_LIMIT -> Prescription(roundTo(lastKg * LIMIT_DROP, 2.5), ex.sets, ex.repsLow, "At the limit last time: drop about 7.5% and rebuild.")
            else -> Prescription(lastKg, ex.sets, ex.repsLow, "Rough day last time: same weight, try again.")
        }
    }

    private fun accessoryNext(ex: Exercise, last: HistoryEntry, lastKg: Double): Prescription {
        val sets = last.log.sets
        if (sets.all { it.reps >= ex.repsHigh }) {
            return Prescription(lastKg + ex.incrementKg, ex.sets, ex.repsLow, "Top of the range on every set: add weight, back to ${ex.repsLow} reps.")
        }
        val target = min(ex.repsHigh, sets.minOf { it.reps } + 1).coerceAtLeast(ex.repsLow)
        return Prescription(lastKg, ex.sets, target, "Same weight, aim for $target reps.")
    }

    /** Deload suggested after ~6 weeks of consistent training with no recent deload. */
    fun deloadDue(sessionDates: List<LocalDate>, lastDeload: LocalDate?, today: LocalDate, afterWeeks: Int = 6, settings: UserSettings = UserSettings()): Boolean {
        val recent = sessionDates.filter { lastDeload == null || it.isAfter(lastDeload) }.sorted()
        if (recent.isEmpty()) return false
        // A gap of 2+ weeks resets the clock: only count the current consistent run.
        var runStart = recent.last()
        for (d in recent.asReversed()) {
            if (ChronoUnit.DAYS.between(d, runStart) > 14) break
            runStart = d
        }
        return ChronoUnit.WEEKS.between(runStart, today) >= (if (afterWeeks != 6) afterWeeks else settings.deloadWeeks)
    }

    fun deloadSets(sets: Int): Int = max(1, ceil(sets * 0.6).toInt())

    private fun fmt(x: Double) = if (x % 1.0 == 0.0) x.toInt().toString() else x.toString()
}
