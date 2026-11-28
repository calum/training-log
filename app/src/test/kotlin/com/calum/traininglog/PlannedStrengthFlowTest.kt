package com.calum.traininglog

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.ExerciseLog
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.GuidedPlan
import com.calum.traininglog.core.HyroxStation
import com.calum.traininglog.core.Intensity
import com.calum.traininglog.core.KneeCare
import com.calum.traininglog.core.KneeCareRoutine
import com.calum.traininglog.core.LaunchHint
import com.calum.traininglog.core.LoggedSet
import com.calum.traininglog.core.Modality
import com.calum.traininglog.core.PlannedSession
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.RacePlanSettings
import com.calum.traininglog.core.RacePlanner
import com.calum.traininglog.core.SessionKind
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.Stats
import com.calum.traininglog.core.TreadmillLog
import com.calum.traininglog.core.TreadmillStyle
import com.calum.traininglog.core.UserSettings
import com.calum.traininglog.core.isKneeCare
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Planned strength end to end (Calum's real history): the lifting window, what is built, the hip prehab step and what is saved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlannedStrengthFlowTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>() as Application
    private val today = LocalDate.of(2026, 11, 20) // a Friday
    private val cfg = RacePlanSettings(sessionsPerWeek = 4, maxWeeklyMinutes = 300)
    private val profile = Profile(settings = UserSettings(racePlan = cfg), restWeekdays = emptySet())
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun onDisk() = Store(app).load()

    @Before fun setUp() { TreadmillEngine.stop() }
    @After fun tearDown() { TreadmillEngine.stop() }

    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)
    private fun sets(kg: Double, n: Int, reps: Int) = List(n) { LoggedSet(kg, reps, reps, 2) }
    private fun gym(date: LocalDate, min: Int, vararg logs: ExerciseLog) =
        SessionRecord("h-$date", date, SessionType.GYM, min, min, Feeling.GOOD, exercises = logs.toList())
    private fun walk(date: LocalDate, min: Int) = SessionRecord(
        "w-$date", date, SessionType.TREADMILL, min, min, Feeling.GOOD,
        treadmill = TreadmillLog(TreadmillStyle.STEADY, min * 60, min * 60, distanceM = min * 85),
    )

    /** His real gym sessions (the last hinge was the deadlift on 16 Nov, so the RDL is next) plus the walks around them. */
    private fun history() = listOf(
        walk(d(10, 30), 30),
        gym(d(11, 1), 23, ExerciseLog("squat", sets(72.5, 3, 5)), ExerciseLog("rdl", sets(62.5, 2, 8)), ExerciseLog("calf", sets(60.0, 3, 12)),
            ExerciseLog("lat_pulldown", sets(40.0, 3, 10)), ExerciseLog("cable_row", sets(40.0, 3, 10))),
        walk(d(11, 2), 30),
        gym(d(11, 5), 27, ExerciseLog("squat", sets(72.5, 3, 5)), ExerciseLog("deadlift", sets(90.0, 2, 3)), ExerciseLog("calf", sets(62.5, 3, 12))),
        walk(d(11, 6), 45), walk(d(11, 8), 30),
        gym(d(11, 12), 37, ExerciseLog("squat", sets(75.0, 3, 5)), ExerciseLog("rdl", sets(65.0, 2, 8)), ExerciseLog("calf", sets(65.0, 3, 12)),
            ExerciseLog("machine_chest", sets(40.0, 3, 10)), ExerciseLog("db_shoulder", sets(12.5, 3, 10))),
        walk(d(11, 15), 20),
        gym(d(11, 16), 40, ExerciseLog("squat", sets(75.0, 3, 5)), ExerciseLog("deadlift", sets(95.0, 2, 3)), ExerciseLog("calf", sets(65.0, 3, 12)),
            ExerciseLog("lat_pulldown", sets(42.5, 3, 10)), ExerciseLog("cable_row", sets(42.5, 3, 10))),
        walk(d(11, 18), 30), walk(d(11, 19), 30),
    )

    private fun newVm(day: LocalDate = today): AppViewModel {
        Fixtures.seed(app, history(), profile)
        return AppViewModel(app, clock = { day })
    }

    private fun planFor(records: List<SessionRecord>, now: LocalDate = today) = RacePlanner.build(cfg, records, now, profile)
    private fun kickoff(): PlannedSession = assertNotNull(planFor(history()).leadIn).sessions.first { it.kind == SessionKind.STRENGTH }

    private fun AppViewModel.doAllSets() {
        val g = gym.value!!
        g.exercises.forEachIndexed { ei, e -> e.sets.indices.forEach { si -> doneSet(ei, si) } }
    }

    private fun AppViewModel.runKneeCareToEnd() {
        var n = 0
        while (kneeCare.ui.value.phase == KneeCarePhase.RUNNING) {
            val u = kneeCare.ui.value
            if (u.session!!.isRepStep(u.run!!.index)) kneeCare.next() else kneeCare.tick(1000)
            if (n++ > 20_000) error("never finished")
        }
        idle()
    }

    @Test fun theKickOffSessionBuildsSquatRdlAndCalfInAnHonestWindowThenOffersTheHipPrehab() {
        val vm = newVm()
        val s = kickoff()
        assertEquals(10, s.launch.prehabMinutes)
        assertTrue(s.minutes in 30..40, "${s.minutes}")
        // The card says what the app will do, not a list it may not build.
        val lifting = s.detail.first { it.startsWith("Lifting (about") }
        assertTrue("the app builds it from your history" in lifting, lifting)
        assertTrue(s.detail.any { it.startsWith("Hip-abductor and glute prehab (about 10 min)") })

        vm.launchPlanned(s)
        assertEquals(Screen.Gym, vm.screen.value)
        val g = vm.gym.value!!
        assertEquals(s.minutes - 10, g.windowMinutes, "no gym travel comes off a planned session")
        assertEquals(listOf("squat", "rdl", "calf"), g.plan.exercises.map { it.exercise.id }.take(3), "the deadlift was last, so the RDL is next")
        assertTrue(g.plan.estimatedMinutes <= g.windowMinutes + com.calum.traininglog.core.GymBuilder.PROTECT_TOLERANCE_MIN, "estimate within the squat-protecting tolerance")
        assertEquals(10, g.prehabMinutes)
        assertEquals(35, s.minutes); assertEquals(25, g.windowMinutes)
        // Trim priority: hinge, then calf, then the squat warm-ups; the squat keeps its 3 sets (the RDL is a 2-set lift anyway; the calf keeps 3 within the tolerance).
        assertEquals(listOf(3, 2, 3), g.plan.exercises.take(3).map { it.prescription.sets })

        // Finish the lifts: they are saved first and the prehab prompt follows.
        vm.doAllSets()
        vm.finishGym()
        idle()
        assertTrue(vm.gym.value!!.prehabOffered)
        assertEquals(Screen.Gym, vm.screen.value)
        assertEquals(history().size + 1, onDisk().records.size)
        assertEquals(SessionType.GYM, onDisk().records.last().type)
        vm.finishGym() // a second tap must not save again
        assertEquals(history().size + 1, onDisk().records.size)

        // Start: the hip-only 10 minute Knee care routine, with the checklist and Get ready screen.
        vm.startPrehab()
        assertNull(vm.gym.value)
        assertEquals(Screen.KneeCare, vm.screen.value)
        val ui = vm.kneeCare.ui.value
        assertTrue(ui.hipOnly)
        assertEquals(KneePhaseSetup, ui.phase)
        val session = ui.session!!
        assertTrue(session.hipOnly)
        assertEquals(600, session.totalSec)
        assertEquals(0, session.calfSets)
        assertTrue(session.gear.isNotEmpty(), "the You'll need checklist")
        assertTrue(session.steps.none { it.exerciseId == "calf_home" || it.exerciseId == KneeCareRoutine.CALF_WARMUP_ID })
        vm.kneeCare.start()
        assertEquals(KneeCarePhase.GET_READY, vm.kneeCare.ui.value.phase)
        vm.kneeCare.ready()
        vm.runKneeCareToEnd()
        assertEquals(KneeCarePhase.DONE, vm.kneeCare.ui.value.phase)
        // Autosaved the moment it ended: one Knee care record (CORE) next to the gym record.
        val records = onDisk().records
        assertEquals(history().size + 2, records.size)
        val kc = records.last()
        assertEquals(SessionType.CORE, kc.type)
        assertEquals("Knee care", kc.dayLabel)
        assertTrue(kc.isKneeCare)
        assertEquals(1, records.count { it.type == SessionType.GYM && it.date == today })
        vm.saveKneeCare()
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(history().size + 2, onDisk().records.size, "the finish card edits, never duplicates")

        // The plan says today is covered (a session logged today counts for today) and the Knee care strip counts the prehab.
        val week = assertNotNull(planFor(onDisk().records).leadIn)
        assertTrue(week.adjustment.orEmpty().contains("Nothing more needed today"), week.adjustment)
        assertEquals(1, week.kneeCare?.done)
        val w = Stats.weekOf(today)
        assertEquals(1, KneeCare.status(w.start, w.endInclusive, onDisk().records, today, false).done)
    }

    private val KneePhaseSetup = KneeCarePhase.SETUP

    @Test fun skippingThePrehabCostsNothing() {
        val vm = newVm()
        vm.launchPlanned(kickoff())
        vm.doAllSets()
        vm.finishGym()
        idle()
        assertTrue(vm.gym.value!!.prehabOffered)
        vm.skipPrehab()
        assertNull(vm.gym.value)
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(history().size + 1, onDisk().records.size, "only the gym session is saved")
        assertTrue(onDisk().records.none { it.isKneeCare })
    }

    @Test fun backingOutOfThePrehabSetupGoesHomeWithNothingSaved() {
        val vm = newVm()
        vm.launchPlanned(kickoff())
        vm.doAllSets(); vm.finishGym(); idle()
        vm.startPrehab()
        vm.kneeCareBack()
        assertEquals(Screen.Home, vm.screen.value)
        assertTrue(onDisk().records.none { it.isKneeCare })
    }

    @Test fun aStrengthBInWeekTwoBuildsAndOffersThePrehabToo() {
        val b = planFor(history()).weeks[1].sessions.last { it.kind == SessionKind.STRENGTH }
        val vm = newVm(b.date)
        val plan = planFor(history(), b.date)
        assertTrue(b.title.startsWith("Strength B"), b.title)
        assertTrue(b.minutes in 30..45, "${b.minutes}")
        vm.launchPlanned(b)
        val g = vm.gym.value!!
        assertEquals(GuidedPlan.liftingWindow(b), g.windowMinutes)
        assertTrue(g.windowMinutes >= 20)
        assertEquals(listOf("squat", "rdl", "calf"), g.plan.exercises.map { it.exercise.id }.take(3))
        assertTrue(g.plan.estimatedMinutes <= g.windowMinutes + com.calum.traininglog.core.GymBuilder.PROTECT_TOLERANCE_MIN, "estimate within the squat-protecting tolerance")
        assertEquals(10, g.prehabMinutes)
        vm.doAllSets(); vm.finishGym(); idle()
        assertTrue(vm.gym.value!!.prehabOffered)
        vm.startPrehab()
        assertEquals(Screen.KneeCare, vm.screen.value)
        assertTrue(vm.kneeCare.ui.value.hipOnly)
        vm.kneeCare.start(); vm.kneeCare.ready(); vm.runKneeCareToEnd(); vm.saveKneeCare()
        // The plan shows the week's strength session done and the Knee care strip counts the prehab.
        val after = onDisk().records
        val week = plan.weeks[1]
        val planB = week.sessions.first { it.kind == SessionKind.STRENGTH && it.title == b.title }
        assertTrue(planB in RacePlanner.doneSessions(week, after, b.date), "strength counted as done")
        assertEquals(1, planFor(after, b.date).weekOf(b.date)?.kneeCare?.done)
    }

    @Test fun aSessionWithoutPrehabLaunchesWithoutThePrompt() {
        val vm = newVm()
        val plain = PlannedSession(
            today, SessionKind.STRENGTH, "Strength", 40, Intensity.STRENGTH, 0, 6..8, listOf(Modality.GYM),
            listOf("Lifting."), "Why.", LaunchHint(SessionType.GYM, minutes = 40),
        )
        vm.launchPlanned(plain)
        val g = vm.gym.value!!
        assertEquals(0, g.prehabMinutes)
        assertEquals(40, g.windowMinutes, "no prehab, no finisher, no travel: the whole session is lifting")
        vm.doAllSets(); vm.finishGym(); idle()
        assertNull(vm.gym.value)
        assertEquals(Screen.Home, vm.screen.value)
        assertEquals(history().size + 1, onDisk().records.size)
    }

    @Test fun prehabThenTheStationFinisherWhenBothArePlanned() {
        val vm = newVm()
        val s = PlannedSession(
            today, SessionKind.STRENGTH, "Strength A + station finisher", 60, Intensity.STRENGTH, 0, 6..8, listOf(Modality.GYM),
            listOf("Warm up 5 minutes.", "Hyrox station finisher (about 12 min) at this phase's ladder level:", "  Sled Push, level 2: 4 x 12 m"),
            "Why.", LaunchHint(SessionType.GYM, hyroxFocus = setOf(HyroxStation.SLED_PUSH), minutes = 60, prehabMinutes = 10),
        )
        vm.launchPlanned(s)
        assertEquals(38, vm.gym.value!!.windowMinutes, "60 minus the prehab and the finisher")
        vm.doAllSets(); vm.finishGym(); idle()
        assertTrue(vm.gym.value!!.prehabOffered)
        vm.skipPrehab()
        assertFalse(vm.gym.value!!.prehabOffered)
        assertTrue(vm.gym.value!!.finisherOffered, "the finisher prompt follows the skipped prehab")
        vm.skipFinisher()
        assertEquals(Screen.Home, vm.screen.value)

        // Starting the prehab instead: the finisher prompt comes back when the routine is saved.
        val vm2 = newVm()
        vm2.launchPlanned(s)
        vm2.doAllSets(); vm2.finishGym(); idle()
        vm2.startPrehab()
        vm2.kneeCare.start(); vm2.kneeCare.ready()
        vm2.runKneeCareToEnd()
        vm2.saveKneeCare()
        assertEquals(Screen.Gym, vm2.screen.value)
        assertTrue(vm2.gym.value!!.finisherOffered)
    }
}
