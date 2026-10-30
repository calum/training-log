package com.calum.traininglog.core

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import kotlin.math.max

/** Per-exercise overrides; null means "use the library default". */
@Serializable
data class LiftOverride(
    val startKg: Double? = null,
    val incrementKg: Double? = null,
    val restSec: Int? = null,
)

/**
 * User-editable tuning. Every field has a default equal to the original hard-coded value, so
 * `UserSettings()` reproduces the app's behaviour before settings existed, and old saved JSON
 * (with no or partial settings) still decodes.
 */
@Serializable
data class UserSettings(
    val version: Int = 1,
    /** Overrides by exercise id (library exercises only). */
    val lifts: Map<String, LiftOverride> = emptyMap(),
    // Weekly targets
    val gymTarget: Int = 2,
    val treadmillTarget: Int = 3,
    val gymStretch: Int = 3,
    val treadmillStretch: Int = 4,
    // Travel and time
    val travelMinEachWay: Int = 10,
    val minLiftingMin: Int = 30,
    // Treadmill warm-up
    val warmupSpeedKmh: Double = 5.0,
    val warmupInclinePct: Double = 6.0,
    // Progression
    val deloadWeeks: Int = 6,
    val restartAfterDays: Int = 21,
    val restartFactor: Double = 0.9,
    val barKg: Double = 20.0,
    // Gentle evening reminder (opt-in, off by default)
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
    @Serializable(with = DayOfWeekSetSerializer::class) val reminderDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val reminderMaxPerWeek: Int = 3,
) {
    /** The library [exercise] with this user's starting weight, increment and rest applied. */
    fun resolve(exercise: Exercise): Exercise {
        val o = lifts[exercise.id] ?: return exercise
        return exercise.copy(
            startKg = o.startKg ?: exercise.startKg,
            incrementKg = o.incrementKg ?: exercise.incrementKg,
            restSec = o.restSec ?: exercise.restSec,
        )
    }

    /** Stretch ceilings are never below the good-week target. */
    val effectiveGymStretch: Int get() = max(gymStretch, gymTarget)
    val effectiveTreadmillStretch: Int get() = max(treadmillStretch, treadmillTarget)

    /** Minutes of lifting left from [minutesAvailable] after travelling both ways. */
    fun liftingMinutes(minutesAvailable: Int): Int = minutesAvailable - 2 * travelMinEachWay

    fun liftOverride(id: String): LiftOverride = lifts[id] ?: LiftOverride()

    /** Sets one override; values equal to the library default are dropped so Reset works cleanly. */
    fun withLift(id: String, o: LiftOverride): UserSettings {
        val base = ExerciseLibrary.find(id)
        val clean = if (base == null) o else LiftOverride(
            o.startKg?.takeIf { it != base.startKg },
            o.incrementKg?.takeIf { it != base.incrementKg },
            o.restSec?.takeIf { it != base.restSec },
        )
        val empty = clean.startKg == null && clean.incrementKg == null && clean.restSec == null
        return copy(lifts = if (empty) lifts - id else lifts + (id to clean))
    }

    /** Clamps every value into its allowed range (used by the settings screen). */
    fun sanitized(): UserSettings = copy(
        lifts = lifts.mapValues { (_, o) ->
            LiftOverride(
                o.startKg?.coerceIn(START_KG_RANGE),
                o.incrementKg?.coerceIn(INCREMENT_RANGE),
                o.restSec?.coerceIn(REST_RANGE),
            )
        },
        gymTarget = gymTarget.coerceIn(TARGET_RANGE),
        treadmillTarget = treadmillTarget.coerceIn(TARGET_RANGE),
        gymStretch = gymStretch.coerceIn(TARGET_RANGE),
        treadmillStretch = treadmillStretch.coerceIn(TARGET_RANGE),
        travelMinEachWay = travelMinEachWay.coerceIn(TRAVEL_RANGE),
        minLiftingMin = minLiftingMin.coerceIn(MIN_LIFTING_RANGE),
        warmupSpeedKmh = warmupSpeedKmh.coerceIn(SPEED_RANGE),
        warmupInclinePct = warmupInclinePct.coerceIn(0.0, MAX_INCLINE),
        deloadWeeks = deloadWeeks.coerceIn(DELOAD_RANGE),
        restartAfterDays = restartAfterDays.coerceIn(RESTART_DAYS_RANGE),
        restartFactor = restartFactor.coerceIn(RESTART_FACTOR_RANGE),
        barKg = barKg.coerceIn(BAR_RANGE),
        reminderHour = reminderHour.coerceIn(0, 23),
        reminderMinute = reminderMinute.coerceIn(0, 59),
        reminderMaxPerWeek = reminderMaxPerWeek.coerceIn(REMINDER_MAX_RANGE),
    )

    companion object {
        val START_KG_RANGE = 0.0..300.0
        val INCREMENT_RANGE = 0.5..10.0
        val REST_RANGE = 30..300
        val TARGET_RANGE = 0..7
        val TRAVEL_RANGE = 0..60
        val MIN_LIFTING_RANGE = 10..90
        val SPEED_RANGE = 2.0..12.0
        val DELOAD_RANGE = 3..12
        val RESTART_DAYS_RANGE = 7..90
        val RESTART_FACTOR_RANGE = 0.5..1.0
        val BAR_RANGE = 5.0..30.0
        val REMINDER_MAX_RANGE = 1..7
    }
}
