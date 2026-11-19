package com.calum.traininglog.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuidedPlanTest {
    private val date = LocalDate.of(2026, 11, 25)

    private fun session(
        minutes: Int, intensity: Intensity = Intensity.EASY, hard: Int = 0, rpe: IntRange = 2..4, detail: List<String> = emptyList(),
        mods: List<Modality> = listOf(Modality.ROW, Modality.SKIERG, Modality.TREADMILL_WALK, Modality.BIKE), kind: SessionKind = SessionKind.EASY,
    ) = PlannedSession(
        date, kind, "Test session", minutes, intensity, hard, rpe, mods, detail, "Because.",
        LaunchHint(SessionType.TREADMILL, TreadmillStyle.STEADY, emptySet(), minutes),
    )

    private fun vo2(minutes: Int, reps: Int = 4) = session(
        minutes, Intensity.HARD, reps * 4, 8..9,
        listOf(
            "Warm up 10 minutes easy (RPE 3) with 3 short pick-ups.",
            "$reps x 4 minutes at RPE 8-9 (hard, last rep still controlled), 3 minutes easy between.",
            "Cool down 5 minutes easy.",
        ),
        kind = SessionKind.KEY,
    )

    // ---------------------------------------------------------------------------------------- easy

    @Test fun easyBlockHasWarmupHalfwayCheckInAndCooldown() {
        val p = GuidedPlan.from(session(45))
        assertEquals(45 * 60, p.totalSec)
        assertEquals(listOf(GuidedKind.WARMUP, GuidedKind.EASY, GuidedKind.EASY, GuidedKind.COOLDOWN), p.steps.map { it.kind })
        assertEquals(300, p.steps.first().seconds)
        assertEquals(180, p.steps.last().seconds)
        val check = p.steps.filter { it.checkIn }
        assertEquals(1, check.size)
        assertTrue("Still able to chat? Good" in check.single().cue)
        // The two halves are within half a minute of each other.
        val halves = p.steps.filter { it.kind == GuidedKind.EASY }.map { it.seconds }
        assertTrue(kotlin.math.abs(halves[0] - halves[1]) <= 30, "$halves")
        assertEquals(0, p.workSec)
        assertEquals(3, p.defaultRpe)
    }

    @Test fun easyBlockNeverNagsAboutPace() {
        val p = GuidedPlan.from(session(60))
        val text = p.steps.joinToString(" ") { it.cue + it.name }.lowercase()
        listOf("faster", "speed up", "push", "harder", "pace yourself", "split").forEach { assertTrue(it !in text, it) }
        assertTrue("no need to watch the pace" in text)
    }

    @Test fun shortEasySessionIsOneBlock() {
        val p = GuidedPlan.from(session(15))
        assertEquals(15 * 60, p.totalSec)
        assertEquals(1, p.steps.count { it.kind == GuidedKind.EASY })
        assertTrue(p.steps.none { it.checkIn })
    }

    @Test fun warmupAndCooldownComeFromTheDetailWhenGiven() {
        val p = GuidedPlan.from(session(40, detail = listOf("Warm up 10 minutes easy.", "Cool down 5 minutes easy.")))
        assertEquals(600, p.steps.first().seconds)
        assertEquals(300, p.steps.last().seconds)
        assertEquals(40 * 60, p.totalSec)
    }

    // ---------------------------------------------------------------------------------------- intervals

    @Test fun intervalSetIsParsedFromTheDetail() {
        val p = GuidedPlan.from(vo2(40))
        assertTrue(p.intervals)
        assertEquals(40 * 60, p.totalSec)
        val work = p.steps.filter { it.hard }
        assertEquals(4, work.size)
        assertTrue(work.all { it.seconds == 240 })
        val rec = p.steps.filter { it.kind == GuidedKind.RECOVERY }
        assertEquals(3, rec.size)
        assertTrue(rec.all { it.seconds == 180 })
        assertEquals(960, p.workSec)
        assertEquals(GuidedKind.WARMUP, p.steps.first().kind)
        assertEquals(600, p.steps.first().seconds)
        assertEquals(GuidedKind.COOLDOWN, p.steps.last().kind)
        assertEquals(300, p.steps.last().seconds)
        assertEquals("Hard 1 of 4", work.first().name)
        assertEquals(8, p.defaultRpe)
    }

    @Test fun spareTimeGoesToAnEasyFinishSoTotalsStillMatch() {
        val p = GuidedPlan.from(vo2(70, reps = 4))
        assertEquals(70 * 60, p.totalSec)
        assertEquals(4, p.workSteps)
        assertTrue(p.steps.any { it.kind == GuidedKind.EASY })
        val small = GuidedPlan.from(vo2(48, reps = 4)) // 10 + 25 + 5 = 40, 8 spare
        assertEquals(48 * 60, small.totalSec)
        assertTrue(small.steps.none { it.kind == GuidedKind.EASY })
        assertEquals(GuidedKind.COOLDOWN, small.steps.last().kind)
    }

    @Test fun aTooShortSetLosesRepsFirst() {
        val p = GuidedPlan.from(vo2(30, reps = 6))
        assertTrue(p.workSteps in 1..4)
        assertTrue(kotlin.math.abs(p.totalSec - 30 * 60) <= 60, "${p.totalSec}")
    }

    @Test fun intervalsWithoutAParsableSetAreDerivedFromHardMinutes() {
        val s = session(
            60, Intensity.HARD, 27, 7..8,
            listOf("Warm up 10 minutes easy.", "3 x [2 stations for about 3 minutes + a run leg], 3 minutes easy between rounds.", "Cool down 5 minutes."),
            mods = listOf(Modality.HYROX_STATIONS, Modality.ROW), kind = SessionKind.KEY,
        )
        val p = GuidedPlan.from(s, Modality.ROW)
        assertTrue(p.workSteps >= 2)
        assertEquals(60 * 60, p.totalSec)
    }

    @Test fun everyPlannedSessionOfTheWholePlanFitsItsMinutes() {
        val plan = RacePlanner.build(RacePlanSettings(), emptyList(), LocalDate.of(2026, 11, 19))
        var checked = 0
        for (w in plan.weeks) for (s in w.sessions) {
            if (s.kind == SessionKind.STRENGTH || s.kind == SessionKind.REST || s.minutes < 10) continue
            for (m in listOf(Modality.ROW, Modality.SKIERG, Modality.BIKE)) {
                val g = GuidedPlan.from(s, m)
                assertTrue(kotlin.math.abs(g.totalSec - s.minutes * 60) <= 60, "${s.title} ${s.minutes}: ${g.totalSec}")
                assertTrue(g.steps.all { it.seconds >= 30 }, s.title)
                assertEquals(GuidedKind.WARMUP, g.steps.first().kind)
                assertEquals(GuidedKind.COOLDOWN, g.steps.last().kind)
                if (s.intensity == Intensity.HARD) assertTrue(g.workSec > 0, s.title)
                else assertEquals(0, g.workSec)
                checked++
            }
        }
        assertTrue(checked > 40, "checked $checked")
    }

    @Test fun rpeWordsArePlain() {
        assertEquals("Easy: you can chat in full sentences", GuidedPlan.rpeWords(2..4))
        assertEquals("Hard: 8/10, short sentences", GuidedPlan.rpeWords(8..9))
        assertEquals("Hard: 7-8/10, short sentences", GuidedPlan.rpeWords(7..8))
    }

    @Test fun finisherLinesAreTakenFromAStrengthDetail() {
        val s = session(
            60, Intensity.STRENGTH, 0, 6..8,
            listOf("Warm up 5 minutes.", "Squat.", "Hyrox station finisher (about 10 min) at this phase's ladder level:", "  Sled Push, level 2: 4 x 12 m", "  Wall Balls, level 2: 4 x 10 reps"),
            kind = SessionKind.STRENGTH,
        )
        assertEquals(listOf("Hyrox station finisher (about 10 min) at this phase's ladder level", "Sled Push, level 2: 4 x 12 m", "Wall Balls, level 2: 4 x 10 reps"), GuidedPlan.finisherLines(s))
        assertEquals(emptyList(), GuidedPlan.finisherLines(session(30)))
    }

    // ---------------------------------------------------------------------------------------- run

    @Test fun runCuesAndTotals() {
        val steps = GuidedPlan.from(session(20)).steps
        var run = GuidedRun(steps)
        val events = mutableListOf<GuidedEvent>()
        var t = 0
        while (!run.finished) {
            val (n, e) = run.advance(1000)
            run = n; t++
            if (e != null) events += e
        }
        assertEquals(20 * 60, t)
        assertEquals(steps.size, events.count { it != GuidedEvent.PRE_WARNING } , "one change per step end")
        assertEquals(GuidedEvent.DONE, events.last { it != GuidedEvent.PRE_WARNING })
        assertEquals(steps.size, events.count { it == GuidedEvent.PRE_WARNING })
        assertEquals(1, events.count { it == GuidedEvent.DONE })
        assertEquals(0, run.workSec)
    }

    @Test fun preWarningIsThreeSecondsBeforeTheEndAndPausedTimeDoesNotCount() {
        var run = GuidedRun(GuidedPlan.from(vo2(40)).steps)
        run = run.advance(595_000).first
        assertEquals(null, run.advance(1000).second)
        run = run.advance(1000).first
        val (r2, e) = run.advance(1500) // crosses 597
        assertEquals(GuidedEvent.PRE_WARNING, e)
        run = r2.pause()
        val (r3, e3) = run.advance(60_000)
        assertEquals(run, r3); assertNull(e3)
        run = r3.resume()
        val (_, e4) = run.advance(3000)
        assertEquals(GuidedEvent.CHANGE, e4)
    }

    @Test fun hardTimeIsOnlyCountedInHardStepsAndSkipKeepsWhatWasDone() {
        var run = GuidedRun(GuidedPlan.from(vo2(40)).steps)
        run = run.advance(600_000).first // warm-up done
        assertEquals(0, run.workSec)
        run = run.advance(100_000).first // 100 s into the first hard step
        assertEquals(100, run.workSec)
        run = run.skip().first
        assertEquals(GuidedKind.RECOVERY, run.current!!.kind)
        assertEquals(100, run.workSec)
        run = run.advance(60_000).first
        assertEquals(100, run.workSec)
    }

    // ---------------------------------------------------------------------------------------- record

    @Test fun easyRowIsAHyroxStationRecordClassifiedEasy() {
        val r = GuidedRecord.build("a", date, 45, Modality.ROW, 45 * 60, 0, 8200, 3, Feeling.GOOD)!!
        assertEquals(SessionType.HYROX, r.type)
        assertEquals(45, r.minutesActual)
        assertEquals(1, r.stations.size)
        val st = r.stations.single()
        assertEquals("row", st.stationId); assertEquals(8200, st.distanceM); assertEquals(2700, st.seconds); assertEquals(3, st.rpe)
        assertEquals("Race plan: easy 45 min, rower", r.note)
        assertEquals(TimeSplit(45, 0, 0), IntensityBalance.classify(r))
    }

    @Test fun skiergUsesItsStationId() {
        val r = GuidedRecord.build("a", date, 30, Modality.SKIERG, 1800, 0, null, 4, Feeling.OKAY)!!
        assertEquals("skierg", r.stations.single().stationId)
        assertNull(r.stations.single().distanceM)
    }

    @Test fun intervalSetCountsItsHardMinutesAsHard() {
        val plan = GuidedPlan.from(vo2(40))
        val r = GuidedRecord.build("a", date, 40, Modality.ROW, plan.totalSec, plan.workSec, 9000, 8, Feeling.GOOD)!!
        val split = IntensityBalance.classify(r)
        assertEquals(16, split.hardMin)
        assertEquals(24, split.easyMin)
        assertEquals(0, split.strengthMin)
        assertEquals(2, r.stations.size)
        assertEquals(960, r.stations.last().seconds); assertEquals(8, r.stations.last().rpe)
        assertEquals(9000, r.stations.last().distanceM)
        assertTrue(r.stations.first().rpe!! <= 3)
    }

    @Test fun ratedEasyIntervalsCountAsEasyAndRatedHardEasySessionsAsHard() {
        val plan = GuidedPlan.from(vo2(40))
        val soft = GuidedRecord.build("a", date, 40, Modality.ROW, plan.totalSec, plan.workSec, null, 5, Feeling.GOOD)!!
        assertEquals(0, IntensityBalance.classify(soft).hardMin)
        val hardEasy = GuidedRecord.build("b", date, 30, Modality.ROW, 1800, 0, null, 8, Feeling.GOOD)!!
        assertEquals(30, IntensityBalance.classify(hardEasy).hardMin)
    }

    @Test fun bikeIsALightRecordThatStillClassifies() {
        val easy = GuidedRecord.build("a", date, 40, Modality.BIKE, 2400, 0, 15000, 3, Feeling.GOOD)!!
        assertEquals(SessionType.HIKE, easy.type)
        assertEquals("bike", easy.stations.single().stationId)
        assertTrue(easy.type.countsAsOther)
        assertEquals(TimeSplit(40, 0, 0), IntensityBalance.classify(easy))
        val plan = GuidedPlan.from(vo2(40), Modality.BIKE)
        val hard = GuidedRecord.build("b", date, 40, Modality.BIKE, plan.totalSec, plan.workSec, null, 9, Feeling.GOOD)!!
        assertEquals(16, IntensityBalance.classify(hard).hardMin)
        // A plain hike (no race-plan label) is untouched.
        val hike = SessionRecord("h", date, SessionType.HIKE, 60, 60, Feeling.GOOD)
        assertEquals(TimeSplit(60, 0, 0), IntensityBalance.classify(hike))
    }

    @Test fun underAMinuteIsNotSaved() {
        assertNull(GuidedRecord.build("a", date, 45, Modality.ROW, 59, 0, null, 3, Feeling.GOOD))
        assertNotNull(GuidedRecord.build("a", date, 45, Modality.ROW, 60, 0, null, 3, Feeling.GOOD))
    }

    @Test fun guidedRecordsSurviveThePublicExportWhitelistAndExport() {
        val r = GuidedRecord.build("a", date, 45, Modality.ROW, 2700, 0, 8200, 3, Feeling.GOOD)!!
        val bike = GuidedRecord.build("b", date, 40, Modality.BIKE, 2400, 0, 15000, 3, Feeling.GOOD)!!
        val text = Export.summary(listOf(r, bike), date, date)
        assertTrue("Rowing" in text && "8200 m" in text, text)
        val files = PublicExport.files(listOf(r, bike))
        assertTrue(files.keys.any { it.startsWith("data/hyrox/") })
        assertTrue(files.values.none { "Race plan" in it }, "notes and labels are never exported")
    }

    // ---------------------------------------------------------------------------------------- treadmill

    private fun runWalkSession(minutes: Int, step: String = "1 min run / 2 min walk x 8") = session(
        minutes, detail = listOf("Walk 5 minutes warm-up at 1% incline.", "Run-walk: $step at 1% incline, running at a pace where you could chat (RPE 3-4)."),
        mods = listOf(Modality.RUN_WALK, Modality.TREADMILL_WALK, Modality.BIKE),
    )

    @Test fun runWalkPlanHasRunAndWalkSegmentsAndTheRightTotal() {
        val settings = UserSettings()
        val plan = PlannedTreadmill.build(runWalkSession(45), Modality.RUN_WALK, Calibration(), settings)
        assertEquals(45 * 60, plan.totalSec)
        assertTrue(plan.segments.all { it.seconds >= 60 }, plan.segments.toString())
        assertEquals("Warm-up", plan.segments.first().label)
        val runs = plan.segments.filter { it.label == "Run" }
        val walks = plan.segments.filter { it.label == "Walk" }
        assertEquals(8, runs.size)
        assertEquals(7, walks.size)
        assertTrue(runs.all { it.seconds == 60 && it.speedKmh == settings.hyroxRunSpeedKmh && it.inclinePct == settings.hyroxRunInclinePct })
        assertTrue(walks.all { it.seconds == 120 && it.inclinePct == 1.0 })
        assertEquals("Cool-down", plan.segments.last().label)
        assertTrue(plan.title!!.contains("1 min run / 2 min walk x 8"))
        assertEquals(TreadmillStyle.STEADY, plan.style) // run-walk is easy time
        // Alternates: run, walk, run ...
        val labels = plan.segments.map { it.label }.dropWhile { it == "Warm-up" }
        assertEquals(listOf("Run", "Walk", "Run"), labels.take(3))
    }

    @Test fun runWalkRungsAreReadFromTheDetailAndShortSessionsDropReps() {
        val three = PlannedTreadmill.build(runWalkSession(40, "3 min run / 1 min walk x 6"), Modality.RUN_WALK)
        assertEquals(6, three.segments.count { it.label == "Run" })
        assertTrue(three.segments.filter { it.label == "Run" }.all { it.seconds == 180 })
        assertEquals(40 * 60, three.totalSec)
        val short = PlannedTreadmill.build(runWalkSession(20, "5 min run / 1 min walk x 5"), Modality.RUN_WALK)
        assertEquals(20 * 60, short.totalSec)
        assertTrue(short.segments.count { it.label == "Run" } in 1..3)
        assertTrue(short.segments.all { it.seconds >= 60 })
    }

    @Test fun easyWalkIsASteadyInclineWalkAtTheCalibration() {
        val cal = Calibration(5.2, 9.0)
        val plan = PlannedTreadmill.build(session(40), Modality.TREADMILL_WALK, cal)
        assertEquals(TreadmillStyle.STEADY, plan.style)
        assertEquals(40 * 60, plan.totalSec)
        val main = plan.segments.single { it.label == "Steady" }
        assertEquals(5.2, main.speedKmh); assertEquals(9.0, main.inclinePct)
        val tiny = PlannedTreadmill.build(session(15), Modality.TREADMILL_WALK, cal)
        assertEquals(15 * 60, tiny.totalSec)
    }

    @Test fun treadmillIntervalsKeepTheSetAndWalkOrRun() {
        val cal = Calibration(5.5, 8.0)
        val walk = PlannedTreadmill.build(vo2(40), Modality.TREADMILL_WALK, cal)
        assertEquals(TreadmillStyle.INTERVALS, walk.style)
        assertEquals(40 * 60, walk.totalSec)
        val hard = walk.segments.filter { it.label.startsWith("Hard") }
        assertEquals(4, hard.size)
        assertTrue(hard.all { it.inclinePct == 11.0 && it.speedKmh == 5.5 })
        val run = PlannedTreadmill.build(vo2(40), Modality.TREADMILL_RUN, cal, UserSettings())
        assertTrue(run.segments.filter { it.label.startsWith("Hard") }.all { it.speedKmh == UserSettings().hyroxRunSpeedKmh })
    }

    @Test fun continuousRunUsesTheDetailMinutes() {
        val s = session(
            40, detail = listOf("Warm up 5 minutes walking, then 15 minutes of easy continuous running (RPE 3-4, 1% incline).", "Fill the rest with easy rower, bike or incline walking."),
            mods = listOf(Modality.TREADMILL_RUN, Modality.ROW),
        )
        val plan = PlannedTreadmill.build(s, Modality.TREADMILL_RUN)
        assertEquals(40 * 60, plan.totalSec)
        assertEquals(900, plan.segments.single { it.label == "Run" }.seconds)
    }
}
