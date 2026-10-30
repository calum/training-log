package com.calum.traininglog.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublicImportTest {
    private fun d(s: String) = LocalDate.parse(s)

    private fun gym(date: String, vararg logs: ExerciseLog) = SessionRecord(
        "x-$date", d(date), SessionType.GYM, 60, 50, Feeling.ROUGH, kneePain = 7, note = "secret", exercises = logs.toList(),
    )

    private val records = listOf(
        gym("2026-09-30", ExerciseLog("squat", listOf(LoggedSet(80.0, 5, 5, 2), LoggedSet(82.5, 4, 5, null)))),
        gym(
            "2026-10-05",
            ExerciseLog("squat", listOf(LoggedSet(85.0, 5, 5, 2), LoggedSet(85.0, 5, 5, 1), LoggedSet(85.0, 3, 5, 0))),
            ExerciseLog("deadlift", listOf(LoggedSet(100.0, 3, 3, 3))),
        ),
        SessionRecord(
            "t1", d("2026-10-06"), SessionType.TREADMILL, 30, 29, Feeling.GOOD,
            treadmill = TreadmillLog(TreadmillStyle.PYRAMID, 1800, 1746),
        ),
        SessionRecord("c1", d("2026-10-07"), SessionType.CORE, 15, 12, Feeling.OKAY),
        SessionRecord("h1", d("2026-10-08"), SessionType.HIKE, 90, 95, Feeling.GOOD),
    )

    @Test fun roundTripPreservesNumbers() {
        val back = PublicImport.records(PublicExport.files(records))
        assertEquals(records.map { it.date to it.type }, back.map { it.date to it.type })
        val g = back.single { it.date == d("2026-10-05") }
        assertEquals(listOf("squat", "deadlift"), g.exercises.map { it.exerciseId })
        assertEquals(records[1].exercises.map { it.sets }, g.exercises.map { it.sets })
        val t = back.single { it.type == SessionType.TREADMILL }.treadmill!!
        assertEquals(TreadmillStyle.PYRAMID, t.style)
        assertEquals(1800, t.plannedSec)
        assertEquals(1746, t.completedSec) // export keeps 0.1 min (6 s) precision
        assertEquals(12, back.single { it.type == SessionType.CORE }.minutesActual)
        assertEquals(95, back.single { it.type == SessionType.HIKE }.minutesActual)
    }

    @Test fun privateFieldsGetNeutralDefaultsAndIdsAreDeterministic() {
        val files = PublicExport.files(records)
        val a = PublicImport.records(files)
        assertEquals(a, PublicImport.records(files))
        assertTrue(a.all { it.feeling == Feeling.GOOD && it.kneePain == null && it.note == null && it.dayLabel == null })
        assertEquals("gh-2026-10-05-gym-1", a.single { it.date == d("2026-10-05") }.id)
        assertEquals("gh-2026-10-06-treadmill-1", a.single { it.type == SessionType.TREADMILL }.id)
        assertEquals("gh-2026-10-07-core-1", a.single { it.type == SessionType.CORE }.id)
    }

    @Test fun twoTreadmillSessionsOnOneDayStaySeparate() {
        fun t(id: String) = SessionRecord(id, d("2026-10-06"), SessionType.TREADMILL, 30, 30, Feeling.GOOD, treadmill = TreadmillLog(TreadmillStyle.STEADY, 1800, 1800))
        val back = PublicImport.records(PublicExport.files(listOf(t("a"), t("b"))))
        assertEquals(listOf("gh-2026-10-06-treadmill-1", "gh-2026-10-06-treadmill-2"), back.map { it.id })
    }

    @Test fun garbageIsSkippedNotFatal() {
        val files = mapOf(
            "data/gym/2026-10.csv" to "${PublicExport.GYM_HEADER}\nnot-a-date,squat,1,80,5,5,2\n2026-10-01,squat,1,80,5,5,\n\n2026-10-01,squat,2,x,5,5,\n",
            "data/treadmill/2026-10.csv" to "${PublicExport.TREADMILL_HEADER}\n2026-10-01,nope,30,30\n",
            "README.md" to "ignored",
        )
        val r = PublicImport.records(files)
        assertEquals(1, r.size)
        assertEquals(listOf(LoggedSet(80.0, 5, 5, null)), r[0].exercises[0].sets)
    }

    @Test fun mergeAddsOnlyMissingDateTypeAndNeverReplaces() {
        val imported = PublicImport.records(PublicExport.files(records))
        val local = listOf(gym("2026-10-05", ExerciseLog("squat", listOf(LoggedSet(1.0, 1, 1, null)))))
        val add = PublicImport.newOnly(local, imported)
        assertEquals(imported.size - 1, add.size)
        assertTrue(add.none { it.date == d("2026-10-05") })
        assertEquals(imported.size, PublicImport.newOnly(emptyList(), imported).size)
        assertTrue(PublicImport.newOnly(imported, imported).isEmpty())
    }
}
