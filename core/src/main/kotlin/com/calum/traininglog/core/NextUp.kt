package com.calum.traininglog.core

import java.time.LocalDate

/**
 * Text for the Home "Next up" card: what today's suggested session will most likely be.
 * Read-only: uses the builder and planner's own choosers and never logs or stores anything.
 * Wording says "likely" because the real session adapts to time, energy and what you swap.
 */
object NextUp {
    /** e.g. "pull + push" or "vertical + horizontal pull" for the two movements the builder would pick. */
    fun upperLabel(movements: List<String>): String {
        fun kind(m: String) = if (m.endsWith("pull")) "pull" else "push"
        fun dir(m: String) = m.substringBefore('-')
        return when {
            movements.isEmpty() -> ""
            movements.size == 1 -> "${dir(movements[0])} ${kind(movements[0])}"
            kind(movements[0]) == kind(movements[1]) -> "${dir(movements[0])} + ${dir(movements[1])} ${kind(movements[0])}"
            else -> "${kind(movements[0])} + ${kind(movements[1])}"
        }
    }

    fun gymText(records: List<SessionRecord>): String {
        val upper = upperLabel(GymBuilder.nextUpperMovements(records, 2))
        return "${GymBuilder.nextDayLabel(records)} day, likely with $upper. Strength builds the base."
    }

    fun treadmillText(records: List<SessionRecord>, today: LocalDate): String {
        val last = records.filter { it.type == SessionType.TREADMILL && it.treadmill != null && !it.date.isAfter(today) }
            .maxByOrNull { it.date }?.treadmill?.style
        val minutes = Projection.forecast(records, today, weeks = 1).treadmill.minutesPerSession
        val style = TreadmillPlanner.chooseStyle(minutes, last, exciting = false, seed = today.toEpochDay())
        return "Treadmill day, likely ${style.label.lowercase()} for about $minutes min. Easy miles, big smiles."
    }

    fun describe(records: List<SessionRecord>, view: WeekView, today: LocalDate): String? {
        val t = view.days.firstOrNull { it.date == today }?.suggested ?: return null
        return when (t) {
            SessionType.GYM -> gymText(records)
            SessionType.TREADMILL -> treadmillText(records, today)
            SessionType.CORE -> "Core day."
            SessionType.HIKE -> "Hike day."
        }
    }
}
