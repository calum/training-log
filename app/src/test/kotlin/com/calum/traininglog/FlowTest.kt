package com.calum.traininglog

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.CueType
import com.calum.traininglog.core.Effort
import com.calum.traininglog.core.ExerciseLibrary
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.PublicExport
import com.calum.traininglog.core.RestDays
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.TreadmillStyle
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whole user flows through the real [AppViewModel] and [Store] on the JVM. Nothing here touches the screen
 * code except the last test; what is asserted is what a person would see afterwards: the saved file on disk,
 * the screen the ViewModel routes to, the export text and the next session's prescription.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = Application::class)
class FlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<Context>() as Application
    private var today = LocalDate.of(2026, 6, 10) // a Wednesday
    private fun newVm() = AppViewModel(app, clock = { today })
    private fun onDisk() = Store(app).load()

    @Before fun setUp() { TreadmillEngine.stop() }
    @After fun tearDown() { TreadmillEngine.stop() }

    /** Does every set of today's gym session: optional weight nudge, Done, then the RIR rating. */
    private fun logAllSets(vm: AppViewModel, rir: Int, only: (String) -> Boolean = { true }) {
        val g = vm.gym.value!!
        g.exercises.forEachIndexed { ei, e ->
            if (!only(e.planned.exercise.id)) return@forEachIndexed
            e.sets.indices.forEach { si ->
                vm.adjustSet(ei, si, dKg = 2.5)   // stepper up ...
                vm.adjustSet(ei, si, dKg = -2.5)  // ... and back: the logged weight is the prescribed one
                vm.doneSet(ei, si)
                vm.setRir(ei, si, rir)
            }
        }
    }

    // (a) fresh install -> recommendation -> gym session -> saved -> next prescription progresses

    @Test fun freshInstallToGymSessionToProgression() {
        val vm = newVm()
        assertEquals(emptyList(), vm.data.value.records)
        assertNull(vm.celebration.value)

        vm.go(Screen.Start)
        vm.setInputs { it.copy(minutes = 75, feeling = Feeling.GOOD) }
        assertNull(vm.recommendation.value)
        vm.recommend()
        val rec = assertNotNull(vm.recommendation.value)
        assertEquals(SessionType.GYM, rec.primary.type)

        vm.begin(rec.primary.type, rec.primary.minutes, rec.primary.style)
        assertEquals(Screen.Gym, vm.screen.value)
        val plan = vm.gym.value!!
        val squatIdx = plan.exercises.indexOfFirst { it.planned.exercise.id == "squat" }
        assertTrue(squatIdx >= 0)
        val squatKg = plan.exercises[squatIdx].sets.first().kg
        assertEquals(72.5, squatKg) // the default starting weight from Settings on a fresh install

        logAllSets(vm, rir = 2)
        assertTrue(vm.gym.value!!.exercises.all { e -> e.sets.all { it.done && it.rir == 2 } })
        assertNotNull(vm.gym.value!!.rest) // resting after the last set

        vm.finishGym()
        assertEquals(Screen.Home, vm.screen.value)
        assertNull(vm.gym.value)

        // Saved to disk, readable by a brand new Store.
        val saved = onDisk().records.single()
        assertEquals(SessionType.GYM, saved.type)
        assertEquals(today, saved.date)
        assertEquals(plan.exercises.map { it.planned.exercise.id }, saved.exercises.map { it.exerciseId })
        val squat = saved.exercises.first { it.exerciseId == "squat" }
        assertEquals(plan.exercises[squatIdx].sets.size, squat.sets.size)
        assertTrue(squat.sets.all { it.weightKg == squatKg && it.reps == 5 && it.targetReps == 5 && it.rir == 2 })

        // Celebration fired for exactly this record.
        assertEquals(saved.id, vm.celebration.value!!.record.id)
        assertTrue(vm.celebration.value!!.messages.isNotEmpty())
        vm.dismissCelebration()
        assertNull(vm.celebration.value)

        // Two days later the squat goes up by the increment because every rep was hit with RIR >= 2.
        today = today.plusDays(2)
        vm.setInputs { it.copy(minutes = 75) }
        vm.begin(SessionType.GYM, 75)
        val next = vm.gym.value!!.exercises.first { it.planned.exercise.id == "squat" }
        assertEquals(squatKg + ExerciseLibrary.squat.incrementKg, next.sets.first().kg)
        assertTrue(next.planned.prescription.note.contains("add", ignoreCase = true))
    }

    @Test fun lowRirKeepsTheSameWeight() {
        val vm = newVm()
        vm.begin(SessionType.GYM, 75)
        val kg = vm.gym.value!!.exercises.first { it.planned.exercise.id == "squat" }.sets.first().kg
        logAllSets(vm, rir = 1)
        vm.finishGym()
        today = today.plusDays(2)
        vm.begin(SessionType.GYM, 75)
        assertEquals(kg, vm.gym.value!!.exercises.first { it.planned.exercise.id == "squat" }.sets.first().kg)
    }

    @Test fun finishingWithNothingDoneSavesNothing() {
        val vm = newVm()
        vm.begin(SessionType.GYM, 75)
        vm.finishGym()
        assertEquals(emptyList(), onDisk().records)
        assertNull(vm.celebration.value)
        assertEquals(Screen.Home, vm.screen.value)
    }

    // (b) treadmill on a fake clock

    @Test fun treadmillSessionEndedEarlyChangesCalibration() {
        val vm = newVm()
        val before = vm.data.value.profile.treadmill
        vm.setInputs { it.copy(minutes = 40) }
        vm.begin(SessionType.TREADMILL, 40, TreadmillStyle.PYRAMID)
        assertEquals(Screen.TreadmillSetup, vm.screen.value)
        val plan = vm.pendingTreadmill.value!!
        assertEquals(TreadmillStyle.PYRAMID, plan.style)

        // Start on a fake monotonic clock (the real start also launches the foreground service).
        TreadmillEngine.start(plan, 0L)
        vm.go(Screen.TreadmillPlayer)
        val cues = mutableListOf<Pair<Int, CueType>>()
        var now = 0L
        while (now < 8 * 60_000L) { // 8 minutes in one-second ticks: crosses the end of the 5 min warm-up
            now += 1000
            TreadmillEngine.tick(now).forEach { cues += (now / 1000).toInt() to it.type }
        }
        assertTrue(cues.any { it.second == CueType.PRE_WARNING && it.first in 280..290 }, "pre-warning before the first change: $cues")
        assertTrue(cues.any { it.second == CueType.CHANGE && it.first in 299..302 }, "change cue at 5:00: $cues")
        assertTrue(TreadmillEngine.ui.value.state!!.index >= 1)

        // Pausing on the fake clock does not count as walked time.
        TreadmillEngine.pause(now)
        TreadmillEngine.tick(now + 60_000)
        assertEquals(480, TreadmillEngine.completedSec())
        TreadmillEngine.resume(now + 60_000)

        vm.endTreadmill()
        assertEquals(Screen.TreadmillDone, vm.screen.value)
        vm.saveTreadmill(Effort.TOO_EASY)
        assertEquals(Screen.Home, vm.screen.value)
        assertFalse(TreadmillEngine.ui.value.active)

        val d = onDisk()
        val rec = d.records.single()
        assertEquals(SessionType.TREADMILL, rec.type)
        assertEquals(8, rec.minutesActual)
        assertEquals(480, rec.treadmill!!.completedSec)
        assertEquals(plan.totalSec, rec.treadmill!!.plannedSec)
        assertEquals(Effort.TOO_EASY, rec.treadmill!!.effort)
        assertEquals(before.inclinePct + 1, d.profile.treadmill.inclinePct) // too easy: turn it up next time
        assertEquals(before.speedKmh, d.profile.treadmill.speedKmh)
        assertNotNull(vm.celebration.value)
    }

    @Test fun treadmillUnderAMinuteIsNotSaved() {
        val vm = newVm()
        vm.begin(SessionType.TREADMILL, 30, TreadmillStyle.STEADY)
        TreadmillEngine.start(vm.pendingTreadmill.value!!, 0L)
        TreadmillEngine.tick(30_000)
        vm.endTreadmill()
        vm.saveTreadmill(Effort.TOO_HARD)
        assertEquals(emptyList(), onDisk().records)
        assertEquals(vm.data.value.profile.treadmill, onDisk().profile.treadmill)
    }

    // (c) manual log

    @Test fun manualLogAppearsInHistoryAndExport() {
        val vm = newVm()
        vm.saveManual(SessionType.HIKE, daysAgo = 3, minutes = 95, style = null, note = "Ridge walk, knee fine")
        vm.saveManual(SessionType.TREADMILL, daysAgo = 1, minutes = 40, style = TreadmillStyle.LADDER, note = "")
        assertEquals(Screen.Home, vm.screen.value)

        val records = onDisk().records
        assertEquals(2, records.size)
        val hike = records.first { it.type == SessionType.HIKE }
        assertEquals(today.minusDays(3), hike.date)
        assertEquals(95, hike.minutesActual)
        val history = vm.data.value.records.sortedByDescending { it.date }
        assertEquals(listOf(SessionType.TREADMILL, SessionType.HIKE), history.map { it.type })

        val export = vm.exportText(7)
        assertTrue(export.contains("${today.minusDays(3)} hike"), export)
        assertTrue(export.contains("note: Ridge walk, knee fine"), export)
        assertTrue(export.contains("${today.minusDays(1)} treadmill"), export)
        assertTrue(export.contains("Sessions done: 2"), export)
        assertFalse(vm.exportText(2).contains("hike")) // outside a 2-day window
    }

    // (d) rest day and the "I've got time" override

    @Test fun restDayAndTrainAnywayOverride() {
        val vm = newVm()
        assertFalse(RestDays.isRestDay(vm.data.value.profile, today))
        vm.updateProfile { RestDays.toggleDate(it, today) }
        assertTrue(RestDays.isRestDay(onDisk().profile, today))
        assertEquals(setOf(today), onDisk().profile.restDates)

        vm.setInputs { it.copy(minutes = 60) }
        vm.recommend()
        assertTrue(vm.recommendation.value!!.reason.startsWith("Rest day"), vm.recommendation.value!!.reason)

        vm.updateProfile { RestDays.trainAnyway(it, today) }
        val p = onDisk().profile
        assertFalse(RestDays.isRestDay(p, today))
        assertTrue(RestDays.isPlannedRest(p, today))
        assertEquals(setOf(today), p.workoutOverrides)
        vm.recommend()
        assertFalse(vm.recommendation.value!!.reason.startsWith("Rest day"))
        // The override is for today only.
        assertFalse(RestDays.isRestDay(p, today.plusDays(1)) )
        assertTrue(RestDays.isRestDay(RestDays.toggleDate(p.copy(workoutOverrides = emptySet()), today.plusDays(1)), today.plusDays(1)))
    }

    // (e) custom exercise

    @Test fun customExerciseIsLoggedExportedAsCustomAndStaysOutOfPublicFiles() {
        val vm = newVm()
        vm.begin(SessionType.GYM, 75)
        val before = vm.gym.value!!.exercises.size
        vm.addCustomExercise("Cable face pull")
        val g = vm.gym.value!!
        assertEquals(before + 1, g.exercises.size)
        assertEquals("custom:cable_face_pull", g.exercises.last().planned.exercise.id)
        logAllSets(vm, rir = 2) // includes the custom one
        vm.finishGym()

        val rec = onDisk().records.single()
        val custom = rec.exercises.first { ExerciseLibrary.isCustom(it.exerciseId) }
        assertEquals("Cable face pull", custom.customName)
        assertTrue(custom.sets.isNotEmpty())

        val export = vm.exportText(null)
        assertTrue(export.contains("CUSTOM: Cable face pull"), export)
        assertTrue(export.contains("Custom exercises logged (please add properly): Cable face pull"), export)

        val files = PublicExport.files(onDisk().records)
        assertTrue(files.keys.any { it.startsWith("data/gym/") }, "library lifts are still published")
        files.forEach { (path, text) ->
            assertFalse(text.contains("custom", ignoreCase = true) || text.contains("face", ignoreCase = true), "leak in $path")
        }
    }

    // (f) delete

    @Test fun deletingARecordRemovesItFromDisk() {
        val vm = newVm()
        vm.saveManual(SessionType.HIKE, 2, 60, null, "keep me")
        vm.saveManual(SessionType.CORE, 1, 20, null, "delete me")
        val victim = vm.data.value.records.first { it.note == "delete me" }
        vm.deleteRecord(victim.id)
        assertEquals(listOf("keep me"), onDisk().records.map { it.note })
        assertFalse(vm.exportText(null).contains("delete me"))
        vm.deleteRecord("no-such-id")
        assertEquals(1, onDisk().records.size)
    }

    // (g) process death

    @Test fun dataSurvivesViewModelRecreation() {
        val first = newVm()
        first.updateProfile { RestDays.toggleDate(it, today.plusDays(1)) }
        first.begin(SessionType.GYM, 75)
        val kg = first.gym.value!!.exercises.first { it.planned.exercise.id == "squat" }.sets.first().kg
        logAllSets(first, rir = 3)
        first.finishGym()
        first.saveManual(SessionType.HIKE, 5, 30, null, "n")
        val expected = first.data.value

        val second = newVm() // as after the process was killed
        assertEquals(expected.records, second.data.value.records)
        assertEquals(expected.profile, second.data.value.profile)
        assertEquals(2, second.data.value.records.size)
        assertNull(second.storeNote.value)
        assertEquals(Screen.Home, second.screen.value)
        // And the progression history is picked up by the new instance.
        today = today.plusDays(2)
        second.begin(SessionType.GYM, 75)
        assertEquals(kg + ExerciseLibrary.squat.incrementKg, second.gym.value!!.exercises.first { it.planned.exercise.id == "squat" }.sets.first().kg)
    }

    // Safety copy fallback seen through the ViewModel and the Home screen

    @Test fun corruptFileIsRestoredFromSafetyCopyAndHomeSaysSo() {
        val first = newVm()
        first.saveManual(SessionType.HIKE, 4, 50, null, "one")
        first.saveManual(SessionType.HIKE, 3, 50, null, "two") // first overwrite of the day takes the safety copy of {one}
        val main = File(app.filesDir, "training-data.json")
        main.writeText(main.readText().take(40)) // truncated mid-write

        val vm = newVm()
        assertEquals(listOf("one"), vm.data.value.records.map { it.note })
        assertEquals("We restored your data from a safety copy.", vm.storeNote.value!!.message)
        assertTrue(app.filesDir.listFiles()!!.any { it.name.startsWith("training-data.corrupt-") })

        rule.setContent { AppRoot(vm, inPip = false) }
        rule.onNodeWithText("We restored your data from a safety copy.").assertExists()
        rule.onNodeWithText("OK").performClickAndSettle()
        assertNull(vm.storeNote.value)
        assertNull(newVm().storeNote.value) // one-time
    }

    @Test fun dataFromANewerAppIsNotOverwrittenByUse() {
        val main = File(app.filesDir, "training-data.json")
        val original = """{"schemaVersion": 99, "records": [], "profile": {}}"""
        main.writeText(original)
        val vm = newVm()
        assertEquals(StoreNote.Kind.TOO_NEW, vm.storeNote.value!!.kind)
        vm.saveManual(SessionType.HIKE, 1, 30, null, "x")
        assertEquals(original, main.readText())
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickAndSettle() {
        performClick(); rule.waitForIdle()
    }
}
