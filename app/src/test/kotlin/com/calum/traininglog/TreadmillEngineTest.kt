package com.calum.traininglog

import com.calum.traininglog.core.Cue
import com.calum.traininglog.core.CueType
import com.calum.traininglog.core.Segment
import com.calum.traininglog.core.TreadmillPlan
import com.calum.traininglog.core.TreadmillStyle
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TreadmillEngineTest {
    // Segments: 0-100, 100-200, 200-300; the clock arg is milliseconds.
    private val plan = TreadmillPlan(
        TreadmillStyle.PYRAMID,
        listOf(Segment(100, 5.0, 6.0, "a"), Segment(100, 5.2, 7.0, "b"), Segment(100, 5.4, 8.0, "c")),
    )

    @BeforeTest fun setUp() { TreadmillEngine.stop(); TreadmillEngine.start(plan, 0) }
    @AfterTest fun tearDown() { TreadmillEngine.stop() }

    private fun runTo(sec: Int, step: Int = 1): List<Cue> {
        val out = mutableListOf<Cue>()
        var t = (TreadmillEngine.ui.value.state!!.elapsedSec) + step
        while (t <= sec) { out += TreadmillEngine.tick(t * 1000L); t += step }
        return out
    }

    @Test fun normalRunFiresEachCueOnce() {
        val cues = runTo(300)
        assertEquals(
            listOf(
                Cue(85, CueType.PRE_WARNING, 1), Cue(100, CueType.CHANGE, 1),
                Cue(185, CueType.PRE_WARNING, 2), Cue(200, CueType.CHANGE, 2), Cue(300, CueType.DONE, 2),
            ),
            cues,
        )
        assertTrue(TreadmillEngine.ui.value.finished)
    }

    @Test fun stallOverChangeFiresOnlyChange() {
        runTo(80)
        val cues = TreadmillEngine.tick(103_000)
        assertEquals(listOf(Cue(100, CueType.CHANGE, 1)), cues)
    }

    @Test fun longStallFiresOnlyLatestChangeAndNoRepeat() {
        runTo(10)
        assertEquals(listOf(Cue(200, CueType.CHANGE, 2)), TreadmillEngine.tick(250_000))
        assertEquals(emptyList(), TreadmillEngine.tick(250_500))
        assertEquals(emptyList(), TreadmillEngine.tick(251_000))
    }

    @Test fun stallPastEndFiresOnlyDoneAndClampsElapsed() {
        runTo(10)
        assertEquals(listOf(Cue(300, CueType.DONE, 2)), TreadmillEngine.tick(9_999_000))
        val ui = TreadmillEngine.ui.value
        assertTrue(ui.finished)
        assertEquals(300, TreadmillEngine.completedSec())
        assertEquals(emptyList(), TreadmillEngine.tick(10_999_000))
    }

    @Test fun pauseAndResumeDoNotDrift() {
        runTo(50)
        TreadmillEngine.pause(50_400)
        // 10 minutes paused: no time passes, no cues.
        assertEquals(emptyList(), TreadmillEngine.tick(650_400))
        assertEquals(50, TreadmillEngine.ui.value.state!!.elapsedSec)
        TreadmillEngine.resume(650_400)
        TreadmillEngine.resume(700_000) // double resume must not re-base
        assertEquals(50, TreadmillEngine.completedSec())
        val cues = TreadmillEngine.tick(650_400 + 35_600 + 1000) // 50.4 + 36.6 = 87 s
        assertEquals(listOf(Cue(85, CueType.PRE_WARNING, 1)), cues)
    }

    @Test fun cueFoundWhilePausingIsNotLost() {
        runTo(84)
        TreadmillEngine.pause(85_500) // crosses 85 s inside pause()
        assertEquals(listOf(Cue(85, CueType.PRE_WARNING, 1)), TreadmillEngine.tick(86_000))
    }

    @Test fun doublePauseKeepsElapsed() {
        runTo(20)
        TreadmillEngine.pause(20_500)
        TreadmillEngine.pause(90_000)
        assertEquals(20, TreadmillEngine.completedSec())
    }

    @Test fun adjustLengthensCurrentSegmentAndMovesCues() {
        runTo(10)
        TreadmillEngine.adjustCurrent(30, 10_000)
        assertEquals(130, TreadmillEngine.ui.value.state!!.current.seconds)
        val cues = runTo(300)
        // Change moves from 100 to 130; the pre-warning from 85 to 115.
        assertTrue(cues.contains(Cue(115, CueType.PRE_WARNING, 1)))
        assertTrue(cues.contains(Cue(130, CueType.CHANGE, 1)))
        assertFalse(cues.any { it.atSec == 100 })
    }

    @Test fun adjustShortenNeverPassesNowAndLeavesFiveSeconds() {
        runTo(90) // 10 s left in segment
        TreadmillEngine.adjustCurrent(-30, 90_000)
        assertEquals(5, TreadmillEngine.ui.value.state!!.secondsLeft)
        TreadmillEngine.adjustCurrent(-30, 90_000) // already at the floor: no-op
        assertEquals(5, TreadmillEngine.ui.value.state!!.secondsLeft)
    }

    @Test fun adjustCannotShrinkBelowThirtySeconds() {
        TreadmillEngine.stop()
        TreadmillEngine.start(TreadmillPlan(TreadmillStyle.STEADY, listOf(Segment(40, 5.0, 5.0, "x"), Segment(100, 6.0, 5.0, "y"))), 0)
        TreadmillEngine.adjustCurrent(-30, 0)
        assertEquals(30, TreadmillEngine.ui.value.state!!.current.seconds)
    }

    @Test fun adjustIsIgnoredOnceFinished() {
        TreadmillEngine.tick(400_000)
        assertTrue(TreadmillEngine.ui.value.finished)
        TreadmillEngine.adjustCurrent(30, 400_000)
        assertEquals(300, TreadmillEngine.ui.value.totalSec)
    }

    @Test fun adjustWhilePausedKeepsClockStill() {
        runTo(20)
        TreadmillEngine.pause(20_000)
        TreadmillEngine.adjustCurrent(30, 500_000)
        assertEquals(20, TreadmillEngine.completedSec())
        assertTrue(TreadmillEngine.ui.value.paused)
    }

    @Test fun nextDelayAlignsToSecondBoundary() {
        TreadmillEngine.tick(300)
        val d = TreadmillEngine.nextDelayMs(300)
        assertTrue(d in 700..800, "delay was $d")
        TreadmillEngine.pause(300)
        assertEquals(1000, TreadmillEngine.nextDelayMs(300))
    }

    @Test fun stopClearsEverything() {
        TreadmillEngine.stop()
        assertFalse(TreadmillEngine.ui.value.active)
        assertEquals(emptyList(), TreadmillEngine.tick(5000))
    }

    /**
     * Regression: the service stops ticking once the session has finished. The last published state used to
     * still say `flashing = true` (the done cue sets a flash), and the flash screen has no buttons, so the
     * player looked frozen with no way to finish. A finished session must never be left flashing.
     */
    @Test fun finishedSessionIsNeverLeftFlashing() {
        runTo(300)
        // The service loop ends here: no more ticks. Even a much later tick must not matter.
        val ui = TreadmillEngine.ui.value
        assertTrue(ui.finished)
        assertFalse(ui.flashing, "a finished session must not be stuck on the flash screen")
    }

    @Test fun refreshWithoutServiceClearsStaleFlashAndKeepsCues() {
        runTo(84)
        // UI-side refresh (no service) at the pre-warning second must not swallow the cue the service plays.
        TreadmillEngine.refresh(85_000L)
        val cues = TreadmillEngine.tick(85_000L)
        assertEquals(listOf(Cue(85, CueType.PRE_WARNING, 1)), cues)
        TreadmillEngine.refresh(86_500L)
        assertFalse(TreadmillEngine.ui.value.flashing)
    }
}
