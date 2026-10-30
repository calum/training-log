package com.calum.traininglog.core

import kotlin.math.abs
import kotlin.random.Random

/** How demanding the mat routine may be. Anything harder than [STANDARD] is opt-in. */
enum class CoreLevel { BEGINNER, STANDARD, ADVANCED }

enum class CoreKind { WARMUP, WORK, REST, COOLDOWN }

enum class CoreSide { LEFT, RIGHT }

/** Movement pattern, used to keep a block varied (anti-extension, anti-rotation, lateral hip, ...). */
enum class CorePattern { ANTI_EXTENSION, ANTI_ROTATION, LATERAL, HIP, FLEXION, EXTENSION }

/**
 * One library exercise. [reps] is a rep target for the work window (null = an isometric hold for the whole
 * window). [kneeLoad] marks moves that put weight through or bend a loaded knee (running-like patterns); they
 * are dropped when the routine is built with `kneeSensitive = true`.
 * Nothing in here jumps or lands: impact moves are deliberately not in the library.
 */
data class CoreExercise(
    val id: String,
    val name: String,
    val cue: String,
    val unilateral: Boolean = false,
    val reps: Int? = null,
    val kneeLoad: Boolean = false,
    val level: CoreLevel = CoreLevel.BEGINNER,
    val pattern: CorePattern = CorePattern.ANTI_EXTENSION,
)

/** One timed step of the flat routine. [nextName] is the label of the next exercise or stretch, null at the end. */
data class CoreStep(
    val name: String,
    val cue: String,
    val seconds: Int,
    val kind: CoreKind,
    val side: CoreSide? = null,
    val nextName: String? = null,
    val exerciseId: String? = null,
    val reps: Int? = null,
)

/** A block of the work section: a circuit of [exercises] done [rounds] times. */
data class CoreBlock(val exercises: List<CoreExercise>, val rounds: Int, val workSec: Int, val restSec: Int)

data class CoreSession(
    val minutes: Int,
    val level: CoreLevel,
    val kneeSensitive: Boolean,
    val warmup: List<CoreStep>,
    val blocks: List<CoreBlock>,
    val cooldown: List<CoreStep>,
    val steps: List<CoreStep>,
) {
    val totalSec: Int get() = steps.sumOf { it.seconds }

    /** Ids of the work exercises in the order they first appear. */
    val exerciseIds: List<String> get() = blocks.flatMap { b -> b.exercises.map { it.id } }
}

object CoreLibrary {
    private fun ex(
        id: String, name: String, cue: String, p: CorePattern, unilateral: Boolean = false, reps: Int? = null,
        knee: Boolean = false, level: CoreLevel = CoreLevel.BEGINNER,
    ) = CoreExercise(id, name, cue, unilateral, reps, knee, level, p)

    val all: List<CoreExercise> = listOf(
        ex("dead_bug", "Dead bug", "Low back stays flat on the mat. Lower opposite arm and leg slowly, exhale as you reach.", CorePattern.ANTI_EXTENSION, reps = 10),
        ex("dead_bug_slides", "Heel-slide dead bug", "Arms to the ceiling, ribs down. Slide one heel out along the floor and back, alternate.", CorePattern.ANTI_EXTENSION, reps = 12),
        ex("tabletop_taps", "Tabletop toe taps", "Hips and knees at 90 degrees. Tap one toe down, keep the back flat, alternate.", CorePattern.ANTI_EXTENSION, reps = 12),
        ex("front_plank", "Front plank", "Forearms down, one straight line from head to heels. Squeeze glutes, breathe.", CorePattern.ANTI_EXTENSION),
        ex("plank_saw", "Plank saw", "In a forearm plank, rock slowly forward and back a few centimetres. Keep the hips level.", CorePattern.ANTI_EXTENSION, level = CoreLevel.STANDARD),
        ex("hollow_tuck", "Tucked hollow hold", "Back pressed down, shoulders and feet lifted, knees bent in. Straighten the legs when it gets easy.", CorePattern.ANTI_EXTENSION),
        ex("hollow_hold", "Hollow-body hold", "Low back glued to the mat, arms overhead, legs long and low. Stop if the back arches.", CorePattern.ANTI_EXTENSION, level = CoreLevel.STANDARD),
        ex("leg_lowers", "Straight-leg lowers", "Legs to the ceiling, lower slowly until the back wants to arch, then lift.", CorePattern.ANTI_EXTENSION, reps = 8, level = CoreLevel.STANDARD),
        ex("bird_dog", "Bird dog", "Hands under shoulders, hips square. Reach opposite arm and leg long, pause, switch. Pad the knees.", CorePattern.ANTI_ROTATION, reps = 10),
        ex("bear_hold", "Bear hold", "Hands and toes down, knees hover a hand-width off the floor. Back flat, breathe.", CorePattern.ANTI_ROTATION),
        ex("plank_taps", "Plank shoulder taps", "Plank on hands, feet wide. Tap each shoulder without letting the hips rock.", CorePattern.ANTI_ROTATION, reps = 16),
        ex("anti_rot_press", "Anti-rotation press (no band)", "Stand tall, hands at your chest. Press out and hold for a second, resisting any twist. Brace as if holding a cable.", CorePattern.ANTI_ROTATION, reps = 10),
        ex("side_plank", "Side plank", "Elbow under shoulder, hips high, body in a line. Drop the bottom knee to the mat if needed.", CorePattern.LATERAL, unilateral = true),
        ex("side_plank_lift", "Side plank with leg lift", "Hold the side plank and lift the top leg to hip height, slow and level.", CorePattern.LATERAL, unilateral = true, reps = 10, level = CoreLevel.ADVANCED),
        ex("copenhagen_short", "Short Copenhagen plank", "Side plank with the top knee on a sofa or low chair, bottom leg tucked. The inner thigh does the work.", CorePattern.LATERAL, unilateral = true, level = CoreLevel.ADVANCED),
        ex("clamshell", "Clamshell", "Side-lying, knees bent, heels together. Open the top knee without rolling the pelvis back. Great for hips and ITB.", CorePattern.LATERAL, unilateral = true, reps = 15),
        ex("side_leg_raise", "Side-lying leg raise", "Bottom leg bent for balance, top leg straight and slightly back. Lift to hip height, slowly.", CorePattern.LATERAL, unilateral = true, reps = 12),
        ex("fire_hydrant", "Fire hydrant", "On all fours, lift one bent knee out to the side without twisting the trunk. Pad the knees.", CorePattern.LATERAL, unilateral = true, reps = 12),
        ex("glute_bridge", "Glute bridge", "Feet flat, hip-width. Push through the heels, squeeze at the top, ribs down. Lower slowly.", CorePattern.HIP, reps = 12),
        ex("march_bridge", "Marching bridge", "Hold the bridge up and lift one foot at a time. Hips stay level.", CorePattern.HIP, reps = 12),
        ex("single_leg_bridge", "Single-leg bridge", "One foot planted, the other leg long. Drive the hips up level, pause, lower slowly.", CorePattern.HIP, unilateral = true, reps = 10, level = CoreLevel.STANDARD),
        ex("reverse_crunch", "Reverse crunch", "Knees over hips. Curl the pelvis off the mat with your abs, not momentum. Lower slowly.", CorePattern.FLEXION, reps = 12),
        ex("bicycle_slow", "Slow bicycle crunch", "Hands light behind the head. Rotate the ribcage towards the opposite knee, one slow side at a time.", CorePattern.FLEXION, reps = 16),
        ex("heel_taps", "Heel taps", "Crunch slightly up and reach one hand to the same-side heel, then the other.", CorePattern.FLEXION, reps = 20),
        ex("russian_twist", "Seated twist with feet down", "Lean back until the abs work, feet on the floor. Turn the whole ribcage side to side.", CorePattern.FLEXION, reps = 16),
        ex("superman", "Superman", "Face down, lift arms, chest and legs a little. Long neck, squeeze the glutes, then lower.", CorePattern.EXTENSION, reps = 10),
        ex("prone_ytw", "Prone Y-T-W", "Face down, chest slightly lifted. Sweep the arms into a Y, a T, then a W. Shoulder blades down.", CorePattern.EXTENSION, reps = 9),
        ex("cobra_hold", "Cobra hold", "Face down, hands by the ribs. Lift the chest using your back, not your hands. Hips stay heavy.", CorePattern.EXTENSION),
        // Knee-loaded moves: only used when the knee is fine.
        ex("mountain_climber", "Slow mountain climbers", "Plank on hands, drive one knee under the chest and switch. Controlled, no hopping.", CorePattern.ANTI_ROTATION, reps = 20, knee = true, level = CoreLevel.STANDARD),
        ex("plank_knee_tuck", "Plank knee-to-elbow", "From a hand plank, bring one knee to the same elbow, return, alternate. Hips stay low.", CorePattern.ANTI_ROTATION, reps = 14, knee = true, level = CoreLevel.STANDARD),
        ex("spiderman_plank", "Spider-Man plank", "Plank on hands, bring a knee wide to the elbow and return. Do not let the hips sag.", CorePattern.ANTI_ROTATION, reps = 12, knee = true, level = CoreLevel.ADVANCED),
    )

    private val byId = all.associateBy { it.id }
    private val byName = all.associateBy { it.name }

    fun get(id: String): CoreExercise? = byId[id]

    /** The work exercises named in a "Core: a, b, c" session note, as ids. Unknown names are ignored. */
    fun idsFromNote(note: String?): List<String> {
        val t = note?.trim() ?: return emptyList()
        if (!t.startsWith("Core:")) return emptyList()
        return t.removePrefix("Core:").split(",").mapNotNull { byName[it.trim().substringBefore(" (felt")]?.id }
    }

    internal val warmupMoves = listOf(
        Triple("cat_cow", "Cat-cow", "On all fours, round the back up, then drop the belly and look forward. Slow, with the breath."),
        Triple("hip_circles", "Hip circles", "On all fours, draw big slow circles with one knee, then the other. Reverse halfway."),
    )

    internal val cooldownMoves = listOf(
        Triple("hip_flexor", "Hip flexor stretch", "Half-kneeling on a pad, or a standing lunge. Tuck the pelvis under and lean forward gently."),
        Triple("figure4", "Figure-4 stretch", "On your back, ankle over the opposite knee, pull the thigh in. Eases glute and outer hip without loading the ITB."),
    )
}

object CoreRoutine {
    const val MIN_MINUTES = 10
    const val MAX_MINUTES = 30
    private const val TARGET_CYCLE = 60

    /** Session note as saved with the record, e.g. "Core: Dead bug, Bird dog". */
    fun note(names: List<String>, felt: String? = null): String =
        "Core: " + names.joinToString(", ") + (felt?.let { " (felt $it)" } ?: "")

    /**
     * Builds a mat session of [minutes] (10..30): warm-up, three blocks of work/rest intervals, cool-down.
     * [last] holds the exercise ids of the previous session; none of those are used again while the library
     * allows it. [kneeSensitive] drops every `kneeLoad` move. Same [seed] = same session.
     */
    fun build(
        minutes: Int,
        level: CoreLevel = CoreLevel.STANDARD,
        last: List<String>? = null,
        seed: Long = 0,
        kneeSensitive: Boolean = false,
    ): CoreSession {
        val mins = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
        val total = mins * 60
        val warmEach = if (mins < 15) 30 else 45
        val coolEach = if (mins < 15) 30 else 40
        val workTotal = total - warmEach * CoreLibrary.warmupMoves.size - coolEach * CoreLibrary.cooldownMoves.size * 2

        val pool = CoreLibrary.all.filter { (!kneeSensitive || !it.kneeLoad) && it.level <= level }
        val avoid = last.orEmpty().toSet()

        // Try 2..4 exercises per block and 1..4 rounds; keep the plan whose work+rest cycle is nearest 60 s.
        class Plan(val blocks: List<List<CoreExercise>>, val rounds: Int, val cycle: Int, val score: Int)
        var best: Plan? = null
        for (perBlock in 2..4) {
            val blocks = pickBlocks(pool, perBlock, avoid, Random(seed * 31 + perBlock))
            val all = blocks.flatten()
            val stepsPerRound: Int = all.size + all.count { it.unilateral }
            for (rounds in 1..4) {
                val cycle = workTotal / (stepsPerRound * rounds)
                val outside = if (cycle < 40) 40 - cycle else if (cycle > 90) cycle - 90 else 0
                val score = outside * 100 + abs(cycle - TARGET_CYCLE) * 2 + (4 - perBlock)
                if (best == null || score < best.score) best = Plan(blocks, rounds, cycle, score)
            }
        }
        val plan = best!!
        val work = maxOf(20, (plan.cycle * 2 / 3 + 2) / 5 * 5)
        val rest = maxOf(5, plan.cycle - work)

        val blocks = plan.blocks.map { CoreBlock(it, plan.rounds, work, rest) }
        val raw = mutableListOf<CoreStep>()
        CoreLibrary.warmupMoves.forEach { (id, n, cue) -> raw += CoreStep(n, cue, warmEach, CoreKind.WARMUP, exerciseId = id) }
        blocks.forEach { b ->
            repeat(b.rounds) {
                b.exercises.forEach { e ->
                    val sides: List<CoreSide?> = if (e.unilateral) listOf(CoreSide.LEFT, CoreSide.RIGHT) else listOf(null)
                    sides.forEach { s ->
                        raw += CoreStep(e.name, e.cue, b.workSec, CoreKind.WORK, s, exerciseId = e.id, reps = e.reps)
                        raw += CoreStep("Rest", "Shake out and breathe.", b.restSec, CoreKind.REST)
                    }
                }
            }
        }
        // The last rest before the cool-down is pointless: drop it (its time is handed to the final stretch below).
        raw.removeAt(raw.indexOfLast { it.kind == CoreKind.REST })
        CoreLibrary.cooldownMoves.forEach { (id, n, cue) ->
            listOf(CoreSide.LEFT, CoreSide.RIGHT).forEach { s -> raw += CoreStep(n, cue, coolEach, CoreKind.COOLDOWN, s, exerciseId = id) }
        }
        val steps = raw.mapIndexed { i, s -> s.copy(nextName = raw.drop(i + 1).firstOrNull { it.kind != CoreKind.REST }?.let(::label)) }
            .toMutableList()
        // Leftover seconds (integer division, the dropped rest) go to the final stretch so the total is exact.
        steps[steps.lastIndex] = steps.last().copy(seconds = steps.last().seconds + total - steps.sumOf { it.seconds })

        return CoreSession(
            mins, level, kneeSensitive,
            warmup = steps.filter { it.kind == CoreKind.WARMUP },
            blocks = blocks,
            cooldown = steps.filter { it.kind == CoreKind.COOLDOWN },
            steps = steps,
        )
    }

    /** Display label of a step, with its side. */
    fun label(s: CoreStep) = when (s.side) {
        CoreSide.LEFT -> "${s.name} (left)"
        CoreSide.RIGHT -> "${s.name} (right)"
        null -> s.name
    }

    /** Picks [perBlock] exercises for each of three blocks: none used twice, none from [avoid] if possible, varied patterns. */
    private fun pickBlocks(pool: List<CoreExercise>, perBlock: Int, avoid: Set<String>, rnd: Random): List<List<CoreExercise>> {
        val used = mutableSetOf<String>()
        return List(3) {
            val block = mutableListOf<CoreExercise>()
            repeat(perBlock) {
                val free = pool.filter { it.id !in used }
                val fresh = free.filter { it.id !in avoid }.ifEmpty { free }
                val varied = fresh.filter { c -> block.none { it.pattern == c.pattern } }.ifEmpty { fresh }
                val pick = varied.random(rnd)
                used += pick.id
                block += pick
            }
            block.toList()
        }
    }
}

enum class CoreEvent { PRE_WARNING, CHANGE, DONE }

/**
 * Pure run state of a guided session: which step, how far in, paused or not. Advance it with real elapsed
 * time ([advance]); it returns the new state and the one cue to play (DONE beats CHANGE beats PRE_WARNING).
 */
data class CoreRun(
    val steps: List<CoreStep>,
    val index: Int = 0,
    val elapsedMs: Long = 0,
    val paused: Boolean = false,
    /** Paused automatically because the app left the foreground. */
    val pausedAway: Boolean = false,
    val totalElapsedMs: Long = 0,
    /** Work steps that were skipped rather than done. */
    val skipped: Set<Int> = emptySet(),
) {
    val finished: Boolean get() = index >= steps.size
    val current: CoreStep? get() = steps.getOrNull(index)
    val secondsLeft: Int get() = current?.let { (((it.seconds * 1000L) - elapsedMs + 999) / 1000).toInt().coerceAtLeast(0) } ?: 0
    val totalSec: Int get() = steps.sumOf { it.seconds }
    val completedSec: Int get() = (totalElapsedMs / 1000).toInt()
    val fraction: Float get() = if (totalSec == 0) 0f else (totalElapsedMs / 1000f / totalSec).coerceIn(0f, 1f)

    /** Names of the work exercises actually done (not skipped, current one counts after 10 s), no repeats. */
    fun doneWorkNames(): List<String> = steps.indices
        .filter { i -> steps[i].kind == CoreKind.WORK && i !in skipped && (i < index || (i == index && elapsedMs >= 10_000)) }
        .map { steps[it].name }.distinct()

    fun pause(away: Boolean = false) = if (finished) this else copy(paused = true, pausedAway = away)
    fun resume() = copy(paused = false, pausedAway = false)
    fun clearAway() = copy(pausedAway = false)

    fun advance(deltaMs: Long): Pair<CoreRun, CoreEvent?> {
        if (paused || finished || deltaMs <= 0) return this to null
        var run = this
        var left = deltaMs
        var event: CoreEvent? = null
        while (left > 0 && !run.finished) {
            val step = run.steps[run.index]
            val stepMs = step.seconds * 1000L
            val toEnd = stepMs - run.elapsedMs
            if (left < toEnd) {
                val warnAt = stepMs - PRE_WARNING_MS
                val after = run.elapsedMs + left
                if (step.kind == CoreKind.WORK && step.seconds >= 8 && run.elapsedMs < warnAt && after >= warnAt && event == null) event = CoreEvent.PRE_WARNING
                run = run.copy(elapsedMs = after, totalElapsedMs = run.totalElapsedMs + left)
                left = 0
            } else {
                left -= toEnd
                run = run.copy(index = run.index + 1, elapsedMs = 0, totalElapsedMs = run.totalElapsedMs + toEnd)
                event = if (run.finished) CoreEvent.DONE else CoreEvent.CHANGE
            }
        }
        return run to event
    }

    /** Jump to the start of the next step. */
    fun skip(): Pair<CoreRun, CoreEvent?> {
        if (finished) return this to null
        val next = copy(index = index + 1, elapsedMs = 0, skipped = skipped + index)
        return next to if (next.finished) CoreEvent.DONE else CoreEvent.CHANGE
    }

    companion object {
        const val PRE_WARNING_MS = 3000L
    }
}
