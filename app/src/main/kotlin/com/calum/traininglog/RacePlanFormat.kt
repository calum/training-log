package com.calum.traininglog

import androidx.compose.runtime.compositionLocalOf
import com.calum.traininglog.core.Intensity
import com.calum.traininglog.core.PlanWeek
import com.calum.traininglog.core.PlannedSession
import com.calum.traininglog.core.RacePhase
import com.calum.traininglog.core.RacePlan
import com.calum.traininglog.core.RacePlanSettings
import com.calum.traininglog.core.RacePlanner
import com.calum.traininglog.core.RunStage
import com.calum.traininglog.core.SessionKind
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Today's date for the race-plan UI. Null means the device date (read it with `LocalToday.current ?: LocalDate.now()`); screenshot tests provide a fixed one so the
 * plan is shown at a chosen point.
 */
internal val LocalToday = compositionLocalOf<LocalDate?> { null }

/** Where one planned session stands, in words that never read as a miss. */
internal enum class SessionState {
    /** A matching logged session exists. */
    DONE,
    /** Today or later, not yet done. */
    UPCOMING,
    /** In the past with nothing logged: shown quietly as optional. */
    OPTIONAL,
}

/** Pure text and matching helpers for the race-plan screens (no Compose, unit tested). */
internal object RacePlanFormat {
    private val longDate = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)
    private val shortDate = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

    fun raceDateText(date: LocalDate): String = date.format(longDate)
    fun shortDay(date: LocalDate): String = date.format(shortDate)
    fun dayMonth(date: LocalDate): String = date.format(dayMonth)

    /** "100 days to go", "1 day to go", "Race day", or a warm line once it is over. */
    fun countdown(daysToGo: Long): String = when {
        daysToGo > 1 -> "$daysToGo days to go"
        daysToGo == 1L -> "1 day to go"
        daysToGo == 0L -> "Race day"
        else -> "Race done"
    }

    /** The big number for the hero (null on and after race day). */
    fun countdownNumber(daysToGo: Long): String? = if (daysToGo >= 1) daysToGo.toString() else null

    fun countdownUnit(daysToGo: Long): String = when {
        daysToGo > 1 -> "days to go"
        daysToGo == 1L -> "day to go"
        daysToGo == 0L -> "Race day"
        else -> "Race done"
    }

    /** "Week 1 of 14 · Base" (the first week before the plan starts; "Plan complete" after it). */
    fun weekHeadline(plan: RacePlan, today: LocalDate): String {
        val w = RacePlanner.thisWeek(plan, today) ?: return "Plan complete"
        val label = "Week ${w.index} of ${RacePlanner.WEEKS} · ${w.phase.label}"
        return label
    }

    /** Short one for the Home card: "Week 1 Base". */
    fun weekShort(plan: RacePlan, today: LocalDate): String {
        val w = RacePlanner.thisWeek(plan, today) ?: return "Plan complete"
        return "Week ${w.index} ${w.phase.label}"
    }

    /** "23-29 Nov" or "30 Nov - 6 Dec". */
    fun weekRange(w: PlanWeek): String =
        if (w.start.month == w.end.month) "${w.start.dayOfMonth}-${w.end.dayOfMonth} ${w.end.format(DateTimeFormatter.ofPattern("MMM", Locale.UK))}"
        else "${dayMonth(w.start)} - ${dayMonth(w.end)}"

    /** One calm line on what a phase is for. */
    fun phasePurpose(phase: RacePhase): String = when (phase) {
        RacePhase.BASE -> "Building the easy aerobic base and calming the knee. Most of it should feel comfortable."
        RacePhase.BUILD -> "Adding aerobic power and heavier station work, with running back only as the knee allows."
        RacePhase.SPECIFIC -> "Practising the race itself: stations at race standard, run legs after them."
        RacePhase.SHARPEN -> "Volume eases while the pace stays race-like. You should feel sharper, not more tired."
        RacePhase.TAPER -> "Training drops by about 40% with the same intensity, so you arrive fresh, not flat."
        RacePhase.RACE -> "Short sharpeners early, nothing hard after Wednesday, rest on Friday. Then the fun part."
    }

    fun intensityLabel(i: Intensity): String = when (i) {
        Intensity.EASY -> "Easy"
        Intensity.HARD -> "Hard"
        Intensity.STRENGTH -> "Strength"
    }

    /** Sessions that count towards a week (not the race itself or a rest day). */
    fun counted(w: PlanWeek): List<PlannedSession> = w.sessions.filter { !it.isRaceDay && it.kind != SessionKind.REST }

    private fun compatible(s: PlannedSession, r: SessionRecord): Boolean = when {
        s.kind == SessionKind.STRENGTH -> r.type == SessionType.GYM
        else -> r.type == SessionType.TREADMILL || r.type == SessionType.HYROX || r.type == SessionType.HIKE
    }

    /**
     * Which of the week's counted sessions have a matching logged session. Each record is used at most once, and
     * a session done a day or two off its planned date still counts (an evening swapped with the kids is normal).
     * Records after [today] never count.
     */
    fun doneSessions(w: PlanWeek, records: List<SessionRecord>, today: LocalDate): Set<PlannedSession> {
        val pool = records.filter { it.date in w.start..w.end && it.date <= today }.toMutableList()
        val done = mutableSetOf<PlannedSession>()
        for (s in counted(w).sortedBy { it.date }) {
            val pick = pool.filter { compatible(s, it) }
                .minWithOrNull(compareBy({ Math.abs(java.time.temporal.ChronoUnit.DAYS.between(it.date, s.date)) }, { it.date }))
                ?: continue
            if (Math.abs(java.time.temporal.ChronoUnit.DAYS.between(pick.date, s.date)) > 2) continue
            pool.remove(pick)
            done += s
        }
        return done
    }

    fun stateOf(s: PlannedSession, done: Set<PlannedSession>, today: LocalDate): SessionState = when {
        s in done -> SessionState.DONE
        s.date >= today -> SessionState.UPCOMING
        else -> SessionState.OPTIONAL
    }

    /** First session today or later that is not done, within the week (the one to start next). */
    fun nextSession(w: PlanWeek, done: Set<PlannedSession>, today: LocalDate): PlannedSession? =
        counted(w).filter { it !in done && it.date >= today }.minByOrNull { it.date }

    /** Today's planned session when it is still to do (null on a rest day, before the plan, after it, or once done). */
    fun todaysOpenSession(plan: RacePlan, records: List<SessionRecord>, today: LocalDate): PlannedSession? {
        val w = plan.weekOf(today) ?: return null
        val s = RacePlanner.today(plan, today) ?: return null
        if (s.isRaceDay || s.kind == SessionKind.REST) return null
        return if (s in doneSessions(w, records, today)) null else s
    }

    /** "2 of 5 done, 138 of 215 planned minutes" wrapped in encouraging words. */
    fun weekProgressText(done: Int, total: Int, doneMin: Int, plannedMin: Int, weekOver: Boolean): String {
        if (total == 0) return "A light week: nothing to tick off."
        val core = "$done of $total done, $doneMin of $plannedMin planned minutes"
        return when {
            done == 0 && !weekOver -> "Fresh week: $total sessions planned, $plannedMin minutes. Start whenever it suits."
            done == 0 -> "A quiet week, and that is fine. The plan picks up from where you are."
            done >= total -> "All done: $core. Anything more is a bonus."
            weekOver -> "Nice work: $core. The rest were optional."
            else -> "Nice work: $core. The rest are optional."
        }
    }

    fun capLabel(cap: Int?): String = if (cap == null) "No limit" else "$cap min"

    /** "4 h 15 min" for a cap, as a hint under the stepper. */
    fun capHours(cap: Int): String = "${cap / 60} h ${cap % 60} min".replace(" 0 min", "")

    /** The step shown when going down from [cap] (below the minimum it turns into "no limit"). */
    fun capDown(cap: Int?): Int? = when {
        cap == null -> RacePlanSettings.CAP_RANGE.last
        cap - RacePlanSettings.CAP_STEP < RacePlanSettings.CAP_RANGE.first -> RacePlanSettings.CAP_RANGE.first
        else -> cap - RacePlanSettings.CAP_STEP
    }

    /** Going up from the top of the range turns the cap off; from "no limit" it stays off. */
    fun capUp(cap: Int?): Int? = when {
        cap == null -> null
        cap + RacePlanSettings.CAP_STEP > RacePlanSettings.CAP_RANGE.last -> null
        else -> cap + RacePlanSettings.CAP_STEP
    }

    /** Short plain description of what each run stage means in the plan. */
    fun stageBlurb(stage: RunStage): String = when (stage) {
        RunStage.NONE -> "Walking, rower, SkiErg and bike instead of running"
        RunStage.RUN_WALK -> "Short run-walk intervals at about 1% incline"
        RunStage.CONTINUOUS -> "10 to 30 minutes of easy continuous running"
        RunStage.INTERVALS -> "Run intervals with walking recoveries"
        RunStage.RACE_RUNS -> "Race-style 400 to 1000 m runs after stations"
    }

    /** Ladder labels for the stage: ("Step 2 of 4", "2 min run / 1 min walk x 8"), or null when the stage has no ladder. */
    fun rungText(stage: RunStage, rung: Int): Pair<String, String>? {
        val steps: List<String> = when (stage) {
            RunStage.NONE -> return null
            RunStage.RUN_WALK -> com.calum.traininglog.core.RunStageLogic.RUN_WALK_LADDER.map { it.label }
            RunStage.CONTINUOUS -> com.calum.traininglog.core.RunStageLogic.CONTINUOUS_LADDER_MIN.map { "$it min easy, continuous" }
            RunStage.INTERVALS -> com.calum.traininglog.core.RunStageLogic.INTERVAL_LADDER
            RunStage.RACE_RUNS -> com.calum.traininglog.core.RunStageLogic.RACE_RUN_LADDER_M.map { "$it m runs after a station" }
        }
        val i = rung.coerceIn(0, steps.size - 1)
        return "Step ${i + 1} of ${steps.size}" to steps[i]
    }

    /** Key session's title for the all-weeks list ("Aerobic intervals: 4 x 6 min"), or a calm fallback. */
    fun keySession(w: PlanWeek): String =
        w.sessions.firstOrNull { it.kind == SessionKind.SIM || it.kind == SessionKind.KEY }?.title
            ?: w.sessions.firstOrNull { it.isRaceDay }?.title ?: "Easy movement"
}
