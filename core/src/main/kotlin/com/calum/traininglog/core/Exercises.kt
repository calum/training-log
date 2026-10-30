package com.calum.traininglog.core

enum class Role { MAIN, ACCESSORY }

data class Exercise(
    val id: String,
    val name: String,
    val role: Role,
    val movement: String,
    val incrementKg: Double,
    /** Placeholder starting weight; restart conservatively after the break. */
    val startKg: Double,
    val sets: Int,
    val repsLow: Int,
    val repsHigh: Int,
    val restSec: Int,
    val warmup: Boolean = false,
)

object ExerciseLibrary {
    val squat = Exercise("squat", "Squat", Role.MAIN, "squat", 2.5, 80.0, 3, 5, 5, 120, warmup = true)
    val deadlift = Exercise("deadlift", "Deadlift", Role.MAIN, "hinge-heavy", 5.0, 100.0, 2, 3, 3, 120)
    val rdl = Exercise("rdl", "Romanian deadlift", Role.MAIN, "hinge", 2.5, 70.0, 2, 8, 8, 90)
    val calf = Exercise("calf", "Calf raise", Role.MAIN, "calf", 2.5, 60.0, 3, 12, 12, 90)

    private fun acc(id: String, name: String, movement: String, start: Double) =
        Exercise(id, name, Role.ACCESSORY, movement, 2.5, start, 3, 8, 12, 90)

    val upper = listOf(
        acc("lat_pulldown", "Lat pulldown", "vertical-pull", 40.0),
        acc("neutral_pulldown", "Neutral-grip pulldown", "vertical-pull", 40.0),
        acc("single_arm_pulldown", "Single-arm cable pulldown", "vertical-pull", 15.0),
        acc("cable_row", "Seated cable row", "horizontal-pull", 40.0),
        acc("db_row", "One-arm dumbbell row", "horizontal-pull", 20.0),
        acc("supported_row", "Chest-supported row", "horizontal-pull", 30.0),
        acc("machine_chest", "Machine chest press", "horizontal-push", 40.0),
        acc("db_bench", "Dumbbell bench press", "horizontal-push", 20.0),
        acc("pushup", "Push-ups", "horizontal-push", 0.0),
        acc("db_shoulder", "Dumbbell shoulder press", "vertical-push", 12.5),
        acc("machine_shoulder", "Machine shoulder press", "vertical-push", 30.0),
    )

    val all: List<Exercise> = listOf(squat, deadlift, rdl, calf) + upper
    private val byId = all.associateBy { it.id }

    const val CUSTOM_PREFIX = "custom:"
    const val OTHER_MOVEMENT = "other"

    /** Throws for unknown or custom ids; prefer [find] for anything that can come from history. */
    fun get(id: String): Exercise = byId.getValue(id)

    /** Safe lookup: null for custom (free-text) or unknown ids. */
    fun find(id: String): Exercise? = byId[id]

    fun isCustom(id: String) = id.startsWith(CUSTOM_PREFIX)

    /** Display name that never throws: library name, else the logged custom name, else a readable id. */
    fun nameOf(id: String, customName: String? = null): String =
        byId[id]?.name ?: customName?.takeIf { it.isNotBlank() } ?: if (isCustom(id)) id.removePrefix(CUSTOM_PREFIX).replace('_', ' ') else id

    fun customId(name: String): String {
        val slug = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return CUSTOM_PREFIX + slug.ifEmpty { "exercise" }
    }

    /** A free-text exercise as a plan-able [Exercise]: 3 x 10, no progression (see GymBuilder.plan). */
    fun custom(name: String, movement: String? = null): Exercise =
        Exercise(customId(name), name.trim(), Role.ACCESSORY, movement ?: OTHER_MOVEMENT, 0.0, 0.0, 3, 10, 10, 90)

    /** Movements that may swap with each other (deadlift and RDL share the hinge group). */
    fun groupOf(movement: String) = if (movement == "hinge-heavy" || movement == "hinge") "hinge" else movement

    fun groupTitle(group: String) = when (group) {
        "squat" -> "Squat"
        "hinge" -> "Hinge"
        "calf" -> "Calves"
        "vertical-pull" -> "Vertical pull"
        "horizontal-pull" -> "Horizontal pull"
        "horizontal-push" -> "Horizontal push"
        "vertical-push" -> "Vertical push"
        else -> "Other"
    }

    /** Other exercises in the same movement group (including deadlift <-> RDL). */
    fun swapsFor(id: String): List<Exercise> = find(id)?.let { swapsFor(it) } ?: emptyList()

    fun swapsFor(ex: Exercise): List<Exercise> =
        if (ex.movement == OTHER_MOVEMENT) emptyList()
        else all.filter { groupOf(it.movement) == groupOf(ex.movement) && it.id != ex.id }

    fun firstFor(movement: String): Exercise = upper.first { it.movement == movement }
}
