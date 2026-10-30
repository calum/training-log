package com.calum.traininglog.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreRoutineTest {
    private val sizes = 10..30

    @Test
    fun totalMatchesRequestedMinutes() {
        for (knee in listOf(false, true)) for (m in sizes) for (seed in 0L..6L) {
            val s = CoreRoutine.build(m, seed = seed, kneeSensitive = knee)
            assertTrue(kotlin.math.abs(s.totalSec - m * 60) <= 60, "m=$m seed=$seed total=${s.totalSec}")
            assertEquals(s.totalSec, s.steps.sumOf { it.seconds })
            assertTrue(s.steps.all { it.seconds >= 10 }, "short step at m=$m: ${s.steps.minOf { it.seconds }}")
        }
    }

    @Test
    fun structureIsWarmupThreeBlocksCooldown() {
        for (m in sizes) {
            val s = CoreRoutine.build(m, seed = 3)
            assertEquals(CoreKind.WARMUP, s.steps.first().kind)
            assertEquals(CoreKind.COOLDOWN, s.steps.last().kind)
            assertEquals(3, s.blocks.size)
            assertTrue(s.blocks.all { it.exercises.size in 2..4 })
            assertNull(s.steps.last().nextName)
            // Kinds never go backwards: warm-up, then work/rest, then cool-down.
            val order = s.steps.map { it.kind }.map { if (it == CoreKind.REST) CoreKind.WORK else it }
            assertEquals(order.sortedBy { it.ordinal }, order)
            // Every work step is followed by rest or something else, and next names point to real steps.
            s.steps.dropLast(1).forEach { assertTrue(it.nextName != null, "no next for ${it.name}") }
            // Unilateral exercises get both sides.
            s.steps.filter { it.kind == CoreKind.WORK && CoreLibrary.get(it.exerciseId!!)!!.unilateral }.groupBy { it.exerciseId }.values
                .forEach { v -> assertEquals(v.count { it.side == CoreSide.LEFT }, v.count { it.side == CoreSide.RIGHT }) }
            assertEquals(2, s.cooldown.count { it.side == CoreSide.LEFT })
        }
    }

    @Test
    fun neverRepeatsLastSession() {
        for (knee in listOf(false, true)) for (m in sizes) for (seed in 0L..8L) {
            val first = CoreRoutine.build(m, seed = seed, kneeSensitive = knee)
            val second = CoreRoutine.build(m, last = first.exerciseIds, seed = seed + 100, kneeSensitive = knee)
            val overlap = first.exerciseIds.intersect(second.exerciseIds.toSet())
            assertTrue(overlap.isEmpty(), "m=$m knee=$knee seed=$seed overlap=$overlap")
        }
    }

    @Test
    fun kneeSensitiveDropsKneeLoadMoves() {
        for (m in sizes) for (seed in 0L..20L) {
            val s = CoreRoutine.build(m, seed = seed, kneeSensitive = true, level = CoreLevel.ADVANCED)
            assertTrue(s.exerciseIds.none { CoreLibrary.get(it)!!.kneeLoad }, "knee move in m=$m seed=$seed")
        }
    }

    @Test
    fun kneeMovesCanAppearWhenKneeIsFine() {
        val seen = (0L..80L).flatMap { CoreRoutine.build(30, seed = it, kneeSensitive = false).exerciseIds }.toSet()
        assertTrue(seen.any { CoreLibrary.get(it)!!.kneeLoad })
    }

    @Test
    fun levelFiltersHarderMoves() {
        for (seed in 0L..20L) {
            val s = CoreRoutine.build(20, level = CoreLevel.BEGINNER, seed = seed)
            assertTrue(s.exerciseIds.all { CoreLibrary.get(it)!!.level == CoreLevel.BEGINNER })
        }
    }

    @Test
    fun seedsGiveVariety() {
        val mixes = (0L..10L).map { CoreRoutine.build(20, seed = it).exerciseIds }.toSet()
        assertTrue(mixes.size > 5)
        assertEquals(CoreRoutine.build(20, seed = 4).steps, CoreRoutine.build(20, seed = 4).steps)
    }

    @Test
    fun namesHaveNoCommas() {
        CoreLibrary.all.forEach { assertTrue(!it.name.contains(',') && !it.name.contains("(felt"), it.name) }
    }

    @Test
    fun noJumpingOrImpactMoves() {
        val bad = listOf("jump", "burpee", "high knee", "hop", "squat jump", "jack")
        CoreLibrary.all.forEach { e -> bad.forEach { assertTrue(!e.name.lowercase().contains(it), e.name) } }
    }

    @Test
    fun noteRoundTrips() {
        val s = CoreRoutine.build(15, seed = 2)
        val names = s.steps.filter { it.kind == CoreKind.WORK }.map { it.name }.distinct()
        assertEquals(s.exerciseIds.toSet(), CoreLibrary.idsFromNote(CoreRoutine.note(names, "just right")).toSet())
        assertEquals(emptyList(), CoreLibrary.idsFromNote("Gym stuff"))
        assertEquals(emptyList(), CoreLibrary.idsFromNote(null))
    }

    @Test
    fun runAdvancesCuesAndFinishes() {
        val steps = listOf(
            CoreStep("A", "", 10, CoreKind.WORK), CoreStep("Rest", "", 5, CoreKind.REST), CoreStep("B", "", 10, CoreKind.COOLDOWN),
        )
        var run = CoreRun(steps)
        val events = mutableListOf<CoreEvent>()
        repeat(250) {
            val (r, e) = run.advance(100)
            run = r
            if (e != null) events += e
        }
        assertEquals(listOf(CoreEvent.PRE_WARNING, CoreEvent.CHANGE, CoreEvent.CHANGE, CoreEvent.DONE), events)
        assertTrue(run.finished)
        assertEquals(25, run.completedSec)
        assertEquals(listOf("A"), run.doneWorkNames())
    }

    @Test
    fun pauseHoldsTimeAndSkipMarksSkipped() {
        val steps = listOf(CoreStep("A", "", 30, CoreKind.WORK), CoreStep("B", "", 30, CoreKind.WORK))
        var run = CoreRun(steps).advance(12_000).first
        assertEquals(18, run.secondsLeft)
        run = run.pause()
        assertEquals(run, run.advance(5000).first)
        run = run.resume().skip().first
        assertEquals(1, run.index)
        assertEquals(emptyList(), run.doneWorkNames())
        assertEquals(listOf("B"), run.copy(elapsedMs = 11_000).doneWorkNames())
        assertEquals(CoreEvent.DONE, run.skip().second)
    }
}
