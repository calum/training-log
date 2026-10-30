package com.calum.traininglog.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublicExportTest {
    private fun gym(id: String, date: String, vararg logs: ExerciseLog) = SessionRecord(
        id = id, date = LocalDate.parse(date), type = SessionType.GYM, minutesAvailable = 61, minutesActual = 50,
        feeling = Feeling.GOOD, exercises = logs.toList(),
    )

    private fun squat(w: Double = 80.0) = ExerciseLog("squat", listOf(LoggedSet(w, 5, 5, 2), LoggedSet(w, 5, 5, 3), LoggedSet(w, 4, 5, null)))

    private fun tread(id: String, date: String, style: TreadmillStyle = TreadmillStyle.STEADY, planned: Int = 1800, done: Int = 1800) = SessionRecord(
        id = id, date = LocalDate.parse(date), type = SessionType.TREADMILL, minutesAvailable = 30, minutesActual = done / 60,
        feeling = Feeling.GOOD, treadmill = TreadmillLog(style, planned, done),
    )

    @Test fun privacyNothingPrivateLeaks() {
        val secret = "SECRETNOTE-7731"
        val records = listOf(
            SessionRecord(
                "r1", LocalDate.parse("2026-10-05"), SessionType.GYM, minutesAvailable = 4242, minutesActual = 40,
                feeling = Feeling.ILL, kneePain = 9, dayLabel = "SECRETDAY",
                exercises = listOf(
                    squat(),
                    ExerciseLog("custom:secretcustom", listOf(LoggedSet(33.3, 10, 10, 1)), MissReason.AT_LIMIT, customName = "SECRETCUSTOM"),
                ),
                note = secret,
            ),
            SessionRecord(
                "r2", LocalDate.parse("2026-10-06"), SessionType.TREADMILL, minutesAvailable = 4243, minutesActual = 30,
                feeling = Feeling.ROUGH, kneePain = 9, treadmill = TreadmillLog(TreadmillStyle.PYRAMID, 1800, 1500, Effort.TOO_HARD),
                note = secret,
            ),
            SessionRecord("r3", LocalDate.parse("2026-10-07"), SessionType.HIKE, 4244, 90, Feeling.ROUGH, kneePain = 9, note = secret),
            SessionRecord("r4", LocalDate.parse("2026-10-08"), SessionType.CORE, 4245, 15, Feeling.ILL, kneePain = 9, note = secret),
        )
        val files = PublicExport.files(records)
        assertTrue(files.size >= 4)
        for ((path, content) in files) {
            val all = (path + "\n" + content).lowercase()
            for (bad in listOf("secret", "knee", "feeling", "note", "custom", "rough", "effort", "too_hard", "4242", "4243", "4244", "4245", "33.3")) {
                assertFalse(bad in all, "'$bad' leaked into $path")
            }
            assertFalse(Regex("\\bill\\b").containsMatchIn(all), "feeling leaked into $path")
        }
    }

    @Test fun headersAndRows() {
        val f = PublicExport.files(listOf(gym("a", "2026-10-05", squat()), tread("b", "2026-10-06", TreadmillStyle.LONG_ROLLING, 3600, 3330)))
        assertEquals(
            "date,exercise,set,weight_kg,reps,target_reps,rir\n" +
                "2026-10-05,squat,1,80,5,5,2\n2026-10-05,squat,2,80,5,5,3\n2026-10-05,squat,3,80,4,5,\n",
            f["data/gym/2026-10.csv"],
        )
        assertEquals("date,style,planned_min,completed_min\n2026-10-06,long_rolling,60,55.5\n", f["data/treadmill/2026-10.csv"])
        assertEquals(setOf("data/gym/2026-10.csv", "data/treadmill/2026-10.csv", "data/summary.json"), f.keys)
    }

    @Test fun otherSessionsHaveNoNotes() {
        val r = SessionRecord("h", LocalDate.parse("2026-10-09"), SessionType.HIKE, 120, 95, Feeling.OKAY, note = "hello, \"world\"")
        val f = PublicExport.files(listOf(r))
        assertEquals("date,type,minutes\n2026-10-09,hike,95\n", f["data/other/2026-10.csv"])
    }

    @Test fun monthSplittingAndNoEmptyMonths() {
        val f = PublicExport.files(listOf(gym("a", "2026-09-30", squat()), gym("b", "2026-11-02", squat(82.5)), tread("c", "2026-11-03")))
        assertEquals(
            listOf("data/gym/2026-09.csv", "data/gym/2026-11.csv", "data/summary.json", "data/treadmill/2026-11.csv"),
            f.keys.toList(),
        )
        assertTrue("2026-11-02,squat,1,82.5,5,5,2" in f.getValue("data/gym/2026-11.csv"))
    }

    @Test fun deterministicRegardlessOfInputOrder() {
        val recs = listOf(gym("a", "2026-10-05", squat()), tread("b", "2026-10-06"), gym("c", "2026-10-02", squat(77.5)))
        assertEquals(PublicExport.files(recs).toList(), PublicExport.files(recs.reversed()).toList())
        assertEquals(PublicExport.files(recs).toList(), PublicExport.files(recs).toList())
    }

    @Test fun sortedByDateThenSetNumber() {
        val f = PublicExport.files(listOf(gym("late", "2026-10-20", squat(90.0)), gym("early", "2026-10-03", squat(80.0))))
        val lines = f.getValue("data/gym/2026-10.csv").lines()
        assertEquals("2026-10-03,squat,1,80,5,5,2", lines[1])
        assertEquals("2026-10-03,squat,3,80,4,5,", lines[3])
        assertEquals("2026-10-20,squat,1,90,5,5,2", lines[4])
    }

    @Test fun csvEscaping() {
        assertEquals("\"a,b\"", PublicExport.csv("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", PublicExport.csv("say \"hi\""))
        assertEquals("plain", PublicExport.csv("plain"))
    }

    @Test fun summaryJson() {
        val f = PublicExport.files(
            listOf(
                gym("a", "2026-10-05", squat(80.0)),
                gym("b", "2026-10-07", squat(82.5)),
                gym("c", "2026-10-13", ExerciseLog("rdl", listOf(LoggedSet(70.0, 8, 8, 2)))),
                tread("t", "2026-10-06", done = 1500),
            ),
        )
        val j = f.getValue("data/summary.json")
        assertTrue("\"generated_from_records\": 4" in j)
        assertTrue("\"squat\": [[\"2026-W41\", 82.5]]" in j, j)
        assertTrue("\"rdl\": [[\"2026-W42\", 70]]" in j, j)
        assertTrue("\"deadlift\": []" in j)
        assertTrue("\"sessions_per_week\": [[\"2026-W41\", 3], [\"2026-W42\", 1]]" in j, j)
        assertTrue("\"treadmill_min_per_week\": [[\"2026-W41\", 25]]" in j, j)
    }

    @Test fun emptyRecordsGiveOnlySummary() {
        val f = PublicExport.files(emptyList())
        assertEquals(setOf("data/summary.json"), f.keys)
        assertTrue("\"generated_from_records\": 0" in f.getValue("data/summary.json"))
    }
}
