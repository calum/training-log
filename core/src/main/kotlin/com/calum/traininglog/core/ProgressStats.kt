package com.calum.traininglog.core

import java.time.LocalDate

/** One lift's real history: weekly top working weight (and an Epley 1RM estimate), carried through quiet weeks. */
data class LiftProgress(
    val exerciseId: String,
    val name: String,
    /** Top working weight per week of the window; null only before the lift was first logged. Quiet weeks repeat the last value. */
    val topKg: List<Double?>,
    /** Epley estimated 1-rep max per week (an ESTIMATE, not a lifted weight), same carry-forward rule. */
    val e1rmKg: List<Double?>,
    val bestEverKg: Double,
    val firstKg: Double,
    val firstDate: LocalDate,
    val lastKg: Double,
    val lastDate: LocalDate,
    val sessions: Int,
) {
    /** Latest top weight minus the first logged weight. Can be zero or negative after an easing-back-in phase; the UI shows only gains. */
    val changeKg: Double get() = lastKg - firstKg
}

/** Counts of what was done in one Mon-Sun week. Never a target, never a "missed" flag. */
data class WeekDone(
    val weekStart: LocalDate,
    val gym: Int,
    val treadmill: Int,
    val other: Int,
    val treadmillMinutes: Int,
    val minutes: Int,
) {
    val sessions: Int get() = gym + treadmill + other
}

data class ProgressTotals(
    val sessions: Int,
    val minutes: Int,
    val volumeKg: Double,
    val treadmillMinutes: Int,
) {
    val hours: Double get() = minutes / 60.0
    val tonnes: Double get() = volumeKg / 1000.0
}

data class ProgressData(
    val weekStarts: List<LocalDate>,
    val lifts: List<LiftProgress>,
    /** Names of free-text exercises logged (listed, never charted). */
    val customExercises: List<String>,
    val weekly: List<WeekDone>,
    /** Treadmill styles tried inside the window with counts (planned incline/speed per segment is not stored, so no intensity trend). */
    val stylesTried: Map<TreadmillStyle, Int>,
    /** All-time totals up to today. */
    val totals: ProgressTotals,
    /** Totals inside the selected window. */
    val windowTotals: ProgressTotals,
    val prs: List<PersonalRecord>,
    val firstSessionDate: LocalDate?,
) {
    val isEmpty: Boolean get() = totals.sessions == 0
    fun lift(id: String): LiftProgress? = lifts.firstOrNull { it.exerciseId == id }
}

object ProgressStats {
    /** Epley estimate of a one-rep max. */
    fun e1rm(weightKg: Double, reps: Int): Double = if (reps <= 1) weightKg else weightKg * (1.0 + reps / 30.0)

    fun weekStart(d: LocalDate): LocalDate = Stats.weekOf(d).start

    fun build(records: List<SessionRecord>, today: LocalDate, weeks: Int = 12, settings: UserSettings = UserSettings()): ProgressData {
        val n = weeks.coerceIn(1, 104)
        val recs = records.filter { it.date <= today }.sortedBy { it.date }
        val thisWeek = weekStart(today)
        val starts = (0 until n).map { thisWeek.minusWeeks((n - 1 - it).toLong()) }
        val windowStart = starts.first()

        val lifts = ExerciseLibrary.all.mapNotNull { ex ->
            val logs = recs.mapNotNull { r ->
                val sets = r.exercises.filter { it.exerciseId == ex.id }.flatMap { it.sets }
                    .filter { it.reps > 0 && it.weightKg > 0.0 }
                if (sets.isEmpty()) null else r to sets
            }
            if (logs.isEmpty()) return@mapNotNull null
            val (firstRec, firstSets) = logs.first()
            val (lastRec, lastSets) = logs.last()
            val byWeek = logs.groupBy { weekStart(it.first.date) }
            var carryTop: Double? = null
            var carryE: Double? = null
            // Seed with the last value from before the window.
            logs.lastOrNull { weekStart(it.first.date) < windowStart }?.let { (_, s) ->
                carryTop = s.maxOf { it.weightKg }
                carryE = s.maxOf { e1rm(it.weightKg, it.reps) }
            }
            val top = ArrayList<Double?>(n)
            val est = ArrayList<Double?>(n)
            for (ws in starts) {
                byWeek[ws]?.let { wk ->
                    val sets = wk.flatMap { it.second }
                    carryTop = sets.maxOf { it.weightKg }
                    carryE = sets.maxOf { e1rm(it.weightKg, it.reps) }
                }
                top += carryTop
                est += carryE
            }
            LiftProgress(
                exerciseId = ex.id, name = ex.name, topKg = top, e1rmKg = est,
                bestEverKg = logs.maxOf { (_, s) -> s.maxOf { it.weightKg } },
                firstKg = firstSets.maxOf { it.weightKg }, firstDate = firstRec.date,
                lastKg = lastSets.maxOf { it.weightKg }, lastDate = lastRec.date,
                sessions = logs.size,
            )
        }
        val customs = recs.flatMap { it.exercises }.filter { ExerciseLibrary.isCustom(it.exerciseId) }
            .map { ExerciseLibrary.nameOf(it.exerciseId, it.customName) }.distinct()

        val weekly = starts.map { ws ->
            val inWeek = recs.filter { weekStart(it.date) == ws }
            WeekDone(
                weekStart = ws,
                gym = inWeek.count { it.type == SessionType.GYM },
                treadmill = inWeek.count { it.type == SessionType.TREADMILL },
                other = inWeek.count { it.type == SessionType.CORE || it.type == SessionType.HIKE },
                treadmillMinutes = inWeek.filter { it.type == SessionType.TREADMILL }.sumOf { treadmillMin(it) },
                minutes = inWeek.sumOf { it.minutesActual },
            )
        }
        val inWindow = recs.filter { it.date >= windowStart }
        val styles = inWindow.filter { it.type == SessionType.TREADMILL }.mapNotNull { it.treadmill?.style }
            .groupingBy { it }.eachCount()

        fun totals(rs: List<SessionRecord>) = ProgressTotals(
            sessions = rs.size,
            minutes = rs.sumOf { it.minutesActual },
            volumeKg = rs.sumOf { r -> r.exercises.sumOf { e -> e.sets.filter { it.reps > 0 }.sumOf { it.weightKg * it.reps } } },
            treadmillMinutes = rs.filter { it.type == SessionType.TREADMILL }.sumOf { treadmillMin(it) },
        )

        return ProgressData(
            weekStarts = starts, lifts = lifts, customExercises = customs, weekly = weekly, stylesTried = styles,
            totals = totals(recs), windowTotals = totals(inWindow),
            prs = Rewards.summary(recs, today, settings).prs.sortedByDescending { it.date },
            firstSessionDate = recs.firstOrNull()?.date,
        )
    }

    private fun treadmillMin(r: SessionRecord): Int = r.treadmill?.let { it.completedSec / 60 } ?: r.minutesActual
}
