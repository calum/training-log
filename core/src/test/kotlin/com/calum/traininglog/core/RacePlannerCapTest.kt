package com.calum.traininglog.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RacePlannerCapTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val today = LocalDate.of(2026, 11, 19)
    private fun plan(cap: Int?, sessions: Int = 5) =
        RacePlanner.build(RacePlanSettings(sessionsPerWeek = sessions, maxWeeklyMinutes = cap), emptyList(), today)

    @Test fun noCapLeavesThePlanUnchanged() {
        assertEquals(plan(null), RacePlanner.build(RacePlanSettings(), emptyList(), today))
        // A cap above the biggest week changes nothing either.
        assertEquals(plan(null), plan(600))
    }

    @Test fun noWeekExceedsTheCapAndEveryWeekKeepsItsStrengthAndKeySession() {
        for (sessions in 3..6) for (cap in RacePlanSettings.CAP_RANGE step 15) {
            val p = plan(cap, sessions)
            for (w in p.weeks) {
                assertTrue(w.plannedMinutes <= cap, "cap $cap sessions $sessions week ${w.index}: ${w.plannedMinutes}")
                if (w.phase != RacePhase.RACE) {
                    assertEquals(2, w.sessions.count { it.kind == SessionKind.STRENGTH }, "cap $cap week ${w.index}")
                    assertTrue(w.sessions.any { it.kind == SessionKind.KEY || it.kind == SessionKind.SIM }, "cap $cap week ${w.index}")
                }
            }
        }
    }

    @Test fun sessionsStayAtTheirMinimumLengths() {
        for (cap in listOf(120, 150, 180, 240)) plan(cap).weeks.filter { it.phase != RacePhase.RACE }.forEach { w ->
            w.sessions.forEach { assertTrue(it.minutes >= 20, "cap $cap week ${w.index} ${it.title} ${it.minutes}") }
        }
    }

    @Test fun phaseProportionsSurviveACap() {
        val p = plan(240)
        val by = p.weeks.associate { it.index to it.plannedMinutes }
        assertTrue(p.peakMinutes <= 240)
        // Taper and race week are still well below the capped peak (Bosquet range of 41-60% less).
        assertTrue(by.getValue(13) <= p.peakMinutes * 0.65, "taper ${by[13]} peak ${p.peakMinutes}")
        assertTrue(by.getValue(14) <= p.peakMinutes * 0.55, "race ${by[14]}")
        assertTrue(by.getValue(12) < p.peakMinutes)
        // Lighter weeks are lighter than the week before them.
        assertTrue(by.getValue(4) < by.getValue(3))
        assertTrue(by.getValue(8) < by.getValue(7))
    }

    @Test fun easyShareHoldsWhenEasySessionsAreKept() {
        for (cap in listOf(240, 300, 360)) plan(cap).weeks.forEach {
            // The simulation weeks (9-11) carry a long hard session, so with less easy time around it they sit a little lower.
            val floor = if (it.index in 9..11) 0.7 else 0.8
            assertTrue(it.easyShare >= floor, "cap $cap week ${it.index} easy share ${it.easyShare}")
        }
    }

    @Test fun capSnapsAndOldJsonDecodes() {
        assertEquals(120, RacePlanSettings(maxWeeklyMinutes = 10).sanitized().maxWeeklyMinutes)
        assertEquals(600, RacePlanSettings(maxWeeklyMinutes = 999).sanitized().maxWeeklyMinutes)
        assertEquals(240, RacePlanSettings(maxWeeklyMinutes = 238).sanitized().maxWeeklyMinutes)
        assertNull(RacePlanSettings().sanitized().maxWeeklyMinutes)
        val s = RacePlanSettings(maxWeeklyMinutes = 255)
        assertEquals(s, json.decodeFromString<RacePlanSettings>(json.encodeToString(s)))
        assertNull(json.decodeFromString<RacePlanSettings>("""{"sessionsPerWeek":4}""").maxWeeklyMinutes)
    }
}
