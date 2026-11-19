package com.calum.traininglog.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/** The offer shown on the hike setup card: try [minutes] next, and why in plain words. Only ever an offer. */
data class HikeSuggestion(val minutes: Int, val basedOnMinutes: Int, val text: String)

/** Helpers for guided hikes and walks: the saved note and the next-length offer. */
object Hike {
    const val MAX_MINUTES = 480
    private const val STEP_FRACTION = 0.10
    private const val ROUND_TO = 5
    private const val EASE_AFTER_DAYS = 42

    /** "8.2 km, 450 m climb. Lovely views" (distance and climb only when entered). */
    fun note(distanceKm: Double?, climbM: Int?, extra: String? = null): String {
        val parts = mutableListOf<String>()
        distanceKm?.takeIf { it > 0.0 }?.let { parts += "${trimKm(it)} km" }
        climbM?.takeIf { it > 0 }?.let { parts += "$it m climb" }
        val head = parts.joinToString(", ")
        val tail = extra?.trim().orEmpty()
        return listOf(head, tail).filter { it.isNotEmpty() }.joinToString(". ")
    }

    private fun trimKm(x: Double) = if (x % 1.0 == 0.0) x.toInt().toString() else String.format(java.util.Locale.ENGLISH, "%.1f", x)

    private fun ceilTo5(x: Double) = (ceil(x / ROUND_TO) * ROUND_TO).toInt()
    private fun roundTo5(x: Double) = (Math.round(x / ROUND_TO) * ROUND_TO).toInt()

    /**
     * The next long-walk length to try, from the last hike or walk: about 10% more (90 min -> 100 min), never more than that
     * rounded up to 5 minutes, and never above [MAX_MINUTES]. After a rough or ill day, a sore knee (4+), it offers the same
     * length instead, and after a long break a little less. Null with no history.
     */
    fun suggest(records: List<SessionRecord>, today: LocalDate): HikeSuggestion? {
        val last = records.filter { it.type == SessionType.HIKE }.maxByOrNull { it.date } ?: return null
        val mins = last.minutesActual
        if (mins <= 0) return null
        val gap = ChronoUnit.DAYS.between(last.date, today)
        val sore = (last.kneePain ?: 0) >= 4
        val poor = last.feeling == Feeling.ROUGH || last.feeling == Feeling.ILL
        return when {
            gap >= EASE_AFTER_DAYS -> {
                val m = roundTo5(mins * 0.8).coerceAtLeast(ROUND_TO)
                HikeSuggestion(m, mins, "Last one was $mins min, a while ago. Ease back in with about $m min.")
            }
            sore || poor -> HikeSuggestion(mins, mins, "Last one was $mins min and not a great day. Same again, about $mins min, no pressure.")
            else -> {
                val cap = ceilTo5(mins * (1 + STEP_FRACTION))
                val m = roundTo5(mins * (1 + STEP_FRACTION)).coerceAtLeast(mins + ROUND_TO).coerceAtMost(cap).coerceAtMost(MAX_MINUTES)
                if (m <= mins) HikeSuggestion(mins, mins, "Last one was $mins min. Same again is a fine goal.")
                else HikeSuggestion(m, mins, "Last one was $mins min. If you fancy it, try about $m min (an offer, not a target).")
            }
        }
    }
}
