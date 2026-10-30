package com.calum.traininglog.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.builtins.SetSerializer
import java.time.DayOfWeek
import java.time.LocalDate

object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

object DayOfWeekSerializer : KSerializer<DayOfWeek> {
    override val descriptor = PrimitiveSerialDescriptor("DayOfWeek", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: DayOfWeek) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): DayOfWeek = DayOfWeek.valueOf(decoder.decodeString())
}

object DayOfWeekSetSerializer : KSerializer<Set<DayOfWeek>> by SetSerializer(DayOfWeekSerializer)

object LocalDateSetSerializer : KSerializer<Set<LocalDate>> by SetSerializer(LocalDateSerializer)

enum class Feeling { GOOD, OKAY, ROUGH, ILL }
enum class SessionType { GYM, TREADMILL, CORE, HIKE }
enum class MissReason { ROUGH_DAY, AT_LIMIT }
enum class Effort { TOO_EASY, JUST_RIGHT, TOO_HARD }

@Serializable
data class LoggedSet(val weightKg: Double, val reps: Int, val targetReps: Int, val rir: Int? = null)

@Serializable
data class ExerciseLog(
    val exerciseId: String,
    val sets: List<LoggedSet>,
    val missReason: MissReason? = null,
)

@Serializable
data class TreadmillLog(
    val style: TreadmillStyle,
    val plannedSec: Int,
    val completedSec: Int,
    val effort: Effort? = null,
)

@Serializable
data class SessionRecord(
    val id: String,
    @Serializable(with = LocalDateSerializer::class) val date: LocalDate,
    val type: SessionType,
    val minutesAvailable: Int,
    val minutesActual: Int,
    val feeling: Feeling,
    val kneePain: Int? = null,
    val dayLabel: String? = null,
    val deload: Boolean = false,
    val exercises: List<ExerciseLog> = emptyList(),
    val treadmill: TreadmillLog? = null,
    val note: String? = null,
)

/** Persisted user state that is not a session: calibration and tuning. */
@Serializable
data class Profile(
    val treadmill: Calibration = Calibration(),
    @Serializable(with = LocalDateSerializer::class) val lastDeload: LocalDate? = null,
    @Serializable(with = DayOfWeekSetSerializer::class) val restWeekdays: Set<DayOfWeek> = emptySet(),
    @Serializable(with = LocalDateSetSerializer::class) val restDates: Set<LocalDate> = emptySet(),
    @Serializable(with = LocalDateSetSerializer::class) val workoutOverrides: Set<LocalDate> = emptySet(),
)

fun roundTo(x: Double, step: Double): Double = Math.round(x / step) * step
