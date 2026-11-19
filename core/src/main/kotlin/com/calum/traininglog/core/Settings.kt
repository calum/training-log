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
    /** Used only for the treadmill calorie estimate. Placeholder until you set yours in Settings. */
    val bodyWeightKg: Double = 80.0,
    // Gentle evening reminder (opt-in, off by default)
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
    @Serializable(with = DayOfWeekSetSerializer::class) val reminderDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val reminderMaxPerWeek: Int = 3,
    // Hyrox
    val hyroxDivision: HyroxDivision = HyroxDivision.MEN_OPEN,
    /** Race-weight overrides by station id (kg; per hand for the farmers carry), for editing the stored standards. */
    val hyroxWeightOverrides: Map<String, Double> = emptyMap(),
    /** Kit ticked at the gym (everything) and at home (only the treadmill until he buys the rest). */
    @Serializable(with = HyroxGearSetSerializer::class) val hyroxGymGear: Set<HyroxGear> = HyroxGear.entries.toSet(),
    @Serializable(with = HyroxGearSetSerializer::class) val hyroxHomeGear: Set<HyroxGear> = setOf(HyroxGear.TREADMILL),
    /** Where the last Hyrox session was planned for. */
    val hyroxPlace: HyroxPlace = HyroxPlace.GYM,
    /** Treadmill run between stations: speed (6 to 16 km/h, steps of 0.5) and incline. */
    val hyroxRunSpeedKmh: Double = 9.0,
    val hyroxRunInclinePct: Double = 1.0,
    /** Race-preparation plan (event date, sessions per week, on/off). */
    /** Calum's answers: 4 sessions a week and about 5 hours (300 min) at most in any week. The planner's own defaults are unchanged. */
    val racePlan: RacePlanSettings = RacePlanSettings(sessionsPerWeek = 4, maxWeeklyMinutes = 300),
    /** Time allowed for each transition (the "Roxzone") in a Hyrox simulation: 10 to 180 s in steps of 5. 45 s suits the gym. */
    val hyroxTransitionSec: Int = HyroxSim.DEFAULT_TRANSITION_SEC,
    /** Gym sessions show one set at a time with big buttons (on by default); off shows the whole list. */
    val guidedGym: Boolean = true,
    /** What he owns at home for knee care: the routine never uses gear that is not ticked here. */
    val kneeKit: KneeCareKit = KneeCareKit(),
) {
    /** The kit ticked for [place]. */
    fun hyroxGearAt(place: HyroxPlace): Set<HyroxGear> = if (place == HyroxPlace.GYM) hyroxGymGear else hyroxHomeGear

    /** The kit at the place he last picked. */
    val hyroxGear: Set<HyroxGear> get() = hyroxGearAt(hyroxPlace)

    /** Ticks or unticks [gear] at [place]. */
    fun withHyroxGear(place: HyroxPlace, gear: HyroxGear, on: Boolean): UserSettings {
        val now = hyroxGearAt(place).let { if (on) it + gear else it - gear }
        return if (place == HyroxPlace.GYM) copy(hyroxGymGear = now) else copy(hyroxHomeGear = now)
    }

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
        bodyWeightKg = bodyWeightKg.coerceIn(BODY_WEIGHT_RANGE),
        reminderHour = reminderHour.coerceIn(0, 23),
        reminderMinute = reminderMinute.coerceIn(0, 59),
        reminderMaxPerWeek = reminderMaxPerWeek.coerceIn(REMINDER_MAX_RANGE),
        hyroxTransitionSec = HyroxSim.snapTransition(hyroxTransitionSec),
        hyroxRunSpeedKmh = HyroxRuns.snapSpeed(hyroxRunSpeedKmh),
        hyroxRunInclinePct = roundTo(hyroxRunInclinePct.coerceIn(HyroxRuns.INCLINE_RANGE), 0.5),
        racePlan = racePlan.sanitized(),
        kneeKit = kneeKit.sanitized(),
        hyroxGymGear = hyroxGymGear.toSet(),
        hyroxHomeGear = hyroxHomeGear.toSet(),
        hyroxWeightOverrides = hyroxWeightOverrides
            .filter { (id, _) -> HyroxStation.byId(id)?.weighted == true }
            .mapValues { (_, kg) -> kg.coerceIn(HYROX_KG_RANGE) },
    )

    /** The station standard for the chosen division with this user's weight edits applied. */
    fun hyroxStandard(station: HyroxStation): StationStandard =
        HyroxStandards.of(hyroxDivision, station, hyroxWeightOverrides)

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
        val BODY_WEIGHT_RANGE = 30.0..250.0
        val REMINDER_MAX_RANGE = 1..7
        val HYROX_KG_RANGE = 1.0..300.0
    }
}
