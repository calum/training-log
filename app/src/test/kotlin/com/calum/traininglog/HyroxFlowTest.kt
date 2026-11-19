package com.calum.traininglog

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.HyroxDivision
import com.calum.traininglog.core.HyroxProgression
import com.calum.traininglog.core.HyroxStation
import com.calum.traininglog.core.PublicExport
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.StationEntry
import com.calum.traininglog.core.StationLog
import com.calum.traininglog.core.UserSettings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The guided Hyrox session and the quick log, end to end: controller on a fake clock, then through the ViewModel and disk. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HyroxFlowTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>() as Application
    private val today = LocalDate.of(2026, 6, 10)
    private fun newVm() = AppViewModel(app, clock = { today })
    private fun onDisk() = Store(app).load()
    private fun advance(ms: Long) { ShadowSystemClock.advanceBy(Duration.ofMillis(ms)) }
    private val input = HyroxPlanInput(emptyList(), HyroxDivision.MEN_OPEN, UserSettings())

    /** Runs every lap of the current block (20 s each) and waits out the rests, until the rating step. */
    private fun HyroxController.lapsToRating(advanceMs: (Long) -> Unit) {
        while (ui.value.step != HyroxStep.RATE) {
            if (ui.value.step == HyroxStep.READY) { go(); advanceMs(20_000); lapDone() }
            if (ui.value.step == HyroxStep.REST) { advanceMs(ui.value.restTotalSec * 1000L); assertTrue(tick()) }
        }
    }

    @Test fun controllerOnAFakeClock() {
        var t = 1_000L
        val c = HyroxController { t }
        c.prepare(input, 45, emptySet())
        val plan = c.ui.value.plan!!
        assertTrue(plan.blocks.size >= 2, "a 45 minute plan has several stations")
        c.start()
        val first = c.ui.value.block!!
        val rounds = first.prescription.rounds
        assertEquals(HyroxStep.READY, c.ui.value.step)

        c.go(); t += 18_400; c.lapDone()
        assertEquals(listOf(18), c.ui.value.laps)
        if (rounds > 1) {
            assertEquals(HyroxStep.REST, c.ui.value.step)
            assertFalse(c.tick(), "rest not over yet")
            t += first.prescription.restSec * 1000L - 1; assertFalse(c.tick())
            t += 1; assertTrue(c.tick(), "rest over: beep")
            assertEquals(HyroxStep.READY, c.ui.value.step)
            assertEquals(1, c.ui.value.round)
            c.lapsToRating { t += it }
        }
        assertEquals(HyroxStep.RATE, c.ui.value.step)
        // Prefilled from the prescription, steppers change it, rating chips toggle.
        assertEquals(first.prescription.totalWork, c.ui.value.actual.distanceM ?: c.ui.value.actual.reps)
        c.adjustDistance(-1); c.adjustReps(-1)
        c.setRpe(8); c.setRpe(8); assertNull(c.ui.value.rpe) // tapping twice clears
        c.setRpe(7); c.setTechnique(3)
        c.saveBlock()
        val log = c.ui.value.results.single()
        assertEquals(first.station.id, log.stationId)
        assertEquals(7, log.rpe); assertEquals(3, log.technique); assertEquals(first.prescription.level, log.level)
        assertTrue(log.seconds!! >= 18)

        // Skip the second station, end early from the third: what is done is kept, partial laps included.
        c.skipStation()
        assertEquals(1, c.ui.value.skipped)
        assertEquals(2, c.ui.value.index)
        c.go(); t += 30_000; c.lapDone()
        val before = c.ui.value.results.size
        c.endEarly()
        assertEquals(HyroxPhase.DONE, c.ui.value.phase)
        assertEquals(before + 1, c.ui.value.results.size)
        assertNull(c.ui.value.results.last().rpe)
    }

    @Test fun runBlockAndSetupRules() {
        var t = 0L
        val c = HyroxController { t }
        c.prepare(input, 60, emptySet(), withRuns = true)
        assertTrue(c.ui.value.withRuns)
        assertTrue(c.ui.value.plan!!.blocks.all { it.runAfterM == 400 })
        c.start(); c.lapsToRating { t += it }; c.saveBlock()
        assertEquals(HyroxStep.RUN, c.ui.value.step)
        c.runGo(); t += 200_000; c.runDone()
        assertEquals(listOf(200), c.ui.value.runs)
        assertEquals(1, c.ui.value.index)
        assertEquals("Easy runs: 1 x 400 m (3:20)", HyroxText.runNote(c.ui.value.runs))

        // A sore knee last time: runs are not offered at all.
        val sore = input.copy(records = listOf(com.calum.traininglog.core.SessionRecord("k", today, SessionType.GYM, 60, 60, Feeling.GOOD, kneePain = 5)))
        assertFalse(sore.runsAllowed)
        val c2 = HyroxController { 0L }
        c2.prepare(sore, 45, emptySet(), withRuns = true)
        assertFalse(c2.ui.value.withRuns)

        // Different mix changes the stations; picking a focus plans around it.
        val c3 = HyroxController { 0L }
        c3.prepare(input, 45, emptySet())
        val a = c3.ui.value.plan!!.blocks.map { it.station }
        c3.rebuild(input, shuffle = true)
        assertTrue(c3.ui.value.plan!!.blocks.map { it.station } != a)
        c3.rebuild(input, focus = setOf(HyroxStation.WALL_BALLS))
        assertEquals(listOf(HyroxStation.WALL_BALLS), c3.ui.value.plan!!.blocks.map { it.station })
    }

    @Test fun wholeGuidedSessionSavesAndProgresses() {
        val vm = newVm()
        vm.begin(SessionType.HYROX, 45)
        assertEquals(Screen.HyroxSession(emptySet()), vm.screen.value)
        val plan = vm.hyrox.ui.value.plan!!
        val firstStation = plan.blocks.first().station
        val firstLevel = plan.blocks.first().prescription.level
        vm.hyrox.start()
        while (vm.hyrox.ui.value.phase == HyroxPhase.RUNNING) {
            vm.hyrox.lapsToRating(::advance)
            vm.hyrox.setRpe(7); vm.hyrox.setTechnique(3)
            vm.hyrox.saveBlock()
        }
        assertEquals(HyroxPhase.DONE, vm.hyrox.ui.value.phase)
        advance(60_000)
        vm.saveHyroxSession(Feeling.GOOD, 1)

        assertEquals(Screen.Home, vm.screen.value)
        val rec = onDisk().records.single()
        assertEquals(SessionType.HYROX, rec.type)
        assertEquals(today, rec.date)
        assertEquals(45, rec.minutesAvailable)
        assertEquals(1, rec.kneePain)
        assertEquals(plan.blocks.map { it.station.id }, rec.stations.map { it.stationId })
        assertTrue(rec.stations.all { it.rpe == 7 && it.technique == 3 && (it.seconds ?: 0) > 0 && it.level != null })
        assertTrue(rec.minutesActual >= 1)
        assertNotNull(vm.celebration.value)
        assertEquals(HyroxPhase.SETUP, vm.hyrox.ui.value.phase)

        // The next prescription for that station has moved up, because everything was done at effort 7.
        val hist = rec.stations.filter { it.stationId == firstStation.id }.map { StationEntry(rec.date, it) }
        val next = HyroxProgression.next(firstStation, HyroxDivision.MEN_OPEN, hist)
        assertEquals(firstLevel + 1, next.level)

        // Export text and public CSV pick it up.
        val export = vm.exportText(null)
        assertTrue(export.contains(firstStation.displayName), export)
        assertTrue(export.contains("RPE 7"), export)
        val csv = PublicExport.files(onDisk().records)
        val hyroxFile = csv.entries.single { it.key.startsWith("data/hyrox/") }.value
        assertEquals(PublicExport.HYROX_HEADER, hyroxFile.lines().first())
        assertEquals(rec.stations.size, hyroxFile.lines().filter { it.isNotBlank() }.size - 1)
        assertTrue(hyroxFile.contains(firstStation.id))
        assertFalse(csv.values.any { it.contains("feeling", true) || it.contains("knee", true) || it.contains("Easy runs") })
    }

    @Test fun endingEarlyWithNothingDoneSavesNothing() {
        val vm = newVm()
        vm.begin(SessionType.HYROX, 30)
        vm.hyrox.start()
        vm.hyrox.endEarly()
        vm.saveHyroxSession(Feeling.GOOD, null)
        assertEquals(emptyList(), onDisk().records)
        assertNull(vm.celebration.value)
        assertEquals(Screen.Home, vm.screen.value)
    }

    @Test fun quickLogCreatesOneRecordAndFeedsProgression() {
        val vm = newVm()
        val defaults = { s: HyroxStation -> HyroxLogForm.defaults(s, vm.data.value.records, HyroxDivision.MEN_OPEN) }
        var form = HyroxLogForm.start(defaults(HyroxStation.SLED_PUSH))
        assertEquals(1, form.current.level)
        form = form.setDaysAgo(2).adjustWeight(2).adjustDistance(1).adjustSeconds(7).setRpe(7).setTechnique(3)
        form = form.addAnother(defaults)
        assertEquals(HyroxStation.SKIERG.id, form.current.stationId) // first station not added yet
        assertEquals(1, form.done.size)
        assertEquals(1, form.toSave().size, "fresh editor is not saved when untouched")
        form = form.pick(defaults(HyroxStation.WALL_BALLS)).adjustReps(1).setSeconds(95)
        val saved = form.toSave()
        assertEquals(listOf("sled_push", "wall_balls"), saved.map { it.stationId })

        vm.saveHyroxLog(form.daysAgo, saved)
        assertEquals(Screen.Home, vm.screen.value)
        val rec = onDisk().records.single()
        assertEquals(today.minusDays(2), rec.date)
        assertEquals(SessionType.HYROX, rec.type)
        val push = rec.stations.first()
        assertEquals(70, push.seconds)
        assertEquals(HyroxLogForm.defaults(HyroxStation.SLED_PUSH, emptyList(), HyroxDivision.MEN_OPEN).weightKg!! + 5.0, push.weightKg!!) // two 2.5 kg steps
        assertEquals(7, push.rpe)
        assertEquals(95, rec.stations.last().seconds)
        assertNotNull(vm.celebration.value)

        // The sled push level moves on after a controlled level-1 session.
        val next = HyroxProgression.next(HyroxStation.SLED_PUSH, HyroxDivision.MEN_OPEN, listOf(StationEntry(rec.date, push)))
        assertEquals(2, next.level)
        assertTrue(vm.exportText(null).contains("Sled Push"))
        assertTrue(PublicExport.files(onDisk().records).keys.any { it.startsWith("data/hyrox/") })
    }

    @Test fun parsingTypedTimes() {
        assertEquals(72, parseClock("1:12"))
        assertEquals(72, parseClock("72"))
        assertEquals(600, parseClock("10:00"))
        assertNull(parseClock("1:75"))
        assertNull(parseClock("abc"))
        assertNull(parseClock(""))
        assertEquals("1:12", HyroxText.clock(72))
        val log = StationLog("sled_push", 40.0, 50, null, 72)
        assertEquals("Sled Push 50 m at 40 kg in 1:12", HyroxText.summary(log))
    }
}
