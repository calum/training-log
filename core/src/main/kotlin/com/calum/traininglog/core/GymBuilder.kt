package com.calum.traininglog.core

import java.time.LocalDate

data class PlannedExercise(
    val exercise: Exercise,
    val prescription: Prescription,
    val warmups: List<LoggedSet>,
)

data class GymPlan(
    /** Descriptive label such as "Squat + Deadlift" (old records carry "A"/"B"). */
    val dayLabel: String,
    val deload: Boolean,
    val exercises: List<PlannedExercise>,
    /** Core slots that were trimmed to fit the time (optional extras are not listed). */
    val dropped: List<Exercise>,
    val estimatedMinutes: Int,
    /** Ids of exercises that are optional extras (e.g. the second hinge with lots of time). */
    val optionalIds: Set<String> = emptySet(),
    /** Set while easing back in after time off (null otherwise). */
    val rebaseline: RebaselineStatus? = null,
)

/** A titled list of library (or "Your exercises") entries for the Add exercise sheet. */
data class ExerciseGroup(val title: String, val exercises: List<Exercise>)

object GymBuilder {
    const val TRAVEL_MIN_EACH_WAY = 10
    const val MIN_WORTHWHILE_LIFTING_MIN = 30
    /** With at least this many lifting minutes the other hinge is offered as an optional extra. */
    const val EXTRA_HINGE_MIN = 75
    const val YOUR_EXERCISES = "Your exercises"

    /** Rotation order for the upper-body movements; also breaks ties between equally stale ones. */
    val upperRotation = listOf("vertical-pull", "horizontal-pull", "horizontal-push", "vertical-push")

    fun liftingMinutes(minutesAvailable: Int) = minutesAvailable - 2 * TRAVEL_MIN_EACH_WAY

    private fun gymRecords(records: List<SessionRecord>) = records.filter { it.type == SessionType.GYM }

    private fun lastDone(records: List<SessionRecord>, match: (String) -> Boolean): LocalDate? =
        gymRecords(records).filter { r -> r.exercises.any { match(it.exerciseId) && it.sets.isNotEmpty() } }.maxOfOrNull { it.date }

    /** Deadlift or RDL, whichever was done least recently; RDL when neither (or both on the same day). */
    fun nextHinge(records: List<SessionRecord>): Exercise {
        val dl = lastDone(records, { it == "deadlift" })
        val rdl = lastDone(records, { it == "rdl" })
        return when {
            dl == null && rdl == null -> ExerciseLibrary.rdl
            dl == null -> ExerciseLibrary.deadlift // never done = least recent
            rdl == null -> ExerciseLibrary.rdl
            dl.isBefore(rdl) -> ExerciseLibrary.deadlift
            else -> ExerciseLibrary.rdl
        }
    }

    private fun hingeName(ex: Exercise) = if (ex.id == "deadlift") "Deadlift" else "RDL"

    /** The label the next session would get if nothing is trimmed, e.g. "Squat + RDL". */
    fun nextDayLabel(records: List<SessionRecord>): String = "Squat + ${hingeName(nextHinge(records))}"

    /** The two (or [count]) upper movements trained least recently, ties by [upperRotation]. */
    fun nextUpperMovements(records: List<SessionRecord>, count: Int = 2): List<String> {
        val last = upperRotation.associateWith { m ->
            lastDone(records) { id -> ExerciseLibrary.find(id)?.movement == m }
        }
        return upperRotation.withIndex()
            .sortedWith(compareBy({ last.getValue(it.value) ?: LocalDate.MIN }, { it.index }))
            .map { it.value }.take(count)
    }

    /** The exercise last used for [movement] (derived from history), else the library default. */
    fun preferredFor(movement: String, records: List<SessionRecord>): Exercise {
        val recent = gymRecords(records).sortedByDescending { it.date }.firstNotNullOfOrNull { r ->
            r.exercises.firstOrNull { it.sets.isNotEmpty() && ExerciseLibrary.find(it.exerciseId)?.movement == movement }
        }
        return recent?.let { ExerciseLibrary.find(it.exerciseId) } ?: ExerciseLibrary.firstFor(movement)
    }

    /** Estimated minutes for an exercise: a set takes ~45 s plus its rest. */
    fun estimateMinutes(p: PlannedExercise): Double =
        p.prescription.sets * (p.exercise.restSec / 60.0 + 0.75) + p.warmups.size * 1.25

    /**
     * Recommend a session that fits [liftingMinutes]. Slots: squat and calf every time, one hinge
     * (alternating), two upper movements (least recently trained), plus the other hinge as an
     * optional extra with lots of time. When over budget, drops in this order: second upper,
     * extras, first upper, then the hinge. Squat and calf are never dropped.
     */
    fun build(
        liftingMinutes: Int,
        records: List<SessionRecord>,
        today: LocalDate,
        deload: Boolean = false,
        easedRestart: Boolean = false,
        rebaseline: RebaselineStatus? = null,
    ): GymPlan {
        val easing = rebaseline?.takeIf { it.active }
        @Suppress("NAME_SHADOWING") val deload = deload && easing?.stage != 1
        fun p(ex: Exercise) = plan(ex, records, today, deload, easedRestart, easing)
        val hinge = nextHinge(records)
        val (u1, u2) = nextUpperMovements(records, 2).map { preferredFor(it, records) }

        val squat = p(ExerciseLibrary.squat)
        val hingeP = p(hinge)
        val calf = p(ExerciseLibrary.calf)
        val upper1 = p(u1)
        val upper2 = p(u2)
        val extra = if (liftingMinutes >= EXTRA_HINGE_MIN) p(if (hinge.id == "rdl") ExerciseLibrary.deadlift else ExerciseLibrary.rdl) else null

        // Presentation order; drop order is separate (least important first).
        val dropOrder = listOfNotNull(upper2, extra, upper1, hingeP)
        val kept = (listOfNotNull(squat, hingeP, calf, upper1, upper2, extra)).toMutableList()
        val dropped = mutableListOf<Exercise>()
        for (candidate in dropOrder) {
            if (kept.sumOf { estimateMinutes(it) } <= liftingMinutes) break
            kept.remove(candidate)
            if (candidate !== extra) dropped += candidate.exercise
        }
        val label = if (kept.any { it === hingeP }) "Squat + ${hingeName(hinge)}" else "Squat + Calf"
        return GymPlan(
            label, deload, kept, dropped, kept.sumOf { estimateMinutes(it) }.toInt(),
            optionalIds = if (extra != null && kept.any { it === extra }) setOf(extra.exercise.id) else emptySet(),
            rebaseline = easing,
        )
    }

    /**
     * Plan one exercise. Library exercises use [Progression]; custom (free-text) ones get 3 x 10 at
     * the weight and reps of their last logged session (0 kg if none) and never auto-progress.
     */
    fun plan(ex: Exercise, records: List<SessionRecord>, today: LocalDate, deload: Boolean, easedRestart: Boolean = false, rebaseline: RebaselineStatus? = null): PlannedExercise {
        val history = history(ex.id, records)
        var rx = if (ExerciseLibrary.isCustom(ex.id)) {
            val last = history.firstOrNull()?.log?.sets
            if (last == null) Prescription(0.0, ex.sets, ex.repsLow, "Your own exercise: set a weight to start.")
            else {
                val top = last.maxByOrNull { it.weightKg }!!
                Prescription(top.weightKg, ex.sets, top.reps.coerceAtLeast(1), "Your own exercise: repeating last time (no automatic progression).")
            }
        } else Progression.next(ex, history, today, easedRestart)
        if (rebaseline != null && rebaseline.active) {
            // Progression already applies its 90% restart after a long gap; only never-done exercises need the factor.
            val w = if (history.isEmpty() && rebaseline.weightFactor < 1.0 && rx.weightKg > 0) roundTo(rx.weightKg * rebaseline.weightFactor, 2.5) else rx.weightKg
            rx = rx.copy(weightKg = w, sets = (rx.sets + rebaseline.setsDelta).coerceAtLeast(1), note = rx.note + " " + rebaseline.note)
        }
        if (deload) {
            rx = rx.copy(sets = Progression.deloadSets(rx.sets), note = "Deload week: fewer sets, same loads.")
        }
        val warmups = if (ex.warmup) Warmup.squatSets(rx.weightKg) else emptyList()
        return PlannedExercise(ex, rx, warmups)
    }

    /** Plan any library or custom exercise by id (custom names come from history when logged before). */
    fun planFor(id: String, records: List<SessionRecord>, today: LocalDate, deload: Boolean, easedRestart: Boolean = false, rebaseline: RebaselineStatus? = null): PlannedExercise {
        val ex = ExerciseLibrary.find(id) ?: customFromHistory(records).firstOrNull { it.id == id }
            ?: ExerciseLibrary.custom(ExerciseLibrary.nameOf(id))
        return plan(ex, records, today, deload, easedRestart, rebaseline)
    }

    /** Free-text exercises logged before, newest first (no extra storage). */
    fun customFromHistory(records: List<SessionRecord>): List<Exercise> {
        val logs = gymRecords(records).sortedByDescending { it.date }
            .flatMap { it.exercises }
            .filter { ExerciseLibrary.isCustom(it.exerciseId) }
        return logs.distinctBy { it.exerciseId }.map { l ->
            // Movement comes from the most recent log of this id that recorded one.
            val movement = logs.firstOrNull { it.exerciseId == l.exerciseId && it.customMovement != null }?.customMovement
            Exercise(l.exerciseId, ExerciseLibrary.nameOf(l.exerciseId, l.customName), Role.ACCESSORY, movement ?: ExerciseLibrary.OTHER_MOVEMENT, 0.0, 0.0, 3, 10, 10, 90)
        }
    }

    /** Movement group remembered for a custom id (most recent log that has one), else null. */
    fun customMovementFor(id: String, records: List<SessionRecord>): String? =
        customFromHistory(records).firstOrNull { it.id == id }?.movement?.takeIf { it != ExerciseLibrary.OTHER_MOVEMENT }

    /** Swap alternatives: library and your own exercises in the same movement group. */
    fun swapsFor(ex: Exercise, records: List<SessionRecord>): List<Exercise> =
        ExerciseLibrary.swapsFor(ex, customFromHistory(records))

    /** Everything that could be added, grouped by movement (your own exercises join their group), with "Your exercises" last for ungrouped ones. */
    fun addOptions(records: List<SessionRecord>): List<ExerciseGroup> {
        val mine = customFromHistory(records)
        val byGroup = mine.groupBy { ExerciseLibrary.groupOf(it.movement) }
        val groups = ExerciseLibrary.all.groupBy { ExerciseLibrary.groupOf(it.movement) }
            .map { (g, list) -> ExerciseGroup(ExerciseLibrary.groupTitle(g), list + byGroup[g].orEmpty()) }
        val ungrouped = mine.filter { ExerciseLibrary.groupOf(it.movement) !in ExerciseLibrary.all.map { l -> ExerciseLibrary.groupOf(l.movement) } }
        return if (ungrouped.isEmpty()) groups else groups + ExerciseGroup(YOUR_EXERCISES, ungrouped)
    }

    fun history(exerciseId: String, records: List<SessionRecord>): List<HistoryEntry> =
        records.sortedByDescending { it.date }.mapNotNull { r ->
            r.exercises.firstOrNull { it.exerciseId == exerciseId && it.sets.isNotEmpty() }?.let { HistoryEntry(r.date, it) }
        }
}
