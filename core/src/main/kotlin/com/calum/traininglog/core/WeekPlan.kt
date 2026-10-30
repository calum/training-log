package com.calum.traininglog.core

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs

enum class DayStatus { DONE, TODAY, OPEN, REST, REST_BUT_TRAINED, PAST_EMPTY }

data class DayPlan(
    val date: LocalDate,
    val status: DayStatus,
    val done: List<SessionType>,
    val suggested: SessionType?,
)

object RestDays {
    /** Planned rest (weekday or one-off date), ignoring any "train anyway" override. */
    fun isPlannedRest(profile: Profile, date: LocalDate): Boolean =
        date.dayOfWeek in profile.restWeekdays || date in profile.restDates

    /** Effective rest day: planned rest unless the user chose to train anyway. */
    fun isRestDay(profile: Profile, date: LocalDate): Boolean =
        isPlannedRest(profile, date) && date !in profile.workoutOverrides

    fun toggleWeekday(profile: Profile, day: DayOfWeek): Profile =
        profile.copy(
            restWeekdays = if (day in profile.restWeekdays) profile.restWeekdays - day else profile.restWeekdays + day,
        )

    /** Toggles whether [date] is a rest day: a working day becomes a one-off rest day, a rest day becomes workable. */
    fun toggleDate(profile: Profile, date: LocalDate): Profile =
        if (isRestDay(profile, date)) {
            profile.copy(
                restDates = profile.restDates - date,
                workoutOverrides = if (date.dayOfWeek in profile.restWeekdays) profile.workoutOverrides + date else profile.workoutOverrides - date,
            )
        } else {
            profile.copy(restDates = profile.restDates + date, workoutOverrides = profile.workoutOverrides - date)
        }

    /** "I suddenly have free time": train on a rest day. */
    fun trainAnyway(profile: Profile, date: LocalDate): Profile =
        if (isPlannedRest(profile, date)) profile.copy(workoutOverrides = profile.workoutOverrides + date) else profile
}

enum class WeekOutlook { ON_TRACK, DOABLE_WITH_PUSH, BONUS_WEEK }

data class WeekView(
    val days: List<DayPlan>,
    val gymDone: Int,
    val treadmillDone: Int,
    val gymLeft: Int,
    val treadmillLeft: Int,
    val gymStretchLeft: Int,
    val treadmillStretchLeft: Int,
    val openDaysLeft: Int,
    val outlook: WeekOutlook,
    val gymTarget: Int = Recommender.GYM_TARGET,
    val treadmillTarget: Int = Recommender.TREADMILL_TARGET,
) {
    val goodWeekTotal: Int get() = gymTarget + treadmillTarget
    val goodWeekDone: Int
        get() = minOf(gymDone, gymTarget) + minOf(treadmillDone, treadmillTarget)

    val outlookLabel: String
        get() = when (outlook) {
            WeekOutlook.ON_TRACK -> "on track"
            WeekOutlook.DOABLE_WITH_PUSH -> "doable with a push"
            WeekOutlook.BONUS_WEEK -> "optional: bonus week"
        }

    /** Positive framing only; never mentions misses. */
    fun headline(): String {
        if (goodWeekDone >= goodWeekTotal) return "Good week reached: $goodWeekDone of $goodWeekTotal done. Anything more is a bonus."
        val days = when (openDaysLeft) {
            0 -> return "$goodWeekDone of $goodWeekTotal done. Rest up, fresh week soon."
            1 -> "1 open day left"
            else -> "$openDaysLeft open days left"
        }
        return "$goodWeekDone of $goodWeekTotal done, $days"
    }
}

object WeekPlanner {
    const val STRETCH_GYM = 3
    const val STRETCH_TREADMILL = 4

    fun plan(records: List<SessionRecord>, profile: Profile, today: LocalDate, settings: UserSettings = profile.settings): WeekView {
        val range = Stats.weekOf(today)
        val start = range.start
        val byDate = records.filter { it.date in range }.groupBy { it.date }
        val dates = (0L..6L).map { start.plusDays(it) }

        val gymDone = records.count { it.date in range && it.type == SessionType.GYM }
        val treadDone = records.count { it.date in range && it.type == SessionType.TREADMILL }
        val gymLeft = maxOf(0, settings.gymTarget - gymDone)
        val treadLeft = maxOf(0, settings.treadmillTarget - treadDone)

        // Days that can still take a session: today onwards, not resting, nothing logged yet.
        val open = dates.filter { it >= today && !RestDays.isRestDay(profile, it) && byDate[it].isNullOrEmpty() }
        val restLeft = dates.count { it >= today && RestDays.isRestDay(profile, it) }

        val gymDays = mutableListOf<LocalDate>()
        val doneGymDays = byDate.filter { (_, v) -> v.any { it.type == SessionType.GYM } }.keys
        repeat(gymLeft) {
            val taken = doneGymDays + gymDays
            fun gap(d: LocalDate) = taken.minOfOrNull { abs(it.toEpochDay() - d.toEpochDay()) } ?: 99L
            val pick = open.filter { it !in gymDays && gap(it) >= 2 }
                .maxWithOrNull(compareBy<LocalDate> { gap(it) }.thenByDescending { it })
            if (pick != null) gymDays += pick
        }
        val treadDays = open.filter { it !in gymDays }.take(treadLeft)

        val plans = dates.map { d ->
            val done = byDate[d].orEmpty().map { it.type }
            val suggested = when (d) {
                in gymDays -> SessionType.GYM
                in treadDays -> SessionType.TREADMILL
                else -> null
            }
            val status = when {
                done.isNotEmpty() -> if (RestDays.isPlannedRest(profile, d)) DayStatus.REST_BUT_TRAINED else DayStatus.DONE
                RestDays.isRestDay(profile, d) -> DayStatus.REST
                d < today -> DayStatus.PAST_EMPTY
                d == today -> DayStatus.TODAY
                else -> DayStatus.OPEN
            }
            DayPlan(d, status, done, suggested)
        }

        val needed = gymLeft + treadLeft
        val outlook = when {
            needed == 0 -> WeekOutlook.BONUS_WEEK
            open.size >= needed -> WeekOutlook.ON_TRACK
            open.size + restLeft >= needed -> WeekOutlook.DOABLE_WITH_PUSH
            else -> WeekOutlook.BONUS_WEEK
        }
        return WeekView(
            plans, gymDone, treadDone, gymLeft, treadLeft,
            maxOf(0, settings.effectiveGymStretch - gymDone), maxOf(0, settings.effectiveTreadmillStretch - treadDone),
            open.size, outlook, settings.gymTarget, settings.treadmillTarget,
        )
    }
}
