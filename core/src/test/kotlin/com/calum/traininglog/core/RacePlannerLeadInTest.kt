package com.calum.traininglog.core

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The kick-off lead-in (the plan starts before week 1's Monday without re-indexing the 14 weeks) and the honest
 * starting load (week 1 follows the real recent minutes), using Calum's real history as the fixture.
 */
class RacePlannerLeadInTest {
    private val today = LocalDate.of(2026, 11, 19)   // Thursday
    private val week1 = LocalDate.of(2026, 11, 23)
    private val cfg = RacePlanSettings(sessionsPerWeek = 4, maxWeeklyMinutes = 300)
    private val fri = LocalDate.of(2026, 11, 20)
    private val sun = LocalDate.of(2026, 11, 22)
    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)

    private fun treadmill(date: LocalDate, min: Int, style: TreadmillStyle = TreadmillStyle.STEADY) =
        SessionRecord("t-$date", date, SessionType.TREADMILL, min, min, Feeling.GOOD,
            treadmill = TreadmillLog(style, min * 60, min * 60, distanceM = min * 85))

    private fun gym(date: LocalDate, min: Int) = SessionRecord("g-$date", date, SessionType.GYM, min, min, Feeling.OKAY)

    /** His real logs: about 85-100 minutes a week, all walking and gym, no knee scores, a ladder today. */
    private val history = listOf(
        treadmill(d(10, 30), 30), gym(d(11, 1), 23), treadmill(d(11, 2), 30), gym(d(11, 5), 27), treadmill(d(11, 6), 45),
        treadmill(d(11, 8), 30), gym(d(11, 12), 37), treadmill(d(11, 15), 20), gym(d(11, 16), 40), treadmill(d(11, 18), 30),
        treadmill(d(11, 19), 30, TreadmillStyle.LADDER),
    )

    private fun plan(records: List<SessionRecord> = history, now: LocalDate = today, c: RacePlanSettings = cfg, profile: Profile = NO_REST) =
        RacePlanner.build(c, records, now, profile)

    // ---------------------------------------------------------------------------------------- (a) lead-in

    @Test fun leadInCoversTomorrowToSundayAndIsThisWeek() {
        val p = plan()
        val li = assertNotNull(p.leadIn)
        assertEquals(0, li.index)
        assertEquals(RacePhase.KICKOFF, li.phase)
        assertTrue(li.isLeadIn)
        assertEquals("Kick-off: easing in before week 1", li.headline)
        assertEquals(fri, li.start)
        assertEquals(sun, li.end)
        assertEquals(14, p.weeks.size)
        assertEquals(week1, p.weeks.first().start)
        assertTrue(li.sessions.size in 1..2, "${li.sessions.size}")
        for (s in li.sessions) {
            assertTrue(s.date in fri..sun, "${s.date}")
            assertTrue(s.date != today, "nothing planned for today: the ladder counts")
            assertTrue(s.kind == SessionKind.EASY || s.kind == SessionKind.STRENGTH)
            assertFalse(s.includesRunning, "knee: walking only")
        }
        val easy = li.sessions.first { it.kind == SessionKind.EASY }
        assertTrue(easy.minutes in 25..35)
        assertEquals(Intensity.EASY, easy.intensity)
        val strength = li.sessions.first { it.kind == SessionKind.STRENGTH }
        assertTrue(strength.minutes in 30..40)
        assertTrue(strength.detail.any { it.contains("Hip-abductor") })
        assertEquals(li.sessions.sumOf { it.minutes }, li.plannedMinutes)
        assertTrue(li.plannedMinutes <= p.weeks.first().plannedMinutes)
        // thisWeek and today
        assertEquals(li, RacePlanner.thisWeek(p, today))
        assertEquals(li, RacePlanner.thisWeek(p, fri))
        assertNull(RacePlanner.today(p, today))
        for (s in li.sessions) assertEquals(s, RacePlanner.today(p, s.date))
        assertEquals(1, RacePlanner.thisWeek(p, week1)!!.index)
    }

    @Test fun nothingMoreNeededTodayWhenTheLadderIsLogged() {
        val p = plan()
        val note = p.leadIn!!.adjustment!!
        assertEquals("Nothing more needed today: your ladder counts. The plan starts tomorrow.", note)
        for (b in listOf("miss", "fail", "behind", "late", "lazy", "should have")) assertFalse(note.contains(b, ignoreCase = true))
        // Progress for the lead-in: nothing counted yet, said gently.
        val prog = RacePlanner.weekProgress(p, history, today)!!
        assertEquals(p.leadIn!!.plannedMinutes, prog.plannedMinutes)
        assertEquals(0, prog.doneMinutes)
        assertTrue(prog.message.contains("tomorrow"))
        // Doing a lead-in session counts as done.
        val done = RacePlanner.weekProgress(p, history + treadmill(fri, 30), fri.plusDays(1))!!
        assertEquals(30, done.doneMinutes)
    }

    @Test fun nothingLoggedTodayMeansTheLeadInStartsToday() {
        val p = plan(history.filter { it.date != today })
        val li = p.leadIn!!
        assertEquals(today, li.start)
        assertNull(li.adjustment)
        assertTrue(li.sessions.size in 1..2)
        assertTrue(li.sessions.all { it.date in today..sun })
    }

    @Test fun noStrengthTwoDaysInARowAcrossTheLeadInAndWeekOne() {
        for (now in listOf(today, today.minusDays(1), today.minusDays(3), LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 21))) {
            for (recs in listOf(history.filter { it.date <= now }, emptyList())) {
                val p = plan(recs, now)
                val all = (p.leadIn?.sessions.orEmpty() + p.weeks.take(2).flatMap { it.sessions }).filter { it.kind != SessionKind.REST }
                val byDate = all.associateBy { it.date }
                for (s in all.filter { it.kind == SessionKind.STRENGTH }) {
                    assertTrue(byDate[s.date.plusDays(1)]?.kind != SessionKind.STRENGTH, "strength twice in a row at ${s.date} (today $now)")
                }
            }
        }
    }

    @Test fun leadInRespectsRestDays() {
        var prof = NO_REST
        prof = RestDays.toggleWeekday(prof, DayOfWeek.FRIDAY)
        prof = RestDays.toggleWeekday(prof, DayOfWeek.SUNDAY)
        val p = plan(profile = prof)
        val li = p.leadIn!!
        assertEquals(1, li.sessions.size, "one free day (Saturday) left")
        assertEquals(LocalDate.of(2026, 11, 21), li.sessions.single().date)
        // Every day a rest day: no lead-in.
        prof = RestDays.toggleWeekday(prof, DayOfWeek.SATURDAY)
        assertNull(plan(profile = prof).leadIn)
    }

    @Test fun leadInOnlyBeforeWeekOneAndWhenADayRemains() {
        assertNull(plan(history, week1).leadIn)
        assertNull(plan(history, week1.plusDays(3)).leadIn)
        // Sunday: logged today means no day is left; not logged means just today.
        assertNull(plan(history + treadmill(sun, 30), sun).leadIn)
        val sundayOnly = plan(history, sun).leadIn!!
        assertEquals(sun, sundayOnly.start)
        assertEquals(1, sundayOnly.sessions.size)
        assertEquals(SessionKind.EASY, sundayOnly.sessions.single().kind)
        // After the lead-in the numbered weeks are untouched.
        assertEquals(1, RacePlanner.thisWeek(plan(history, week1), week1)!!.index)
        // Far from the start the lead-in is still just the days before week 1 (the Monday is unchanged).
        val early = plan(emptyList(), LocalDate.of(2026, 11, 3)).leadIn!!
        assertEquals(LocalDate.of(2026, 11, 3), early.start)
        assertEquals(sun, early.end)
    }

    @Test fun leadInDoesNotChangeTheNumberedWeeks() {
        val withLead = plan()
        val atStart = plan(history.filter { it.date <= week1.minusDays(1) }, week1)
        assertEquals(withLead.weeks.map { it.start }, atStart.weeks.map { it.start })
        assertEquals(withLead.weeks.map { it.phase }, atStart.weeks.map { it.phase })
        assertEquals(withLead.phaseTimeline.map { it.phase }, atStart.phaseTimeline.map { it.phase })
        assertTrue(withLead.phaseTimeline.none { it.phase == RacePhase.KICKOFF })
    }

    // ---------------------------------------------------------------------------------------- (b)-(e) honest load

    @Test fun startingLoadFollowsRealRecentMinutes() {
        val p = plan()
        assertTrue(p.baselineFromHistory)
        assertEquals(115, p.baselineMinutes, "342 minutes over the 3 weeks since his first logged session")
        assertTrue(p.weeks[0].plannedMinutes in 110..135, "week 1: ${p.weeks[0].plannedMinutes}")
        assertEquals(4, p.weeks[0].sessions.size)
        assertNotNull(p.startNote)
        assertTrue(p.startNote!!.contains("real recent minutes"))
        assertTrue(p.startNote!!.contains("${p.peakMinutes}"))
    }

    @Test fun noNormalWeekGrowsMoreThanTenPercentAndDeloadsAreLighter() {
        val p = plan()
        var lastNormal = 0
        for (w in p.weeks.filter { it.index <= 11 }) {
            if (w.deload) {
                assertTrue(w.plannedMinutes < lastNormal, "deload week ${w.index}")
                continue
            }
            if (lastNormal > 0) assertTrue(w.plannedMinutes <= lastNormal * 1.10 + 0.01, "week ${w.index}: ${w.plannedMinutes} vs $lastNormal")
            lastNormal = w.plannedMinutes
        }
        // The first week against what he really did: about +10%, not +60-90%.
        assertTrue(p.weeks[0].plannedMinutes <= p.baselineMinutes * 1.2, "${p.weeks[0].plannedMinutes} vs ${p.baselineMinutes}")
    }

    @Test fun peakIsTheCompoundedBuildNotTheCapAndTaperIsAnchored() {
        val p = plan()
        val peak = p.weeks.filter { it.index in 9..11 }.maxOf { it.plannedMinutes }
        assertEquals(peak, p.peakMinutes)
        assertTrue(peak <= 300, "cap")
        assertTrue(peak < 300, "the honest peak is below the cap: $peak")
        assertTrue(p.weeks.all { it.plannedMinutes <= 300 })
        val cut13 = 1.0 - p.weeks[12].plannedMinutes.toDouble() / peak
        val cut14 = 1.0 - p.weeks[13].plannedMinutes.toDouble() / peak
        assertTrue(cut13 in 0.41..0.60, "taper cut $cut13")
        assertTrue(cut14 in 0.50..0.62, "race week cut $cut14")
        assertEquals(LocalDate.of(2027, 2, 15), p.weeks[12].start)
        assertEquals(LocalDate.of(2027, 2, 22), p.weeks[13].start)
        assertEquals(LocalDate.of(2027, 2, 27), p.weeks[13].sessions.last().date)
        assertEquals(RacePhase.TAPER, p.weeks[12].phase)
    }

    @Test fun easyShareStaysNearEightyPercentWithFourSessions() {
        for (w in plan().weeks) assertTrue(w.easyShare >= 0.77, "week ${w.index} easy share ${w.easyShare}")
    }

    @Test fun shortSessionsStayAboveTheRampMinimumsAndMakeSense() {
        val p = plan()
        for (w in p.weeks.filter { it.index <= 13 }) for (s in w.sessions) {
            // The sharpen and taper weeks keep their own (shorter) minimums: 10 minutes under the ramp ones, at least 20.
            val relaxed = w.phase == RacePhase.SHARPEN || w.phase == RacePhase.TAPER
            val min = when (s.kind) { SessionKind.STRENGTH -> 30; SessionKind.KEY, SessionKind.SIM -> 30; SessionKind.LONG_EASY -> 35; else -> 20 }
                .let { if (relaxed) (it - 10).coerceAtLeast(20) else it }
            assertTrue(s.minutes >= min, "week ${w.index} ${s.title} ${s.minutes}")
        }
        // A 30-minute key session still fits its own steps: warm-up 10 + 2 x 6 + 3 easy + cool-down 5.
        val key = p.weeks[0].sessions.first { it.kind == SessionKind.KEY }
        assertEquals(30, key.minutes)
        assertTrue(key.title.contains("2 x 6 min"), key.title)
        assertTrue(key.hardMinutes <= key.minutes)
        // A short strength session keeps its hip prehab and has no station finisher.
        val str = p.weeks[0].sessions.first { it.kind == SessionKind.STRENGTH }
        assertEquals(30, str.minutes)
        assertTrue(str.detail.any { it.contains("Hip-abductor") })
        assertTrue(str.launch.hyroxFocus.isEmpty())
    }

    @Test fun runStageStartsAtNoneBecauseThereAreNoRuns() {
        val p = plan()
        assertEquals(RunStage.NONE, p.runStatus.stage)
        assertEquals(RunStage.NONE, p.weeks[0].runStage)
        assertEquals(RunStage.NONE, p.leadIn!!.runStage)
        for (w in listOf(p.leadIn!!, p.weeks[0], p.weeks[1])) for (s in w.sessions) {
            assertFalse(s.includesRunning)
            assertFalse(Modality.TREADMILL_RUN in s.modalityOptions)
            assertFalse(Modality.RUN_WALK in s.modalityOptions)
        }
    }

    // ---------------------------------------------------------------------------------------- baseline details and (f)

    @Test fun baselineIgnoresDaysBeforeTheFirstSessionAndKeepsAFloor() {
        // A new user: 4 sessions of 30 minutes in the last 7 days is 120 a week, not 30.
        val recent = (0 until 4).map { treadmill(today.minusDays(it * 2L), 30) }
        val b = RacePlanner.baseline(recent, week1, today)
        assertTrue(b.fromHistory)
        assertEquals(120, b.minutes)
        // A very light history is floored at 60 minutes a week.
        val tiny = (0 until 4).map { treadmill(today.minusDays(it * 7L), 5) }
        assertEquals(RacePlanner.BASELINE_FLOOR_MIN, RacePlanner.baseline(tiny, week1, today).minutes)
        // Under 4 sessions: the default.
        val few = recent.take(3)
        val f = RacePlanner.baseline(few, week1, today)
        assertFalse(f.fromHistory)
        assertEquals(RacePlanner.DEFAULT_BASELINE_MIN, f.minutes)
        // Once the plan has begun the baseline stays where it was (logs inside the plan do not move it).
        val before = plan(history.filter { it.date < week1 }, week1)
        val later = plan(history.filter { it.date < week1 } + (0 until 6).map { treadmill(week1.plusDays(it.toLong()), 60) }, week1.plusDays(5))
        assertEquals(before.baselineMinutes, later.baselineMinutes)
    }

    @Test fun aNewUserKeepsTheOldDefaultBehaviour() {
        val p = RacePlanner.build(RacePlanSettings(), emptyList(), today)
        assertFalse(p.baselineFromHistory)
        assertNull(p.startNote)
        assertEquals(RacePlanner.DEFAULT_BASELINE_MIN, p.baselineMinutes)
        assertTrue(p.weeks[0].plannedMinutes in 195..205)
        assertEquals(380, p.peakMinutes)
        // The lead-in still exists and is sized from the default load; nothing is logged so it starts today.
        assertEquals(today, p.leadIn!!.start)
        assertTrue(p.weeks[0].sessions.size == 5)
    }

    @Test fun capStillLimitsAHeavyHistory() {
        val heavy = (0 until 8).map { gym(week1.minusDays(2L + it * 3), 150) }
        val p = RacePlanner.build(RacePlanSettings(sessionsPerWeek = 5, maxWeeklyMinutes = 300), heavy, week1)
        assertTrue(p.weeks.all { it.plannedMinutes <= 300 })
        assertEquals(300, p.peakMinutes)
        assertTrue(p.startNote!!.contains("300"))
    }
}
