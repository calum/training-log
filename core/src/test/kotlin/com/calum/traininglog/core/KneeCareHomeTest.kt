package com.calum.traininglog.core

import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Knee care at home: the kit, the gear list, the no-step control block, and the calf ladder with its feedback rules. */
class KneeCareHomeTest {
    private val today = LocalDate.of(2026, 11, 25)
    private var n = 0
    private val calum = KneeCareKit() // 6 kg kettlebell, no step, no band, chair, mat
    private val full = KneeCareKit(kettlebellKg = 12.0, hasStep = true, hasBand = true)
    private val bare = KneeCareKit(kettlebellKg = null, hasStep = false, hasBand = false, hasChair = false, hasMat = false)

    private fun set(level: Int, reps: Int, rir: Int?, target: Int = 12, kg: Double = 6.0) = LoggedSet(kg, reps, target, rir, level)

    private fun record(date: LocalDate, vararg sets: LoggedSet) =
        KneeCareRecord.build("h${n++}", date, 15, 900, listOf("Hip circles", "Single-leg Romanian deadlift"), sets.toList())!!

    // ------------------------------------------------------------------------------------------------------ kit

    @Test
    fun kitDefaultsMatchTheUserAndOldJsonDecodes() {
        val k = KneeCareKit()
        assertEquals(6.0, k.kettlebellKg)
        assertFalse(k.hasStep); assertFalse(k.hasBand); assertTrue(k.hasChair); assertTrue(k.hasMat)
        // Settings saved before the kit existed still decode, with the defaults.
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = json.decodeFromString<UserSettings>("""{"gymTarget":3}""")
        assertEquals(KneeCareKit(), old.kneeKit)
        assertEquals(3, old.gymTarget)
        // Partial kit JSON keeps the other defaults; a round trip keeps a "no kettlebell" kit.
        assertEquals(KneeCareKit(hasStep = true), json.decodeFromString<UserSettings>("""{"kneeKit":{"hasStep":true}}""").kneeKit)
        val none = UserSettings(kneeKit = KneeCareKit(kettlebellKg = null))
        assertNull(json.decodeFromString<UserSettings>(json.encodeToString(UserSettings.serializer(), none)).kneeKit.kettlebellKg)
        // Old logged sets without a level decode too.
        assertNull(json.decodeFromString<LoggedSet>("""{"weightKg":20.0,"reps":8,"targetReps":8}""").level)
    }

    @Test
    fun kitIsSanitisedAndTheKettlebellStepsInWholeKilos() {
        assertEquals(6.0, KneeCareKit(kettlebellKg = 5.6).sanitized().kettlebellKg)
        assertNull(KneeCareKit(kettlebellKg = 0.0).sanitized().kettlebellKg)
        assertNull(KneeCareKit(kettlebellKg = -3.0).sanitized().kettlebellKg)
        assertEquals(KneeCareKit.MAX_KG, KneeCareKit(kettlebellKg = 500.0).sanitized().kettlebellKg)
        assertEquals(7.0, calum.withKettlebellStep(1).kettlebellKg)
        assertEquals(5.0, calum.withKettlebellStep(-1).kettlebellKg)
        assertNull(KneeCareKit(kettlebellKg = 1.0).withKettlebellStep(-1).kettlebellKg, "none is allowed")
        assertEquals(1.0, KneeCareKit(kettlebellKg = null).withKettlebellStep(1).kettlebellKg)
        assertEquals(KneeCareKit(kettlebellKg = 40.0), UserSettings(kneeKit = KneeCareKit(kettlebellKg = 99.0)).sanitized().kneeKit)
    }

    // ------------------------------------------------------------------------------------------- gear and the routine

    @Test
    fun noRoutineEverUsesGearHeDoesNotHave() {
        val kits = listOf(calum, full, bare, KneeCareKit(hasStep = true), KneeCareKit(hasBand = true, hasChair = false), KneeCareKit(kettlebellKg = null))
        for (kit in kits) for (seed in 0L..5L) for (knee in listOf(null, 2, 5)) for (level in 1..6) for (m in listOf(12, 15, 20)) {
            val s = KneeCareRoutine.build(m, seed = seed, kneePain = knee, kit = kit, calfLevel = level)
            val ids = s.steps.mapNotNull { it.exerciseId }.toSet()
            val why = "kit=$kit seed=$seed knee=$knee"
            if (!kit.hasStep) assertTrue("kc_step_down" !in ids && "kc_hip_hike" !in ids, "step needed: $why")
            if (!kit.hasBand) assertTrue("kc_lateral_walk" !in ids, "band needed: $why")
            if (!kit.hasChair) assertTrue("kc_pistol" !in ids, "chair needed: $why")
            if (!kit.hasKettlebell) assertTrue(s.gear.none { it.name.startsWith("Kettlebell") }, "kettlebell listed: $why")
            // Every piece of kit on the list is something he owns.
            s.gear.forEach { g ->
                if (g.name.startsWith("Low step")) assertTrue(kit.hasStep, why)
                if (g.name.contains("band")) assertTrue(kit.hasBand, why)
                if (g.name == "Chair") assertTrue(kit.hasChair, why)
                if (g.name == "Mat") assertTrue(kit.hasMat, why)
            }
        }
    }

    @Test
    fun withoutAStepTheSingleLegRdlAndChairSquatReplaceTheStepDown() {
        val seen = mutableSetOf<String>()
        for (seed in 0L..5L) {
            val s = KneeCareRoutine.build(15, seed = seed, kit = calum, calfLevel = 3)
            val ids = s.steps.mapNotNull { it.exerciseId }
            assertTrue("kc_step_down" !in ids)
            seen += ids.filter { it == "kc_sl_rdl" || it == "kc_pistol" }
            // A knee control move is in every routine, timed, with both sides.
            val control = s.steps.filter { it.kind == CoreKind.WORK && (it.exerciseId == "kc_sl_rdl" || it.exerciseId == "kc_pistol") }
            assertTrue(control.isNotEmpty(), "seed $seed has a knee control move")
            assertEquals(control.count { it.side == CoreSide.LEFT }, control.count { it.side == CoreSide.RIGHT })
            assertTrue(control.all { it.seconds >= 50 }, "a block of slow reps needs time: ${control.map { it.seconds }}")
        }
        assertEquals(setOf("kc_sl_rdl", "kc_pistol"), seen, "both appear across the sessions")
        // With a step the step-down stays an option.
        assertTrue((0L..5L).any { v -> KneeCareRoutine.build(15, seed = v, kit = KneeCareKit(hasStep = true)).steps.any { it.exerciseId == "kc_step_down" } })
    }

    @Test
    fun controlMovesStartChallengingAndProgressByTempoLoadAndReps() {
        val rdl = KneeCareLibrary.slRdl
        val pistol = KneeCareLibrary.pistol
        // The single-leg RDL starts holding the kettlebell; the pistol starts bodyweight with the kettlebell at the chest next.
        assertTrue(KneeCareRoutine.controlCue(rdl, 0, calum).contains("Hold the 6 kg kettlebell"))
        assertTrue(KneeCareRoutine.controlCue(rdl, 0, calum).contains("3 x 8"))
        assertFalse(KneeCareRoutine.controlCue(pistol, 0, calum).contains("Kettlebell"))
        assertTrue(KneeCareRoutine.controlCue(pistol, 1, calum).contains("Kettlebell at your chest"))
        assertTrue(KneeCareRoutine.controlCue(rdl, 1, calum).contains("4 s down"), "slower lowering")
        assertTrue(KneeCareRoutine.controlCue(rdl, 2, calum).contains("3 x 10"), "more reps")
        assertTrue(KneeCareRoutine.controlCue(pistol, 2, calum).contains("4 s"))
        assertTrue(KneeCareRoutine.controlCue(pistol, 0, calum).contains("light chair touch") && KneeCareRoutine.controlCue(pistol, 0, calum).contains("middle toes"))
        assertTrue(KneeCareRoutine.controlCue(pistol, 0, calum).contains("pinches"))
        listOf(rdl, pistol).forEach { m -> (0..3).forEach { l -> assertTrue(KneeCareRoutine.controlCue(m, l, calum).length <= 120, "${m.id} L$l") } }
        // No kettlebell: bodyweight cue.
        assertFalse(KneeCareRoutine.controlCue(rdl, 0, bare).contains("kettlebell"))
        // The session passes the level into its steps.
        val s = KneeCareRoutine.build(15, seed = 0, kit = calum, controlLevels = mapOf("kc_sl_rdl" to 2, "kc_pistol" to 1))
        val st = s.steps.first { it.exerciseId == "kc_sl_rdl" }
        assertEquals(2, st.level)
        assertTrue(st.cue.contains("3 x 10"))
    }

    @Test
    fun kneeScoreStillGatesTheSquatVariantsAndMobilityOnly() {
        for (seed in 0L..5L) {
            val five = KneeCareRoutine.build(15, seed = seed, kneePain = 5, kit = full)
            assertTrue(five.steps.none { it.exerciseId == "kc_pistol" || it.exerciseId == "kc_step_down" }, "squat variants drop at 5")
            assertTrue(five.steps.any { it.exerciseId == "kc_sl_rdl" }, "the hip hinge stays")
            val seven = KneeCareRoutine.build(15, seed = seed, kneePain = 7, kit = full)
            assertEquals(KneeGate.GENTLE_ONLY, seven.gate)
            assertTrue(seven.steps.none { it.kind == CoreKind.WORK })
        }
    }

    @Test
    fun gearListsExactlyWhatTheStepsNeedForTheKit() {
        val s = KneeCareRoutine.build(15, seed = 0, kit = calum, calfLevel = 3)
        val names = s.gear.map { it.name }
        assertTrue("Mat" in names)
        assertTrue("Kettlebell (6 kg)" in names)
        assertTrue("Wall or chair for balance" in names)
        assertTrue("Low step" !in names && "Light band" !in names)
        val kb = s.gear.first { it.name == "Kettlebell (6 kg)" }
        assertFalse(kb.optional, "level 3 holds the kettlebell")
        assertTrue(kb.forStep.contains("Calf raises") && kb.forStep.contains("Single-leg Romanian deadlift"), kb.forStep)
        // At level 1 the kettlebell is still listed (a set that feels easy may need it) but optional ... unless the RDL needs it.
        val l1 = KneeCareRoutine.build(15, seed = 1, kit = calum, calfLevel = 1)
        val kb1 = l1.gear.first { it.name == "Kettlebell (6 kg)" }
        assertEquals(l1.steps.any { it.exerciseId == "kc_sl_rdl" }, !kb1.optional)
        // Every item names its exercises and is listed once.
        assertEquals(s.gear.size, s.gear.map { it.name }.toSet().size)
        assertTrue(s.gear.all { it.forStep.isNotBlank() })
        // A full kit adds the step and band items; no mat becomes a towel.
        val f = KneeCareRoutine.build(20, seed = 0, kit = full.copy(hasMat = false), calfLevel = 6)
        val fn = f.gear.map { it.name }
        assertTrue("Towel or carpet to lie on" in fn && "Mat" !in fn)
        assertTrue(f.gear.any { it.name == "Step or stair edge" && it.optional })
        assertTrue(f.gear.any { it.name.startsWith("Kettlebell (12 kg)") })
        // No kettlebell owned: none listed anywhere.
        assertTrue(KneeCareRoutine.build(15, kit = bare).gear.none { it.name.startsWith("Kettlebell") })
        // Gear needs only the steps: the calf-only routine lists balance and the kettlebell.
        val calf = KneeCareRoutine.buildCalfOnly(kit = calum, calfLevel = 4)
        assertEquals(listOf("Wall or chair for balance", "Kettlebell (6 kg)"), calf.gear.map { it.name })
        assertTrue(calf.gear.none { it.optional })
    }

    // -------------------------------------------------------------------------------------------- the calf ladder

    @Test
    fun theLadderHasSixDefinedLevels() {
        fun sp(l: Int, kit: KneeCareKit = calum) = CalfLadder.spec(l, kit)
        assertEquals(6, CalfLadder.LEVELS)
        val l1 = sp(1); assertFalse(l1.singleLeg); assertFalse(l1.loaded); assertEquals(15..15, l1.repsLow..l1.repsHigh)
        val l2 = sp(2); assertTrue(l2.singleLeg); assertFalse(l2.loaded); assertEquals(12..15, l2.repsLow..l2.repsHigh)
        val l3 = sp(3); assertTrue(l3.singleLeg && l3.loaded); assertEquals(12..15, l3.repsLow..l3.repsHigh); assertFalse(l3.slow)
        val l4 = sp(4); assertTrue(l4.singleLeg && l4.loaded && l4.slow); assertEquals(4, l4.downSec); assertEquals(2, l4.pauseSec); assertEquals(10..12, l4.repsLow..l4.repsHigh)
        val l5 = sp(5); assertEquals(4, l5.downSec); assertEquals(2, l5.pauseSec); assertEquals(15..20, l5.repsLow..l5.repsHigh)
        val l6 = sp(6); assertEquals(3, l6.pauseSec); assertEquals(20..25, l6.repsLow..l6.repsHigh)
        assertFalse(l6.stepEdge, "no step: flat ground")
        assertTrue(l6.levelLine.contains("flat ground"))
        assertTrue(sp(6, calum.copy(hasStep = true)).stepEdge)
        assertTrue(sp(6, calum.copy(hasStep = true)).cue().contains("step edge"))
        assertEquals("Level 4 of 6: single-leg with kettlebell, slow lowering", l4.levelLine)
        assertEquals("Level 1 of 6: two legs", l1.levelLine)
        // Without a kettlebell the levels stay bodyweight, tempo and reps still rise.
        assertFalse(sp(4, bare).loaded)
        assertEquals("Level 4 of 6: single-leg, slow lowering", sp(4, bare).levelLine)
        assertEquals(0.0, CalfLadder.loadKg(sp(4, bare), bare))
        assertEquals(6.0, CalfLadder.loadKg(l4, calum))
        assertEquals(0.0, CalfLadder.loadKg(l2, calum))
        assertEquals(6, CalfLadder.clamp(9)); assertEquals(1, CalfLadder.clamp(-2))
        // Single-leg sets take both legs: longer than a two-leg set of the same reps.
        assertTrue(l2.setSec(12) > l1.setSec(12))
        assertTrue(l4.tempo.contains("pause 2 s") && l4.tempo.startsWith("Each leg"))
        assertEquals("Single-leg calf raise (bent knee)", l4.title(CalfVariant.BENT))
    }

    @Test
    fun startLevelComesFromHisSignals() {
        assertEquals(1, CalfLadder.startLevel(emptyList()), "a new user")
        fun gym(kg: Double) = listOf(SessionRecord("g$kg", today.minusDays(5), SessionType.GYM, 60, 55, Feeling.GOOD, exercises = listOf(ExerciseLog("calf", listOf(LoggedSet(kg, 12, 12, 2))))))
        assertEquals(1, CalfLadder.startLevel(gym(30.0)))
        assertEquals(3, CalfLadder.startLevel(gym(40.0)), "gym calf history of 40 kg or more")
        assertEquals(3, CalfLadder.startLevel(gym(60.0)))
        assertEquals(4, CalfLadder.startLevel(gym(80.0)))
        assertEquals(3, CalfLadder.startLevel(emptyList(), reportedEasy = true), "a too easy report")
        assertEquals(4, CalfLadder.startLevel(gym(90.0), reportedEasy = true))
        // The plan uses it only when there is no ladder history, and the gym history is not touched.
        val p = KneeCare.calfPlan(gym(60.0), UserSettings())
        assertEquals(3, p.level); assertTrue(p.fromStart); assertEquals(6.0, p.kg); assertEquals(12, p.reps)
        assertEquals(1, KneeCare.calfPlan(emptyList()).level)
        assertEquals(0.0, KneeCare.calfPlan(emptyList()).kg, "level 1 is bodyweight")
        // Older home logs (no level) do not count as ladder history.
        val legacy = listOf(KneeCareRecord.build("x", today.minusDays(3), 15, 900, emptyList(), listOf(LoggedSet(20.0, 8, 8, 2)))!!)
        assertEquals(1, KneeCare.calfPlan(legacy).level)
    }

    @Test
    fun levelUpWhenAllSetsHitTheTopWithTwoRepsLeftOrTooEasy() {
        // Top reps everywhere with RIR >= 2: up one level.
        assertEquals(4, CalfLadder.nextLevel(listOf(set(3, 15, 2, 15), set(3, 15, 2, 15), set(3, 15, 3, 15))))
        // One set short of the top, or no spare reps: stay.
        assertEquals(3, CalfLadder.nextLevel(listOf(set(3, 15, 2, 15), set(3, 14, 2, 15), set(3, 15, 2, 15))))
        assertEquals(3, CalfLadder.nextLevel(listOf(set(3, 15, 1, 15), set(3, 15, 1, 15), set(3, 15, 1, 15))))
        assertEquals(3, CalfLadder.nextLevel(listOf(set(3, 15, null, 15), set(3, 15, null, 15), set(3, 15, null, 15))), "no feedback is never a level up")
        // Too easy: one tap moves the exercise up a level next session.
        assertEquals(3, CalfLadder.nextLevel(listOf(set(2, 12, 3), set(3, 12, 2), set(3, 12, 2))))
        assertEquals(6, CalfLadder.nextLevel(listOf(set(4, 10, 3), set(5, 15, 2), set(5, 15, 3))), "the best of every set: too easy at level 5 means 6")
        assertEquals(6, CalfLadder.nextLevel(listOf(set(6, 25, 3, 25))), "capped at 6")
        assertEquals(6, CalfLadder.nextLevel(listOf(set(6, 25, 2, 25), set(6, 25, 2, 25), set(6, 25, 2, 25))))
        // Too hard stays; two in a row at the end drops one level.
        assertEquals(4, CalfLadder.nextLevel(listOf(set(4, 10, 2, 10), set(4, 8, 0, 10), set(4, 9, 2, 10))))
        assertEquals(3, CalfLadder.nextLevel(listOf(set(4, 10, 2, 10), set(4, 8, 0, 10), set(4, 7, 0, 10))))
        assertEquals(1, CalfLadder.nextLevel(listOf(set(1, 10, 0, 15), set(1, 9, 0, 15))), "never below 1")
        // Higher reps days never level up on reps alone; sets without a level are ignored.
        assertEquals(3, CalfLadder.nextLevel(listOf(set(3, 18, 2, 18), set(3, 18, 2, 18))))
        assertNull(CalfLadder.nextLevel(listOf(LoggedSet(20.0, 8, 8, 2))))
        assertTrue(CalfLadder.movedUp(listOf(set(3, 15, 2, 15), set(3, 15, 2, 15))))
        assertFalse(CalfLadder.movedUp(listOf(set(3, 12, 2), set(3, 12, 2))))
        assertEquals(3, CalfLadder.startedAt(listOf(set(3, 12, 2), set(4, 12, 2))))
    }

    @Test
    fun theNextSessionStartsAtTheLevelTheLastOneEarnedAndAddsReps() {
        // A session of level 3 at 12 reps, all with spare reps: same level, one more rep.
        val a = listOf(record(today.minusDays(3), set(3, 12, 2, 12), set(3, 12, 2, 12), set(3, 12, 2, 12)))
        val p = KneeCare.calfPlan(a)
        assertEquals(3, p.level); assertEquals(13, p.reps); assertFalse(p.movedUp); assertEquals(6.0, p.kg)
        // Top reps with spare reps: level 4 at the bottom of its range, and the note.
        val b = listOf(record(today.minusDays(3), set(3, 15, 2, 15), set(3, 15, 2, 15), set(3, 15, 2, 15)))
        val q = KneeCare.calfPlan(b)
        assertEquals(4, q.level); assertEquals(10, q.reps); assertTrue(q.movedUp)
        assertEquals(CalfSpec::class, q.spec::class)
        // The setup card's by-hand level change.
        assertEquals(5, KneeCare.calfPlan(b, adjust = 1).level)
        assertEquals(1, KneeCare.calfPlan(emptyList(), adjust = -3).level)
        // Higher reps: the same level, aimed above its range.
        val h = KneeCare.calfPlan(b, mode = CalfMode.STAMINA)
        assertEquals(4, h.level); assertEquals(12 + CalfLadder.STAMINA_EXTRA, h.reps)
        // A too-hard finish drops a level.
        val c = listOf(record(today.minusDays(2), set(4, 10, 2, 10), set(4, 8, 0, 10), set(4, 7, 0, 10)))
        assertEquals(3, KneeCare.calfPlan(c).level)
        // The kit shapes the load: no kettlebell means bodyweight at every level.
        val noKb = UserSettings(kneeKit = KneeCareKit(kettlebellKg = null))
        assertEquals(0.0, KneeCare.calfPlan(b, noKb).kg)
        assertFalse(KneeCare.calfPlan(b, noKb).spec.loaded)
    }

    @Test
    fun aHeavierKettlebellRaisesTheLoadBeforeTheTempo() {
        val settings = UserSettings(kneeKit = KneeCareKit(kettlebellKg = 16.0))
        val top = listOf(record(today.minusDays(3), set(3, 15, 2, 15, 16.0), set(3, 15, 2, 15, 16.0), set(3, 15, 2, 15, 16.0)))
        val p = KneeCare.calfPlan(top, settings)
        assertEquals(3, p.level, "stay at the level and add load first")
        assertEquals(18.5, p.kg)
        // After enough extra load the level goes up.
        val more = listOf(record(today.minusDays(3), set(3, 15, 2, 15, 21.0), set(3, 15, 2, 15, 21.0), set(3, 15, 2, 15, 21.0)))
        assertEquals(4, KneeCare.calfPlan(more, settings).level)
        // A 6 kg kettlebell never does this.
        assertEquals(4, KneeCare.calfPlan(top, UserSettings()).level)
    }

    // ------------------------------------------------------------------------------- the feel row and the rest

    @Test
    fun feelTapsAreStoredAsRirEquivalents() {
        assertEquals(3, Effort.TOO_EASY.rir)
        assertEquals(2, Effort.JUST_RIGHT.rir)
        assertEquals(0, Effort.TOO_HARD.rir)
        assertEquals(Effort.TOO_EASY, effortOf(3)); assertEquals(Effort.JUST_RIGHT, effortOf(2)); assertEquals(Effort.TOO_HARD, effortOf(0))
        assertEquals(Effort.JUST_RIGHT, effortOf(1))
        assertNull(effortOf(null))
    }

    @Test
    fun tooEasyUpgradesTheNextSetWithTheNextLever() {
        val k = calum
        // Level 1 (two legs) -> single-leg; 2 -> kettlebell; 3 -> slow lowering with pause; 4 -> more reps.
        assertEquals(2 to 12, CalfLadder.follower(1, 15, Effort.TOO_EASY, null, k, CalfMode.HEAVY))
        assertEquals(3 to 12, CalfLadder.follower(2, 12, Effort.TOO_EASY, null, k, CalfMode.HEAVY))
        assertEquals(4 to 10, CalfLadder.follower(3, 12, Effort.TOO_EASY, null, k, CalfMode.HEAVY))
        assertEquals(5 to 15, CalfLadder.follower(4, 10, Effort.TOO_EASY, null, k, CalfMode.HEAVY))
        // At the top of the ladder: two more reps.
        assertEquals(6 to 27, CalfLadder.follower(6, 25, Effort.TOO_EASY, null, k, CalfMode.HEAVY))
        // Just right keeps the set; too hard keeps it, and drops a level after two in a row.
        assertEquals(3 to 12, CalfLadder.follower(3, 12, Effort.JUST_RIGHT, null, k, CalfMode.HEAVY))
        assertEquals(3 to 12, CalfLadder.follower(3, 12, null, null, k, CalfMode.HEAVY))
        assertEquals(3 to 12, CalfLadder.follower(3, 12, Effort.TOO_HARD, null, k, CalfMode.HEAVY))
        assertEquals(3 to 12, CalfLadder.follower(4, 10, Effort.TOO_HARD, Effort.TOO_HARD, k, CalfMode.HEAVY))
        assertEquals(1 to 15, CalfLadder.follower(1, 15, Effort.TOO_HARD, Effort.TOO_HARD, k, CalfMode.HEAVY))
    }

    @Test
    fun restIsShortWhenItFeltEasyAndALittleLongerWhenHard() {
        assertEquals(30, CalfLadder.restSec(45, Effort.TOO_EASY))
        assertEquals(30, CalfLadder.restSec(30, Effort.TOO_EASY), "never lengthened")
        assertEquals(45, CalfLadder.restSec(45, Effort.JUST_RIGHT))
        assertEquals(45, CalfLadder.restSec(45, null))
        assertEquals(60, CalfLadder.restSec(45, Effort.TOO_HARD))
        assertEquals(45, CalfLadder.baseRestSec(3, CalfMode.HEAVY))
        assertEquals(60, CalfLadder.baseRestSec(4, CalfMode.HEAVY))
        assertEquals(35, CalfLadder.baseRestSec(4, CalfMode.STAMINA))
    }

    @Test
    fun homeNoteSaysItPlainly() {
        assertEquals(
            "At home the load is limited, so we make it harder with one leg, a slower lowering and more reps. Your heavy calf raises at the gym stay the strength work.",
            KneeCare.HOME_NOTE,
        )
    }

    @Test
    fun controlLevelsRiseWithUseAndTheFeedback() {
        fun rec(day: Int, rir: Int?, knee: Int? = 1) = KneeCareRecord.build(
            "c$day", today.minusDays(30L - day), 15, 900, listOf("Hip circles", "Single-leg Romanian deadlift"),
            listOf(LoggedSet(6.0, 12, 12, rir, 3)), kneePain = knee,
        )!!
        assertEquals(0, KneeCare.controlLevel(emptyList(), "kc_sl_rdl"))
        assertEquals(0, KneeCare.controlLevel(listOf(rec(1, 2)), "kc_sl_rdl"))
        assertEquals(1, KneeCare.controlLevel(listOf(rec(1, 2), rec(3, 2)), "kc_sl_rdl"))
        assertEquals(2, KneeCare.controlLevel(listOf(rec(1, 2), rec(3, 2), rec(5, 3)), "kc_sl_rdl"), "an easy calf session nudges up")
        assertEquals(0, KneeCare.controlLevel(listOf(rec(1, 2), rec(3, 0)), "kc_sl_rdl"), "a hard one nudges down")
        assertEquals(0, KneeCare.controlLevel(listOf(rec(1, 2), rec(3, 2), rec(5, 2, knee = 6)), "kc_sl_rdl"), "a sore knee after drops a level")
        assertEquals(3, KneeCare.controlLevel((1..12).map { rec(it, 3) }, "kc_sl_rdl"), "capped")
        assertEquals(setOf("kc_sl_rdl", "kc_pistol", "kc_step_down"), KneeCare.controlLevels(emptyList()).keys)
        assertNotNull(KneeCareLibrary.get("kc_pistol"))
    }
}
