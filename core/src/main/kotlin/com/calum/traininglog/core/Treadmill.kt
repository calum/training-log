package com.calum.traininglog.core

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.random.Random

@Serializable
enum class TreadmillStyle(val label: String) {
    STEADY("Steady walk"),
    PYRAMID("Pyramid"),
    LADDER("Ladder"),
    INTERVALS("Intervals"),
    LONG_ROLLING("Long rolling"),
    LONG_INTERVALS("Long intervals"),
}

@Serializable
data class Segment(val seconds: Int, val speedKmh: Double, val inclinePct: Double, val label: String)

/** Conversational-effort base setting, adjusted from user feedback. */
@Serializable
data class Calibration(val speedKmh: Double = 5.5, val inclinePct: Double = 8.0) {
    fun adjusted(effort: Effort): Calibration = when (effort) {
        Effort.TOO_EASY -> copy(inclinePct = minOf(MAX_INCLINE, inclinePct + 1))
        Effort.TOO_HARD -> copy(inclinePct = maxOf(2.0, inclinePct - 1))
        Effort.JUST_RIGHT -> this
    }
}

const val MAX_INCLINE = 12.0

data class TreadmillPlan(val style: TreadmillStyle, val segments: List<Segment>) {
    val totalSec get() = segments.sumOf { it.seconds }
}

object TreadmillPlanner {
    const val MIN_SEGMENT_SEC = 120
    const val WARMUP_SEC = 300
    const val COOLDOWN_SEC = 180
    val WARMUP = Segment(WARMUP_SEC, 5.0, 6.0, "Warm-up")
    val COOLDOWN = Segment(COOLDOWN_SEC, 4.0, 2.0, "Cool-down")

    private data class Shape(val levels: List<Int>, val minSegSec: Int)

    private val shapes = mapOf(
        TreadmillStyle.PYRAMID to Shape(listOf(0, 1, 2, 3, 2, 1, 0), MIN_SEGMENT_SEC),
        TreadmillStyle.LADDER to Shape(listOf(0, 1, 2, 3, 4, 1), MIN_SEGMENT_SEC),
        TreadmillStyle.INTERVALS to Shape(listOf(0, 3), 150),
        TreadmillStyle.LONG_ROLLING to Shape(listOf(0, 1, 0, -1), 300),
        TreadmillStyle.LONG_INTERVALS to Shape(listOf(0, 3, 0, 3, 1, 4, 0, 2), 180),
    )

    fun chooseStyle(minutes: Int, last: TreadmillStyle?, exciting: Boolean, seed: Long = 0): TreadmillStyle {
        val pool = buildList {
            add(TreadmillStyle.STEADY)
            if (minutes >= 25) add(TreadmillStyle.PYRAMID)
            if (minutes >= 30) { add(TreadmillStyle.LADDER); add(TreadmillStyle.INTERVALS) }
            if (minutes >= 60) { add(TreadmillStyle.LONG_ROLLING); add(TreadmillStyle.LONG_INTERVALS) }
        }
        val fresh = pool.filter { it != last }.ifEmpty { pool }
        val preferred = if (exciting) fresh.filter {
            it in setOf(TreadmillStyle.INTERVALS, TreadmillStyle.LONG_INTERVALS, TreadmillStyle.LADDER)
        }.ifEmpty { fresh } else fresh
        return preferred[Random(seed).nextInt(preferred.size)]
    }

    fun build(style: TreadmillStyle, minutes: Int, base: Calibration = Calibration(), settings: UserSettings = UserSettings()): TreadmillPlan {
        val totalSec = minutes.coerceIn(20, 120) * 60
        val mainSec = totalSec - WARMUP_SEC - COOLDOWN_SEC
        val shape = shapes[style]
        val levels = shape?.let { repeatToFit(it, mainSec) }
        val main = if (shape == null || levels == null || mainSec < levels.size * shape.minSegSec) {
            listOf(Segment(mainSec, base.speedKmh, base.inclinePct, "Steady"))
        } else {
            val segSec = (mainSec / levels.size) / 5 * 5
            levels.mapIndexed { idx, lvl ->
                val sec = if (idx == levels.lastIndex) mainSec - segSec * (levels.size - 1) else segSec
                Segment(sec, round1(base.speedKmh + 0.2 * lvl), (base.inclinePct + lvl).coerceIn(0.0, MAX_INCLINE),
                    if (lvl == 0) "Base" else if (lvl > 0) "Push +$lvl" else "Ease $lvl")
            }
        }
        val effective = if (main.size == 1) TreadmillStyle.STEADY else style
        return TreadmillPlan(effective, listOf(WARMUP.copy(speedKmh = settings.warmupSpeedKmh, inclinePct = settings.warmupInclinePct)) + main + listOf(COOLDOWN))
    }

    private fun repeatToFit(shape: Shape, mainSec: Int): List<Int> {
        val tail = if (shape.levels.last() != 0) 1 else 0
        val reps = max(1, (mainSec / shape.minSegSec - tail) / shape.levels.size)
        val levels = List(reps) { shape.levels }.flatten().toMutableList()
        if (levels.last() != 0) levels += 0
        return levels
    }

    private fun round1(x: Double) = Math.round(x * 10) / 10.0
}

enum class CueType { PRE_WARNING, CHANGE, DONE }
data class Cue(val atSec: Int, val type: CueType, val segmentIndex: Int)

data class TimelineState(
    val elapsedSec: Int,
    val index: Int,
    val secondsLeft: Int,
    val current: Segment,
    val next: Segment?,
    val finished: Boolean,
)

class Timeline(val segments: List<Segment>) {
    private val starts: List<Int> = segments.runningFold(0) { acc, s -> acc + s.seconds }
    val totalSec: Int = starts.last()

    fun stateAt(elapsedSec: Int): TimelineState {
        val e = elapsedSec.coerceIn(0, totalSec)
        if (e >= totalSec) {
            val last = segments.lastIndex
            return TimelineState(totalSec, last, 0, segments[last], null, true)
        }
        val idx = starts.indexOfLast { it <= e }.coerceAtMost(segments.lastIndex)
        return TimelineState(e, idx, starts[idx + 1] - e, segments[idx], segments.getOrNull(idx + 1), false)
    }

    /** Cues falling in (fromSec, toSec]. A pre-warning is only given when the next segment actually changes settings. */
    fun cuesBetween(fromSec: Int, toSec: Int, preWarnSec: Int = 15): List<Cue> = buildList {
        for (i in 1 until segments.size) {
            val change = starts[i]
            val differs = segments[i].speedKmh != segments[i - 1].speedKmh || segments[i].inclinePct != segments[i - 1].inclinePct
            val pre = change - preWarnSec
            if (differs && pre > starts[i - 1] && pre > fromSec && pre <= toSec) add(Cue(pre, CueType.PRE_WARNING, i))
            if (change > fromSec && change <= toSec) add(Cue(change, CueType.CHANGE, i))
        }
        if (totalSec > fromSec && totalSec <= toSec) add(Cue(totalSec, CueType.DONE, segments.lastIndex))
    }.sortedBy { it.atSec }

    /** Lengthen or shorten one segment; a segment never drops below 30 s. */
    fun adjusted(index: Int, deltaSec: Int): Timeline {
        val seg = segments[index]
        val updated = seg.copy(seconds = max(30, seg.seconds + deltaSec))
        return Timeline(segments.toMutableList().also { it[index] = updated })
    }
}
