package com.calum.traininglog.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class Scenario { CONSERVATIVE, EXPECTED, BEST }

data class HeadlineTarget(val exerciseId: String, val targetKg: Double)

/**
 * Working weight for one lift at the end of a future week.
 * [weightKg] is the weight the next session would load (rounded to 2.5 kg).
 * [sets] is the planned set count for that week's sessions (fewer in a deload week).
 */
data class WeekPoint(val weekOffset: Int, val weightKg: Double, val sets: Int, val deload: Boolean)

/** [weeks] starts with week 0 (the weight you would load next) followed by weeks 1..N. */
data class ScenarioForecast(
    val scenario: Scenario,
    val weeks: List<WeekPoint>,
    /** Earliest week offset at which the headline target is reached; null if not within the horizon or not this lift. */
    val weeksToTarget: Int?,
)

data class LiftForecast(
    val exerciseId: String,
    val name: String,
    /** Most recent logged working weight, or null with no history. */
    val lastLoggedKg: Double?,
    /** Weight the next session would load now (after the restart rule, if it applies). */
    val baselineKg: Double,
    /** True when a 21+ day gap means the ~90% restart rule sets the baseline. */
    val restartApplied: Boolean,
    /** Average exposures per week given the A/B pattern (squat is on both days). */
    val exposuresPerWeek: Double,
    val scenarios: Map<Scenario, ScenarioForecast>,
)

data class TreadmillWeek(val weekOffset: Int, val plannedMinutes: Int, val cumulativeMinutes: Int)

data class TreadmillForecast(
    val minutesPerSession: Int,
    val sessionsPerWeek: Int,
    val weeklyMinutes: Int,
    /** How many recent treadmill sessions the average is based on (0 means the default was used). */
    val basedOnSessions: Int,
    val weeks: List<TreadmillWeek>,
)

data class Milestone(val weekOffset: Int, val text: String)

data class LiftOutlook(val exerciseId: String, val name: String, val fromKg: Double, val toKg: Double)

data class ForecastOutlook(
    val weekStart: LocalDate,
    val gymDone: Int,
    val gymTarget: Int,
    val gymStretch: Int,
    val treadmillDone: Int,
    val treadmillTarget: Int,
    val treadmillStretch: Int,
    val targetMet: Boolean,
    /** Expected-scenario effect of completing the week's gym target, per lift. */
    val lifts: List<LiftOutlook>,
    val summary: String,
    val liftLines: List<String>,
)

data class HeadlineForecast(val target: HeadlineTarget, val weeksToTarget: Map<Scenario, Int?>)

data class Forecast(
    val today: LocalDate,
    val horizonWeeks: Int,
    val lifts: List<LiftForecast>,
    val treadmill: TreadmillForecast,
    val headline: HeadlineForecast?,
    val milestones: List<Milestone>,
    val weekOutlook: ForecastOutlook,
) {
    fun lift(id: String): LiftForecast? = lifts.firstOrNull { it.exerciseId == id }
}

/**
 * Long-term projection: "what should I expect if I hit my targets each week?"
 *
 * Assumptions (estimates only, not promises):
 * - Weights come from SIMULATING [Progression.next]: each simulated session is logged as
 *   all reps hit; RIR 2 ("progress") makes Progression add the exercise increment, RIR 1
 *   ("hold") makes it repeat the weight. The rules are not duplicated here.
 * - Start point: the next prescription from real history (so the ~90% restart after a 21+
 *   day gap applies), or the exercise's `startKg` with no history.
 * - Scenarios differ only by the share of sessions that earn an increase, spread evenly:
 *   CONSERVATIVE 50%, EXPECTED 70%, BEST 100%. Lower rates never overtake higher ones.
 * - Week 1 is the current Mon-Sun week and only counts the gym sessions still to do; week N
 *   is N-1 weeks later. Week 0 is "now".
 * - Gym sessions alternate the hinge (continuing from whichever of deadlift or RDL was logged
 *   last, RDL first), each with squat and calf, so squat gets about twice the exposures of
 *   rdl or deadlift.
 * - Upper-body accessories use the builder's own rule: each gym session takes the 2 of the 4
 *   movement groups trained least recently (so each group appears about every second session),
 *   using the exercise last logged in that group, else the library default. Their weight is an
 *   estimate of double progression (reps 8 to 12, then add weight): an "earned" session moves one
 *   rep up, a held one repeats; the scenario hit-rates are the same shares as for the main lifts.
 *   Bodyweight-only exercises (0 kg) are not forecast.
 * - Every 6th week (6, 12, ...) is a deload in every scenario: loads stay the same, sets
 *   drop to [Progression.deloadSets], and no increase is earned.
 * - Weights are rounded to the nearest 2.5 kg with [roundTo]. No injuries, illness, or missed
 *   sessions are modelled beyond the scenario hit-rates; real life will wobble, and that is fine.
 * - Treadmill is only planned minutes (average of recent sessions, default 40); no fitness
 *   outcome is predicted.
 */
object Projection {
    const val DELOAD_EVERY_WEEKS = 6
    const val DEFAULT_TREADMILL_MIN = 40
    const val GOOD_GYM = 2
    const val GOOD_TREADMILL = 3
    private const val RECENT_TREADMILL_DAYS = 56L
    private const val RECENT_TREADMILL_COUNT = 6

    private val mainLifts = listOf("squat", "deadlift", "rdl", "calf")
    private val hitPercent = mapOf(Scenario.CONSERVATIVE to 50, Scenario.EXPECTED to 70, Scenario.BEST to 100)

    fun forecast(
        records: List<SessionRecord>,
        today: LocalDate,
        weeks: Int = 12,
        gymPerWeek: Int? = null,
        treadmillPerWeek: Int? = null,
        headlineTarget: HeadlineTarget? = null,
        settings: UserSettings = UserSettings(),
    ): Forecast {
        val horizon = weeks.coerceAtLeast(1)
        val gymN = (gymPerWeek ?: settings.gymTarget).coerceAtLeast(0)
        val tmN = (treadmillPerWeek ?: settings.treadmillTarget).coerceAtLeast(0)
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val inWeek = records.filter { !it.date.isBefore(weekStart) && !it.date.isAfter(weekStart.plusDays(6)) }
        val gymDone = inWeek.count { it.type == SessionType.GYM }
        val tmDone = inWeek.count { it.type == SessionType.TREADMILL }

        val schedule = buildSchedule(records, horizon, gymN, gymDone, settings.deloadWeeks)

        val lifts = mainLifts.map { id ->
            liftForecast(ExerciseLibrary.get(id), records, today, weekStart, schedule, headlineTarget, settings)
        } + GymBuilder.upperRotation.mapNotNull { m -> upperForecast(m, records, today, schedule, settings) }
        val treadmill = treadmillForecast(records, today, horizon, tmN)
        val headline = headlineTarget?.let { t ->
            lifts.firstOrNull { it.exerciseId == t.exerciseId }?.let { lf ->
                HeadlineForecast(t, Scenario.values().associateWith { lf.scenarios.getValue(it).weeksToTarget })
            }
        }
        val outlook = weekOutlook(weekStart, gymDone, gymN, tmDone, tmN, lifts)
        return Forecast(today, horizon, lifts, treadmill, headline, milestones(lifts, headline, horizon, settings.deloadWeeks), outlook)
    }

    // ---- schedule ----

    private class ScheduledWeek(val offset: Int, val days: List<String>, val deload: Boolean, val upper: List<List<String>> = emptyList())

    private fun buildSchedule(records: List<SessionRecord>, horizon: Int, gymN: Int, gymDone: Int, deloadEvery: Int = DELOAD_EVERY_WEEKS): List<ScheduledWeek> {
        var label = if (GymBuilder.nextHinge(records).id == "deadlift") "B" else "A"
        // Same rotation as GymBuilder.nextUpperMovements: take the 2 stalest, then they go to the back.
        val queue = ArrayDeque(GymBuilder.nextUpperMovements(records, GymBuilder.upperRotation.size))
        return (1..horizon).map { w ->
            val count = if (w == 1) (gymN - gymDone).coerceAtLeast(0) else gymN
            val days = List(count) { label.also { label = if (label == "A") "B" else "A" } }
            val upper = List(count) { List(minOf(2, queue.size)) { queue.removeFirst() }.also { queue.addAll(it) } }
            ScheduledWeek(w, days, w % deloadEvery == 0, upper)
        }
    }

    private fun dayPattern(label: String) =
        if (label == "A") listOf("squat", "rdl", "calf") else listOf("squat", "deadlift", "calf")

    private fun exposures(ex: Exercise, days: List<String>) = days.count { ex.id in dayPattern(it) }

    // ---- lifts ----

    private fun liftForecast(
        ex: Exercise,
        records: List<SessionRecord>,
        today: LocalDate,
        weekStart: LocalDate,
        schedule: List<ScheduledWeek>,
        headline: HeadlineTarget?,
        settings: UserSettings = UserSettings(),
    ): LiftForecast {
        val history = GymBuilder.history(ex.id, records)
        val baseline = Progression.next(ex, history, today, settings = settings).weightKg
        val last = history.firstOrNull()
        val lastKg = last?.log?.sets?.maxOf { it.weightKg }
        val restart = last != null && ChronoUnit.DAYS.between(last.date, today) >= settings.restartAfterDays
        val target = headline?.takeIf { it.exerciseId == ex.id }?.targetKg

        val scenarios = Scenario.values().associateWith { s ->
            val pts = simulate(ex, baseline, schedule, today, weekStart, hitPercent.getValue(s), settings)
            val eta = target?.let { t -> pts.firstOrNull { it.weightKg >= t }?.weekOffset }
            ScenarioForecast(s, pts, eta)
        }
        val avgExposures = schedule.drop(1).ifEmpty { schedule }.let { ws ->
            if (ws.isEmpty()) 0.0 else ws.sumOf { exposures(ex, it.days) }.toDouble() / ws.size
        }
        return LiftForecast(ex.id, ex.name, lastKg, roundTo(baseline, 2.5), restart, avgExposures, scenarios)
    }

    /** Upper-body accessory in movement group [movement]; null for bodyweight-only exercises. */
    private fun upperForecast(movement: String, records: List<SessionRecord>, today: LocalDate, schedule: List<ScheduledWeek>, settings: UserSettings = UserSettings()): LiftForecast? {
        val ex = settings.resolve(GymBuilder.preferredFor(movement, records))
        val history = GymBuilder.history(ex.id, records)
        val start = Progression.next(ex, history, today, settings = settings)
        if (start.weightKg <= 0.0) return null
        val last = history.firstOrNull()
        val restart = last != null && ChronoUnit.DAYS.between(last.date, today) >= 21
        val scenarios = Scenario.values().associateWith { s ->
            ScenarioForecast(s, simulateAccessory(ex, start, movement, schedule, today, hitPercent.getValue(s), settings), null)
        }
        val avg = schedule.drop(1).ifEmpty { schedule }.let { ws ->
            if (ws.isEmpty()) 0.0 else ws.sumOf { w -> w.upper.count { movement in it } }.toDouble() / ws.size
        }
        return LiftForecast(ex.id, ex.name, last?.log?.sets?.maxOf { it.weightKg }, roundTo(start.weightKg, 2.5), restart, avg, scenarios)
    }

    private fun simulateAccessory(
        ex: Exercise,
        start: Prescription,
        movement: String,
        schedule: List<ScheduledWeek>,
        today: LocalDate,
        percent: Int,
        settings: UserSettings = UserSettings(),
    ): List<WeekPoint> {
        var rx = start
        var hist = emptyList<HistoryEntry>()
        var k = 0
        var day = 0L // synthetic consecutive days: only the order matters, so no restart rule fires
        val points = mutableListOf(WeekPoint(0, roundTo(rx.weightKg, 2.5), ex.sets, false))
        for (week in schedule) {
            if (!week.deload) {
                repeat(week.upper.count { movement in it }) {
                    val progress = (k + 1) * percent / 100 > k * percent / 100
                    k++
                    val reps = if (progress) rx.reps else rx.reps - 1 // a held session falls one rep short
                    val date = today.plusDays(day++)
                    val set = LoggedSet(rx.weightKg, reps, rx.reps, rir = 2)
                    hist = listOf(HistoryEntry(date, ExerciseLog(ex.id, List(ex.sets) { set }))) + hist
                    rx = Progression.next(ex, hist, date.plusDays(1), settings = settings)
                }
            }
            val sets = if (week.deload) Progression.deloadSets(ex.sets) else ex.sets
            points += WeekPoint(week.offset, roundTo(rx.weightKg, 2.5), sets, week.deload)
        }
        return points
    }

    /** Simulates sessions through [Progression.next]; returns week 0 plus one point per week. */
    private fun simulate(
        ex: Exercise,
        baseline: Double,
        schedule: List<ScheduledWeek>,
        today: LocalDate,
        weekStart: LocalDate,
        percent: Int,
        settings: UserSettings = UserSettings(),
    ): List<WeekPoint> {
        var weight = baseline
        var hist = emptyList<HistoryEntry>() // synthetic, newest first
        var k = 0 // non-deload exposures so far
        val points = mutableListOf(WeekPoint(0, roundTo(weight, 2.5), ex.sets, false))
        for (week in schedule) {
            val n = exposures(ex, week.days)
            if (!week.deload) {
                repeat(n) { i ->
                    val progress = (k + 1) * percent / 100 > k * percent / 100
                    k++
                    val date = maxOf(today, weekStart.plusDays((week.offset - 1) * 7L + i * 3L))
                    val set = LoggedSet(weight, ex.repsLow, ex.repsLow, rir = if (progress) 2 else 1)
                    hist = listOf(HistoryEntry(date, ExerciseLog(ex.id, List(ex.sets) { set }))) + hist
                    weight = Progression.next(ex, hist, date.plusDays(1), settings = settings).weightKg
                }
            }
            val sets = if (week.deload) Progression.deloadSets(ex.sets) else ex.sets
            points += WeekPoint(week.offset, roundTo(weight, 2.5), sets, week.deload)
        }
        return points
    }

    // ---- treadmill ----

    private fun treadmillForecast(records: List<SessionRecord>, today: LocalDate, horizon: Int, perWeek: Int): TreadmillForecast {
        val recent = records
            .filter { it.type == SessionType.TREADMILL && !it.date.isAfter(today) }
            .filter { ChronoUnit.DAYS.between(it.date, today) <= RECENT_TREADMILL_DAYS }
            .sortedByDescending { it.date }
            .take(RECENT_TREADMILL_COUNT)
        val minutes = recent.map { r -> r.treadmill?.completedSec?.takeIf { it > 0 }?.let { it / 60.0 } ?: r.minutesActual.toDouble() }
            .filter { it > 0 }
        val perSession = if (minutes.isEmpty()) DEFAULT_TREADMILL_MIN
        else roundTo(minutes.average(), 5.0).toInt().coerceAtLeast(5)
        val weekly = perSession * perWeek
        val weeks = (1..horizon).map { TreadmillWeek(it, weekly, weekly * it) }
        return TreadmillForecast(perSession, perWeek, weekly, minutes.size, weeks)
    }

    // ---- milestones and outlook ----

    private fun fmt(kg: Double) = if (kg % 1.0 == 0.0) kg.toInt().toString() else kg.toString()

    private fun milestones(lifts: List<LiftForecast>, headline: HeadlineForecast?, horizon: Int, deloadEvery: Int = DELOAD_EVERY_WEEKS): List<Milestone> {
        val out = mutableListOf<Milestone>()
        fun expected(id: String, w: Int) =
            lifts.first { it.exerciseId == id }.scenarios.getValue(Scenario.EXPECTED).weeks.first { it.weekOffset == w }
        for (w in listOf(4, 8, 12).filter { it <= horizon }) {
            out += Milestone(w, "Week $w: squat about ${fmt(expected("squat", w).weightKg)} kg (expected)")
        }
        val lastW = horizon
        if (lastW !in listOf(4, 8, 12)) {
            out += Milestone(lastW, "Week $lastW: squat about ${fmt(expected("squat", lastW).weightKg)} kg (expected)")
        }
        if (horizon >= 8) {
            out += Milestone(8, "Week 8: deadlift about ${fmt(expected("deadlift", 8).weightKg)} kg (expected)")
        }
        val gains = lifts.filter { it.exerciseId !in mainLifts }.map { lf ->
            lf.scenarios.getValue(Scenario.EXPECTED).weeks.let { it.last().weightKg - it.first().weightKg }
        }
        if (gains.isNotEmpty() && gains.max() > 0.0) {
            out += Milestone(horizon, "Week $horizon: upper-body lifts up about ${fmt(gains.filter { it > 0 }.min())} to ${fmt(gains.max())} kg each (expected)")
        }
        if (horizon >= deloadEvery) {
            out += Milestone(deloadEvery, "Week $deloadEvery: a planned lighter week, same loads with fewer sets")
        }
        headline?.let { h ->
            val name = ExerciseLibrary.nameOf(h.target.exerciseId).lowercase()
            val eta = h.weeksToTarget.getValue(Scenario.EXPECTED)
            out += if (eta != null) {
                Milestone(eta, "Week $eta: $name about ${fmt(h.target.targetKg)} kg (expected pace, your target)")
            } else {
                Milestone(horizon, "Week $horizon: $name ${fmt(h.target.targetKg)} kg is past this window at the expected pace; the best case ${
                    h.weeksToTarget.getValue(Scenario.BEST)?.let { "gets there around week $it" } ?: "is also beyond it"
                }")
            }
        }
        return out.sortedBy { it.weekOffset }
    }

    private fun weekOutlook(
        weekStart: LocalDate,
        gymDone: Int,
        gymN: Int,
        tmDone: Int,
        tmN: Int,
        lifts: List<LiftForecast>,
    ): ForecastOutlook {
        val gymStretch = gymN + 1
        val tmStretch = tmN + 1
        val met = gymDone >= gymN && tmDone >= tmN
        val liftOutlooks = lifts.map { lf ->
            val pts = lf.scenarios.getValue(Scenario.EXPECTED).weeks
            LiftOutlook(lf.exerciseId, lf.name, pts[0].weightKg, pts[1].weightKg)
        }
        val summary = when {
            gymDone >= gymStretch && tmDone >= tmStretch -> "Stretch reached: $gymDone gym and $tmDone treadmill. Plenty; rest is part of the plan."
            met -> "A good week is done: $gymDone of $gymN gym and $tmDone of $tmN treadmill. Anything extra is a bonus."
            else -> "So far: $gymDone of $gymN gym and $tmDone of $tmN treadmill. ${(gymN - gymDone).coerceAtLeast(0)} gym and ${(tmN - tmDone).coerceAtLeast(0)} treadmill would make a good week; $gymStretch gym and $tmStretch treadmill is the stretch."
        }
        val remainingGym = (gymN - gymDone).coerceAtLeast(0)
        val lines = liftOutlooks.filter { it.exerciseId in setOf("squat", "deadlift", "rdl") }.map { o ->
            val prefix = if (remainingGym > 0) "Hit $gymN gym sessions this week and" else "With $gymN gym sessions this week,"
            if (o.toKg > o.fromKg) {
                "$prefix ${o.name.lowercase()} moves from ${fmt(o.fromKg)} to about ${fmt(o.toKg)} kg"
            } else {
                "$prefix ${o.name.lowercase()} stays about ${fmt(o.fromKg)} kg (consolidating)"
            }
        }
        return ForecastOutlook(weekStart, gymDone, gymN, gymStretch, tmDone, tmN, tmStretch, met, liftOutlooks, summary, lines)
    }
}
