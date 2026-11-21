package com.calum.traininglog.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The one place that answers "what should I do next?".
 *
 * Two planners exist: [RacePlanner] (the Hyrox plan) and the older weekly gym / treadmill targets ([WeekPlanner] via
 * [NextUp]). They know nothing of each other, so every screen, notification or widget that suggests a session MUST
 * ask this object instead of picking one itself; otherwise two cards can say two different things.
 * `CoachRulesTest` fails the build if anything else calls [NextUp.describe].
 *
 * While the race plan is on it is the only source. Without one the weekly-target text is used until the maintenance
 * plan replaces it (see docs/unified-design.md).
 */
object Coach {
    private val shortDate = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    /** The race plan for [today], or null when it is switched off. Building it is not free: build once per screen. */
    fun racePlan(profile: Profile, records: List<SessionRecord>, today: LocalDate): RacePlan? =
        profile.settings.racePlan.takeIf { it.enabled }?.let { RacePlanner.build(it, records, today, profile) }

    /** Today's planned session if still to do, else the next open one this week; null when nothing is left. */
    fun nextPlanned(plan: RacePlan, records: List<SessionRecord>, today: LocalDate): PlannedSession? {
        val w = RacePlanner.thisWeek(plan, today) ?: return null
        val done = RacePlanner.doneSessions(w, records, today)
        return RacePlanner.counted(w).filter { it !in done && it.date >= today }.minByOrNull { it.date }
    }

    /** The Home "Next up" line. Pass the already built [plan] (null: the race plan is off). Null: show nothing. */
    fun nextUpText(plan: RacePlan?, records: List<SessionRecord>, profile: Profile, today: LocalDate): String? {
        if (plan == null) {
            val view = WeekPlanner.plan(records, profile, today, profile.settings)
            return NextUp.describe(records, view, today)
        }
        val next = nextPlanned(plan, records, today) ?: return null
        return if (next.date == today) "From your race plan: ${next.title}, ${next.minutes} min."
        else "${next.title}, ${next.minutes} min, ${next.date.format(shortDate)}."
    }

    /** How many of this week's planned sessions are done, and how many were planned (skipped and rest days excluded). */
    data class WeekProgress(val done: Int, val total: Int)

    /** Null when there is no race plan or no plan week for [today] (before or after it). */
    fun weekProgress(plan: RacePlan?, records: List<SessionRecord>, today: LocalDate): WeekProgress? {
        val w = RacePlanner.thisWeek(plan ?: return null, today) ?: return null
        return WeekProgress(RacePlanner.doneSessions(w, records, today).size, RacePlanner.counted(w).size)
    }

    /**
     * The good week for the Home ring: the plan's sessions this week while a race plan is on, the weekly targets
     * otherwise. A plan week with nothing planned counts as reached, like a week with no targets.
     */
    fun goodWeek(plan: RacePlan?, records: List<SessionRecord>, profile: Profile, today: LocalDate): GoodWeek {
        val p = weekProgress(plan, records, today)
        if (p == null) {
            val view = WeekPlanner.plan(records, profile, today, profile.settings)
            val ring = Rewards.ring(records.sortedBy { it.date }, today, profile.settings)
            return GoodWeek(view.goodWeekDone, view.goodWeekTotal, ring.progress, ring.bonus, fromPlan = false)
        }
        val range = Stats.weekOf(today)
        val loggedThisWeek = records.count { it.date in range && it.date <= today && !it.isKneeCare }
        val extra = (loggedThisWeek - p.done).coerceAtLeast(0)
        val progress = if (p.total == 0) 1.0 else (p.done.toDouble() / p.total).coerceIn(0.0, 1.0)
        val bonus = (extra.toDouble() / maxOf(1, p.total)).coerceIn(0.0, 1.0)
        return GoodWeek(p.done, p.total, progress, bonus, fromPlan = true)
    }

    fun nextUpText(records: List<SessionRecord>, profile: Profile, today: LocalDate): String? =
        nextUpText(racePlan(profile, records, today), records, profile, today)
}
