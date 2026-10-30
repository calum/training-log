package com.calum.traininglog.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class WeekStats(val gym: Int, val treadmill: Int, val other: Int)

object Stats {
    fun weekOf(date: LocalDate): ClosedRange<LocalDate> {
        val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return start..start.plusDays(6)
    }

    fun week(records: List<SessionRecord>, date: LocalDate): WeekStats {
        val r = weekOf(date)
        val inWeek = records.filter { it.date in r }
        return WeekStats(
            gym = inWeek.count { it.type == SessionType.GYM },
            treadmill = inWeek.count { it.type == SessionType.TREADMILL },
            other = inWeek.count { it.type == SessionType.CORE || it.type == SessionType.HIKE },
        )
    }

    fun daysSince(type: SessionType, records: List<SessionRecord>, today: LocalDate): Int? =
        records.filter { it.type == type }.maxOfOrNull { it.date }?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today).toInt() }
}

/** Plain, compact text meant to be pasted into Claude. Includes private fields; never send this to the public repo. */
object Export {
    fun summary(records: List<SessionRecord>, from: LocalDate, to: LocalDate): String {
        val rows = records.filter { it.date in from..to }.sortedBy { it.date }
        val sb = StringBuilder()
        sb.appendLine("TRAINING EXPORT $from to $to")
        sb.appendLine("Sessions done: ${rows.size} (gym ${rows.count { it.type == SessionType.GYM }}, treadmill ${rows.count { it.type == SessionType.TREADMILL }}, other ${rows.count { it.type == SessionType.CORE || it.type == SessionType.HIKE }})")
        if (rows.isEmpty()) sb.appendLine("No sessions in this period.")
        for (r in rows) {
            sb.appendLine()
            sb.append("${r.date} ${r.type.name.lowercase()}")
            r.dayLabel?.let { sb.append(" day $it") }
            if (r.deload) sb.append(" (deload)")
            sb.append(", ${r.minutesActual}/${r.minutesAvailable} min, felt ${r.feeling.name.lowercase()}")
            r.kneePain?.let { sb.append(", knee $it/10") }
            sb.appendLine()
            for (e in r.exercises) sb.appendLine("  " + exerciseLine(e))
            r.treadmill?.let { t ->
                val done = t.completedSec / 60
                val planned = t.plannedSec / 60
                val short = if (t.completedSec < t.plannedSec) " (shortened)" else ""
                sb.appendLine("  ${t.style.label}: $done of $planned min$short${t.effort?.let { ", effort ${it.name.lowercase().replace('_', ' ')}" } ?: ""}")
            }
            r.note?.let { sb.appendLine("  note: $it") }
        }
        return sb.toString().trimEnd()
    }

    private fun exerciseLine(e: ExerciseLog): String {
        val name = ExerciseLibrary.get(e.exerciseId).name
        val sets = e.sets
        val sameWeight = sets.map { it.weightKg }.distinct().size == 1
        val body = if (sameWeight) "${num(sets[0].weightKg)}kg x ${sets.joinToString("/") { it.reps.toString() }}"
        else sets.joinToString(", ") { "${num(it.weightKg)}x${it.reps}" }
        val rir = sets.lastOrNull()?.rir?.let { ", last set RIR $it" } ?: ""
        val missed = if (sets.any { it.reps < it.targetReps }) ", MISSED reps" + (e.missReason?.let { " (${it.name.lowercase().replace('_', ' ')})" } ?: "") else ""
        return "$name $body$rir$missed"
    }

    private fun num(x: Double) = if (x % 1.0 == 0.0) x.toInt().toString() else x.toString()
}
