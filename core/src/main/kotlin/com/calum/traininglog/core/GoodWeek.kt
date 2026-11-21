package com.calum.traininglog.core

/**
 * What a "good week" is right now, in one value for the Home ring, its centre number and its headline.
 * While a race plan is on it is the plan's own sessions this week (never gym 2 / treadmill 3, which the plan does not
 * ask for); without one it is the weekly targets as before. See [Coach.goodWeek].
 *
 * [progress] is 0..1 toward the good week, [bonus] 0..1 for extra sessions beyond it. Both are clamped, never negative,
 * and nothing here ever reads as a miss.
 */
data class GoodWeek(
    val done: Int,
    val total: Int,
    val progress: Double,
    val bonus: Double,
    val fromPlan: Boolean,
) {
    val reached: Boolean get() = done >= total

    /** Positive framing only; never mentions misses. */
    fun headline(openDaysLeft: Int): String = headline(done, total, openDaysLeft)

    companion object {
        fun headline(done: Int, total: Int, openDaysLeft: Int): String {
            if (done >= total) return "Good week reached: $done of $total done. Anything more is a bonus."
            val days = when (openDaysLeft) {
                0 -> return "$done of $total done. Rest up, fresh week soon."
                1 -> "1 open day left"
                else -> "$openDaysLeft open days left"
            }
            return "$done of $total done, $days"
        }
    }
}
