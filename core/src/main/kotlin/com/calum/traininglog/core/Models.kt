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

/** Kit sets are stored as lists of enum names. Unknown names (from a newer app) are not expected; they would fail loudly. */
object HyroxGearSetSerializer : KSerializer<Set<HyroxGear>> by SetSerializer(kotlinx.serialization.serializer<HyroxGear>())

object LocalDateSetSerializer : KSerializer<Set<LocalDate>> by SetSerializer(LocalDateSerializer)

enum class Feeling { GOOD, OKAY, ROUGH, ILL }
enum class SessionType { GYM, TREADMILL, CORE, HIKE, HYROX }

/** Sessions counted as "other" in weekly stats (never in the gym or treadmill targets). */
val SessionType.countsAsOther: Boolean get() = this == SessionType.CORE || this == SessionType.HIKE || this == SessionType.HYROX
enum class MissReason { ROUGH_DAY, AT_LIMIT }
enum class Effort { TOO_EASY, JUST_RIGHT, TOO_HARD }

@Serializable
data class LoggedSet(val weightKg: Double, val reps: Int, val targetReps: Int, val rir: Int? = null)

@Serializable
data class ExerciseLog(
    val exerciseId: String,
    val sets: List<LoggedSet>,
    val missReason: MissReason? = null,
    /** Display name for free-text exercises (ids like `custom:cable_face_pull`); null for library exercises. */
    val customName: String? = null,
    /** Movement group chosen for a free-text exercise (e.g. "vertical-pull"); null when none/library. */
    val customMovement: String? = null,
)

@Serializable
data class TreadmillLog(
    val style: TreadmillStyle,
    val plannedSec: Int,
    val completedSec: Int,
    val effort: Effort? = null,
    /** Estimated from the plan's speeds and inclines (see [TreadmillStatsCalc]); null on older sessions. */
    val distanceM: Int? = null,
    val climbM: Int? = null,
    val kcal: Int? = null,
)

/**
 * One Hyrox station in a session. [distanceM] / [reps] are the TOTAL done at [weightKg] (per hand for the
 * farmers carry). [rpe] is 1-10, [technique] 1-3 (1 = needs work), [level] the ladder level that was prescribed.
 */
@Serializable
data class StationLog(
    val stationId: String,
    val weightKg: Double? = null,
    val distanceM: Int? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val rpe: Int? = null,
    val technique: Int? = null,
    val level: Int? = null,
    /**
     * How much work (metres or reps) this block was meant to be, when that was less than the full level
     * (a plan fitted to a short session, or ended early). Null means 'the level's full work'. A shortened
     * block that was completed is neither 'hard' nor a reason to move up.
     */
    val plannedWork: Int? = null,
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
    val stations: List<StationLog> = emptyList(),
)

/** Persisted user state that is not a session: calibration and tuning. */
@Serializable
data class Profile(
    val treadmill: Calibration = Calibration(),
    @Serializable(with = LocalDateSerializer::class) val lastDeload: LocalDate? = null,
    /** Weekdays that are rest days every week. Default Tuesday and Friday (older saved data without the field gets this too). */
    @Serializable(with = DayOfWeekSetSerializer::class) val restWeekdays: Set<DayOfWeek> = DEFAULT_REST_WEEKDAYS,
    @Serializable(with = LocalDateSetSerializer::class) val restDates: Set<LocalDate> = emptySet(),
    @Serializable(with = LocalDateSetSerializer::class) val workoutOverrides: Set<LocalDate> = emptySet(),
    /** Plan screen headline target (lift id and kg). */
    val headlineExerciseId: String = "squat",
    val headlineKg: Double = 100.0,
    /** User-editable tuning (starting weights, increments, rest times, targets...). */
    val settings: UserSettings = UserSettings(),
    /** Training blocks and their history. */
    val blocks: BlockState = BlockState(),
    /** Race plan: moved and skipped sessions, and the replan alert snooze (see [PlanAdjustments]). */
    val planAdjustments: PlanAdjustments = PlanAdjustments(),
)

/** Rest weekdays a new (or older, field-less) profile starts with: Tuesday and Friday. */
val DEFAULT_REST_WEEKDAYS: Set<DayOfWeek> = setOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY)

fun roundTo(x: Double, step: Double): Double = Math.round(x / step) * step
