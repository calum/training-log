package com.calum.traininglog.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The timed transitions (the "Roxzone") between every run and station. */
class HyroxSimTransitionTest {
    private val D = HyroxDivision.MEN_OPEN
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun plan(f: SimFormat = SimFormat.FULL, mode: RunMode = RunMode.TREADMILL, sec: Int = 45, pace: Int? = 400, target: Int? = null, load: Double = 1.0) =
        HyroxSim.build(f, D, UserSettings(), load, mode, pace, target, transitionSec = sec)

    @Test fun transitionsInterleaveForEveryFormat() {
        for ((f, trans) in listOf(SimFormat.FULL to 15, SimFormat.FIRST_HALF to 7, SimFormat.SECOND_HALF to 7)) {
            val p = plan(f)
            assertEquals(f.legCount + trans, p.legs.size, "$f")
            assertEquals(trans, p.transitionCount)
            // Never two transitions in a row, never first or last; each sits between a run and a station (either way round).
            p.legs.forEachIndexed { i, l ->
                if (l.kind == SimLegKind.TRANSITION) {
                    assertTrue(i > 0 && i < p.legs.lastIndex)
                    assertTrue(p.legs[i - 1].kind != SimLegKind.TRANSITION && p.legs[i + 1].kind != SimLegKind.TRANSITION)
                    assertTrue(p.legs[i - 1].kind != p.legs[i + 1].kind)
                }
            }
            assertEquals(SimLegKind.STATION, p.legs.last().kind)
            assertEquals(f.legCount, p.raceLegs.size)
        }
    }

    @Test fun prepTextComesFromTheNextLeg() {
        val p = plan(SimFormat.FULL, RunMode.TREADMILL)
        fun prepBefore(pred: (SimLeg) -> Boolean) = p.legs.first { it.kind == SimLegKind.TRANSITION && pred(p.legs[it.index + 1]) }.prep
        assertEquals("Reset the SkiErg monitor and check the damper", prepBefore { it.station == HyroxStation.SKIERG })
        assertEquals("Load the sled to 152 kg", prepBefore { it.station == HyroxStation.SLED_PUSH })
        assertEquals("Hook up the pull rope (sled at 103 kg)", prepBefore { it.station == HyroxStation.SLED_PULL })
        assertEquals("Reset the rower monitor", prepBefore { it.station == HyroxStation.ROW })
        assertEquals("Pick up the 2 x 24 kg handles", prepBefore { it.station == HyroxStation.FARMERS_CARRY })
        assertEquals("Get the 20 kg sandbag on your shoulders", prepBefore { it.station == HyroxStation.SANDBAG_LUNGES })
        assertEquals("Grab the 6 kg wall ball and find your line", prepBefore { it.station == HyroxStation.WALL_BALLS })
        assertEquals("Step onto the treadmill and set 9.0 km/h", prepBefore { it.kind == SimLegKind.RUN })
        assertEquals("Shoes laced, water if you need it", plan(mode = RunMode.OUTSIDE).legs.first { it.kind == SimLegKind.TRANSITION && it.index == 3 }.prep)
        assertEquals("Set the bike resistance and check the saddle", plan(mode = RunMode.BIKE).legs[3].prep)
        // The loads follow a lighter attempt.
        assertTrue(plan(load = 0.9).legs.any { it.prep == "Load the sled to 137.5 kg" })
        // Titles name what comes next.
        val t = p.legs[1]
        assertEquals("Transition · next: SkiErg", HyroxSim.legTitle(p, t))
        assertEquals("Transition · next: Run 2", HyroxSim.legTitle(p, p.legs[3]))
        assertEquals("Transition · next: Run leg 2", HyroxSim.legTitle(plan(mode = RunMode.ROW), plan(mode = RunMode.ROW).legs[3]))
    }

    @Test fun allowancePresetsAndSanitising() {
        assertEquals(20, TransitionMode.RACE_LIKE.seconds)
        assertEquals(45, TransitionMode.GYM_SETUP.seconds)
        assertEquals(90, TransitionMode.HOME_SETUP.seconds)
        assertNull(TransitionMode.CUSTOM.seconds)
        assertEquals(TransitionMode.GYM_SETUP, TransitionMode.of(45))
        assertEquals(TransitionMode.CUSTOM, TransitionMode.of(35))
        assertEquals(45, HyroxSim.DEFAULT_TRANSITION_SEC)
        assertEquals(45, UserSettings().hyroxTransitionSec)
        assertEquals(10, HyroxSim.snapTransition(0)); assertEquals(180, HyroxSim.snapTransition(999))
        assertEquals(35, HyroxSim.snapTransition(36)); assertEquals(45, HyroxSim.snapTransition(44))
        assertEquals(10, UserSettings(hyroxTransitionSec = 2).sanitized().hyroxTransitionSec)
        assertEquals(180, UserSettings(hyroxTransitionSec = 500).sanitized().hyroxTransitionSec)
        assertEquals(65, UserSettings(hyroxTransitionSec = 63).sanitized().hyroxTransitionSec)
        // The plan uses the settings value by default and the allowance on each transition.
        assertEquals(90, HyroxSim.build(SimFormat.FULL, D, UserSettings(hyroxTransitionSec = 90)).legs[1].allowanceSec)
        assertEquals(20, plan(sec = 20).legs[1].allowanceSec)
        assertEquals(20, plan(sec = 20).transitionSec)
        // Old saved settings (no key) decode to the default.
        assertEquals(45, json.decodeFromString<UserSettings>("{}").hyroxTransitionSec)
    }

    @Test fun estimatesAndNotesIncludeTheTransitions() {
        val short = plan(sec = 20).estimatedMinutes
        val long = plan(sec = 90).estimatedMinutes
        assertEquals(15 * 70 / 60.0, (long - short).toDouble(), 1.0)
        assertTrue(plan().notes.any { "15 transitions" in it && "45 s" in it && "Roxzone" in it })
        assertFalse(plan().notes.any { it.contains("penalty", true) || it.contains("fail", true) })
    }

    private fun logs(p: SimPlan, secs: Int = 100) = p.raceLegs.map { l ->
        if (l.kind == SimLegKind.RUN) StationLog("run", distanceM = l.distanceM, seconds = secs, rpe = 7)
        else StationLog(l.station!!.id, l.weightKg, l.distanceM, l.reps, secs, 7, level = 5, plannedWork = l.distanceM ?: l.reps)
    }

    private fun rec(id: String, n: Int, trans: List<Int>, secs: Int = 100, format: SimFormat = SimFormat.FIRST_HALF): SessionRecord {
        val p = plan(format)
        val total = logs(p, secs).sumOf { it.seconds!! } + trans.sum()
        return SessionRecord(
            id, LocalDate.parse("2027-01-18").plusDays(n.toLong()), SessionType.HYROX, 90, total / 60, Feeling.GOOD,
            stations = logs(p, secs), sim = SimMeta(format, 1.0, RunMode.TREADMILL, total, null, emptyList(), false, trans, 45),
        )
    }

    @Test fun resultsShowTheRoxzoneShareAndCompareIt() {
        val trans = listOf(40, 50, 60, 30, 45, 55, 35) // 7 transitions of a half
        val r = SimResult.from(rec("a", 0, trans))!!
        assertEquals(trans, r.transitions)
        assertEquals(315, r.roxSec); assertEquals(7, r.roxCount)
        assertEquals(800 + 315, r.totalSec)
        val txt = r.shareText!!
        assertTrue(txt.startsWith("Running 36% · stations 36% · Roxzone 28%") || "Roxzone" in txt, txt)
        assertEquals(100, Math.round(r.runShare!! * 100) + Math.round(r.stationShare!! * 100) + Math.round(r.roxShare!! * 100))
        // Compare: transitions on their own, in kind words.
        val quicker = SimResult.from(rec("b", 7, trans.map { it - 5 }))!!
        val c = SimResult.compare(quicker, r)
        assertEquals(-35, c.transitionDeltaSec)
        assertEquals("Transitions: 0:35 quicker", c.transitionText)
        val slower = SimResult.from(rec("c", 8, trans.map { it + 5 }))!!
        assertEquals("Transitions: 0:35 slower, that is fine", SimResult.compare(slower, r).transitionText)
        // The legs total is not mixed up with the transitions.
        assertEquals(0, c.totalDeltaSec)
        // No transitions recorded (an older sim): no Roxzone in the text.
        val old = SimResult.from(rec("d", 9, emptyList()))!!
        assertFalse("Roxzone" in old.shareText!!)
        assertNull(SimResult.compare(old, r).transitionDeltaSec)
    }

    @Test fun recordKeepsTransitionSecondsAndOldJsonStillDecodes() {
        val r = rec("a", 0, listOf(40, 50, 60, 30, 45, 55, 35))
        assertEquals(r, json.decodeFromString<SessionRecord>(json.encodeToString(r)))
        val m = json.decodeFromString<SimMeta>("{\"format\":\"FULL\",\"totalSec\":99}")
        assertEquals(emptyList(), m.transitionSec); assertEquals(0, m.allowanceSec)
    }

    @Test fun claudeExportListsTheTransitionTimes() {
        val r = rec("a", 0, listOf(40, 50, 60, 30, 45, 55, 35))
        val text = Export.summary(listOf(r), LocalDate.parse("2027-01-18"), LocalDate.parse("2027-01-18"))
        assertTrue("1. Run leg (treadmill run): 1000 m, 1:40, RPE 7" in text, text)
        assertTrue("       transition: 0:40" in text, text)
        assertTrue("transitions (Roxzone) 5:15" in text && "allowance 45 s each" in text, text)
        assertEquals(7, text.lines().count { it.trim().startsWith("transition:") })
    }

    @Test fun publicExportCarriesOnlyTheWhitelistedTransitionColumn() {
        val r = rec("a", 0, listOf(40, 50, 60, 30, 45, 55, 35)).copy(kneePain = 8, note = "SECRETNOTE", dayLabel = "SECRETDAY")
        val files = PublicExport.files(listOf(r))
        val csv = files.getValue("data/hyrox-sim/2027-01.csv")
        assertEquals(PublicExport.SIM_HEADER, csv.lines().first())
        assertTrue(PublicExport.SIM_HEADER.endsWith("rpe,transition_s"))
        val rows = csv.lines().filter { it.isNotBlank() }.drop(1)
        assertTrue(rows[0].endsWith(",7,"), rows[0])     // leg 1: no transition before it
        assertTrue(rows[1].endsWith(",7,40"), rows[1])   // leg 2: the transition after leg 1
        assertTrue(rows[7].endsWith(",7,35"), rows[7])
        for ((path, content) in files) {
            val all = (path + content).lowercase()
            for (bad in listOf("secret", "knee", "note", "feeling")) assertFalse(bad in all, "$bad in $path")
        }
        val back = PublicImport.records(files).single()
        assertEquals(listOf(40, 50, 60, 30, 45, 55, 35), back.sim!!.transitionSec)
        assertEquals(800 + 315, back.sim!!.totalSec)
    }
}
