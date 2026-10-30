package com.calum.traininglog.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What the first sessions back should look like. Inactive when [active] is false (all other fields neutral). */
data class RebaselineStatus(
    val active: Boolean,
    /** 1..3 while active; 0 when not active. */
    val stage: Int = 0,
    /** Target share of the pre-break working weight (informational for stage 2 and 3). */
    val weightFactor: Double = 1.0,
    /** Added to the normal set count (stage 1: -1). */
    val setsDelta: Int = 0,
    /** Reps to leave in reserve on every set. */
    val targetRir: Int = 2,
    val headline: String = "",
    val note: String = "",
) {
    companion object {
        val OFF = RebaselineStatus(active = false)
    }
}

/**
 * Easing back in after time off (no guilt: the break was real life, this is just a ramp).
 *
 * Rules, in terms of "sessions since the break":
 * - A break starts when the last gym session is [BREAK_DAYS] (21) or more days ago, or on any earlier
 *   gap of 21+ days between two gym sessions. The very start of history counts as a break too, so a
 *   first-ever user (placeholder start weights) also gets three easing-in sessions.
 * - The re-baseline lasts for the first [SESSIONS] (3) gym sessions after the latest break; the stage
 *   is the number of sessions logged since the break, plus one.
 * - Stage 1 "test the water": about 90% of the last working weight (Progression already applies its
 *   90% restart for a 21+ day gap, so GymBuilder never applies it twice; only exercises with no
 *   history use [RebaselineStatus.weightFactor] on the start weight), one set fewer, stop every set
 *   with 3+ reps in reserve, and no deload logic.
 * - Stage 2: 90-95% (normal progression from the stage 1 weight lands there), normal sets, RIR 2-3.
 * - Stage 3: normal prescriptions; normal progression resumes afterwards.
 *
 * Progression is untouched: the lower easing-in weight is simply the last working weight, so hitting
 * the reps with reps in reserve progresses from it as usual (no penalty for having eased in).
 */
object Rebaseline {
    const val BREAK_DAYS = 21L
    const val SESSIONS = 3

    fun status(records: List<SessionRecord>, today: LocalDate): RebaselineStatus {
        val dates = records.filter { it.type == SessionType.GYM }.map { it.date }.sorted()
        // Index of the first session after the latest break (0 = start of history).
        var start = 0
        for (i in 1 until dates.size) {
            if (ChronoUnit.DAYS.between(dates[i - 1], dates[i]) >= BREAK_DAYS) start = i
        }
        val trailingBreak = dates.isNotEmpty() && ChronoUnit.DAYS.between(dates.last(), today) >= BREAK_DAYS
        val sinceBreak = if (trailingBreak) 0 else dates.size - start
        if (sinceBreak >= SESSIONS) return RebaselineStatus.OFF
        val stage = sinceBreak + 1
        val head = "Welcome back: session $stage of $SESSIONS easing in"
        return when (stage) {
            1 -> RebaselineStatus(
                true, 1, 0.9, -1, 3, head,
                "Test the water: lighter weights, one set fewer, and stop every set with 3 or more reps in reserve.",
            )
            2 -> RebaselineStatus(
                true, 2, 0.95, 0, 2, head,
                "Building back: normal sets, finishing each with 2 to 3 reps in reserve.",
            )
            else -> RebaselineStatus(
                true, 3, 1.0, 0, 2, head,
                "Nearly there: normal prescriptions today, then normal progression resumes.",
            )
        }
    }
}
