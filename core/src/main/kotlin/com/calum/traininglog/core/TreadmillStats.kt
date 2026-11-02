package com.calum.traininglog.core

import kotlin.math.roundToInt

/** Estimated totals for a treadmill session so far. */
data class TreadmillStats(val distanceM: Int, val climbM: Int, val kcal: Int)

/**
 * Distance, vertical climb and calories from the plan's speeds and inclines.
 *
 *  - Distance: speed x time, per segment (treadmill belt distance).
 *  - Climb: distance x incline%, i.e. how far you would have gone up a slope of that grade.
 *  - Calories: the ACSM walking equation, VO2 (ml/kg/min) = 0.1 x S + 1.8 x S x grade + 3.5 with S in m/min,
 *    then 5 kcal per litre of oxygen. This is a gross figure like a treadmill console's, and an estimate:
 *    it depends on your body weight (Settings) and ignores handrail use, stride and fitness.
 *
 * Segments partly done count in proportion, so it can be shown live as the session runs.
 */
object TreadmillStatsCalc {
    fun at(segments: List<Segment>, elapsedSec: Int, bodyWeightKg: Double): TreadmillStats {
        var remaining = elapsedSec.coerceAtLeast(0)
        var distance = 0.0
        var climb = 0.0
        var kcal = 0.0
        for (seg in segments) {
            if (remaining <= 0) break
            val t = minOf(seg.seconds, remaining)
            remaining -= t
            val metresPerMin = seg.speedKmh * 1000.0 / 60.0
            val grade = seg.inclinePct / 100.0
            val d = metresPerMin * t / 60.0
            distance += d
            climb += d * grade
            val vo2 = 0.1 * metresPerMin + 1.8 * metresPerMin * grade + 3.5
            kcal += vo2 * bodyWeightKg / 1000.0 * 5.0 * t / 60.0
        }
        return TreadmillStats(distance.roundToInt(), climb.roundToInt(), kcal.roundToInt())
    }
}
