package com.calum.traininglog.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HyroxTest {
    private val D = HyroxDivision.MEN_OPEN
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun day(n: Int) = LocalDate.parse("2026-11-01").plusDays(n.toLong())

    private fun entry(n: Int, station: HyroxStation, level: Int, rpe: Int?, tech: Int? = null, full: Boolean = true,
                      division: HyroxDivision = D): StationEntry {
        val l = HyroxLadder.of(division, station)[level - 1]
        val work = if (full) l.totalWork else l.totalWork / 2
        return StationEntry(
            day(n),
            StationLog(
                station.id, weightKg = l.weightKg,
                distanceM = if (station.kind == StationKind.DISTANCE) work else null,
                reps = if (station.kind == StationKind.REPS) work else null,
                rpe = rpe, technique = tech, level = level,
            ),
        )
    }

    private fun session(id: String, n: Int, vararg logs: StationLog, knee: Int? = null) = SessionRecord(
        id = id, date = day(n), type = SessionType.HYROX, minutesAvailable = 45, minutesActual = 45,
        feeling = Feeling.GOOD, kneePain = knee, stations = logs.toList(),
    )

    // ---- standards

    @Test fun standardsTable() {
        fun s(d: HyroxDivision, st: HyroxStation) = HyroxStandards.of(d, st)
        assertEquals(152.0, s(D, HyroxStation.SLED_PUSH).weightKg)
        assertEquals(103.0, s(D, HyroxStation.SLED_PULL).weightKg)
        assertEquals(24.0, s(D, HyroxStation.FARMERS_CARRY).weightKg)
        assertEquals(20.0, s(D, HyroxStation.SANDBAG_LUNGES).weightKg)
        assertEquals(StationStandard(reps = 100, weightKg = 6.0, targetHeightM = 3.0), s(D, HyroxStation.WALL_BALLS))
        val w = HyroxDivision.WOMEN_OPEN
        assertEquals(102.0, s(w, HyroxStation.SLED_PUSH).weightKg)
        assertEquals(78.0, s(w, HyroxStation.SLED_PULL).weightKg)
        assertEquals(16.0, s(w, HyroxStation.FARMERS_CARRY).weightKg)
        assertEquals(10.0, s(w, HyroxStation.SANDBAG_LUNGES).weightKg)
        assertEquals(4.0, s(w, HyroxStation.WALL_BALLS).weightKg)
        assertTrue(s(w, HyroxStation.WALL_BALLS).approx)
        assertEquals(2.7, s(w, HyroxStation.WALL_BALLS).targetHeightM)
        assertEquals(listOf(202.0, 153.0, 32.0, 30.0, 9.0), listOf(HyroxStation.SLED_PUSH, HyroxStation.SLED_PULL, HyroxStation.FARMERS_CARRY, HyroxStation.SANDBAG_LUNGES, HyroxStation.WALL_BALLS).map { s(HyroxDivision.MEN_PRO, it).weightKg })
        assertEquals(listOf(152.0, 103.0, 24.0, 20.0, 6.0), listOf(HyroxStation.SLED_PUSH, HyroxStation.SLED_PULL, HyroxStation.FARMERS_CARRY, HyroxStation.SANDBAG_LUNGES, HyroxStation.WALL_BALLS).map { s(HyroxDivision.WOMEN_PRO, it).weightKg })
        assertTrue(s(HyroxDivision.MEN_PRO, HyroxStation.WALL_BALLS).approx)
        assertFalse(s(D, HyroxStation.WALL_BALLS).approx)
        for (d in HyroxDivision.entries) {
            assertEquals(1000, s(d, HyroxStation.SKIERG).distanceM)
            assertEquals(1000, s(d, HyroxStation.ROW).distanceM)
            assertEquals(80, s(d, HyroxStation.BURPEE_BROAD_JUMP).distanceM)
            assertEquals(50, s(d, HyroxStation.SLED_PUSH).distanceM)
            assertEquals(200, s(d, HyroxStation.FARMERS_CARRY).distanceM)
            assertEquals(100, s(d, HyroxStation.SANDBAG_LUNGES).distanceM)
        }
        assertTrue(HyroxStandards.standardsNote.contains("confirm at hyrox.com"))
    }

    @Test fun stationsInRaceOrder() {
        assertEquals(8, HyroxStation.entries.size)
        assertEquals((1..8).toList(), HyroxStation.entries.map { it.order })
        assertEquals(StationKind.REPS, HyroxStation.WALL_BALLS.kind)
        assertEquals(setOf(HyroxStation.BURPEE_BROAD_JUMP, HyroxStation.SANDBAG_LUNGES), HyroxStation.entries.filter { it.kneeHeavy }.toSet())
        assertEquals(HyroxStation.ROW, HyroxStation.byId("row"))
    }

    @Test fun overridesReplaceWeight() {
        val s = HyroxStandards.of(D, HyroxStation.SLED_PUSH, mapOf("sled_push" to 100.0))
        assertEquals(100.0, s.weightKg)
        assertEquals(100.0, HyroxLadder.of(D, HyroxStation.SLED_PUSH, mapOf("sled_push" to 100.0))[4].weightKg)
        // Stations without a weight ignore overrides.
        assertNull(HyroxStandards.of(D, HyroxStation.ROW, mapOf("row" to 5.0)).weightKg)
    }

    // ---- guide

    @Test fun guidesAreComplete() {
        for (st in HyroxStation.entries) {
            val g = HyroxGuide.of(st)
            assertTrue(g.summary.isNotBlank() && g.whatItIs.isNotBlank() && g.pacingTip.isNotBlank(), st.name)
            assertTrue(g.tips.size in 4..5, "${st.name} tips")
            assertEquals(3, g.mistakes.size, "${st.name} mistakes")
            assertTrue(g.alternatives.isNotEmpty())
            for (t in g.tips) assertTrue(t.split(" ").size <= 20, "tip too long: $t")
            assertEquals(st == HyroxStation.ROW, g.confident, st.name)
        }
        assertNotNull(HyroxGuide.of(HyroxStation.BURPEE_BROAD_JUMP).kneeNote)
        assertNotNull(HyroxGuide.of(HyroxStation.SANDBAG_LUNGES).kneeNote)
    }

    // ---- ladder

    @Test fun laddersAreMonotoneAndEndAtRaceStandard() {
        for (d in HyroxDivision.entries) for (st in HyroxStation.entries) {
            val ladder = HyroxLadder.of(d, st)
            assertEquals(6, ladder.size)
            assertEquals((1..6).toList(), ladder.map { it.level })
            val std = HyroxStandards.of(d, st)
            var prev: Triple<Double, Int, Int>? = null
            for (l in ladder) {
                val key = Triple(l.weightKg ?: 0.0, l.distanceM ?: l.reps ?: 0, l.rounds)
                prev?.let {
                    assertTrue(
                        key.first > it.first || (key.first == it.first && (key.second > it.second || (key.second == it.second && key.third >= it.third))),
                        "$d $st level ${l.level} not harder: $key after $it",
                    )
                }
                prev = key
                assertTrue(l.targetNote.isNotBlank())
            }
            // Level 5 is exactly the race standard, level 6 goes beyond it.
            val race = ladder[HyroxLadder.RACE_LEVEL - 1]
            assertEquals(std.weightKg, race.weightKg, "$d $st")
            assertEquals(std.distanceM ?: std.reps, race.totalWork, "$d $st")
            assertTrue(ladder[5].totalWork > race.totalWork, "$d $st")
            // First level is lighter or shorter than race.
            assertTrue(ladder[0].totalWork < race.totalWork || (ladder[0].weightKg ?: 0.0) < (race.weightKg ?: 0.0), "$d $st")
        }
    }

    @Test fun sledPushScalesFromRaceLoad() {
        val l = HyroxLadder.of(D, HyroxStation.SLED_PUSH)
        assertEquals(4, l[0].rounds)
        assertEquals(45.0, l[0].weightKg) // about 30% of 152
        assertEquals(152.0, l[4].weightKg)
        assertEquals(2, l[5].rounds)
        val wb = HyroxLadder.of(D, HyroxStation.WALL_BALLS)
        assertEquals(3, wb[0].rounds); assertEquals(10, wb[0].reps)
        assertEquals(100, wb[4].reps)
    }

    @Test fun meetsStandardChecksWeightAndVolume() {
        val std = HyroxStandards.of(D, HyroxStation.SLED_PUSH)
        assertTrue(HyroxStandards.meets(std, StationLog("sled_push", weightKg = 152.0, distanceM = 50)))
        assertFalse(HyroxStandards.meets(std, StationLog("sled_push", weightKg = 140.0, distanceM = 50)))
        assertFalse(HyroxStandards.meets(std, StationLog("sled_push", weightKg = 152.0, distanceM = 25)))
        assertTrue(HyroxStandards.meets(D, StationLog("row", distanceM = 1000)))
        assertFalse(HyroxStandards.meets(D, StationLog("wall_balls", weightKg = 6.0, reps = 90)))
    }

    // ---- progression

    @Test fun unknownHistoryStartsLowExceptRowAndSkierg() {
        for (st in HyroxStation.entries) {
            val p = HyroxProgression.next(st, D, emptyList())
            val expected = if (st == HyroxStation.ROW || st == HyroxStation.SKIERG) 3 else 1
            assertEquals(expected, p.level, st.name)
            assertTrue(p.note.isNotBlank())
        }
        val row = HyroxProgression.next(HyroxStation.ROW, D, emptyList())
        assertTrue(row.note.contains("comfortable"))
    }

    @Test fun movesUpWhenEasyAndComplete() {
        val p = HyroxProgression.next(HyroxStation.SLED_PUSH, D, listOf(entry(0, HyroxStation.SLED_PUSH, 1, rpe = 7, tech = 2)))
        assertEquals(2, p.level)
        assertEquals(2, HyroxProgression.next(HyroxStation.SLED_PUSH, D, listOf(entry(0, HyroxStation.SLED_PUSH, 1, 8))).level)
    }

    @Test fun staysAtRpe9OrPoorTechniqueOrNoRpe() {
        val s = HyroxStation.SLED_PULL
        assertEquals(2, HyroxProgression.next(s, D, listOf(entry(0, s, 2, 9))).level)
        assertEquals(2, HyroxProgression.next(s, D, listOf(entry(0, s, 2, 6, tech = 1))).level)
        assertEquals(2, HyroxProgression.next(s, D, listOf(entry(0, s, 2, null))).level)
        // One incomplete session alone does not drop a level.
        assertEquals(3, HyroxProgression.next(s, D, listOf(entry(0, s, 3, 8, full = false))).level)
        assertEquals(3, HyroxProgression.next(s, D, listOf(entry(0, s, 3, 10))).level)
    }

    @Test fun dropsBackAfterTwoHardSessions() {
        val s = HyroxStation.FARMERS_CARRY
        val two = listOf(entry(7, s, 3, 10), entry(0, s, 3, 10))
        assertEquals(2, HyroxProgression.next(s, D, two).level)
        val incomplete = listOf(entry(7, s, 3, 8, full = false), entry(0, s, 3, 10))
        assertEquals(2, HyroxProgression.next(s, D, incomplete).level)
        // Never below level 1.
        assertEquals(1, HyroxProgression.next(s, D, listOf(entry(7, s, 1, 10), entry(0, s, 1, 10))).level)
        // An easy session between them resets it.
        assertEquals(3, HyroxProgression.next(s, D, listOf(entry(7, s, 3, 10), entry(3, s, 3, 7).let { it.copy(log = it.log.copy(rpe = 9)) }, entry(0, s, 3, 10))).level)
    }

    /** A block shortened to fit the time, and done as planned, is not 'incomplete' and does not move you up. */
    @Test fun shortenedBlockIsNeitherHardNorAPromotion() {
        val s = HyroxStation.SLED_PUSH
        val level = HyroxLadder.of(D, s)[2]
        fun shortened(n: Int, rpe: Int) = StationEntry(
            day(n),
            StationLog(s.id, weightKg = level.weightKg, distanceM = level.totalWork / 2, rpe = rpe, level = 3, plannedWork = level.totalWork / 2),
        )
        // Done as planned at an easy effort: repeat at full length, do not move up.
        val one = HyroxProgression.next(s, D, listOf(shortened(0, 7)))
        assertEquals(3, one.level)
        assertTrue(one.note.contains("shortened"), one.note)
        // Two shortened sessions in a row never count as two hard ones.
        assertEquals(3, HyroxProgression.next(s, D, listOf(shortened(7, 7), shortened(0, 8))).level)
        // The same distance WITHOUT the planned-work note is still judged against the full level (incomplete).
        val unplanned = StationEntry(day(0), shortened(0, 8).log.copy(plannedWork = null))
        val hardPrev = StationEntry(day(-7), shortened(-7, 10).log.copy(plannedWork = null))
        assertEquals(2, HyroxProgression.next(s, D, listOf(unplanned, hardPrev)).level)
        // Falling short of even the shortened plan is incomplete.
        val short = StationEntry(day(0), shortened(0, 8).log.copy(distanceM = level.totalWork / 4))
        assertEquals(3, HyroxProgression.next(s, D, listOf(short)).level)
    }

    @Test fun topOfLadderStays() {
        val s = HyroxStation.WALL_BALLS
        assertEquals(6, HyroxProgression.next(s, D, listOf(entry(0, s, 6, 6))).level)
    }

    @Test fun messagesNeverGuilt() {
        val s = HyroxStation.BURPEE_BROAD_JUMP
        val notes = listOf(
            HyroxProgression.next(s, D, emptyList()),
            HyroxProgression.next(s, D, listOf(entry(7, s, 3, 10), entry(0, s, 3, 10))),
            HyroxProgression.next(s, D, listOf(entry(0, s, 3, 8, full = false))),
        ).map { it.note.lowercase() }
        for (n in notes) for (bad in listOf("fail", "missed", "behind", "should have", "disappoint", "lazy")) assertFalse(bad in n, n)
    }

    // ---- session plan

    private fun plan(minutes: Int, records: List<SessionRecord> = emptyList(), runs: Boolean = false, focus: Set<HyroxStation>? = null) =
        HyroxSessionPlan.build(minutes, records, D, UserSettings(), focus, runs)

    @Test fun planRespectsMinutes() {
        for (m in listOf(20, 30, 45, 60, 90)) {
            val p = plan(m)
            assertTrue(p.estimatedMinutes <= m, "$m -> ${p.estimatedMinutes}")
            assertTrue(p.blocks.size in 1..4, "$m blocks ${p.blocks.size}")
            assertTrue(p.warmupNote.isNotBlank())
            assertEquals(p.estimatedMinutes, 8 + p.blocks.sumOf { it.estimatedMin })
        }
        assertTrue(plan(20).blocks.size >= 2)
        assertEquals(4, plan(90).blocks.size)
        assertTrue(plan(90).blocks.size >= plan(30).blocks.size)
        assertTrue(plan(5).estimatedMinutes <= 20) // clamped to 20
    }

    @Test fun planWithRunsAddsRunsAndStillFits() {
        val p = plan(60, runs = true)
        assertTrue(p.runMetres > 0)
        assertEquals(p.blocks.size * 400, p.runMetres)
        assertTrue(p.estimatedMinutes <= 60)
        assertEquals(0, plan(60).runMetres)
    }

    @Test fun planRespectsKneeRule() {
        val sore = session("s0", -10, StationLog("row", distanceM = 1000, rpe = 6), knee = 5)
        for (m in listOf(45, 60, 90)) {
            val p = plan(m, listOf(sore), focus = setOf(HyroxStation.BURPEE_BROAD_JUMP, HyroxStation.SANDBAG_LUNGES, HyroxStation.WALL_BALLS, HyroxStation.SKIERG))
            assertTrue(p.blocks.count { it.station.kneeHeavy } <= 1, "$m")
            assertEquals(0, plan(m, listOf(sore), runs = true).runMetres)
        }
        // Without a high knee score both knee-heavy stations can appear together.
        val ok = plan(90, focus = setOf(HyroxStation.BURPEE_BROAD_JUMP, HyroxStation.SANDBAG_LUNGES))
        assertEquals(2, ok.blocks.count { it.station.kneeHeavy })
    }

    @Test fun planRotatesStations() {
        val first = plan(30)
        val rec = session("a", 0, *first.blocks.map { StationLog(it.station.id, distanceM = it.prescription.distanceM, reps = it.prescription.reps, rpe = 8, level = it.prescription.level) }.toTypedArray())
        val second = plan(30, listOf(rec))
        assertTrue(second.blocks.map { it.station }.toSet().intersect(first.blocks.map { it.station }.toSet()).isEmpty(), "${first.label} / ${second.label}")
    }

    @Test fun planFocusLimitsStations() {
        val p = plan(60, focus = setOf(HyroxStation.WALL_BALLS, HyroxStation.ROW))
        assertTrue(p.blocks.all { it.station in setOf(HyroxStation.WALL_BALLS, HyroxStation.ROW) })
        assertTrue(p.label.startsWith("Hyrox"))
    }

    @Test fun planUsesProgression() {
        val rec = session("a", 0, StationLog("sled_push", weightKg = 45.0, distanceM = 40, rpe = 6, level = 1))
        val p = plan(30, listOf(rec), focus = setOf(HyroxStation.SLED_PUSH))
        assertEquals(2, p.blocks.single { it.station == HyroxStation.SLED_PUSH }.prescription.level)
    }

    // ---- data model

    @Test fun oldJsonDecodes() {
        val old = """{"id":"x","date":"2026-10-05","type":"GYM","minutesAvailable":40,"minutesActual":38,"feeling":"GOOD"}"""
        val r = json.decodeFromString<SessionRecord>(old)
        assertEquals(emptyList(), r.stations)
        val oldSettings = json.decodeFromString<UserSettings>("""{"gymTarget":3}""")
        assertEquals(HyroxDivision.MEN_OPEN, oldSettings.hyroxDivision)
        assertEquals(emptyMap(), oldSettings.hyroxWeightOverrides)
        val log = json.decodeFromString<StationLog>("""{"stationId":"row"}""")
        assertNull(log.seconds)
    }

    @Test fun jsonRoundTrip() {
        val r = session("h", 3, StationLog("sled_push", 102.5, 50, null, 72, 8, 2, 5))
        assertEquals(r, json.decodeFromString<SessionRecord>(json.encodeToString(r)))
        val s = UserSettings(hyroxDivision = HyroxDivision.WOMEN_PRO, hyroxWeightOverrides = mapOf("sled_push" to 90.0))
        assertEquals(s, json.decodeFromString<UserSettings>(json.encodeToString(s)))
    }

    @Test fun settingsSanitizeOverrides() {
        val s = UserSettings(hyroxWeightOverrides = mapOf("sled_push" to 9999.0, "row" to 10.0, "nope" to 5.0, "wall_balls" to 0.0)).sanitized()
        assertEquals(mapOf("sled_push" to 300.0, "wall_balls" to 1.0), s.hyroxWeightOverrides)
        assertEquals(300.0, s.hyroxStandard(HyroxStation.SLED_PUSH).weightKg)
    }

    @Test fun hyroxCountsAsOtherNeverGymOrTreadmill() {
        val recs = listOf(session("a", 0, StationLog("row", distanceM = 1000)))
        val w = Stats.week(recs, day(0))
        assertEquals(WeekStats(gym = 0, treadmill = 0, other = 1), w)
        assertEquals(1, ProgressStats.build(recs, day(0)).weekly.last().other)
        assertEquals(0, Rewards.ring(recs, day(0)).gym)
        assertEquals(0, Rewards.ring(recs, day(0)).treadmill)
    }

    // ---- export text

    @Test fun textExportHasStationLines() {
        val r = session("a", 0, StationLog("sled_push", 102.5, 50, null, 72, 8, 2, 5), StationLog("wall_balls", 6.0, null, 100, 410, 9, 3, 5))
        val text = Export.summary(listOf(r), day(0), day(0))
        assertTrue("hyrox" in text)
        assertTrue("Sled Push: 102.5 kg, 50 m, 1:12, RPE 8, technique 2/3, level 5" in text, text)
        assertTrue("Wall Balls: 6 kg, 100 reps, 6:50, RPE 9, technique 3/3, level 5" in text, text)
        assertTrue("other 1" in text)
    }

    // ---- public export / import

    @Test fun publicHyroxCsvAndPrivacy() {
        val secret = "SECRETNOTE-1"
        val r = SessionRecord(
            "h1", day(0), SessionType.HYROX, 4242, 40, Feeling.ILL, kneePain = 9, note = secret,
            stations = listOf(
                StationLog("sled_push", 102.5, 50, null, 72, 8, 3, 5),
                StationLog("row", null, 1000, null, 240, 7),
                StationLog("run", null, 400, null, 151, 6, level = 3, technique = 2),
                StationLog("custom:secretstation", 1.0, 1, null, 1, 1),
            ),
        )
        val files = PublicExport.files(listOf(r))
        assertEquals(
            "date,station,weight_kg,distance_m,reps,seconds,rpe\n2026-11-01,sled_push,102.5,50,,72,8\n2026-11-01,row,,1000,,240,7\n2026-11-01,run,,400,,151,6\n",
            files["data/hyrox/2026-11.csv"],
        )
        for ((path, content) in files) {
            val all = (path + "\n" + content).lowercase()
            for (bad in listOf("secret", "knee", "feeling", "note", "custom", "4242", "technique", "level")) assertFalse(bad in all, "'$bad' leaked into $path")
            assertFalse(Regex("\\bill\\b").containsMatchIn(all))
        }
        assertEquals(PublicExport.files(listOf(r)), PublicExport.files(listOf(r)))
    }

    @Test fun publicHyroxRoundTrip() {
        val a = session("a", 0, StationLog("sled_push", 102.5, 50, null, 72, 8), StationLog("wall_balls", 6.0, null, 100, 400, 9), StationLog("run", null, 400, null, 151, 6))
        val b = session("b", 40, StationLog("row", null, 1000, null, 245, 7))
        val back = PublicImport.records(PublicExport.files(listOf(a, b))).filter { it.type == SessionType.HYROX }
        assertEquals(2, back.size)
        assertEquals(a.stations, back[0].stations)
        assertEquals(b.stations, back[1].stations)
        assertEquals(a.date, back[0].date)
        assertEquals(Feeling.GOOD, back[0].feeling)
        assertNull(back[0].kneePain)
    }

    // ---- rewards

    @Test fun xpAndBadges() {
        val today = day(30)
        val none = Rewards.summary(emptyList(), today)
        assertFalse(none.badges.first { it.id == "hyrox_first_station" }.unlocked)
        assertEquals(listOf("hyrox_first_station", "hyrox_all_eight", "hyrox_race_standard"), none.badges.takeLast(3).map { it.id })
        assertEquals("streak_4", none.badges[none.badges.size - 4].id)

        val r1 = session("a", 0, StationLog("row", distanceM = 600, rpe = 7), StationLog("skierg", distanceM = 400, rpe = 7))
        val xp1 = Rewards.sessionXp(r1, emptyList())
        assertEquals(80, xp1.base)
        assertEquals(30, xp1.effort)
        assertEquals(20, xp1.variety) // two new stations
        assertEquals(0, xp1.race)
        val r2 = session("b", 7, StationLog("row", distanceM = 1000, rpe = 7))
        val xp2 = Rewards.sessionXp(r2, listOf(r1))
        assertEquals(15, xp2.effort)
        assertEquals(0, xp2.variety)
        assertEquals(Rewards.HYROX_RACE_STANDARD, xp2.race)
        assertEquals(80 + 15 + 30, xp2.total)

        val s = Rewards.summary(listOf(r1, r2), today)
        assertEquals(day(0), s.badges.first { it.id == "hyrox_first_station" }.unlockedOn)
        assertEquals(day(7), s.badges.first { it.id == "hyrox_race_standard" }.unlockedOn)
        assertFalse(s.badges.first { it.id == "hyrox_all_eight" }.unlocked)
    }

    @Test fun allEightBadge() {
        val recs = HyroxStation.entries.mapIndexed { i, st -> session("s$i", i, StationLog(st.id, distanceM = 10, rpe = 6)) }
        val b = Rewards.summary(recs, day(30)).badges
        assertEquals(day(7), b.first { it.id == "hyrox_all_eight" }.unlockedOn)
        // Not race standard: short distances.
        assertFalse(b.first { it.id == "hyrox_race_standard" }.unlocked)
    }

    @Test fun raceStandardFollowsDivisionAndOverrides() {
        val rec = session("a", 0, StationLog("sled_push", 102.0, 50, null, 80, 8))
        assertFalse(Rewards.summary(listOf(rec), day(1)).badges.first { it.id == "hyrox_race_standard" }.unlocked)
        val women = UserSettings(hyroxDivision = HyroxDivision.WOMEN_OPEN)
        assertTrue(Rewards.summary(listOf(rec), day(1), women).badges.first { it.id == "hyrox_race_standard" }.unlocked)
        val edited = UserSettings(hyroxWeightOverrides = mapOf("sled_push" to 100.0))
        assertTrue(Rewards.summary(listOf(rec), day(1), edited).badges.first { it.id == "hyrox_race_standard" }.unlocked)
    }

    @Test fun stationPersonalRecords() {
        val a = session("a", 0, StationLog("sled_push", 100.0, 50, null, 70, 8), StationLog("row", null, 1000, null, 250, 7))
        val b = session("b", 7, StationLog("sled_push", 110.0, 50, null, 90, 8), StationLog("row", null, 1000, null, 240, 7))
        val c = session("c", 14, StationLog("sled_push", 105.0, 50, null, 60, 8), StationLog("row", null, 1000, null, 245, 7))
        val prs = Rewards.summary(listOf(a, b, c), day(20)).prs
        assertTrue(prs.none { it.sessionId == "a" })
        val bp = prs.filter { it.sessionId == "b" }.map { it.text }
        assertEquals(2, bp.size, bp.toString())
        assertTrue(bp.any { "heaviest yet at 110 kg" in it })
        assertTrue(bp.any { "4:00" in it && "4:10" in it })
        // c: 105 kg in 60 s beats no heavier-or-equal prior time? 110 kg took 90 s, so yes it is a time PR; row 245 is slower.
        val cp = prs.filter { it.sessionId == "c" }.map { it.text }
        assertEquals(1, cp.size, cp.toString())
        assertTrue(cp[0].contains("1:00"))
        assertTrue(Rewards.summary(listOf(a, b, c), day(20)).prSessionIds.containsAll(setOf("b", "c")))
    }

    @Test fun existingBadgeOrderUnchanged() {
        val ids = Rewards.summary(emptyList(), day(0)).badges.map { it.id }
        assertEquals(
            listOf(
                "first_session", "first_gym", "first_treadmill", "first_pr", "sessions_5", "sessions_10", "sessions_25", "sessions_50",
                "squat_100", "comeback", "all_styles", "long_walker", "early_week", "quiet_hero", "streak_4",
            ),
            ids.take(15),
        )
    }

    // ---- progress stats

    @Test fun stationBestSeries() {
        val today = LocalDate.parse("2026-11-19") // Thursday
        fun rec(id: String, d: String, vararg l: StationLog) = SessionRecord(id, LocalDate.parse(d), SessionType.HYROX, 30, 30, Feeling.GOOD, stations = l.toList())
        val recs = listOf(
            rec("1", "2026-11-02", StationLog("row", distanceM = 1000, seconds = 250)),
            rec("2", "2026-11-04", StationLog("row", distanceM = 500, seconds = 118), StationLog("sled_push", 100.0, 50, null, 70)),
            rec("3", "2026-11-17", StationLog("row", distanceM = 1000, seconds = 241)),
        )
        val series = ProgressStats.stationBest(recs, HyroxStation.ROW, today, weeks = 4)
        assertEquals(4, series.size)
        assertEquals(LocalDate.parse("2026-11-16"), series.last().weekStart)
        // Week of Nov 2: 250 s/1000 m = 0.25 vs 118 s/500 m = 0.236 -> the 500 m piece has the faster pace.
        assertEquals(118, series[1].bestSeconds); assertEquals(500, series[1].volume)
        assertEquals(241, series[3].bestSeconds); assertEquals(1000, series[3].volume)
        assertNull(series[0].bestSeconds); assertEquals(0, series[2].logs)
        val sled = ProgressStats.stationBest(recs, HyroxStation.SLED_PUSH, today, weeks = 4)
        assertEquals(100.0, sled[1].heaviestKg)
        assertNull(sled[3].heaviestKg)
    }
}
