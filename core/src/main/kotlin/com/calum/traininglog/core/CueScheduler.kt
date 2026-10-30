package com.calum.traininglog.core

import kotlin.math.PI
import kotlin.math.sin

/**
 * Picks the cues worth playing for a clock advance from [fromSec] to [toSec].
 *
 * Normally the window is one second wide and this is [Timeline.cuesBetween]. After a stall (throttled
 * background loop, Doze, a long pause in the main thread) the window can span many seconds; replaying every
 * missed cue would be a burst of stale beeps, so at most ONE cue is returned:
 *  - the done cue wins over everything;
 *  - a pre-warning whose change time has already passed (<= [toSec]) is stale and dropped;
 *  - otherwise the latest remaining cue (the most recent change, or a still-valid pre-warning).
 */
fun Timeline.relevantCues(fromSec: Int, toSec: Int, preWarnSec: Int = 15): List<Cue> {
    val all = cuesBetween(fromSec, toSec, preWarnSec)
    if (all.isEmpty()) return all
    all.lastOrNull { it.type == CueType.DONE }?.let { return listOf(it) }
    val starts = segments.runningFold(0) { acc, s -> acc + s.seconds }
    val live = all.filter { it.type != CueType.PRE_WARNING || starts[it.segmentIndex] > toSec }
    return live.lastOrNull()?.let { listOf(it) } ?: emptyList()
}

/** One beep: [freqHz] for [ms] milliseconds, followed by [gapMs] of silence. */
data class Tone(val freqHz: Double, val ms: Int, val gapMs: Int = 0)

/**
 * Distinct, easily told apart cue sounds, rendered as 16-bit mono PCM so they play through an AudioTrack at
 * media volume (same loudness as the video, unlike ToneGenerator's fixed internal mix).
 * Pre-warning: one short beep. Change: two beeps. Done: three rising beeps.
 */
object CueTones {
    /** Leading silence: Bluetooth/USB headphones often swallow the first ~100 ms after the stream opens. */
    const val LEAD_IN_MS = 150
    private const val FADE_MS = 8
    private const val AMPLITUDE = 0.85

    fun pattern(type: CueType): List<Tone> = when (type) {
        CueType.PRE_WARNING -> listOf(Tone(880.0, 220))
        CueType.CHANGE -> listOf(Tone(1175.0, 180, 120), Tone(1175.0, 180))
        CueType.DONE -> listOf(Tone(784.0, 220, 90), Tone(988.0, 220, 90), Tone(1319.0, 380))
    }

    fun durationMs(type: CueType): Int = LEAD_IN_MS + pattern(type).sumOf { it.ms + it.gapMs }

    fun render(type: CueType, sampleRate: Int = 44100): ShortArray {
        fun samples(ms: Int) = sampleRate * ms / 1000
        val out = ShortArray(samples(durationMs(type)))
        var pos = samples(LEAD_IN_MS)
        for (t in pattern(type)) {
            val n = samples(t.ms)
            val fade = samples(FADE_MS)
            for (i in 0 until n) {
                // Short linear fade in/out avoids clicks at the tone edges.
                val env = minOf(1.0, i / fade.toDouble(), (n - 1 - i) / fade.toDouble())
                out[pos + i] = (sin(2 * PI * t.freqHz * i / sampleRate) * env * AMPLITUDE * Short.MAX_VALUE).toInt().toShort()
            }
            pos += n + samples(t.gapMs)
        }
        return out
    }
}
