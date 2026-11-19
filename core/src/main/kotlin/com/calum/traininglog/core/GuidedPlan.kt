package com.calum.traininglog.core

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Guided timer for race-plan sessions that have no flow of their own (rower, SkiErg, bike, ...): pure logic.
 *
 *  - [GuidedPlan.from] turns a [PlannedSession] (its minutes, intensity and `detail` text) into timed steps:
 *    warm-up, then one EASY block (halfway check-in) or an interval set, then cool-down. Total = planned minutes.
 *  - [GuidedRun] is the running state (like CoreRun): advance it with real elapsed milliseconds.
 *  - [GuidedRecord] builds the saved SessionRecord so IntensityBalance can classify it.
 *  - [PlannedTreadmill] builds the TreadmillPlan for treadmill-modality sessions (incl. run-walk).
 */

enum class GuidedKind(val label: String) {
    WARMUP("WARM-UP"), EASY("EASY"), WORK("HARD"), RECOVERY("EASY BETWEEN"), COOLDOWN("COOL-DOWN"),
}

/** One timed step. [checkIn] marks the halfway "still able to chat?" step of an easy block. */
data class GuidedStep(
    val kind: GuidedKind,
    val name: String,
    val seconds: Int,
    val cue: String,
    val checkIn: Boolean = false,
) {
    val hard: Boolean get() = kind == GuidedKind.WORK
}

enum class GuidedEvent { PRE_WARNING, CHANGE, DONE }

/** The steps for one session on one [modality]. [rpeTarget] is the plan's target for the hard (or the only) effort. */
data class GuidedPlan(
    val modality: Modality,
    val steps: List<GuidedStep>,
    val rpeTarget: IntRange,
    val intervals: Boolean,
) {
    val totalSec: Int get() = steps.sumOf { it.seconds }
    val workSec: Int get() = steps.filter { it.hard }.sumOf { it.seconds }
    val workSteps: Int get() = steps.count { it.hard }

    /** The effort to preselect on the rating card: the middle of the target (8-9 gives 8, 2-4 gives 3). */
    val defaultRpe: Int get() = rpeTarget.first + (rpeTarget.last - rpeTarget.first) / 2

    companion object {
        /** Modalities the timer itself runs (the others, like the treadmill, open their own flow). */
        val TIMER_MODALITIES = listOf(Modality.ROW, Modality.SKIERG, Modality.BIKE)

        const val MIN_SESSION_MIN = 5

        /** The modality a session starts on: the first listed option, or the rower. */
        fun defaultModality(s: PlannedSession): Modality = s.modalityOptions.firstOrNull { it != Modality.REST } ?: Modality.ROW

        /** Chips to show at the top of the timer: every option of the session that can be done as a workout. */
        fun chipOptions(s: PlannedSession): List<Modality> = s.modalityOptions.filter { it != Modality.REST && it != Modality.GYM }

        private val REPS_RE = Regex("""(\d+)\s*x\s*(\d+)\s*min""", RegexOption.IGNORE_CASE)
        private val REST_RE = Regex("""(\d+)\s*minutes?\s*easy\s*between""", RegexOption.IGNORE_CASE)
        private val WARM_RE = Regex("""warm\s*up\s*(\d+)\s*min""", RegexOption.IGNORE_CASE)
        private val COOL_RE = Regex("""cool\s*down\s*(\d+)\s*min""", RegexOption.IGNORE_CASE)

        private fun first(re: Regex, lines: List<String>): Int? = lines.firstNotNullOfOrNull { re.find(it)?.groupValues?.get(1)?.toIntOrNull() }

        fun from(session: PlannedSession, modality: Modality? = null): GuidedPlan {
            val mod = modality ?: defaultModality(session)
            val minutes = max(MIN_SESSION_MIN, session.minutes)
            val hard = session.intensity == Intensity.HARD && session.hardMinutes > 0
            return if (hard) intervalPlan(session, mod, minutes) else easyPlan(session, mod, minutes)
        }

        private fun tip(mod: Modality, easy: Boolean) = when (mod) {
            Modality.ROW -> if (easy) "Easy pulls, about 20 strokes a minute." else "Drive with the legs, strong but controlled."
            Modality.SKIERG -> if (easy) "Smooth pulls, hinge at the hips, stay relaxed." else "Long strong pulls, keep the rhythm."
            Modality.BIKE -> if (easy) "Light gear, spin easy." else "Firm gear, steady cadence."
            Modality.TREADMILL_WALK -> if (easy) "Easy walk, a gentle incline." else "Raise the incline until it is hard but steady."
            else -> if (easy) "Keep it easy." else "Hard but controlled."
        }

        private fun easyPlan(s: PlannedSession, mod: Modality, minutes: Int): GuidedPlan {
            val short = minutes < 20
            val warm = (first(WARM_RE, s.detail)?.let { min(it, 10) } ?: if (short) 3 else 5) * 60
            val cool = (first(COOL_RE, s.detail)?.let { min(it, 10) } ?: if (short) 2 else 3) * 60
            val total = minutes * 60
            val main = max(60, total - warm - cool)
            val steps = mutableListOf(GuidedStep(GuidedKind.WARMUP, "Warm-up", total - main - cool, "Easy and loose. ${tip(mod, true)}"))
            if (main >= 900) {
                val half = main / 2 / 30 * 30
                steps += GuidedStep(GuidedKind.EASY, "Easy, first half", half, "Chat pace: full sentences. ${tip(mod, true)} No need to watch the pace.")
                steps += GuidedStep(GuidedKind.EASY, "Easy, second half", main - half, "Halfway. Still able to chat? Good. Keep it exactly as it is.", checkIn = true)
            } else {
                steps += GuidedStep(GuidedKind.EASY, "Easy", main, "Chat pace: full sentences. ${tip(mod, true)} No need to watch the pace.")
            }
            steps += GuidedStep(GuidedKind.COOLDOWN, "Cool-down", cool, "Ease right down. Slow, deep breaths.")
            return GuidedPlan(mod, steps, s.rpeTarget, intervals = false)
        }

        private fun intervalPlan(s: PlannedSession, mod: Modality, minutes: Int): GuidedPlan {
            val total = minutes * 60
            val parsed = REPS_RE.find(s.detail.joinToString(" "))
            val work = parsed?.groupValues?.get(2)?.toIntOrNull() ?: 3
            val rest = first(REST_RE, s.detail) ?: if (work <= 3) 2 else 3
            var warm = (first(WARM_RE, s.detail) ?: 10) * 60
            var cool = (first(COOL_RE, s.detail) ?: 5) * 60
            val wantedReps = parsed?.groupValues?.get(1)?.toIntOrNull() ?: (s.hardMinutes / work).coerceIn(2, 8)
            val setSec = { r: Int -> (r * work + (r - 1) * rest) * 60 }
            var reps = wantedReps.coerceAtLeast(1)
            // A short session loses reps first, then warm-up and cool-down shrink.
            while (reps > 1 && warm + cool + setSec(reps) > total) reps--
            if (warm + cool + setSec(reps) > total) { warm = 180; cool = 120 }
            val leftover = max(0, total - warm - cool - setSec(reps))
            val steps = mutableListOf(GuidedStep(GuidedKind.WARMUP, "Warm-up", warm, "Easy and loose, RPE 3. ${tip(mod, true)}"))
            val rpeText = "RPE ${s.rpeTarget.first}-${s.rpeTarget.last}"
            for (r in 1..reps) {
                steps += GuidedStep(GuidedKind.WORK, "Hard $r of $reps", work * 60, "$rpeText. ${tip(mod, false)} Short sentences only; the last one should still be controlled.")
                if (r < reps) steps += GuidedStep(GuidedKind.RECOVERY, "Easy between", rest * 60, "Back to easy. Shake out, breathe, sip some water.")
            }
            // Spare time (the plan caps the number of reps) goes to an easy finish, so the total still matches.
            if (leftover > 600) steps += GuidedStep(GuidedKind.EASY, "Easy finish", leftover - 300, "Chat pace. Nothing to prove.")
            val coolSec = if (leftover > 600) cool + 300 else cool + leftover
            steps += GuidedStep(GuidedKind.COOLDOWN, "Cool-down", coolSec, "Ease right down. Slow, deep breaths.")
            return GuidedPlan(mod, steps, s.rpeTarget, intervals = true)
        }

        /** Plain-words effort target: easy is a talk test, hard is what you can still say. */
        fun rpeWords(target: IntRange): String = when {
            target.last <= 4 -> "Easy: you can chat in full sentences"
            target.first >= 8 -> "Hard: ${target.first}/10, short sentences"
            target.first >= 7 -> "Hard: ${target.first}-${target.last}/10, short sentences"
            else -> "Comfortably hard: ${target.first}-${target.last}/10, a few words at a time"
        }

        /** The Hyrox-station finisher lines of a strength session's detail (header first), or empty. */
        fun finisherLines(s: PlannedSession): List<String> {
            val i = s.detail.indexOfFirst { it.startsWith("Hyrox station finisher") || it.startsWith("Station technique primer") }
            if (i < 0) return emptyList()
            val body = s.detail.drop(i + 1).takeWhile { it.startsWith("  ") }.map { it.trim() }
            return listOf(s.detail[i].trimEnd(':')) + body
        }
    }
}

/**
 * Running state of a guided session: which step, how far in, paused or not. Advance with real elapsed time
 * ([advance]); the one cue to play comes back with it. [workMs] is the time actually spent in hard steps.
 */
data class GuidedRun(
    val steps: List<GuidedStep>,
    val index: Int = 0,
    val elapsedMs: Long = 0,
    val paused: Boolean = false,
    val pausedAway: Boolean = false,
    val totalElapsedMs: Long = 0,
    val workMs: Long = 0,
) {
    val finished: Boolean get() = index >= steps.size
    val current: GuidedStep? get() = steps.getOrNull(index)
    val next: GuidedStep? get() = steps.getOrNull(index + 1)
    val secondsLeft: Int get() = current?.let { (((it.seconds * 1000L) - elapsedMs + 999) / 1000).toInt().coerceAtLeast(0) } ?: 0
    val totalSec: Int get() = steps.sumOf { it.seconds }
    val completedSec: Int get() = (totalElapsedMs / 1000).toInt()
    val workSec: Int get() = (workMs / 1000).toInt()
    val fraction: Float get() = if (totalSec == 0) 0f else (totalElapsedMs / 1000f / totalSec).coerceIn(0f, 1f)

    fun pause(away: Boolean = false) = if (finished) this else copy(paused = true, pausedAway = away)
    fun resume() = copy(paused = false, pausedAway = false)

    fun advance(deltaMs: Long): Pair<GuidedRun, GuidedEvent?> {
        if (paused || finished || deltaMs <= 0) return this to null
        var run = this
        var left = deltaMs
        var event: GuidedEvent? = null
        while (left > 0 && !run.finished) {
            val step = run.steps[run.index]
            val toEnd = step.seconds * 1000L - run.elapsedMs
            val work = if (step.hard) 1 else 0
            if (left < toEnd) {
                val warnAt = step.seconds * 1000L - PRE_WARNING_MS
                val after = run.elapsedMs + left
                if (step.seconds >= 10 && run.elapsedMs < warnAt && after >= warnAt && event == null) event = GuidedEvent.PRE_WARNING
                run = run.copy(elapsedMs = after, totalElapsedMs = run.totalElapsedMs + left, workMs = run.workMs + work * left)
                left = 0
            } else {
                left -= toEnd
                run = run.copy(index = run.index + 1, elapsedMs = 0, totalElapsedMs = run.totalElapsedMs + toEnd, workMs = run.workMs + work * toEnd)
                event = if (run.finished) GuidedEvent.DONE else GuidedEvent.CHANGE
            }
        }
        return run to event
    }

    /** Jump to the start of the next step (what was done so far still counts). */
    fun skip(): Pair<GuidedRun, GuidedEvent?> {
        if (finished) return this to null
        val next = copy(index = index + 1, elapsedMs = 0)
        return next to if (next.finished) GuidedEvent.DONE else GuidedEvent.CHANGE
    }

    companion object { const val PRE_WARNING_MS = 3000L }
}

/**
 * The saved record of a guided session. ROW and SKIERG are real Hyrox stations, so they go in as HYROX with a
 * [StationLog] (progression and PRs work); everything else (bike, ...) is a HIKE record that carries the same
 * logs under the modality's own id (HIKE counts as "other" in the weekly stats and is easy time by default).
 *
 * Logs carry seconds and RPE so [IntensityBalance] can classify the session: a hard set is one log at the rated
 * RPE for the time actually spent in hard steps, everything else (warm-up, recoveries, cool-down) one easy log.
 */
object GuidedRecord {
    /** Marks the records made here; IntensityBalance then reads the logs' seconds directly. */
    const val DAY_LABEL = "Race plan"
    const val EASY_RPE = 3

    fun stationId(m: Modality): String = when (m) {
        Modality.ROW -> HyroxStation.ROW.id
        Modality.SKIERG -> HyroxStation.SKIERG.id
        else -> m.name.lowercase()
    }

    fun type(m: Modality): SessionType = if (m == Modality.ROW || m == Modality.SKIERG) SessionType.HYROX else SessionType.HIKE

    fun note(minutes: Int, hardMin: Int, m: Modality): String =
        "Race plan: " + (if (hardMin > 0) "$hardMin min hard in $minutes min" else "easy $minutes min") + ", ${m.label.lowercase()}"

    /** Null when under a minute was done (nothing worth saving). */
    fun build(
        id: String, date: LocalDate, planned: Int, modality: Modality, completedSec: Int, hardSec: Int,
        distanceM: Int?, rpe: Int, feeling: Feeling,
    ): SessionRecord? {
        if (completedSec < 60) return null
        val minutes = (completedSec + 30) / 60
        val sid = stationId(modality)
        val hard = hardSec.coerceIn(0, completedSec)
        val logs = if (hard >= 30) listOfNotNull(
            (completedSec - hard).takeIf { it > 0 }?.let { StationLog(sid, seconds = it, rpe = min(rpe, EASY_RPE)) },
            StationLog(sid, distanceM = distanceM, seconds = hard, rpe = rpe),
        ) else listOf(StationLog(sid, distanceM = distanceM, seconds = completedSec, rpe = rpe))
        return SessionRecord(
            id = id, date = date, type = type(modality), minutesAvailable = planned, minutesActual = minutes,
            feeling = feeling, dayLabel = DAY_LABEL, note = note(minutes, (hard / 60.0).roundToInt(), modality), stations = logs,
        )
    }
}

/** Builds the treadmill plan for a planned session whose modality is the treadmill (walk, run, run-walk, intervals). */
object PlannedTreadmill {
    private val RUN_WALK_RE = Regex("""(\d+)\s*(min|s)\s*run\s*/\s*(\d+)\s*(min|s)\s*walk\s*x\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val CONT_RE = Regex("""(\d+)\s*minutes?\s*of\s*easy\s*continuous\s*running""", RegexOption.IGNORE_CASE)
    private const val WALK_INCLINE = 1.0
    private const val MIN_SEG = 60

    private fun sec(n: String, unit: String) = n.toInt() * if (unit.equals("min", true)) 60 else 1

    /** The run-walk rung in [s]'s detail (falls back to the first rung of the ladder). */
    fun runWalkStep(s: PlannedSession): RunWalkStep {
        val m = s.detail.firstNotNullOfOrNull { RUN_WALK_RE.find(it) }
        return if (m != null) RunWalkStep(sec(m.groupValues[1], m.groupValues[2]), sec(m.groupValues[3], m.groupValues[4]), m.groupValues[5].toInt())
        else RunStageLogic.RUN_WALK_LADDER.first()
    }

    fun build(
        s: PlannedSession, modality: Modality, calibration: Calibration = Calibration(), settings: UserSettings = UserSettings(),
    ): TreadmillPlan {
        val minutes = max(10, s.minutes)
        return when {
            s.intensity == Intensity.HARD && s.hardMinutes > 0 -> intervals(s, modality, calibration, settings)
            modality == Modality.RUN_WALK -> runWalk(runWalkStep(s), minutes, calibration, settings)
            modality == Modality.TREADMILL_RUN -> continuousRun(s, minutes, calibration, settings)
            else -> steady(s, minutes, calibration, settings)
        }
    }

    private fun steady(s: PlannedSession, minutes: Int, cal: Calibration, settings: UserSettings): TreadmillPlan {
        if (minutes >= 20) {
            val style = s.launch.treadmillStyle?.takeIf { it == TreadmillStyle.STEADY || it == TreadmillStyle.LONG_ROLLING } ?: TreadmillStyle.STEADY
            return TreadmillPlanner.build(style, minutes, cal, settings)
        }
        val total = minutes * 60
        val warm = Segment(180, settings.warmupSpeedKmh, settings.warmupInclinePct, "Warm-up")
        val cool = Segment(120, TreadmillPlanner.COOLDOWN.speedKmh, TreadmillPlanner.COOLDOWN.inclinePct, "Cool-down")
        return TreadmillPlan(TreadmillStyle.STEADY, listOf(warm, Segment(total - 300, cal.speedKmh, cal.inclinePct, "Steady"), cool))
    }

    private fun walkSeg(sec: Int, label: String, settings: UserSettings) = Segment(sec, settings.warmupSpeedKmh, WALK_INCLINE, label)

    /** Warm-up walk, [step] repeated (no walk after the last run), then easy walking to fill the time and a cool-down. */
    fun runWalk(step: RunWalkStep, minutes: Int, cal: Calibration, settings: UserSettings): TreadmillPlan {
        val total = minutes * 60
        val warm = 300
        val cool = 180
        val runSeg = Segment(step.runSec, settings.hyroxRunSpeedKmh, settings.hyroxRunInclinePct, "Run")
        var reps = step.reps
        fun blockSec(r: Int) = r * step.runSec + (r - 1) * step.walkSec
        while (reps > 1 && warm + blockSec(reps) + cool > total) reps--
        val segs = mutableListOf(walkSeg(warm, "Warm-up", settings))
        for (r in 1..reps) {
            segs += runSeg
            if (r < reps) segs += walkSeg(step.walkSec, "Walk", settings)
        }
        val left = total - warm - blockSec(reps)
        // Leftover time: easy incline walking at his calibrated setting, then a short cool-down walk.
        if (left - cool >= MIN_SEG * 2) {
            segs += Segment(left - cool, cal.speedKmh, cal.inclinePct, "Easy walk")
            segs += walkSeg(cool, "Cool-down", settings)
        } else {
            segs += walkSeg(max(MIN_SEG, left), "Cool-down", settings)
        }
        return TreadmillPlan(TreadmillStyle.STEADY, segs, title = "Run-walk: ${step.label}")
    }

    private fun continuousRun(s: PlannedSession, minutes: Int, cal: Calibration, settings: UserSettings): TreadmillPlan {
        val total = minutes * 60
        val runMin = s.detail.firstNotNullOfOrNull { CONT_RE.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 10
        val warm = 300
        val cool = 180
        val run = min(runMin * 60, max(MIN_SEG, total - warm - cool))
        val segs = mutableListOf(walkSeg(warm, "Warm-up", settings), Segment(run, settings.hyroxRunSpeedKmh, settings.hyroxRunInclinePct, "Run"))
        val left = total - warm - run - cool
        if (left >= MIN_SEG * 2) segs += Segment(left, cal.speedKmh, cal.inclinePct, "Easy walk")
        segs += walkSeg(cool + if (left in 1 until MIN_SEG * 2) left else 0, "Cool-down", settings)
        return TreadmillPlan(TreadmillStyle.STEADY, segs, title = "Easy run: ${run / 60} min")
    }

    private fun intervals(s: PlannedSession, modality: Modality, cal: Calibration, settings: UserSettings): TreadmillPlan {
        val run = modality == Modality.TREADMILL_RUN
        val g = GuidedPlan.from(s, Modality.TREADMILL_WALK)
        val segs = g.steps.map { st ->
            when (st.kind) {
                GuidedKind.WARMUP -> Segment(st.seconds, settings.warmupSpeedKmh, settings.warmupInclinePct, st.name)
                GuidedKind.WORK ->
                    if (run) Segment(st.seconds, settings.hyroxRunSpeedKmh, settings.hyroxRunInclinePct, st.name)
                    else Segment(st.seconds, cal.speedKmh, (cal.inclinePct + 3).coerceAtMost(MAX_INCLINE), st.name)
                GuidedKind.RECOVERY, GuidedKind.EASY -> Segment(st.seconds, settings.warmupSpeedKmh, (cal.inclinePct - 4).coerceAtLeast(2.0), st.name)
                GuidedKind.COOLDOWN -> Segment(st.seconds, TreadmillPlanner.COOLDOWN.speedKmh, TreadmillPlanner.COOLDOWN.inclinePct, st.name)
            }
        }
        return TreadmillPlan(TreadmillStyle.INTERVALS, segs, title = s.title)
    }
}
