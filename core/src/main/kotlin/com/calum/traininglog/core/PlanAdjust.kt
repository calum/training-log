package com.calum.traininglog.core

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Flexibility for the race plan: moving a session within its week, skipping one without guilt, and asking for a
 * session that fits the time available. These are design choices for a parent whose time and energy are unpredictable,
 * not research findings (see docs/hyrox-plan-evidence.md, 'Flexibility and re-planning').
 *
 * Everything is stored as small facts in [Profile.planAdjustments] and re-applied by [RacePlanner.build] on top of the
 * generated week, so the result is always deterministic and undo is simply deleting the fact.
 */
enum class SkipReason(val label: String) {
    UNWELL("Not feeling well"),
    NO_TIME("No time"),
    OTHER("Other");

    /** Lower-case wording for a line like 'Skipped: no time.'. */
    val short: String get() = when (this) { UNWELL -> "unwell"; NO_TIME -> "no time"; OTHER -> "other" }

    /** Kind one-liner shown on a skipped session. */
    val line: String get() = when (this) {
        UNWELL -> "Skipped: unwell. Rest up."
        NO_TIME -> "Skipped: no time. No catching up needed."
        OTHER -> "Skipped. That is fine."
    }
}

/** The session of [kind] planned on [fromDate] is done on [toDate] instead (same Monday-Sunday week). [title] is only for display. */
@Serializable
data class PlanMove(
    @Serializable(with = LocalDateSerializer::class) val fromDate: LocalDate,
    @Serializable(with = LocalDateSerializer::class) val toDate: LocalDate,
    val kind: SessionKind,
    val title: String? = null,
)

/** The session of [kind] shown on [date] is skipped; [recordedOn] is the day this was recorded (the re-flow depends on it, not on today). */
@Serializable
data class PlanSkip(
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val kind: SessionKind,
    val title: String? = null,
    val reason: SkipReason = SkipReason.OTHER,
    @Serializable(with = LocalDateSerializer::class) val recordedOn: LocalDate = date,
)

@Serializable
data class PlanAdjustments(
    val moves: List<PlanMove> = emptyList(),
    val skips: List<PlanSkip> = emptyList(),
    /** The replan alert stays quiet until this day (see [PlanHealth.assess]). */
    @Serializable(with = LocalDateSerializer::class) val replanDismissedUntil: LocalDate? = null,
) {
    /** Drops moves and skips older than [KEEP_DAYS] days (and an expired snooze). */
    fun pruned(today: LocalDate): PlanAdjustments {
        val cut = today.minusDays(KEEP_DAYS)
        return copy(
            moves = moves.filter { maxOf(it.fromDate, it.toDate) >= cut },
            skips = skips.filter { it.date >= cut },
            replanDismissedUntil = replanDismissedUntil?.takeIf { it >= today },
        )
    }

    /** Quiet for [DISMISS_DAYS] days from [today]. */
    fun dismissReplan(today: LocalDate): PlanAdjustments = copy(replanDismissedUntil = today.plusDays(DISMISS_DAYS))

    companion object {
        const val KEEP_DAYS = 60L
        const val DISMISS_DAYS = 5L
    }
}

/** One day of a session's week in the Move picker. [hint] is a gentle note (never a block); [blockedReason] explains an unavailable day. */
data class MoveOption(
    val date: LocalDate,
    val allowed: Boolean,
    val isRestDay: Boolean,
    val isCurrent: Boolean,
    val hint: String? = null,
    val blockedReason: String? = null,
)

/** What to do with the minutes available: [primary] is scaled to fit, [extra] is true for a bonus session beyond the plan. */
data class TimeSuggestion(
    val primary: PlannedSession,
    val alternative: PlannedSession?,
    val reason: String,
    val extra: Boolean,
) {
    val isRest: Boolean get() = primary.kind == SessionKind.REST
}

/** Pure helpers behind the [RacePlanner] flexibility API. */
internal object PlanAdjust {
    private fun hard(k: SessionKind) = k == SessionKind.KEY || k == SessionKind.SIM

    /** Shorter minimums used when fitting a session into a short window (the ramp minimums of the planner). */
    private fun minLen(k: SessionKind) = when (k) {
        SessionKind.STRENGTH -> 30; SessionKind.KEY, SessionKind.SIM -> 30; SessionKind.LONG_EASY -> 35; else -> 20
    }

    fun dayName(d: LocalDate): String = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)

    // ------------------------------------------------------------------------------------------ applying

    class Applied(val sessions: List<PlannedSession>, val notes: List<String>)

    private fun movable(s: PlannedSession) = s.kind != SessionKind.REST && !s.isRaceDay && s.skipReason == null

    /** Dates that were turned into one-off training days by a move; the layout ignores their override (see [RacePlanner.build]). */
    fun moveRestTargets(profile: Profile): Set<LocalDate> =
        profile.planAdjustments.moves.map { it.toDate }.filter { RestDays.isPlannedRest(profile, it) }.toSet()

    /**
     * Applies moves, then skips (with their re-flow), to one week's freshly generated [sessions] covering [from]..[to].
     * [profile] must be the layout profile (move-made overrides removed), so rest days still count as rest for the re-flow.
     */
    fun apply(sessions: List<PlannedSession>, from: LocalDate, to: LocalDate, adj: PlanAdjustments, profile: Profile, raceWeek: Boolean): Applied {
        if (adj.moves.isEmpty() && adj.skips.isEmpty()) return Applied(sessions, emptyList())
        var list = sessions
        for (m in adj.moves) {
            if (m.fromDate == m.toDate || m.fromDate !in from..to || m.toDate !in from..to) continue
            val s = list.firstOrNull { it.date == m.fromDate && it.kind == m.kind && movable(it) } ?: continue
            list = list.map { if (it === s) it.copy(date = m.toDate, movedFrom = m.fromDate) else it }
        }
        val notes = mutableListOf<String>()
        for (k in adj.skips) {
            if (k.date !in from..to) continue
            val s = list.firstOrNull { it.date == k.date && it.kind == k.kind && movable(it) } ?: continue
            var dropped = false
            if (k.reason == SkipReason.UNWELL) {
                // Unwell, and the skipped one was not the hard session: the hard session is off this week too.
                val keys = if (hard(s.kind)) emptyList() else list.filter { hard(it.kind) && movable(it) && it !== s && it.date >= k.recordedOn }
                dropped = keys.isNotEmpty()
                list = list.map { if (it in keys) it.copy(skipReason = SkipReason.UNWELL) else it }
                notes += if (dropped) "You were unwell, so the hard session is off this week too. Rest up; the plan carries on, with no catching up."
                else "You were unwell, so rest up. The plan carries on, with no catching up."
            }
            val target = if (raceWeek) null else reflowDay(list, s, k, to, profile)
            list = list.map { if (it === s) it.copy(skipReason = k.reason, reflowedTo = target) else it } +
                listOfNotNull(target?.let { s.copy(date = it, movedFrom = s.date) })
        }
        return Applied(list.sortedBy { it.date }, notes.distinct())
    }

    /**
     * The next free, allowed day for a skipped session, or null when none is left in the week. Free means a training
     * day with nothing else planned; allowed means no strength or hard day next to another strength or hard day. Unwell leaves a rest day first. Deterministic: depends only on the skip, never on today.
     */
    private fun reflowDay(list: List<PlannedSession>, s: PlannedSession, k: PlanSkip, weekEnd: LocalDate, profile: Profile): LocalDate? {
        val earliest = if (k.date >= k.recordedOn) k.date.plusDays(1) else k.recordedOn
        val start = if (k.reason == SkipReason.UNWELL) maxOf(earliest, k.recordedOn.plusDays(2)) else earliest
        val others = list.filter { it !== s && it.skipReason == null && it.kind != SessionKind.REST }
        var d = start
        while (d <= weekEnd) {
            if (!RestDays.isRestDay(profile, d) && others.none { it.date == d } && spacingOk(others, s.kind, d)) return d
            d = d.plusDays(1)
        }
        return null
    }

    /** Two demanding days (strength or hard) back to back, in either order, are avoided when re-flowing. */
    private fun forbiddenPair(before: SessionKind, after: SessionKind): Boolean =
        (before == SessionKind.STRENGTH || hard(before)) && (after == SessionKind.STRENGTH || hard(after))

    private fun spacingOk(others: List<PlannedSession>, kind: SessionKind, d: LocalDate): Boolean =
        others.none { it.date == d.minusDays(1) && forbiddenPair(it.kind, kind) } &&
            others.none { it.date == d.plusDays(1) && forbiddenPair(kind, it.kind) }

    // ------------------------------------------------------------------------------------------ writing facts

    fun addMove(profile: Profile, session: PlannedSession, to: LocalDate, today: LocalDate): Profile {
        val adj = profile.planAdjustments
        var moves = adj.moves
        var from = session.date
        var overrides = profile.workoutOverrides
        // A session that was already moved: replace the old move by one from its original day.
        moves.firstOrNull { it.toDate == session.date && it.kind == session.kind }?.let { old ->
            moves = moves - old
            from = old.fromDate
            if (moves.none { it.toDate == old.toDate } && RestDays.isPlannedRest(profile, old.toDate)) overrides = overrides - old.toDate
        }
        if (from != to) {
            moves = moves + PlanMove(from, to, session.kind, session.title)
            if (RestDays.isRestDay(profile.copy(workoutOverrides = overrides), to)) overrides = overrides + to
        }
        return profile.copy(workoutOverrides = overrides, planAdjustments = adj.copy(moves = moves).pruned(today))
    }

    fun skip(profile: Profile, session: PlannedSession, reason: SkipReason, today: LocalDate): Profile {
        val adj = profile.planAdjustments
        if (adj.skips.any { it.date == session.date && it.kind == session.kind }) return profile
        val k = PlanSkip(session.date, session.kind, session.title, reason, today)
        return profile.copy(planAdjustments = adj.copy(skips = adj.skips + k).pruned(today))
    }

    /** Undoes whatever put [session] where it is: its skip, the skip that re-flowed it here, or its move. */
    fun undo(profile: Profile, session: PlannedSession): Profile {
        val adj = profile.planAdjustments
        if (session.skipReason != null) {
            val k = adj.skips.firstOrNull { it.date == session.date && it.kind == session.kind } ?: return profile
            return profile.copy(planAdjustments = adj.copy(skips = adj.skips - k))
        }
        val from = session.movedFrom ?: return profile
        val m = adj.moves.firstOrNull { it.fromDate == from && it.toDate == session.date && it.kind == session.kind }
        if (m != null) {
            val keep = adj.moves - m
            val overrides = if (keep.none { it.toDate == m.toDate } && RestDays.isPlannedRest(profile, m.toDate)) profile.workoutOverrides - m.toDate else profile.workoutOverrides
            return profile.copy(workoutOverrides = overrides, planAdjustments = adj.copy(moves = keep))
        }
        val k = adj.skips.firstOrNull { it.date == from && it.kind == session.kind } ?: return profile
        return profile.copy(planAdjustments = adj.copy(skips = adj.skips - k))
    }

    /** What changed after a skip: 'Moved to Sunday.' or 'Not this week, no catching up.' (plus 'Rest up.' when unwell). */
    fun skipOutcome(planAfter: RacePlan, skipped: PlannedSession): String {
        val s = planAfter.segmentOf(skipped.date)?.sessions
            ?.firstOrNull { it.date == skipped.date && it.kind == skipped.kind && it.skipReason != null }
        val base = if (s?.reflowedTo != null) "Moved to ${dayName(s.reflowedTo)}." else "Not this week, no catching up."
        return if (s?.skipReason == SkipReason.UNWELL) "$base Rest up." else base
    }

    // ------------------------------------------------------------------------------------------ move options

    fun moveOptions(plan: RacePlan, session: PlannedSession, today: LocalDate, profile: Profile): List<MoveOption> {
        val week = plan.segmentOf(session.date) ?: return emptyList()
        val others = week.sessions.filter { it !== session && it.skipReason == null && it.kind != SessionKind.REST }
        val restEve = week.sessions.filter { it.kind == SessionKind.REST }.map { it.date }.toSet()
        return generateSequence(week.start) { it.plusDays(1) }.takeWhile { it <= week.end }.map { d ->
            val rest = RestDays.isRestDay(profile, d)
            val isCurrent = d == session.date
            val onDay = others.filter { it.date == d }
            val comboOk = onDay.size == 1 && ((session.kind == SessionKind.EASY && onDay[0].kind == SessionKind.STRENGTH) ||
                (session.kind == SessionKind.STRENGTH && onDay[0].kind == SessionKind.EASY))
            val blocked = when {
                isCurrent -> "It is planned for this day already."
                d < today -> "That day has gone."
                d >= plan.race.minusDays(1) -> "Keep the day before the race and race day clear."
                d in restEve -> "Keep this day clear."
                onDay.isNotEmpty() && !comboOk -> "Another session is planned that day."
                else -> null
            }
            val hint = if (blocked != null) null else hintFor(others, session, d, rest, onDay.isNotEmpty(), plan.race)
            MoveOption(d, blocked == null, rest, isCurrent, hint, blocked)
        }.toList()
    }

    private fun hintFor(others: List<PlannedSession>, s: PlannedSession, d: LocalDate, rest: Boolean, doubled: Boolean, race: LocalDate): String? {
        val before = others.filter { it.date == d.minusDays(1) }.map { it.kind }
        val after = others.filter { it.date == d.plusDays(1) }.map { it.kind }
        val hints = mutableListOf<String>()
        if (s.kind == SessionKind.STRENGTH && (SessionKind.STRENGTH in before || SessionKind.STRENGTH in after)) hints += "Two strength days in a row. Fine to do, legs may feel it."
        else if (s.kind == SessionKind.STRENGTH && after.any { hard(it) }) hints += "Strength the day before a hard session. Fine to do, legs may feel it."
        else if (s.kind == SessionKind.STRENGTH && before.any { hard(it) }) hints += "Strength the day after a hard session. Fine to do, legs may feel it."
        else if (hard(s.kind) && (SessionKind.STRENGTH in before || SessionKind.STRENGTH in after)) hints += "A hard session next to a strength day. Fine to do, legs may feel it."
        else if (hard(s.kind) && (before.any { hard(it) } || after.any { hard(it) })) hints += "Two hard days in a row. Fine to do, keep the second one controlled."
        if (doubled) hints += "Two sessions that day (easy and strength). Fine to do, keep the easy one really easy."
        if (hard(s.kind) && ChronoUnit.DAYS.between(d, race) in 1..3) hints += "Close to the race: a hard session is best kept earlier."
        if (rest) hints += "A rest day: moving here makes it a one-off training day."
        return hints.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    // ------------------------------------------------------------------------------------------ time suggestion

    private fun restToday(date: LocalDate) = PlannedSession(
        date, SessionKind.REST, "Rest today", 0, Intensity.EASY, 0, 1..2, listOf(Modality.REST),
        listOf("Rest, water, food and sleep. A short gentle walk if you feel like it.", "The plan waits for you; nothing needs catching up."),
        "Rest is when training turns into fitness, and resting when ill is how you come back sooner.",
        LaunchHint(SessionType.HIKE, minutes = 0),
    )

    private fun ctxFor(plan: RacePlan, week: PlanWeek, records: List<SessionRecord>, today: LocalDate, profile: Profile): RacePlanner.Ctx {
        val status = plan.runStatus
        val rung = if (!week.runStageProjected && week.runStage == status.stage) status.rung else 0
        val knee = status.flareRecent || (status.steppedBackOn?.let { ChronoUnit.DAYS.between(it, today) <= 14 } == true)
        return RacePlanner.Ctx(
            max(1, week.index), week.phase, week.deload, week.start, week.runStage, rung, records, profile.settings, profile, plan.race, knee,
            profile.settings.racePlan.maxWeeklyMinutes != null,
        )
    }

    private fun strengthNo(s: PlannedSession) = if (s.title.startsWith("Strength B")) 1 else 0

    /** [s] fitted to [minutes] (regenerated shorter when needed) and, with a sore knee, without running. */
    private fun fit(ctx: RacePlanner.Ctx, s: PlannedSession, minutes: Int, noRun: Boolean): PlannedSession {
        val target = RacePlanner.floor5(min(s.minutes, minutes).toDouble()).coerceAtLeast(min(s.minutes, minLen(s.kind)))
        if (target >= s.minutes && !(noRun && s.includesRunning)) return s
        return RacePlanner.sessionFor(ctx, RacePlanner.Draft(s.date, s.kind, target, strengthNo(s)), allowRun = !noRun).copy(movedFrom = s.movedFrom)
    }

    private fun easyOf(ctx: RacePlanner.Ctx, minutes: Int, noRun: Boolean, date: LocalDate, title: String): PlannedSession {
        val m = RacePlanner.floor5(minutes.toDouble()).coerceAtLeast(10)
        return RacePlanner.sessionFor(ctx, RacePlanner.Draft(date, SessionKind.EASY, m, 0), allowRun = !noRun).copy(title = title.replace("{m}", m.toString()))
    }

    fun suggestForTime(
        plan: RacePlan, records: List<SessionRecord>, today: LocalDate, minutes: Int, feeling: Feeling, kneePain: Int?, profile: Profile,
    ): TimeSuggestion {
        if (feeling == Feeling.ILL) {
            return TimeSuggestion(restToday(today), null, "You are unwell, so rest is today's session. Nothing from the plan needs doing, and it will be here when you are better.", false)
        }
        val lead = if (RestDays.isRestDay(profile, today)) "Rest day, but if you fancy it... " else ""
        val knee = kneePain ?: 0
        val noRun = knee >= 4
        val week = RacePlanner.thisWeek(plan, today) ?: plan.weeks.last()
        val ctx = ctxFor(plan, week, records, today, profile)
        val done = RacePlanner.doneSessions(week, records, today)
        val open = if (today < week.start) emptyList() else RacePlanner.counted(week).filter { it !in done && it.date <= today.plusDays(6) }
        val recent = records.filter { it.date in today.minusDays(1)..today }
        val hardRecent = recent.any { IntensityBalance.classify(it).hardMin > 0 }
        val strengthRecent = recent.any { it.type == SessionType.GYM }
        val rough = feeling == Feeling.ROUGH
        val fresh = feeling == Feeling.GOOD && knee < 3
        val avail = if (rough) min(minutes, 30) else minutes
        val kneeNote = if (noRun) " Knee at $knee/10, so no running today." else ""

        val order = when {
            rough -> listOf(SessionKind.EASY, SessionKind.LONG_EASY)
            noRun -> listOf(SessionKind.EASY, SessionKind.LONG_EASY, SessionKind.STRENGTH)
            fresh -> listOf(SessionKind.KEY, SessionKind.SIM, SessionKind.STRENGTH, SessionKind.LONG_EASY, SessionKind.EASY)
            else -> listOf(SessionKind.STRENGTH, SessionKind.LONG_EASY, SessionKind.EASY)
        }
        fun allowed(s: PlannedSession) = when {
            hard(s.kind) -> !hardRecent && avail >= minLen(s.kind)
            s.kind == SessionKind.STRENGTH -> !strengthRecent && avail >= minLen(s.kind)
            else -> true
        }
        val ranked = order.flatMap { k ->
            open.filter { it.kind == k && allowed(it) }.sortedWith(compareBy({ abs(ChronoUnit.DAYS.between(today, it.date)) }, { it.date }))
        }

        fun build(s: PlannedSession): PlannedSession =
            if ((s.kind == SessionKind.LONG_EASY || s.kind == SessionKind.EASY) && avail < minLen(s.kind)) {
                easyOf(ctx, avail, noRun, today, "Easy aerobic: {m} min (a shorter version)")
            } else fit(ctx, s, avail, noRun)

        val first = ranked.firstOrNull()
        if (first == null) {
            if (open.isNotEmpty()) {
                val why = when {
                    rough -> "Feeling rough, so keep it easy and short. Nothing hard today; the planned sessions will wait for a better day."
                    hardRecent && strengthRecent -> "You trained hard and lifted in the last day, so an easy session is the best fit now."
                    hardRecent -> "A hard session was done in the last day, so keep today easy."
                    strengthRecent -> "You lifted in the last day, so keep today easy and give the legs a break."
                    else -> "With $minutes minutes, a short easy session is the best use of the time."
                }
                return TimeSuggestion(easyOf(ctx, min(avail, 45), noRun, today, "Easy aerobic: {m} min (a gentler version)"), null, lead + why + kneeNote, false)
            }
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val ahead = week.plannedMinutes > 0 && RacePlanner.loggedMinutes(records, maxOf(week.start, monday), today) > week.plannedMinutes
            val why = buildString {
                append(
                    if (today < week.start) "The plan has not started yet, so this is just a bonus: an easy session if you fancy it."
                    else "Everything planned this week is done or skipped, so this is a bonus: an easy session, entirely optional.",
                )
                if (ahead) append(" You are ahead; rest is training too.")
                append(" Resting instead is just as good.")
            }
            return TimeSuggestion(easyOf(ctx, min(avail, 45), noRun, today, "Bonus: easy aerobic, {m} min"), null, lead + why + kneeNote, true)
        }
        val primary = build(first)
        val alt = ranked.firstOrNull { it.kind != first.kind && it.date != first.date }?.let { build(it) }?.takeIf { it.title != primary.title }
        val why = when {
            rough -> "Feeling rough, so keep it easy and short. Nothing hard today."
            hard(first.kind) -> "You feel good and have $minutes minutes, so the hard session is a good use of them. It is the one that matters most this week."
            first.kind == SessionKind.STRENGTH -> "Strength is due: it is a day or more since your last lift. Fitted to $minutes minutes."
            else -> "Easy aerobic time is always a good use of spare minutes, and it helps recovery too."
        } + (if (primary.minutes < first.minutes) " Shortened to ${primary.minutes} minutes." else "") + kneeNote
        return TimeSuggestion(primary, alt, lead + why, false)
    }
}
