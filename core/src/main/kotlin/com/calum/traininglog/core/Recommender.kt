package com.calum.traininglog.core

data class RecommendationInput(
    val minutes: Int,
    val feeling: Feeling,
    val kneePain: Int? = null,
    val nearGym: Boolean = true,
    val gymDoneThisWeek: Int = 0,
    val treadmillDoneThisWeek: Int = 0,
    val daysSinceGym: Int? = null,
    val lastTreadmillStyle: TreadmillStyle? = null,
    val restDay: Boolean = false,
    val settings: UserSettings = UserSettings(),
)

data class SessionOption(
    val type: SessionType?,
    val minutes: Int,
    val label: String,
    val style: TreadmillStyle? = null,
)

data class Recommendation(val primary: SessionOption, val alternative: SessionOption?, val reason: String)

object Recommender {
    const val GYM_TARGET = 2
    const val TREADMILL_TARGET = 3
    const val MIN_SESSION = 20
    const val MAX_TREADMILL = 120

    private val rest = SessionOption(null, 0, "Rest")

    fun recommend(i: RecommendationInput, seed: Long = 0): Recommendation {
        if (i.feeling == Feeling.ILL || i.minutes < 15) {
            val reason = if (i.feeling == Feeling.ILL) "Rest is the plan today. No penalty." else "Not much time. Rest, or squeeze in a short walk if you like."
            return Recommendation(rest, null, reason)
        }
        if (i.feeling == Feeling.ROUGH) {
            val mins = minOf(i.minutes, 30)
            val walk = if (mins >= MIN_SESSION) SessionOption(SessionType.TREADMILL, mins, "Easy steady walk, $mins min", TreadmillStyle.STEADY) else rest
            return Recommendation(walk, if (walk === rest) null else rest, "Feeling rough: keep it easy, or skip with no penalty.")
        }

        val gymLifting = GymBuilder.liftingMinutes(i.minutes, i.settings)
        val gymOk = i.nearGym && gymLifting >= i.settings.minLiftingMin &&
            (i.daysSinceGym == null || i.daysSinceGym >= 2)
        val kneeHigh = (i.kneePain ?: 0) >= 7
        val treadMins = i.minutes.coerceAtMost(MAX_TREADMILL)
        val style = if (kneeHigh) TreadmillStyle.STEADY
        else TreadmillPlanner.chooseStyle(treadMins, i.lastTreadmillStyle, exciting = i.minutes >= 60, seed = seed)

        val candidates = buildList {
            if (gymOk) {
                val score = 40 + 25 * maxOf(0, i.settings.gymTarget - i.gymDoneThisWeek) - (if (i.gymDoneThisWeek >= i.settings.gymTarget) 30 else 0) +
                    3 * minOf(i.daysSinceGym ?: 7, 7)
                add(score to SessionOption(SessionType.GYM, i.minutes, "Gym, about ${minOf(gymLifting, 60)} min of lifting"))
            }
            if (treadMins >= MIN_SESSION) {
                val score = 30 + 10 * maxOf(0, i.settings.treadmillTarget - i.treadmillDoneThisWeek) + (if (i.minutes >= 60) 10 else 0)
                add(score to SessionOption(SessionType.TREADMILL, treadMins, "Treadmill ${style.label.lowercase()}, $treadMins min", style))
            }
            if (i.minutes >= 15) {
                val score = if (i.minutes < 30) 25 else 15
                val m = minOf(i.minutes, 20)
                add(score to SessionOption(SessionType.CORE, m, "Core on the mat, $m min"))
            }
        }.sortedByDescending { it.first }

        val top = candidates.first().second
        val alt = candidates.getOrNull(1)?.second
        val reason = reasonFor(top, i, kneeHigh)
        return Recommendation(top, alt, if (i.restDay) "Rest day, but if you have time: $reason" else reason)
    }

    private fun reasonFor(top: SessionOption, i: RecommendationInput, kneeHigh: Boolean): String = when (top.type) {
        SessionType.GYM ->
            if (i.gymDoneThisWeek < i.settings.gymTarget) "Gym is behind for the week (${i.gymDoneThisWeek} of ${i.settings.gymTarget}) and you have time for it."
            else "Plenty of time and legs are rested."
        SessionType.TREADMILL ->
            when {
                kneeHigh -> "Knee is sore: a steady walk, no running."
                i.minutes >= 60 -> "Lots of time: a longer treadmill session."
                else -> "Treadmill fits the time you have."
            }
        SessionType.CORE -> "Short on time: core work is a good fit."
        else -> "Rest is fine."
    }
}
