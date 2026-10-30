package com.calum.traininglog

import com.calum.traininglog.core.Cue
import com.calum.traininglog.core.Segment
import com.calum.traininglog.core.Timeline
import com.calum.traininglog.core.TimelineState
import com.calum.traininglog.core.TreadmillPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide treadmill clock. The foreground service drives [tick]; the UI only observes [ui].
 * Elapsed time is accumulated from the monotonic clock so pausing and a dimmed screen don't drift it.
 */
object TreadmillEngine {
    data class Ui(
        val active: Boolean = false,
        val paused: Boolean = false,
        val finished: Boolean = false,
        val flashing: Boolean = false,
        val plan: TreadmillPlan? = null,
        val state: TimelineState? = null,
        val totalSec: Int = 0,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private var timeline: Timeline? = null
    private var style = TreadmillPlan(com.calum.traininglog.core.TreadmillStyle.STEADY, emptyList())
    private var elapsedMs = 0L
    private var lastRealtime = 0L
    private var lastSec = -1
    private var flashUntil = 0L

    @Synchronized
    fun start(plan: TreadmillPlan, now: Long) {
        style = plan
        timeline = Timeline(plan.segments)
        elapsedMs = 0
        lastRealtime = now
        lastSec = -1
        flashUntil = 0
        publish(now, paused = false, finished = false)
    }

    @Synchronized
    fun pause(now: Long) {
        tick(now)
        publish(now, paused = true, finished = _ui.value.finished)
    }

    @Synchronized
    fun resume(now: Long) {
        lastRealtime = now
        publish(now, paused = false, finished = _ui.value.finished)
    }

    /** Lengthen or shorten the current segment; shortening never skips past the current moment. */
    @Synchronized
    fun adjustCurrent(deltaSec: Int, now: Long) {
        val tl = timeline ?: return
        tick(now)
        val s = tl.stateAt((elapsedMs / 1000).toInt())
        val delta = if (deltaSec < 0) -minOf(-deltaSec, maxOf(0, s.secondsLeft - 5)) else deltaSec
        if (delta == 0) return
        timeline = tl.adjusted(s.index, delta)
        publish(now, _ui.value.paused, _ui.value.finished)
    }

    @Synchronized
    fun stop() {
        timeline = null
        _ui.value = Ui()
    }

    fun completedSec(): Int = minOf((elapsedMs / 1000).toInt(), timeline?.totalSec ?: 0)

    /** Advance the clock; returns the cues that fell due since the previous call. */
    @Synchronized
    fun tick(now: Long): List<Cue> {
        val tl = timeline ?: return emptyList()
        val cur = _ui.value
        if (!cur.active) return emptyList()
        if (!cur.paused && !cur.finished) elapsedMs += now - lastRealtime
        lastRealtime = now
        val sec = (elapsedMs / 1000).toInt()
        var cues = emptyList<Cue>()
        if (!cur.paused && !cur.finished && sec > lastSec) {
            cues = tl.cuesBetween(lastSec, sec)
            lastSec = sec
        }
        if (cues.isNotEmpty()) flashUntil = now + 800
        publish(now, cur.paused, finished = cur.finished || sec >= tl.totalSec)
        return cues
    }

    private fun publish(now: Long, paused: Boolean, finished: Boolean) {
        val tl = timeline ?: return
        val state = tl.stateAt((elapsedMs / 1000).toInt())
        val next = Ui(
            active = true, paused = paused, finished = finished, flashing = now < flashUntil,
            plan = TreadmillPlan(style.style, tl.segments), state = state, totalSec = tl.totalSec,
        )
        if (next != _ui.value) _ui.value = next
    }

    fun segments(): List<Segment> = timeline?.segments ?: emptyList()
}
