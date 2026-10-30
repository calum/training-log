package com.calum.traininglog

import com.calum.traininglog.core.Cue
import com.calum.traininglog.core.CueType
import com.calum.traininglog.core.relevantCues
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

    private const val IDLE_DELAY_MS = 1000L

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private var timeline: Timeline? = null
    private var style = TreadmillPlan(com.calum.traininglog.core.TreadmillStyle.STEADY, emptyList())
    private var elapsedMs = 0L
    private var lastRealtime = 0L
    private var lastSec = -1
    private var flashUntil = 0L

    /** Cues found while advancing the clock for a pause or adjust; handed out by the next [tick]. */
    private var pending: Cue? = null

    @Synchronized
    fun start(plan: TreadmillPlan, now: Long) {
        style = plan
        timeline = Timeline(plan.segments)
        elapsedMs = 0
        lastRealtime = now
        lastSec = -1
        flashUntil = 0
        pending = null
        publish(now, paused = false, finished = false)
    }

    @Synchronized
    fun pause(now: Long) {
        if (_ui.value.paused) return
        advance(now)
        publish(now, paused = true, finished = _ui.value.finished)
    }

    @Synchronized
    fun resume(now: Long) {
        // Only re-base the clock when actually paused; otherwise time since the last tick would be lost.
        if (!_ui.value.paused) return
        lastRealtime = now
        publish(now, paused = false, finished = _ui.value.finished)
    }

    @Synchronized
    fun togglePause(now: Long) = if (_ui.value.paused) resume(now) else pause(now)

    /** Lengthen or shorten the current segment; shortening never skips past the current moment. */
    @Synchronized
    fun adjustCurrent(deltaSec: Int, now: Long) {
        val tl = timeline ?: return
        if (_ui.value.finished) return
        advance(now)
        val s = tl.stateAt((elapsedMs / 1000).toInt())
        if (s.finished) return
        val delta = if (deltaSec < 0) -minOf(-deltaSec, maxOf(0, s.secondsLeft - 5)) else deltaSec
        if (delta == 0) return
        timeline = tl.adjusted(s.index, delta)
        publish(now, _ui.value.paused, _ui.value.finished)
    }

    @Synchronized
    fun stop() {
        timeline = null
        pending = null
        _ui.value = Ui()
    }

    @Synchronized
    fun completedSec(): Int = minOf((elapsedMs / 1000).toInt(), timeline?.totalSec ?: 0)

    /**
     * Advance the clock and return the cue to play now (at most one; see [relevantCues]).
     * Safe to call late or irregularly: elapsed time comes from [now] (monotonic), not from call count.
     */
    @Synchronized
    fun tick(now: Long): List<Cue> {
        advance(now)
        publish(now, _ui.value.paused, _ui.value.finished)
        val out = pending ?: return emptyList()
        pending = null
        return listOf(out)
    }

    /**
     * How long the driver may sleep before the next tick matters: the next whole-second boundary of the
     * session clock (so the timer, cues and notification stay on the second), or the end of the flash.
     * Paused/finished sessions only need an occasional wake.
     */
    @Synchronized
    fun nextDelayMs(now: Long): Long {
        val cur = _ui.value
        if (!cur.active || cur.paused || cur.finished) return IDLE_DELAY_MS
        val elapsed = elapsedMs + (now - lastRealtime).coerceAtLeast(0)
        var delay = 1000 - elapsed % 1000 + 5
        if (flashUntil > now) delay = minOf(delay, flashUntil - now + 5)
        return delay.coerceIn(20, 1000)
    }

    /** Moves the clock to [now], queueing any cue that fell due. Does not publish. */
    private fun advance(now: Long) {
        val tl = timeline ?: return
        val cur = _ui.value
        if (!cur.active) return
        if (cur.paused || cur.finished) {
            lastRealtime = now
            return
        }
        elapsedMs += (now - lastRealtime).coerceAtLeast(0)
        lastRealtime = now
        // Never run past the end, so a late tick cannot inflate the completed time.
        elapsedMs = minOf(elapsedMs, tl.totalSec * 1000L)
        val sec = (elapsedMs / 1000).toInt()
        if (sec > lastSec) {
            val cues = tl.relevantCues(lastSec, sec)
            lastSec = sec
            if (cues.isNotEmpty()) {
                // One pending cue at most: a newer one replaces an older one, but nothing replaces DONE.
                if (pending?.type != CueType.DONE) pending = cues.last()
                flashUntil = now + 800
            }
        }
    }

    private fun publish(now: Long, paused: Boolean, finished: Boolean) {
        val tl = timeline ?: return
        val state = tl.stateAt((elapsedMs / 1000).toInt())
        val next = Ui(
            active = true, paused = paused, finished = finished || state.finished, flashing = now < flashUntil,
            plan = TreadmillPlan(style.style, tl.segments), state = state, totalSec = tl.totalSec,
        )
        if (next != _ui.value) _ui.value = next
    }

    fun segments(): List<Segment> = timeline?.segments ?: emptyList()
}
