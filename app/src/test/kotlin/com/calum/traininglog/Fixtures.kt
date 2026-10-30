package com.calum.traininglog

import android.content.Context
import com.calum.traininglog.core.Effort
import com.calum.traininglog.core.ExerciseLog
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.LoggedSet
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.TreadmillLog
import com.calum.traininglog.core.TreadmillStyle
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/**
 * Realistic training history for the screenshot harness. Dates are relative to "today" so the screens always
 * look current: eight weeks back, a three-week gap (illness / travel), then a return with a streak running.
 */
object Fixtures {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private var n = 0

    private fun sets(kg: Double, reps: List<Int>, target: Int, rir: List<Int?>) =
        reps.mapIndexed { i, r -> LoggedSet(kg, r, target, rir.getOrNull(i)) }

    private fun gym(
        daysAgo: Int, today: LocalDate, label: String, squatKg: Double, hinge: String, hingeKg: Double,
        calfKg: Double, upperId: String, upperKg: Double, squatReps: List<Int> = listOf(5, 5, 5),
        miss: com.calum.traininglog.core.MissReason? = null, deload: Boolean = false, minutes: Int = 62,
    ) = SessionRecord(
        id = "fx-${n++}", date = today.minusDays(daysAgo.toLong()), type = SessionType.GYM,
        minutesAvailable = 75, minutesActual = minutes, feeling = Feeling.GOOD, dayLabel = label, deload = deload,
        exercises = listOf(
            ExerciseLog("squat", sets(squatKg, squatReps, 5, listOf(3, 2, 1)), miss),
            ExerciseLog(hinge, sets(hingeKg, listOf(8, 8), 8, listOf(3, 2))),
            ExerciseLog("calf", sets(calfKg, listOf(12, 12, 11), 12, listOf(3, 2, 1))),
            ExerciseLog(upperId, sets(upperKg, listOf(10, 9, 8), 8, listOf(2, 1, 0))),
        ),
    )

    private fun treadmill(daysAgo: Int, today: LocalDate, style: TreadmillStyle, minutes: Int, effort: Effort?) =
        SessionRecord(
            id = "fx-${n++}", date = today.minusDays(daysAgo.toLong()), type = SessionType.TREADMILL,
            minutesAvailable = minutes, minutesActual = minutes, feeling = Feeling.GOOD,
            treadmill = TreadmillLog(style, minutes * 60, minutes * 60, effort),
        )

    private fun light(daysAgo: Int, today: LocalDate, type: SessionType, minutes: Int, note: String?) =
        SessionRecord(
            id = "fx-${n++}", date = today.minusDays(daysAgo.toLong()), type = type,
            minutesAvailable = minutes, minutesActual = minutes, feeling = Feeling.OKAY, note = note,
        )

    /** Eight weeks of history with a 3-week gap and a return (squat restarts at ~90%). Newest session 2 days ago. */
    fun busyRecords(today: LocalDate = LocalDate.now()): List<SessionRecord> {
        n = 0
        return listOf(
            gym(56, today, "Squat + Deadlift", 80.0, "rdl", 70.0, 60.0, "lat_pulldown", 40.0),
            treadmill(54, today, TreadmillStyle.STEADY, 40, Effort.JUST_RIGHT),
            gym(52, today, "Squat + Romanian deadlift", 82.5, "deadlift", 100.0, 62.5, "db_bench", 20.0),
            treadmill(49, today, TreadmillStyle.PYRAMID, 45, Effort.TOO_EASY),
            gym(47, today, "Squat + Deadlift", 85.0, "rdl", 72.5, 65.0, "cable_row", 42.5),
            light(45, today, SessionType.CORE, 15, "Dead bugs and planks"),
            gym(42, today, "Squat + Romanian deadlift", 87.5, "deadlift", 105.0, 65.0, "machine_shoulder", 30.0),
            treadmill(40, today, TreadmillStyle.INTERVALS, 35, Effort.JUST_RIGHT),
            gym(38, today, "Squat + Deadlift", 90.0, "rdl", 75.0, 67.5, "lat_pulldown", 42.5, squatReps = listOf(5, 5, 4), miss = com.calum.traininglog.core.MissReason.AT_LIMIT),
            // 3-week gap (illness) ...
            light(15, today, SessionType.HIKE, 120, "Back on the hills, knee fine"),
            gym(13, today, "Squat + Calf", 80.0, "rdl", 60.0, 55.0, "db_row", 20.0, minutes = 48),
            treadmill(11, today, TreadmillStyle.LADDER, 50, Effort.JUST_RIGHT),
            gym(9, today, "Squat + Deadlift", 85.0, "deadlift", 95.0, 60.0, "cable_row", 40.0),
            treadmill(7, today, TreadmillStyle.LONG_INTERVALS, 70, Effort.TOO_HARD),
            gym(5, today, "Squat + Romanian deadlift", 90.0, "rdl", 75.0, 65.0, "machine_chest", 40.0),
            treadmill(3, today, TreadmillStyle.LONG_ROLLING, 65, Effort.JUST_RIGHT),
            gym(2, today, "Squat + Deadlift", 92.5, "deadlift", 105.0, 67.5, "lat_pulldown", 45.0),
        )
    }

    fun busyProfile(today: LocalDate = LocalDate.now()) = Profile(
        // Tomorrow is a planned rest day; Sundays too.
        restDates = setOf(today.plusDays(1)),
        restWeekdays = setOf(java.time.DayOfWeek.SUNDAY),
    )

    /** Busy profile plus one running block (week 4 of 6, squat 85 -> 100 kg) and one finished block. */
    fun blockProfile(today: LocalDate = LocalDate.now(), withFinished: Boolean = true, withCurrent: Boolean = true): Profile {
        val finished = com.calum.traininglog.core.TrainingBlock(
            id = "fx-b1", name = "Rebuild block", startDate = today.minusWeeks(11), weeks = 6,
            headline = com.calum.traininglog.core.BlockTarget("squat", 90.0, 80.0),
            extras = listOf(com.calum.traininglog.core.ExtraGoal(com.calum.traininglog.core.ExtraKind.TOTAL_SESSIONS, 10)),
        )
        val current = com.calum.traininglog.core.TrainingBlock(
            id = "fx-b2", name = "Squat 100", startDate = today.minusWeeks(3), weeks = 6,
            headline = com.calum.traininglog.core.BlockTarget("squat", 100.0, 85.0),
            extras = listOf(
                com.calum.traininglog.core.ExtraGoal(com.calum.traininglog.core.ExtraKind.SESSIONS_PER_WEEK, 3),
                com.calum.traininglog.core.ExtraGoal(com.calum.traininglog.core.ExtraKind.TREADMILL_MINUTES, 240),
            ),
        )
        return busyProfile(today).copy(
            blocks = com.calum.traininglog.core.BlockState(listOfNotNull(finished.takeIf { withFinished }, current.takeIf { withCurrent })),
        )
    }

    /** Write [records]/[profile] where [Store] reads them; call BEFORE constructing the ViewModel. */
    fun seed(ctx: Context, records: List<SessionRecord>, profile: Profile = Profile()) {
        File(ctx.filesDir, "training-data.json")
            .writeText(json.encodeToString(AppData.serializer(), AppData(records, profile)))
    }
}
