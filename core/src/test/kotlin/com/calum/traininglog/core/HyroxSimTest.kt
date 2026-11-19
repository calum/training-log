package com.calum.traininglog.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HyroxSimTest {
    private val D = HyroxDivision.MEN_OPEN
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun day(n: Int) = LocalDate.parse("2027-01-18").plusDays(n.toLong())

    private fun plan(
        f: SimFormat = SimFormat.FULL, load: Double = 1.0, mode: RunMode = RunMode.TREADMILL, settings: UserSettings = UserSettings(),
        division: HyroxDivision = D, target: Int? = null, pace: Int? = null,
    ) = HyroxSim.build(f, division, settings, load, mode, pace, target)

    // ---- legs, order and counts

    @Test fun legOrderAndCounts() {
        val full = plan()
        assertEquals(16, full.raceLegs.size)
        assertEquals(8, full.runCount); assertEquals(8, full.stationCount)
        assertEquals(List(16) { if (it % 2 == 0) SimLegKind.RUN else SimLegKind.STATION }, full.raceLegs.map { it.kind })
        assertEquals(HyroxStation.entries.toList(), full.raceLegs.filter { it.kind == SimLegKind.STATION }.map { it.station })
        assertEquals((0 until 16).toList(), full.raceLegs.map { it.raceIndex })
        // With the transitions: 8 runs + 8 stations + 15 transitions, none after the last station.
        assertEquals(31, full.legs.size)
        assertEquals(15, full.transitionCount)
        assertEquals((0 until 31).toList(), full.legs.map { it.index })
        assertEquals(List(31) { when { it % 2 == 1 -> SimLegKind.TRANSITION; it % 4 == 0 -> SimLegKind.RUN; else -> SimLegKind.STATION } }, full.legs.map { it.kind })
        assertEquals(SimLegKind.STATION, full.legs.last().kind)
        assertTrue(full.legs.filter { it.kind == SimLegKind.TRANSITION }.all { it.raceIndex == -1 && it.allowanceSec == 45 && it.prep.isNotBlank() })
        assertEquals(15 * 2 + 1, full.legs.size)
        assertEquals(15, plan(SimFormat.FIRST_HALF).legs.size)
        assertEquals(7, plan(SimFormat.SECOND_HALF).transitionCount)
        assertTrue(full.raceLegs.filter { it.kind == SimLegKind.RUN }.all { it.distanceM == 1000 })

        val first = plan(SimFormat.FIRST_HALF)
        assertEquals(8, first.raceLegs.size)
        assertEquals(listOf("skierg", "sled_push", "sled_pull", "burpee_broad_jump"), first.raceLegs.filter { it.station != null }.map { it.station!!.id })
        val second = plan(SimFormat.SECOND_HALF)
        assertEquals(8, second.raceLegs.size)
        assertEquals(listOf("row", "farmers_carry", "sandbag_lunges", "wall_balls"), second.raceLegs.filter { it.station != null }.map { it.station!!.id })
        assertEquals(SimLegKind.RUN, second.raceLegs.first().kind)
        assertEquals(SimLegKind.STATION, second.raceLegs.last().kind)
        assertEquals(16, SimFormat.FULL.legCount); assertEquals(8, SimFormat.FIRST_HALF.legCount)
        assertEquals("Simulation: full", SimFormat.FULL.dayLabel)
        assertEquals("Simulation: first half", SimFormat.FIRST_HALF.dayLabel)
        assertEquals("Simulation: second half", SimFormat.SECOND_HALF.dayLabel)
    }

    // ---- loads and reps

    @Test fun raceLoadsPerDivisionAndOverrides() {
        fun station(p: SimPlan, s: HyroxStation) = p.raceLegs.first { it.station == s }
        val open = plan()
        assertEquals(152.0, station(open, HyroxStation.SLED_PUSH).weightKg)
        assertEquals(103.0, station(open, HyroxStation.SLED_PULL).weightKg)
        assertEquals(24.0, station(open, HyroxStation.FARMERS_CARRY).weightKg)
        assertEquals(20.0, station(open, HyroxStation.SANDBAG_LUNGES).weightKg)
        assertEquals(6.0, station(open, HyroxStation.WALL_BALLS).weightKg)
        assertEquals(100, station(open, HyroxStation.WALL_BALLS).reps)
        assertNull(station(open, HyroxStation.WALL_BALLS).distanceM)
        assertEquals(1000, station(open, HyroxStation.SKIERG).distanceM)
        assertNull(station(open, HyroxStation.SKIERG).weightKg)
        assertEquals(80, station(open, HyroxStation.BURPEE_BROAD_JUMP).distanceM)
        assertEquals(200, station(open, HyroxStation.FARMERS_CARRY).distanceM)
        assertEquals(50, station(open, HyroxStation.SLED_PUSH).distanceM)

        val women = plan(division = HyroxDivision.WOMEN_OPEN)
        assertEquals(102.0, station(women, HyroxStation.SLED_PUSH).weightKg)
        assertEquals(4.0, station(women, HyroxStation.WALL_BALLS).weightKg)
        val pro = plan(division = HyroxDivision.MEN_PRO)
        assertEquals(202.0, station(pro, HyroxStation.SLED_PUSH).weightKg)

        // The user's own race weights win.
        val edited = UserSettings(hyroxWeightOverrides = mapOf("sled_push" to 140.0, "wall_balls" to 9.0))
        val e = plan(settings = edited)
        assertEquals(140.0, station(e, HyroxStation.SLED_PUSH).weightKg)
        assertEquals(9.0, station(e, HyroxStation.WALL_BALLS).weightKg)
        assertEquals(103.0, station(e, HyroxStation.SLED_PULL).weightKg)
    }

    @Test fun loadFactorScalesWeightsAndRoundsTo2p5() {
        fun w(p: SimPlan, s: HyroxStation) = p.raceLegs.first { it.station == s }.weightKg!!
        val p90 = plan(load = 0.9)
        assertEquals(137.5, w(p90, HyroxStation.SLED_PUSH))   // 136.8 -> 137.5
        assertEquals(92.5, w(p90, HyroxStation.SLED_PULL))    // 92.7 -> 92.5
        assertEquals(22.5, w(p90, HyroxStation.FARMERS_CARRY)) // 21.6 -> 22.5
        assertEquals(17.5, w(p90, HyroxStation.SANDBAG_LUNGES)) // 18 -> 17.5
        assertEquals(5.0, w(p90, HyroxStation.WALL_BALLS))    // 5.4 -> 5
        val p80 = plan(load = 0.8)
        assertEquals(122.5, w(p80, HyroxStation.SLED_PUSH))   // 121.6 -> 122.5
        assertEquals(82.5, w(p80, HyroxStation.SLED_PULL))    // 82.4 -> 82.5
        assertEquals(20.0, w(p80, HyroxStation.FARMERS_CARRY)) // 19.2 -> 20
        assertEquals(15.0, w(p80, HyroxStation.SANDBAG_LUNGES))
        val p70 = plan(load = 0.7)
        assertEquals(107.5, w(p70, HyroxStation.SLED_PUSH))   // 106.4 -> 107.5
        // Never below one 2.5 kg step.
        assertEquals(2.5, w(plan(division = HyroxDivision.WOMEN_OPEN, load = 0.7), HyroxStation.WALL_BALLS)) // 2.8 -> 2.5
        // Reps and distances stay standard at any load; unloaded stations have no weight.
        val light = plan(load = 0.7)
        assertEquals(100, light.raceLegs.first { it.station == HyroxStation.WALL_BALLS }.reps)
        assertEquals(80, light.raceLegs.first { it.station == HyroxStation.BURPEE_BROAD_JUMP }.distanceM)
        assertNull(light.raceLegs.first { it.station == HyroxStation.ROW }.weightKg)
        assertTrue(p80.notes.any { "80%" in it })
        assertEquals(152.0, HyroxSim.scaleKg(152.0, 1.0))
        assertEquals(10.0, HyroxSim.scaleKg(10.0, 1.0))
    }

    // ---- run modes

    @Test fun runModeDefaultsFollowTheKneeStage() {
        assertEquals(RunMode.ROW, HyroxSim.defaultRunMode(RunStage.NONE))
        assertEquals(RunMode.ROW, HyroxSim.defaultRunMode(RunStage.RUN_WALK))
        assertEquals(RunMode.ROW, HyroxSim.defaultRunMode(RunStage.CONTINUOUS))
        assertEquals(RunMode.TREADMILL, HyroxSim.defaultRunMode(RunStage.INTERVALS))
        assertEquals(RunMode.TREADMILL, HyroxSim.defaultRunMode(RunStage.RACE_RUNS))
        assertEquals(RunMode.OUTSIDE, HyroxSim.defaultRunMode(RunStage.RACE_RUNS, treadmillAvailable = false))
        assertEquals(
            "Running is not ready yet, so the run legs are a rower 1000 m: same effort, no knee load.",
            HyroxSim.runModeNote(RunMode.ROW, RunStage.NONE),
        )
        assertTrue("SkiErg 1000 m" in HyroxSim.runModeNote(RunMode.SKIERG, RunStage.RUN_WALK))
        assertTrue("3 km bike" in HyroxSim.runModeNote(RunMode.BIKE, RunStage.NONE))
        assertTrue("incline walk" in HyroxSim.runModeNote(RunMode.WALK_INCLINE, RunStage.NONE))
        assertTrue("real 1 km runs" in HyroxSim.runModeNote(RunMode.TREADMILL, RunStage.RACE_RUNS))
        assertFalse("not ready" in HyroxSim.runModeNote(RunMode.TREADMILL, RunStage.INTERVALS))
        // No note ever shouts or blames.
        for (m in RunMode.entries) for (s in RunStage.entries) {
            val n = HyroxSim.runModeNote(m, s)
            assertFalse(n.contains("!") || n.contains("must", true) || n.contains("fail", true), n)
        }
    }

    @Test fun runLegsFollowTheMode() {
        assertTrue(plan(mode = RunMode.ROW).raceLegs.filter { it.kind == SimLegKind.RUN }.all { it.distanceM == 1000 && it.runMode == RunMode.ROW })
        assertTrue(plan(mode = RunMode.BIKE).raceLegs.filter { it.kind == SimLegKind.RUN }.all { it.distanceM == 3000 })
        assertEquals(listOf(true, true, false, false, false, false), RunMode.entries.map { it.running })
        assertEquals("1 km", RunMode.TREADMILL.distanceText); assertEquals("3 km", RunMode.BIKE.distanceText)
        val p = plan(mode = RunMode.ROW)
        assertEquals("Run leg 3 of 8 · Rower 1000 m", HyroxSim.legTitle(p, p.raceLegs[4]))
        assertEquals("Station 3 · Sled Pull", HyroxSim.legTitle(p, p.raceLegs[5]))
        val t = plan()
        assertEquals("Run 3 of 8 · 1 km", HyroxSim.legTitle(t, t.raceLegs[4]))
        assertEquals("50 m · 152 kg", HyroxSim.legLoadText(t.raceLegs[3]))
        assertEquals("100 reps · 6 kg", HyroxSim.legLoadText(t.raceLegs.last()))
        assertEquals("200 m · 24 kg each", HyroxSim.legLoadText(t.raceLegs[11]))
        assertEquals("1000 m", HyroxSim.legLoadText(t.raceLegs[1]))
    }

    // ---- pacing

    @Test fun targetSplitsSumToTheTargetWithSensibleProportions() {
        for (f in SimFormat.entries) for (total in listOf(3600, 4321, 5400, 5999, 1800)) {
            val p = plan(f, target = total)
            val legs = p.legs
            assertTrue(legs.all { it.targetSec != null })
            assertEquals(total, legs.sumOf { it.targetSec!! }, "$f $total")
            val trans = legs.filter { it.kind == SimLegKind.TRANSITION }.sumOf { it.targetSec!! }
            assertTrue(trans.toDouble() / total in 0.045..0.055, "transition share ${trans.toDouble() / total}")
            val run = legs.filter { it.kind == SimLegKind.RUN }.sumOf { it.targetSec!! }
            val share = run.toDouble() / (total - trans)
            assertTrue(share in 0.55..0.60, "run share $share")
            val runs = legs.filter { it.kind == SimLegKind.RUN }.map { it.targetSec!! }
            assertTrue(runs.max() - runs.min() <= 1, "equal run legs")
            assertEquals(total.toDouble() / 60, p.estimatedMinutes.toDouble(), 1.0)
            assertEquals(total, p.targetTotalSec)
        }
        // Station shares follow typical durations: wall balls and burpees long, farmers short.
        val full = plan(target = 5400)
        fun st(s: HyroxStation) = full.raceLegs.first { it.station == s }.targetSec!!
        assertTrue(st(HyroxStation.WALL_BALLS) > st(HyroxStation.SLED_PUSH))
        assertTrue(st(HyroxStation.BURPEE_BROAD_JUMP) > st(HyroxStation.FARMERS_CARRY))
        assertTrue(st(HyroxStation.FARMERS_CARRY) < st(HyroxStation.SKIERG))
        // A treadmill leg gets a speed from its target.
        val run = full.raceLegs.first { it.kind == SimLegKind.RUN }
        assertNotNull(run.speedKmh)
        assertEquals(Math.round(3600.0 / run.targetSec!! * 10) / 10.0, run.speedKmh)
        assertNull(plan(target = 5400, mode = RunMode.ROW).raceLegs.first().speedKmh)
        assertTrue(full.notes.any { "ESTIMATES" in it })
    }

    @Test fun noTargetGivesEffortGuidanceNotTimes() {
        val p = plan()
        assertTrue(p.raceLegs.filter { it.kind == SimLegKind.STATION }.all { it.targetSec == null })
        assertTrue(p.raceLegs.filter { it.kind == SimLegKind.RUN }.all { it.targetSec == null })
        assertNull(p.targetTotalSec)
        assertTrue(p.notes.any { "easier than you think" in it && "RPE 6-7" in it && "last two legs" in it })
        assertTrue(p.notes.any { "RPE 7-8" in it && "rehearsal" in it })
        assertTrue(p.estimatedMinutes in 75..100, "typical estimate ${p.estimatedMinutes}")
        // A known treadmill pace gives the countdown, nothing else gets a time.
        val withPace = plan(pace = 360)
        assertTrue(withPace.raceLegs.filter { it.kind == SimLegKind.RUN }.all { it.targetSec == 360 })
        assertTrue(withPace.raceLegs.filter { it.kind == SimLegKind.STATION }.all { it.targetSec == null })
        assertEquals(10.0, withPace.raceLegs.first().speedKmh)
        // The pace only matters on a treadmill.
        assertTrue(plan(mode = RunMode.ROW, pace = 360).raceLegs.all { it.targetSec == null })
        // No wording that treats a time as a test.
        for (n in p.notes + plan(target = 5400).notes) assertFalse(listOf("fail", "must beat", "miss").any { n.contains(it, true) }, n)
        assertTrue(plan(SimFormat.FIRST_HALF).estimatedMinutes < p.estimatedMinutes)
    }

    @Test fun tipsAreOneLinersAndRunTipsChange() {
        val p = plan()
        assertTrue(p.raceLegs.all { it.tip.isNotBlank() && '\n' !in it.tip })
        val runs = p.raceLegs.filter { it.kind == SimLegKind.RUN }
        assertTrue("Easier than you think" in runs.first().tip)
        assertTrue("Last" in runs.last().tip)
        assertEquals(HyroxGuide.of(HyroxStation.SKIERG).pacingTip, p.raceLegs[1].tip)
    }

    // ---- levels, assign

    @Test fun levelForPicksTheLadderRungMatchingTheLoad() {
        val none = emptyMap<String, Double>()
        assertEquals(5, HyroxSim.levelFor(HyroxStation.SLED_PUSH, D, none, 152.0))
        assertEquals(4, HyroxSim.levelFor(HyroxStation.SLED_PUSH, D, none, 137.5)) // near race load (0.85 = 130)
        assertEquals(3, HyroxSim.levelFor(HyroxStation.SLED_PUSH, D, none, 107.5)) // 0.7 = 107.5
        assertEquals(5, HyroxSim.levelFor(HyroxStation.ROW, D, none, null))
        assertEquals(1, HyroxSim.levelFor(HyroxStation.SLED_PUSH, D, none, 10.0))
        assertEquals(5, HyroxSim.levelFor(HyroxStation.SLED_PUSH, D, none, 160.0)) // capped at the race standard
    }

    private fun legLogs(plan: SimPlan, skip: Set<Int> = emptySet(), upTo: Int = plan.raceLegs.size, secs: (SimLeg) -> Int = { 60 + it.index }, rpe: Int? = 7): List<StationLog> =
        plan.raceLegs.filter { it.raceIndex < upTo && it.raceIndex !in skip }.map { l0 ->
            val l = l0.copy(index = l0.raceIndex) // the lambdas below think in race-leg numbers
            if (l.kind == SimLegKind.RUN) StationLog("run", distanceM = l.distanceM, seconds = secs(l), rpe = rpe)
            else StationLog(l.station!!.id, l.weightKg, l.distanceM, l.reps, secs(l), rpe, level = 5, plannedWork = l.distanceM ?: l.reps)
        }

    @Test fun assignPlacesLogsOnLegsAndSkipsGaps() {
        val p = plan(SimFormat.FIRST_HALF)
        val all = legLogs(p)
        val meta = SimMeta(SimFormat.FIRST_HALF)
        val a = HyroxSim.assign(meta, all)
        assertTrue(a.all { it != null })
        assertEquals(all, a.filterNotNull())
        // Skipped legs (named in the meta) leave a gap, even with two runs in a row.
        val skipped = setOf(1, 2)
        val logs = legLogs(p, skip = skipped)
        val b = HyroxSim.assign(meta.copy(skipped = skipped.toList()), logs)
        assertEquals(listOf(true, false, false, true, true, true, true, true), b.map { it != null })
        assertEquals("sled_push", b[3]!!.stationId)
        // Ended early: the rest is unreached.
        val c = HyroxSim.assign(meta.copy(endedEarly = true), legLogs(p, upTo = 3))
        assertEquals(3, c.count { it != null })
        assertTrue(c.drop(3).all { it == null })
    }

    // ---- compare

    private fun record(id: String, n: Int, meta: SimMeta, logs: List<StationLog>) = SessionRecord(
        id, day(n), SessionType.HYROX, 90, 70, Feeling.GOOD, dayLabel = meta.format.dayLabel, stations = logs, sim = meta,
    )

    @Test fun resultReadsARecordAndComparesWithTheLastOne() {
        val p = plan(SimFormat.FIRST_HALF, target = 2700)
        val prevLogs = legLogs(p, secs = { 300 })
        val prevMeta = SimMeta(SimFormat.FIRST_HALF, 1.0, RunMode.TREADMILL, 2400, 2700)
        val prev = SimResult.from(record("a", 0, prevMeta, prevLogs))!!
        assertEquals(2400, prev.totalSec)
        assertEquals(8, prev.legsDone)
        assertEquals(1200, prev.runSec); assertEquals(1200, prev.stationSec)
        assertEquals(0.5, prev.runShare)
        assertEquals("Running 50% · stations 50%", prev.shareText)
        assertEquals(p.raceLegs.map { it.targetSec }, prev.legs.map { it.targetSec }) // rebuilt from the saved target

        val curLogs = legLogs(p, secs = { if (it.index == 1) 250 else if (it.index == 3) 330 else 300 })
        val cur = SimResult.from(record("b", 7, SimMeta(SimFormat.FIRST_HALF, 1.0, RunMode.TREADMILL, 2380, 2700), curLogs))!!
        val cmp = SimResult.compare(cur, prev)
        assertEquals(8, cmp.legsCompared)
        assertEquals(-20, cmp.totalDeltaSec) // -50 on leg 1, +30 on leg 3, the rest the same
        // legs 1 and 3 differ
        assertEquals(listOf(1 to -50, 3 to 30), cmp.legs.filter { it.deltaSec != 0 }.map { it.index to it.deltaSec })
        assertEquals("0:50 quicker", cmp.legs.first { it.index == 1 }.text)
        assertEquals("0:30 slower", cmp.legs.first { it.index == 3 }.text)
        assertEquals("0:20 quicker than last time.", cmp.totalText)

        // Wording.
        assertEquals("2:10 quicker", SimResult.deltaText(-130))
        assertEquals("0:25 slower, that is fine", SimResult.deltaText(25))
        assertEquals("0:25 slower", SimResult.deltaText(25, withReassurance = false))
        assertEquals("the same", SimResult.deltaText(0))

        // A genuinely quicker and a slower day, over all legs.
        val quick = SimResult.from(record("c", 8, SimMeta(SimFormat.FIRST_HALF, 1.0, RunMode.TREADMILL, 2000, 2700), legLogs(p, secs = { 250 })))!!
        val q = SimResult.compare(quick, prev)
        assertEquals(-400, q.totalDeltaSec)
        assertEquals("6:40 quicker than last time.", q.totalText)
        val slow = SimResult.from(record("d", 9, SimMeta(SimFormat.FIRST_HALF, 1.0, RunMode.TREADMILL, 2500, 2700), legLogs(p, secs = { 310 })))!!
        val s = SimResult.compare(slow, prev)
        assertEquals(80, s.totalDeltaSec)
        assertTrue(s.totalText.startsWith("1:20 slower than last time") && "that is fine" in s.totalText, s.totalText)

        // No previous: a kind starting point.
        val first = SimResult.compare(prev, null)
        assertNull(first.totalDeltaSec)
        assertTrue("starting point" in first.totalText)
        assertTrue(first.legs.isEmpty())
    }

    @Test fun compareOnlyUsesLegsDoneInBoth() {
        val p = plan(SimFormat.FIRST_HALF)
        val prev = SimResult.from(record("a", 0, SimMeta(SimFormat.FIRST_HALF, totalSec = 480), legLogs(p, secs = { 60 })))!!
        val skipped = setOf(2, 3)
        val curRec = record("b", 5, SimMeta(SimFormat.FIRST_HALF, totalSec = 360, skipped = skipped.toList()), legLogs(p, skip = skipped, secs = { 50 }))
        val cur = SimResult.from(curRec)!!
        assertEquals(6, cur.legsDone)
        val cmp = SimResult.compare(cur, prev)
        assertEquals(6, cmp.legsCompared)
        assertEquals(-60, cmp.totalDeltaSec)
        assertTrue("on the 6 legs you did both times" in cmp.totalText, cmp.totalText)
        assertTrue(cmp.legs.none { it.index in skipped })
        // A different format is never compared.
        val other = SimResult.from(record("c", 6, SimMeta(SimFormat.SECOND_HALF, totalSec = 600), legLogs(plan(SimFormat.SECOND_HALF))))!!
        assertNull(SimResult.compare(other, prev).totalDeltaSec)
    }

    @Test fun previousOfFindsTheSameFormatBefore() {
        val m = { f: SimFormat -> SimMeta(f, totalSec = 100) }
        val a = record("a", 0, m(SimFormat.FIRST_HALF), emptyList())
        val b = record("b", 3, m(SimFormat.FULL), emptyList())
        val c = record("c", 6, m(SimFormat.FIRST_HALF), emptyList())
        val d = record("d", 9, m(SimFormat.FIRST_HALF), emptyList())
        assertEquals("c", SimResult.previousOf(listOf(a, b, c, d), d)!!.id)
        assertEquals("a", SimResult.previousOf(listOf(a, b, c, d), c)!!.id)
        assertNull(SimResult.previousOf(listOf(a, b, c, d), a))
        assertNull(SimResult.previousOf(listOf(a, b, c, d), b))
        assertEquals(listOf("a", "b", "c", "d"), SimResult.history(listOf(d, c, b, a)).map { it.first.id })
    }

    @Test fun workOnPointsAtTheSlowestStationGently() {
        val p = plan(SimFormat.FIRST_HALF, target = 2700)
        // Sled pull takes twice its estimate.
        val pull = p.raceLegs.first { it.station == HyroxStation.SLED_PULL }
        val logs = legLogs(p, secs = { l -> if (l.index == pull.raceIndex) l.targetSec!! * 2 else l.targetSec!! })
        val res = SimResult.from(record("a", 0, SimMeta(SimFormat.FIRST_HALF, totalSec = 3000, targetSec = 2700), logs))!!
        val tip = res.workOn()!!
        assertTrue("Sled Pull" in tip && "not a criticism" in tip, tip)
        assertTrue(HyroxGuide.of(HyroxStation.SLED_PULL).pacingTip in tip)
        // Everything on or under target: nothing to say.
        val easy = SimResult.from(record("b", 1, SimMeta(SimFormat.FIRST_HALF, totalSec = 2000, targetSec = 2700), legLogs(p, secs = { it.targetSec!! - 5 })))!!
        assertNull(easy.workOn())
        // No target: relative to a typical race. Nothing logged: nothing to say.
        val noTarget = plan(SimFormat.FIRST_HALF)
        val skew = legLogs(noTarget, secs = { if (it.station == HyroxStation.SLED_PUSH) 600 else 100 })
        val t = SimResult.from(record("c", 2, SimMeta(SimFormat.FIRST_HALF, totalSec = 1400), skew))!!
        assertTrue("Sled Push" in t.workOn()!!)
        assertNull(SimResult.from(record("d", 3, SimMeta(SimFormat.FIRST_HALF, endedEarly = true), emptyList()))!!.workOn())
        assertNull(SimResult.from(record("e", 3, SimMeta(SimFormat.FIRST_HALF), emptyList()).copy(sim = null)))
    }

    // ---- save, rewards, balance, run stage

    private fun fullRecord(id: String, n: Int, total: Int, meta: SimMeta = SimMeta(SimFormat.FIRST_HALF, 1.0, RunMode.TREADMILL, total), rpe: Int? = 7): SessionRecord {
        val p = plan(meta.format, meta.loadFactor, meta.runMode)
        return record(id, n, meta, legLogs(p, rpe = rpe)).copy(minutesActual = total / 60, minutesAvailable = total / 60)
    }

    @Test fun serializationRoundTripAndOldJson() {
        val r = fullRecord("a", 0, 2400)
        assertEquals(r, json.decodeFromString<SessionRecord>(json.encodeToString(r)))
        val noSim = SessionRecord("x", day(0), SessionType.GYM, 60, 60, Feeling.GOOD)
        val text = json.encodeToString(noSim)
        assertNull(json.decodeFromString<SessionRecord>(text.replace(",\"sim\":null", "")).sim)
        // JSON without any sim key (an older file) decodes, and a minimal sim meta takes its defaults.
        val min = json.decodeFromString<SimMeta>("{\"format\":\"FULL\"}")
        assertEquals(SimMeta(SimFormat.FULL, 1.0, RunMode.TREADMILL, 0, null, emptyList(), false), min)
        assertTrue(min.complete)
        assertFalse(SimMeta(SimFormat.FULL, skipped = listOf(2)).complete)
        assertFalse(SimMeta(SimFormat.FULL, endedEarly = true).complete)
    }

    @Test fun rewardsGiveSimBonusBadgeAndPrsByFormat() {
        val first = fullRecord("s1", 0, 2400)
        val none = Rewards.summary(emptyList(), day(0))
        // The Knee care badge is appended at the very end, after the Hyrox ones.
        assertEquals("hyrox_first_sim", none.badges.first { it.id == "hyrox_first_sim" }.id)
        assertFalse(none.badges.first { it.id == "hyrox_first_sim" }.unlocked)
        val sum = Rewards.summary(listOf(first), day(1))
        assertEquals(day(0), sum.badges.first { it.id == "hyrox_first_sim" }.unlockedOn)
        assertEquals("First simulation", sum.badges.first { it.id == "hyrox_first_sim" }.title)
        val xp = sum.sessionXp.getValue("s1")
        assertEquals(Rewards.HYROX_SIM_BONUS, xp.sim)
        assertTrue("Simulation ${Rewards.HYROX_SIM_BONUS}" in xp.lines)
        val plainXp = Rewards.sessionXp(first.copy(sim = null), emptyList())
        assertEquals(Rewards.HYROX_SIM_BONUS, xp.total - plainXp.total)
        // The first of a format is not a PR; a quicker complete one at the same load is, by format only.
        assertTrue(sum.prs.none { it.exerciseId.startsWith("hyrox_sim:") })
        val quicker = fullRecord("s2", 7, 2300)
        val s2 = Rewards.summary(listOf(first, quicker), day(8))
        val pr = s2.prs.single { it.exerciseId == "hyrox_sim:first_half" }
        assertEquals("s2", pr.sessionId)
        assertEquals("First half simulation: 38:20 (was 40:00)", pr.text)
        // A slower one, a different format, a lighter-load one and an incomplete one are not PRs.
        val slower = fullRecord("s3", 14, 2500)
        val otherFormat = fullRecord("s4", 15, 1000, SimMeta(SimFormat.SECOND_HALF, 1.0, RunMode.TREADMILL, 1000))
        val partial = fullRecord("s5", 16, 100, SimMeta(SimFormat.FIRST_HALF, 1.0, RunMode.TREADMILL, 100, endedEarly = true))
        val s3 = Rewards.summary(listOf(first, quicker, slower, otherFormat, partial), day(20))
        assertEquals(listOf("s2"), s3.prs.filter { it.exerciseId.startsWith("hyrox_sim:") }.map { it.sessionId })
        val lighterFast = fullRecord("s6", 17, 2000, SimMeta(SimFormat.FIRST_HALF, 0.8, RunMode.ROW, 2000))
        assertTrue(Rewards.summary(listOf(first, lighterFast), day(20)).prs.none { it.exerciseId.startsWith("hyrox_sim:") })
        // The celebration names the badge on a first sim.
        val msgs = Rewards.celebration(first, emptyList())
        assertTrue(msgs.first().contains("Simulation"), msgs.joinToString())
    }

    @Test fun stationLegsFeedProgressionAndPrsRunLegsAreIgnoredForLevels() {
        val r = fullRecord("s1", 0, 2400)
        val push = r.stations.filter { it.stationId == "sled_push" }.map { StationEntry(r.date, it) }
        assertEquals(1, push.size)
        // Done at the race standard at effort 7: the next step is the top rung, "race plus".
        assertEquals(6, HyroxProgression.next(HyroxStation.SLED_PUSH, D, push).level)
        assertTrue(ProgressStats.stationBest(listOf(r), HyroxStation.SKIERG, day(1)).any { it.bestSeconds != null })
        assertTrue(ProgressStats.build(listOf(r), day(1)).totals.sessions == 1)
        // Run logs never count as a station.
        assertTrue(HyroxRuns.isRun(r.stations.first()))
        assertNull(HyroxStation.byId("run"))
        // Race standard counts at 100% loads (the sled push meets it), not at 80%.
        assertTrue(HyroxStandards.meets(D, r.stations.first { it.stationId == "sled_push" }))
        assertEquals(Rewards.HYROX_RACE_STANDARD, Rewards.sessionXp(r, emptyList()).race)
        val light = fullRecord("s2", 1, 2400, SimMeta(SimFormat.FIRST_HALF, 0.8, RunMode.ROW, 2400))
        assertFalse(HyroxStandards.meets(D, light.stations.first { it.stationId == "sled_push" }))
    }

    @Test fun runStageIgnoresSubstituteRunLegs() {
        val rower = fullRecord("r1", 0, 2400, SimMeta(SimFormat.FIRST_HALF, 1.0, RunMode.ROW, 2400))
        assertEquals(0, RunStageLogic.runSeconds(rower))
        assertFalse(RunStageLogic.isRun(rower))
        assertEquals(RunStage.NONE, RunStageLogic.stage(listOf(rower.copy(kneePain = 1), rower.copy(id = "r2", date = day(8), kneePain = 1)), day(10)))
        // A real treadmill run leg does count as running.
        val real = fullRecord("r3", 0, 2400, SimMeta(SimFormat.FIRST_HALF, 1.0, RunMode.TREADMILL, 2400))
        assertTrue(RunStageLogic.runSeconds(real) > 0)
        assertTrue(RunStageLogic.isRun(real))
    }

    @Test fun intensityBalanceCountsASimAsMixedHardAndModerate() {
        // RPE 7 everywhere: all legs hard.
        val hard = IntensityBalance.classify(fullRecord("a", 0, 2400, rpe = 7))
        assertEquals(40, hard.hardMin); assertEquals(0, hard.easyMin); assertEquals(0, hard.strengthMin)
        // RPE 6 everywhere: a controlled, moderate session, all easy.
        val moderate = IntensityBalance.classify(fullRecord("b", 0, 2400, rpe = 6))
        assertEquals(TimeSplit(40, 0, 0), moderate)
        // Unrated legs: stations count hard (loaded work), runs easy: a genuine mix.
        val unrated = IntensityBalance.classify(fullRecord("c", 0, 2400, rpe = null))
        assertEquals(40, unrated.easyMin + unrated.hardMin)
        assertTrue(unrated.hardMin in 15..25 && unrated.easyMin in 15..25, "$unrated")
        assertEquals(TimeSplit(5, 0, 0), IntensityBalance.classify(record("d", 0, SimMeta(SimFormat.FULL), emptyList()).copy(minutesActual = 5)))
        // And it flows into the week split without crashing.
        assertNotNull(IntensityBalance.of(listOf(fullRecord("e", 0, 2400)), day(0)).weekLabel.let { it ?: "n/a" })
    }

    // ---- export

    @Test fun claudeExportHasAReadableSplitTable() {
        val r = fullRecord("s1", 0, 2400, SimMeta(SimFormat.FIRST_HALF, 0.9, RunMode.ROW, 2400, targetSec = 2700, skipped = listOf(3)))
            .let { it.copy(stations = legLogs(plan(SimFormat.FIRST_HALF, 0.9, RunMode.ROW), skip = setOf(3), rpe = 8)) }
        val text = Export.summary(listOf(r), day(0), day(0))
        assertTrue("hyrox day Simulation: first half" in text, text)
        assertTrue("Simulation (first half), loads 90%, run legs: rower, total 40:00, target 45:00" in text, text)
        assertTrue("1. Run leg (rower): 1000 m, 1:00, RPE 8" in text, text)
        assertTrue("2. SkiErg: 1000 m, 1:01, RPE 8" in text, text)
        assertTrue("3. Run leg (rower): 1000 m, 1:02, RPE 8" in text, text)
        assertTrue("4. Sled Push: skipped or not done" in text, text)
        assertTrue("5. Run leg (rower)" in text && "8. Burpee Broad Jumps: 80 m" in text, text)
        assertTrue("Running " in text && "stations " in text, text)
        // Ended early marks it.
        val early = r.copy(sim = r.sim!!.copy(endedEarly = true, skipped = emptyList()), stations = legLogs(plan(SimFormat.FIRST_HALF), upTo = 3))
        assertTrue("ended early" in Export.summary(listOf(early), day(0), day(0)))
        // A normal Hyrox session still prints its plain lines.
        val plain = r.copy(sim = null)
        assertFalse("Splits in race order" in Export.summary(listOf(plain), day(0), day(0)))
    }

    @Test fun publicExportIsWhitelistedAndRoundTrips() {
        val secret = "SECRETNOTE-9"
        val base = fullRecord("s1", 3, 2400, SimMeta(SimFormat.FIRST_HALF, 0.8, RunMode.ROW, 2400, targetSec = 2700, skipped = listOf(2, 3)))
        val logs = legLogs(plan(SimFormat.FIRST_HALF, 0.8, RunMode.ROW), skip = setOf(2, 3), rpe = 8) + StationLog("custom:secretstation", 1.0, 1, null, 1, 1)
        val r = base.copy(
            kneePain = 9, feeling = Feeling.ILL, note = secret, minutesAvailable = 4242, stations = logs,
            dayLabel = "SECRETDAY",
        )
        val files = PublicExport.files(listOf(r))
        val csv = files.getValue("data/hyrox-sim/2027-01.csv")
        assertEquals(PublicExport.SIM_HEADER, csv.lines().first())
        assertTrue(csv.lines()[1].startsWith("2027-01-21,1,first_half,80,row,1,run,,1000,,60,8"), csv)
        assertTrue(csv.contains("2027-01-21,1,first_half,80,row,2,skierg,,1000,,61,8"))
        assertFalse(files.containsKey("data/hyrox/2027-01.csv"), "a sim never lands in the plain hyrox file")
        for ((path, content) in files) {
            val all = (path + "\n" + content).lowercase()
            for (bad in listOf("secret", "knee", "feeling", "note", "custom", "4242", "technique", "level", "simulation", "effort", "ill")) {
                if (bad == "ill") assertFalse(Regex("\\bill\\b").containsMatchIn(all)) else assertFalse(bad in all, "'$bad' leaked into $path")
            }
        }
        val back = PublicImport.records(files).single()
        assertEquals(SessionType.HYROX, back.type)
        val sim = back.sim!!
        assertEquals(SimFormat.FIRST_HALF, sim.format); assertEquals(0.8, sim.loadFactor); assertEquals(RunMode.ROW, sim.runMode)
        assertEquals(listOf(2, 3), sim.skipped)
        assertFalse(sim.endedEarly)
        assertEquals(SimFormat.FIRST_HALF.dayLabel, back.dayLabel)
        assertEquals(logs.dropLast(1).map { it.stationId }, back.stations.map { it.stationId })
        assertEquals(logs.dropLast(1).map { it.seconds }, back.stations.map { it.seconds })
        assertEquals(back.stations.sumOf { it.seconds ?: 0 }, sim.totalSec)
        assertEquals(PublicImport.records(files), PublicImport.records(files)) // deterministic ids
        // Two sims on one day stay separate; an early end is remembered.
        val second = fullRecord("s2", 3, 600, SimMeta(SimFormat.FULL, 1.0, RunMode.TREADMILL, 600)).copy(
            stations = legLogs(plan(SimFormat.FULL), upTo = 4), sim = SimMeta(SimFormat.FULL, 1.0, RunMode.TREADMILL, 600, endedEarly = true),
        )
        val two = PublicImport.records(PublicExport.files(listOf(r, second)))
        assertEquals(2, two.size)
        assertTrue(two.any { it.sim!!.format == SimFormat.FULL && it.sim!!.endedEarly && it.stations.size == 4 })
        // Plain hyrox sessions keep their old file and header.
        assertEquals("date,station,weight_kg,distance_m,reps,seconds,rpe", PublicExport.HYROX_HEADER)
    }
}
