package com.calum.traininglog.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CueSchedulerTest {
    // starts: 0, 100, 200, 300 (total 300). Every segment differs, so each change has a pre-warning at start-15.
    private val tl = Timeline(listOf(
        Segment(100, 5.0, 6.0, "a"), Segment(100, 5.2, 7.0, "b"), Segment(100, 5.4, 8.0, "c"),
    ))

    @Test fun normalSingleSecondWindowIsUnchanged() {
        assertEquals(listOf(Cue(85, CueType.PRE_WARNING, 1)), tl.relevantCues(84, 85))
        assertEquals(listOf(Cue(100, CueType.CHANGE, 1)), tl.relevantCues(99, 100))
        assertEquals(emptyList(), tl.relevantCues(10, 11))
    }

    @Test fun stallOverChangeDropsStalePreWarning() {
        val c = tl.relevantCues(80, 103)
        assertEquals(listOf(Cue(100, CueType.CHANGE, 1)), c)
    }

    @Test fun stallKeepsStillValidPreWarning() {
        val c = tl.relevantCues(60, 90)
        assertEquals(listOf(Cue(85, CueType.PRE_WARNING, 1)), c)
    }

    @Test fun longStallFiresOnlyLatestChange() {
        val c = tl.relevantCues(10, 250)
        assertEquals(listOf(Cue(200, CueType.CHANGE, 2)), c)
    }

    @Test fun stallPastEndFiresOnlyDone() {
        assertEquals(listOf(Cue(300, CueType.DONE, 2)), tl.relevantCues(10, 999))
    }

    @Test fun noDuplicatesAcrossConsecutiveWindows() {
        val fired = mutableListOf<Cue>()
        var last = -1
        for (now in listOf(0, 1, 50, 86, 87, 101, 102, 200, 290, 291, 300, 301, 302)) {
            fired += tl.relevantCues(last, now)
            last = now
        }
        assertEquals(fired.distinct(), fired)
        assertTrue(fired.any { it.type == CueType.DONE })
    }

    @Test fun tonePatternsAreDistinguishable() {
        assertEquals(1, CueTones.pattern(CueType.PRE_WARNING).size)
        assertEquals(2, CueTones.pattern(CueType.CHANGE).size)
        val done = CueTones.pattern(CueType.DONE)
        assertEquals(3, done.size)
        assertTrue(done[0].freqHz < done[1].freqHz && done[1].freqHz < done[2].freqHz)
    }

    @Test fun renderedPcmMatchesDurationAndIsAudible() {
        for (t in CueType.entries) {
            val pcm = CueTones.render(t, 44100)
            assertEquals(44100 * CueTones.durationMs(t) / 1000, pcm.size)
            assertTrue(pcm.maxOf { it.toInt() } > 20000)
            assertTrue(pcm.take(44100 * CueTones.LEAD_IN_MS / 1000).all { it.toInt() == 0 })
        }
    }
}
