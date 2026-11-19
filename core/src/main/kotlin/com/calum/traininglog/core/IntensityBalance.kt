package com.calum.traininglog.core

import java.time.LocalDate
import kotlin.math.roundToInt

/** Minutes split into easy / hard endurance time and strength time. */
data class TimeSplit(val easyMin: Int, val hardMin: Int, val strengthMin: Int) {
    val enduranceMin: Int get() = easyMin + hardMin
    /** Share of endurance time that was easy, 0..1; null when there is no endurance time. */
    val easyShare: Double? get() = if (enduranceMin <= 0) null else easyMin.toDouble() / enduranceMin
    operator fun plus(o: TimeSplit) = TimeSplit(easyMin + o.easyMin, hardMin + o.hardMin, strengthMin + o.strengthMin)

    companion object { val ZERO = TimeSplit(0, 0, 0) }
}

/** How a week's balance reads, in neutral terms (the screen should not colour any of these red). */
enum class BalanceStatus { NOT_ENOUGH_DATA, ON_TARGET, A_BIT_HARD, MOSTLY_HARD }

/**
 * @property week this Monday-Sunday week; [last4Weeks] the 4 weeks ending with it (this one included).
 * @property targetEasyShare the plan target, 0.8 by default ("about 80/20").
 */
data class IntensityBalanceResult(
    val weekStart: LocalDate,
    val week: TimeSplit,
    val last4Weeks: TimeSplit,
    val targetEasyShare: Double,
    val weekStatus: BalanceStatus,
    val last4Status: BalanceStatus,
    val message: String,
) {
    /** "about 85/15" for the week, or null with too little endurance time to say. */
    val weekLabel: String? get() = label(week)
    val last4Label: String? get() = label(last4Weeks)

    private fun label(s: TimeSplit): String? = s.easyShare?.takeIf { s.enduranceMin >= IntensityBalance.MIN_MINUTES_TO_JUDGE }?.let {
        val easy = (it * 100 / 5.0).roundToInt() * 5
        "about $easy/${100 - easy}"
    }
    /** "about 80/20" target label. */
    val targetLabel: String get() = "about ${(targetEasyShare * 100).roundToInt()}/${100 - (targetEasyShare * 100).roundToInt()}"
}

/**
 * Compares the easy/hard mix of what was ACTUALLY logged with the plan's target.
 *
 * THE CLASSIFICATION IS APPROXIMATE. The app does not record a session RPE, so time is sorted like this:
 *  - GYM and CORE: strength (reported separately, never counted as easy or hard endurance).
 *  - HIKE: easy.
 *  - TREADMILL: steady and long rolling are easy; pyramids and ladders count [HARD_FRACTION] of the main time
 *    as hard, intervals and long intervals about half (the rest is recovery); warm-up and cool-down
 *    ([WARM_COOL_MIN] min) are easy.
 *  - Guided race-plan records (dayLabel 'Race plan', HYROX or HIKE type): the logged seconds at RPE 7 or more are hard,
 *    the rest easy (exact, no warm-up allowance).
 *  - HYROX: station RPE where logged. Blocks at RPE 7 or more are 'hard', weighted by their logged seconds
 *    (equal weights when seconds are missing); blocks with no RPE count as easy.
 * An RPE of 7 or more is used as the 'hard' line; real intensity zones depend on the athlete. Use the numbers as
 * a rough nudge, not a measurement. Evidence: a mostly-easy distribution (about 80/20) is a sound default, not a
 * proven optimum (Silva Oliveira et al. 2024 found similar results across distributions in interventions over
 * 12 weeks).
 */
object IntensityBalance {
    const val HARD_RPE = 7
    const val WARM_COOL_MIN = 8
    const val DEFAULT_TARGET = 0.8
    /** Below this much endurance time the split is not worth showing. */
    const val MIN_MINUTES_TO_JUDGE = 60

    private val HARD_FRACTION = mapOf(
        TreadmillStyle.STEADY to 0.0,
        TreadmillStyle.LONG_ROLLING to 0.0,
        TreadmillStyle.PYRAMID to 0.4,
        TreadmillStyle.LADDER to 0.4,
        TreadmillStyle.INTERVALS to 0.5,
        TreadmillStyle.LONG_INTERVALS to 0.5,
    )

    /** One session's split (approximate, see the class comment). */
    fun classify(r: SessionRecord): TimeSplit = when (r.type) {
        SessionType.GYM, SessionType.CORE -> TimeSplit(0, 0, r.minutesActual)
        SessionType.HIKE -> if (isGuided(r)) guided(r) else TimeSplit(r.minutesActual, 0, 0)
        SessionType.TREADMILL -> {
            val t = r.treadmill
            val minutes = t?.let { it.completedSec / 60 } ?: r.minutesActual
            val frac = t?.let { HARD_FRACTION[it.style] } ?: 0.0
            val hard = ((minutes - WARM_COOL_MIN).coerceAtLeast(0) * frac).roundToInt()
            TimeSplit(minutes - hard, hard, 0)
        }
        SessionType.HYROX -> if (isGuided(r)) guided(r) else {
            val blocks = r.stations
            val weights = if (blocks.isNotEmpty() && blocks.all { (it.seconds ?: 0) > 0 }) blocks.map { it.seconds!!.toDouble() } else blocks.map { 1.0 }
            val total = weights.sum()
            val hardW = blocks.indices.filter { (blocks[it].rpe ?: 0) >= HARD_RPE }.sumOf { weights[it] }
            val workMin = (r.minutesActual - WARM_COOL_MIN).coerceAtLeast(0)
            val hard = if (total > 0) (workMin * hardW / total).roundToInt() else 0
            TimeSplit(r.minutesActual - hard, hard, 0)
        }
    }

    /** A guided race-plan record (rower, SkiErg, bike ...) whose logs cover the session in seconds. */
    private fun isGuided(r: SessionRecord) =
        r.dayLabel == GuidedRecord.DAY_LABEL && r.stations.isNotEmpty() && r.stations.all { (it.seconds ?: 0) > 0 }

    /** Exact split for a guided record: the seconds logged at RPE 7 or more are hard, everything else is easy. */
    private fun guided(r: SessionRecord): TimeSplit {
        val hardSec = r.stations.filter { (it.rpe ?: 0) >= HARD_RPE }.sumOf { it.seconds ?: 0 }
        val hard = (hardSec / 60.0).roundToInt().coerceAtMost(r.minutesActual)
        return TimeSplit(r.minutesActual - hard, hard, 0)
    }

    fun split(records: List<SessionRecord>, from: LocalDate, toInclusive: LocalDate): TimeSplit =
        records.filter { it.date in from..toInclusive }.fold(TimeSplit.ZERO) { a, r -> a + classify(r) }

    /** The week starting [weekStart] (a Monday) and the 4 weeks ending with it, against [targetEasyShare]. */
    fun of(records: List<SessionRecord>, weekStart: LocalDate, targetEasyShare: Double = DEFAULT_TARGET): IntensityBalanceResult {
        val start = Stats.weekOf(weekStart).start
        val week = split(records, start, start.plusDays(6))
        val last4 = split(records, start.minusWeeks(3), start.plusDays(6))
        val ws = status(week, targetEasyShare)
        val ls = status(last4, targetEasyShare)
        val msg = when (ws) {
            BalanceStatus.NOT_ENOUGH_DATA -> "Not enough easy or hard time logged yet this week to show a split. The plan aims for about ${(targetEasyShare * 100).roundToInt()}/${100 - (targetEasyShare * 100).roundToInt()} easy to hard."
            BalanceStatus.ON_TARGET -> "Nicely balanced: mostly easy time, with the hard work kept short. That is what builds the base."
            BalanceStatus.A_BIT_HARD -> "A bit more hard work than planned this week. Nothing to fix: an easy session next keeps the balance."
            BalanceStatus.MOSTLY_HARD -> "This week leaned hard. An easy day or two (RPE 4 or less) will bring it back, and easy time is where the base is built."
        }
        return IntensityBalanceResult(start, week, last4, targetEasyShare, ws, ls, msg)
    }

    private fun status(s: TimeSplit, target: Double): BalanceStatus {
        val share = s.easyShare ?: return BalanceStatus.NOT_ENOUGH_DATA
        if (s.enduranceMin < MIN_MINUTES_TO_JUDGE) return BalanceStatus.NOT_ENOUGH_DATA
        return when {
            share >= target - 0.02 -> BalanceStatus.ON_TARGET
            share >= target - 0.12 -> BalanceStatus.A_BIT_HARD
            else -> BalanceStatus.MOSTLY_HARD
        }
    }
}
