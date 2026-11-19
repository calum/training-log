package com.calum.traininglog.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Default Tuesday/Friday rest, moving and skipping sessions, the time-based suggestion, the replan alert and its export.
 * Week 1 for the default race is Monday 23 Nov 2026; with Tuesday and Friday rest and 4 sessions it is
 * Mon strength, Wed key, Sat strength, Sun long easy.
 */
class PlanAdjustTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val cfg = RacePlanSettings(sessionsPerWeek = 4, maxWeeklyMinutes = 300)
    private val profile = Profile(settings = UserSettings(racePlan = cfg))
    private val mon = LocalDate.of(2026, 11, 23)
    private val wed = mon.plusDays(2)
    private val thu = mon.plusDays(3)
    private val sat = mon.plusDays(5)
    private val sun = mon.plusDays(6)
    private var n = 0

    private fun plan(records: List<SessionRecord> = emptyList(), now: LocalDate = wed, p: Profile = profile) = RacePlanner.build(cfg, records, now, p)
    private fun session(p: RacePlan, date: LocalDate, kind: SessionKind) = p.weeks.flatMap { it.sessions }.first { it.date == date && it.kind == kind }
    private fun week1(p: RacePlan) = p.weeks[0]

    private fun gym(date: LocalDate, min: Int = 40, knee: Int? = null) = SessionRecord("a${n++}", date, SessionType.GYM, min, min, Feeling.GOOD, kneePain = knee)
    private fun walk(date: LocalDate, min: Int = 45, style: TreadmillStyle = TreadmillStyle.STEADY, knee: Int? = null, feeling: Feeling = Feeling.GOOD) =
        SessionRecord("a${n++}", date, SessionType.TREADMILL, min, min, feeling, kneePain = knee, treadmill = TreadmillLog(style, min * 60, min * 60, distanceM = min * 85))

    private fun skipped(p: RacePlan, date: LocalDate, kind: SessionKind) = p.weeks.flatMap { it.sessions }.first { it.date == date && it.kind == kind && it.skipReason != null }

    // ---------------------------------------------------------------------------------------- default rest days

    @Test fun tuesdayAndFridayAreTheDefaultRestDays() {
        assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY), Profile().restWeekdays)
        // Older saved data without the field gets the default; an explicit choice is kept, even an empty one.
        assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY), json.decodeFromString<Profile>("""{"lastDeload":"2026-10-01"}""").restWeekdays)
        val mine = Profile(restWeekdays = setOf(DayOfWeek.SUNDAY))
        assertEquals(mine, json.decodeFromString<Profile>(json.encodeToString(mine)))
        val none = Profile(restWeekdays = emptySet())
        assertEquals(emptySet(), json.decodeFromString<Profile>(json.encodeToString(none)).restWeekdays)
    }

    @Test fun plannerSchedulesAroundTuesdayAndFridayWithoutHardBeforeStrength() {
        for (count in 3..6) {
            val p = RacePlanner.build(RacePlanSettings(sessionsPerWeek = count, maxWeeklyMinutes = 300), emptyList(), wed, Profile())
            for (w in p.weeks) for (s in w.sessions.filter { !it.isRaceDay && it.kind != SessionKind.REST }) {
                assertFalse(s.date.dayOfWeek in setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY), "session on rest day ${s.date} ($count sessions)")
            }
        }
        for (w in plan().weeks.filter { it.index <= 13 }) {
            val byDate = w.sessions.associateBy { it.date }
            for (s in w.sessions.filter { it.kind == SessionKind.KEY || it.kind == SessionKind.SIM }) {
                assertTrue(byDate[s.date.plusDays(1)]?.kind != SessionKind.STRENGTH, "hard day before strength in week ${w.index}")
            }
        }
        assertEquals(listOf(SessionKind.STRENGTH, SessionKind.KEY, SessionKind.STRENGTH, SessionKind.LONG_EASY), week1(plan()).sessions.map { it.kind })
        assertEquals(listOf(mon, wed, sat, sun), week1(plan()).sessions.map { it.date })
    }

    @Test fun kickOffWorksWithTuesdayAndFridayRest() {
        for (day in 19..22) {
            val p = RacePlanner.build(cfg, emptyList(), LocalDate.of(2026, 11, day), Profile())
            val li = p.leadIn
            if (li != null) for (s in li.sessions) {
                assertFalse(s.date.dayOfWeek in setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY))
                assertTrue(s.kind == SessionKind.EASY || s.kind == SessionKind.STRENGTH)
                assertTrue(s.date >= LocalDate.of(2026, 11, day) && s.date < mon)
            }
        }
        // Friday: only Saturday and Sunday are left, and both are used.
        val fri = RacePlanner.build(cfg, emptyList(), LocalDate.of(2026, 11, 20), Profile()).leadIn!!
        assertTrue(fri.sessions.isNotEmpty() && fri.sessions.all { it.date == LocalDate.of(2026, 11, 21) || it.date == LocalDate.of(2026, 11, 22) })
    }

    // ---------------------------------------------------------------------------------------- data

    @Test fun adjustmentsRoundTripOldJsonAndPruning() {
        assertEquals(PlanAdjustments(), json.decodeFromString<Profile>("{}").planAdjustments)
        val a = PlanAdjustments(
            moves = listOf(PlanMove(wed, thu, SessionKind.KEY, "Key")),
            skips = listOf(PlanSkip(sat, SessionKind.STRENGTH, null, SkipReason.UNWELL, sat)),
            replanDismissedUntil = LocalDate.of(2026, 12, 1),
        )
        val p = Profile(planAdjustments = a)
        assertEquals(p, json.decodeFromString<Profile>(json.encodeToString(p)))
        val old = PlanAdjustments(
            moves = listOf(PlanMove(mon.minusDays(90), mon.minusDays(89), SessionKind.EASY), PlanMove(wed, thu, SessionKind.KEY)),
            skips = listOf(PlanSkip(mon.minusDays(70), SessionKind.EASY), PlanSkip(sat, SessionKind.STRENGTH)),
            replanDismissedUntil = mon.minusDays(3),
        )
        val pruned = old.pruned(sun)
        assertEquals(1, pruned.moves.size)
        assertEquals(1, pruned.skips.size)
        assertNull(pruned.replanDismissedUntil)
        assertEquals(sun.plusDays(5), PlanAdjustments().dismissReplan(sun).replanDismissedUntil)
    }

    // ---------------------------------------------------------------------------------------- moves

    @Test fun moveAppliesWithinTheWeekAndLeavesTheRestAlone() {
        val before = plan()
        val key = session(before, wed, SessionKind.KEY)
        val moved = RacePlanner.addMove(profile, key, thu, wed)
        val after = plan(p = moved)
        val k = session(after, thu, SessionKind.KEY)
        assertEquals(wed, k.movedFrom)
        assertEquals(key.minutes, k.minutes)
        assertEquals(before.weeks[0].plannedMinutes, after.weeks[0].plannedMinutes)
        // The other sessions and the other weeks are exactly as before.
        assertEquals(before.weeks[0].sessions.filter { it.kind != SessionKind.KEY }, after.weeks[0].sessions.filter { it.kind != SessionKind.KEY })
        assertEquals(before.weeks.drop(1).map { it.sessions }, after.weeks.drop(1).map { it.sessions })
        assertEquals(listOf(mon, thu, sat, sun), after.weeks[0].sessions.map { it.date })
        // Undo puts it back.
        val undone = RacePlanner.undo(moved, k)
        assertEquals(profile.planAdjustments, undone.planAdjustments)
        assertEquals(before.weeks, plan(p = undone).weeks)
    }

    @Test fun movingOntoARestDayMakesItAOneOffTrainingDayWithoutReshufflingTheWeek() {
        val p0 = plan()
        val tue = mon.plusDays(1)
        assertTrue(RestDays.isRestDay(profile, tue))
        val s = session(p0, mon, SessionKind.STRENGTH)
        val moved = RacePlanner.addMove(profile, s, tue, mon)
        assertTrue(tue in moved.workoutOverrides)
        assertFalse(RestDays.isRestDay(moved, tue))
        val after = plan(p = moved)
        assertEquals(listOf(tue, wed, sat, sun), after.weeks[0].sessions.map { it.date })
        assertEquals(p0.weeks.drop(1).map { it.sessions }, after.weeks.drop(1).map { it.sessions })
        val back = RacePlanner.undo(moved, session(after, tue, SessionKind.STRENGTH))
        assertFalse(tue in back.workoutOverrides)
        assertTrue(RestDays.isRestDay(back, tue))
    }

    @Test fun movedAgainReplacesTheEarlierMoveAndMovingBackCancelsIt() {
        val key = session(plan(), wed, SessionKind.KEY)
        val a = RacePlanner.addMove(profile, key, thu, wed)
        val keyThu = session(plan(p = a), thu, SessionKind.KEY)
        val b = RacePlanner.addMove(a, keyThu, mon.plusDays(4), wed)   // Friday, a rest day
        assertEquals(listOf(PlanMove(wed, mon.plusDays(4), SessionKind.KEY, key.title)), b.planAdjustments.moves)
        assertTrue(mon.plusDays(4) in b.workoutOverrides)
        assertFalse(thu in b.workoutOverrides)
        val keyFri = session(plan(p = b), mon.plusDays(4), SessionKind.KEY)
        val c = RacePlanner.addMove(b, keyFri, wed, wed)
        assertEquals(emptyList(), c.planAdjustments.moves)
        assertFalse(mon.plusDays(4) in c.workoutOverrides)
    }

    @Test fun movesOutsideTheWeekOrOnUnknownSessionsAreIgnored() {
        val bad = profile.copy(planAdjustments = PlanAdjustments(moves = listOf(
            PlanMove(wed, sun.plusDays(2), SessionKind.KEY), PlanMove(thu, sat, SessionKind.KEY), PlanMove(sat, sun, SessionKind.LONG_EASY),
        )))
        assertEquals(plan().weeks[0].sessions.map { it.date }, plan(p = bad).weeks[0].sessions.map { it.date }.let { it })
    }

    @Test fun moveOptionsMarkRestDaysPastDaysAndGivePlainHints() {
        val p = plan(now = thu)
        val key = session(p, wed, SessionKind.KEY)
        val strengthSat = session(p, sat, SessionKind.STRENGTH)
        val opts = RacePlanner.moveOptions(p, strengthSat, thu, profile)
        assertEquals(7, opts.size)
        assertEquals((0L..6L).map { mon.plusDays(it) }, opts.map { it.date })
        fun o(d: LocalDate) = opts.first { it.date == d }
        assertFalse(o(mon).allowed); assertFalse(o(wed).allowed)            // gone, or already a session there
        assertTrue(o(thu).allowed)
        assertTrue(o(mon.plusDays(4)).allowed)                                // Friday: allowed, a rest day
        assertTrue(o(mon.plusDays(4)).isRestDay)
        assertTrue(o(mon.plusDays(4)).hint!!.contains("one-off training day"))
        assertFalse(o(thu).isRestDay)
        assertTrue(o(sat).isCurrent && !o(sat).allowed)
        // Strength on Thursday is the day after the hard Wednesday: a hint, never a block.
        val optsForKey = RacePlanner.moveOptions(p, key, wed, profile)
        val thuKey = optsForKey.first { it.date == thu }
        assertTrue(thuKey.allowed)
        assertNull(thuKey.hint)
        // Strength moved next to strength: hint.
        val monStrength = session(p, mon, SessionKind.STRENGTH)
        val optsMon = RacePlanner.moveOptions(p, monStrength, mon, profile)
        val sunOpt = optsMon.first { it.date == sun }
        assertFalse(sunOpt.allowed)   // the long easy session is there and is not an easy+strength pair
        val thuStrength = RacePlanner.moveOptions(p, monStrength, mon, profile).first { it.date == thu }
        assertTrue(thuStrength.hint!!.contains("legs may feel it"))   // the day after the hard session
        assertTrue(RacePlanner.moveOptions(p, session(p, sun, SessionKind.LONG_EASY), mon, profile).none { it.hint?.contains("Two strength") == true })
    }

    @Test fun easyAndStrengthMayShareADayButNothingElseCan() {
        val p = plan(now = mon)
        // Add an easy session: 5 sessions per week gives week 1 an EASY.
        val five = RacePlanner.build(RacePlanSettings(sessionsPerWeek = 5, maxWeeklyMinutes = 300), emptyList(), mon, Profile())
        val easy = five.weeks[0].sessions.first { it.kind == SessionKind.EASY }
        val strength = five.weeks[0].sessions.first { it.kind == SessionKind.STRENGTH }
        val opts = RacePlanner.moveOptions(five, easy, mon, Profile())
        val onStrength = opts.first { it.date == strength.date }
        assertTrue(onStrength.allowed)
        assertTrue(onStrength.hint!!.contains("Two sessions"))
        val key = five.weeks[0].sessions.first { it.kind == SessionKind.KEY }
        assertFalse(RacePlanner.moveOptions(five, key, mon, Profile()).first { it.date == strength.date }.allowed)
        val moved = RacePlanner.build(RacePlanSettings(sessionsPerWeek = 5, maxWeeklyMinutes = 300), emptyList(), mon, RacePlanner.addMove(Profile(), easy, strength.date, mon))
        assertEquals(2, moved.weeks[0].sessions.count { it.date == strength.date })
        assertNotNull(p)
    }

    @Test fun cannotMoveIntoThePastOrRaceEve() {
        val p = plan(now = sat)
        val long = session(p, sun, SessionKind.LONG_EASY)
        val opts = RacePlanner.moveOptions(p, long, sat, profile)
        assertTrue(opts.filter { it.date < sat }.none { it.allowed })
        val raceWeek = RacePlanner.build(cfg, emptyList(), LocalDate.of(2027, 2, 22), profile)
        val rw = raceWeek.weeks.last()
        val key = rw.sessions.first { it.kind == SessionKind.KEY }
        val ro = RacePlanner.moveOptions(raceWeek, key, LocalDate.of(2027, 2, 22), profile)
        assertFalse(ro.first { it.date == LocalDate.of(2027, 2, 27) }.allowed)
        assertFalse(ro.first { it.date == LocalDate.of(2027, 2, 26) }.allowed)
    }

    // ---------------------------------------------------------------------------------------- skip

    @Test fun skippedSessionMovesToTheNextFreeAllowedDay() {
        val key = session(plan(), wed, SessionKind.KEY)
        val sk = RacePlanner.skip(profile, key, SkipReason.NO_TIME, wed)
        val after = plan(p = sk)
        val w = after.weeks[0]
        assertEquals(SkipReason.NO_TIME, skipped(after, wed, SessionKind.KEY).skipReason)
        assertEquals(thu, skipped(after, wed, SessionKind.KEY).reflowedTo)
        assertEquals(wed, session(after, thu, SessionKind.KEY).movedFrom)
        assertEquals(200, w.plannedMinutes)              // nothing lost: it just moved
        assertEquals("Moved to Thursday.", RacePlanner.skipOutcome(after, key))
        assertEquals(1, w.skippedSessions.size)
        assertTrue(w.skippedSessions.none { it in RacePlanner.counted(w) })
    }

    @Test fun skipWithNoDayLeftIsDroppedForTheWeekWithoutCatchingUp() {
        val p0 = plan()
        val longEasy = session(p0, sun, SessionKind.LONG_EASY)
        val after = plan(p = RacePlanner.skip(profile, longEasy, SkipReason.NO_TIME, sun), now = sun)
        val w = after.weeks[0]
        assertNull(skipped(after, sun, SessionKind.LONG_EASY).reflowedTo)
        assertEquals(p0.weeks[0].plannedMinutes - longEasy.minutes, w.plannedMinutes)
        assertEquals(3, RacePlanner.counted(w).size)    // nothing doubled or carried over
        assertEquals("Not this week, no catching up.", RacePlanner.skipOutcome(after, longEasy))
        // Next week is not bigger than it was without the skip, and grows at most 10% on this (smaller) week.
        assertTrue(after.weeks[1].plannedMinutes <= p0.weeks[1].plannedMinutes)
        assertTrue(after.weeks[1].plannedMinutes <= w.plannedMinutes * 1.10 + 0.01, "${after.weeks[1].plannedMinutes} vs ${w.plannedMinutes}")
        // The week after that is not squashed by the skip.
        assertTrue(after.weeks[2].plannedMinutes <= after.weeks[1].plannedMinutes * 1.10 + 5)
        assertEquals(p0.weeks[1].start, after.weeks[1].start)
    }

    @Test fun aWeekWithSkipsNeverPushesTheFollowingWeeksAboveTenPercentGrowth() {
        val p0 = plan()
        val dropped = RacePlanner.skip(profile, session(p0, sun, SessionKind.LONG_EASY), SkipReason.NO_TIME, sun)
        val p = plan(p = dropped, now = sun)
        val w = p.weeks
        for (i in 1..10) {
            if (w[i].deload) continue
            assertTrue(w[i].plannedMinutes <= w[i - 1].plannedMinutes * 1.10 + 5, "week ${w[i].index}: ${w[i].plannedMinutes} after ${w[i - 1].plannedMinutes}")
        }
        // The held-back weeks never exceed the original build-up, and the taper is sized from the peak actually planned.
        for (i in 0..10) assertTrue(w[i].plannedMinutes <= p0.weeks[i].plannedMinutes, "week ${i + 1}")
        val peak = w.filter { it.index <= 11 }.maxOf { it.plannedMinutes }
        assertEquals(peak, p.peakMinutes)
        assertTrue(w[11].plannedMinutes.toDouble() / peak in 0.80..0.90, "sharpen ${w[11].plannedMinutes} of $peak")
        assertTrue(w[12].plannedMinutes < w[11].plannedMinutes && w[13].plannedMinutes < w[12].plannedMinutes)
        assertEquals(LocalDate.of(2027, 2, 27), w[13].sessions.last().date)
        // A skip that re-flows loses nothing, so nothing is held back.
        val flowed = plan(p = RacePlanner.skip(profile, session(p0, wed, SessionKind.KEY), SkipReason.NO_TIME, wed), now = wed)
        assertEquals(p0.weeks.map { it.plannedMinutes }, flowed.weeks.map { it.plannedMinutes })
    }

    @Test fun reflowRespectsRestDaysAndSpacing() {
        // Skip Monday's strength: Tuesday is rest, Wednesday has the key, Thursday is next to it, Friday is rest,
        // Saturday has strength, Sunday the long session: no allowed day.
        val monStrength = session(plan(), mon, SessionKind.STRENGTH)
        val after = plan(p = RacePlanner.skip(profile, monStrength, SkipReason.OTHER, mon), now = mon)
        assertNull(skipped(after, mon, SessionKind.STRENGTH).reflowedTo)
        // With no rest days it is spacing alone: Tuesday and Thursday sit next to the key session, Saturday next to strength.
        val open = NO_REST.copy(settings = profile.settings)
        val s0 = RacePlanner.build(cfg, emptyList(), mon, open).weeks[0].sessions.first()
        val a2 = RacePlanner.build(cfg, emptyList(), mon, RacePlanner.skip(open, s0, SkipReason.OTHER, s0.date))
        assertNull(a2.weeks[0].sessions.first { it.skipReason != null }.reflowedTo)
    }

    @Test fun reflowDoesNotDependOnToday() {
        val key = session(plan(), wed, SessionKind.KEY)
        val sk = RacePlanner.skip(profile, key, SkipReason.NO_TIME, wed)
        val a = plan(p = sk, now = wed).weeks[0].sessions
        val b = plan(p = sk, now = sun).weeks[0].sessions
        assertEquals(a, b)
        val c = plan(p = sk, now = wed).weeks[0].sessions
        assertEquals(a, c)
    }

    @Test fun unwellSkipOfAnEasySessionAlsoDropsTheKeySessionAndSuggestsRest() {
        val p0 = plan(now = mon)
        val monS = session(p0, mon, SessionKind.STRENGTH)
        val after = plan(p = RacePlanner.skip(profile, monS, SkipReason.UNWELL, mon), now = mon)
        val w = after.weeks[0]
        val key = w.sessions.first { it.kind == SessionKind.KEY }
        assertEquals(SkipReason.UNWELL, key.skipReason)
        assertNull(key.reflowedTo)
        assertTrue(w.adjustment!!.contains("Rest up"))
        assertTrue(w.adjustment!!.contains("hard session"))
        // The strength session waits for a rest day first: Wednesday (two days on), not Tuesday.
        assertEquals(wed, skipped(after, mon, SessionKind.STRENGTH).reflowedTo)
        // Skipping the KEY session when unwell does not drop anything else.
        val k = session(p0, wed, SessionKind.KEY)
        val only = plan(p = RacePlanner.skip(profile, k, SkipReason.UNWELL, wed))
        assertEquals(1, only.weeks[0].sessions.count { it.skipReason == SkipReason.UNWELL && it.reflowedTo == null || it.skipReason != null })
        assertEquals(SessionKind.STRENGTH, only.weeks[0].sessions.first { it.date == sat }.kind)
        assertNull(only.weeks[0].sessions.first { it.date == sat }.skipReason)
    }

    @Test fun skipCanBeUndoneAndIsNeverCountedAsDone() {
        val key = session(plan(), wed, SessionKind.KEY)
        val sk = RacePlanner.skip(profile, key, SkipReason.NO_TIME, wed)
        // Recording the same skip twice changes nothing.
        assertEquals(sk, RacePlanner.skip(sk, key, SkipReason.UNWELL, wed))
        val after = plan(p = sk, now = sat)
        val w = after.weeks[0]
        val done = RacePlanner.doneSessions(w, listOf(gym(mon)), sat)
        assertTrue(done.none { it.isSkipped })
        val progress = RacePlanner.weekProgress(after, listOf(gym(mon)), sat)!!
        assertEquals(1, progress.skipped)
        val unwell = plan(p = RacePlanner.skip(profile, key, SkipReason.UNWELL, wed), now = sat)
        assertTrue(RacePlanner.weekProgress(unwell, emptyList(), sat)!!.message.contains("Skipped: unwell. Rest up."))
        // Undo from either the skipped session or the one it moved to.
        assertEquals(profile.planAdjustments, RacePlanner.undo(sk, skipped(after, wed, SessionKind.KEY)).planAdjustments)
        assertEquals(profile.planAdjustments, RacePlanner.undo(sk, session(after, thu, SessionKind.KEY)).planAdjustments)
        assertEquals(plan().weeks, plan(p = RacePlanner.undo(sk, skipped(after, wed, SessionKind.KEY))).weeks)
    }

    @Test fun weekWithSkipsKeepsTheAdaptiveRules() {
        // A quiet week 1 (one session of 200 planned) still repeats its target in week 2 and a skip does not break that.
        val sk = RacePlanner.skip(profile, session(plan(), sat, SessionKind.STRENGTH), SkipReason.NO_TIME, sat)
        val now = LocalDate.of(2026, 11, 30)
        val quiet = listOf(gym(mon, 40))
        val after = plan(quiet, now, sk)
        assertTrue(after.weeks[1].plannedMinutes <= after.weeks[0].plannedMinutes * 1.10 + 0.01)
        // Taper and race week stay anchored to the race date whatever was skipped.
        assertEquals(LocalDate.of(2027, 2, 22), after.weeks.last().start)
        assertTrue(after.weeks.last().sessions.any { it.isRaceDay && it.date == LocalDate.of(2027, 2, 27) })
        // Skips in race week are dropped, never re-flowed (no hard session late in the week).
        val rwKey = RacePlanner.build(cfg, emptyList(), LocalDate.of(2027, 2, 22), profile).weeks.last().sessions.first { it.kind == SessionKind.KEY }
        val rw = RacePlanner.build(cfg, emptyList(), LocalDate.of(2027, 2, 22), RacePlanner.skip(profile, rwKey, SkipReason.NO_TIME, rwKey.date)).weeks.last()
        assertNull(rw.sessions.first { it.skipReason != null }.reflowedTo)
    }

    // ---------------------------------------------------------------------------------------- time suggestion

    private fun suggest(
        minutes: Int, feeling: Feeling = Feeling.GOOD, knee: Int? = null, now: LocalDate = wed, records: List<SessionRecord> = listOf(gym(mon)), p: Profile = profile,
    ): TimeSuggestion {
        val pl = plan(records, now, p)
        return RacePlanner.suggestForTime(pl, records, now, minutes, feeling, knee, p)
    }

    @Test fun freshWithTimeGetsTheKeySessionAndAStrengthAlternative() {
        val s = suggest(60)
        assertEquals(SessionKind.KEY, s.primary.kind)
        assertFalse(s.extra)
        assertEquals(SessionKind.STRENGTH, s.alternative!!.kind)
        assertTrue(s.reason.isNotBlank())
        assertEquals(s, suggest(60))
    }

    @Test fun suggestionIsScaledToTheTimeAndRespectsMinimums() {
        val s = suggest(35)
        assertEquals(SessionKind.KEY, s.primary.kind)
        assertTrue(s.primary.minutes <= 35 && s.primary.minutes >= 30)
        assertTrue(s.reason.contains("Shortened"))
        // Below every minimum for the hard and strength sessions: a short easy version.
        val tiny = suggest(20)
        assertEquals(SessionKind.EASY, tiny.primary.kind)
        assertEquals(Intensity.EASY, tiny.primary.intensity)
        assertEquals(20, tiny.primary.minutes)
        assertTrue(tiny.primary.title.contains("shorter version"))
        // A bigger window never makes it longer than planned.
        assertEquals(session(plan(listOf(gym(mon))), wed, SessionKind.KEY).minutes, suggest(90).primary.minutes)
    }

    @Test fun okayFeelingPrefersStrengthButNotRightAfterStrength() {
        val s = suggest(45, Feeling.OKAY)
        assertEquals(SessionKind.STRENGTH, s.primary.kind)
        val after = suggest(45, Feeling.OKAY, records = listOf(gym(wed.minusDays(1))))
        assertEquals(SessionKind.LONG_EASY, after.primary.kind.takeIf { it == SessionKind.LONG_EASY } ?: after.primary.kind.takeIf { it == SessionKind.EASY })
        assertTrue(after.primary.intensity == Intensity.EASY)
        // Fresh but a hard session yesterday: no key, strength instead.
        val hardYesterday = suggest(60, records = listOf(walk(wed.minusDays(1), 45, TreadmillStyle.INTERVALS)))
        assertEquals(SessionKind.STRENGTH, hardYesterday.primary.kind)
    }

    @Test fun roughMeansEasyAndIllMeansRest() {
        val rough = suggest(60, Feeling.ROUGH)
        assertEquals(Intensity.EASY, rough.primary.intensity)
        assertTrue(rough.primary.minutes <= 30)
        assertTrue(rough.reason.lowercase().contains("easy"))
        val ill = suggest(60, Feeling.ILL)
        assertTrue(ill.isRest)
        assertEquals(SessionKind.REST, ill.primary.kind)
        assertNull(ill.alternative)
        assertTrue(ill.reason.lowercase().contains("rest"))
    }

    @Test fun sorerKneeMeansNoRunning() {
        val s = suggest(60, knee = 5, now = LocalDate.of(2026, 12, 20), records = emptyList())
        assertFalse(s.primary.includesRunning)
        assertTrue(s.primary.modalityOptions.none { it == Modality.TREADMILL_RUN || it == Modality.RUN_WALK })
        assertTrue(s.reason.contains("no running"))
        assertTrue(s.alternative?.includesRunning != true)
    }

    @Test fun onARestDayItIsPhrasedAsAnOffer() {
        val tue = mon.plusDays(1)
        val s = suggest(45, now = tue)
        assertTrue(s.reason.startsWith("Rest day, but if you fancy it..."))
        assertFalse(suggest(45).reason.startsWith("Rest day"))
    }

    @Test fun whenEverythingIsDoneOrSkippedOfferABonusEasySession() {
        val longEasy = session(plan(), sun, SessionKind.LONG_EASY)
        val p = RacePlanner.skip(profile, longEasy, SkipReason.NO_TIME, sun)
        val recs = listOf(gym(mon), walk(wed, 50, TreadmillStyle.INTERVALS), gym(sat))
        val s = suggest(90, records = recs, now = sun, p = p)
        assertTrue(s.extra)
        assertEquals(SessionKind.EASY, s.primary.kind)
        assertTrue(s.primary.minutes <= 45)
        assertTrue(s.primary.title.startsWith("Bonus"))
        assertFalse(s.reason.contains("You are ahead"))
        // Already done more than planned: say so kindly.
        val more = suggest(90, records = recs + walk(thu, 45), now = sun, p = p)
        assertTrue(more.extra)
        assertTrue(more.reason.contains("You are ahead; rest is training too"))
    }

    // ---------------------------------------------------------------------------------------- plan health

    private fun health(records: List<SessionRecord>, now: LocalDate, p: Profile = profile) = PlanHealth.assess(plan(records, now, p), records, p, now)

    private fun everythingDone(through: LocalDate): List<SessionRecord> {
        val out = mutableListOf<SessionRecord>()
        var w = mon
        while (w <= through) {
            out += gym(w); out += walk(w.plusDays(2), 50, TreadmillStyle.INTERVALS); out += gym(w.plusDays(5)); out += walk(w.plusDays(6), 70)
            w = w.plusWeeks(1)
        }
        return out.filter { it.date <= through }
    }

    @Test fun calmWhenThingsAreGoingWell() {
        val h = health(everythingDone(LocalDate.of(2026, 12, 6)), LocalDate.of(2026, 12, 7))
        assertEquals(HealthLevel.NONE, h.level)
        assertTrue(h.reasons.isEmpty())
    }

    @Test fun neverDuringTheKickOffEvenWithAKneeFlare() {
        val now = LocalDate.of(2026, 11, 20)
        val recs = listOf(gym(now.minusDays(2), knee = 6))
        assertEquals(HealthLevel.NONE, health(recs, now).level)
    }

    @Test fun threeSessionsSkippedOrLeftInTenDaysSuggestsAFreshPlan() {
        val now = LocalDate.of(2026, 12, 6)
        val h = health(emptyList(), now)
        assertEquals(HealthLevel.SUGGEST, h.level)
        assertEquals("Rough stretches happen. A fresh plan from today might help.", h.summary)
        assertTrue(h.reasons.any { it.contains("skipped or left for now") })
        // Skipped sessions count the same way.
        val key = session(plan(), wed, SessionKind.KEY)
        val sk = RacePlanner.skip(RacePlanner.skip(profile, key, SkipReason.OTHER, wed), session(plan(), sat, SessionKind.STRENGTH), SkipReason.NO_TIME, sat)
        val recs = everythingDone(LocalDate.of(2026, 12, 5)).filter { it.date.dayOfWeek != DayOfWeek.WEDNESDAY && it.date.dayOfWeek != DayOfWeek.SUNDAY }
        assertEquals(HealthLevel.SUGGEST, health(recs, now, sk).level)
    }

    @Test fun twoLeftIsOnlyAQuietLine() {
        val now = LocalDate.of(2026, 12, 3)
        val recs = everythingDone(LocalDate.of(2026, 12, 2)).filter { it.date != sat && it.date != sun }
        val h = health(recs, now)
        assertEquals(HealthLevel.GENTLE, h.level)
        assertTrue(h.summary.isNotBlank())
    }

    @Test fun twoQuietWeeksInARowSuggestsAFreshPlan() {
        val now = LocalDate.of(2026, 12, 14)
        val recs = listOf(gym(LocalDate.of(2026, 12, 1), 30), gym(LocalDate.of(2026, 12, 8), 30))
        val h = health(recs, now)
        assertEquals(HealthLevel.SUGGEST, h.level)
        assertTrue(h.reasons.any { it.contains("quiet weeks") })
    }

    @Test fun threeUnwellSkipsInAWeekSuggestsAFreshPlanWithRestFirst() {
        val now = LocalDate.of(2026, 11, 26)
        val sk = (0L..2L).fold(profile) { acc, i ->
            val d = mon.plusDays(i)
            acc.copy(planAdjustments = acc.planAdjustments.copy(skips = acc.planAdjustments.skips + PlanSkip(d, SessionKind.EASY, null, SkipReason.UNWELL, d)))
        }
        val h = health(listOf(gym(mon)), now, sk)
        assertEquals(HealthLevel.SUGGEST, h.level)
        assertTrue(h.reasons.any { it.contains("unwell") })
    }

    @Test fun aSoreKneeSuggestsAFreshPlanWithThePhysioNote() {
        val now = LocalDate.of(2026, 12, 1)
        val base = everythingDone(LocalDate.of(2026, 11, 30))
        val five = health(base + gym(LocalDate.of(2026, 11, 28), knee = 5), now)
        assertEquals(HealthLevel.SUGGEST, five.level)
        assertEquals(RunStageLogic.PHYSIO_NOTE, five.physioNote)
        val two = health(base + gym(LocalDate.of(2026, 11, 27), knee = 4) + walk(LocalDate.of(2026, 11, 30), 30, knee = 4), now)
        assertEquals(HealthLevel.SUGGEST, two.level)
        assertNotNull(two.physioNote)
        val one = health(base + gym(LocalDate.of(2026, 11, 27), knee = 4), now)
        assertEquals(HealthLevel.NONE, one.level)
        assertNull(one.physioNote)
    }

    @Test fun dismissingKeepsItQuietForFiveDays() {
        val now = LocalDate.of(2026, 12, 6)
        assertEquals(HealthLevel.SUGGEST, health(emptyList(), now).level)
        val dismissed = profile.copy(planAdjustments = profile.planAdjustments.dismissReplan(now))
        assertEquals(HealthLevel.NONE, health(emptyList(), now, dismissed).level)
        assertEquals(HealthLevel.NONE, health(emptyList(), now.plusDays(4), dismissed).level)
        assertEquals(HealthLevel.SUGGEST, health(emptyList(), now.plusDays(5), dismissed).level)
    }

    @Test fun wordingIsKindEverywhere() {
        val banned = listOf("fail", "miss", "behind", "lazy", "bad", "guilt")
        val scenarios = listOf(
            health(emptyList(), LocalDate.of(2026, 12, 6)),
            health(everythingDone(LocalDate.of(2026, 12, 2)).filter { it.date != sat && it.date != sun }, LocalDate.of(2026, 12, 3)),
            health(listOf(gym(LocalDate.of(2026, 12, 1), 30), gym(LocalDate.of(2026, 12, 8), 30)), LocalDate.of(2026, 12, 14)),
            health(everythingDone(LocalDate.of(2026, 11, 30)) + gym(LocalDate.of(2026, 11, 28), knee = 6), LocalDate.of(2026, 12, 1)),
        )
        for (h in scenarios) for (t in h.reasons + h.summary + listOfNotNull(h.physioNote)) {
            for (b in banned) assertFalse(t.lowercase().contains(b), "'$t' contains '$b'")
        }
        for (r in SkipReason.entries) for (b in banned) assertFalse(r.line.lowercase().contains(b) || r.label.lowercase().contains(b))
        val key = session(plan(), wed, SessionKind.KEY)
        val texts = SkipReason.entries.flatMap { r ->
            val after = plan(p = RacePlanner.skip(profile, key, r, wed), now = sat)
            listOf(RacePlanner.skipOutcome(after, key), after.weeks[0].adjustment.orEmpty(), RacePlanner.weekProgress(after, emptyList(), sat)!!.message)
        } + listOf(suggest(60, Feeling.ILL).reason, suggest(60, Feeling.ROUGH).reason, suggest(20).reason, suggest(60).reason)
        for (t in texts) for (b in banned) assertFalse(t.lowercase().contains(b), "'$t' contains '$b'")
    }

    // ---------------------------------------------------------------------------------------- export

    @Test fun replanExportHasEverythingClaudeNeeds() {
        val now = LocalDate.of(2026, 12, 9)
        val key = session(plan(), wed, SessionKind.KEY)
        val p = RacePlanner.skip(profile, key, SkipReason.UNWELL, wed)
        val recs = listOf(gym(mon, 45, knee = 2), walk(LocalDate.of(2026, 12, 7), 40, knee = 3), gym(LocalDate.of(2026, 12, 8), 50, knee = 1))
        val text = ReplanExport.text(plan(recs, now, p), recs, p, now)
        assertTrue(text.contains("27 Feb 2027"))
        assertTrue(text.contains("80 days to go"))
        assertTrue(text.contains("week 3 of 14"))
        assertTrue(text.contains("Sessions per week: 4"))
        assertTrue(text.contains("Weekly time cap: 300 minutes"))
        assertTrue(text.contains("Rest weekdays: Tuesday and Friday"))
        assertTrue(text.contains("Event format assumption"))
        assertTrue(text.contains("Week 1 (Base"))
        assertTrue(text.contains("planned") && text.contains("done"))
        assertTrue(text.contains("TRAINING EXPORT 2026-11-26 to 2026-12-09"))
        assertTrue(text.contains("2026-11-25 skipped") && text.contains("unwell"))
        assertTrue(text.contains("2026-12-07 3/10"))
        assertTrue(text.contains("Run stage:"))
        assertTrue(text.contains("Please re-plan my Hyrox prep from here for 27 Feb 2027"))
        assertTrue(text.contains("2 week taper") && text.contains("ITB knee") && text.contains("Tuesday and Friday"))
        assertTrue(text.endsWith("tell me what to change in the app settings."))
        assertEquals(text, ReplanExport.text(plan(recs, now, p), recs, p, now))
    }

    @Test fun replanExportBeforeTheStartAndWithNothingRecorded() {
        val now = LocalDate.of(2026, 11, 19)
        val text = ReplanExport.text(plan(emptyList(), now), emptyList(), profile, now)
        assertTrue(text.contains("Week 1 has not started yet."))
        assertTrue(text.contains("None recorded."))
        assertTrue(text.contains("No knee scores"))
        assertTrue(text.contains("kick-off"))
    }
}
