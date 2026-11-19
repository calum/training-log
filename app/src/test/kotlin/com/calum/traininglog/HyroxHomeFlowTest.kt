package com.calum.traininglog

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.HyroxDivision
import com.calum.traininglog.core.HyroxGear
import com.calum.traininglog.core.HyroxPlace
import com.calum.traininglog.core.HyroxRuns
import com.calum.traininglog.core.HyroxStation
import com.calum.traininglog.core.PublicExport
import com.calum.traininglog.core.PublicImport
import com.calum.traininglog.core.SessionType
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

/** Home and garden practice with a wall ball, a sandbag and a treadmill: the plan, the treadmill runs, the saved record. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HyroxHomeFlowTest {
    private val app get() = ApplicationProvider.getApplicationContext<Context>() as Application
    private val today = LocalDate.of(2026, 6, 10)
    private fun newVm() = AppViewModel(app, clock = { today })
    private fun onDisk() = Store(app).load()
    private fun advance(ms: Long) { ShadowSystemClock.advanceBy(Duration.ofMillis(ms)) }

    private val homeKit = setOf(HyroxGear.TREADMILL, HyroxGear.WALL_BALL, HyroxGear.SANDBAG)
    private val allowed = setOf(HyroxStation.WALL_BALLS, HyroxStation.SANDBAG_LUNGES, HyroxStation.BURPEE_BROAD_JUMP)

    private fun HyroxController.lapsToRating(advanceMs: (Long) -> Unit) {
        while (ui.value.step != HyroxStep.RATE) {
            if (ui.value.step == HyroxStep.READY) { go(); advanceMs(20_000); lapDone() }
            if (ui.value.step == HyroxStep.REST) { advanceMs(ui.value.restTotalSec * 1000L); assertTrue(tick()) }
        }
    }

    private fun input(settings: UserSettings) = HyroxPlanInput(emptyList(), HyroxDivision.MEN_OPEN, settings)

    @Test fun treadmillRunCountsDownBeepsAndIsLogged() {
        var t = 0L
        val c = HyroxController { t }
        val settings = UserSettings(hyroxPlace = HyroxPlace.HOME, hyroxHomeGear = homeKit, hyroxRunSpeedKmh = 9.0, hyroxRunInclinePct = 1.5)
        c.prepare(input(settings), 60, emptySet(), withRuns = true, runMetres = 600)
        assertEquals(HyroxPlace.HOME, c.ui.value.place)
        assertTrue(c.ui.value.plan!!.blocks.all { it.station in allowed && it.runAfterM == 600 })
        c.start()
        assertTrue(c.ui.value.runSpec!!.treadmill)
        assertEquals(1.5, c.ui.value.runSpec!!.inclinePct)

        c.lapsToRating { t += it }; c.saveBlock()
        assertEquals(HyroxStep.RUN, c.ui.value.step)
        assertNull(c.ui.value.runTargetSec, "not started yet")
        c.runGo()
        assertEquals(240, c.ui.value.runTargetSec) // 600 m at 9 km/h
        t += 100_000
        assertFalse(c.tickRun())
        c.runAddTime()
        assertEquals(270, c.ui.value.runTargetSec)
        t += 169_000
        assertFalse(c.tickRun(), "one second to go")
        t += 1_000
        assertTrue(c.tickRun(), "countdown reached zero: done beep")
        assertFalse(c.tickRun(), "only once")
        assertEquals(1, c.ui.value.index, "moved on to the next station")
        val run = c.ui.value.results.last()
        assertEquals(StationLog("run", null, 600, null, 270, null), run)

        // Done early: the log holds what the treadmill covered, with the optional effort.
        c.lapsToRating { t += it }; c.saveBlock()
        c.runGo(); t += 60_000; c.setRunRpe(7); c.setRunRpe(7); assertNull(c.ui.value.runRpe); c.setRunRpe(7)
        c.runDone()
        assertEquals(StationLog("run", null, 150, null, 60, 7), c.ui.value.results.last())
        assertEquals(listOf(270, 60), c.ui.value.runs)
        assertEquals(2, c.ui.value.results.count { HyroxRuns.isRun(it) })
        assertEquals(2, c.ui.value.stationCount)

        // Skip run: nothing logged.
        if (c.ui.value.phase == HyroxPhase.RUNNING) {
            c.lapsToRating { t += it }; c.saveBlock()
            val before = c.ui.value.results.size
            c.skipRun()
            assertEquals(before, c.ui.value.results.size)
        }
    }

    @Test fun withoutATreadmillTheRunIsAStopwatch() {
        var t = 0L
        val c = HyroxController { t }
        val settings = UserSettings(hyroxPlace = HyroxPlace.HOME, hyroxHomeGear = setOf(HyroxGear.WALL_BALL))
        c.prepare(input(settings), 45, emptySet(), withRuns = true)
        assertFalse(c.ui.value.treadmillHere)
        c.start()
        assertFalse(c.ui.value.runSpec!!.treadmill)
        c.lapsToRating { t += it }; c.saveBlock()
        c.runGo()
        assertNull(c.ui.value.runTargetSec)
        t += 200_000
        assertFalse(c.tickRun(), "a stopwatch never ends by itself")
        c.runDone()
        assertEquals(StationLog("run", null, 400, null, 200, null), c.ui.value.results.last())
    }

    @Test fun homeSessionWithWallBallSandbagAndTreadmillSavesExportsAndImports() {
        val vm = newVm()
        vm.updateProfile { p ->
            p.copy(settings = p.settings.copy(hyroxPlace = HyroxPlace.HOME, hyroxHomeGear = homeKit, hyroxRunSpeedKmh = 9.0, hyroxRunInclinePct = 1.0))
        }
        vm.begin(SessionType.HYROX, 60)
        vm.hyroxSetup(withRuns = true, runMetres = 600)
        val plan = vm.hyrox.ui.value.plan!!
        assertEquals(allowed, plan.blocks.map { it.station }.toSet(), "only the three stations he has the kit for")
        assertTrue(plan.unavailable.containsAll(listOf(HyroxStation.SLED_PUSH, HyroxStation.SKIERG, HyroxStation.ROW, HyroxStation.FARMERS_CARRY)))
        assertTrue(plan.runSpec!!.treadmill)
        assertTrue(plan.blocks.all { it.runAfterM == 600 })

        vm.hyrox.start()
        var runIndex = 0
        while (vm.hyrox.ui.value.phase == HyroxPhase.RUNNING) {
            vm.hyrox.lapsToRating(::advance)
            vm.hyrox.setRpe(7); vm.hyrox.setTechnique(3)
            vm.hyrox.saveBlock()
            assertEquals(HyroxStep.RUN, vm.hyrox.ui.value.step)
            when (runIndex++) {
                0 -> { vm.hyrox.runGo(); advance(100_000); vm.hyrox.setRunRpe(6); vm.hyrox.runDone() } // done early: 250 m
                1 -> { vm.hyrox.runGo(); advance(241_000); assertTrue(vm.hyrox.tickRun()) }           // full countdown: 600 m
                else -> vm.hyrox.skipRun()
            }
        }
        assertEquals(HyroxPhase.DONE, vm.hyrox.ui.value.phase)
        assertEquals(plan.blocks.size, vm.hyrox.ui.value.stationCount)
        advance(60_000)
        vm.saveHyroxSession(Feeling.GOOD, 1)

        // ---- the saved record: stations in order, each run right after its station
        val rec = onDisk().records.single()
        assertEquals(SessionType.HYROX, rec.type)
        assertEquals(HyroxPlace.HOME, onDisk().profile.settings.hyroxPlace)
        val stationLogs = rec.stations.filter { it.stationId != "run" }
        assertEquals(plan.blocks.map { it.station.id }, stationLogs.map { it.stationId })
        assertTrue(stationLogs.all { HyroxStation.byId(it.stationId)!! in allowed })
        val runs = rec.stations.filter { it.stationId == "run" }
        assertEquals(minOf(2, plan.blocks.size), runs.size)
        assertEquals(StationLog("run", null, 250, null, 100, 6), runs[0])
        if (runs.size > 1) assertEquals(StationLog("run", null, 600, null, 240, null), runs[1])
        assertEquals("run", rec.stations[1].stationId, "the run follows the first station")
        assertNull(rec.note)
        assertNotNull(vm.celebration.value)

        // ---- the Claude export
        val export = vm.exportText(null)
        assertTrue("Run 250 m in 1:40, RPE 6" in export, export)
        if (runs.size > 1) assertTrue("Run 600 m in 4:00" in export, export)
        assertTrue(stationLogs.all { export.contains(HyroxStation.byId(it.stationId)!!.displayName) }, export)

        // ---- the public export and its import
        val files = PublicExport.files(onDisk().records)
        val csv = files.entries.single { it.key.startsWith("data/hyrox/") }.value
        assertTrue(csv.lines().any { it.startsWith("2026-06-10,run,,250,,100,6") }, csv)
        if (runs.size > 1) assertTrue(csv.lines().any { it.startsWith("2026-06-10,run,,600,,240,") }, csv)
        assertEquals(rec.stations.size, csv.lines().filter { it.isNotBlank() }.size - 1)
        assertFalse(csv.contains("knee", true) || csv.contains("feeling", true))
        val back = PublicImport.records(files).single { it.type == SessionType.HYROX }
        assertEquals(rec.stations.map { it.stationId }, back.stations.map { it.stationId })
        assertEquals(runs, back.stations.filter { it.stationId == "run" })

        // ---- levels and badges ignore the runs
        val hist = HyroxFormat.entries(onDisk().records, HyroxStation.WALL_BALLS)
        assertTrue(hist.all { it.log.stationId == "wall_balls" })
    }
}
