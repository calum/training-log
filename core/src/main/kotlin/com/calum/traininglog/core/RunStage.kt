package com.calum.traininglog.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * How much running the right ITB knee is currently cleared for. The race plan only schedules running that this
 * stage allows; everything else is replaced by rower, SkiErg, incline walking or the bike.
 *
 * Evidence and limits (see docs/hyrox-plan-evidence.md): a staged return (calm irritability, hip-abductor and
 * heavy-slow strength, then graded run-walk re-introduction) is practitioner consensus from physio sources and a
 * 2024 systematic review of clinical practice, NOT the result of controlled trials. The thresholds below
 * (2/10, 7 days, 2 or more clean runs) are sensible heuristics. The app is not a medical tool: persistent pain
 * means seeing a physio.
 */
enum class RunStage(val label: String) {
    /** Walking / incline walking only. */
    NONE("Walking only"),
    /** Run-walk intervals at about 1% incline. */
    RUN_WALK("Run-walk"),
    /** 10 to 30 minutes of continuous easy running. */
    CONTINUOUS("Continuous easy running"),
    /** Run intervals (including on the treadmill). */
    INTERVALS("Run intervals"),
    /** Compromised race-style runs (400 to 1000 m) after stations. */
    RACE_RUNS("Race runs");

    fun next(): RunStage = entries.getOrElse(ordinal + 1) { this }
    fun previous(): RunStage = entries.getOrElse(ordinal - 1) { this }
}

/** One rung of the run-walk ladder: [reps] repeats of [runSec] running then [walkSec] walking. */
data class RunWalkStep(val runSec: Int, val walkSec: Int, val reps: Int) {
    val runMinutes: Double get() = runSec * reps / 60.0
    val totalMinutes: Double get() = (runSec + walkSec) * reps / 60.0
    val label: String get() = "${fmt(runSec)} run / ${fmt(walkSec)} walk x $reps"
    private fun fmt(sec: Int) = if (sec % 60 == 0) "${sec / 60} min" else "$sec s"
}

/**
 * The result of [RunStageLogic.assess]: the stage, how far through its ladder he is, and a gentle status line.
 * Wording is deliberately neutral and never framed as failure.
 */
data class RunStatus(
    val stage: RunStage,
    /** Consecutive clean runs (knee 0-2/10, no flare in the previous 7 days) at this stage: the rung he is on. */
    val cleanRuns: Int,
    /** Date of the latest knee score of 3 or more, if any. */
    val lastFlare: LocalDate?,
    /** True when [lastFlare] is within 7 days of today: no advancing yet. */
    val flareRecent: Boolean,
    /** Date of the latest step back, if any. */
    val steppedBackOn: LocalDate?,
    /** One plain sentence for the screen (never red, never blaming). */
    val message: String,
    /** Gentle "see your physio" note, only when pain has been high or has hung around; null otherwise. */
    val physioNote: String?,
) {
    /** Current rung in the stage's ladder (0-based), capped to the ladder length. */
    val rung: Int get() = RunStageLogic.rungOf(stage, cleanRuns)
}

/**
 * Knee-gated running logic, computed from the logs only. Deterministic and pure.
 *
 * Rules (heuristics, not trial-proven):
 *  - Start at [RunStage.NONE]. Leave it either by 2 clean runs (he is already running) or by 14+ calm days with
 *    at least 4 knee-rated sessions of any type, all 2/10 or less, and no flare in the last 7 days.
 *  - Advance only after [required] consecutive clean runs, on distinct days, each at least 7 days after any knee
 *    score of 3 or more. A knee score of 3-4 HOLDS (the streak restarts, the stage stays).
 *  - Knee 5 or more, or two sessions in a row at 4 or more, steps back one stage with a gentle physio note.
 *  - 8 or more clean running days in the previous 28 days counts as 'consistent running' and lifts the stage to
 *    at least [RunStage.CONTINUOUS].
 *
 * What counts as a run: a Hyrox session with `run` station logs; a treadmill session whose average speed
 * (distance / time of the estimated log) is at least [RUN_SPEED_KMH] (walking, even steep, stays below it); or a
 * treadmill session whose `dayLabel` starts with "Run" (so the UI can label run-walk sessions explicitly).
 */
object RunStageLogic {
    /** Average treadmill speed at or above which a session is treated as running (km/h). */
    const val RUN_SPEED_KMH = 6.6
    const val FLARE_KNEE = 3
    const val STEP_BACK_KNEE = 5
    const val TWO_IN_A_ROW_KNEE = 4
    const val CLEAN_KNEE_MAX = 2
    const val FLARE_COOLDOWN_DAYS = 7L
    const val CALM_DAYS_FOR_FIRST_RUN = 14L
    const val CALM_SESSIONS_FOR_FIRST_RUN = 4

    val RUN_WALK_LADDER = listOf(RunWalkStep(60, 120, 8), RunWalkStep(120, 60, 8), RunWalkStep(180, 60, 6), RunWalkStep(300, 60, 5))
    val CONTINUOUS_LADDER_MIN = listOf(10, 15, 20, 30)
    val INTERVAL_LADDER = listOf("6 x 2 min run / 2 min walk at RPE 5-6", "5 x 3 min run / 2 min walk at RPE 6")
    val RACE_RUN_LADDER_M = listOf(400, 600, 800, 1000)

    /** Clean runs needed to leave a stage. */
    fun required(stage: RunStage): Int = when (stage) {
        RunStage.NONE -> 2
        RunStage.RUN_WALK -> RUN_WALK_LADDER.size
        RunStage.CONTINUOUS -> CONTINUOUS_LADDER_MIN.size
        RunStage.INTERVALS -> INTERVAL_LADDER.size
        RunStage.RACE_RUNS -> Int.MAX_VALUE
    }

    fun rungOf(stage: RunStage, cleanRuns: Int): Int = when (stage) {
        RunStage.NONE -> 0
        RunStage.RUN_WALK -> cleanRuns.coerceIn(0, RUN_WALK_LADDER.size - 1)
        RunStage.CONTINUOUS -> cleanRuns.coerceIn(0, CONTINUOUS_LADDER_MIN.size - 1)
        RunStage.INTERVALS -> cleanRuns.coerceIn(0, INTERVAL_LADDER.size - 1)
        RunStage.RACE_RUNS -> cleanRuns.coerceIn(0, RACE_RUN_LADDER_M.size - 1)
    }

    /** Just the stage, for callers that need nothing else. */
    fun stage(records: List<SessionRecord>, today: LocalDate): RunStage = assess(records, today).stage

    /** Seconds of running in [r] (0 when it was not a run). */
    fun runSeconds(r: SessionRecord): Int {
        return when (r.type) {
            SessionType.HYROX -> r.stations.filter { HyroxRuns.isRun(it) }.sumOf { run ->
                run.seconds ?: run.distanceM?.let { HyroxRuns.targetSec(it, 9.0) } ?: 0
            }
            SessionType.TREADMILL -> {
                val t = r.treadmill
                val labelled = r.dayLabel?.trim()?.startsWith("run", ignoreCase = true) == true
                val speed = if (t?.distanceM != null && t.completedSec > 0) t.distanceM * 3.6 / t.completedSec else null
                when {
                    t == null -> 0
                    labelled || (speed != null && speed >= RUN_SPEED_KMH) -> t.completedSec
                    else -> 0
                }
            }
            else -> 0
        }
    }

    fun isRun(r: SessionRecord): Boolean = runSeconds(r) >= 120

    fun assess(records: List<SessionRecord>, today: LocalDate): RunStatus {
        val recs = records.filter { it.date <= today }.sortedWith(compareBy({ it.date }, { it.id }))
        var stage = RunStage.NONE
        var streak = 0
        var lastRunDate: LocalDate? = null
        var lastFlare: LocalDate? = null
        var calmCount = 0
        var calmSince: LocalDate? = null
        var highInRow = 0
        var steppedBack: LocalDate? = null
        val cleanRunDays = ArrayDeque<LocalDate>()
        val flareDates = mutableListOf<LocalDate>()

        fun flareOk(d: LocalDate) = lastFlare == null || ChronoUnit.DAYS.between(lastFlare, d) >= FLARE_COOLDOWN_DAYS

        for (r in recs) {
            val k = r.kneePain
            val run = isRun(r)
            if (k != null) {
                if (k >= FLARE_KNEE) {
                    lastFlare = r.date
                    flareDates += r.date
                    calmCount = 0
                    calmSince = null
                }
                highInRow = if (k >= TWO_IN_A_ROW_KNEE) highInRow + 1 else 0
                if (k >= STEP_BACK_KNEE || highInRow >= 2) {
                    stage = stage.previous()
                    streak = 0
                    steppedBack = r.date
                    highInRow = 0
                    continue
                }
                if (k >= FLARE_KNEE) { streak = 0; continue } // hold
                if (calmCount == 0) calmSince = r.date
                calmCount++
            }
            if (k == null || k > CLEAN_KNEE_MAX) continue
            if (run) {
                if (flareOk(r.date)) {
                    if (lastRunDate != r.date) streak++
                    lastRunDate = r.date
                    cleanRunDays += r.date
                    while (cleanRunDays.isNotEmpty() && ChronoUnit.DAYS.between(cleanRunDays.first(), r.date) > 28) cleanRunDays.removeFirst()
                    if (stage < RunStage.CONTINUOUS && cleanRunDays.distinct().size >= 8) { stage = RunStage.CONTINUOUS; streak = 0 }
                    else if (streak >= required(stage) && stage != RunStage.RACE_RUNS) { stage = stage.next(); streak = 0 }
                }
            } else if (stage == RunStage.NONE && flareOk(r.date) && calmCount >= CALM_SESSIONS_FOR_FIRST_RUN &&
                calmSince != null && ChronoUnit.DAYS.between(calmSince, r.date) >= CALM_DAYS_FOR_FIRST_RUN
            ) {
                stage = RunStage.RUN_WALK
                streak = 0
            }
        }

        val flareRecent = lastFlare != null && ChronoUnit.DAYS.between(lastFlare, today) < FLARE_COOLDOWN_DAYS
        val recentStepBack = steppedBack != null && ChronoUnit.DAYS.between(steppedBack, today) <= 14
        val persistent = flareDates.count { ChronoUnit.DAYS.between(it, today) in 0..21 } >= 3 &&
            flareDates.filter { ChronoUnit.DAYS.between(it, today) in 0..21 }.let { d -> d.isNotEmpty() && ChronoUnit.DAYS.between(d.min(), d.max()) >= 14 }
        val physio = if ((steppedBack != null && ChronoUnit.DAYS.between(steppedBack, today) <= 21) || persistent) PHYSIO_NOTE else null
        val message = when {
            recentStepBack -> "Running is stepping back to ${stage.label.lowercase()} while the knee settles. That is the plan doing its job, not a setback."
            flareRecent -> "Holding at ${stage.label.lowercase()} until the knee has been calm for a week. Lifting, rowing and incline walking carry on as normal."
            stage == RunStage.NONE -> "Walking, rowing and lifting for now. Running comes back in small steps once the knee has been calm for a couple of weeks."
            else -> "Running stage: ${stage.label.lowercase()}. Each step needs a calm knee (2/10 or less) first."
        }
        return RunStatus(stage, streak, lastFlare, flareRecent, steppedBack, message, physio)
    }

    const val PHYSIO_NOTE =
        "If the knee pain persists for more than a couple of weeks, please see your physio. This app is not a medical tool."
}
