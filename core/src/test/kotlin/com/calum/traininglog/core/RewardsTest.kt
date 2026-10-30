package com.calum.traininglog.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 2026-11-02 is a Monday.
private val mon = LocalDate.of(2026, 11, 2)

private var seq = 0
private fun gym(
    date: LocalDate, sets: List<LoggedSet> = listOf(LoggedSet(80.0, 5, 5, 2)), ex: String = "squat",
    feeling: Feeling = Feeling.GOOD, minutes: Int = 45, id: String = "g${seq++}",
) = SessionRecord(id, date, SessionType.GYM, 60, minutes, feeling, exercises = listOf(ExerciseLog(ex, sets)))

private fun tm(
    date: LocalDate, minutes: Int = 30, style: TreadmillStyle = TreadmillStyle.STEADY,
    feeling: Feeling = Feeling.GOOD, id: String = "t${seq++}",
) = SessionRecord(id, date, SessionType.TREADMILL, minutes, minutes, feeling,
    treadmill = TreadmillLog(style, minutes * 60, minutes * 60))

private fun other(date: LocalDate, type: SessionType, minutes: Int, id: String = "o${seq++}") =
    SessionRecord(id, date, type, minutes, minutes, Feeling.OKAY)

private fun sets(n: Int, kg: Double = 80.0, reps: Int = 5, rir: Int? = 2) = List(n) { LoggedSet(kg, reps, 5, rir) }

class RewardsXpTest {
    @Test fun gymXp() {
        val r = gym(mon, sets(3))
        val x = Rewards.sessionXp(r, emptyList())
        assertEquals(100, x.base)
        assertEquals(24, x.effort)
        assertEquals(10, x.rir)
        assertEquals(0, x.pr)
        assertEquals(134, x.total)
    }

    @Test fun rirBonusNeedsEverySet() {
        val r = gym(mon, listOf(LoggedSet(80.0, 5, 5, 2), LoggedSet(80.0, 5, 5, null)))
        assertEquals(0, Rewards.sessionXp(r, emptyList()).rir)
    }

    @Test fun gymSetCap() {
        val x = Rewards.sessionXp(gym(mon, sets(40, rir = null)), emptyList())
        assertEquals(160, x.effort)
    }

    @Test fun treadmillXpAndCap() {
        assertEquals(40 + 45, Rewards.sessionXp(tm(mon, 30), emptyList()).let { it.base + it.effort })
        assertEquals(40 + 135, Rewards.sessionXp(tm(mon, 120), emptyList()).let { it.base + it.effort })
    }

    @Test fun coreAndHike() {
        assertEquals(40 + 30, Rewards.sessionXp(other(mon, SessionType.CORE, 20), emptyList()).total)
        assertEquals(40 + 45, Rewards.sessionXp(other(mon, SessionType.CORE, 60), emptyList()).total)
        assertEquals(40 + 180, Rewards.sessionXp(other(mon, SessionType.HIKE, 200), emptyList()).total)
    }

    @Test fun varietyBonusWindow() {
        val styles = listOf(TreadmillStyle.STEADY, TreadmillStyle.PYRAMID, TreadmillStyle.LADDER)
        val prior = styles.mapIndexed { i, s -> tm(mon.plusDays(i.toLong()), style = s) }
        val day = mon.plusDays(5)
        assertEquals(0, Rewards.sessionXp(tm(day, style = TreadmillStyle.STEADY), prior).variety)
        assertEquals(15, Rewards.sessionXp(tm(day, style = TreadmillStyle.INTERVALS), prior).variety)
        // Steady falls out of the last-3 window after another style is added.
        val prior4 = prior + tm(mon.plusDays(4), style = TreadmillStyle.INTERVALS)
        assertEquals(15, Rewards.sessionXp(tm(day, style = TreadmillStyle.STEADY), prior4).variety)
    }

    @Test fun prAddsBonus() {
        val first = gym(mon, sets(3, 80.0))
        val second = gym(mon.plusDays(2), sets(3, 85.0))
        assertEquals(25, Rewards.sessionXp(second, listOf(first)).pr)
        assertEquals(0, Rewards.sessionXp(first, emptyList()).pr)
    }

    @Test fun summaryTotalsSessionXp() {
        val rs = listOf(gym(mon, sets(3)), tm(mon.plusDays(1), 30))
        val s = Rewards.summary(rs, mon.plusDays(1))
        assertEquals(134 + 40 + 45 + 15, s.xp)
        assertEquals(s.xp, s.sessionXp.values.sumOf { it.total })
    }

    @Test fun futureSessionsIgnored() {
        val s = Rewards.summary(listOf(gym(mon.plusDays(10))), mon)
        assertEquals(0, s.xp)
        assertEquals(0, s.totalSessions)
    }
}

class RewardsLevelTest {
    @Test fun boundaries() {
        assertEquals(1, Rewards.levelFor(0))
        assertEquals(1, Rewards.levelFor(199))
        assertEquals(2, Rewards.levelFor(200))
        assertEquals(2, Rewards.levelFor(449))
        assertEquals(3, Rewards.levelFor(450))
        assertEquals(4, Rewards.levelFor(750))
    }

    @Test fun spansIncreaseGently() {
        for (l in 1..30) assertTrue(Rewards.levelSpan(l + 1) > Rewards.levelSpan(l))
        assertEquals(200, Rewards.levelSpan(1))
    }

    @Test fun namesAndOverflow() {
        assertEquals(12, Rewards.levelNames.size)
        assertEquals("Warm-up", Rewards.levelName(1))
        assertEquals("Iron Dad", Rewards.levelName(4))
        assertEquals("Evergreen", Rewards.levelName(40))
    }

    @Test fun summaryProgressFields() {
        val s = Rewards.summary(listOf(gym(mon, sets(3)), gym(mon.plusDays(1), sets(3))), mon.plusDays(1))
        assertEquals(268, s.xp)
        assertEquals(2, s.level)
        assertEquals("Regular", s.levelName)
        assertEquals(68, s.xpIntoLevel)
        assertEquals(250, s.xpForNextLevel)
        assertTrue(s.levelFraction in 0.0..1.0)
    }
}

class RewardsPrTest {
    @Test fun firstTimeIsBaselineNotPr() {
        val s = Rewards.summary(listOf(gym(mon, sets(3, 80.0))), mon)
        assertTrue(s.prs.isEmpty())
    }

    @Test fun heaviestWeightPr() {
        val a = gym(mon, sets(3, 80.0), id = "a")
        val b = gym(mon.plusDays(2), sets(3, 85.0), id = "b")
        val s = Rewards.summary(listOf(b, a), mon.plusDays(2))
        assertEquals(1, s.prs.size)
        assertEquals("squat", s.prs[0].exerciseId)
        assertEquals("b", s.prs[0].sessionId)
        assertTrue(s.prs[0].text.contains("85"))
        assertEquals(setOf("b"), s.prSessionIds)
    }

    @Test fun repsAtWeightPr() {
        val a = gym(mon, listOf(LoggedSet(80.0, 5, 5)), id = "a")
        val b = gym(mon.plusDays(2), listOf(LoggedSet(80.0, 6, 5)), id = "b")
        val s = Rewards.summary(listOf(a, b), mon.plusDays(2))
        assertEquals(1, s.prs.size)
        assertTrue(s.prs[0].text.contains("6 reps at 80"))
        assertTrue(s.prs[0].text.contains("was 5"))
    }

    @Test fun equalIsNotPrAndLighterIsNotPr() {
        val a = gym(mon, listOf(LoggedSet(80.0, 5, 5)))
        val b = gym(mon.plusDays(1), listOf(LoggedSet(80.0, 5, 5)))
        val c = gym(mon.plusDays(2), listOf(LoggedSet(70.0, 5, 5)))
        assertTrue(Rewards.summary(listOf(a, b, c), mon.plusDays(2)).prs.isEmpty())
    }

    @Test fun oneSessionPrPerExercise() {
        val a = gym(mon, sets(1, 80.0))
        val b = gym(mon.plusDays(1), listOf(LoggedSet(85.0, 5, 5), LoggedSet(90.0, 5, 5)), id = "b")
        val s = Rewards.summary(listOf(a, b), mon.plusDays(1))
        assertEquals(1, s.prs.size)
        assertTrue(s.prs[0].text.contains("90"))
    }

    @Test fun separatePerExercise() {
        val a = gym(mon, sets(1, 40.0), ex = "lat_pulldown")
        val b = gym(mon.plusDays(1), sets(1, 80.0), ex = "squat")
        assertTrue(Rewards.summary(listOf(a, b), mon.plusDays(1)).prs.isEmpty())
    }
}

class RewardsRingTest {
    @Test fun emptyWeek() {
        val r = Rewards.summary(emptyList(), mon).ring
        assertEquals(0.0, r.progress)
        assertEquals(0.0, r.bonus)
        assertFalse(r.goodWeek)
    }

    @Test fun partialProgress() {
        val r = Rewards.summary(listOf(gym(mon), tm(mon.plusDays(1))), mon.plusDays(2)).ring
        assertEquals(2.0 / 5, r.progress, 1e-9)
        assertEquals(0.0, r.bonus)
    }

    @Test fun goodWeekAndBonus() {
        val rs = listOf(gym(mon), gym(mon.plusDays(1)), tm(mon), tm(mon.plusDays(1)), tm(mon.plusDays(2)))
        val r = Rewards.summary(rs, mon.plusDays(3)).ring
        assertEquals(1.0, r.progress)
        assertEquals(0.0, r.bonus)
        assertTrue(r.goodWeek)
        val more = rs + gym(mon.plusDays(3)) + tm(mon.plusDays(3))
        val r2 = Rewards.summary(more, mon.plusDays(3)).ring
        assertEquals(1.0, r2.progress)
        assertEquals(1.0, r2.bonus)
    }

    @Test fun clampsBeyondStretch() {
        val rs = List(6) { gym(mon.plusDays(it.toLong() % 3)) } + List(8) { tm(mon.plusDays(it.toLong() % 3)) }
        val r = Rewards.summary(rs, mon.plusDays(3)).ring
        assertEquals(1.0, r.progress)
        assertEquals(1.0, r.bonus)
    }

    @Test fun onlyCurrentWeekCounts() {
        val r = Rewards.summary(listOf(gym(mon.minusDays(1)), tm(mon.minusDays(3))), mon).ring
        assertEquals(0.0, r.progress)
    }
}

class RewardsStreakTest {
    private fun wk(n: Int) = mon.plusWeeks(n.toLong())

    @Test fun noSessions() {
        val s = Rewards.summary(emptyList(), mon)
        assertEquals(0, s.activeWeekStreak)
        assertEquals(0, s.longestStreak)
        assertEquals(0, s.restTokens)
    }

    @Test fun consecutiveWeeks() {
        val s = Rewards.summary(listOf(gym(wk(0)), gym(wk(1)), gym(wk(2))), wk(2).plusDays(1))
        assertEquals(3, s.activeWeekStreak)
        assertEquals(3, s.longestStreak)
    }

    @Test fun currentWeekInProgressDoesNotReset() {
        val s = Rewards.summary(listOf(gym(wk(0)), gym(wk(1))), wk(2).plusDays(4))
        assertEquals(2, s.activeWeekStreak)
        assertEquals(0, s.restTokens)
    }

    @Test fun emptyPastWeekWithoutTokenResets() {
        val s = Rewards.summary(listOf(gym(wk(0)), gym(wk(1)), gym(wk(3))), wk(3).plusDays(1))
        assertEquals(1, s.activeWeekStreak)
        assertEquals(2, s.longestStreak)
    }

    @Test fun tokenEarnedEveryFourActiveWeeks() {
        val s = Rewards.summary((0..3).map { gym(wk(it)) }, wk(3).plusDays(1))
        assertEquals(4, s.activeWeekStreak)
        assertEquals(1, s.restTokens)
        val capped = Rewards.summary((0..15).map { gym(wk(it)) }, wk(15))
        assertEquals(2, capped.restTokens)
    }

    @Test fun emptyWeekConsumesTokenAndKeepsStreak() {
        val rs = (0..3).map { gym(wk(it)) } + gym(wk(5))
        val s = Rewards.summary(rs, wk(5).plusDays(1))
        // 4 active weeks, 1 bridged by a token (not counted), then the new week.
        assertEquals(5, s.activeWeekStreak)
        assertEquals(5, s.longestStreak)
        assertEquals(0, s.restTokens)
    }

    @Test fun tokensRunOutOnLongBreak() {
        val rs = (0..3).map { gym(wk(it)) } + gym(wk(7)) // 1 token, 3 empty weeks
        assertEquals(1, Rewards.summary(rs, wk(7)).activeWeekStreak)
    }

    @Test fun twoTokensBridgeTwoWeeks() {
        val rs = (0..7).map { gym(wk(it)) } + gym(wk(10)) // 8 active weeks -> 2 tokens, 2 empty weeks
        val s = Rewards.summary(rs, wk(10))
        assertEquals(9, s.activeWeekStreak)
        assertEquals(0, s.restTokens)
    }

    @Test fun inProgressWeekNeverConsumesToken() {
        val s = Rewards.summary((0..3).map { gym(wk(it)) }, wk(4).plusDays(6))
        assertEquals(4, s.activeWeekStreak)
        assertEquals(1, s.restTokens)
    }
}

class RewardsBadgeTest {
    private fun badge(rs: List<SessionRecord>, id: String, today: LocalDate = rs.maxOf { it.date }) =
        Rewards.summary(rs, today).badges.first { it.id == id }

    @Test fun fifteenBadgesAllLockedWhenEmpty() {
        val s = Rewards.summary(emptyList(), mon)
        assertEquals(15, s.badges.size)
        assertEquals(15, s.badges.map { it.id }.toSet().size)
        assertTrue(s.badges.all { !it.unlocked })
    }

    @Test fun firstsAndCounts() {
        val rs = (0 until 10).map { tm(mon.plusDays(it.toLong())) }
        assertEquals(mon, badge(rs, "first_session").unlockedOn)
        assertNull(badge(rs, "first_gym").unlockedOn)
        assertEquals(mon, badge(rs, "first_treadmill").unlockedOn)
        assertEquals(mon.plusDays(4), badge(rs, "sessions_5").unlockedOn)
        assertEquals(mon.plusDays(9), badge(rs, "sessions_10").unlockedOn)
        assertNull(badge(rs, "sessions_25").unlockedOn)
    }

    @Test fun squat100() {
        assertNull(badge(listOf(gym(mon, sets(3, 95.0))), "squat_100").unlockedOn)
        val d = mon.plusDays(3)
        assertEquals(d, badge(listOf(gym(mon, sets(3, 95.0)), gym(d, sets(1, 100.0))), "squat_100").unlockedOn)
        assertNull(badge(listOf(gym(mon, sets(1, 100.0), ex = "rdl")), "squat_100").unlockedOn)
    }

    @Test fun firstPrBadge() {
        val rs = listOf(gym(mon, sets(1, 80.0)), gym(mon.plusDays(2), sets(1, 82.5)))
        assertEquals(mon.plusDays(2), badge(rs, "first_pr").unlockedOn)
    }

    @Test fun comeback() {
        assertNull(badge(listOf(gym(mon), gym(mon.plusDays(13))), "comeback").unlockedOn)
        val back = mon.plusDays(14)
        assertEquals(back, badge(listOf(gym(mon), gym(back)), "comeback").unlockedOn)
    }

    @Test fun allStyles() {
        val styles = TreadmillStyle.entries
        val rs = styles.mapIndexed { i, s -> tm(mon.plusDays(i.toLong()), style = s) }
        assertEquals(mon.plusDays(styles.size - 1L), badge(rs, "all_styles").unlockedOn)
        assertNull(badge(rs.dropLast(1), "all_styles").unlockedOn)
    }

    @Test fun longWalker() {
        assertNull(badge(listOf(tm(mon, 89)), "long_walker").unlockedOn)
        assertNotNull(badge(listOf(tm(mon, 90)), "long_walker").unlockedOn)
    }

    @Test fun earlyWeek() {
        val ok = listOf(gym(mon), gym(mon.plusDays(1)), tm(mon), tm(mon.plusDays(2)), tm(mon.plusDays(4)))
        assertEquals(mon.plusDays(4), badge(ok, "early_week").unlockedOn)
        val late = listOf(gym(mon), gym(mon.plusDays(1)), tm(mon), tm(mon.plusDays(2)), tm(mon.plusDays(5)))
        assertNull(badge(late, "early_week").unlockedOn)
    }

    @Test fun quietHero() {
        assertNotNull(badge(listOf(tm(mon, 25, feeling = Feeling.ROUGH)), "quiet_hero").unlockedOn)
        assertNull(badge(listOf(tm(mon, 45, feeling = Feeling.ROUGH)), "quiet_hero").unlockedOn)
        assertNull(badge(listOf(tm(mon, 20, feeling = Feeling.GOOD)), "quiet_hero").unlockedOn)
    }

    @Test fun streakBadge() {
        val rs = (0..3).map { gym(mon.plusWeeks(it.toLong())) }
        assertEquals(mon.plusWeeks(3), badge(rs, "streak_4").unlockedOn)
        assertNull(badge(rs.take(3), "streak_4").unlockedOn)
    }
}

class RewardsCelebrationTest {
    @Test fun deterministic() {
        val r = gym(mon, sets(3), id = "same")
        assertEquals(Rewards.celebration(r, emptyList()), Rewards.celebration(r, emptyList()))
    }

    @Test fun xpMessageFirstWithBreakdownAndMaxThree() {
        val r = gym(mon, sets(3), id = "x1")
        val msgs = Rewards.celebration(r, emptyList())
        assertTrue(msgs.size in 1..3)
        assertTrue(msgs[0].contains("134"))
        assertTrue(msgs[0].contains("Session 100"))
        assertTrue(msgs[0].contains("Effort 24"))
    }

    @Test fun mentionsLevelUpAndBadge() {
        val prior = listOf(gym(mon, sets(3, 80.0), id = "p1"))
        val r = gym(mon.plusDays(2), sets(3, 100.0), id = "p2")
        val msgs = Rewards.celebration(r, prior)
        assertTrue(msgs.size <= 3)
        assertTrue(msgs.any { it.contains("Regular") })
    }

    @Test fun prShownWhenNothingElseCompetes() {
        val prior = (0 until 2).map { gym(mon.minusDays(30L - it), sets(1, 80.0, 5, null), id = "q$it") }
        val r = gym(mon, sets(1, 82.5, 5, null), id = "pr1")
        val msgs = Rewards.celebration(r, prior)
        assertTrue(msgs.any { it.contains("82.5") })
    }

    @Test fun wordingVariesAcrossIds() {
        val variants = (0 until 60).map { Rewards.celebration(gym(mon, sets(3), id = "id$it"), emptyList())[0].substringBefore(" (") }
        assertTrue(variants.map { it.replace(Regex("\\d+"), "N") }.toSet().size >= 4)
    }

    @Test fun ignoresDuplicateOfSelfInBefore() {
        val r = gym(mon, sets(3), id = "dup")
        assertEquals(Rewards.celebration(r, emptyList()), Rewards.celebration(r, listOf(r)))
    }
}

class RewardsSettingsTest {
    private val fri = mon.plusDays(4)

    @Test fun earlyWeekBadgeFollowsTargets() {
        val recs = listOf(gym(mon), tm(mon.plusDays(1)), tm(mon.plusDays(2)), tm(mon.plusDays(3)), gym(mon.plusDays(3)))
        fun early(s: UserSettings) = Rewards.summary(recs, fri, s).badges.first { it.id == "early_week" }
        assertTrue(early(UserSettings()).unlocked)
        assertFalse(early(UserSettings(gymTarget = 3)).unlocked)
        val light = UserSettings(gymTarget = 1, treadmillTarget = 1)
        assertEquals(mon.plusDays(1), early(light).unlockedOn)
        assertTrue(early(light).description.contains("1 gym + 1 treadmill"))
        assertTrue(early(UserSettings()).description.contains("2 gym + 3 treadmill"))
    }

    @Test fun ringFollowsTargets() {
        val recs = listOf(gym(mon), tm(mon.plusDays(1)))
        assertFalse(Rewards.summary(recs, fri).ring.goodWeek)
        assertTrue(Rewards.summary(recs, fri, UserSettings(gymTarget = 1, treadmillTarget = 1)).ring.goodWeek)
    }

    @Test fun celebrationAcceptsSettings() {
        val msgs = Rewards.celebration(gym(mon.plusDays(1)), listOf(tm(mon)), UserSettings(gymTarget = 1, treadmillTarget = 1))
        assertTrue(msgs.isNotEmpty())
    }
}
