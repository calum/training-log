package com.calum.traininglog.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Knee care: the routine, the record, the separate home calf track, and that it never disturbs the plan. */
class KneeCareTest {
    private val mon = LocalDate.of(2026, 11, 23) // week 1 of the default plan
    private val wed = mon.plusDays(2)
    private val thu = mon.plusDays(3)
    private val cfg = RacePlanSettings(sessionsPerWeek = 4, maxWeeklyMinutes = 300)
    private val profile = Profile(settings = UserSettings(racePlan = cfg))
    private var n = 0

    private fun plan(records: List<SessionRecord>, now: LocalDate) = RacePlanner.build(cfg, records, now, profile)

    private fun sets(kg: Double, vararg reps: Int, target: Int = 8) = reps.map { LoggedSet(kg, it, target, null) }

    private fun kneeCare(date: LocalDate, kg: Double = 20.0, reps: IntArray = intArrayOf(8, 8, 8), target: Int = 8, knee: Int? = null, min: Int = 15, feeling: Feeling = Feeling.GOOD) =
        KneeCareRecord.build("k${n++}", date, 15, min * 60, listOf("Hip circles", "Clamshell"), reps.map { LoggedSet(kg, it, target, null) }, feeling, knee)!!

    private fun gym(date: LocalDate, calfKg: Double? = null) = SessionRecord(
        "g${n++}", date, SessionType.GYM, 60, 55, Feeling.GOOD,
        exercises = listOfNotNull(calfKg?.let { ExerciseLog("calf", sets(it, 12, 12, 12, target = 12)) }),
    )

    private fun walk(date: LocalDate, min: Int = 45) = SessionRecord(
        "w${n++}", date, SessionType.TREADMILL, min, min, Feeling.GOOD, treadmill = TreadmillLog(TreadmillStyle.STEADY, min * 60, min * 60, distanceM = min * 85),
    )

    // ------------------------------------------------------------------------------------------------ routine

    @Test
    fun totalsMatchTheMinutesExactlyAndEveryStepHasLengthAndCue() {
        for (mode in CalfMode.entries) for (m in 12..20) for (seed in 0L..5L) for (knee in listOf(null, 2, 5, 8)) {
            val s = KneeCareRoutine.build(m, seed = seed, kneePain = knee, calfMode = mode)
            // Exactly the minutes asked for, unless the calf block leaves the hips too little room (then whole minutes longer).
            assertTrue(s.totalSec >= m * 60 && s.totalSec == s.minutes * 60, "m=$m seed=$seed knee=$knee mode=$mode total=${s.totalSec}")
            if (m >= 15) assertEquals(m * 60, s.totalSec, "m=$m seed=$seed knee=$knee mode=$mode")
            assertEquals(s.totalSec, s.steps.sumOf { it.seconds })
            for (st in s.steps) {
                assertTrue(st.seconds >= 5, "${st.name} ${st.seconds}")
                if (st.kind == CoreKind.WORK && !KneeCareRoutine.isRepStep(st)) assertTrue(st.seconds in 25..100, "${st.name} ${st.seconds}")
                assertTrue(st.name.isNotBlank() && st.cue.isNotBlank(), "empty text in ${st.name}")
                assertTrue(st.cue.length <= 120, "cue too long (${st.cue.length}): ${st.cue}")
                assertFalse(st.name.contains(','), "commas break the note: ${st.name}")
            }
            assertNull(s.steps.last().nextName)
            s.steps.dropLast(1).forEach { assertNotNull(it.nextName, "no next for ${it.name}") }
        }
    }

    @Test
    fun fifteenMinutesHasAboutNineToTenExercisesInTheRightOrder() {
        val s = KneeCareRoutine.build(15, seed = 1)
        assertEquals(15 * 60, s.totalSec)
        assertTrue(s.names.size in 8..11, "exercises: ${s.names}")
        assertEquals(CoreKind.WARMUP, s.steps.first().kind)
        assertEquals(CoreKind.COOLDOWN, s.steps.last().kind)
        val firstCalf = s.steps.indexOfFirst { it.exerciseId == ExerciseLibrary.CALF_HOME_ID }
        val lastHip = s.steps.indexOfLast { it.exerciseId?.startsWith("kc_") == true && it.kind == CoreKind.WORK }
        val firstCool = s.steps.indexOfFirst { it.kind == CoreKind.COOLDOWN }
        assertTrue(lastHip < firstCalf && firstCalf < firstCool)
        // Warm-up first, a hip abductor block, 3 or 4 calf sets, then stretches.
        assertEquals(setOf("Hip circles", "Leg swings"), s.steps.filter { it.kind == CoreKind.WARMUP }.map { it.name }.toSet())
        assertEquals(3, s.steps.count { it.kind == CoreKind.WORK && it.exerciseId == ExerciseLibrary.CALF_HOME_ID })
        assertTrue(s.steps.any { it.name == "Side-lying hip abduction" || it.name == "Side plank" })
        assertTrue(s.steps.any { it.exerciseId == "kc_hip_flexor" } && s.steps.any { it.exerciseId == "kc_figure4" })
        // Unilateral moves get both sides; nothing jumps.
        s.steps.filter { it.kind != CoreKind.REST && KneeCareLibrary.get(it.exerciseId!!)?.unilateral == true }.groupBy { it.exerciseId }.values
            .forEach { v -> assertEquals(v.count { it.side == CoreSide.LEFT }, v.count { it.side == CoreSide.RIGHT }) }
        assertTrue(s.steps.none { Regex("jump|hop|bound|skip", RegexOption.IGNORE_CASE).containsMatchIn(it.name + it.cue) })
    }

    @Test
    fun consecutiveSessionsDiffer() {
        var last: List<String>? = null
        val seen = mutableListOf<KneeCareSession>()
        repeat(7) {
            val s = KneeCareRoutine.build(15, last = last)
            seen.lastOrNull()?.let { prev ->
                assertNotEquals(prev.names, s.names, "same routine twice in a row")
                assertNotEquals(prev.calfVariant, s.calfVariant, "calf variant must alternate")
                assertNotEquals(prev.variant, s.variant)
            }
            seen += s
            last = s.exerciseIds
        }
        assertEquals(3, seen.map { it.variant }.toSet().size)
        assertEquals(setOf(CalfVariant.STRAIGHT, CalfVariant.BENT), seen.mapNotNull { it.calfVariant }.toSet())
        // Different mix (a new seed) changes the routine too.
        assertNotEquals(KneeCareRoutine.build(15, seed = 0).names, KneeCareRoutine.build(15, seed = 1).names)
        // Deterministic.
        assertEquals(KneeCareRoutine.build(15, seed = 4), KneeCareRoutine.build(15, seed = 4))
    }

    @Test
    fun theLastNoteGivesTheSameIdsBack() {
        val s = KneeCareRoutine.build(15, seed = 2)
        val rec = KneeCareRecord.build("x", mon, 15, 900, s.names, emptyList())!!
        val ids = KneeCareLibrary.idsFromNote(rec.note)
        assertTrue(ids.containsAll(s.exerciseIds.filter { it != ExerciseLibrary.CALF_HOME_ID }), "ids $ids vs ${s.exerciseIds}")
        val next = KneeCareRoutine.build(15, last = ids)
        assertNotEquals(s.variant, next.variant)
        assertNotEquals(s.calfVariant, next.calfVariant)
    }

    @Test
    fun kneeScoreGatesTheRoutine() {
        val ok = KneeCareRoutine.build(15, kneePain = 4)
        assertEquals(KneeGate.FULL, ok.gate)
        assertNull(ok.physioLine)
        for (v in 0L..2L) {
            val full = KneeCareRoutine.build(15, seed = v)
            val five = KneeCareRoutine.build(15, seed = v, kneePain = 5)
            assertEquals(KneeGate.NO_STEP_DOWN, five.gate)
            assertTrue(five.steps.none { it.exerciseId == "kc_step_down" }, "step-down must go at 5+")
            assertNotNull(five.physioLine)
            assertTrue(five.physioLine!!.contains("physio"))
            assertEquals(900, five.totalSec)
            assertTrue(five.steps.any { it.exerciseId == ExerciseLibrary.CALF_HOME_ID }, "calf work stays at 5-6")
            assertTrue(full.steps.size > 5)
        }
        // With a step, at least one variant includes the step-down; without one, never.
        val withStep = KneeCareKit(hasStep = true)
        assertTrue((0L..2L).any { v -> KneeCareRoutine.build(15, seed = v, kit = withStep).steps.any { it.exerciseId == "kc_step_down" } })
        assertTrue((0L..2L).all { v -> KneeCareRoutine.build(15, seed = v, kit = withStep, kneePain = 5).steps.none { it.exerciseId == "kc_step_down" || it.exerciseId == "kc_pistol" } })
        val gentle = KneeCareRoutine.build(15, kneePain = 7)
        assertEquals(KneeGate.GENTLE_ONLY, gentle.gate)
        assertNotNull(gentle.physioLine)
        assertEquals(900, gentle.totalSec)
        assertTrue(gentle.steps.none { it.kind == CoreKind.WORK }, "gentle mobility only")
        assertEquals(0, gentle.calfSets)
        assertEquals(KneeGate.GENTLE_ONLY, KneeCareRoutine.build(15, kneePain = 10).gate)
    }

    @Test
    fun calfBlockIsRepBasedAndFollowsTheLadderLevel() {
        val heavy = KneeCareRoutine.build(15, calfStartKg = 6.0, calfLevel = 4)
        assertEquals(CalfMode.HEAVY, heavy.calfMode)
        val calf = heavy.steps.filter { it.kind == CoreKind.WORK && it.exerciseId == ExerciseLibrary.CALF_HOME_ID }
        assertEquals(3, calf.size)
        assertTrue(calf.all { it.reps == 10 && KneeCareRoutine.isRepStep(it) && it.level == 4 })
        assertEquals(6.0, heavy.calfStartKg)
        assertEquals(listOf(0, 1, 2), calf.map { heavy.calfSetAt(heavy.steps.indexOf(it)) }.map { it!! })
        val stamina = KneeCareRoutine.build(15, calfMode = CalfMode.STAMINA, calfLevel = 2)
        assertEquals(3, stamina.steps.count { it.kind == CoreKind.WORK && it.exerciseId == ExerciseLibrary.CALF_HOME_ID })
        assertEquals(15 + CalfLadder.STAMINA_EXTRA, stamina.calfReps, "higher reps sit above the level's top")
        assertEquals(3, KneeCareRoutine.build(18).calfSets)
        // Hip steps stay timed.
        assertTrue(heavy.steps.filter { it.exerciseId?.startsWith("kc_") == true }.none { KneeCareRoutine.isRepStep(it) })
    }

    @Test
    fun justCalfRaisesHasAWarmUpSetAndGrowsWithTheLevel() {
        val s = KneeCareRoutine.buildCalfOnly(calfLevel = 1)
        assertTrue(s.calfOnly)
        assertTrue(s.steps.none { it.exerciseId?.startsWith("kc_") == true && it.kind != CoreKind.WORK || (it.kind == CoreKind.COOLDOWN) })
        val repSteps = s.steps.filter { KneeCareRoutine.isRepStep(it) }
        assertEquals(KneeCareRoutine.CALF_WARMUP_ID, repSteps.first().exerciseId)
        assertEquals(3, repSteps.count { it.exerciseId == ExerciseLibrary.CALF_HOME_ID })
        assertTrue(s.totalSec in 360..540, "total ${s.totalSec}")
        assertTrue(KneeCareRoutine.buildCalfOnly(calfLevel = 4).totalSec > s.totalSec)
        // The warm-up set is not a working set.
        assertNull(s.calfSetAt(s.steps.indexOf(repSteps.first())))
    }

    // ------------------------------------------------------------------------------------- calf_home track

    @Test
    fun homeCalfHasItsOwnTrackAndNeverTouchesTheGymCalf() {
        val gymOnly = listOf(gym(mon.minusDays(30), calfKg = 60.0))
        val gymPlan = GymBuilder.plan(ExerciseLibrary.calf, gymOnly, wed, deload = false)
        val withHome = gymOnly + kneeCare(mon.minusDays(20), kg = 6.0, reps = intArrayOf(8, 8, 8)) + kneeCare(mon.minusDays(10), kg = 6.0, reps = intArrayOf(9, 8, 8))
        val gymPlanAfter = GymBuilder.plan(ExerciseLibrary.calf, withHome, wed, deload = false)
        assertEquals(gymPlan.prescription, gymPlanAfter.prescription, "a light home set must not change the gym calf")
        assertEquals(1, GymBuilder.history("calf", withHome).size)
        assertEquals(2, GymBuilder.history(ExerciseLibrary.CALF_HOME_ID, withHome).size)
        // The gym calf is only read for the start level of a new home user.
        assertEquals(3, KneeCare.calfPlan(gymOnly).level)
        assertEquals("Calf raise (home)", ExerciseLibrary.nameOf(ExerciseLibrary.CALF_HOME_ID))
    }

    @Test
    fun lastModeAndLastTimeKeepTheModesApart() {
        val both = listOf(
            ladder(mon.minusDays(8), 3, 6.0, intArrayOf(12, 12, 12), target = 12),
            ladder(mon.minusDays(3), 3, 6.0, intArrayOf(18, 17, 17), target = 18),
        )
        assertEquals(CalfMode.STAMINA, KneeCare.lastMode(both))
        assertEquals("Last time (level 3): 6 kg x 12, 12, 12", KneeCare.lastTime(both, CalfMode.HEAVY))
        assertEquals("Last time (level 3): 6 kg x 18, 17, 17", KneeCare.lastTime(both, CalfMode.STAMINA))
        assertNull(KneeCare.lastTime(emptyList(), CalfMode.HEAVY))
        assertEquals(CalfMode.HEAVY, KneeCare.lastMode(emptyList()))
        // Older logs (no level) keep the old rule.
        assertEquals(CalfMode.STAMINA, KneeCare.lastMode(listOf(kneeCare(mon, kg = 8.0, reps = intArrayOf(13, 13, 12), target = 12))))
    }

    private fun ladder(date: LocalDate, level: Int, kg: Double, reps: IntArray, target: Int, rir: Int? = 2) =
        KneeCareRecord.build("l${n++}", date, 15, 900, listOf("Hip circles"), reps.map { LoggedSet(kg, it, target, rir, level) })!!

    @Test
    fun recordShapeAndMinimum() {
        val r = KneeCareRecord.build("a", wed, 15, 14 * 60 + 40, listOf("Hip circles", "Calf raise (straight knee)"), sets(20.0, 8, 8, 7), Feeling.OKAY, 2, "just right")!!
        assertEquals(SessionType.CORE, r.type)
        assertEquals("Knee care", r.dayLabel)
        assertTrue(r.isKneeCare)
        assertEquals(15, r.minutesActual)
        assertEquals(2, r.kneePain)
        assertEquals(1, r.exercises.size)
        assertEquals(ExerciseLibrary.CALF_HOME_ID, r.exercises[0].exerciseId)
        assertNull(r.exercises[0].customName)
        assertEquals("Knee care: Hip circles, Calf raise (straight knee) (felt just right)", r.note)
        assertNull(KneeCareRecord.build("b", wed, 15, 20, emptyList(), emptyList()), "under a minute logs nothing")
        assertTrue(KneeCareRecord.build("c", wed, 15, 600, emptyList(), emptyList())!!.exercises.isEmpty())
    }

    // ----------------------------------------------------------------------- stats, balance, health, rest days

    @Test
    fun countsAsOtherNotGymOrTreadmill() {
        val w = Stats.week(listOf(kneeCare(wed), gym(wed), walk(thu)), wed)
        assertEquals(1, w.gym)
        assertEquals(1, w.treadmill)
        assertEquals(1, w.other)
        val view = WeekPlanner.plan(listOf(kneeCare(wed)), profile, wed)
        assertEquals(0, view.gymDone)
        assertEquals(0, view.treadmillDone)
        assertEquals(0, view.goodWeekDone)
        assertEquals(0, Export.summary(listOf(kneeCare(wed)), mon, mon.plusDays(6)).let { Regex("gym [1-9]").findAll(it).count() })
    }

    @Test
    fun kneeCareOnARestDayDoesNotBreakRestDayLogic() {
        val tue = mon.plusDays(1) // a default rest day
        val without = WeekPlanner.plan(emptyList(), profile.copy(), wed)
        val with = WeekPlanner.plan(listOf(kneeCare(tue)), profile, wed)
        assertEquals(DayStatus.REST, with.days.first { it.date == tue }.status, "still a plain rest day, not 'rest but trained'")
        assertEquals(without.days.map { it.status }, with.days.map { it.status })
        assertEquals(without.openDaysLeft, with.openDaysLeft)
        assertTrue(RestDays.isRestDay(profile, tue))
        // The reminder still thinks today is empty.
        val p = profile.copy(settings = profile.settings.copy(reminderEnabled = true, reminderDays = setOf(thu.dayOfWeek)))
        assertEquals(Reminder.shouldNudge(emptyList(), p, thu, 0), Reminder.shouldNudge(listOf(kneeCare(thu)), p, thu, 0))
        // A real workout on the same rest day does show.
        assertEquals(DayStatus.REST_BUT_TRAINED, WeekPlanner.plan(listOf(gym(tue)), profile, wed).days.first { it.date == tue }.status)
    }

    @Test
    fun balanceAndPlanAreNotDisturbed() {
        val base = listOf(walk(mon, 45), walk(wed, 45), gym(mon.minusDays(2)))
        val withKc = base + kneeCare(mon) + kneeCare(wed, knee = null) + kneeCare(thu)
        val b1 = IntensityBalance.of(base, mon)
        val b2 = IntensityBalance.of(withKc, mon)
        assertEquals(b1.week.hardMin, b2.week.hardMin)
        assertEquals(b1.week.easyMin, b2.week.easyMin)
        assertEquals(b1.weekStatus, b2.weekStatus)
        // The plan: same sessions and minutes, same baseline, same run stage.
        val records = base + (1..4).map { walk(mon.minusDays(it * 3L)) }
        val p1 = plan(records, thu)
        val p2 = plan(records + kneeCare(wed) + kneeCare(mon.minusDays(2)) + kneeCare(mon.minusDays(9)), thu)
        assertEquals(p1.baselineMinutes, p2.baselineMinutes)
        assertEquals(p1.peakMinutes, p2.peakMinutes)
        assertEquals(p1.runStatus.stage, p2.runStatus.stage)
        assertEquals(p1.weeks.map { it.plannedMinutes }, p2.weeks.map { it.plannedMinutes })
        assertEquals(p1.weeks.map { w -> w.sessions.map { it.kind to it.date } }, p2.weeks.map { w -> w.sessions.map { it.kind to it.date } })
        assertEquals(RacePlanner.weekProgress(p1, records, thu), RacePlanner.weekProgress(p2, records + kneeCare(wed), thu))
        assertEquals(RacePlanner.doneSessions(p1.weeks[0], records, thu), RacePlanner.doneSessions(p2.weeks[0], records + kneeCare(wed), thu))
    }

    @Test
    fun aRoughFeelingOnKneeCareDoesNotDropTheKeySession() {
        val records = listOf(walk(mon.minusDays(5)))
        val rough = kneeCare(wed, feeling = Feeling.ROUGH)
        val keyDays = { p: RacePlan -> p.weeks[0].sessions.filter { it.kind == SessionKind.KEY } }
        assertEquals(keyDays(plan(records, thu)).size, keyDays(plan(records + rough, thu)).size)
    }

    @Test
    fun skippingKneeCareNeverReachesPlanHealth() {
        // Nothing records a knee care skip, and health is identical with or without any knee care sessions.
        val records = (1..4).map { walk(mon.minusDays(it * 3L)) } + gym(mon.minusDays(2))
        val p = plan(records, thu)
        val h1 = PlanHealth.assess(p, records, profile, thu)
        val h2 = PlanHealth.assess(p, records + kneeCare(wed), profile, thu)
        assertEquals(h1, h2)
        val quiet = PlanHealth.assess(plan(emptyList(), mon.plusDays(20)), emptyList(), profile, mon.plusDays(20))
        val quietWith = PlanHealth.assess(plan(listOf(kneeCare(mon.plusDays(10))), mon.plusDays(20)), listOf(kneeCare(mon.plusDays(10))), profile, mon.plusDays(20))
        assertEquals(quiet.level, quietWith.level, "knee care minutes must not rescue (or cause) a quiet week")
    }

    // ------------------------------------------------------------------------------------------- plan status

    private fun status(records: List<SessionRecord>, now: LocalDate): KneeCareStatus = RacePlanner.thisWeek(plan(records, now), now)!!.kneeCare

    @Test
    fun statusCountsGymCalfAndKneeCareAndNeverCapsAtTwo() {
        val s0 = status(emptyList(), wed)
        assertEquals(2, s0.target)
        assertEquals(0, s0.done)
        assertEquals(0, s0.calfExposures)
        assertEquals("Knee care 0 of 2 this week", s0.label)
        val s = status(listOf(kneeCare(mon), gym(mon, calfKg = 60.0)), wed)
        assertEquals(1, s.done)
        assertEquals(1, s.gymCalf)
        assertEquals(2, s.calfExposures)
        // A gym session without calf sets is not a calf exposure.
        assertEquals(0, status(listOf(gym(mon)), wed).calfExposures)
        val many = status(listOf(kneeCare(mon), kneeCare(mon), kneeCare(wed), kneeCare(wed)), wed)
        assertEquals(4, many.done)
        assertTrue(many.complete)
        assertEquals("Knee care 4 this week", many.label)
        // Allowed on a rest day and after another session the same day.
        assertEquals(1, status(listOf(kneeCare(mon.plusDays(1))), wed).done)
    }

    @Test
    fun nudgeAppearsFromThursdayOnlyWhenCalfExposuresAreUnderTwo() {
        val tue = mon.plusDays(1)
        assertNull(status(emptyList(), mon).note)
        assertNull(status(emptyList(), tue).note)
        assertNull(status(emptyList(), wed).note)
        for (d in 3L..6L) {
            val note = status(emptyList(), mon.plusDays(d)).note
            assertEquals(KneeCare.NUDGE, note, "day $d")
            assertTrue(note!!.startsWith("Calf and hip work: do one knee care session this week"))
            assertFalse(Regex("miss|behind|fail|warning|!", RegexOption.IGNORE_CASE).containsMatchIn(note))
        }
        // One gym calf is still under 2: nudge. Two gym calf sessions: no nudge.
        assertEquals(KneeCare.NUDGE, status(listOf(gym(mon, calfKg = 60.0)), thu).note)
        assertNull(status(listOf(gym(mon, calfKg = 60.0), gym(wed, calfKg = 60.0)), thu).note)
        // Gym calf plus one knee care add-on also covers it.
        assertNull(status(listOf(gym(mon, calfKg = 60.0), kneeCare(wed)), thu).note)
        // Two add-ons: a calm done line.
        assertEquals(KneeCare.DONE_NOTE, status(listOf(kneeCare(mon), kneeCare(wed)), thu).note)
        // Past and future weeks carry no nudge.
        val p = plan(emptyList(), thu)
        assertTrue(p.weeks.filter { it.index != 1 }.all { it.kneeCare.note == null || it.phase == RacePhase.RACE })
    }

    @Test
    fun raceWeekOnlySaysItKeepsThingsLoose() {
        val raceMon = LocalDate.of(2027, 2, 22)
        for (d in 0L..5L) {
            val st = status(emptyList(), raceMon.plusDays(d))
            assertEquals(KneeCare.RACE_WEEK_NOTE, st.note)
        }
        val race = plan(emptyList(), raceMon.plusDays(4)).weeks.last()
        assertEquals(RacePhase.RACE, race.phase)
        assertEquals("Knee care keeps things loose.", race.kneeCare.note)
        // Taper week still gets the (light) nudge like any other week.
        assertEquals(KneeCare.NUDGE, status(emptyList(), LocalDate.of(2027, 2, 18)).note)
    }

    @Test
    fun launchSupportOpensKneeCare() {
        val s = KneeCare.session(wed)
        assertTrue(s.launch.kneeCare)
        assertEquals("Knee care", s.title)
        assertEquals(15, s.minutes)
        // Never in a week's sessions or planned minutes.
        val p = plan(emptyList(), wed)
        assertTrue(p.weeks.flatMap { it.sessions }.none { it.launch.kneeCare })
    }

    // ---------------------------------------------------------------------------------- rewards and exports

    @Test
    fun smallXpAndABadgeAtTheEnd() {
        val kc = kneeCare(wed)
        val xp = Rewards.sessionXp(kc, emptyList())
        assertTrue(xp.total in 20..45, "small XP, was ${xp.total}")
        assertEquals(0, xp.pr, "home calf work does not earn PRs")
        val core = SessionRecord("c", wed, SessionType.CORE, 15, 15, Feeling.GOOD)
        assertTrue(xp.total < Rewards.sessionXp(core, emptyList()).total)
        val ten = (1..10).map { kneeCare(mon.minusDays(it * 2L)) }
        val s = Rewards.summary(ten, wed)
        assertEquals("knee_care_10", s.badges.last().id)
        assertEquals("Knee care x 10", s.badges.last().title)
        assertTrue(s.badges.last().unlocked)
        assertFalse(Rewards.summary(ten.take(9), wed).badges.last().unlocked)
        // Knee care does not unlock the session-count badges, and is not a counted session.
        assertFalse(s.badges.first { it.id == "first_session" }.unlocked)
        assertFalse(s.badges.first { it.id == "sessions_10" }.unlocked)
        assertEquals(0, s.totalSessions)
        assertEquals(Rewards.summary(emptyList(), wed).badges.size, s.badges.size)
    }

    @Test
    fun exportShowsKneeCareAndPublicExportStaysPrivate() {
        val kc = kneeCare(wed, kg = 22.5, reps = intArrayOf(8, 8, 7))
        val text = Export.summary(listOf(kc), mon, mon.plusDays(6))
        assertTrue(text.contains("Knee care"), text)
        assertTrue(text.contains("Calf raise (home) 22.5kg x 8/8/7"), text)
        assertFalse(text.contains("MISSED"), "no guilt flags on home calf work")
        assertTrue(text.contains("other 1"))
        val files = PublicExport.files(listOf(kc, gym(wed, calfKg = 60.0)))
        val all = files.values.joinToString("\n")
        assertFalse(all.contains("Knee"), "the note must not leak")
        assertFalse(all.contains("calf_home"))
        assertFalse(files.keys.any { it.contains("other") }, "knee care is not published at all")
        assertTrue(all.contains("calf"), "the gym calf is exported as before")
    }
}
