package com.calum.traininglog.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where he trains (gym or home), the kit he has there, and the treadmill runs between stations. */
class HyroxGearTest {
    private val D = HyroxDivision.MEN_OPEN
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val homeStart = setOf(HyroxGear.TREADMILL)
    private val homeBought = setOf(HyroxGear.TREADMILL, HyroxGear.WALL_BALL, HyroxGear.SANDBAG)

    private fun plan(
        minutes: Int = 45, gear: Set<HyroxGear>? = null, focus: Set<HyroxStation>? = null, runs: Boolean = false,
        records: List<SessionRecord> = emptyList(), runM: Int = 400, settings: UserSettings = UserSettings(),
    ) = HyroxSessionPlan.build(minutes, records, D, settings, focus, runs, gear, runM)

    private fun knee(score: Int) = SessionRecord("k", LocalDate.parse("2026-11-01"), SessionType.GYM, 60, 60, Feeling.GOOD, kneePain = score)

    @Test fun requiredGearPerStation() {
        assertEquals(emptySet(), HyroxStation.BURPEE_BROAD_JUMP.requiredGear)
        assertEquals(setOf(HyroxGear.SLED), HyroxStation.SLED_PUSH.requiredGear)
        assertEquals(setOf(HyroxGear.SLED), HyroxStation.SLED_PULL.requiredGear)
        assertEquals(setOf(HyroxGear.SKIERG), HyroxStation.SKIERG.requiredGear)
        assertEquals(setOf(HyroxGear.ROWER), HyroxStation.ROW.requiredGear)
        assertEquals(setOf(HyroxGear.WEIGHTS), HyroxStation.FARMERS_CARRY.requiredGear)
        assertEquals(setOf(HyroxGear.SANDBAG), HyroxStation.SANDBAG_LUNGES.requiredGear)
        assertEquals(setOf(HyroxGear.WALL_BALL), HyroxStation.WALL_BALLS.requiredGear)
        assertTrue(HyroxStation.BURPEE_BROAD_JUMP.availableWith(emptySet()))
        assertTrue(HyroxStation.SLED_PUSH.availableWith(null))
    }

    @Test fun gymMeansEverythingAndNullGearIsUnchanged() {
        val s = UserSettings()
        assertEquals(HyroxGear.entries.toSet(), s.hyroxGear)
        val everything = plan(gear = s.hyroxGear)
        val legacy = plan()
        assertEquals(legacy.blocks, everything.blocks)
        assertTrue(everything.unavailable.isEmpty())
        assertTrue(everything.blocks.size >= 2)
    }

    @Test fun homeWithOnlyATreadmillGivesBurpeesAndAHelpfulNote() {
        val p = plan(gear = homeStart)
        assertEquals(listOf(HyroxStation.BURPEE_BROAD_JUMP), p.blocks.map { it.station })
        assertEquals(7, p.unavailable.size)
        assertTrue(p.notes.any { "substitute" in it }, p.notes.toString())
        val withRuns = plan(gear = homeStart, runs = true)
        assertEquals(listOf(400), withRuns.blocks.map { it.runAfterM })
        assertTrue(withRuns.runSpec!!.treadmill)
    }

    @Test fun homeWithWallBallAndSandbagUsesThoseThreeStations() {
        val p = plan(60, gear = homeBought)
        assertEquals(
            setOf(HyroxStation.WALL_BALLS, HyroxStation.SANDBAG_LUNGES, HyroxStation.BURPEE_BROAD_JUMP),
            p.blocks.map { it.station }.toSet(),
        )
        assertTrue(p.estimatedMinutes <= 60)
        assertFalse(p.unavailable.isEmpty())
    }

    @Test fun focusWithoutGearIsSkippedWithANote() {
        val p = plan(gear = homeStart, focus = setOf(HyroxStation.SLED_PUSH, HyroxStation.BURPEE_BROAD_JUMP))
        assertEquals(listOf(HyroxStation.BURPEE_BROAD_JUMP), p.blocks.map { it.station })
        assertEquals(listOf(HyroxStation.SLED_PUSH), p.unavailable)
        val note = p.notes.first { "Sled Push" in it }
        assertTrue("sled" in note, note)
        assertTrue(HyroxGuide.of(HyroxStation.SLED_PUSH).alternatives.first() in note, note)
        // Nothing possible at all: still a plan, with the alternative as a note, not a failure.
        val none = plan(gear = homeStart, focus = setOf(HyroxStation.WALL_BALLS))
        assertTrue(none.blocks.isEmpty())
        assertTrue(none.notes.any { "wall ball" in it })
        assertTrue(none.notes.any { it.contains("Thrusters") }, none.notes.toString())
    }

    @Test fun wallBallSubstituteIsThrusters() {
        val p = plan(gear = homeStart)
        assertTrue(p.notes.any { it.startsWith("Wall Balls substitute: Thrusters") }, p.notes.toString())
        assertEquals("Thrusters with a dumbbell or barbell", HyroxGuide.of(HyroxStation.WALL_BALLS).alternatives.first())
    }

    @Test fun sessionTimeStillRespectedAtHome() {
        for (m in listOf(20, 30, 45, 60, 90)) {
            val p = plan(m, gear = homeBought, runs = true)
            assertTrue(p.estimatedMinutes <= m, "$m: ${p.estimatedMinutes}")
        }
    }

    @Test fun kneeRuleStillHolds() {
        val sore = listOf(knee(5))
        val p = plan(60, gear = homeBought, runs = true, records = sore)
        assertEquals(0, p.runMetres)
        assertNull(p.runSpec)
        assertTrue(p.blocks.count { it.station.kneeHeavy } <= 1, "one knee-heavy station at most")
        val ok = plan(60, gear = homeBought, runs = true, records = listOf(knee(3)))
        assertTrue(ok.runMetres > 0)
    }

    @Test fun runSpecTreadmillOrStopwatch() {
        val s = UserSettings(hyroxRunSpeedKmh = 9.0, hyroxRunInclinePct = 1.0)
        val tm = plan(gear = homeStart, runs = true, runM = 600, settings = s).runSpec!!
        assertTrue(tm.treadmill)
        assertEquals(600, tm.metres)
        assertEquals(240, tm.targetSec) // 600 m at 9 km/h
        assertEquals(1.0, tm.inclinePct)
        val outside = plan(gear = homeBought - HyroxGear.TREADMILL, runs = true, settings = s)
        assertFalse(outside.runSpec!!.treadmill)
        assertNull(outside.runSpec!!.targetSec)
        assertTrue(outside.blocks.all { it.runAfterM == 400 })
        assertNull(plan(gear = homeStart).runSpec)
        assertEquals(160, HyroxRuns.targetSec(400, 9.0))
        assertEquals(400, HyroxRuns.distanceAt(9.0, 160))
    }

    @Test fun settingsDefaultsRoundTripAndOldJson() {
        val d = UserSettings()
        assertEquals(setOf(HyroxGear.TREADMILL), d.hyroxHomeGear)
        assertEquals(HyroxPlace.GYM, d.hyroxPlace)
        assertEquals(9.0, d.hyroxRunSpeedKmh)
        assertEquals(1.0, d.hyroxRunInclinePct)
        val custom = d.withHyroxGear(HyroxPlace.HOME, HyroxGear.WALL_BALL, true).copy(hyroxPlace = HyroxPlace.HOME)
        assertEquals(homeStart + HyroxGear.WALL_BALL, custom.hyroxGear)
        assertEquals(custom, json.decodeFromString<UserSettings>(json.encodeToString(custom)))
        assertEquals(homeStart, custom.withHyroxGear(HyroxPlace.HOME, HyroxGear.WALL_BALL, false).hyroxGear)
        // Old JSON with none of the new fields decodes to the defaults.
        val old = json.decodeFromString<UserSettings>("""{"version":1,"gymTarget":3,"hyroxDivision":"MEN_OPEN"}""")
        assertEquals(3, old.gymTarget)
        assertEquals(HyroxGear.entries.toSet(), old.hyroxGymGear)
        assertEquals(homeStart, old.hyroxHomeGear)
        assertEquals(HyroxPlace.GYM, old.hyroxPlace)
        // Sanitising keeps the speed in range, on half-km/h steps.
        assertEquals(16.0, d.copy(hyroxRunSpeedKmh = 40.0).sanitized().hyroxRunSpeedKmh)
        assertEquals(6.0, d.copy(hyroxRunSpeedKmh = 1.0).sanitized().hyroxRunSpeedKmh)
        assertEquals(9.5, d.copy(hyroxRunSpeedKmh = 9.4).sanitized().hyroxRunSpeedKmh)
        assertEquals(15.0, d.copy(hyroxRunInclinePct = 99.0).sanitized().hyroxRunInclinePct)
    }

    // ---- runs as logs: every consumer copes with stationId "run"

    private fun runSession(): SessionRecord = SessionRecord(
        "r1", LocalDate.parse("2026-11-03"), SessionType.HYROX, 45, 44, Feeling.GOOD,
        stations = listOf(
            StationLog("wall_balls", 6.0, null, 50, 200, 7, 3, 3),
            StationLog("run", null, 400, null, 151, 6),
            StationLog("sandbag_lunges", 15.0, 100, null, 300, 8, 2, 4),
            StationLog("run", null, 400, null, 160, null),
        ),
    )

    @Test fun runsAreSafeEverywhere() {
        val r = runSession()
        val day = LocalDate.parse("2026-11-04")
        assertNull(HyroxStation.byId("run"))
        assertFalse(HyroxStandards.meets(D, r.stations[1]))
        val ex = Export.summary(listOf(r), day.minusDays(7), day)
        assertTrue("Run 400 m in 2:31, RPE 6" in ex, ex)
        assertTrue("Run 400 m in 2:40" in ex, ex)
        val sum = Rewards.summary(listOf(r), day)
        assertTrue(sum.badges.first { it.id == "hyrox_first_station" }.unlocked)
        assertFalse(sum.badges.first { it.id == "hyrox_all_eight" }.unlocked)
        assertFalse(sum.badges.first { it.id == "hyrox_race_standard" }.unlocked)
        val noRuns = r.copy(stations = r.stations.filter { it.stationId != "run" })
        assertEquals(2 * Rewards.HYROX_PER_RUN, Rewards.sessionXp(r, emptyList()).total - Rewards.sessionXp(noRuns, emptyList()).total)
        assertTrue(ProgressStats.stationBest(listOf(r), HyroxStation.WALL_BALLS, day).isNotEmpty())
        // The public CSV carries the runs; the import brings them back.
        val files = PublicExport.files(listOf(r))
        val csv = files.getValue("data/hyrox/2026-11.csv")
        assertTrue("2026-11-03,run,,400,,151,6" in csv, csv)
        assertTrue("2026-11-03,run,,400,,160," in csv, csv)
        val back = PublicImport.records(files).single { it.type == SessionType.HYROX }
        assertEquals(listOf("wall_balls", "run", "sandbag_lunges", "run"), back.stations.map { it.stationId })
        assertEquals(StationLog("run", null, 400, null, 151, 6), back.stations[1])
        // And the record itself saves and loads.
        assertEquals(r, json.decodeFromString<SessionRecord>(json.encodeToString(r)))
    }
}
