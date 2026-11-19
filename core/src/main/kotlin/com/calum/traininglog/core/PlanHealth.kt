package com.calum.traininglog.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class HealthLevel { NONE, GENTLE, SUGGEST }

/**
 * How the plan is going, in kind terms, and whether a fresh plan from today is worth suggesting.
 *
 * [level]: SUGGEST shows the replan card; GENTLE is only a quiet line (no alert); NONE is silence. [reasons] are
 * plain, no-blame sentences ('quiet week', 'rough stretch', never 'failed' or 'missed'). [physioNote] is set with a
 * knee-based suggestion. These thresholds are this app's own sensible rules, not research.
 *
 * SUGGEST when any of: 3 or more planned (non-rest, past) sessions were skipped or not done in the last 10 days; each of
 * the last 2 completed weeks had under 40% of its planned minutes; 3 or more unwell skips in 7 days; a knee score of 5 or
 * more, or two sessions at 4 or more, in the last 2 weeks. GENTLE for milder patterns (2 skips in a week, 2 sessions
 * left in 10 days, or one quiet week). Never during the kick-off (before week 1). Once dismissed it stays quiet until
 * [PlanAdjustments.replanDismissedUntil].
 */
data class PlanHealth(
    val level: HealthLevel,
    val reasons: List<String>,
    val summary: String,
    val physioNote: String? = null,
) {
    companion object {
        const val WINDOW_DAYS = 10L
        const val SKIPS_FOR_SUGGEST = 3
        const val QUIET_WEEK_BELOW = 0.40
        const val UNWELL_FOR_SUGGEST = 3
        const val KNEE_DAYS = 14L

        const val SUGGEST_SUMMARY = "Rough stretches happen. A fresh plan from today might help."
        const val GENTLE_SUMMARY = "A quieter few days, and that is fine. The plan simply carries on at your pace."
        private val NONE = PlanHealth(HealthLevel.NONE, emptyList(), "")

        fun assess(plan: RacePlan, records: List<SessionRecord>, profile: Profile, today: LocalDate): PlanHealth {
            if (today < plan.start || today > plan.race) return NONE
            val adj = profile.planAdjustments
            val until = adj.replanDismissedUntil
            if (until != null && today < until) return NONE

            val segments = listOfNotNull(plan.leadIn) + plan.weeks
            val windowStart = today.minusDays(WINDOW_DAYS)
            var left = 0
            for (w in segments) {
                val done = RacePlanner.doneSessions(w, records, today)
                for (s in w.sessions) {
                    if (s.isRaceDay || s.kind == SessionKind.REST || s.date < windowStart || s.date >= today) continue
                    if (s.skipReason != null) { if (s.reflowedTo == null) left++ } else if (s !in done) left++
                }
            }
            val reasons = mutableListOf<String>()
            var suggest = false
            var physio: String? = null

            if (left >= SKIPS_FOR_SUGGEST) {
                suggest = true
                reasons += "$left planned sessions were skipped or left for now in the last $WINDOW_DAYS days."
            }

            val completed = plan.weeks.filter { it.end < today }.takeLast(2)
            fun planned(w: PlanWeek) = w.sessions.filter { !it.isRaceDay && it.kind != SessionKind.REST }.sumOf { it.minutes }
            fun quiet(w: PlanWeek): Boolean {
                val p = planned(w)
                return p > 0 && RacePlanner.loggedMinutes(records, w.start, w.end) < QUIET_WEEK_BELOW * p
            }
            val lastTwoQuiet = completed.size == 2 && completed.all { quiet(it) }
            if (lastTwoQuiet) {
                suggest = true
                reasons += "The last two weeks were quiet weeks, under 40% of the planned minutes."
            }

            val unwell = adj.skips.count { it.reason == SkipReason.UNWELL && it.date in today.minusDays(6)..today }
            if (unwell >= UNWELL_FOR_SUGGEST) {
                suggest = true
                reasons += "You have been unwell on $unwell days in the last week. Rest comes first."
            }

            val knees = records.filter { it.date in today.minusDays(KNEE_DAYS)..today && it.kneePain != null }
            val worst = knees.maxOfOrNull { it.kneePain!! } ?: 0
            if (worst >= RunStageLogic.STEP_BACK_KNEE) {
                suggest = true
                reasons += "Your knee scored $worst/10 recently."
                physio = RunStageLogic.PHYSIO_NOTE
            } else if (knees.count { it.kneePain!! >= RunStageLogic.TWO_IN_A_ROW_KNEE } >= 2) {
                suggest = true
                reasons += "Your knee has scored 4/10 or more in two sessions recently."
                physio = RunStageLogic.PHYSIO_NOTE
            }

            if (suggest) return PlanHealth(HealthLevel.SUGGEST, reasons, SUGGEST_SUMMARY, physio)

            val skipsThisWeek = adj.skips.count { it.date in today.minusDays(6)..today }
            val gentleReasons = mutableListOf<String>()
            if (skipsThisWeek >= 2) gentleReasons += "Two sessions were skipped in the last week."
            else if (left == 2) gentleReasons += "Two planned sessions are waiting from the last $WINDOW_DAYS days."
            if (completed.isNotEmpty() && quiet(completed.last())) gentleReasons += "Last week was a quiet week."
            return if (gentleReasons.isEmpty()) NONE else PlanHealth(HealthLevel.GENTLE, gentleReasons, GENTLE_SUMMARY)
        }
    }
}

/** A plain, compact text to paste to Claude when the plan needs a fresh start. Includes private notes; keep it between you two. */
object ReplanExport {
    private val raceFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
    private val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

    private fun dayName(d: java.time.DayOfWeek) = d.getDisplayName(TextStyle.FULL, Locale.UK)

    fun text(plan: RacePlan, records: List<SessionRecord>, profile: Profile, today: LocalDate): String {
        val cfg = profile.settings.racePlan
        val rest = profile.restWeekdays.sorted().joinToString(" and ") { dayName(it) }.ifEmpty { "none" }
        val week = RacePlanner.thisWeek(plan, today)
        val days = plan.daysToGo(today)
        val sb = StringBuilder()
        sb.appendLine("RE-PLAN REQUEST $today")
        sb.appendLine("Race: HYROX simulation on ${dayName(plan.race.dayOfWeek)} ${plan.race.format(raceFmt)}, $days days to go.")
        sb.appendLine(
            when {
                week == null -> "Where I am: the plan has finished."
                week.isLeadIn -> "Where I am: kick-off, before week 1 (${plan.start.format(dayFmt)})."
                else -> "Where I am: week ${week.index} of ${RacePlanner.WEEKS}, ${week.label}."
            },
        )
        sb.appendLine()
        sb.appendLine("SETTINGS")
        sb.appendLine("- Sessions per week: ${cfg.sessionsPerWeek}")
        sb.appendLine("- Weekly time cap: ${cfg.maxWeeklyMinutes?.let { "$it minutes" } ?: "none"}")
        sb.appendLine("- Rest weekdays: $rest")
        sb.appendLine("- Event format assumption: 8 x (1 km run + station), Hyrox ${profile.settings.hyroxDivision.label} loads, done as a simulation")
        sb.appendLine("- Plan shape: 14 weeks (base, build, specific, sharpen, 2 week taper), about 80/20 easy/hard, strength twice a week")
        sb.appendLine()
        sb.appendLine("WEEKLY MINUTES, PLANNED VS DONE")
        val started = plan.weeks.filter { it.start <= today }
        if (started.isEmpty()) sb.appendLine("Week 1 has not started yet.")
        for (w in started) {
            val planned = w.plannedMinutes
            val done = RacePlanner.loggedMinutes(records, w.start, minOf(w.end, today))
            val skipped = w.skippedSessions.size
            sb.appendLine(
                "Week ${w.index} (${w.phase.label}${if (w.deload) ", lighter" else ""}, ${w.start.format(dayFmt)}-${w.end.format(dayFmt)}): " +
                    "planned $planned, done $done${if (skipped > 0) ", $skipped skipped" else ""}${if (w.end >= today) " (so far)" else ""}",
            )
        }
        val ahead = plan.weeks.filter { it.start > today }
        if (ahead.isNotEmpty()) sb.appendLine("Planned ahead: " + ahead.joinToString(", ") { "w${it.index} ${it.plannedMinutes}" })
        sb.appendLine()
        sb.appendLine("LAST 14 DAYS")
        sb.appendLine(Export.summary(records, today.minusDays(13), today))
        sb.appendLine()
        sb.appendLine("SKIPPED AND MOVED SESSIONS")
        val adj = profile.planAdjustments
        if (adj.skips.isEmpty() && adj.moves.isEmpty()) sb.appendLine("None recorded.")
        adj.skips.sortedBy { it.date }.forEach {
            sb.appendLine("- ${it.date} skipped: ${it.title ?: it.kind.name.lowercase()} (${it.reason.short})")
        }
        adj.moves.sortedBy { it.fromDate }.forEach {
            sb.appendLine("- ${it.fromDate} moved to ${it.toDate}: ${it.title ?: it.kind.name.lowercase()}")
        }
        sb.appendLine()
        sb.appendLine("KNEE AND RUNNING")
        val knees = records.filter { it.kneePain != null && it.date in today.minusDays(27)..today }.sortedBy { it.date }
        sb.appendLine(if (knees.isEmpty()) "No knee scores in the last 4 weeks." else "Knee scores, last 4 weeks: " + knees.joinToString(", ") { "${it.date} ${it.kneePain}/10" })
        sb.appendLine("Run stage: ${plan.runStatus.stage.label}. ${plan.runStatus.message}")
        plan.runStatus.physioNote?.let { sb.appendLine(it) }
        sb.appendLine()
        sb.appendLine(
            "Please re-plan my Hyrox prep from here for ${plan.race.format(raceFmt)}: keep about 80/20 easy/hard, a 2 week taper, " +
                "graded running return for my ITB knee, ${cfg.sessionsPerWeek} sessions a week with ${if (profile.restWeekdays.isEmpty()) "flexible rest days" else "$rest as rest days"}, " +
                "and tell me what to change in the app settings.",
        )
        return sb.toString().trimEnd()
    }
}
