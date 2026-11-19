package com.calum.traininglog.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The race-preparation plan engine: a 14-week, Monday-Sunday plan that ends on the race date. Pure, deterministic
 * and re-computed from the logs every time (call [build] again whenever records or the date change).
 *
 * Shape (weeks counted back from the race week, so the default race of Saturday 2027-02-27 gives week 1 =
 * Monday 2026-11-23): BASE weeks 1-3 plus a lighter week 4, BUILD 5-7 plus a lighter week 8, SPECIFIC 9-11,
 * SHARPEN 12, TAPER 13, RACE WEEK 14.
 *
 * Evidence, and how strong it is (full notes in docs/hyrox-plan-evidence.md):
 *  - Mostly-easy distribution (about 80/20): the latest meta-analysis (Silva Oliveira et al., Sports Med 2024)
 *    found polarized training modestly better for VO2peak (SMD 0.24, mostly short interventions in highly trained
 *    athletes) but no clear advantage for time-trial or threshold performance, and similar results in
 *    interventions over 12 weeks. So low-intensity-dominant is a sound, injury-safe default, not a magic formula.
 *  - Taper: Bosquet et al., Med Sci Sports Exerc 2007 (27 studies): best was about 2 weeks, volume cut
 *    exponentially by 41-60% with intensity and frequency kept; cutting intensity alone did not help; about 3% gain.
 *  - Hyrox: a first small study (n = 11 recreational athletes) found running was about 59% of race time and
 *    aerobic fitness correlated with total and run time but not station time. A hint only; coaching sources say a
 *    weak station can still undo a good runner, so stations are trained too.
 *  - Concurrent training (Schumann et al. meta-analysis): maximal strength and size are not compromised, but
 *    explosive strength is blunted when strength and endurance share a session, so strength and hard endurance
 *    are placed on different days.
 *  - ITB return to running: practitioner consensus and a 2024 systematic review of clinical practice, not
 *    trials. See [RunStageLogic]. Not a medical tool.
 *  - NOT strong evidence, only sensible heuristics: weekly load increases of about 10% or less, a lighter week
 *    every 4th week, session RPE for monitoring, the exact session lengths and shares used here.
 *
 * Time cap ([RacePlanSettings.maxWeeklyMinutes], default none): the weekly volume model runs as usual and, when its
 * largest week is over the cap, weeks 1-11 are all multiplied by cap / largest (floored to 5 minutes), so the
 * build-up shape and the lighter weeks keep their proportions and no week is over the cap. Sharpen, taper and race
 * week are then sized from the CAPPED peak (85%, 58%, 42%), so their reductions are unchanged. The week layout
 * already drops sessions in priority order when time is short (second easy, easy, long easy first; the key
 * session and the two strength sessions stay), and never makes a session shorter than its minimum length, so with
 * a very tight cap (near 120 minutes) a lighter week cannot be lighter than three minimum-length sessions. The
 * hard share of a session is part of its own length, so the 80/20 mix holds whenever the easy sessions are
 * kept; with only the key session left as endurance work the week is as mixed as that one session. With no cap the
 * output is exactly what it was before the cap existed.
 *
 * Starting load: the true average of the last 4 weeks of logs (days before the first logged session are not
 * counted, so a new user is not averaged down; floor 60 min a week, and the default of [DEFAULT_BASELINE_MIN] only
 * when there are under 4 sessions). Week 1 is that average x [FIRST_WEEK_FACTOR] (rounded to 5), every normal week
 * adds at most about 10%, and the peak is simply what the compounded build reaches by weeks 9-11 (never above the
 * cap), which is lower than the old fixed peak target: the honest result from a low starting point. To make a
 * small week possible, session minimum lengths are lower while the weekly volume is small (strength 30, key 30, long
 * 35, easy 20 minutes) and rise to the normal minimums as the week reaches the normal minimum total.
 *
 * Kick-off lead-in ([RacePlan.leadIn]): while today is before week 1's Monday, 1-2 gentle sessions (an easy aerobic
 * one and, with 3 or more free days, a first strength session with hip prehab) cover tomorrow (today when nothing is
 * logged today) to the Sunday before week 1, sized to the current load. The 14 numbered weeks are not changed.
 *
 * Adaptive rules (no guilt, never shown as failure): a past week with under 60% of its planned time repeats its
 * target instead of progressing; there is never any 'catching up' by doubling sessions; feeling ill or rough
 * drops the key session first; a gap of 10 or more days steps one week of load back; the sharpen, taper and race
 * weeks stay anchored to the race date and are sized from the largest week actually planned.
 */
object RacePlanner {
    const val WEEKS = 14
    const val DEFAULT_BASELINE_MIN = 200
    /** Week 1 is the real recent average times this (about +10%), then at most [MAX_WEEKLY_INCREASE] per normal week. */
    const val FIRST_WEEK_FACTOR = 1.10
    /** Lowest and highest weekly average treated as real history. */
    const val BASELINE_FLOOR_MIN = 60
    const val BASELINE_CEILING_MIN = 300
    const val EASY_TARGET = 0.8
    const val MAX_WEEKLY_INCREASE = 1.10
    const val DELOAD_FACTOR = 0.75
    const val SHARPEN_FACTOR = 0.85
    /** Taper week 13 is 42% below the peak week and race week 58% below: both inside Bosquet's 41-60%. */
    const val TAPER_FACTOR = 0.58
    const val RACE_WEEK_FACTOR = 0.42
    const val REPEAT_BELOW = 0.60
    const val GAP_DAYS_STEP_BACK = 10L

    fun phaseOf(index: Int): RacePhase = when {
        index <= 4 -> RacePhase.BASE
        index <= 8 -> RacePhase.BUILD
        index <= 11 -> RacePhase.SPECIFIC
        index == 12 -> RacePhase.SHARPEN
        index == 13 -> RacePhase.TAPER
        else -> RacePhase.RACE
    }

    fun isDeload(index: Int): Boolean = index == 4 || index == 8

    /** Monday of week 1 for a race on [raceDate]. */
    fun planStart(raceDate: LocalDate): LocalDate =
        raceDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks((WEEKS - 1).toLong())

    /** 1-based plan week index containing [date] (0 or less before the plan, above 14 after the race week). */
    fun weekIndexOf(raceDate: LocalDate, date: LocalDate): Int =
        Math.floorDiv(ChronoUnit.DAYS.between(planStart(raceDate), date), 7L).toInt() + 1

    fun daysToGo(config: RacePlanSettings, today: LocalDate): Long = ChronoUnit.DAYS.between(today, config.raceDate)

    /**
     * The week containing [today]; the kick-off lead-in while it is active (today before week 1); the first week
     * when the plan has not started and there is no lead-in; null after race week.
     */
    fun thisWeek(plan: RacePlan, today: LocalDate): PlanWeek? = when {
        today < plan.start -> plan.leadIn ?: plan.weeks.first()
        else -> plan.weekOf(today)
    }

    /** Today's planned session (in the lead-in or a numbered week), or null on a day with nothing planned. */
    fun today(plan: RacePlan, today: LocalDate): PlannedSession? =
        plan.segmentOf(today)?.sessions?.firstOrNull { it.date == today }

    /** Done versus planned time for the week (or lead-in) containing [today] (null when there is no such week). */
    fun weekProgress(plan: RacePlan, records: List<SessionRecord>, today: LocalDate): WeekProgress? {
        val w = (if (today < plan.start) plan.leadIn else plan.weekOf(today)) ?: return null
        return progress(w, records, today)
    }

    private fun progress(w: PlanWeek, records: List<SessionRecord>, today: LocalDate): WeekProgress {
        val done = loggedMinutes(records, w.start, if (w.end < today) w.end else today)
        val planned = w.plannedMinutes
        val fraction = if (planned <= 0) 1.0 else min(1.0, done.toDouble() / planned)
        val over = w.end < today
        val message = when {
            today < w.start && w.isLeadIn -> "Kick-off starts ${if (ChronoUnit.DAYS.between(today, w.start) == 1L) "tomorrow" else w.start.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)}: $planned gentle planned minutes."
            today < w.start -> "This week starts on Monday: $planned planned minutes."
            w.isLeadIn && planned > 0 && done >= planned -> "Kick-off done: $done of $planned minutes. Week 1 starts on Monday."
            w.isLeadIn && done == 0 && !over -> "$planned gentle minutes planned. Easing in is the point; every minute counts."
            planned > 0 && done >= planned -> "Planned time reached: $done of $planned minutes. Anything more is a bonus."
            done == 0 && !over -> "$planned minutes planned. Start whenever it suits; every minute counts."
            done == 0 -> "A quiet week, and that is fine. The plan simply picks up from where you are."
            over && fraction < REPEAT_BELOW -> "A lighter week: $done of $planned minutes. That is completely normal, and next week repeats these targets instead of adding more."
            over -> "$done of $planned minutes. Solid week."
            fraction >= REPEAT_BELOW -> "$done of $planned planned minutes so far. Going well."
            else -> "$done of $planned planned minutes so far. Every session counts, whatever the rest of the week brings."
        }
        return WeekProgress(planned, done, fraction, message)
    }

    // ---------------------------------------------------------------------------------------------- build

    /**
     * Builds the plan. [records] are all logged sessions, [today] the current date, [profile] supplies rest days
     * and the Hyrox division, kit and weight settings.
     */
    fun build(config: RacePlanSettings, records: List<SessionRecord>, today: LocalDate, profile: Profile = Profile()): RacePlan {
        val cfg = config.sanitized()
        val settings = profile.settings
        val count = cfg.sessionsPerWeek
        val start = planStart(cfg.raceDate)
        val status = RunStageLogic.assess(records, today)
        val bl = baseline(records, start, today)
        val baseline = bl.minutes
        val vol = volumes(records, today, start, count, bl, cfg.maxWeeklyMinutes)
        val currentIdx = weekIndexOf(cfg.raceDate, today)
        val rough = recentRough(records, today)

        var prevSessions: List<PlannedSession> = emptyList()
        val weeks = (1..WEEKS).map { i ->
            val ws = start.plusWeeks((i - 1).toLong())
            val projected = i > currentIdx && currentIdx >= 0
            val stage = projectStage(status, i, currentIdx, today)
            val rung = if (stage == status.stage && !projected) status.rung else 0
            val kneeCaution = status.flareRecent || (status.steppedBackOn?.let { ChronoUnit.DAYS.between(it, today) <= 14 } == true)
            val ctx = Ctx(i, phaseOf(i), isDeload(i), ws, stage, rung, records, settings, profile, cfg.raceDate, kneeCaution, cfg.maxWeeklyMinutes != null)
            var sessions = layoutWeek(ctx, vol.minutes[i], count, prevSessions)
            var adjustment = vol.notes[i]
            if (i == currentIdx && rough != null) {
                val keyDropped = sessions.filter { (it.kind == SessionKind.KEY || it.kind == SessionKind.SIM) && !it.isRaceDay && it.date >= today }
                if (keyDropped.isNotEmpty()) {
                    sessions = sessions - keyDropped.toSet()
                    adjustment = (adjustment?.plus(" ") ?: "") + "You logged feeling ${rough.name.lowercase()} recently, so the hard session is skipped this week. Easy movement only, and rest if you need it."
                }
            }
            prevSessions = sessions
            PlanWeek(
                index = i, start = ws, phase = ctx.phase, deload = ctx.deload,
                plannedMinutes = sessions.filter { !it.isRaceDay && it.kind != SessionKind.REST }.sumOf { it.minutes },
                easyShareTarget = EASY_TARGET, runStage = stage, runStageProjected = projected,
                sessions = sessions, headline = headline(ctx), focus = focus(ctx),
                stationTargets = stationTargets(ctx), adjustment = adjustment,
            )
        }
        val peak = weeks.filter { it.index <= 11 }.maxOf { it.plannedMinutes }
        val kneeCautionNow = status.flareRecent || (status.steppedBackOn?.let { ChronoUnit.DAYS.between(it, today) <= 14 } == true)
        val leadIn = leadIn(cfg, records, today, profile, start, status, kneeCautionNow, bl, weeks.first())
        return RacePlan(weeks, cfg.raceDate, timeline(weeks), baseline, peak, status, leadIn, bl.fromHistory, startNote(bl, weeks.first().plannedMinutes, peak, cfg.maxWeeklyMinutes))
    }

    /** Where the starting load came from, in plain words (null for a new user on the default). */
    private fun startNote(bl: Baseline, week1: Int, peak: Int, cap: Int?): String? {
        if (!bl.fromHistory) return null
        val peakText = if (cap != null && peak >= cap) "your $cap minute cap" else "about $peak minutes"
        return "The starting load follows your real recent minutes (about ${bl.minutes} a week), so week 1 is $week1 minutes, about 10% above that. " +
            "Each normal week then adds at most about 10%, so the build reaches $peakText by the peak weeks" +
            (if (cap != null && peak < cap) ", lower than your $cap minute cap" else "") +
            ". That is the honest build from where you are now, and it is kind to a returning knee. If you do more, the plan follows."
    }

    // ------------------------------------------------------------------------------------------ kick-off lead-in

    private fun loggedLabel(r: SessionRecord): String = when {
        r.treadmill != null -> when (r.treadmill.style) {
            TreadmillStyle.LADDER -> "ladder"
            TreadmillStyle.PYRAMID -> "pyramid"
            TreadmillStyle.STEADY -> "walk"
            TreadmillStyle.INTERVALS, TreadmillStyle.LONG_INTERVALS -> "intervals"
            TreadmillStyle.LONG_ROLLING -> "long walk"
        }
        r.type == SessionType.GYM -> "gym session"
        else -> "session"
    }

    /**
     * The kick-off lead-in: present only while [today] is before week 1's Monday and at least one training day is
     * left. Starts tomorrow, or today when nothing is logged today (a session logged today counts for today). It has
     * an easy aerobic session of 25-35 minutes and, with 3 or more free days, a first 30-40 minute strength session
     * with hip prehab, sized to the current load, on free days, never next to another strength day.
     */
    private fun leadIn(
        cfg: RacePlanSettings, records: List<SessionRecord>, today: LocalDate, profile: Profile, start: LocalDate,
        status: RunStatus, kneeCaution: Boolean, bl: Baseline, week1: PlanWeek,
    ): PlanWeek? {
        if (today >= start) return null
        val loggedToday = records.filter { it.date == today }
        val first = if (loggedToday.isNotEmpty()) today.plusDays(1) else today
        val last = start.minusDays(1)
        if (first > last) return null
        val ctx = Ctx(1, RacePhase.KICKOFF, false, first, status.stage, status.rung, records, profile.settings, profile, cfg.raceDate, kneeCaution, cfg.maxWeeklyMinutes != null)
        val days = freeDays(ctx, first, last)
        if (days.isEmpty()) return null
        val kinds = if (days.size >= 3) listOf(SessionKind.EASY, SessionKind.STRENGTH) else listOf(SessionKind.EASY)
        val b = bl.minutes
        val minutes = mapOf(
            SessionKind.EASY to round5(b * 0.25).coerceIn(25, 35),
            SessionKind.STRENGTH to round5(b * 0.30).coerceIn(30, 40),
        )
        val context = HashMap<LocalDate, SessionKind>()
        records.filter { it.date >= first.minusDays(2) && it.date < first }.forEach {
            context[it.date] = if (it.type == SessionType.GYM) SessionKind.STRENGTH else SessionKind.EASY
        }
        week1.sessions.filter { it.kind != SessionKind.REST }.forEach { context[it.date] = it.kind }
        val dates = schedule(kinds, days, null, context)
        val sessions = kinds.indices.map { n ->
            val d = Draft(dates[n], kinds[n], minutes.getValue(kinds[n]), 0)
            val base = sessionFor(ctx, d, allowRun = true)
            when (kinds[n]) {
                SessionKind.STRENGTH -> base.copy(title = "Kick-off: first strength session, hip prehab")
                else -> base.copy(title = "Kick-off: easy aerobic, ${d.minutes} min")
            }
        }.sortedBy { it.date }
        val note = loggedToday.takeIf { it.isNotEmpty() }?.let { "Nothing more needed today: your ${loggedLabel(it.last())} counts. The plan starts tomorrow." }
        return PlanWeek(
            index = 0, start = first, phase = RacePhase.KICKOFF, deload = false,
            plannedMinutes = sessions.sumOf { it.minutes },
            easyShareTarget = EASY_TARGET, runStage = status.stage, runStageProjected = false,
            sessions = sessions, headline = "Kick-off: easing in before week 1",
            focus = "A couple of short, easy sessions sized to what you are doing now (about $b minutes a week), so week 1 starts from where you really are. Nothing is hard.",
            stationTargets = stationTargets(ctx), adjustment = note, lastDay = last,
        )
    }

    private fun timeline(weeks: List<PlanWeek>): List<PhaseSpan> =
        weeks.groupBy { it.phase }.map { (p, ws) -> PhaseSpan(p, ws.first().index, ws.last().index, ws.first().start, ws.last().end) }
            .sortedBy { it.firstWeek }

    /** Most recent ILL or ROUGH feeling in the last 3 days, if any. */
    private fun recentRough(records: List<SessionRecord>, today: LocalDate): Feeling? =
        records.filter { it.date <= today && ChronoUnit.DAYS.between(it.date, today) <= 3 && (it.feeling == Feeling.ILL || it.feeling == Feeling.ROUGH) }
            .maxWithOrNull(compareBy({ it.date }, { it.id }))?.feeling

    /** Minutes of logged training in [from]..[to] (treadmill sessions use the time actually walked). */
    internal fun loggedMinutes(records: List<SessionRecord>, from: LocalDate, to: LocalDate): Int =
        records.filter { it.date in from..to }.sumOf { r -> r.treadmill?.let { it.completedSec / 60 } ?: r.minutesActual }

    internal class Baseline(val minutes: Int, val fromHistory: Boolean)

    /**
     * The true recent weekly average: logged minutes over the last 4 weeks ending at [today] (before week 1) or at the
     * day before week 1 once the plan has begun (so the baseline stays put while the plan runs). Days before the
     * first logged session are not counted, so a new user is not averaged down. Rounded to 5 and kept inside
     * [BASELINE_FLOOR_MIN]..[BASELINE_CEILING_MIN]. With under 4 sessions in the window it is [DEFAULT_BASELINE_MIN].
     */
    internal fun baseline(records: List<SessionRecord>, start: LocalDate, today: LocalDate): Baseline {
        val end = if (today < start) today else start.minusDays(1)
        val from = end.minusDays(27)
        val recent = records.filter { it.date in from..end }
        if (recent.size < 4) return Baseline(DEFAULT_BASELINE_MIN, false)
        val firstDay = maxOf(from, recent.minOf { it.date })
        val weeks = max(1.0, (ChronoUnit.DAYS.between(firstDay, end) + 1) / 7.0)
        val avg = loggedMinutes(recent, from, end) / weeks
        return Baseline((avg / 5).roundToInt().times(5).coerceIn(BASELINE_FLOOR_MIN, BASELINE_CEILING_MIN), true)
    }

    internal fun baselineMinutes(records: List<SessionRecord>, start: LocalDate, today: LocalDate): Int = baseline(records, start, today).minutes

    // ------------------------------------------------------------------------------------- volume model

    private class Volumes(val minutes: IntArray, val notes: Array<String?>)

    /** Normal (minimum to maximum) session lengths. The minimums apply once the week is big enough; see [minFor]. */
    private val SLOT_BOUNDS = mapOf(
        SessionKind.STRENGTH to (40 to 65),
        SessionKind.KEY to (40 to 95),
        SessionKind.LONG_EASY to (40 to 120),
        SessionKind.EASY to (25 to 60),
    )

    /** Shorter minimums used while ramping up from a small week. */
    private val RAMP_MIN = mapOf(
        SessionKind.STRENGTH to 30, SessionKind.KEY to 30, SessionKind.LONG_EASY to 35, SessionKind.EASY to 20,
    )

    private fun kindsFor(count: Int): List<SessionKind> =
        listOf(SessionKind.STRENGTH, SessionKind.STRENGTH, SessionKind.KEY, SessionKind.LONG_EASY, SessionKind.EASY, SessionKind.EASY).take(count)

    private fun minTotalNormal(count: Int) = kindsFor(count).sumOf { SLOT_BOUNDS.getValue(it).first }
    private fun minTotalRamp(count: Int) = kindsFor(count).sumOf { RAMP_MIN.getValue(it) }

    /**
     * Minimum length of a [kind] session in a week of [total] minutes with [count] sessions: the ramp minimum for a
     * small week, scaled up to the normal minimum as [total] reaches the normal minimum total of the [count] sessions.
     */
    private fun minFor(kind: SessionKind, count: Int, total: Int): Int {
        val ramp = minTotalRamp(count); val normal = minTotalNormal(count)
        val t = if (normal <= ramp) 1.0 else ((total - ramp).toDouble() / (normal - ramp)).coerceIn(0.0, 1.0)
        val r = RAMP_MIN.getValue(kind); val n = SLOT_BOUNDS.getValue(kind).first
        return floor5(r + t * (n - r))
    }
    private fun maxTotal(count: Int) = kindsFor(count).sumOf { SLOT_BOUNDS.getValue(it).second }
    private fun factor(count: Int) = when (count) { 3 -> 0.62; 4 -> 0.82; 5 -> 1.0; else -> 1.12 }
    private fun round5(x: Double) = (x / 5).roundToInt() * 5
    private fun floor5(x: Double) = (floor(x / 5) * 5).toInt()

    /**
     * Weekly minutes for weeks 1-14 (index 0 unused). Normal weeks climb geometrically (at most 10% a week) from
     * the baseline to a peak target; see the class comment for the adaptive rules.
     */
    private fun volumes(records: List<SessionRecord>, today: LocalDate, start: LocalDate, count: Int, bl: Baseline, cap: Int?): Volumes {
        val baseline = bl.minutes
        val real = bl.fromHistory
        // A real (usually lower) history may start from the ramp minimums; the default start keeps the normal ones.
        val lo = if (real) minTotalRamp(count) else minTotalNormal(count)
        // Real history: week 1 is the true average + 10%, then plain compounding (at most 10% a week), never above
        // the cap. No history: the default start and the old peak target. With a cap the starting week and the
        // peak are limited to it, so the build-up keeps its shape instead of the first weeks being squashed.
        val s0 = if (real) round5(baseline * FIRST_WEEK_FACTOR).toDouble() else baseline.toDouble()
        val s = max(if (cap != null) min(s0, cap.toDouble()) else s0, lo.toDouble())
        val p0 = max(s, min(factor(count) * (max(baseline, lo) * 1.9).coerceIn(240.0, 380.0), maxTotal(count) * 0.95))
        val p = when {
            real -> if (cap != null) max(cap.toDouble(), s) else Double.MAX_VALUE
            cap != null -> max(min(p0, cap.toDouble()), s)
            else -> p0
        }
        val vols = IntArray(WEEKS + 1)
        val notes = arrayOfNulls<String>(WEEKS + 1)
        val normal = (1..11).filter { !isDeload(it) }
        val started = records.any { it.date >= start && it.date <= today }
        var vPrev = s
        val hist = mutableListOf<Double>()
        var lastNormalT = 0
        for (i in 1..11) {
            val ws = start.plusWeeks((i - 1).toLong())
            if (isDeload(i)) { vols[i] = max(lo, round5(DELOAD_FACTOR * vPrev)); continue }
            val stepsLeft = normal.count { it >= i }
            val r = if (vPrev >= p) 1.0 else if (real) MAX_WEEKLY_INCREASE else min(MAX_WEEKLY_INCREASE, (p / vPrev).pow(1.0 / stepsLeft))
            var v = if (i == 1) s else vPrev * r
            if (i > 1 && ws <= today) {
                if (!started) {
                    v = vPrev
                } else {
                    val prevIdx = i - 1
                    val ps = start.plusWeeks((i - 2).toLong())
                    val done = loggedMinutes(records, ps, ps.plusDays(6))
                    val ref = if (today in ws..ws.plusDays(6)) today else ws
                    val lastBefore = records.filter { it.date < ref }.maxOfOrNull { it.date }
                    val gap = lastBefore?.let { ChronoUnit.DAYS.between(it, ref) } ?: 0L
                    if (gap >= GAP_DAYS_STEP_BACK) {
                        v = max(lo.toDouble(), min(hist.getOrElse(hist.size - 2) { vPrev }, vPrev / 1.08))
                        notes[i] = "There was a break of $gap days, so this week steps back a little and builds again from there. Easing back in is how it sticks."
                    } else if (!isDeload(prevIdx) && done < REPEAT_BELOW * vols[prevIdx]) {
                        v = vPrev
                        notes[i] = "Last week was a lighter one ($done of ${vols[prevIdx]} planned minutes), which is completely normal. This week repeats those targets instead of adding more."
                    }
                }
            }
            var t = round5(v)
            if (lastNormalT > 0) t = min(t, floor5(lastNormalT * MAX_WEEKLY_INCREASE))
            if (real && cap != null) t = min(t, floor5(p))
            t = t.coerceIn(lo, maxTotal(count))
            vols[i] = t
            vPrev = t.toDouble(); hist += vPrev; lastNormalT = t
        }
        if (cap != null) {
            // Time cap: scale weeks 1-11 by one factor so the biggest week lands on the cap. Deloads (already 75% of
            // the week before) and the build-up keep their proportions; floor to 5 so no week can round past the cap.
            val biggest = normal.maxOf { vols[it] }
            if (biggest > cap) {
                val f = cap.toDouble() / biggest
                for (i in 1..11) vols[i] = floor5(vols[i] * f)
            }
        }
        val peak = normal.maxOf { vols[it] }
        vols[12] = round5(SHARPEN_FACTOR * peak)
        vols[13] = round5(TAPER_FACTOR * peak)
        vols[14] = round5(RACE_WEEK_FACTOR * peak)
        return Volumes(vols, notes)
    }

    // ------------------------------------------------------------------------------------- run stage

    private val PHASE_CEILING = mapOf(
        RacePhase.BASE to RunStage.RUN_WALK, RacePhase.BUILD to RunStage.INTERVALS,
        RacePhase.SPECIFIC to RunStage.RACE_RUNS, RacePhase.SHARPEN to RunStage.RACE_RUNS,
        RacePhase.TAPER to RunStage.RACE_RUNS, RacePhase.RACE to RunStage.RACE_RUNS,
    )
    private val WEEKS_PER_STAGE = mapOf(RunStage.NONE to 2, RunStage.RUN_WALK to 3, RunStage.CONTINUOUS to 3, RunStage.INTERVALS to 2)

    /**
     * Stage used for week [i]: the actual stage for the current and earlier weeks; for future weeks a projection
     * that assumes a calm knee (advancing a stage every 2-3 weeks) but never beyond the phase's ceiling, and none
     * at all while the knee is flared. Recomputed from the logs on every build.
     */
    private fun projectStage(status: RunStatus, i: Int, currentIdx: Int, today: LocalDate): RunStage {
        val ahead = max(0, i - max(currentIdx, 1))
        if (i <= max(currentIdx, 1)) return status.stage
        val flared = status.flareRecent || (status.steppedBackOn?.let { ChronoUnit.DAYS.between(it, today) <= 14 } == true)
        if (flared) return status.stage
        var st = status.stage
        var weeks = ahead - 0
        while (weeks > 0 && st != RunStage.RACE_RUNS) {
            val need = WEEKS_PER_STAGE.getValue(st)
            if (weeks < need) break
            weeks -= need
            st = st.next()
        }
        val ceiling = PHASE_CEILING.getValue(phaseOf(i))
        return if (st > ceiling) max(status.stage, ceiling) else st
    }

    private fun max(a: RunStage, b: RunStage) = if (a >= b) a else b

    // ------------------------------------------------------------------------------------- station ladder

    // Ladder level targeted in each plan week 1..14. BASE reaches 3 (knee-heavy stations reach 2 and ramp more
    // slowly), BUILD 4-5, SPECIFIC race standard (5): stations 1-4 in week 9 (the half-sim) and 5-8 in week 10.
    private val GENERAL_LEVELS = intArrayOf(1, 2, 3, 2, 4, 4, 5, 4, 5, 5, 5, 5, 5, 5)
    private val ERG_LEVELS = intArrayOf(3, 3, 4, 3, 4, 5, 5, 4, 5, 5, 5, 5, 5, 5)
    private val BBJ_LEVELS = intArrayOf(1, 2, 2, 2, 3, 3, 4, 3, 5, 4, 5, 4, 4, 4)
    private val LUNGE_LEVELS = intArrayOf(1, 1, 2, 2, 3, 3, 4, 3, 4, 5, 5, 4, 4, 4)

    private fun stationTargets(c: Ctx): Map<HyroxStation, Int> = HyroxStation.entries.associateWith { s ->
        val table = when (s) {
            HyroxStation.SKIERG, HyroxStation.ROW -> ERG_LEVELS
            HyroxStation.BURPEE_BROAD_JUMP -> BBJ_LEVELS
            HyroxStation.SANDBAG_LUNGES -> LUNGE_LEVELS
            else -> GENERAL_LEVELS
        }
        val t = table[c.i - 1]
        if (s.kneeHeavy && c.kneeCaution) min(t, 3) else t
    }

    private fun stationLine(c: Ctx, s: HyroxStation, level: Int): String {
        val ladder = HyroxLadder.of(c.settings.hyroxDivision, s, c.settings.hyroxWeightOverrides)
        val l = ladder[level.coerceIn(1, ladder.size) - 1]
        val unit = if (s.kind == StationKind.REPS) "reps" else "m"
        val per = l.distanceM ?: l.reps ?: 0
        val kg = l.weightKg?.let { " at ${fmtKg(it)} kg" } ?: ""
        val line = "${s.displayName}, level ${l.level} (${l.label}): ${if (l.rounds > 1) "${l.rounds} x " else ""}$per $unit$kg${if (l.restSec > 0) ", rest ${l.restSec} s" else ""}"
        return if (!s.availableWith(c.settings.hyroxGymGear)) "$line. No ${s.requiredGear.joinToString(" or ") { it.short }} ticked at the gym, so instead: ${HyroxGuide.of(s).alternatives.first()}" else line
    }

    private fun fmtKg(kg: Double) = if (kg == kg.toInt().toDouble()) kg.toInt().toString() else "%.1f".format(kg)

    private val ROTATION_EVEN = listOf(listOf(HyroxStation.SLED_PUSH, HyroxStation.FARMERS_CARRY), listOf(HyroxStation.WALL_BALLS, HyroxStation.SANDBAG_LUNGES))
    private val ROTATION_ODD = listOf(listOf(HyroxStation.SLED_PULL, HyroxStation.BURPEE_BROAD_JUMP), listOf(HyroxStation.FARMERS_CARRY, HyroxStation.WALL_BALLS))

    /** The stations the n-th (0 or 1) strength session of week [i] ends with; every station recurs within 2 weeks. */
    private fun finisherStations(i: Int, n: Int): List<HyroxStation> = (if (i % 2 == 0) ROTATION_EVEN else ROTATION_ODD)[n]

    private fun ergOfWeek(i: Int) = if (i % 2 == 1) HyroxStation.SKIERG else HyroxStation.ROW

    // ------------------------------------------------------------------------------------- week layout

    private class Ctx(
        val i: Int, val phase: RacePhase, val deload: Boolean, val start: LocalDate, val stage: RunStage, val rung: Int,
        val records: List<SessionRecord>, val settings: UserSettings, val profile: Profile, val raceDate: LocalDate,
        val kneeCaution: Boolean, val capped: Boolean = false,
    )

    private data class Slot(val kind: SessionKind, val weight: Double, val min: Int, val max: Int, val fixed: Int? = null)

    private fun weightOf(kind: SessionKind) = when (kind) {
        SessionKind.STRENGTH -> 0.17; SessionKind.KEY, SessionKind.SIM -> 0.21; SessionKind.LONG_EASY -> 0.28; else -> 0.15
    }

    private fun layoutWeek(c: Ctx, total: Int, count: Int, prev: List<PlannedSession>): List<PlannedSession> {
        val context = prev.filter { it.kind != SessionKind.REST }.associate { it.date to it.kind }
        return if (c.phase == RacePhase.RACE) layoutRaceWeek(c, total, count, context) else layoutNormalWeek(c, total, count, context)
    }

    private fun freeDays(c: Ctx, from: LocalDate = c.start, to: LocalDate = c.start.plusDays(6)): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.filter { !RestDays.isRestDay(c.profile, it) }.toList()

    private fun layoutNormalWeek(c: Ctx, total: Int, count: Int, context: Map<LocalDate, SessionKind>): List<PlannedSession> {
        val relaxed = c.phase == RacePhase.TAPER || c.phase == RacePhase.SHARPEN
        val days = freeDays(c)
        var kinds = kindsFor(count).toMutableList()
        val simWeek = c.i == 9 || c.i == 11
        val simMinutes = if (c.i == 9) 55 else 90
        fun bounds(k: SessionKind, n: Int): Pair<Int, Int> {
            val first = minFor(k, n, total)
            return if (relaxed) (first - 10).coerceAtLeast(20) to SLOT_BOUNDS.getValue(k).second else first to SLOT_BOUNDS.getValue(k).second
        }
        // Drop the lowest-priority sessions until the days and the minutes fit.
        while (kinds.size > 3 && (kinds.size > days.size || kinds.sumOf { bounds(it, kinds.size).first } > total)) kinds.removeAt(kinds.lastIndex)
        if (kinds.size > days.size) kinds = kinds.take(days.size).toMutableList()
        val slots = kinds.map { k ->
            val (lo, hi) = bounds(k, kinds.size)
            if (k == SessionKind.KEY && simWeek) Slot(SessionKind.SIM, weightOf(k), 30, 95, simFixed(c, total, kinds, simMinutes))
            else Slot(k, weightOf(k), lo, hi)
        }
        val mins = allocate(total, slots)
        val dates = schedule(slots.map { it.kind }, days, null, context)
        val strengthIdx = slots.indices.filter { slots[it].kind == SessionKind.STRENGTH }.sortedBy { dates[it] }
        val drafts = slots.indices.map { n ->
            val sn = strengthIdx.indexOf(n)
            Draft(dates[n], slots[n].kind, mins[n], max(0, sn))
        }.sortedBy { it.date }
        return finishDrafts(c, drafts)
    }

    /**
     * Length of the simulation session: normally [simMinutes] but at most 45% of the week. With a time cap it also
     * leaves the other sessions their minimum lengths, so a tight week cannot be pushed over the cap.
     */
    private fun simFixed(c: Ctx, total: Int, kinds: List<SessionKind>, simMinutes: Int): Int {
        val base = min(simMinutes, floor5(0.45 * total))
        if (!c.capped) return base
        val others = kinds.filter { it != SessionKind.KEY }.sumOf { minFor(it, kinds.size, total) }
        return min(base, floor5((total - others).toDouble()).coerceAtLeast(30))
    }

    private class Draft(val date: LocalDate, val kind: SessionKind, val minutes: Int, val strengthNo: Int)

    private fun finishDrafts(c: Ctx, drafts: List<Draft>): List<PlannedSession> {
        val out = mutableListOf<PlannedSession>()
        var prevRunDate: LocalDate? = null
        for (d in drafts) {
            var s = sessionFor(c, d, allowRun = true)
            val consecutive = prevRunDate != null && ChronoUnit.DAYS.between(prevRunDate, d.date) == 1L
            if (s.includesRunning && consecutive && c.stage < RunStage.INTERVALS) s = sessionFor(c, d, allowRun = false)
            if (s.includesRunning) prevRunDate = d.date
            out += s
        }
        return out
    }

    private fun layoutRaceWeek(c: Ctx, total: Int, count: Int, context: Map<LocalDate, SessionKind>): List<PlannedSession> {
        val raceDay = c.raceDate
        val hardCutoff = raceDay.minusDays(4)   // nothing hard in the last 3 days
        val easyCutoff = raceDay.minusDays(2)   // the day before the race is a rest day
        val kinds = listOf(SessionKind.KEY, SessionKind.STRENGTH, SessionKind.EASY, SessionKind.EASY).take(if (count <= 3) 3 else 4).toMutableList()
        val days = freeDays(c, c.start, easyCutoff)
        val allowed = { k: SessionKind, d: LocalDate -> if (k == SessionKind.EASY) d <= easyCutoff else d <= hardCutoff }
        while (kinds.size > 2 && kinds.size > days.size) kinds.removeAt(kinds.lastIndex)
        val slots = kinds.map { k ->
            when (k) {
                SessionKind.KEY -> Slot(k, 0.30, 25, 45); SessionKind.STRENGTH -> Slot(k, 0.27, 25, 40); else -> Slot(k, 0.22, 20, 45)
            }
        }
        val mins = allocate(total, slots)
        val dates = schedule(kinds, days, allowed, context)
        val drafts = kinds.indices.map { Draft(dates[it], kinds[it], mins[it], 0) }.sortedBy { it.date }
        val sessions = finishDrafts(c, drafts).toMutableList()
        sessions += restSession(raceDay.minusDays(1))
        sessions += raceDaySession(c)
        return sessions.sortedBy { it.date }
    }

    private fun restSession(date: LocalDate) = PlannedSession(
        date, SessionKind.REST, "Rest day before the race", 0, Intensity.EASY, 0, 1..2, listOf(Modality.REST),
        listOf("Nothing hard. A short walk and some gentle mobility if you like.", "Pack the bag, lay out kit, eat normally, early night."),
        "Rest before the event lets the taper do its job (Bosquet et al. 2007: cut volume, keep a little sharpness, arrive fresh).",
        LaunchHint(SessionType.HIKE, minutes = 0),
    )

    private fun raceDaySession(c: Ctx) = PlannedSession(
        c.raceDate, SessionKind.SIM, "Race day: HYROX simulation", 90, Intensity.HARD, 45, 7..8, listOf(Modality.HYROX_STATIONS),
        listOf(
            "Warm up 10 minutes: easy cardio, a few leg swings and hip hinges, a short practice of the first station.",
            "8 x [1 km run + station] at Men's Open loads. Start easier than feels necessary: first 2 run legs at RPE 6, then hold steady.",
            runLegText(c.stage, plan = false),
            "Fuel and drink as you have in training: nothing new on race day.",
            "Smile, enjoy it. How it goes is information for next time, not a verdict.",
        ),
        "A steady start is the one pacing rule almost every coaching source agrees on; the race is the event itself, not a test of the plan.",
        LaunchHint(SessionType.HYROX, hyroxFocus = HyroxStation.entries.toSet(), minutes = 90),
        isRaceDay = true, includesRunning = c.stage >= RunStage.INTERVALS,
    )

    // ------------------------------------------------------------------------------------- allocation

    /** Splits [total] minutes over [slots] in proportion to their weights inside min/max, to the nearest 5. */
    private fun allocate(total: Int, slots: List<Slot>): IntArray {
        val n = slots.size
        val out = DoubleArray(n)
        val done = BooleanArray(n)
        var remaining = total.toDouble()
        slots.forEachIndexed { i, s -> s.fixed?.let { out[i] = it.toDouble(); done[i] = true; remaining -= it } }
        while (true) {
            val free = slots.indices.filter { !done[it] }
            if (free.isEmpty()) break
            val sumW = free.sumOf { slots[it].weight }
            val share = free.associateWith { remaining * slots[it].weight / sumW }
            val over = free.filter { share.getValue(it) > slots[it].max }
            if (over.isNotEmpty()) { over.forEach { out[it] = slots[it].max.toDouble(); done[it] = true; remaining -= slots[it].max }; continue }
            val under = free.filter { share.getValue(it) < slots[it].min }
            if (under.isNotEmpty()) { under.forEach { out[it] = slots[it].min.toDouble(); done[it] = true; remaining -= slots[it].min }; continue }
            free.forEach { out[it] = share.getValue(it); done[it] = true }
            break
        }
        val res = IntArray(n) { floor5(out[it]) }
        var left = total - res.sum()
        val order = slots.indices.filter { slots[it].fixed == null }.sortedByDescending { out[it] - res[it] }
        var guard = 0
        while (left >= 5 && guard++ < 50) {
            val pick = order.firstOrNull { res[it] + 5 <= slots[it].max } ?: break
            res[pick] += 5; left -= 5
            // rotate so leftovers spread
            if (guard % order.size == 0) continue
        }
        return res
    }

    // ------------------------------------------------------------------------------------- scheduling

    /**
     * Picks the best day for every session by trying every assignment: strength never on consecutive days and not
     * the day before a hard session; hard and long sessions not adjacent; long session at the weekend; at most
     * mild stacking of endurance days. Ties go to the earliest assignment, so output is deterministic.
     */
    internal fun schedule(kinds: List<SessionKind>, days: List<LocalDate>, allowed: ((SessionKind, LocalDate) -> Boolean)?, context: Map<LocalDate, SessionKind> = emptyMap()): List<LocalDate> {
        val n = kinds.size
        val sorted = days.sorted()
        var best: List<LocalDate>? = null
        var bestScore = Double.NEGATIVE_INFINITY
        val cur = arrayOfNulls<LocalDate>(n)
        val used = BooleanArray(sorted.size)
        fun rec(k: Int) {
            if (k == n) {
                val sc = score(kinds, cur.map { it!! }, context)
                if (sc > bestScore) { bestScore = sc; best = cur.map { it!! } }
                return
            }
            for (di in sorted.indices) {
                if (used[di] || (allowed != null && !allowed(kinds[k], sorted[di]))) continue
                used[di] = true; cur[k] = sorted[di]
                rec(k + 1)
                used[di] = false
            }
        }
        rec(0)
        // If a restriction made it impossible, fall back to the earliest free days.
        return best ?: sorted.take(n)
    }

    private fun score(kinds: List<SessionKind>, dates: List<LocalDate>, context: Map<LocalDate, SessionKind>): Double {
        val byDate = context + dates.indices.associate { dates[it] to kinds[it] }
        var sc = 0.0
        fun hard(k: SessionKind?) = k == SessionKind.KEY || k == SessionKind.SIM
        for ((d, k) in byDate) {
            val next = byDate[d.plusDays(1)]
            val dow = d.dayOfWeek
            val weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY
            if (k == SessionKind.STRENGTH && next == SessionKind.STRENGTH) sc -= 100
            if (k == SessionKind.STRENGTH && dow == DayOfWeek.SUNDAY) sc -= 12
            if (k == SessionKind.STRENGTH && hard(next)) sc -= 40
            if (k == SessionKind.STRENGTH && next == SessionKind.LONG_EASY) sc -= 10
            if (hard(k) && next == SessionKind.LONG_EASY) sc -= 18
            if (k == SessionKind.LONG_EASY && hard(next)) sc -= 18
            if (hard(k) && next == SessionKind.STRENGTH) sc -= 6
            if (k != SessionKind.STRENGTH && next != null && next != SessionKind.STRENGTH) sc -= 5
            if (k == SessionKind.LONG_EASY) sc += if (weekend) 15 else -20
            if (k == SessionKind.KEY) sc += if (dow in listOf(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)) 6 else 0
            if (k == SessionKind.SIM) sc += if (weekend) 8 else 0
            if (byDate[d.plusDays(1)] != null && byDate[d.plusDays(2)] != null) sc -= 8
        }
        return sc
    }

    // ------------------------------------------------------------------------------------- session content

    private fun runLegText(stage: RunStage, plan: Boolean = true): String = when (stage) {
        RunStage.RACE_RUNS -> "Run legs: 1 km easy-steady run (RPE 6) between stations."
        RunStage.INTERVALS -> "Run legs: run the first 400 m, then walk briskly at 5-6% incline to complete the 1 km. Your knee is cleared for run intervals, not yet full 1 km runs."
        else -> (if (plan) "Run legs: your knee is not cleared for running yet, so use 4 minutes on the rower or SkiErg at RPE 5, or a brisk 1 km incline walk (5-8%), in place of each 1 km run. That keeps the engine work without the impact." else "Run legs: run what your knee allows and walk briskly for the rest; that is a fine race plan.")
    }

    private val PREHAB =
        "Hip-abductor and glute prehab (10 min): side-lying hip abduction 2 x 15 each side, banded lateral walks 2 x 10 steps each way, single-leg glute bridge 2 x 12, side plank 2 x 30 s each side. Slow and controlled; stop if the outside of the knee flares."

    private fun sessionFor(c: Ctx, d: Draft, allowRun: Boolean): PlannedSession = when (d.kind) {
        SessionKind.STRENGTH -> strength(c, d)
        SessionKind.KEY, SessionKind.SIM -> key(c, d, allowRun)
        SessionKind.LONG_EASY -> longEasy(c, d, allowRun)
        SessionKind.EASY -> easy(c, d, allowRun)
        SessionKind.REST -> restSession(d.date)
    }

    private fun strength(c: Ctx, d: Draft): PlannedSession {
        val hinge = GymBuilder.nextHinge(c.records).name
        val uppers = GymBuilder.nextUpperMovements(c.records).joinToString(" and ") { it.replace("-", " ") }
        val stations = finisherStations(c.i, min(1, d.strengthNo))
        val phasePrimer = when (c.phase) { RacePhase.BASE -> 10; RacePhase.BUILD -> 15; RacePhase.SPECIFIC -> 10; RacePhase.RACE, RacePhase.KICKOFF -> 0; else -> 8 }
        // Warm-up 5 + prehab 10 + at least 20 minutes of lifts come first; the finisher gets what is left. A short
        // (ramp or kick-off) session under 40 minutes in the base, build or specific phases has no finisher at all.
        val shortRamp = d.minutes < 40 && (c.phase == RacePhase.BASE || c.phase == RacePhase.BUILD || c.phase == RacePhase.SPECIFIC)
        val primer = if (phasePrimer == 0 || shortRamp) 0 else min(phasePrimer, max(5, d.minutes - 35))
        val level = { s: HyroxStation -> stationTargets(c).getValue(s) }
        val detail = mutableListOf<String>()
        detail += "Warm up 5 minutes: easy cardio, then bodyweight squats and hip hinges."
        val liftNote = when {
            c.phase == RacePhase.KICKOFF -> "First session back: squat, $hinge, calf raise, then $uppers, 2 sets each with plenty in reserve (the app picks loads from your history). Keep it easy; the point is to start."
            c.phase == RacePhase.RACE -> "Race week: same weights as usual, only 2 sets of each lift, and finish feeling fresh. Squat, $hinge, calf raise and one upper-body pair."
            c.phase == RacePhase.TAPER -> "Taper: keep the weights, do about 60% of the sets. Squat, $hinge, calf raise, then $uppers."
            c.deload -> "Lighter week: same exercises, 2 sets each, leaving plenty in reserve. Squat, $hinge, calf raise, then $uppers."
            else -> "Squat, $hinge, calf raise, then $uppers (the app picks loads from your history; keep 2 or more reps in reserve)."
        }
        detail += liftNote
        detail += PREHAB
        if (primer > 0) {
            if (c.phase == RacePhase.BASE) {
                detail += "Station technique primer (about $primer min, light and easy, never a test):"
            } else {
                detail += "Hyrox station finisher (about $primer min) at this phase's ladder level:"
            }
            stations.forEach { detail += "  " + stationLine(c, it, level(it)) }
            if (c.kneeCaution && stations.any { it.kneeHeavy }) detail += "  Knee-heavy station: keep it at a level that stays under 3/10 and stop if it flares."
        }
        val why = if (c.phase == RacePhase.RACE) "Light strength keeps the strength you built while you freshen up (Bosquet 2007: keep intensity, cut volume)."
        else "Two strength days a week on separate days from hard endurance keep strength and size without blunting power (Schumann et al. concurrent-training meta-analysis); hip-abductor work is the standard first step for an irritable ITB (physio consensus, clinical rather than trial evidence)."
        val title = if (d.strengthNo == 0) "Strength A: legs, hip prehab" + (if (primer > 0 && c.phase != RacePhase.BASE) " + station finisher" else "") else "Strength B: legs, upper, hip prehab" + (if (primer > 0 && c.phase != RacePhase.BASE) " + station finisher" else "")
        return PlannedSession(
            d.date, SessionKind.STRENGTH, title, d.minutes, Intensity.STRENGTH, 0, 6..8, listOf(Modality.GYM), detail, why,
            LaunchHint(SessionType.GYM, hyroxFocus = if (primer > 0) stations.toSet() else emptySet(), minutes = d.minutes),
        )
    }

    private fun repsFor(minutes: Int, workMin: Int, restMin: Int, fixed: Int, lo: Int, hi: Int) = ((minutes - fixed + restMin) / (workMin + restMin)).coerceIn(lo, hi)

    private fun key(c: Ctx, d: Draft, allowRun: Boolean): PlannedSession {
        val m = d.minutes
        val erg = ergOfWeek(c.i)
        val runOk = allowRun && c.stage >= RunStage.INTERVALS
        val mods = mutableListOf(Modality.ROW, Modality.SKIERG, Modality.TREADMILL_WALK, Modality.BIKE)
        if (runOk) mods.add(2, Modality.TREADMILL_RUN)
        return when {
            c.phase == RacePhase.BASE -> {
                val reps = repsFor(m, 6, 3, 15, if (m < 40) 2 else 3, 5)
                PlannedSession(
                    d.date, SessionKind.KEY, "Aerobic intervals: $reps x 6 min", m, Intensity.HARD, reps * 6, 6..7, mods,
                    listOf(
                        "Warm up 10 minutes easy (RPE 3).",
                        "$reps x 6 minutes at RPE 6-7 (comfortably hard, short sentences only), 3 minutes easy between.",
                        "Cool down 5 minutes easy.",
                        "Choose the ${erg.displayName} this week (the other one next week), or incline walking at 6-10%, or the bike. Treadmill running is not used in this phase.",
                    ),
                    "This is the 'hard 20%': a little aerobic work above easy builds VO2peak, while a mostly-easy week (about 80/20) is a safe default with no proven advantage for other distributions (Silva Oliveira et al. 2024).",
                    LaunchHint(SessionType.TREADMILL, TreadmillStyle.INTERVALS, setOf(erg), m), includesRunning = false,
                )
            }
            c.phase == RacePhase.BUILD -> {
                val threshold = c.i == 6
                val reps = if (threshold) repsFor(m, 8, 3, 15, 2, 4) else repsFor(m, 4, 3, 15, if (m < 40) 2 else 3, 6)
                val work = if (threshold) 8 else 4
                val rpe = if (threshold) 7..8 else 8..9
                PlannedSession(
                    d.date, SessionKind.KEY, if (threshold) "Threshold intervals: $reps x 8 min" else "VO2 intervals: $reps x 4 min", m, Intensity.HARD, reps * work, rpe, mods,
                    listOf(
                        "Warm up 10 minutes easy (RPE 3) with 3 short pick-ups.",
                        "$reps x $work minutes at RPE ${rpe.first}-${rpe.last}${if (threshold) " (hard but steady: you could hold it for 30 minutes)" else " (hard, last rep still controlled)"}, 3 minutes easy between.",
                        "Cool down 5 minutes easy.",
                        "On the ${erg.displayName} (alternate with the other one) or the bike" + if (runOk) ", or on the treadmill running if the knee has been calm" else ". Treadmill running only comes in once your knee stage allows it.",
                    ),
                    "Threshold and VO2 intervals are the 'hard 20%'; the 2024 meta-analysis found modest VO2peak gains from polarized training with no clear performance edge, so they stay short and everything else stays easy.",
                    LaunchHint(SessionType.TREADMILL, TreadmillStyle.INTERVALS, setOf(erg), m), includesRunning = runOk,
                )
            }
            else -> specificKey(c, d, mods, runOk)
        }
    }

    private fun stationList(c: Ctx, stations: List<HyroxStation>): List<String> =
        stations.map { "  " + stationLine(c, it, stationTargets(c).getValue(it)) }

    private fun specificKey(c: Ctx, d: Draft, mods: MutableList<Modality>, runOk: Boolean): PlannedSession {
        val m = d.minutes
        val rl = runLegText(c.stage)
        val all = HyroxStation.entries
        val first4 = all.take(4); val last4 = all.drop(4)
        val hardM = (0.40 * m).roundToInt()
        val modsH = listOf(Modality.HYROX_STATIONS, Modality.ROW, Modality.SKIERG, Modality.TREADMILL_WALK)
        val whySpecific = "Race-specific practice matters because a weak station can undo a good runner, even though one small study (n = 11) found aerobic fitness tracked run and total time but not station time."
        val caution = if (c.kneeCaution) listOf("Knee note: if the burpee broad jumps or lunges push the knee above 3/10, do the rower or an incline walk for that station instead.") else emptyList()
        return when (c.i) {
            9 -> PlannedSession(
                d.date, SessionKind.SIM, "Half simulation: 4 x [run leg + station]", m, Intensity.HARD, hardM, 7..8, modsH,
                listOf("Warm up 10 minutes easy.", "Do the first four stations at race standard, each after a run leg, at controlled effort (RPE 7-8, not all-out):") +
                    stationList(c, first4) + listOf(rl, "Cool down 5 minutes. Note how each station felt; that tells us what to keep working on.") + caution,
                whySpecific,
                LaunchHint(SessionType.HYROX, hyroxFocus = first4.toSet(), minutes = m), includesRunning = runOk,
            )
            10 -> PlannedSession(
                d.date, SessionKind.KEY, "Hyrox circuit: stations 5-8 at race standard", m, Intensity.HARD, hardM, 7..8, modsH,
                listOf("Warm up 10 minutes easy.", "Second half of the race, each station after a run leg, at race load:") +
                    stationList(c, last4) + listOf(rl, "Cool down 5 minutes.") + caution,
                whySpecific,
                LaunchHint(SessionType.HYROX, hyroxFocus = last4.toSet(), minutes = m), includesRunning = runOk,
            )
            11 -> PlannedSession(
                d.date, SessionKind.SIM, "Near-full simulation rehearsal", m, Intensity.HARD, hardM, 7..8, modsH,
                listOf("Warm up 10 minutes easy.", "8 x [run leg + station] at race loads, controlled effort (RPE 7-8): finish feeling you could do a bit more, not empty.") +
                    stationList(c, all) + listOf(rl, "Practise the plan for race day: fuel, pacing, transitions. Cool down 5 minutes.") + caution,
                "A controlled rehearsal checks pacing and kit without taking a big recovery bill; it is a dress rehearsal, not a test (heuristic, not trial evidence).",
                LaunchHint(SessionType.HYROX, hyroxFocus = all.toSet(), minutes = m), includesRunning = runOk,
            )
            12 -> PlannedSession(
                d.date, SessionKind.KEY, "Sharpen: race-pace pieces", m, Intensity.HARD, hardM, 7..8, modsH,
                listOf("Warm up 10 minutes easy.", "3 x [2 stations at race load for about 3 minutes + a run leg or its substitute], 3 minutes easy between rounds. Stations: SkiErg, sled push, rower, wall balls.", rl, "Cool down 5 minutes. Stop each piece while it still feels good.") + caution,
                "Sharpening keeps intensity while volume starts to fall; intensity is the part of a taper that should not be cut (Bosquet et al. 2007).",
                LaunchHint(SessionType.HYROX, hyroxFocus = setOf(HyroxStation.SKIERG, HyroxStation.SLED_PUSH, HyroxStation.ROW, HyroxStation.WALL_BALLS), minutes = m), includesRunning = runOk,
            )
            13 -> PlannedSession(
                d.date, SessionKind.KEY, "Taper: short race-pace pieces", m, Intensity.HARD, min(hardM, 18), 7..8, modsH,
                listOf("Warm up 10 minutes easy.", "2 x [2 stations at race load for about 3 minutes + a run leg or its substitute], 3 minutes easy between. Same pace as before, just fewer rounds.", rl, "Cool down 5 minutes. You should leave feeling sharp.") + caution,
                "Taper volume falls by about 40-60% while intensity and frequency stay (Bosquet et al. 2007, 27 studies; about a 3% gain on average).",
                LaunchHint(SessionType.HYROX, hyroxFocus = setOf(HyroxStation.SKIERG, HyroxStation.SLED_PUSH, HyroxStation.ROW, HyroxStation.WALL_BALLS), minutes = m), includesRunning = runOk,
            )
            else -> PlannedSession(
                d.date, SessionKind.KEY, "Race-week sharpener", m, Intensity.HARD, min(hardM, 12), 7..7, modsH,
                listOf("Warm up 10 minutes easy.", "3 x 3 minutes at race pace (RPE 7) on the rower, SkiErg or incline walk, 2 minutes easy between, then 2 short touches (1-2 minutes) of sled push and wall balls at race load.", "Stop feeling fresh. Nothing hard after Wednesday."),
                "A short sharpener early in the week keeps the legs awake while the rest of the taper restores them (Bosquet et al. 2007).",
                LaunchHint(SessionType.HYROX, hyroxFocus = setOf(HyroxStation.SLED_PUSH, HyroxStation.WALL_BALLS), minutes = m),
            )
        }
    }

    private fun blockSplit(minutes: Int, parts: List<String>): List<String> {
        val each = max(5, floor5(minutes.toDouble() / parts.size))
        var left = minutes
        return parts.mapIndexed { idx, p ->
            val mm = if (idx == parts.lastIndex) left else each
            left -= mm
            "$p: $mm min"
        }
    }

    private fun longEasy(c: Ctx, d: Draft, allowRun: Boolean): PlannedSession {
        val m = d.minutes
        val runMin = if (allowRun && c.stage >= RunStage.CONTINUOUS) min(20, RunStageLogic.CONTINUOUS_LADDER_MIN[RunStageLogic.rungOf(RunStage.CONTINUOUS, if (c.stage == RunStage.CONTINUOUS) c.rung else 3)]) else 0
        val parts = mutableListOf("Rower (easy, 20-22 spm)", "Incline treadmill walk (4-8%)", "SkiErg (easy)", "Bike or incline walk")
        if (m < 70) parts.removeAt(3)
        val rest = m - runMin
        val blocks = blockSplit(rest, parts).toMutableList()
        if (runMin > 0) blocks += "Easy run block: $runMin min (RPE 3-4, conversational), walk the first and last minute"
        return PlannedSession(
            d.date, SessionKind.LONG_EASY, "Long easy aerobic: $m min", m, Intensity.EASY, 0, 2..4, listOf(Modality.ROW, Modality.TREADMILL_WALK, Modality.SKIERG, Modality.BIKE) + (if (runMin > 0) listOf(Modality.RUN_WALK) else emptyList()),
            listOf("Keep it conversational the whole way (RPE 4 or less). Rotate through several movements so no single one piles up:") + blocks +
                (if (runMin == 0) listOf("No running in this one yet: the knee-sparing movements carry the aerobic volume while running comes back gradually.") else emptyList()) +
                listOf("Drink and snack as you would in the race."),
            "Most of your endurance time should be easy; long easy sessions build the aerobic base that carries the running share of a Hyrox (about 59% of finishing time in a small first study, n = 11).",
            LaunchHint(SessionType.TREADMILL, if (m >= 60) TreadmillStyle.LONG_ROLLING else TreadmillStyle.STEADY, emptySet(), m), includesRunning = runMin > 0,
        )
    }

    private fun easy(c: Ctx, d: Draft, allowRun: Boolean): PlannedSession {
        val m = d.minutes
        val stage = if (allowRun) c.stage else RunStage.NONE
        val run = when (stage) {
            RunStage.NONE -> null
            RunStage.RUN_WALK -> RunStageLogic.RUN_WALK_LADDER[RunStageLogic.rungOf(RunStage.RUN_WALK, if (c.stage == RunStage.RUN_WALK) c.rung else 0)]
            else -> null
        }
        val contMin = if (stage >= RunStage.CONTINUOUS) RunStageLogic.CONTINUOUS_LADDER_MIN[RunStageLogic.rungOf(RunStage.CONTINUOUS, if (c.stage == RunStage.CONTINUOUS) c.rung else 3)] else 0
        val detail = mutableListOf<String>()
        var mods = listOf(Modality.ROW, Modality.SKIERG, Modality.TREADMILL_WALK, Modality.BIKE)
        var running = false
        when {
            run != null -> {
                running = true
                mods = listOf(Modality.RUN_WALK, Modality.TREADMILL_WALK, Modality.BIKE)
                detail += "Walk 5 minutes warm-up at 1% incline."
                detail += "Run-walk: ${run.label} at 1% incline, running at a pace where you could chat (RPE 3-4). Knee 2/10 or less the whole time; if it climbs, switch to walking."
                detail += "Fill the rest of the time with easy rower, bike or incline walking."
                detail += "Each calm session (knee 2/10 or less) moves you up the run-walk ladder: 1/2 min, then 2/1, 3/1, 5/1."
            }
            stage >= RunStage.CONTINUOUS && contMin > 0 -> {
                running = true
                mods = listOf(Modality.TREADMILL_RUN, Modality.ROW, Modality.BIKE)
                detail += "Warm up 5 minutes walking, then $contMin minutes of easy continuous running (RPE 3-4, 1% incline)."
                detail += "Fill the rest with easy rower, bike or incline walking. Stop the run and walk if the knee goes above 2/10."
            }
            else -> {
                detail += "Easy aerobic work, RPE 4 or less: split the time across rower, SkiErg, incline walk (4-8%) and bike."
                detail += "No running in this one yet. Your knee gets calm, strong hips first (the strength days), then graded run-walk when the knee has been calm for about two weeks."
            }
        }
        return PlannedSession(
            d.date, SessionKind.EASY, "Easy aerobic: $m min", m, Intensity.EASY, 0, 2..4, mods, detail,
            "Easy aerobic time is the bulk of the plan (about 80% of endurance time); the staged run-walk return is practitioner consensus for ITB, not trial-proven.",
            LaunchHint(SessionType.TREADMILL, TreadmillStyle.STEADY, emptySet(), m), includesRunning = running,
        )
    }

    // ------------------------------------------------------------------------------------- headline / focus

    private fun headline(c: Ctx): String {
        val label = when {
            c.phase == RacePhase.RACE -> "Race week"
            c.deload -> "Week ${c.i}: lighter week"
            else -> "Week ${c.i} of ${WEEKS - 1}: ${c.phase.label}"
        }
        return label
    }

    private fun focus(c: Ctx): String = when {
        c.phase == RacePhase.BASE && c.deload -> "A lighter week to absorb the first block. Same sessions, less volume."
        c.phase == RacePhase.BASE -> "Build the easy aerobic base and calm the knee: rower, SkiErg, incline walking, strength with hip prehab."
        c.phase == RacePhase.BUILD && c.deload -> "A lighter week before the specific block."
        c.phase == RacePhase.BUILD -> "More aerobic power (VO2 and threshold intervals), heavier station work, and graded running as the knee allows."
        c.phase == RacePhase.SPECIFIC -> when (c.i) {
            9 -> "Hyrox-specific: a half simulation. Stations 1-4 at race standard."
            10 -> "Hyrox-specific: stations 5-8 at race standard."
            else -> "Hyrox-specific: the near-full simulation rehearsal, at controlled effort."
        }
        c.phase == RacePhase.SHARPEN -> "Volume eases to about 85% of the peak week while the pace stays race-like."
        c.phase == RacePhase.TAPER -> "Volume drops by about 40% with the same intensity: arrive fresh, not flat."
        else -> "Short sharpeners early in the week, nothing hard after Wednesday, rest Friday, race Saturday."
    }
}
