package com.calum.traininglog.core

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** The default target event: a HYROX simulation on Saturday 27 February 2027. */
val DEFAULT_RACE_DATE: LocalDate = LocalDate.of(2027, 2, 27)

/**
 * Settings for the race-preparation plan, stored in [UserSettings.racePlan]. Every field has a default so old
 * saved JSON (which has none) still decodes.
 *
 * @property sessionsPerWeek 3 to 6. Fewer sessions drop the easy extras first (second easy, easy, long easy) and
 * always keep the key session and two strength sessions.
 * @property maxWeeklyMinutes optional time cap (120 to 600 minutes, in steps of 15; null means no limit). When set,
 * [RacePlanner.build] scales the whole plan down so no week plans more than this (see there). Null leaves the plan
 * exactly as it is without a cap.
 */
@Serializable
data class RacePlanSettings(
    @Serializable(with = LocalDateSerializer::class) val raceDate: LocalDate = DEFAULT_RACE_DATE,
    val sessionsPerWeek: Int = 5,
    val enabled: Boolean = true,
    val maxWeeklyMinutes: Int? = null,
) {
    fun sanitized(): RacePlanSettings = copy(
        sessionsPerWeek = sessionsPerWeek.coerceIn(SESSIONS_RANGE),
        maxWeeklyMinutes = maxWeeklyMinutes?.let { snapCap(it) },
    )

    companion object {
        val SESSIONS_RANGE = 3..6
        val CAP_RANGE = 120..600
        const val CAP_STEP = 15

        /** Clamps [minutes] into [CAP_RANGE] and rounds it to the nearest [CAP_STEP]. */
        fun snapCap(minutes: Int): Int = (Math.round(minutes.toDouble() / CAP_STEP).toInt() * CAP_STEP).coerceIn(CAP_RANGE)
    }
}

enum class RacePhase(val label: String) {
    /** The short lead-in before week 1 ([RacePlan.leadIn]); never part of the 14 numbered weeks or the timeline. */
    KICKOFF("Kick-off"),
    BASE("Base"),
    BUILD("Build"),
    SPECIFIC("Hyrox-specific"),
    SHARPEN("Sharpen"),
    TAPER("Taper"),
    RACE("Race week"),
    /** After the race: the steady weekly plan of [RacePlanner.buildMaintenance]. Never part of the 14 race weeks. */
    MAINTAIN("Maintenance"),
}

enum class SessionKind { STRENGTH, KEY, LONG_EASY, EASY, SIM, REST }

enum class Intensity { EASY, HARD, STRENGTH }

/** What a session can be done on; the first entries are the preferred ones. */
enum class Modality(val label: String) {
    ROW("Rower"),
    SKIERG("SkiErg"),
    TREADMILL_WALK("Incline treadmill walk"),
    TREADMILL_RUN("Treadmill run"),
    RUN_WALK("Run-walk"),
    BIKE("Bike"),
    GYM("Gym"),
    HYROX_STATIONS("Hyrox stations"),
    REST("Rest"),
}

/** What the UI should open for this session; [minutes] is the planned time. */
data class LaunchHint(
    val sessionType: SessionType,
    val treadmillStyle: TreadmillStyle? = null,
    val hyroxFocus: Set<HyroxStation> = emptySet(),
    val minutes: Int,
    /** Opens the true race-order simulation (format and loads) instead of the normal guided Hyrox session. */
    val sim: SimPreset? = null,
    /** Opens the knee care routine (an optional add-on, see [KneeCare]) instead of a plan session. */
    val kneeCare: Boolean = false,
    /** Minutes of guided hip prehab that follow the lifting in a strength session (0: the session has none). It is part of [minutes]. */
    val prehabMinutes: Int = 0,
)

/**
 * One planned day. [minutes] is the whole session (warm-up and recoveries included); [hardMinutes] is the part
 * of it at RPE 7 or more (the "hard 20%"), and is 0 for easy and strength sessions. [detail] holds the steps,
 * [why] one plain sentence of the evidence behind it. [isRaceDay] marks the event itself, which is not counted
 * in [PlanWeek.plannedMinutes].
 */
data class PlannedSession(
    val date: LocalDate,
    val kind: SessionKind,
    val title: String,
    val minutes: Int,
    val intensity: Intensity,
    val hardMinutes: Int,
    val rpeTarget: IntRange,
    val modalityOptions: List<Modality>,
    val detail: List<String>,
    val why: String,
    val launch: LaunchHint,
    val isRaceDay: Boolean = false,
    /** True when the session includes running (so the UI can show the knee note). */
    val includesRunning: Boolean = false,
    /** Set when the session was moved by the user (or re-flowed after a skip): the day it was planned on. */
    val movedFrom: LocalDate? = null,
    /** Set when the session was skipped: shown calmly, not counted as planned or done. */
    val skipReason: SkipReason? = null,
    /** On a skipped session: the day it was re-flowed to (null: dropped for the week, no catching up). */
    val reflowedTo: LocalDate? = null,
) {
    val isSkipped: Boolean get() = skipReason != null
    val rpeLabel: String get() = if (rpeTarget.first == rpeTarget.last) "RPE ${rpeTarget.first}" else "RPE ${rpeTarget.first}-${rpeTarget.last}"
}

/**
 * One Monday-Sunday week of the plan. [easyShareTarget] is the target share of ENDURANCE time that is easy
 * (0.8). [stationTargets] is the ladder level aimed for on each station this week. [adjustment] explains, in
 * no-guilt words, why a week repeats or steps back (null when nothing was adjusted).
 */
data class PlanWeek(
    val index: Int,
    val start: LocalDate,
    val phase: RacePhase,
    val deload: Boolean,
    val plannedMinutes: Int,
    val easyShareTarget: Double,
    val runStage: RunStage,
    /** True when [runStage] is a projection (a future week); it is recomputed from the logs every day. */
    val runStageProjected: Boolean,
    val sessions: List<PlannedSession>,
    val headline: String,
    val focus: String,
    val stationTargets: Map<HyroxStation, Int>,
    val adjustment: String? = null,
    /** Last day of a lead-in (the Sunday before week 1); null for the ordinary Monday-Sunday weeks. */
    val lastDay: LocalDate? = null,
    /** Optional knee care add-ons this week (not one of the sessions, not in the planned minutes). */
    val kneeCare: KneeCareStatus = KneeCareStatus(),
) {
    val end: LocalDate get() = lastDay ?: start.plusDays(6)
    /** True for the kick-off lead-in ([RacePlan.leadIn]), which has [index] 0. */
    val isLeadIn: Boolean get() = phase == RacePhase.KICKOFF
    private val counted: List<PlannedSession> get() = sessions.filter { !it.isRaceDay && it.kind != SessionKind.REST && it.skipReason == null }
    /** Sessions skipped this week (shown calmly; they are not part of the planned minutes). */
    val skippedSessions: List<PlannedSession> get() = sessions.filter { it.skipReason != null }
    val strengthMinutes: Int get() = counted.filter { it.intensity == Intensity.STRENGTH }.sumOf { it.minutes }
    val enduranceMinutes: Int get() = counted.filter { it.intensity != Intensity.STRENGTH }.sumOf { it.minutes }
    val hardMinutes: Int get() = counted.sumOf { it.hardMinutes }
    /** Planned share of endurance time that is easy (1 when there is none). */
    val easyShare: Double get() = if (enduranceMinutes == 0) 1.0 else 1.0 - hardMinutes.toDouble() / enduranceMinutes
    val label: String get() = if (deload) "${phase.label} (lighter week)" else phase.label
}

data class PhaseSpan(val phase: RacePhase, val firstWeek: Int, val lastWeek: Int, val start: LocalDate, val end: LocalDate)

/**
 * The whole plan. [baselineMinutes] is the weekly starting volume derived from the logs (or the default),
 * [peakMinutes] the largest planned week, [runStatus] the knee-gated running state it was built with.
 */
data class RacePlan(
    val weeks: List<PlanWeek>,
    val race: LocalDate,
    val phaseTimeline: List<PhaseSpan>,
    val baselineMinutes: Int,
    val peakMinutes: Int,
    val runStatus: RunStatus,
    /**
     * Gentle kick-off sessions from tomorrow (today when nothing is logged today) to the Sunday before week 1, so
     * the plan can start before its first Monday without re-numbering the 14 weeks. Null once week 1 has begun,
     * or when no day is left. It has [PlanWeek.index] 0 and phase [RacePhase.KICKOFF].
     */
    val leadIn: PlanWeek? = null,
    /** True when [baselineMinutes] is the real recent average from the logs (false: the default for a new user). */
    val baselineFromHistory: Boolean = false,
    /** Plain-words note on where the starting load came from and why the peak is what it is (null for the default). */
    val startNote: String? = null,
    /** True for the after-race maintenance plan ([RacePlanner.buildMaintenance]): no race countdown, no phases. */
    val maintenance: Boolean = false,
) {
    val start: LocalDate get() = weeks.first().start
    fun week(index: Int): PlanWeek? = weeks.firstOrNull { it.index == index }
    fun weekOf(date: LocalDate): PlanWeek? = weeks.firstOrNull { date in it.start..it.end }
    /** The lead-in when [date] falls inside it, else the numbered week containing [date]. */
    fun segmentOf(date: LocalDate): PlanWeek? = leadIn?.takeIf { date in it.start..it.end } ?: weekOf(date)
    fun daysToGo(today: LocalDate): Long = ChronoUnit.DAYS.between(today, race)
}

/** Done versus planned time for a week, with wording that never frames a shortfall as a miss. */
data class WeekProgress(
    val plannedMinutes: Int,
    val doneMinutes: Int,
    /** 0..1 for a progress bar (capped at 1). */
    val fraction: Double,
    val message: String,
    /** Sessions skipped this week; they are neither planned nor done, and never framed as a miss. */
    val skipped: Int = 0,
)
