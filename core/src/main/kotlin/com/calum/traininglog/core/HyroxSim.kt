package com.calum.traininglog.core

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.max

/**
 * Hyrox SIMULATION mode: a true race-order rehearsal (run 1 km, station, run 1 km, station, ...) at the division's
 * race standard, optionally with lighter loads for a first attempt, with timed TRANSITIONS (the "Roxzone") between
 * every run and station. Pure and deterministic.
 *
 * Design choices (see docs/hyrox-plan-evidence.md, "Simulation mode"):
 *  - A simulation is a REHEARSAL, never a test. Nothing here can be failed; times are information.
 *  - Run legs are knee-gated: only the INTERVALS and RACE_RUNS stages default to a real run. Before that the run
 *    legs are a knee-safe substitute of the same effort (rower, SkiErg, bike, incline walk).
 *  - Split targets are ESTIMATES built from typical proportions of a Hyrox (the first Hyrox study found running
 *    to be about 59% of the time, median 51.2 of 86.5 minutes). They exist so the treadmill can count down and so
 *    the results can say "quicker / slower"; they are not a standard to meet.
 *  - The clock never stops, as in a real race: the time between a run and a station counts in the total. Each
 *    transition has an ALLOWANCE (a countdown), and running over it is shown calmly, never as a penalty.
 *  - A finished simulation is stored as ONE [SessionRecord] with [SessionRecord.sim].
 *
 * Numbering: a [SimPlan] holds every leg in order including the transitions ([SimPlan.legs], FULL = 31); the 16
 * run and station legs are [SimPlan.raceLegs]. A record's `stations` list and [SimMeta.skipped] use the RACE-leg
 * numbering (0..15); the transitions are stored as seconds in [SimMeta.transitionSec] (entry k is the transition
 * after race leg k).
 */
enum class SimFormat(val id: String, val label: String, val stations: List<HyroxStation>) {
    FULL("full", "Full", HyroxStation.entries.toList()),
    FIRST_HALF("first_half", "First half", HyroxStation.entries.take(4)),
    SECOND_HALF("second_half", "Second half", HyroxStation.entries.drop(4));

    /** Run legs plus station legs (the transitions are not counted). */
    val legCount: Int get() = stations.size * 2

    /** "Simulation: full" / "Simulation: first half": the record's `dayLabel`. */
    val dayLabel: String get() = "Simulation: ${label.lowercase()}"

    companion object {
        fun byId(id: String): SimFormat? = entries.firstOrNull { it.id == id }
    }
}

/** What stands in for a 1 km run on a run leg. The last four are KNEE-SAFE substitutes at about the same effort. */
enum class RunMode(val id: String, val label: String, val distanceM: Int, val running: Boolean) {
    TREADMILL("treadmill", "Treadmill run", 1000, true),
    OUTSIDE("outside", "Outside run", 1000, true),
    WALK_INCLINE("walk_incline", "Incline walk", 1000, false),
    ROW("row", "Rower", 1000, false),
    SKIERG("skierg", "SkiErg", 1000, false),
    BIKE("bike", "Bike", 3000, false);

    /** "1 km" or "3 km". */
    val distanceText: String get() = if (distanceM % 1000 == 0) "${distanceM / 1000} km" else "$distanceM m"

    /** "a rower 1000 m", "a 3 km bike ride": how a plain sentence names this leg. */
    val phrase: String
        get() = when (this) {
            TREADMILL -> "a 1 km treadmill run"
            OUTSIDE -> "a 1 km run outside"
            WALK_INCLINE -> "a 1 km incline walk"
            ROW -> "a rower 1000 m"
            SKIERG -> "a SkiErg 1000 m"
            BIKE -> "a 3 km bike ride"
        }

    companion object {
        fun byId(id: String): RunMode? = entries.firstOrNull { it.id == id }
    }
}

/** How much time each transition is allowed: a preset, or a custom number of seconds (10 to 180 in steps of 5). */
enum class TransitionMode(val label: String, val seconds: Int?) {
    RACE_LIKE("Race-like", 20),
    GYM_SETUP("Gym setup", 45),
    HOME_SETUP("Home setup", 90),
    CUSTOM("Custom", null);

    companion object {
        /** The preset whose seconds match [sec], else [CUSTOM]. */
        fun of(sec: Int): TransitionMode = entries.firstOrNull { it.seconds == sec } ?: CUSTOM
    }
}

/** Format and loads a planned session asks for ([LaunchHint.sim]). [loadFactor] 1.0 is the race standard. */
data class SimPreset(val format: SimFormat, val loadFactor: Double = 1.0)

/**
 * What a finished simulation records, in [SessionRecord.sim]. [totalSec] is the race clock (pauses excluded, the
 * transitions included); [targetSec] the optional finish-time target the splits were estimated from; [skipped] the
 * 0-based RACE legs that were skipped; [endedEarly] when the session was ended before the last leg.
 * [transitionSec] holds, per transition k (the one after race leg k), the seconds it took (at least 1), or 0 when it
 * was never done; [allowanceSec] is the allowance each had. Every field after [format] has a default so older saved
 * JSON still decodes.
 */
@Serializable
data class SimMeta(
    val format: SimFormat,
    val loadFactor: Double = 1.0,
    val runMode: RunMode = RunMode.TREADMILL,
    val totalSec: Int = 0,
    val targetSec: Int? = null,
    val skipped: List<Int> = emptyList(),
    val endedEarly: Boolean = false,
    val transitionSec: List<Int> = emptyList(),
    val allowanceSec: Int = 0,
) {
    /** Every leg was done: nothing skipped and not ended early. */
    val complete: Boolean get() = skipped.isEmpty() && !endedEarly
}

enum class SimLegKind { RUN, STATION, TRANSITION }

/**
 * One leg of a simulation. [index] is the 0-based position in [SimPlan.legs] (transitions included); [raceIndex] the
 * position among the run and station legs (even = run, odd = station), or -1 for a transition. For a run leg
 * [distanceM] is the leg distance of [runMode] (1000, or 3000 for the bike); for a station it is the standard
 * distance, or [reps] for wall balls. [targetSec] is an ESTIMATE (null when there is no target). A TRANSITION has an
 * [allowanceSec] (its countdown) and a [prep] line for what to do next.
 */
data class SimLeg(
    val index: Int,
    val kind: SimLegKind,
    val station: HyroxStation? = null,
    val distanceM: Int? = null,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val targetSec: Int? = null,
    val tip: String = "",
    val runMode: RunMode? = null,
    val raceIndex: Int = index,
    val allowanceSec: Int? = null,
    val prep: String = "",
) {
    /** The treadmill speed for a 1 km run leg with a target (one decimal), else null. */
    val speedKmh: Double?
        get() = if (kind == SimLegKind.RUN && runMode == RunMode.TREADMILL && targetSec != null && targetSec > 0 && distanceM != null) {
            Math.round(distanceM * 3.6 / targetSec * 10.0) / 10.0
        } else null
}

data class SimPlan(
    val legs: List<SimLeg>,
    val estimatedMinutes: Int,
    val notes: List<String>,
    val format: SimFormat = SimFormat.FULL,
    val loadFactor: Double = 1.0,
    val runMode: RunMode = RunMode.TREADMILL,
    val targetTotalSec: Int? = null,
    /** The allowance of every transition. */
    val transitionSec: Int = HyroxSim.DEFAULT_TRANSITION_SEC,
) {
    /** Only the run and station legs, in race order. */
    val raceLegs: List<SimLeg> get() = legs.filter { it.kind != SimLegKind.TRANSITION }
    val runCount: Int get() = legs.count { it.kind == SimLegKind.RUN }
    val stationCount: Int get() = legs.count { it.kind == SimLegKind.STATION }
    val transitionCount: Int get() = legs.count { it.kind == SimLegKind.TRANSITION }
}

object HyroxSim {
    /** Running's share of the NON-transition time in the first Hyrox study: 51.2 of 86.5 minutes is 59%; 57.5% is used. */
    const val RUN_SHARE = 0.575
    /** About this share of a target total goes to the transitions (the Roxzone). */
    const val TRANSITION_SHARE = 0.05
    /** A typical 1 km leg (51.2 min / 8) in seconds, for estimates when no pace is known. */
    const val RUN_TYPICAL_SEC = 384
    /** Weights step to 2.5 kg when a load factor is applied. */
    const val WEIGHT_STEP = 2.5
    val LOAD_CHOICES = listOf(1.0, 0.9, 0.8, 0.7)

    const val DEFAULT_TRANSITION_SEC = 45
    val TRANSITION_RANGE = 10..180
    const val TRANSITION_STEP = 5

    /** Clamps [sec] into [TRANSITION_RANGE] and rounds it to the nearest [TRANSITION_STEP]. */
    fun snapTransition(sec: Int): Int = (Math.round(sec.toDouble() / TRANSITION_STEP).toInt() * TRANSITION_STEP).coerceIn(TRANSITION_RANGE)

    /**
     * Typical seconds per station in a mid-pack open-division race (about 35 of 86.5 minutes in the study). Only
     * their PROPORTIONS are used, to split a target time; they are estimates.
     */
    private val TYPICAL_STATION_SEC = mapOf(
        HyroxStation.SKIERG to 290, HyroxStation.SLED_PUSH to 165, HyroxStation.SLED_PULL to 230,
        HyroxStation.BURPEE_BROAD_JUMP to 330, HyroxStation.ROW to 290, HyroxStation.FARMERS_CARRY to 115,
        HyroxStation.SANDBAG_LUNGES to 330, HyroxStation.WALL_BALLS to 360,
    )

    fun typicalStationSec(station: HyroxStation): Int = TYPICAL_STATION_SEC.getValue(station)

    /** The run mode to start from for a knee stage: a real run only once the knee is cleared for run intervals. */
    fun defaultRunMode(stage: RunStage, treadmillAvailable: Boolean = true): RunMode =
        if (stage >= RunStage.INTERVALS) (if (treadmillAvailable) RunMode.TREADMILL else RunMode.OUTSIDE) else RunMode.ROW

    /** One plain sentence on the run legs for the setup screen (never a warning, never red). */
    fun runModeNote(mode: RunMode, stage: RunStage): String = when {
        mode.running && stage >= RunStage.RACE_RUNS -> "Run legs are real 1 km runs. Keep them steady (RPE 6-7), and swap a leg for a rower if the knee talks."
        mode.running && stage >= RunStage.INTERVALS ->
            "Your knee is cleared for run intervals, not yet full 1 km runs. Run what feels easy and walk the rest of each leg, or pick a rower."
        mode.running -> "You chose real runs ahead of the knee plan. Go easy, and stop a leg early if the right knee flares; skipping is fine."
        stage >= RunStage.INTERVALS -> "Run legs are ${mode.phrase} by your choice: the same effort, no impact."
        else -> "Running is not ready yet, so the run legs are ${mode.phrase}: same effort, no knee load."
    }

    /** Scales a race weight by [factor]; 1.0 keeps the standard exactly, lighter loads round to 2.5 kg. */
    fun scaleKg(raceKg: Double, factor: Double): Double =
        if (factor >= 0.999) raceKg else max(WEIGHT_STEP, roundTo(raceKg * factor, WEIGHT_STEP))

    /** The pattern of the run and station legs of a format: even legs run, odd legs station, in race order. */
    fun pattern(format: SimFormat): List<Pair<SimLegKind, HyroxStation?>> =
        format.stations.flatMap { listOf(SimLegKind.RUN to null, SimLegKind.STATION to it) }

    /** The same with a TRANSITION between every two race legs (none after the last station). */
    fun fullPattern(format: SimFormat): List<Pair<SimLegKind, HyroxStation?>> {
        val race = pattern(format)
        return race.flatMapIndexed { i, p -> if (i < race.lastIndex) listOf(p, SimLegKind.TRANSITION to null) else listOf(p) }
    }

    /**
     * Builds the legs of [format] at [division]'s race standard (with the user's weight edits from [settings]),
     * scaled by [loadFactor], with a transition of [transitionSec] seconds between every two race legs. Reps and
     * distances stay standard.
     *
     * With [targetTotalSec] every leg gets an ESTIMATED time: about [TRANSITION_SHARE] to the transitions, and of
     * the rest running about [RUN_SHARE] shared equally between the run legs and the stations in proportion to
     * [typicalStationSec]; the targets sum exactly to the total. Without a target the run legs on a treadmill get
     * [paceSecPerKm] (the countdown) and nothing else has a time, only effort guidance in [SimPlan.notes].
     */
    fun build(
        format: SimFormat,
        division: HyroxDivision,
        settings: UserSettings = UserSettings(),
        loadFactor: Double = 1.0,
        runMode: RunMode = RunMode.TREADMILL,
        paceSecPerKm: Int? = null,
        targetTotalSec: Int? = null,
        transitionSec: Int = settings.hyroxTransitionSec,
    ): SimPlan {
        val overrides = settings.hyroxWeightOverrides
        val allowance = snapTransition(transitionSec)
        val pat = pattern(format)
        val target = targetTotalSec?.takeIf { it > 0 }
        val targets: List<Int>? = target?.let { splitTargets(format, it) }
        val runLegDistance = runMode.distanceM
        val pace = paceSecPerKm?.takeIf { it > 0 }
        val runCount = format.stations.size
        val race = pat.mapIndexed { i, (kind, station) ->
            if (kind == SimLegKind.RUN) {
                val runNo = i / 2 + 1
                val paced = if (runMode == RunMode.TREADMILL && pace != null) (pace * runLegDistance / 1000.0).toInt() else null
                SimLeg(i, kind, null, runLegDistance, null, null, paced, runTip(runNo, runCount, runMode), runMode, raceIndex = i)
            } else {
                val st = station!!
                val std = HyroxStandards.of(division, st, overrides)
                SimLeg(i, kind, st, std.distanceM, std.weightKg?.let { scaleKg(it, loadFactor) }, std.reps, null, HyroxGuide.of(st).pacingTip, raceIndex = i)
            }
        }
        val legs = mutableListOf<SimLeg>()
        race.forEachIndexed { i, l ->
            legs += l.copy(index = legs.size, targetSec = targets?.get(legs.size) ?: l.targetSec)
            if (i < race.lastIndex) {
                val next = race[i + 1]
                legs += SimLeg(
                    legs.size, SimLegKind.TRANSITION, null, targetSec = targets?.get(legs.size), runMode = null, raceIndex = -1,
                    allowanceSec = allowance, prep = prepFor(next, pace),
                )
            }
        }
        val transitions = legs.count { it.kind == SimLegKind.TRANSITION }
        val estSec = target ?: (legs.sumOf { l ->
            when (l.kind) {
                SimLegKind.RUN -> l.targetSec ?: RUN_TYPICAL_SEC
                SimLegKind.STATION -> typicalStationSec(l.station!!)
                SimLegKind.TRANSITION -> 0
            }
        } + transitions * allowance)
        val notes = mutableListOf<String>()
        if (targets != null) {
            notes += "Split times are ESTIMATES from the usual proportions of a Hyrox (running is about 55-60% of the time, the transitions about 5%, the stations the rest). " +
                "They are guides for pacing, not a test: quicker or slower is just information."
        } else {
            notes += "No target time, so no split times to chase. Effort guide: make the first run easier than you think, " +
                "keep RPE 6-7 through the first half and hold it. Nothing all-out until the last two legs, and only if you feel good."
        }
        notes += "Aim for RPE 7-8 overall: a simulation is a rehearsal, not a race."
        notes += "The clock never stops, like the real thing: each of the $transitions transitions gets $allowance s to load, fetch or reset (the Roxzone). Going over is fine, it is just counted."
        if (loadFactor < 0.999) notes += "Loads are ${(loadFactor * 100).toInt()}% of the race standard, rounded to 2.5 kg. Reps and distances stay standard."
        return SimPlan(legs, (estSec + 59) / 60, notes, format, loadFactor, runMode, target, allowance)
    }

    /** What to do in a transition before [next]: a short, plain line derived from the leg that follows. */
    fun prepFor(next: SimLeg, paceSecPerKm: Int?): String {
        fun kg(x: Double?) = x?.let { HyroxSim.kg(it) } ?: ""
        if (next.kind == SimLegKind.STATION) {
            return when (next.station!!) {
                HyroxStation.SKIERG -> "Reset the SkiErg monitor and check the damper"
                HyroxStation.ROW -> "Reset the rower monitor"
                HyroxStation.SLED_PUSH -> "Load the sled to ${kg(next.weightKg)} kg"
                HyroxStation.SLED_PULL -> "Hook up the pull rope (sled at ${kg(next.weightKg)} kg)"
                HyroxStation.BURPEE_BROAD_JUMP -> "Find your lane and mark your start line"
                HyroxStation.FARMERS_CARRY -> "Pick up the 2 x ${kg(next.weightKg)} kg handles"
                HyroxStation.SANDBAG_LUNGES -> "Get the ${kg(next.weightKg)} kg sandbag on your shoulders"
                HyroxStation.WALL_BALLS -> "Grab the ${kg(next.weightKg)} kg wall ball and find your line"
            }
        }
        return when (next.runMode ?: RunMode.TREADMILL) {
            RunMode.TREADMILL -> {
                val kmh = next.speedKmh ?: paceSecPerKm?.let { Math.round(3600.0 / it * 10.0) / 10.0 }
                if (kmh != null) "Step onto the treadmill and set ${String.format(Locale.ENGLISH, "%.1f", kmh)} km/h" else "Step onto the treadmill and set your pace"
            }
            RunMode.OUTSIDE -> "Shoes laced, water if you need it"
            RunMode.WALK_INCLINE -> "Step onto the treadmill and set the incline and speed"
            RunMode.ROW -> "Reset the rower monitor"
            RunMode.SKIERG -> "Reset the SkiErg monitor"
            RunMode.BIKE -> "Set the bike resistance and check the saddle"
        }
    }

    /**
     * Estimated seconds for every leg of [format] (transitions included, in [fullPattern] order) so that they sum to
     * exactly [totalSec]: [TRANSITION_SHARE] to the transitions (equal), then [RUN_SHARE] of the rest to the run legs
     * (equal) and the remainder over the stations in proportion to [typicalStationSec]. Largest-remainder rounding.
     */
    fun splitTargets(format: SimFormat, totalSec: Int): List<Int> {
        val pat = fullPattern(format)
        val runs = pat.count { it.first == SimLegKind.RUN }
        val trans = pat.count { it.first == SimLegKind.TRANSITION }
        val stWeight = format.stations.sumOf { typicalStationSec(it) }.toDouble()
        val rest = totalSec * (1.0 - TRANSITION_SHARE)
        val raw = pat.map { (kind, st) ->
            when (kind) {
                SimLegKind.RUN -> rest * RUN_SHARE / runs
                SimLegKind.TRANSITION -> totalSec * TRANSITION_SHARE / trans
                SimLegKind.STATION -> rest * (1.0 - RUN_SHARE) * typicalStationSec(st!!) / stWeight
            }
        }
        val floors = raw.map { Math.floor(it).toInt() }.toMutableList()
        var left = totalSec - floors.sum()
        val order = raw.indices.sortedWith(compareByDescending<Int> { raw[it] - floors[it] }.thenBy { it })
        var k = 0
        while (left > 0) { floors[order[k % order.size]]++; left--; k++ }
        return floors
    }

    private fun runTip(runNo: Int, runs: Int, mode: RunMode): String {
        val unit = if (mode.running) "run" else "leg"
        return when (runNo) {
            1 -> "Easier than you think. Settle your breathing; this $unit sets the day."
            runs -> "Last $unit. If you feel good you can lift the effort a little now."
            else -> "Hold a steady RPE 6-7 and let the heart rate settle before the station."
        }
    }

    /** The race leg after [leg] in [plan]'s list (skipping nothing: the next leg of any kind), or null. */
    private fun nameOf(plan: SimPlan, next: SimLeg): String = when (next.kind) {
        SimLegKind.STATION -> next.station!!.displayName
        else -> {
            val n = next.raceIndex / 2 + 1
            if ((next.runMode ?: plan.runMode).running) "Run $n" else "Run leg $n"
        }
    }

    /**
     * "Run 3 of 8 · 1 km" for a real run; "Run leg 3 of 8 · Rower 1000 m" for a substitute; "Station 3 · Sled Pull";
     * "Transition · next: Wall Balls".
     */
    fun legTitle(plan: SimPlan, leg: SimLeg): String {
        if (leg.kind == SimLegKind.TRANSITION) {
            val next = plan.legs.getOrNull(leg.index + 1) ?: return "Transition"
            return "Transition · next: ${nameOf(plan, next)}"
        }
        if (leg.kind == SimLegKind.STATION) return "Station ${leg.station!!.order} · ${leg.station.displayName}"
        val n = leg.raceIndex / 2 + 1
        val mode = leg.runMode ?: plan.runMode
        return if (mode.running) "Run $n of ${plan.runCount} · ${mode.distanceText}"
        else "Run leg $n of ${plan.runCount} · ${mode.label} ${if (mode.distanceM >= 1000 && mode != RunMode.BIKE) "1000 m" else mode.distanceText}"
    }

    /** The load and distance of a station leg in one line: "50 m · 152 kg", "100 reps · 6 kg", "200 m · 24 kg each". */
    fun legLoadText(leg: SimLeg): String {
        val st = leg.station ?: return leg.runMode?.let { "${it.label} ${it.distanceText}" } ?: ""
        val parts = mutableListOf<String>()
        leg.distanceM?.let { parts += "$it m" }
        leg.reps?.let { parts += "$it reps" }
        leg.weightKg?.let { parts += kg(it) + " kg" + if (st == HyroxStation.FARMERS_CARRY) " each" else "" }
        return parts.joinToString(" · ")
    }

    /**
     * The ladder level to record for a station leg done with [weightKg]: the highest level whose weight the log
     * reaches (5, the race standard, for stations without a load). Lets the existing progression read a sim leg.
     */
    fun levelFor(station: HyroxStation, division: HyroxDivision, overrides: Map<String, Double>, weightKg: Double?): Int {
        val ladder = HyroxLadder.of(division, station, overrides)
        if (weightKg == null || !station.weighted) return HyroxLadder.RACE_LEVEL
        return ladder.lastOrNull { it.weightKg != null && weightKg >= it.weightKg - 0.001 }?.level?.coerceAtMost(HyroxLadder.RACE_LEVEL) ?: 1
    }

    /**
     * Matches a record's logs to the RACE legs of [meta]'s format. The result has one entry per race leg: the log, or
     * null for a leg that was skipped or never reached. Skipped legs listed in [SimMeta.skipped] are never matched; the
     * rest match in order (a run log to a run leg, a station log to its own station).
     */
    fun assign(meta: SimMeta, logs: List<StationLog>): List<StationLog?> {
        val pat = pattern(meta.format)
        var next = 0
        return pat.mapIndexed { i, (kind, station) ->
            val log = logs.getOrNull(next)
            val matches = log != null && i !in meta.skipped &&
                (if (kind == SimLegKind.RUN) HyroxRuns.isRun(log) else log.stationId == station!!.id)
            if (matches) { next++; log } else null
        }
    }

    internal fun kg(x: Double) = if (x % 1.0 == 0.0) x.toInt().toString() else x.toString()

    fun clock(sec: Int): String = "${sec / 60}:${"%02d".format(sec % 60)}"
}

/** One run or station leg of a [SimResult]; [index] is the RACE-leg number (0-based). */
data class SimResultLeg(
    val index: Int,
    val kind: SimLegKind,
    val station: HyroxStation?,
    /** Seconds the leg took; null when it was skipped or never reached. */
    val seconds: Int?,
    val targetSec: Int?,
    val rpe: Int?,
    val log: StationLog?,
) {
    val done: Boolean get() = seconds != null
}

/** How one leg compares with the previous simulation of the same format, in kind words. */
data class SimLegDelta(val index: Int, val deltaSec: Int, val text: String)

data class SimComparison(
    val legs: List<SimLegDelta>,
    /** Over the run and station legs done in BOTH simulations; negative is quicker. Null when no leg was done in both. */
    val totalDeltaSec: Int?,
    val totalText: String,
    val legsCompared: Int,
    val shareText: String,
    /** Over the transitions done in both (negative is quicker), or null. */
    val transitionDeltaSec: Int? = null,
    /** "Transitions 0:40 quicker" in kind words, or empty when there is nothing to compare. */
    val transitionText: String = "",
)

/** A simulation record read as legs, splits and shares. Build it with [from]. */
data class SimResult(
    val meta: SimMeta,
    val legs: List<SimResultLeg>,
    val totalSec: Int,
    /** Seconds of transition k (after race leg k), or null when it was never done. */
    val transitions: List<Int?> = emptyList(),
) {
    val format: SimFormat get() = meta.format
    val runSec: Int get() = legs.filter { it.kind == SimLegKind.RUN }.sumOf { it.seconds ?: 0 }
    val stationSec: Int get() = legs.filter { it.kind == SimLegKind.STATION }.sumOf { it.seconds ?: 0 }

    /** Total transition (Roxzone) time. */
    val roxSec: Int get() = transitions.sumOf { it ?: 0 }
    val roxCount: Int get() = transitions.count { it != null }
    val legsDone: Int get() = legs.count { it.done }
    private val loggedSec: Int get() = runSec + stationSec + roxSec

    /** Running's share of the logged time (legs and transitions), 0..1, or null with nothing logged. */
    val runShare: Double? get() = loggedSec.takeIf { it > 0 }?.let { runSec.toDouble() / it }
    val roxShare: Double? get() = loggedSec.takeIf { it > 0 }?.let { roxSec.toDouble() / it }
    val stationShare: Double? get() = loggedSec.takeIf { it > 0 }?.let { stationSec.toDouble() / it }

    /** "Running 58% · stations 42%", with "· Roxzone 8%" when there are transitions, or null. */
    val shareText: String?
        get() {
            val r = runShare ?: return null
            val run = Math.round(r * 100).toInt()
            val rox = Math.round((roxShare ?: 0.0) * 100).toInt()
            val st = 100 - run - rox
            return if (roxCount > 0) "Running $run% · stations $st% · Roxzone $rox%" else "Running $run% · stations $st%"
        }

    /**
     * One gentle pointer: the station that ran longest against its estimate (or, with no target, against a typical
     * race), with its pacing tip. A tip for training, never a criticism; null when nothing stood out.
     */
    fun workOn(): String? {
        val stations = legs.filter { it.kind == SimLegKind.STATION && it.seconds != null && it.station != null }
        if (stations.isEmpty()) return null
        val ratios: List<Pair<SimResultLeg, Double>> = if (stations.all { it.targetSec != null && it.targetSec > 0 }) {
            stations.map { it to it.seconds!!.toDouble() / it.targetSec!! }
        } else {
            val total = stations.sumOf { it.seconds!! }.toDouble()
            val typical = stations.sumOf { HyroxSim.typicalStationSec(it.station!!) }.toDouble()
            if (stations.size < 2 || total <= 0) return null
            stations.map { it to (it.seconds!! / total) / (HyroxSim.typicalStationSec(it.station!!) / typical) }
        }
        val (leg, ratio) = ratios.maxByOrNull { it.second } ?: return null
        if (ratio < WORK_ON_RATIO) return null
        val st = leg.station!!
        return "${st.displayName} took the biggest bite compared with its estimate. ${HyroxGuide.of(st).pacingTip} " +
            "It is a pointer for training, not a criticism."
    }

    companion object {
        /** Station took this much longer than its estimate (ratio) before it is mentioned. */
        const val WORK_ON_RATIO = 1.1

        /** Reads [record] as a simulation, with each leg's estimated target rebuilt from the saved meta; null for a non-sim. */
        fun from(record: SessionRecord, division: HyroxDivision = HyroxDivision.MEN_OPEN, settings: UserSettings = UserSettings()): SimResult? {
            val meta = record.sim ?: return null
            val allowance = meta.allowanceSec.takeIf { it > 0 } ?: settings.hyroxTransitionSec
            val plan = HyroxSim.build(meta.format, division, settings, meta.loadFactor, meta.runMode, targetTotalSec = meta.targetSec, transitionSec = allowance)
            val logs = HyroxSim.assign(meta, record.stations)
            val legs = plan.raceLegs.map { leg ->
                val log = logs.getOrNull(leg.raceIndex)
                SimResultLeg(leg.raceIndex, leg.kind, leg.station, log?.seconds, leg.targetSec, log?.rpe, log)
            }
            val transitions = (0 until (meta.format.legCount - 1)).map { k -> meta.transitionSec.getOrNull(k)?.takeIf { it > 0 } }
            val total = meta.totalSec.takeIf { it > 0 } ?: (legs.sumOf { it.seconds ?: 0 } + transitions.sumOf { it ?: 0 })
            return SimResult(meta, legs, total, transitions)
        }

        /** Every simulation in [records], oldest first. */
        fun history(records: List<SessionRecord>, division: HyroxDivision = HyroxDivision.MEN_OPEN, settings: UserSettings = UserSettings()): List<Pair<SessionRecord, SimResult>> =
            records.filter { it.sim != null }.sortedWith(compareBy({ it.date }, { it.id }))
                .mapNotNull { r -> from(r, division, settings)?.let { r to it } }

        /** The latest simulation of [current]'s format in [records] before it (same date, earlier id included), or null. */
        fun previousOf(records: List<SessionRecord>, current: SessionRecord): SessionRecord? {
            val fmt = current.sim?.format ?: return null
            return records.filter { it.sim?.format == fmt && it.id != current.id && (it.date < current.date || (it.date == current.date && it.id < current.id)) }
                .maxWithOrNull(compareBy({ it.date }, { it.id }))
        }

        /** "2:10 quicker", "0:25 slower, that is fine" or "the same". */
        fun deltaText(deltaSec: Int, withReassurance: Boolean = true): String = when {
            deltaSec == 0 -> "the same"
            deltaSec < 0 -> "${HyroxSim.clock(-deltaSec)} quicker"
            else -> "${HyroxSim.clock(deltaSec)} slower" + if (withReassurance) ", that is fine" else ""
        }

        /**
         * Per-leg and total deltas of [current] against [previous] (the previous simulation of the same format; null
         * for a first one). Only legs done in both are compared, so a skipped leg never distorts the total. The
         * transitions are compared on their own ([SimComparison.transitionDeltaSec]).
         */
        fun compare(current: SimResult, previous: SimResult?): SimComparison {
            val share = shareLine(current, previous)
            if (previous == null || previous.format != current.format) {
                return SimComparison(emptyList(), null, "First simulation in this format: this is your starting point.", 0, share)
            }
            val pairs = current.transitions.indices.mapNotNull { k ->
                val c = current.transitions[k]; val p = previous.transitions.getOrNull(k)
                if (c != null && p != null) c - p else null
            }
            val tDelta = pairs.takeIf { it.isNotEmpty() }?.sum()
            val tText = tDelta?.let { "Transitions: ${deltaText(it)}" } ?: ""
            val deltas = current.legs.mapNotNull { c ->
                val p = previous.legs.getOrNull(c.index)
                if (c.seconds != null && p?.seconds != null) {
                    val d = c.seconds - p.seconds
                    SimLegDelta(c.index, d, deltaText(d, withReassurance = false))
                } else null
            }
            if (deltas.isEmpty()) return SimComparison(emptyList(), null, "No leg was done in both, so there is nothing to compare yet.", 0, share, tDelta, tText)
            val total = deltas.sumOf { it.deltaSec }
            val all = deltas.size == current.legs.size
            val scope = if (all) "" else " on the ${deltas.size} legs you did both times"
            val text = when {
                total == 0 -> "The same total as last time$scope."
                total < 0 -> "${HyroxSim.clock(-total)} quicker than last time$scope."
                else -> "${HyroxSim.clock(total)} slower than last time$scope, and that is fine: a rehearsal is for learning."
            }
            return SimComparison(deltas, total, text, deltas.size, share, tDelta, tText)
        }

        private fun shareLine(current: SimResult, previous: SimResult?): String {
            val now = current.shareText ?: return ""
            val before = previous?.takeIf { it.format == current.format }?.runShare ?: return now
            val diff = Math.round(((current.runShare ?: return now) - before) * 100).toInt()
            return when {
                diff == 0 -> "$now, the same balance as last time."
                diff > 0 -> "$now, a little more of the day on the run legs than last time."
                else -> "$now, a little less of the day on the run legs than last time."
            }
        }
    }
}
