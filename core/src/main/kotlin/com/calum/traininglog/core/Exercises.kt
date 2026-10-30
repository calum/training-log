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

    fun get(id: String): Exercise = byId.getValue(id)

    /** Other upper-body exercises that train the same movement. */
    fun swapsFor(id: String): List<Exercise> {
        val ex = get(id)
        return upper.filter { it.movement == ex.movement && it.id != id }
    }

    fun firstFor(movement: String): Exercise = upper.first { it.movement == movement }
}
