package com.calum.traininglog.core

import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * The gym session as far as it has got, saved after every change so a killed app or dead phone cannot lose sets.
 * It holds exactly what a saved session would hold ([exercises] are the done sets only), kept in a file of its own
 * and never mixed into the main data. On the next start a draft becomes a normal session (see [toRecord]).
 */
@Serializable
data class GymDraft(
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val minutesAvailable: Int,
    val minutesActual: Int,
    val feeling: Feeling,
    val kneePain: Int? = null,
    val dayLabel: String? = null,
    val deload: Boolean = false,
    val exercises: List<ExerciseLog>,
) {
    /** The session this draft stands for. [id] is new; [note] says it was recovered. */
    fun toRecord(id: String, note: String? = null) = SessionRecord(
        id = id, date = date, type = SessionType.GYM,
        minutesAvailable = minutesAvailable, minutesActual = minutesActual.coerceAtLeast(1), feeling = feeling,
        kneePain = kneePain, dayLabel = dayLabel, deload = deload, exercises = exercises, note = note,
    )

    /** True when [records] already hold this very session (the app was killed after saving but before the draft was cleared). */
    fun alreadySavedIn(records: List<SessionRecord>): Boolean =
        records.any { it.type == SessionType.GYM && it.date == date && it.exercises == exercises }

    val setCount: Int get() = exercises.sumOf { it.sets.size }
}
