package com.calum.traininglog.core

import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 2026-11-02 is a Monday.
private val start = LocalDate.of(2026, 11, 2)

private var n = 0
private fun gym(date: LocalDate, kg: Double, ex: String = "squat", knee: Int? = null, reps: Int = 5, target: Int = 5) =
    SessionRecord("b${n++}", date, SessionType.GYM, 60, 45, Feeling.GOOD, kneePain = knee,
        exercises = listOf(ExerciseLog(ex, List(3) { LoggedSet(kg, reps, target, 2) })))

private fun tm(date: LocalDate, min: Int) =
    SessionRecord("b${n++}", date, SessionType.TREADMILL, min, min, Feeling.OKAY,
        treadmill = TreadmillLog(TreadmillStyle.STEADY, min * 60, min * 60, Effort.JUST_RIGHT))

private fun block(weeks: Int = 6, id: String = "a", s: LocalDate = start, target: Double = 100.0, from: Double = 80.0) =
    TrainingBlock(id, "Block", s, weeks, BlockTarget("squat", target, from))

class BlocksTest {
    @Test fun weekCountingAndEnd() {
        val b = block(6)
        assertEquals(1, Blocks.progress(b, emptyList(), start).weekNumber)
        assertEquals(1, Blocks.progress(b, emptyList(), start.plusDays(6)).weekNumber)
        assertEquals(2, Blocks.progress(b, emptyList(), start.plusDays(7)).weekNumber)
        assertEquals(start.plusDays(41), b.plannedEnd)
        assertEquals(6, Blocks.progress(b, emptyList(), start.plusDays(41)).weekNumber)
        assertFalse(Blocks.progress(b, emptyList(), start.plusDays(41)).finished)
        val after = Blocks.progress(b, emptyList(), start.plusDays(42))
        assertTrue(after.finished)
        assertEquals(6, after.weekNumber)
        assertEquals(1.0, after.timeFraction)
    }

    @Test fun headlineFraction() {
        val recs = listOf(gym(start.plusDays(1), 80.0), gym(start.plusDays(8), 90.0))
        val p = Blocks.progress(block(), recs, start.plusDays(10))
        assertEquals(90.0, p.currentKg)
        assertEquals(0.5, p.headlineFraction, 1e-9)
        assertFalse(p.headlineReached)
        assertEquals(2, p.sessionsDone)
        assertTrue(p.wins.any { it.contains("up from 80 to 90") })
        assertTrue(p.paceText.isNotBlank())
    }

    @Test fun reachedIsFullAndNeverNegative() {
        val p = Blocks.progress(block(), listOf(gym(start.plusDays(2), 102.5)), start.plusDays(3))
        assertTrue(p.headlineReached)
        assertEquals(1.0, p.headlineFraction)
        val none = Blocks.progress(block(), emptyList(), start.plusDays(3))
        assertEquals(0.0, none.headlineFraction)
        assertEquals(80.0, none.currentKg)
        assertTrue(none.wins.isNotEmpty())
    }

    @Test fun ignoresSessionsOutsideBlock() {
        val recs = listOf(gym(start.minusDays(3), 95.0), gym(start.plusDays(50), 99.0))
        val p = Blocks.progress(block(), recs, start.plusDays(60))
        assertEquals(0, p.sessionsDone)
        assertEquals(80.0, p.currentKg)
    }

    @Test fun extras() {
        val b = block().copy(extras = listOf(
            ExtraGoal(ExtraKind.SESSIONS_PER_WEEK, 2), ExtraGoal(ExtraKind.TREADMILL_MINUTES, 60), ExtraGoal(ExtraKind.TOTAL_SESSIONS, 3),
        ))
        val recs = listOf(gym(start, 80.0), tm(start.plusDays(1), 30), tm(start.plusDays(8), 30))
        val p = Blocks.progress(b, recs, start.plusDays(9))
        assertEquals(3, p.extras[0].done); assertEquals(12, p.extras[0].total)
        assertEquals(60, p.extras[1].done); assertEquals(1.0, p.extras[1].fraction)
        assertEquals(1.0, p.extras[2].fraction)
        assertEquals(listOf(2, 1, 0, 0, 0, 0), p.sessionsPerWeek)
    }

    @Test fun startingEndsActiveBlock() {
        val s0 = Blocks.start(BlockState(), block(id = "a"), start)
        val today = start.plusDays(10)
        val s1 = Blocks.start(s0, block(id = "b", s = today), today)
        assertEquals(today, s1.blocks.first { it.id == "a" }.endedOn)
        assertEquals("b", Blocks.current(s1, today)?.id)
        assertEquals(listOf("a"), Blocks.finished(s1, today).map { it.id })
    }

    @Test fun timeUpIsFinishedNotCurrent() {
        val s = BlockState(listOf(block()))
        assertNotNull(Blocks.current(s, start.plusDays(41)))
        assertNull(Blocks.current(s, start.plusDays(42)))
        assertEquals(1, Blocks.finished(s, start.plusDays(42)).size)
    }

    @Test fun finishEarlyAndExtend() {
        val s = BlockState(listOf(block()))
        val ext = Blocks.extend(s, start.plusDays(5), 2)
        assertEquals(8, ext.blocks[0].weeks)
        assertEquals(12, Blocks.extend(Blocks.extend(ext, start, 9), start, 9).blocks[0].weeks)
        val fin = Blocks.finishEarly(s, start.plusDays(5))
        assertNull(Blocks.current(fin, start.plusDays(5)))
        assertEquals(start.plusDays(5), fin.blocks[0].lastDay)
    }

    @Test fun suggestionRounding() {
        val recs = listOf(gym(start, 91.5))
        val s = BlockState(listOf(block(weeks = 5)))
        val next = Blocks.suggestNext(s, recs, start.plusDays(50))
        assertEquals(5, next.weeks)
        assertEquals(92.5, next.headline.startKg)
        assertEquals(97.5, next.headline.targetKg)
        assertEquals(0.0, next.headline.targetKg % 2.5)
        val other = BlockState(listOf(block().copy(headline = BlockTarget("rdl", 80.0, 70.0))))
        val nx = Blocks.suggestNext(other, listOf(gym(start, 72.5, "rdl")), start.plusDays(50))
        assertEquals("rdl", nx.headline.exerciseId)
        assertEquals(75.0, nx.headline.targetKg)
        // no history: library start + step
        assertEquals(85.0, Blocks.suggestNext(BlockState(), emptyList(), start).headline.targetKg)
        assertEquals(6, Blocks.suggestNext(BlockState(), emptyList(), start).weeks)
    }

    @Test fun exportContent() {
        val recs = listOf(
            gym(start.plusDays(1), 80.0, knee = 3), gym(start.plusDays(8), 90.0, knee = 1),
            gym(start.plusDays(9), 60.0, "rdl", reps = 4, target = 8), tm(start.plusDays(10), 30),
        )
        val t = BlockExport.text(block(), recs, start.plusDays(20))
        assertTrue(t.contains("Headline goal: Squat 100 kg"))
        assertTrue(t.contains("Headline outcome:"))
        assertTrue(t.contains("Squat: 80 / 90 / best 90"))
        assertTrue(t.contains("Romanian deadlift: 60 / 60 / best 60"))
        assertTrue(t.contains("Week 1:"))
        assertTrue(t.contains("30 treadmill min"))
        assertTrue(t.contains("KNEE: average 2.0/10"))
        assertTrue(t.contains("4/8 at 60"))
        assertTrue(t.contains("suggest the next 4-8 week goal"))
    }

    @Test fun jsonRoundTripAndOldJson() {
        val json = Json { ignoreUnknownKeys = true }
        val st = BlockState(listOf(block().copy(extras = listOf(ExtraGoal(ExtraKind.TOTAL_SESSIONS, 12)), endedOn = start.plusDays(9), note = "x")))
        assertEquals(st, json.decodeFromString(BlockState.serializer(), json.encodeToString(BlockState.serializer(), st)))
        val old = """{"headlineKg":100.0}"""
        assertEquals(BlockState(), json.decodeFromString(Profile.serializer(), old).blocks)
        assertEquals(BlockState(), json.decodeFromString(BlockState.serializer(), """{"version":1}"""))
        val t = json.decodeFromString(TrainingBlock.serializer(), """{"id":"z","startDate":"2026-11-02"}""")
        assertEquals(Blocks.DEFAULT_WEEKS, t.weeks)
    }
}
