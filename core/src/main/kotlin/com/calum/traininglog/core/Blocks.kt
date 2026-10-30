package com.calum.traininglog.core

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Training blocks: short (4-8 weeks, default 6) goal periods with a headline lift target and optional
 * smaller goals. Everything is pure and derived from the session history. No-guilt: a block whose time
 * is up is "finished", never "failed", and partial progress is only ever shown as progress made.
 */

enum class ExtraKind { SESSIONS_PER_WEEK, TREADMILL_MINUTES, TOTAL_SESSIONS }

@Serializable
data class BlockTarget(
    val exerciseId: String = "squat",
    val targetKg: Double = 100.0,
    /** Best working weight when the block began. */
    val startKg: Double = 80.0,
)

/**
 * Optional smaller goal. [target] means: SESSIONS_PER_WEEK sessions each week (counted across the block),
 * TREADMILL_MINUTES total treadmill minutes in the whole block, TOTAL_SESSIONS sessions in the whole block.
 */
@Serializable
data class ExtraGoal(val kind: ExtraKind = ExtraKind.SESSIONS_PER_WEEK, val target: Int = 2)

@Serializable
data class TrainingBlock(
    val id: String = "",
    val name: String = "Block",
    @Serializable(with = LocalDateSerializer::class) val startDate: LocalDate = LocalDate.of(2000, 1, 1),
    val weeks: Int = Blocks.DEFAULT_WEEKS,
    val headline: BlockTarget = BlockTarget(),
    val extras: List<ExtraGoal> = emptyList(),
    /** Set when the block was finished early (or replaced by a new one). Null means it runs to its planned end. */
    @Serializable(with = LocalDateSerializer::class) val endedOn: LocalDate? = null,
    val note: String? = null,
) {
    /** Last planned day (inclusive). */
    val plannedEnd: LocalDate get() = startDate.plusDays(weeks * 7L - 1)

    /** Last day that counts: the early end date if there is one, else the planned end. */
    val lastDay: LocalDate get() = endedOn?.let { if (it < plannedEnd) it else plannedEnd } ?: plannedEnd

    fun isFinished(today: LocalDate): Boolean = endedOn != null || today > plannedEnd
}

@Serializable
data class BlockState(val blocks: List<TrainingBlock> = emptyList())

data class ExtraProgress(
    val goal: ExtraGoal,
    val done: Int,
    /** Total target over the block (per-week targets are multiplied by the block length). */
    val total: Int,
    val fraction: Double,
    val label: String,
)

data class BlockProgress(
    val block: TrainingBlock,
    /** 1-based week number, clamped to 1..weeks. */
    val weekNumber: Int,
    val weeks: Int,
    /** Fraction of the block's time elapsed, 0..1. */
    val timeFraction: Double,
    val daysLeft: Int,
    val finished: Boolean,
    val currentKg: Double,
    val startKg: Double,
    val targetKg: Double,
    /** Progress from start toward target, 0..1. */
    val headlineFraction: Double,
    val headlineReached: Boolean,
    /** Expected-scenario weeks to reach the target from now (0 = reached), null if beyond the horizon. */
    val weeksAtThisPace: Int?,
    val paceText: String,
    val extras: List<ExtraProgress>,
    val sessionsDone: Int,
    val sessionsPerWeek: List<Int>,
    val prs: List<PersonalRecord>,
    val wins: List<String>,
)

object Blocks {
    const val DEFAULT_WEEKS = 6
    const val MIN_WEEKS = 4
    const val MAX_WEEKS = 8
    const val STEP_KG = 2.5

    private fun kg(x: Double) = if (x % 1.0 == 0.0) x.toInt().toString() else x.toString()

    /** The running block: not ended early, started on or before [today], and not past its planned end. */
    fun current(state: BlockState, today: LocalDate): TrainingBlock? =
        state.blocks.filter { it.endedOn == null && it.startDate <= today && today <= it.plannedEnd }
            .maxByOrNull { it.startDate }

    /** Blocks that are over (ended early or time ran out), newest first. */
    fun finished(state: BlockState, today: LocalDate): List<TrainingBlock> {
        val cur = current(state, today)
        return state.blocks.filter { it.isFinished(today) && it.id != cur?.id }.sortedByDescending { it.startDate }
    }

    /** The active block, else the most recently started one (to review or suggest from). */
    fun latest(state: BlockState, today: LocalDate): TrainingBlock? =
        current(state, today) ?: state.blocks.filter { it.startDate <= today }.maxByOrNull { it.startDate }

    /** Adds [block]; any block still running is ended first (on the day before [today]'s start if sensible). */
    fun start(state: BlockState, block: TrainingBlock, today: LocalDate): BlockState {
        val ended = state.blocks.map {
            if (it.endedOn == null && it.startDate <= today && today <= it.plannedEnd) it.copy(endedOn = today) else it
        }
        return BlockState(ended + block)
    }

    /** Finish the running block today. Counts everything done so far. */
    fun finishEarly(state: BlockState, today: LocalDate): BlockState {
        val cur = current(state, today) ?: return state
        return BlockState(state.blocks.map { if (it.id == cur.id) it.copy(endedOn = today) else it })
    }

    /** Give the running block [extraWeeks] more weeks (max 12 in total). */
    fun extend(state: BlockState, today: LocalDate, extraWeeks: Int = 1): BlockState {
        val cur = current(state, today) ?: return state
        return BlockState(state.blocks.map {
            if (it.id == cur.id) it.copy(weeks = (it.weeks + extraWeeks).coerceIn(it.weeks, 12)) else it
        })
    }

    fun remove(state: BlockState, id: String) = BlockState(state.blocks.filter { it.id != id })

    private fun inBlock(block: TrainingBlock, records: List<SessionRecord>, today: LocalDate): List<SessionRecord> {
        val end = minOf(block.lastDay, today)
        return records.filter { it.date >= block.startDate && it.date <= end }.sortedBy { it.date }
    }

    private fun topWeight(r: SessionRecord, id: String): Double? =
        r.exercises.filter { it.exerciseId == id }.flatMap { it.sets }.filter { it.reps > 0 }.maxOfOrNull { it.weightKg }

    /** Best working weight logged for [id] on or before [upTo]; null with no history. */
    fun bestWeight(records: List<SessionRecord>, id: String, upTo: LocalDate): Double? =
        records.filter { it.date <= upTo }.mapNotNull { topWeight(it, id) }.maxOrNull()

    /** Best weight for [id] now, or the library start weight with no history. */
    fun currentBest(records: List<SessionRecord>, id: String, today: LocalDate): Double =
        bestWeight(records, id, today) ?: ExerciseLibrary.find(id)?.startKg ?: 0.0

    fun stepFor(exerciseId: String): Double = if (exerciseId == "squat" || exerciseId == "deadlift") 5.0 else STEP_KG

    /** Sensible headline target: rounded current best plus one step, in 2.5 kg multiples. */
    fun defaultTarget(exerciseId: String, records: List<SessionRecord>, today: LocalDate): Double =
        roundTo(currentBest(records, exerciseId, today), STEP_KG) + stepFor(exerciseId)

    fun weekIndex(block: TrainingBlock, date: LocalDate): Int =
        (ChronoUnit.DAYS.between(block.startDate, date) / 7).toInt()

    fun progress(block: TrainingBlock, records: List<SessionRecord>, today: LocalDate): BlockProgress {
        val sessions = inBlock(block, records, today)
        val weeks = block.weeks.coerceAtLeast(1)
        val dayOf = ChronoUnit.DAYS.between(block.startDate, minOf(today, block.lastDay)).coerceAtLeast(0)
        val weekNumber = ((ChronoUnit.DAYS.between(block.startDate, today).coerceAtLeast(0) / 7).toInt() + 1).coerceIn(1, weeks)
        val timeFraction = ((dayOf + 1).toDouble() / (weeks * 7)).coerceIn(0.0, 1.0)
        val daysLeft = ChronoUnit.DAYS.between(today, block.lastDay).toInt().coerceAtLeast(0)

        // headline
        val h = block.headline
        val inBlockBest = sessions.mapNotNull { topWeight(it, h.exerciseId) }.maxOrNull()
        val currentKg = maxOf(inBlockBest ?: 0.0, h.startKg)
        val span = h.targetKg - h.startKg
        val reached = currentKg >= h.targetKg
        val fraction = when {
            reached -> 1.0
            span <= 0.0 -> 0.0
            else -> ((currentKg - h.startKg) / span).coerceIn(0.0, 1.0)
        }
        val name = ExerciseLibrary.nameOf(h.exerciseId).lowercase()
        val finished = block.isFinished(today)
        var weeksAtPace: Int? = null
        val pace = when {
            reached -> "Target reached. ${kg(h.targetKg)} kg is done."
            finished -> "Reached ${kg(currentKg)} kg this block. Nice base for the next one."
            else -> {
                val fc = Projection.forecast(records.filter { it.date <= today }, today, 12, headlineTarget = HeadlineTarget(h.exerciseId, h.targetKg))
                weeksAtPace = fc.headline?.weeksToTarget?.get(Scenario.EXPECTED)
                when (weeksAtPace) {
                    null -> "Keep building. This one may take longer than the block, and that is fine."
                    0 -> "Ready to try ${kg(h.targetKg)} kg."
                    else -> "About $weeksAtPace ${if (weeksAtPace == 1) "week" else "weeks"} at this pace."
                }
            }
        }

        // extras
        val perWeek = List(weeks) { w -> sessions.count { weekIndex(block, it.date) == w } }
        val treadMin = sessions.sumOf { r -> r.treadmill?.let { it.completedSec / 60 } ?: if (r.type == SessionType.TREADMILL) r.minutesActual else 0 }
        val extras = block.extras.map { g ->
            val target = g.target.coerceAtLeast(1)
            val (done, total, label) = when (g.kind) {
                ExtraKind.SESSIONS_PER_WEEK -> Triple(sessions.size, target * weeks, "$target sessions a week: ${sessions.size} of ${target * weeks} so far")
                ExtraKind.TREADMILL_MINUTES -> Triple(treadMin, target, "Treadmill: $treadMin of $target min")
                ExtraKind.TOTAL_SESSIONS -> Triple(sessions.size, target, "Sessions: ${sessions.size} of $target")
            }
            ExtraProgress(g, done, total, (done.toDouble() / total).coerceIn(0.0, 1.0), label)
        }

        // PRs set in the block
        val prs = Rewards.summary(records, today).prs.filter { it.date >= block.startDate && it.date <= minOf(block.lastDay, today) }

        val wins = buildList {
            if (reached) add("Hit the headline: $name ${kg(h.targetKg)} kg")
            else if (currentKg > h.startKg) add("$name up from ${kg(h.startKg)} to ${kg(currentKg)} kg")
            if (prs.isNotEmpty()) add("${prs.size} personal ${if (prs.size == 1) "record" else "records"} set")
            if (sessions.isNotEmpty()) add("${sessions.size} ${if (sessions.size == 1) "session" else "sessions"} done")
            if (treadMin > 0) add("$treadMin treadmill minutes")
            val active = perWeek.count { it > 0 }
            if (active >= 2) add("Trained in $active of $weeks weeks")
            extras.filter { it.fraction >= 1.0 }.forEach { add("Extra goal met: ${it.label}") }
            // Lifts that went up besides the headline
            sessions.flatMap { r -> r.exercises.map { it.exerciseId } }.distinct()
                .filter { it != h.exerciseId && !ExerciseLibrary.isCustom(it) }
                .forEach { id ->
                    val tops = sessions.mapNotNull { topWeight(it, id) }
                    if (tops.size >= 2 && tops.last() > tops.first()) {
                        add("${ExerciseLibrary.nameOf(id)} up from ${kg(tops.first())} to ${kg(tops.maxOrNull()!!)} kg")
                    }
                }
            if (isEmpty()) add("A fresh start. The first session is the hard one.")
        }

        return BlockProgress(
            block, weekNumber, weeks, timeFraction, daysLeft, finished, currentKg, h.startKg, h.targetKg,
            fraction, reached, weeksAtPace, pace, extras, sessions.size, perWeek, prs, wins,
        )
    }

    /** Next block proposal (an editable suggestion, not yet saved). Same length as the last block. */
    fun suggestNext(state: BlockState, records: List<SessionRecord>, today: LocalDate): TrainingBlock {
        val prev = latest(state, today)
        val id = prev?.headline?.exerciseId ?: "squat"
        val best = currentBest(records, id, today)
        val target = roundTo(best, STEP_KG) + stepFor(id)
        val weeks = (prev?.weeks ?: DEFAULT_WEEKS).coerceIn(MIN_WEEKS, MAX_WEEKS)
        return TrainingBlock(
            id = "",
            name = "Block ${state.blocks.size + 1}",
            startDate = today,
            weeks = weeks,
            headline = BlockTarget(id, target, roundTo(best, STEP_KG)),
            extras = prev?.extras ?: emptyList(),
        )
    }
}

/** The block-end version of the Claude export. Includes private fields (feelings, knee): it stays between you and Claude. */
object BlockExport {
    private fun kg(x: Double) = if (x % 1.0 == 0.0) x.toInt().toString() else x.toString()

    fun text(block: TrainingBlock, records: List<SessionRecord>, today: LocalDate): String {
        val p = Blocks.progress(block, records, today)
        val end = minOf(block.lastDay, today)
        val sessions = records.filter { it.date >= block.startDate && it.date <= end }.sortedBy { it.date }
        val h = block.headline
        val hName = ExerciseLibrary.nameOf(h.exerciseId)
        val sb = StringBuilder()
        sb.appendLine("TRAINING BLOCK REVIEW: ${block.name}")
        sb.appendLine("Dates: ${block.startDate} to $end (${block.weeks} weeks planned${if (block.endedOn != null) ", finished early on ${block.endedOn}" else ""})")
        sb.appendLine("Headline goal: $hName ${kg(h.targetKg)} kg (started at ${kg(h.startKg)} kg)")
        sb.appendLine(
            "Headline outcome: " + if (p.headlineReached) "reached, best ${kg(p.currentKg)} kg"
            else "best ${kg(p.currentKg)} kg, ${(p.headlineFraction * 100).toInt()}% of the way from start to target",
        )
        for (e in p.extras) sb.appendLine("Extra goal: ${e.label}")
        block.note?.takeIf { it.isNotBlank() }?.let { sb.appendLine("Note: $it") }
        sb.appendLine("Sessions: ${sessions.size} (gym ${sessions.count { it.type == SessionType.GYM }}, treadmill ${sessions.count { it.type == SessionType.TREADMILL }}, other ${sessions.count { it.type == SessionType.CORE || it.type == SessionType.HIKE }})")
        sb.appendLine("Personal records: ${p.prs.size}" + if (p.prs.isEmpty()) "" else " (" + p.prs.joinToString("; ") { it.text } + ")")

        // per lift
        sb.appendLine()
        sb.appendLine("LIFTS (start / end / best working weight, kg)")
        val liftIds = sessions.flatMap { r -> r.exercises.map { it.exerciseId } }.distinct()
        if (liftIds.isEmpty()) sb.appendLine("  No lifts logged in this block.")
        for (id in liftIds) {
            val per = sessions.mapNotNull { r ->
                val logs = r.exercises.filter { it.exerciseId == id }
                val top = logs.flatMap { it.sets }.filter { it.reps > 0 }.maxOfOrNull { it.weightKg }
                top?.let { r.date to it }
            }
            if (per.isEmpty()) continue
            val custom = sessions.flatMap { it.exercises }.firstOrNull { it.exerciseId == id }?.customName
            val label = if (ExerciseLibrary.isCustom(id)) "CUSTOM: ${ExerciseLibrary.nameOf(id, custom)}" else ExerciseLibrary.nameOf(id)
            sb.appendLine("  $label: ${kg(per.first().second)} / ${kg(per.last().second)} / best ${kg(per.maxOf { it.second })} over ${per.size} sessions")
        }

        // weekly
        sb.appendLine()
        sb.appendLine("WEEKLY")
        for (w in 0 until block.weeks) {
            val ws = block.startDate.plusWeeks(w.toLong())
            if (ws > today) break
            val inW = sessions.filter { Blocks.weekIndex(block, it.date) == w }
            val tmin = inW.sumOf { r -> r.treadmill?.let { it.completedSec / 60 } ?: if (r.type == SessionType.TREADMILL) r.minutesActual else 0 }
            sb.appendLine("  Week ${w + 1}: ${inW.size} sessions (gym ${inW.count { it.type == SessionType.GYM }}, treadmill ${inW.count { it.type == SessionType.TREADMILL }}, ${tmin} treadmill min)")
        }

        // treadmill effort
        val efforts = sessions.mapNotNull { it.treadmill?.effort }
        if (efforts.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("TREADMILL EFFORT: " + Effort.entries.joinToString(", ") { e -> "${e.name.lowercase().replace('_', ' ')} ${efforts.count { it == e }}" })
        }

        // effort (RIR) and failed sets
        val rirs = sessions.flatMap { r -> r.exercises.flatMap { it.sets } }.mapNotNull { it.rir }
        sb.appendLine()
        if (rirs.isNotEmpty()) sb.appendLine("LIFT EFFORT: average reps in reserve ${"%.1f".format(rirs.average())} over ${rirs.size} sets")
        val misses = sessions.flatMap { r ->
            r.exercises.mapNotNull { e ->
                val short = e.sets.filter { it.reps in 0 until it.targetReps }
                if (short.isEmpty() && e.missReason == null) null
                else "${r.date} ${ExerciseLibrary.nameOf(e.exerciseId, e.customName)}: " +
                    short.joinToString(", ") { "${it.reps}/${it.targetReps} at ${kg(it.weightKg)}" } +
                    (e.missReason?.let { " (${it.name.lowercase().replace('_', ' ')})" } ?: "")
            }
        }
        sb.appendLine("FAILED OR SHORT SETS: " + if (misses.isEmpty()) "none" else "")
        misses.forEach { sb.appendLine("  $it") }

        // knee and feeling
        val knees = sessions.mapNotNull { it.kneePain }
        sb.appendLine()
        sb.appendLine(
            "KNEE: " + if (knees.isEmpty()) "not recorded"
            else "average ${"%.1f".format(knees.average())}/10, highest ${knees.max()}/10, last ${knees.last()}/10",
        )
        val feel = Feeling.entries.map { f -> f to sessions.count { it.feeling == f } }.filter { it.second > 0 }
        sb.appendLine("FEELING: " + if (feel.isEmpty()) "no sessions" else feel.joinToString(", ") { "${it.first.name.lowercase()} ${it.second}" })
        val notes = sessions.filter { !it.note.isNullOrBlank() }
        if (notes.isNotEmpty()) {
            sb.appendLine("SESSION NOTES:")
            notes.forEach { sb.appendLine("  ${it.date}: ${it.note}") }
        }

        sb.appendLine()
        sb.appendLine("Please review this block and suggest the next 4-8 week goal: a headline target plus one or two smaller ones, keeping it fresh, achievable and kind to my knee.")
        return sb.toString()
    }
}
