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

/** A profile with no weekly rest days (the app default is Tuesday and Friday); the older planner tests assume none. */
internal val NO_REST = Profile(restWeekdays = emptySet())

class RacePlannerTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val today = LocalDate.of(2026, 11, 19)
    private val cfg = RacePlanSettings()
    private val week1 = LocalDate.of(2026, 11, 23)
    private var n = 0
    private fun id() = "r${n++}"

    private fun plan(records: List<SessionRecord> = emptyList(), now: LocalDate = today, c: RacePlanSettings = cfg, profile: Profile = NO_REST) =
        RacePlanner.build(c, records, now, profile)

    private fun gym(date: LocalDate, knee: Int? = null, min: Int = 45, feeling: Feeling = Feeling.GOOD) =
        SessionRecord(id(), date, SessionType.GYM, min, min, feeling, kneePain = knee)

    private fun walk(date: LocalDate, min: Int = 45, knee: Int? = null, feeling: Feeling = Feeling.GOOD) =
        SessionRecord(id(), date, SessionType.TREADMILL, min, min, feeling, kneePain = knee,
            treadmill = TreadmillLog(TreadmillStyle.STEADY, min * 60, min * 60, distanceM = min * 85))

    private fun run(date: LocalDate, knee: Int?) =
        SessionRecord(id(), date, SessionType.TREADMILL, 30, 30, Feeling.GOOD, kneePain = knee, dayLabel = "Run-walk",
            treadmill = TreadmillLog(TreadmillStyle.STEADY, 1800, 1800))

    private fun cleanRuns(start: LocalDate, count: Int) = (0 until count).map { run(start.plusDays(it * 2L), 1) }

    // ---------------------------------------------------------------------------------------- shape

    @Test fun weekDatesPhasesAndRaceDay() {
        val p = plan()
        assertEquals(14, p.weeks.size)
        assertEquals(week1, p.weeks[0].start)
        assertEquals(LocalDate.of(2027, 2, 15), p.weeks[12].start)
        assertEquals(LocalDate.of(2027, 2, 22), p.weeks[13].start)
        assertEquals(LocalDate.of(2027, 2, 27), p.race)
        assertTrue(p.weeks.all { it.start.dayOfWeek == DayOfWeek.MONDAY })
        val phases = p.weeks.map { it.phase }
        assertEquals(List(4) { RacePhase.BASE } + List(4) { RacePhase.BUILD } + List(3) { RacePhase.SPECIFIC } +
            listOf(RacePhase.SHARPEN, RacePhase.TAPER, RacePhase.RACE), phases)
        assertEquals(listOf(4, 8), p.weeks.filter { it.deload }.map { it.index })
        assertEquals(listOf(RacePhase.BASE, RacePhase.BUILD, RacePhase.SPECIFIC, RacePhase.SHARPEN, RacePhase.TAPER, RacePhase.RACE), p.phaseTimeline.map { it.phase })
        assertEquals(1..4, p.phaseTimeline[0].let { it.firstWeek..it.lastWeek })
        val race = p.weeks.last()
        val raceDay = race.sessions.last()
        assertEquals(LocalDate.of(2027, 2, 27), raceDay.date)
        assertTrue(raceDay.isRaceDay)
        assertEquals(DayOfWeek.FRIDAY, race.sessions.first { it.kind == SessionKind.REST }.date.dayOfWeek)
        assertTrue(race.sessions.filter { !it.isRaceDay && it.kind != SessionKind.REST }.all { it.date <= LocalDate.of(2027, 2, 25) })
        // Nothing hard after Wednesday.
        assertTrue(race.sessions.filter { it.kind == SessionKind.KEY || it.kind == SessionKind.STRENGTH }.all { it.date <= LocalDate.of(2027, 2, 24) })
        assertEquals(100, RacePlanner.daysToGo(cfg, today))
    }

    @Test fun taperAndRaceWeekReduceVolumeWithinBosquetRange() {
        val p = plan()
        val peak = p.weeks.filter { it.index <= 11 }.maxOf { it.plannedMinutes }
        assertEquals(peak, p.peakMinutes)
        val taper = p.weeks[12].plannedMinutes
        val raceWeek = p.weeks[13].plannedMinutes
        for (w in listOf(taper, raceWeek)) {
            val cut = 1.0 - w.toDouble() / peak
            assertTrue(cut in 0.41..0.60, "cut $cut")
        }
        assertTrue(raceWeek < taper)
        val sharpen = p.weeks[11].plannedMinutes
        assertTrue(sharpen.toDouble() / peak in 0.82..0.88)
        // Intensity is kept: the taper key session is still race-pace, not cut to easy.
        assertTrue(p.weeks[12].sessions.any { it.kind == SessionKind.KEY && it.intensity == Intensity.HARD })
        // Frequency is kept in the taper week.
        assertEquals(p.weeks[10].sessions.size, p.weeks[12].sessions.size)
    }

    @Test fun easyShareIsAtLeastEightyPercentEveryWeek() {
        val p = plan()
        for (w in p.weeks) {
            assertTrue(w.easyShare >= 0.8, "week ${w.index} easy share ${w.easyShare}")
            assertEquals(0.8, w.easyShareTarget)
            assertTrue(w.hardMinutes > 0)
        }
    }

    @Test fun deloadEveryFourthWeekAndGrowthAtMostTenPercent() {
        val p = plan()
        for (d in listOf(4, 8)) {
            val prev = p.weeks[d - 2].plannedMinutes
            assertTrue(p.weeks[d - 1].plannedMinutes <= prev * 0.9, "deload $d")
        }
        var lastNormal = 0
        for (w in p.weeks.filter { it.index <= 11 }) {
            if (w.deload) continue
            if (lastNormal > 0) assertTrue(w.plannedMinutes <= lastNormal * 1.10 + 0.01, "week ${w.index}: ${w.plannedMinutes} vs $lastNormal")
            assertTrue(w.plannedMinutes >= lastNormal, "normal weeks never shrink with full compliance")
            lastNormal = w.plannedMinutes
        }
        assertEquals(RacePlanner.DEFAULT_BASELINE_MIN, p.baselineMinutes)
        assertTrue(p.weeks[0].plannedMinutes in 195..205)
    }

    @Test fun baselineFromRecentHistory() {
        // 4 weeks before 2026-11-23 at about 300 min/week.
        val recs = (0 until 8).map { gym(week1.minusDays(2L + it * 3), min = 150) }
        val p = plan(recs, week1)
        assertTrue(p.baselineMinutes in 295..300, "${p.baselineMinutes}")
        assertTrue(p.weeks[0].plannedMinutes >= 290)
        // A heavy history now starts at about +10% on its real average (330) and compounds at most 10% a week,
        // up to the 5-session maximum; it no longer jumps to a fixed peak target.
        for (w in p.weeks.filter { it.index in 2..11 && !it.deload }) assertTrue(w.plannedMinutes <= 405)
    }

    // ---------------------------------------------------------------------------------------- scheduling

    private fun allSessions(p: RacePlan) = p.weeks.flatMap { it.sessions }.filter { it.kind != SessionKind.REST }

    @Test fun strengthNeverConsecutiveAndNeverBeforeKey() {
        for (c in 3..6) {
            val p = plan(c = RacePlanSettings(sessionsPerWeek = c))
            val s = allSessions(p)
            val byDate = s.associateBy { it.date }
            for (x in s.filter { it.kind == SessionKind.STRENGTH }) {
                val next = byDate[x.date.plusDays(1)]
                assertTrue(next?.kind != SessionKind.STRENGTH, "$c: strength twice in a row at ${x.date}")
                assertTrue(next?.kind != SessionKind.KEY && next?.kind != SessionKind.SIM, "$c: strength before key at ${x.date}")
            }
        }
    }

    @Test fun twoStrengthSessionsAndAKeyEveryWeek() {
        for (c in 3..6) {
            val p = plan(c = RacePlanSettings(sessionsPerWeek = c))
            for (w in p.weeks.filter { it.index <= 13 }) {
                assertEquals(2, w.sessions.count { it.kind == SessionKind.STRENGTH }, "$c week ${w.index}")
                assertEquals(1, w.sessions.count { it.kind == SessionKind.KEY || it.kind == SessionKind.SIM }, "$c week ${w.index}")
                assertEquals(c, w.sessions.size, "$c week ${w.index}")
                assertTrue(w.sessions.map { it.date }.distinct().size == w.sessions.size)
            }
        }
        val p3 = plan(c = RacePlanSettings(sessionsPerWeek = 3))
        assertTrue(allSessions(p3).filter { it.date < LocalDate.of(2027, 2, 22) }.none { it.kind == SessionKind.EASY || it.kind == SessionKind.LONG_EASY })
        val p4 = plan(c = RacePlanSettings(sessionsPerWeek = 4))
        assertTrue(p4.weeks.filter { it.index <= 13 }.all { w -> w.sessions.any { it.kind == SessionKind.LONG_EASY } })
        assertTrue(allSessions(p4).filter { it.date < LocalDate.of(2027, 2, 22) }.none { it.kind == SessionKind.EASY })
        // Fewer sessions plan less time, never more than the default week.
        assertTrue(p3.weeks[10].plannedMinutes < p4.weeks[10].plannedMinutes)
        assertTrue(p4.weeks[10].plannedMinutes < plan().weeks[10].plannedMinutes)
        // Taper is still 41-60% below the peak with 3 sessions.
        val peak3 = p3.peakMinutes
        assertTrue(1.0 - p3.weeks[12].plannedMinutes.toDouble() / peak3 in 0.41..0.60)
    }

    @Test fun defaultWeekHasLongSessionAtTheWeekend() {
        val p = plan()
        for (w in p.weeks.filter { it.index in 2..13 }) {
            val long = w.sessions.first { it.kind == SessionKind.LONG_EASY }
            assertTrue(long.date.dayOfWeek in listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), "week ${w.index}")
        }
    }

    @Test fun noSessionsOnRestDays() {
        var prof = NO_REST
        prof = RestDays.toggleWeekday(prof, DayOfWeek.SUNDAY)
        prof = RestDays.toggleWeekday(prof, DayOfWeek.FRIDAY)
        prof = RestDays.toggleDate(prof, LocalDate.of(2026, 12, 9))
        val p = plan(profile = prof)
        for (s in allSessions(p).filter { !it.isRaceDay }) assertFalse(RestDays.isRestDay(prof, s.date), "session on rest day ${s.date}")
        for (w in p.weeks.filter { it.index <= 13 }) {
            assertEquals(2, w.sessions.count { it.kind == SessionKind.STRENGTH })
            assertTrue(w.sessions.any { it.kind == SessionKind.KEY || it.kind == SessionKind.SIM })
        }
        // A rest day can be worked on once 'train anyway' is chosen.
        val any = RestDays.trainAnyway(prof, LocalDate.of(2026, 12, 9))
        assertFalse(RestDays.isRestDay(any, LocalDate.of(2026, 12, 9)))
    }

    @Test fun veryFewFreeDaysKeepsKeyAndStrengthFirst() {
        var prof = NO_REST
        listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SUNDAY).forEach { prof = RestDays.toggleWeekday(prof, it) }
        val p = plan(profile = prof)
        for (w in p.weeks.filter { it.index <= 13 }) {
            assertEquals(3, w.sessions.size, "week ${w.index}")
            assertEquals(2, w.sessions.count { it.kind == SessionKind.STRENGTH })
        }
    }

    // ---------------------------------------------------------------------------------------- hyrox ladder

    @Test fun stationLaddersFollowThePhases() {
        val p = plan()
        val general = listOf(HyroxStation.SLED_PUSH, HyroxStation.SLED_PULL, HyroxStation.FARMERS_CARRY, HyroxStation.WALL_BALLS)
        for (s in general) assertTrue(p.weeks[2].stationTargets.getValue(s) >= 3, "base reaches 3: $s")
        for (s in HyroxStation.entries) {
            val best = p.weeks.filter { it.index <= 10 }.maxOf { it.stationTargets.getValue(s) }
            assertEquals(HyroxLadder.RACE_LEVEL, best, "race standard before the rehearsal: $s")
            assertTrue(p.weeks[7].stationTargets.getValue(s) in 3..5 || s.kneeHeavy, "build: $s")
        }
        for (s in HyroxStation.entries.filter { it.kneeHeavy }) {
            assertTrue(p.weeks[2].stationTargets.getValue(s) < p.weeks[2].stationTargets.getValue(HyroxStation.SLED_PUSH), "knee-heavy ramps slower: $s")
            assertTrue(p.weeks[5].stationTargets.getValue(s) <= p.weeks[5].stationTargets.getValue(HyroxStation.SLED_PUSH))
        }
    }

    @Test fun everyStationIsVisitedEveryTwoWeeks() {
        val p = plan()
        for (start in 1..12) {
            val covered = p.weeks.filter { it.index in start..start + 1 }.flatMap { it.sessions }.flatMap { it.launch.hyroxFocus }.toSet()
            assertEquals(HyroxStation.entries.toSet(), covered, "weeks $start-${start + 1}")
        }
    }

    @Test fun sessionsCarryEvidenceAndStepsAndPrehab() {
        val p = plan()
        for (s in allSessions(p)) {
            assertTrue(s.why.isNotBlank() && s.detail.isNotEmpty() && s.title.isNotBlank(), "${s.date}")
            assertEquals(s.minutes, s.launch.minutes)
        }
        val strength = p.weeks[4].sessions.first { it.kind == SessionKind.STRENGTH }
        assertTrue(strength.detail.any { it.contains("Hip-abductor") })
        assertTrue(strength.detail.any { it.contains("level") }, "BUILD adds a station finisher")
        assertTrue(p.weeks[0].sessions.first { it.kind == SessionKind.KEY }.detail.any { it.contains("x 6 minutes at RPE 6-7") })
        assertTrue(p.weeks[4].sessions.first { it.kind == SessionKind.KEY }.title.contains("x 4 min"))
        assertEquals(SessionType.GYM, strength.launch.sessionType)
        // Sims.
        assertEquals(SessionKind.SIM, p.weeks[8].sessions.first { it.kind == SessionKind.SIM }.kind)
        assertTrue(p.weeks[10].sessions.any { it.kind == SessionKind.SIM && it.title.contains("Near-full") })
    }

    @Test fun missingKitIsSubstitutedAndSaid() {
        val prof = Profile(settings = UserSettings(hyroxGymGear = setOf(HyroxGear.ROWER, HyroxGear.SKIERG)))
        val p = plan(profile = prof)
        val finisher = p.weeks[4].sessions.first { it.kind == SessionKind.STRENGTH }
        assertTrue(finisher.detail.any { it.contains("instead") })
    }

    // ---------------------------------------------------------------------------------------- run stage

    @Test fun runStageStartsAtNone() {
        assertEquals(RunStage.NONE, RunStageLogic.stage(emptyList(), today))
        // Walking (slow average speed) never counts as running.
        val st = RunStageLogic.assess(List(6) { walk(today.minusDays(it * 2L + 1), knee = 1) }, today)
        assertEquals(RunStage.NONE, st.stage)
        assertNull(st.physioNote)
    }

    @Test fun calmWeeksLeadToRunWalk() {
        val d0 = LocalDate.of(2026, 11, 23)
        val recs = listOf(0L, 4L, 8L, 12L, 15L).map { gym(d0.plusDays(it), knee = 1) }
        assertEquals(RunStage.NONE, RunStageLogic.stage(recs.take(4), d0.plusDays(12)))
        assertEquals(RunStage.RUN_WALK, RunStageLogic.stage(recs, d0.plusDays(15)))
        // A flare resets the calm clock.
        val flared = recs.take(3) + gym(d0.plusDays(9), knee = 3) + recs.drop(3)
        assertEquals(RunStage.NONE, RunStageLogic.stage(flared, d0.plusDays(15)))
    }

    @Test fun twoCleanRunsAdvanceAndStagesClimb() {
        val d0 = LocalDate.of(2026, 12, 1)
        assertEquals(RunStage.NONE, RunStageLogic.stage(cleanRuns(d0, 1), d0.plusDays(5)))
        assertEquals(RunStage.RUN_WALK, RunStageLogic.stage(cleanRuns(d0, 2), d0.plusDays(5)))
        val six = cleanRuns(d0, 6)
        val st = RunStageLogic.assess(six, d0.plusDays(20))
        assertEquals(RunStage.CONTINUOUS, st.stage)
        assertEquals(0, st.cleanRuns)
        assertEquals(RunStage.INTERVALS, RunStageLogic.stage(cleanRuns(d0, 10), d0.plusDays(30)))
        // Same-day runs count once.
        val sameDay = listOf(run(d0, 1), run(d0, 1), run(d0, 1))
        assertEquals(RunStage.NONE, RunStageLogic.stage(sameDay, d0))
    }

    @Test fun kneeThreeOrFourHoldsAndFlareBlocksAdvancingForSevenDays() {
        val d0 = LocalDate.of(2026, 12, 1)
        val base = cleanRuns(d0, 2)                       // RUN_WALK on day 2
        val flare = run(d0.plusDays(4), 3)                // hold
        val st = RunStageLogic.assess(base + flare, d0.plusDays(5))
        assertEquals(RunStage.RUN_WALK, st.stage)
        assertTrue(st.flareRecent)
        assertNull(st.physioNote)
        assertFalse(st.message.contains("fail", ignoreCase = true))
        // Clean runs within 7 days of the flare do not count.
        val soon = base + flare + run(d0.plusDays(6), 1) + run(d0.plusDays(8), 1)
        assertEquals(0, RunStageLogic.assess(soon, d0.plusDays(9)).cleanRuns)
        // After the cooling-off they do, and four make it to the next stage.
        val later = base + flare + (0 until 4).map { run(d0.plusDays(12L + it * 2), 1) }
        assertEquals(RunStage.CONTINUOUS, RunStageLogic.stage(later, d0.plusDays(20)))
        val laterTwo = base + flare + (0 until 2).map { run(d0.plusDays(12L + it * 2), 1) }
        assertEquals(2, RunStageLogic.assess(laterTwo, d0.plusDays(20)).cleanRuns)
        // A 4 on its own is a hold, not a step back.
        val four = RunStageLogic.assess(base + run(d0.plusDays(4), 4), d0.plusDays(5))
        assertEquals(RunStage.RUN_WALK, four.stage)
        assertNull(four.steppedBackOn)
    }

    @Test fun kneeFiveOrTwoFoursStepBackWithPhysioNote() {
        val d0 = LocalDate.of(2026, 12, 1)
        val six = cleanRuns(d0, 6)                        // CONTINUOUS around day 10
        val after = d0.plusDays(14)
        val five = RunStageLogic.assess(six + run(after, 5), after.plusDays(1))
        assertEquals(RunStage.RUN_WALK, five.stage)
        assertEquals(after, five.steppedBackOn)
        assertNotNull(five.physioNote)
        assertTrue(five.physioNote!!.contains("physio") && five.physioNote!!.contains("couple of weeks"))
        assertTrue(five.message.contains("not a setback"))
        // Two sessions in a row at 4+ (any session type) also step back.
        val two = RunStageLogic.assess(six + gym(after, 4) + run(after.plusDays(2), 4), after.plusDays(3))
        assertEquals(RunStage.RUN_WALK, two.stage)
        assertNotNull(two.physioNote)
        // 4 then a calm session is only a hold.
        val one = RunStageLogic.assess(six + gym(after, 4) + gym(after.plusDays(2), 1), after.plusDays(3))
        assertEquals(RunStage.CONTINUOUS, one.stage)
        // Never below NONE.
        assertEquals(RunStage.NONE, RunStageLogic.stage(listOf(gym(d0, 6)), d0))
    }

    @Test fun consistentRunningHistoryStartsHigher() {
        val d0 = LocalDate.of(2026, 11, 1)
        val runs = (0 until 8).map { run(d0.plusDays(it * 3L), 1) }
        assertTrue(RunStageLogic.stage(runs, d0.plusDays(30)) >= RunStage.CONTINUOUS)
    }

    @Test fun hyroxRunLogsCountAsRuns() {
        val d0 = LocalDate.of(2026, 12, 1)
        fun hy(d: LocalDate) = SessionRecord(id(), d, SessionType.HYROX, 40, 40, Feeling.GOOD, kneePain = 1,
            stations = listOf(StationLog(HyroxRuns.ID, distanceM = 400, seconds = 150), StationLog("skierg", distanceM = 500)))
        assertEquals(RunStage.RUN_WALK, RunStageLogic.stage(listOf(hy(d0), hy(d0.plusDays(3))), d0.plusDays(4)))
    }

    @Test fun planUsesOnlyWhatTheStageAllows() {
        val p = plan()
        // Week 1-2: walking only. No running anywhere, and no treadmill-run option.
        for (w in p.weeks.take(2)) {
            assertEquals(RunStage.NONE, w.runStage)
            for (s in w.sessions) {
                assertFalse(s.includesRunning, "week ${w.index}")
                assertFalse(Modality.TREADMILL_RUN in s.modalityOptions)
            }
        }
        // Projection is flagged and reaches the sim only through the stages.
        assertTrue(p.weeks[5].runStageProjected)
        assertTrue(p.weeks[8].runStage < RunStage.RACE_RUNS)
        val half = p.weeks[8].sessions.first { it.kind == SessionKind.SIM }
        assertTrue(half.detail.any { it.contains("400 m") || it.contains("not cleared") })
        // With no stage for running the rehearsal substitutes rower / incline walk and says so.
        val noRun = plan(listOf(gym(today, knee = 4), gym(today.minusDays(1), knee = 4)))
        val sim = noRun.weeks[10].sessions.first { it.kind == SessionKind.SIM }
        assertTrue(sim.detail.any { it.contains("not cleared for running") && it.contains("rower") })
        assertTrue(noRun.runStatus.stage == RunStage.NONE)
        // Rehearsal with 1 km runs only when the stage is RACE_RUNS.
        val runner = plan(cleanRuns(LocalDate.of(2026, 10, 1), 14), now = LocalDate.of(2027, 2, 1))
        assertEquals(RunStage.RACE_RUNS, runner.runStatus.stage)
        assertTrue(runner.weeks[10].sessions.first { it.kind == SessionKind.SIM }.detail.any { it.contains("1 km easy-steady run") })
    }

    @Test fun noTwoRunDaysInARowEarly() {
        val recs = cleanRuns(LocalDate.of(2026, 11, 1), 6)   // CONTINUOUS
        val p = plan(recs, now = LocalDate.of(2026, 11, 20))
        assertEquals(RunStage.CONTINUOUS, p.runStatus.stage)
        var count = 0
        for (w in p.weeks.filter { it.runStage < RunStage.INTERVALS }) {
            val runDays = w.sessions.filter { it.includesRunning }.map { it.date }.toSet()
            count += runDays.size
            for (d in runDays) assertFalse(d.plusDays(1) in runDays, "consecutive run days in week ${w.index}")
        }
        assertTrue(count > 0)
    }

    @Test fun kneeFlareCapsKneeHeavyStations() {
        val recs = listOf(gym(today.minusDays(1), knee = 4), gym(today, knee = 3))
        val p = plan(recs)
        for (w in p.weeks) for (s in HyroxStation.entries.filter { it.kneeHeavy }) assertTrue(w.stationTargets.getValue(s) <= 3)
        assertTrue(p.weeks[8].sessions.first { it.kind == SessionKind.SIM }.detail.any { it.contains("Knee note") })
    }

    // ---------------------------------------------------------------------------------------- adaptive

    private fun fullWeek(start: LocalDate, minutes: Int = 220) =
        listOf(0L, 2L, 3L, 5L).map { gym(start.plusDays(it), min = minutes / 4) }

    @Test fun lightWeekRepeatsTargetsWithoutCatchingUp() {
        val nominal = plan()
        val w2 = week1.plusWeeks(1)
        val recs = fullWeek(week1, 200) + gym(w2.plusDays(1), min = 30)
        val now = week1.plusWeeks(2).plusDays(1)
        val p = plan(recs, now)
        assertEquals(nominal.weeks[1].plannedMinutes, p.weeks[1].plannedMinutes)
        assertEquals(p.weeks[1].plannedMinutes, p.weeks[2].plannedMinutes, "week 3 repeats week 2")
        assertNotNull(p.weeks[2].adjustment)
        assertTrue(p.weeks[2].adjustment!!.contains("normal"))
        // No catching up: no week ever plans more than the nominal plan did, nor more sessions.
        for (i in 0 until 14) {
            assertTrue(p.weeks[i].plannedMinutes <= nominal.weeks[i].plannedMinutes, "week ${i + 1}")
            assertTrue(p.weeks[i].sessions.size <= nominal.weeks[i].sessions.size)
        }
        // Progress resumes after the repeat, still within 10%.
        assertTrue(p.weeks[4].plannedMinutes > p.weeks[2].plannedMinutes)
        assertTrue(p.weeks[4].plannedMinutes <= p.weeks[2].plannedMinutes * 1.10 + 0.01)
    }

    @Test fun fullWeeksKeepProgressing() {
        val nominal = plan()
        val recs = fullWeek(week1, 200) + fullWeek(week1.plusWeeks(1), 215)
        val p = plan(recs, week1.plusWeeks(2).plusDays(1))
        assertEquals(nominal.weeks.map { it.plannedMinutes }, p.weeks.map { it.plannedMinutes })
        assertNull(p.weeks[2].adjustment)
    }

    @Test fun gapOfTenDaysStepsBackAWeek() {
        val nominal = plan()
        val recs = fullWeek(week1, 200) + fullWeek(week1.plusWeeks(1), 215)
        val now = week1.plusWeeks(4).plusDays(1)     // week 5, 20 days after the last session
        val p = plan(recs, now)
        assertTrue(p.weeks[4].plannedMinutes < nominal.weeks[4].plannedMinutes)
        assertNotNull(p.weeks[4].adjustment)
        assertTrue(p.weeks[4].adjustment!!.contains("break"))
        assertFalse(p.weeks[4].adjustment!!.contains("miss", ignoreCase = true))
    }

    @Test fun taperAndRaceWeekStayAnchoredToTheRaceDate() {
        val nominal = plan()
        val recs = fullWeek(week1, 200) + gym(week1.plusWeeks(1).plusDays(1), min = 20)
        val p = plan(recs, week1.plusWeeks(5).plusDays(2))
        assertEquals(nominal.weeks.map { it.start }, p.weeks.map { it.start })
        assertEquals(LocalDate.of(2027, 2, 22), p.weeks[13].start)
        assertEquals(LocalDate.of(2027, 2, 27), p.weeks[13].sessions.last().date)
        assertEquals(RacePhase.TAPER, p.weeks[12].phase)
        val peak = p.weeks.filter { it.index <= 11 }.maxOf { it.plannedMinutes }
        assertTrue(1.0 - p.weeks[12].plannedMinutes.toDouble() / peak in 0.41..0.60)
        assertTrue(1.0 - p.weeks[13].plannedMinutes.toDouble() / peak in 0.41..0.60)
        assertEquals(LocalDate.of(2027, 2, 27), RacePlanner.thisWeek(p, LocalDate.of(2027, 2, 27))!!.sessions.last().date)
    }

    @Test fun feelingIllDropsTheKeySessionFirst() {
        val wed = LocalDate.of(2026, 12, 9)
        val recs = fullWeek(week1, 200) + gym(LocalDate.of(2026, 12, 8), feeling = Feeling.ILL)
        val p = plan(recs, wed)
        val w = RacePlanner.thisWeek(p, wed)!!
        assertEquals(3, w.index)
        assertTrue(w.sessions.none { it.kind == SessionKind.KEY && it.date >= wed })
        assertTrue(w.sessions.any { it.kind == SessionKind.STRENGTH })
        assertNotNull(w.adjustment)
        // Well again: the key session is back.
        val well = plan(fullWeek(week1, 200) + gym(LocalDate.of(2026, 12, 8)), wed)
        assertTrue(RacePlanner.thisWeek(well, wed)!!.sessions.any { it.kind == SessionKind.KEY })
        // A key session already past is left alone.
        val later = plan(recs, LocalDate.of(2026, 12, 11))
        assertTrue(RacePlanner.thisWeek(later, LocalDate.of(2026, 12, 11))!!.sessions.any { it.kind == SessionKind.KEY && it.date < LocalDate.of(2026, 12, 11) })
    }

    @Test fun beforeTheStartAndDuringTheWeekHelpers() {
        val p = plan()
        // Before week 1 the kick-off lead-in (index 0) is 'this week'; from week 1's Monday it is week 1.
        assertEquals(0, RacePlanner.thisWeek(p, today)!!.index)
        assertTrue(RacePlanner.thisWeek(p, today)!!.isLeadIn)
        assertEquals(1, RacePlanner.thisWeek(p, week1)!!.index)
        assertEquals(14, RacePlanner.thisWeek(p, LocalDate.of(2027, 2, 27))!!.index)
        assertNull(RacePlanner.thisWeek(p, LocalDate.of(2027, 3, 1)))
        val mon = RacePlanner.today(p, week1)
        assertEquals(SessionKind.STRENGTH, mon!!.kind)
        assertNull(RacePlanner.today(p, week1.plusDays(2)))
        assertEquals(3, RacePlanner.weekIndexOf(cfg.raceDate, LocalDate.of(2026, 12, 9)))
        assertEquals(0, RacePlanner.weekIndexOf(cfg.raceDate, today))
    }

    @Test fun weekProgressUsesNoGuiltWording() {
        val p = plan()
        val w1 = p.weeks[0]
        val none = RacePlanner.weekProgress(p, emptyList(), week1.plusDays(2))!!
        assertEquals(0, none.doneMinutes)
        assertEquals(w1.plannedMinutes, none.plannedMinutes)
        val half = RacePlanner.weekProgress(p, listOf(gym(week1, min = 60)), week1.plusDays(3))!!
        assertEquals(60, half.doneMinutes)
        assertTrue(half.fraction in 0.2..0.4)
        val done = RacePlanner.weekProgress(p, listOf(gym(week1, min = 250)), week1.plusDays(3))!!
        assertEquals(1.0, done.fraction)
        assertTrue(done.message.contains("bonus"))
        val bad = listOf("miss", "fail", "behind", "late", "red", "lazy", "should have", "only ")
        val all = listOf(none, half, done) + listOf(
            RacePlanner.weekProgress(p, emptyList(), week1.plusWeeks(1).plusDays(6))!!,
        )
        for (m in all.map { it.message }) for (b in bad) assertFalse(m.contains(b, ignoreCase = true), "'$b' in: $m")
        // A past light week is framed as normal. (Check via the planner's own text for a closed week.)
        val past = RacePlanner.weekProgress(plan(emptyList(), week1.plusWeeks(1).plusDays(6)), listOf(gym(week1.plusWeeks(1), min = 30)), week1.plusWeeks(1).plusDays(6))!!
        assertTrue(past.message.isNotBlank())
        for (b in bad) assertFalse(past.message.contains(b, ignoreCase = true), "'$b' in: ${past.message}")
        // Adjustments and run messages carry no blame either.
        for (w in p.weeks) for (b in bad.take(5)) assertFalse((w.adjustment ?: "").contains(b, ignoreCase = true))
    }

    // ---------------------------------------------------------------------------------------- balance

    private fun hyrox(date: LocalDate, minutes: Int, vararg rpes: Int) =
        SessionRecord(id(), date, SessionType.HYROX, minutes, minutes, Feeling.GOOD,
            stations = rpes.mapIndexed { i, r -> StationLog("sled_push", rpe = r, seconds = 300 + i) })

    @Test fun intensityBalanceClassifiesApproximately() {
        val mon = week1
        val steady = walk(mon, 60)
        val intervals = SessionRecord(id(), mon.plusDays(1), SessionType.TREADMILL, 48, 48, Feeling.GOOD,
            treadmill = TreadmillLog(TreadmillStyle.INTERVALS, 2880, 2880))
        val strength = gym(mon.plusDays(2), min = 50)
        val hx = hyrox(mon.plusDays(3), 48, 8, 5, 5, 5)   // 1 of 4 equal blocks at RPE 8 -> 10 of 40 work minutes
        assertEquals(TimeSplit(60, 0, 0), IntensityBalance.classify(steady))
        assertEquals(TimeSplit(28, 20, 0), IntensityBalance.classify(intervals))
        assertEquals(TimeSplit(0, 0, 50), IntensityBalance.classify(strength))
        assertEquals(TimeSplit(38, 10, 0), IntensityBalance.classify(hx))
        val r = IntensityBalance.of(listOf(steady, intervals, strength, hx), mon.plusDays(2))
        assertEquals(mon, r.weekStart)
        assertEquals(TimeSplit(126, 30, 50), r.week)
        assertEquals(r.week, r.last4Weeks)
        assertEquals(BalanceStatus.ON_TARGET, r.weekStatus)
        assertEquals("about 80/20", r.targetLabel)
        assertEquals("about 80/20", r.weekLabel)
        assertFalse(r.message.contains("fail", ignoreCase = true))
        // Last 4 weeks window.
        val old = walk(mon.minusWeeks(3), 100)
        val tooOld = walk(mon.minusWeeks(4), 100)
        val r2 = IntensityBalance.of(listOf(old, tooOld, steady), mon)
        assertEquals(160, r2.last4Weeks.easyMin)
        assertEquals(60, r2.week.easyMin)
        // A week of three interval sessions leans hard, and is said neutrally.
        val hard = (0L..2L).map { SessionRecord(id(), mon.plusDays(it), SessionType.TREADMILL, 48, 48, Feeling.GOOD, treadmill = TreadmillLog(TreadmillStyle.INTERVALS, 2880, 2880)) }
        val rh = IntensityBalance.of(hard, mon)
        assertEquals(BalanceStatus.MOSTLY_HARD, rh.weekStatus)
        assertFalse(rh.message.contains("fail", ignoreCase = true))
        // Not enough data.
        assertEquals(BalanceStatus.NOT_ENOUGH_DATA, IntensityBalance.of(emptyList(), mon).weekStatus)
        assertNull(IntensityBalance.of(emptyList(), mon).weekLabel)
        // The planned week itself is about 80/20 or easier.
        val pw = plan().weeks[5]
        assertTrue(pw.easyShare >= 0.8)
    }

    // ---------------------------------------------------------------------------------------- settings, determinism

    @Test fun settingsJsonRoundTripAndOldJson() {
        val s = RacePlanSettings(raceDate = LocalDate.of(2027, 3, 6), sessionsPerWeek = 4, enabled = false)
        assertEquals(s, json.decodeFromString<RacePlanSettings>(json.encodeToString(s)))
        val u = UserSettings(racePlan = s)
        assertEquals(u, json.decodeFromString<UserSettings>(json.encodeToString(u)))
        // Old JSON without the field.
        val old = json.decodeFromString<UserSettings>("""{"gymTarget":4}""")
        // The app's own default is Calum's answers (4 sessions, 300 min cap); the planner's defaults are unchanged.
        assertEquals(RacePlanSettings(sessionsPerWeek = 4, maxWeeklyMinutes = 300), old.racePlan)
        assertEquals(LocalDate.of(2027, 2, 27), old.racePlan.raceDate)
        assertEquals(4, old.racePlan.sessionsPerWeek)
        assertEquals(300, old.racePlan.maxWeeklyMinutes)
        assertEquals(5, RacePlanSettings().sessionsPerWeek)
        assertTrue(old.racePlan.enabled)
        assertEquals(UserSettings().racePlan, json.decodeFromString<Profile>("{}").settings.racePlan)
        assertEquals(6, RacePlanSettings(sessionsPerWeek = 9).sanitized().sessionsPerWeek)
        assertEquals(3, UserSettings(racePlan = RacePlanSettings(sessionsPerWeek = 1)).sanitized().racePlan.sessionsPerWeek)
    }

    @Test fun buildIsDeterministic() {
        val recs = fullWeek(week1, 200) + cleanRuns(LocalDate.of(2026, 11, 1), 3)
        val a = plan(recs, week1.plusWeeks(2))
        val b = plan(recs.reversed(), week1.plusWeeks(2))
        assertEquals(a, b)
        assertEquals(plan(), plan())
    }

    @Test fun otherRaceDatesAnchorTheSamePlan() {
        val p = plan(c = RacePlanSettings(raceDate = LocalDate.of(2027, 3, 6)))
        assertEquals(LocalDate.of(2027, 3, 1), p.weeks.last().start)
        assertEquals(LocalDate.of(2027, 3, 6), p.weeks.last().sessions.last().date)
        assertEquals(LocalDate.of(2026, 11, 30), p.weeks.first().start)
    }
}
