package com.calum.traininglog.core

import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Hyrox support: the eight stations, the race standards (as editable DATA), a coaching guide per station,
 * a six-level ladder per station, effort-based progression and a session builder. Pure and deterministic.
 *
 * A race is 8 rounds of [1 km run + one station]. The run is not a station here.
 * In logs ([StationLog]) `distanceM` / `reps` mean the TOTAL done at that weight in that block of work.
 */
enum class HyroxDivision(val label: String) {
    MEN_OPEN("Men's Open"),
    WOMEN_OPEN("Women's Open"),
    MEN_PRO("Men's Pro"),
    WOMEN_PRO("Women's Pro"),
}

enum class StationKind { DISTANCE, REPS }

/** The 8 stations in race order. [id] is what is stored in [StationLog.stationId]. */
enum class HyroxStation(val id: String, val displayName: String, val order: Int, val kind: StationKind) {
    SKIERG("skierg", "SkiErg", 1, StationKind.DISTANCE),
    SLED_PUSH("sled_push", "Sled Push", 2, StationKind.DISTANCE),
    SLED_PULL("sled_pull", "Sled Pull", 3, StationKind.DISTANCE),
    BURPEE_BROAD_JUMP("burpee_broad_jump", "Burpee Broad Jumps", 4, StationKind.DISTANCE),
    ROW("row", "Rowing", 5, StationKind.DISTANCE),
    FARMERS_CARRY("farmers_carry", "Farmers Carry", 6, StationKind.DISTANCE),
    SANDBAG_LUNGES("sandbag_lunges", "Sandbag Lunges", 7, StationKind.DISTANCE),
    WALL_BALLS("wall_balls", "Wall Balls", 8, StationKind.REPS);

    /** The two stations that load the knee hardest (deep bends, repeated landings). */
    val kneeHeavy: Boolean get() = this == BURPEE_BROAD_JUMP || this == SANDBAG_LUNGES

    /** True for stations done with an external load. */
    val weighted: Boolean get() = this != SKIERG && this != ROW && this != BURPEE_BROAD_JUMP

    companion object {
        fun byId(id: String): HyroxStation? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What one station asks for in a race. [weightKg] is the sled load (sled included) for the sleds, the weight
 * PER HAND for the farmers carry, the bag for the lunges and the ball for wall balls. [approx] marks values
 * that third-party sources disagreed on.
 */
data class StationStandard(
    val distanceM: Int? = null,
    val reps: Int? = null,
    val weightKg: Double? = null,
    val targetHeightM: Double? = null,
    val approx: Boolean = false,
    val note: String = "",
)

object HyroxStandards {
    const val standardsNote =
        "Standards as reported by third-party sources in Nov 2026: confirm at hyrox.com; the app lets you edit them"

    /** Station standard for [division]; [overrides] (station id to race weight) replace the stored weight. */
    fun of(division: HyroxDivision, station: HyroxStation, overrides: Map<String, Double> = emptyMap()): StationStandard {
        val base = table(division, station)
        val o = overrides[station.id]
        return if (o != null && base.weightKg != null) base.copy(weightKg = o, note = base.note.ifEmpty { "Weight edited by you" }) else base
    }

    private fun table(d: HyroxDivision, s: HyroxStation): StationStandard {
        return when (s) {
            HyroxStation.SKIERG -> StationStandard(distanceM = 1000)
            HyroxStation.ROW -> StationStandard(distanceM = 1000)
            HyroxStation.BURPEE_BROAD_JUMP -> StationStandard(distanceM = 80)
            HyroxStation.SLED_PUSH -> StationStandard(
                distanceM = 50,
                weightKg = when (d) { HyroxDivision.MEN_OPEN -> 152.0; HyroxDivision.WOMEN_OPEN -> 102.0; HyroxDivision.MEN_PRO -> 202.0; HyroxDivision.WOMEN_PRO -> 152.0 },
                note = "Load includes the sled",
            )
            HyroxStation.SLED_PULL -> StationStandard(
                distanceM = 50,
                weightKg = when (d) { HyroxDivision.MEN_OPEN -> 103.0; HyroxDivision.WOMEN_OPEN -> 78.0; HyroxDivision.MEN_PRO -> 153.0; HyroxDivision.WOMEN_PRO -> 103.0 },
                note = "Load includes the sled",
            )
            HyroxStation.FARMERS_CARRY -> StationStandard(
                distanceM = 200,
                weightKg = when (d) { HyroxDivision.MEN_OPEN -> 24.0; HyroxDivision.WOMEN_OPEN -> 16.0; HyroxDivision.MEN_PRO -> 32.0; HyroxDivision.WOMEN_PRO -> 24.0 },
                note = "Weight is per hand (2 implements)",
            )
            HyroxStation.SANDBAG_LUNGES -> StationStandard(
                distanceM = 100,
                weightKg = when (d) { HyroxDivision.MEN_OPEN -> 20.0; HyroxDivision.WOMEN_OPEN -> 10.0; HyroxDivision.MEN_PRO -> 30.0; HyroxDivision.WOMEN_PRO -> 20.0 },
            )
            HyroxStation.WALL_BALLS -> when (d) {
                HyroxDivision.MEN_OPEN -> StationStandard(reps = 100, weightKg = 6.0, targetHeightM = 3.0)
                HyroxDivision.WOMEN_OPEN -> StationStandard(
                    reps = 100, weightKg = 4.0, targetHeightM = 2.7, approx = true,
                    note = "Some sources say 75 reps; target height approximate (9 ft)",
                )
                HyroxDivision.MEN_PRO -> StationStandard(reps = 100, weightKg = 9.0, targetHeightM = 3.0, approx = true, note = "Target height approximate")
                HyroxDivision.WOMEN_PRO -> StationStandard(reps = 100, weightKg = 6.0, targetHeightM = 3.0, approx = true, note = "Target height approximate")
            }
        }
    }

    /** True when [log] reaches this station's full race standard (weight, and total distance or reps). */
    fun meets(standard: StationStandard, log: StationLog): Boolean {
        standard.weightKg?.let { w -> if ((log.weightKg ?: return false) < w - 0.001) return false }
        standard.distanceM?.let { d -> if ((log.distanceM ?: return false) < d) return false }
        standard.reps?.let { r -> if ((log.reps ?: return false) < r) return false }
        return true
    }

    fun meets(division: HyroxDivision, log: StationLog, overrides: Map<String, Double> = emptyMap()): Boolean {
        val st = HyroxStation.byId(log.stationId) ?: return false
        return meets(of(division, st, overrides), log)
    }
}

// ------------------------------------------------------------------------------------------------ guide

data class StationGuide(
    val summary: String,
    val whatItIs: String,
    val tips: List<String>,
    val mistakes: List<String>,
    val kneeNote: String?,
    val alternatives: List<String>,
    val pacingTip: String,
    /** True when the user is already confident here (the rower): the guide is short and race-pacing focused. */
    val confident: Boolean = false,
)

object HyroxGuide {
    fun of(station: HyroxStation): StationGuide = guides.getValue(station)

    private val guides: Map<HyroxStation, StationGuide> = mapOf(
        HyroxStation.SKIERG to StationGuide(
            summary = "Pull down and through with your whole body, not just your arms.",
            whatItIs = "1000 m on the SkiErg, a standing double-pole machine, straight after the first 1 km run.",
            tips = listOf(
                "Start tall: arms up, handles at head height, a slight lean from the hips.",
                "Pull down with straight arms first, then hinge at the hips as the handles pass your face.",
                "Use lats, core and legs together; finish with the handles by your thighs.",
                "Rhythm about 1:2: one strong pull, then twice as long to float back up.",
                "Keep shoulders down and hands relaxed on the way up.",
            ),
            mistakes = listOf(
                "Arming it: all biceps, no hips, so you tire fast.",
                "Rushing the recovery, which wrecks rhythm and breathing.",
                "Squatting deep or leaning back at the end of each pull.",
            ),
            kneeNote = "Standing and low impact. Keep the knee bend small and soft; stand a little taller if the right knee complains.",
            alternatives = listOf(
                "Cable straight-arm pulldown (same lat pattern)",
                "Lat pulldown, adding a small hip hinge on each rep",
                "Resistance-band straight-arm pulldown",
            ),
            pacingTip = "Hold a rhythm you could keep for 1.5 km. It is the first station, so skip the adrenaline sprint.",
        ),
        HyroxStation.SLED_PUSH to StationGuide(
            summary = "Stay low, lock your arms, and drive with short, strong steps.",
            whatItIs = "Push a weighted sled 50 m; the race load includes the weight of the sled.",
            tips = listOf(
                "Low body angle: hips below shoulders, chest toward the handles, back flat.",
                "Lock your arms straight into the handles so your legs do the pushing.",
                "Short, powerful steps, driving through the balls of the feet.",
                "Keep the sled moving; a moving sled is far easier than a restart.",
                "Stay low through the turns and brace your core.",
            ),
            mistakes = listOf(
                "Standing too tall, so the force goes up instead of forward.",
                "Long, slow strides that let the sled stop between steps.",
                "Bent elbows or a rounded lower back.",
            ),
            kneeNote = "Mostly quads and glutes in a low, controlled drive, so usually fine for a lifting-OK knee. Still start light.",
            alternatives = listOf(
                "Prowler or sled push on turf at the gym",
                "Heavy walking lunge or leg press",
                "Incline treadmill hands-on push (manual mode, motor off)",
            ),
            pacingTip = "Slow and continuous beats fast with a stop. Pick a pace you can hold the whole 50 m.",
        ),
        HyroxStation.SLED_PULL to StationGuide(
            summary = "Pull hand over hand, keep the rope low, and never let the sled stop.",
            whatItIs = "Pull a weighted sled 50 m towards you with a rope; the load includes the sled.",
            tips = listOf(
                "Pull hand over hand while stepping back, or sit low and lean back.",
                "Keep the rope low, near your hips, so the sled slides rather than lifts.",
                "Keep the sled moving; pausing costs more than a steady pull.",
                "Brace your core and keep your chest up; do not round forward.",
                "Use your legs and back, with arms as the link.",
            ),
            mistakes = listOf(
                "Pulling with the arms only and burning out your grip and biceps.",
                "Rope too high, so the sled drags or tips.",
                "Stopping to re-grip, which means restarting a heavy sled.",
            ),
            kneeNote = "Low knee stress. A wide, stable stance is kinder to the knee than a deep squat.",
            alternatives = listOf(
                "Rope pull on a cable stack, hand over hand",
                "Heavy seated cable row or chest-supported row",
                "Backwards sled drag on turf, if you have one",
            ),
            pacingTip = "Smooth and unbroken. Save your grip for the farmers carry two stations later.",
        ),
        HyroxStation.BURPEE_BROAD_JUMP to StationGuide(
            summary = "Chest to floor, a two-foot jump forward, repeat at a rhythm you can keep.",
            whatItIs = "Burpees where each rep ends in a broad jump forward; 80 m in total.",
            tips = listOf(
                "Chest to the floor, then press up and bring your feet under you.",
                "Jump forward with two feet and swing your arms; aim for steady, not maximal, distance.",
                "Step or jump back down; stepping is fine and saves your knees.",
                "Land softly, hips back, knees tracking over your toes.",
                "Find a sustainable rhythm early and breathe on the way up.",
            ),
            mistakes = listOf(
                "Going all out for the first 20 m and then stalling.",
                "Stiff-legged landings that jar the knees and back.",
                "Sagging hips on the floor instead of a tight plank.",
            ),
            kneeNote = "Deep bends plus repeated landings make this the hardest station for the knee. Use step-backs, shorter jumps, build distance slowly, and stop if it flares.",
            alternatives = listOf(
                "No equipment needed: practise it as it is, in small doses",
                "Step-back burpees with a small jump forward",
                "Burpees with no jump, then add the jump gradually",
            ),
            pacingTip = "A steady, slightly slow rhythm all the way. Rest is better taken standing, not on the floor.",
        ),
        HyroxStation.ROW to StationGuide(
            summary = "You know this one: just pace it for the fatigue of the stations before it.",
            whatItIs = "1000 m on a rowing machine, the fifth station.",
            tips = listOf(
                "Legs, body, arms on the drive; arms, body, legs on the recovery.",
                "Hold a split you can sustain; you arrive with tired legs and a high heart rate.",
                "Keep the stroke rate controlled and strong rather than frantic.",
                "Settle your breathing in the first 200 m, then hold.",
            ),
            mistakes = listOf(
                "Starting at your fresh 1 km pace instead of a realistic race one.",
                "Cutting the leg drive short as your legs tire.",
                "Pulling hard with the arms early and losing the rhythm.",
            ),
            kneeNote = null,
            alternatives = listOf(
                "Any rowing machine; a bike erg or SkiErg gives similar cardio",
            ),
            pacingTip = "Aim a touch slower than your fresh 1 km pace, then hold it. Race fatigue is the real limiter.",
            confident = true,
        ),
        HyroxStation.FARMERS_CARRY to StationGuide(
            summary = "Stand tall, pack your shoulders, and take short, fast steps without setting down.",
            whatItIs = "Carry two heavy handles (kettlebells) 200 m; the weight is per hand.",
            tips = listOf(
                "Stand tall, ribs down, shoulders back and down; do not let them shrug up.",
                "Short, fast steps keep the weights from swinging.",
                "Squeeze the handles hard and keep your wrists neutral.",
                "Do not put the weights down early; grip is the limiter, so plan for it.",
                "Check the current rules on chalk and straps at hyrox.com before relying on them.",
            ),
            mistakes = listOf(
                "Leaning back or to one side under the load.",
                "Long strides that swing the weights.",
                "Setting down too early because the grip fades.",
            ),
            kneeNote = "Walking only: usually fine. Take short steps and avoid a stiff, locked knee.",
            alternatives = listOf(
                "Dumbbell or kettlebell farmers walks in the gym",
                "Heavy dumbbell holds for time to build grip",
                "Dead hangs from a bar",
            ),
            pacingTip = "Walk briskly and unbroken. If you must stop, make it short and pick up straight away.",
        ),
        HyroxStation.SANDBAG_LUNGES to StationGuide(
            summary = "Bag on your shoulders, knee kisses the ground, upright torso, steady cadence.",
            whatItIs = "Walking lunges for 100 m while carrying a sandbag across the shoulders.",
            tips = listOf(
                "Bag on your shoulders or behind your neck, torso upright.",
                "Back knee kisses the ground each rep, with a controlled drop.",
                "Step through at a steady cadence; do not rush.",
                "Watch the front knee: it should track over the middle of your foot.",
                "Keep your core braced so the bag does not pull you forward.",
            ),
            mistakes = listOf(
                "Short, shuffly steps that overload the front knee.",
                "Front knee caving inward as you tire.",
                "Rushing early, then wobbling for the last 30 m.",
            ),
            kneeNote = "Loaded knee bends make this the other knee-heavy station. Begin with bodyweight, add load slowly, and take a slightly longer step to keep the shin more upright.",
            alternatives = listOf(
                "Loaded rucksack walking lunges",
                "Goblet lunge with a dumbbell",
                "Reverse lunges or split squats if forward lunges bother the knee",
            ),
            pacingTip = "Find a cadence you can keep for 100 m. Slow and clean beats fast and wobbly.",
        ),
        HyroxStation.WALL_BALLS to StationGuide(
            summary = "Squat deep, drive up with the legs, and break your sets early and short.",
            whatItIs = "A weighted ball thrown to a target on the wall: 100 reps in the race, after seven rounds of work.",
            tips = listOf(
                "Squat with the hip crease below the knee, chest up.",
                "Drive up with the legs and let the throw follow through.",
                "Catch the ball with the squat, absorbing it into the next rep.",
                "Aim above the target line, not at it.",
                "Breathe at the top; plan short breaks (about 10 to 20 reps) before you need them.",
            ),
            mistakes = listOf(
                "Throwing with the arms and skipping the legs.",
                "Cutting depth as you tire (the rep may be no-repped).",
                "Waiting to break until your arms are gone.",
            ),
            kneeNote = "Repeated squats are generally knee-friendly when the knee is OK in lifting; keep the depth you can control.",
            alternatives = listOf(
                "Medicine ball squat and throw, or a ball slam variation",
                "Thrusters with a dumbbell or barbell",
                "Goblet squat into an overhead press",
            ),
            pacingTip = "Plan sets of 15 to 25 with short rests rather than one big set and a crash.",
        ),
    )
}

// ------------------------------------------------------------------------------------------------ ladder

/**
 * One rung of a station's ladder. For stations done in repeats, [rounds] x ([distanceM] or [reps]) is the
 * total volume. Level 5 is the race standard (see [HyroxLadder.RACE_LEVEL]); level 6 is "race plus".
 */
data class StationLevel(
    val level: Int,
    val label: String,
    val weightKg: Double?,
    val distanceM: Int?,
    val reps: Int?,
    val rounds: Int,
    val restSec: Int,
    val targetNote: String,
) {
    /** Total distance or reps over all rounds. */
    val totalWork: Int get() = rounds * (distanceM ?: reps ?: 0)
}

object HyroxLadder {
    const val RACE_LEVEL = 5
    const val LEVELS = 6

    /** Level a brand-new user starts on: 3 for rowing and SkiErg (he is confident there), else 1. */
    fun startLevel(station: HyroxStation): Int =
        if (station == HyroxStation.ROW || station == HyroxStation.SKIERG) 3 else 1

    fun of(division: HyroxDivision, station: HyroxStation, overrides: Map<String, Double> = emptyMap()): List<StationLevel> {
        val std = HyroxStandards.of(division, station, overrides)
        val race = std.weightKg
        val step = when (station) {
            HyroxStation.SLED_PUSH, HyroxStation.SLED_PULL -> 2.5
            else -> 1.0
        }
        val minKg = if (station == HyroxStation.WALL_BALLS) 2.0 else 1.0
        fun w(frac: Double): Double? = race?.let { if (frac <= 0.0) null else if (frac >= 1.0) it else max(minKg, roundTo(it * frac, step)) }
        fun lv(n: Int, label: String, frac: Double, per: Int, rounds: Int, rest: Int, note: String): StationLevel {
            val reps = station.kind == StationKind.REPS
            return StationLevel(n, label, w(frac).takeIf { station.weighted }, if (reps) null else per, if (reps) per else null, rounds, rest, note)
        }
        val h = std.targetHeightM?.let { "Target about $it m." } ?: ""
        return when (station) {
            HyroxStation.SLED_PUSH -> listOf(
                lv(1, "Learn the movement", 0.3, 10, 4, 90, "Light sled. Low angle, locked arms, short steps. Easy pace."),
                lv(2, "Add distance", 0.5, 20, 4, 90, "Keep the sled moving the whole length."),
                lv(3, "Heavier", 0.7, 25, 4, 90, "Stay low through the turn."),
                lv(4, "Near race load", 0.85, 25, 3, 120, "Strong, steady steps; rest fully between pushes."),
                lv(5, "Race standard", 1.0, 50, 1, 180, "One unbroken 50 m at race load."),
                lv(6, "Race plus", 1.0, 50, 2, 180, "Two 50 m pushes with a full rest, or beat your time."),
            )
            HyroxStation.SLED_PULL -> listOf(
                lv(1, "Learn the movement", 0.3, 10, 4, 90, "Light sled. Hand over hand, rope low, step back."),
                lv(2, "Add distance", 0.5, 20, 4, 90, "Do not let the sled stop."),
                lv(3, "Heavier", 0.7, 25, 4, 90, "Brace the core, chest up."),
                lv(4, "Near race load", 0.85, 25, 3, 120, "Steady pulls; save your grip."),
                lv(5, "Race standard", 1.0, 50, 1, 180, "One unbroken 50 m pull at race load."),
                lv(6, "Race plus", 1.0, 50, 2, 180, "Two 50 m pulls with a full rest, or beat your time."),
            )
            HyroxStation.SKIERG -> listOf(
                lv(1, "Learn the movement", 0.0, 100, 4, 60, "Easy 100 m reps. Tall start, hinge, straight-arm pull."),
                lv(2, "Build the rhythm", 0.0, 200, 4, 60, "Smooth 1:2 rhythm; legs and core, not only arms."),
                lv(3, "Steady 1 km of work", 0.0, 250, 4, 60, "Comfortable pace you could repeat."),
                lv(4, "Longer pieces", 0.0, 500, 2, 90, "Two 500 m pieces at a steady pace."),
                lv(5, "Race standard", 0.0, 1000, 1, 0, "1000 m at a steady pace you could hold on race day."),
                lv(6, "Race plus", 0.0, 1000, 2, 300, "Two 1000 m pieces, or aim for a time target."),
            )
            HyroxStation.ROW -> listOf(
                lv(1, "Easy volume", 0.0, 250, 3, 60, "Relaxed technique reps."),
                lv(2, "Steady pieces", 0.0, 500, 3, 90, "Hold an even split."),
                lv(3, "Long pieces", 0.0, 750, 2, 120, "Even splits across both pieces."),
                lv(4, "Full distance, easy", 0.0, 1000, 1, 0, "1000 m comfortable; practise pacing."),
                lv(5, "Race standard", 0.0, 1000, 1, 0, "1000 m at race pace, a touch slower than fresh."),
                lv(6, "Race plus", 0.0, 1000, 2, 300, "Row 1 km after a hard block, or aim for a time target."),
            )
            HyroxStation.BURPEE_BROAD_JUMP -> listOf(
                lv(1, "Learn the movement", 0.0, 10, 4, 90, "Step back, small jump forward. Land softly."),
                lv(2, "Build slowly", 0.0, 15, 4, 90, "Add distance only if the knee is calm."),
                lv(3, "Longer sets", 0.0, 20, 4, 90, "Steady rhythm; step back if the jump tires you."),
                lv(4, "Half race", 0.0, 40, 2, 120, "Two 40 m pieces at an even rhythm."),
                lv(5, "Race standard", 0.0, 80, 1, 180, "80 m, steady rhythm. Stop if the knee flares."),
                lv(6, "Race plus", 0.0, 80, 2, 240, "Two 80 m pieces only if the knee is happy."),
            )
            HyroxStation.FARMERS_CARRY -> listOf(
                lv(1, "Learn the movement", 0.5, 25, 4, 60, "Tall, shoulders packed, short steps."),
                lv(2, "Heavier", 0.7, 25, 4, 60, "Squeeze the handles."),
                lv(3, "Longer carries", 0.7, 50, 4, 75, "Do not put them down early."),
                lv(4, "Near race load", 0.85, 50, 4, 90, "Hold the grip; short fast steps."),
                lv(5, "Race standard", 1.0, 200, 1, 180, "200 m at race weight, as few stops as you can."),
                lv(6, "Race plus", 1.0, 200, 2, 240, "Two 200 m carries with a rest, or a faster time."),
            )
            HyroxStation.SANDBAG_LUNGES -> listOf(
                lv(1, "Learn the movement", 0.0, 10, 3, 90, "Bodyweight walking lunges. Knee tracks over the foot."),
                lv(2, "Add a light bag", 0.25, 10, 4, 90, "Bag on your shoulders. Control the drop."),
                lv(3, "Build the load", 0.5, 15, 4, 90, "Steady cadence, upright torso."),
                lv(4, "Near race load", 0.75, 25, 4, 120, "Stop sets early if the form slips."),
                lv(5, "Race standard", 1.0, 100, 1, 240, "100 m at race load. Slow and clean beats fast."),
                lv(6, "Race plus", 1.0, 100, 2, 300, "Two 100 m pieces only if the knee is happy."),
            )
            HyroxStation.WALL_BALLS -> listOf(
                lv(1, "Learn the movement", 0.5, 10, 3, 60, "Light ball. Depth, drive, catch. $h".trim()),
                lv(2, "Build volume", 0.67, 10, 4, 60, "Squat below parallel every rep."),
                lv(3, "More reps", 0.83, 15, 4, 75, "Breathe at the top."),
                lv(4, "Race weight", 1.0, 20, 4, 90, "Race ball, sets of 20. $h".trim()),
                lv(5, "Race standard", 1.0, 100, 1, 240, "100 reps; plan on 3 to 4 sets of about 25."),
                lv(6, "Race plus", 1.0, 150, 1, 300, "150 reps in sets of 25 to 30, or beat your 100-rep time."),
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------ progression

data class StationEntry(val date: LocalDate, val log: StationLog)

data class StationPrescription(
    val level: Int,
    val label: String,
    val weightKg: Double?,
    val distanceM: Int?,
    val reps: Int?,
    val rounds: Int,
    val restSec: Int,
    val note: String,
) {
    val totalWork: Int get() = rounds * (distanceM ?: reps ?: 0)
}

object HyroxProgression {
    /** [history] is every earlier entry for this station (any order; sorted newest first inside). */
    fun next(
        station: HyroxStation,
        division: HyroxDivision,
        history: List<StationEntry>,
        overrides: Map<String, Double> = emptyMap(),
    ): StationPrescription {
        val ladder = HyroxLadder.of(division, station, overrides)
        val sorted = history.sortedByDescending { it.date }
        val last = sorted.firstOrNull()
        if (last == null) {
            val start = HyroxLadder.startLevel(station)
            val why = if (start > 1) {
                "Starting here because you are already comfortable with this movement; go down a level if it feels too much."
            } else {
                "First time: start light and learn the movement."
            }
            return presc(ladder[start - 1], why)
        }
        val lastLevel = (last.log.level ?: HyroxLadder.startLevel(station)).coerceIn(1, ladder.size)
        val rpe = last.log.rpe
        val doneLast = completed(last.log, ladder[lastLevel - 1], station)
        val hardLast = !doneLast || (rpe != null && rpe >= 10)
        val prev = sorted.getOrNull(1)
        val hardPrev = prev?.let {
            val l = (it.log.level ?: HyroxLadder.startLevel(station)).coerceIn(1, ladder.size)
            !completed(it.log, ladder[l - 1], station) || (it.log.rpe != null && it.log.rpe >= 10)
        } ?: false

        if (hardLast && hardPrev) {
            val target = max(1, lastLevel - 1)
            val msg = if (target < lastLevel) "Two tough ones in a row, so a step back to level $target. That is normal and it makes the next step stick."
            else "Two tough ones in a row. Staying at level 1 with a lighter, shorter feel: take it easy and enjoy it."
            return presc(ladder[target - 1], msg)
        }
        if (doneLast && rpe != null && rpe <= 8 && last.log.technique != 1) {
            val target = min(ladder.size, lastLevel + 1)
            val msg = if (target > lastLevel) "Last time felt controlled (effort $rpe/10), so moving up to level $target."
            else "Top of the ladder. Repeat, or chase a faster time."
            return presc(ladder[target - 1], msg)
        }
        val msg = when {
            last.log.technique == 1 && doneLast && (rpe ?: 0) <= 8 -> "Strong effort. One more session on this level to polish the technique before moving up."
            rpe == 9 -> "That was close to your limit, so repeat this level and let it settle."
            rpe == null -> "Add an effort rating (RPE) after the session so the next step can be picked well. Repeating this level."
            !doneLast || rpe >= 10 -> "A hard one. Repeat this level, and shorten it if you need to."
            else -> "Repeat this level."
        }
        return presc(ladder[lastLevel - 1], msg)
    }

    private fun presc(l: StationLevel, why: String) = StationPrescription(
        level = l.level, label = l.label, weightKg = l.weightKg, distanceM = l.distanceM, reps = l.reps,
        rounds = l.rounds, restSec = l.restSec,
        note = "Level ${l.level} of ${HyroxLadder.LEVELS}: ${l.targetNote} $why".replace("  ", " ").trim(),
    )

    internal fun completed(log: StationLog, planned: StationLevel, station: HyroxStation): Boolean {
        val loggedWork = if (station.kind == StationKind.REPS) log.reps else log.distanceM
        if (loggedWork != null && loggedWork < planned.totalWork) return false
        val pw = planned.weightKg
        if (pw != null && log.weightKg != null && log.weightKg < pw - 0.001) return false
        return true
    }
}

// ------------------------------------------------------------------------------------------------ session plan

data class StationBlock(
    val station: HyroxStation,
    val prescription: StationPrescription,
    val estimatedMin: Int,
    /** Easy compromised-run metres after this block (0 when runs are off). */
    val runAfterM: Int = 0,
)

data class HyroxSessionPlan(
    val blocks: List<StationBlock>,
    val runMetres: Int,
    val estimatedMinutes: Int,
    val label: String,
    val warmupNote: String,
    val notes: List<String> = emptyList(),
) {
    companion object {
        /** See [HyroxPlanner.build]. */
        fun build(
            minutes: Int,
            records: List<SessionRecord>,
            division: HyroxDivision,
            settings: UserSettings = UserSettings(),
            focus: Set<HyroxStation>? = null,
            withRuns: Boolean = false,
        ): HyroxSessionPlan = HyroxPlanner.build(minutes, records, division, settings, focus, withRuns)
    }
}

object HyroxPlanner {
    internal const val WARMUP_MIN = 8
    internal const val RUN_M = 400
    internal const val RUN_MIN = 3
    internal const val MAX_BLOCKS = 4
    internal const val KNEE_CAP_SCORE = 4

    /** Builds a 20-90 minute session of 2-4 stations (as many as fit). Deterministic. */
    fun build(
        minutes: Int,
        records: List<SessionRecord>,
        division: HyroxDivision,
        settings: UserSettings = UserSettings(),
        focus: Set<HyroxStation>? = null,
        withRuns: Boolean = false,
    ): HyroxSessionPlan {
        val total = minutes.coerceIn(20, 90)
        val overrides = settings.hyroxWeightOverrides
        val hyrox = records.filter { it.type == SessionType.HYROX }.sortedWith(compareBy({ it.date }, { it.id }))
        val lastKnee = records.filter { it.kneePain != null }.sortedWith(compareBy({ it.date }, { it.id })).lastOrNull()?.kneePain
        val kneeHigh = lastKnee != null && lastKnee >= HyroxPlanner.KNEE_CAP_SCORE
        val runsOn = withRuns && !kneeHigh

        fun entries(s: HyroxStation) = hyrox.flatMap { r -> r.stations.filter { it.stationId == s.id }.map { StationEntry(r.date, it) } }.reversed()
        fun sessionsSince(s: HyroxStation): Int {
            val idx = hyrox.indexOfLast { r -> r.stations.any { it.stationId == s.id } }
            return if (idx < 0) 50 else hyrox.size - 1 - idx
        }

        val pool = HyroxStation.entries.filter { focus.isNullOrEmpty() || it in focus }
        data class Cand(val station: HyroxStation, val score: Double, val presc: StationPrescription)
        val cands = pool.map { s ->
            val hist = entries(s)
            val p = HyroxProgression.next(s, division, hist, overrides)
            val lastLevel = hist.firstOrNull()?.log?.level
            // Stations that are ready to move up (their next level is higher than last time) are a little more attractive.
            val ready = if (lastLevel != null && p.level > lastLevel) 1.5 else 0.0
            Cand(s, sessionsSince(s) + ready, p)
        }.sortedWith(compareByDescending<Cand> { it.score }.thenBy { it.station.order })

        val warm = HyroxPlanner.WARMUP_MIN
        var remaining = total - warm
        val blocks = mutableListOf<StationBlock>()
        var kneeHeavyCount = 0
        val runMin = if (runsOn) HyroxPlanner.RUN_MIN else 0
        for (c in cands) {
            if (blocks.size >= HyroxPlanner.MAX_BLOCKS) break
            if (kneeHigh && c.station.kneeHeavy && kneeHeavyCount >= 1) continue
            var p = c.presc
            var est = estimateMin(c.station, p) + runMin
            while (est > remaining && p.rounds > 1) {
                p = p.copy(rounds = p.rounds - 1)
                est = estimateMin(c.station, p) + runMin
            }
            if (est > remaining) continue
            blocks += StationBlock(c.station, p, est - runMin, if (runsOn) HyroxPlanner.RUN_M else 0)
            remaining -= est
            if (c.station.kneeHeavy) kneeHeavyCount++
        }
        val runMetres = blocks.sumOf { it.runAfterM }
        val est = warm + blocks.sumOf { it.estimatedMin + (if (runsOn) runMin else 0) }
        val notes = buildList {
            if (kneeHigh) add("Your knee was sore last time, so this plan keeps to one knee-heavy station at most and skips running. Stop any station that flares it.")
            if (withRuns && runsOn) add("Short easy runs between blocks to get used to running on tired legs. Keep them easy.")
            add(HyroxStandards.standardsNote)
        }
        val label = if (blocks.isEmpty()) "Hyrox practice" else "Hyrox: " + blocks.joinToString(", ") { it.station.displayName }
        return HyroxSessionPlan(
            blocks = blocks, runMetres = runMetres, estimatedMinutes = est, label = label,
            warmupNote = "Warm-up (about $warm min): easy cardio for 4 to 5 min on the bike, rower or a brisk walk, then bodyweight squats, hip hinges and arm circles. Do one light practice set of the first station.",
            notes = notes,
        )
    }

    /** Rough clock time for a prescription: work at a typical pace, rests between rounds, and a minute of setup. */
    fun estimateMin(station: HyroxStation, p: StationPrescription): Int {
        val rate = when (station) {
            HyroxStation.SKIERG -> 0.28
            HyroxStation.ROW -> 0.25
            HyroxStation.SLED_PUSH -> 1.2
            HyroxStation.SLED_PULL -> 1.5
            HyroxStation.BURPEE_BROAD_JUMP -> 4.0
            HyroxStation.FARMERS_CARRY -> 0.8
            HyroxStation.SANDBAG_LUNGES -> 2.4
            HyroxStation.WALL_BALLS -> 4.0
        }
        val sec = p.totalWork * rate + (p.rounds - 1) * p.restSec + 60
        return ceil(sec / 60.0).toInt()
    }
}
