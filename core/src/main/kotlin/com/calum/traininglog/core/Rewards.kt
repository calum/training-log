package com.calum.traininglog.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.min

/**
 * Rewards and motivation engine. Pure and deterministic: everything is derived from the session history.
 *
 * Design rules (no guilt):
 *  - Only effort is rewarded. Nothing is ever deducted and no screen needs to show a deficit.
 *  - Rings and streaks never go negative; an empty week is simply a week with nothing in it.
 *  - Variable positive feedback: celebration wording varies (deterministically per session).
 */

/** XP earned by one session, split so the finish screen can show where it came from. */
data class XpBreakdown(
    val base: Int,
    /** Working sets (gym) or minutes (everything else). */
    val effort: Int,
    val variety: Int,
    val rir: Int,
    val pr: Int,
) {
    val total: Int get() = base + effort + variety + rir + pr

    /** Human readable parts, only the non-zero ones, e.g. "Session 100", "Sets 48", "PR 25". */
    val lines: List<String>
        get() = buildList {
            add("Session $base")
            if (effort > 0) add("Effort $effort")
            if (variety > 0) add("New style $variety")
            if (rir > 0) add("RIR logged $rir")
            if (pr > 0) add("PR $pr")
        }
}

data class PersonalRecord(val date: LocalDate, val exerciseId: String, val text: String, val sessionId: String = "")

data class Badge(val id: String, val title: String, val description: String, val unlockedOn: LocalDate?) {
    val unlocked: Boolean get() = unlockedOn != null
}

/**
 * Progress toward the "good week" (2 gym + 3 treadmill) for the current Mon-Sun week.
 * [progress] is 0..1 toward the target. [bonus] is 0..1 past the target up to the stretch ceiling
 * (3 gym + 4 treadmill). Both are clamped; there is no negative value.
 */
data class WeeklyRing(
    val gym: Int,
    val treadmill: Int,
    val gymTarget: Int,
    val treadmillTarget: Int,
    val progress: Double,
    val bonus: Double,
) {
    val goodWeek: Boolean get() = progress >= 1.0
}

/**
 * [xpIntoLevel] is XP earned inside the current level; [xpForNextLevel] is the size of the current level
 * (XP needed to go from its start to the next level). `xpIntoLevel / xpForNextLevel` is the level bar fraction.
 *
 * Streak: "active weeks in a row" (Mon-Sun weeks). See [Rewards.streakFor] for the exact rules.
 */
data class RewardsSummary(
    val xp: Int,
    val level: Int,
    val levelName: String,
    val xpIntoLevel: Int,
    val xpForNextLevel: Int,
    val prs: List<PersonalRecord>,
    /** Ids of sessions that set at least one PR, so history rows can be marked. */
    val prSessionIds: Set<String>,
    val ring: WeeklyRing,
    val activeWeekStreak: Int,
    val longestStreak: Int,
    val restTokens: Int,
    val badges: List<Badge>,
    val sessionXp: Map<String, XpBreakdown>,
    val totalSessions: Int,
) {
    val levelFraction: Double get() = if (xpForNextLevel <= 0) 0.0 else (xpIntoLevel.toDouble() / xpForNextLevel).coerceIn(0.0, 1.0)
    val unlockedBadges: List<Badge> get() = badges.filter { it.unlocked }
}

object Rewards {
    const val GYM_BASE = 100
    const val GYM_PER_SET = 8
    const val GYM_SET_CAP = 20
    const val OTHER_BASE = 40
    const val PER_MINUTE = 1.5
    const val TREADMILL_MINUTE_CAP = 90
    const val CORE_MINUTE_CAP = 30
    const val HIKE_MINUTE_CAP = 120
    const val VARIETY_BONUS = 15
    const val RIR_BONUS = 10
    const val PR_BONUS = 25
    const val VARIETY_WINDOW = 3

    const val GYM_TARGET = 2
    const val TREADMILL_TARGET = 3
    const val GYM_STRETCH = 3
    const val TREADMILL_STRETCH = 4

    const val TOKEN_EVERY_ACTIVE_WEEKS = 4
    const val MAX_TOKENS = 2
    const val COMEBACK_GAP_DAYS = 14L

    val levelNames = listOf(
        "Warm-up", "Regular", "Showing Up", "Iron Dad", "Hill Climber", "Steady Engine",
        "Plate Mover", "Trail Blazer", "Tireless", "Mountain Goat", "Iron Legend", "Evergreen",
    )

    /** Size of the span from level [level] to [level]+1: 200, 250, 300 ... (gently increasing). */
    fun levelSpan(level: Int): Int = 150 + 50 * level

    /** Total XP at which [level] starts. Level 1 starts at 0. */
    fun levelStart(level: Int): Int = (1 until level).sumOf { levelSpan(it) }

    fun levelFor(xp: Int): Int {
        var level = 1
        var start = 0
        while (xp >= start + levelSpan(level)) {
            start += levelSpan(level)
            level++
        }
        return level
    }

    fun levelName(level: Int): String = levelNames[(level - 1).coerceIn(0, levelNames.lastIndex)]

    // ---------------------------------------------------------------- summary

    fun summary(records: List<SessionRecord>, today: LocalDate): RewardsSummary {
        val sorted = records.filter { it.date <= today }.sortedBy { it.date }
        val a = analyse(sorted)
        val xp = a.xp.values.sumOf { it.total }
        val level = levelFor(xp)
        val streak = streakFor(sorted, today)
        return RewardsSummary(
            xp = xp,
            level = level,
            levelName = levelName(level),
            xpIntoLevel = xp - levelStart(level),
            xpForNextLevel = levelSpan(level),
            prs = a.prs,
            prSessionIds = a.prs.map { it.sessionId }.toSet(),
            ring = ring(sorted, today),
            activeWeekStreak = streak.streak,
            longestStreak = streak.longest,
            restTokens = streak.tokens,
            badges = badges(sorted, a, streak),
            sessionXp = a.xp,
            totalSessions = sorted.size,
        )
    }

    /** XP for one session given the sessions before it (the record is placed after them). */
    fun sessionXp(record: SessionRecord, before: List<SessionRecord>): XpBreakdown {
        val prior = before.filter { it.id != record.id && it.date <= record.date }.sortedBy { it.date }
        return analyse(prior + record).xp.getValue(record.id)
    }

    // ---------------------------------------------------------------- weekly ring

    fun ring(sorted: List<SessionRecord>, today: LocalDate): WeeklyRing {
        val w = Stats.week(sorted.filter { it.date <= today }, today)
        val gymDone = min(w.gym, GYM_TARGET)
        val tmDone = min(w.treadmill, TREADMILL_TARGET)
        val progress = (gymDone + tmDone).toDouble() / (GYM_TARGET + TREADMILL_TARGET)
        val bonusCap = (GYM_STRETCH - GYM_TARGET) + (TREADMILL_STRETCH - TREADMILL_TARGET)
        val bonusDone = (min(w.gym, GYM_STRETCH) - gymDone) + (min(w.treadmill, TREADMILL_STRETCH) - tmDone)
        return WeeklyRing(
            gym = w.gym,
            treadmill = w.treadmill,
            gymTarget = GYM_TARGET,
            treadmillTarget = TREADMILL_TARGET,
            progress = progress.coerceIn(0.0, 1.0),
            bonus = (bonusDone.toDouble() / bonusCap).coerceIn(0.0, 1.0),
        )
    }

    // ---------------------------------------------------------------- streak

    internal data class StreakResult(val streak: Int, val longest: Int, val tokens: Int, val fourWeekOn: LocalDate?)

    private fun weekStart(date: LocalDate): LocalDate = Stats.weekOf(date).start

    /**
     * Active-weeks streak with rest tokens.
     *  - A Mon-Sun week is active if at least one session (any type) was logged in it.
     *  - Active week: streak +1. Every 4th active week ever earns 1 rest token (max 2 banked).
     *  - A fully empty PAST week while a streak is running automatically spends 1 token and the streak is
     *    kept (it is not extended). With no token, the streak restarts from zero; nothing else is lost.
     *  - The current week still in progress never counts against you: empty so far means no change at all.
     *  - Counting starts at the first week with a session, so a brand new user has no empty weeks.
     */
    internal fun streakFor(sorted: List<SessionRecord>, today: LocalDate): StreakResult {
        if (sorted.isEmpty()) return StreakResult(0, 0, 0, null)
        val byWeek = sorted.groupBy { weekStart(it.date) }
        val currentWeek = weekStart(today)
        var week = weekStart(sorted.first().date)
        var streak = 0
        var longest = 0
        var tokens = 0
        var activeTotal = 0
        var fourOn: LocalDate? = null
        while (week <= currentWeek) {
            val sessions = byWeek[week]
            if (sessions != null) {
                streak++
                activeTotal++
                if (activeTotal % TOKEN_EVERY_ACTIVE_WEEKS == 0) tokens = min(MAX_TOKENS, tokens + 1)
                if (streak > longest) longest = streak
                if (streak >= 4 && fourOn == null) fourOn = sessions.maxOf { it.date }
            } else if (week < currentWeek && streak > 0) {
                if (tokens > 0) tokens-- else streak = 0
            }
            week = week.plusWeeks(1)
        }
        return StreakResult(streak, longest, tokens, fourOn)
    }

    // ---------------------------------------------------------------- XP and PR analysis

    private data class Analysis(val xp: Map<String, XpBreakdown>, val prs: List<PersonalRecord>)

    private fun analyse(sorted: List<SessionRecord>): Analysis {
        val bestWeight = HashMap<String, Double>()
        val bestReps = HashMap<String, HashMap<Double, Int>>()
        val recentStyles = ArrayDeque<TreadmillStyle>()
        val xp = LinkedHashMap<String, XpBreakdown>()
        val prs = mutableListOf<PersonalRecord>()

        for (rec in sorted) {
            val found = mutableListOf<PersonalRecord>()
            for (log in rec.exercises) {
                val sets = log.sets.filter { it.reps > 0 }
                if (sets.isEmpty()) continue
                val id = log.exerciseId
                val name = ExerciseLibrary.all.firstOrNull { it.id == id }?.name ?: id
                val known = bestWeight.containsKey(id)
                val prevMax = bestWeight[id] ?: 0.0
                val repsAt = bestReps.getOrPut(id) { HashMap() }
                val sessMax = sets.maxOf { it.weightKg }
                if (known) {
                    if (sessMax > prevMax && sessMax > 0.0) {
                        found += PersonalRecord(rec.date, id, "$name: heaviest yet at ${num(sessMax)} kg", rec.id)
                    } else {
                        val repPr = sets.groupBy { it.weightKg }
                            .mapValues { (_, v) -> v.maxOf { it.reps } }
                            .filter { (w, r) -> repsAt[w]?.let { r > it } == true }
                            .maxByOrNull { it.key }
                        if (repPr != null) {
                            found += PersonalRecord(
                                rec.date, id,
                                "$name: ${repPr.value} reps at ${num(repPr.key)} kg (was ${repsAt.getValue(repPr.key)})",
                                rec.id,
                            )
                        }
                    }
                }
                bestWeight[id] = maxOf(prevMax, sessMax)
                for (s in sets) repsAt[s.weightKg] = maxOf(repsAt[s.weightKg] ?: 0, s.reps)
            }
            prs += found

            var variety = 0
            if (rec.type == SessionType.TREADMILL) {
                rec.treadmill?.let { t ->
                    if (t.style !in recentStyles) variety = VARIETY_BONUS
                    recentStyles.addLast(t.style)
                    while (recentStyles.size > VARIETY_WINDOW) recentStyles.removeFirst()
                }
            }
            xp[rec.id] = baseXp(rec).copy(variety = variety, pr = if (found.isNotEmpty()) PR_BONUS else 0)
        }
        return Analysis(xp, prs)
    }

    private fun baseXp(rec: SessionRecord): XpBreakdown {
        return when (rec.type) {
            SessionType.GYM -> {
                val setCount = rec.exercises.sumOf { e -> e.sets.count { it.reps > 0 } }
                val allSets = rec.exercises.flatMap { it.sets }
                val rir = if (allSets.isNotEmpty() && allSets.all { it.rir != null }) RIR_BONUS else 0
                XpBreakdown(GYM_BASE, min(setCount, GYM_SET_CAP) * GYM_PER_SET, 0, rir, 0)
            }
            SessionType.TREADMILL -> {
                val minutes = rec.treadmill?.let { it.completedSec / 60 } ?: rec.minutesActual
                XpBreakdown(OTHER_BASE, minuteXp(minutes, TREADMILL_MINUTE_CAP), 0, 0, 0)
            }
            SessionType.CORE -> XpBreakdown(OTHER_BASE, minuteXp(rec.minutesActual, CORE_MINUTE_CAP), 0, 0, 0)
            SessionType.HIKE -> XpBreakdown(OTHER_BASE, minuteXp(rec.minutesActual, HIKE_MINUTE_CAP), 0, 0, 0)
        }
    }

    private fun minuteXp(minutes: Int, cap: Int): Int = (min(minutes.coerceAtLeast(0), cap) * PER_MINUTE).toInt()

    private fun num(x: Double) = if (x % 1.0 == 0.0) x.toInt().toString() else x.toString()

    // ---------------------------------------------------------------- badges

    private fun badges(sorted: List<SessionRecord>, a: Analysis, streak: StreakResult): List<Badge> {
        fun nth(n: Int) = sorted.getOrNull(n - 1)?.date
        fun first(pred: (SessionRecord) -> Boolean) = sorted.firstOrNull(pred)?.date

        val comeback = sorted.zipWithNext().firstOrNull { (p, c) -> ChronoUnit.DAYS.between(p.date, c.date) >= COMEBACK_GAP_DAYS }?.second?.date

        val seen = mutableSetOf<TreadmillStyle>()
        var allStyles: LocalDate? = null
        for (r in sorted) {
            r.treadmill?.let { seen += it.style }
            if (allStyles == null && seen.size == TreadmillStyle.entries.size) allStyles = r.date
        }

        val earlyWeek = sorted.groupBy { weekStart(it.date) }.toSortedMap().firstNotNullOfOrNull { (start, list) ->
            val friday = start.with(DayOfWeek.FRIDAY)
            var gym = 0
            var tm = 0
            list.filter { it.date <= friday }.sortedBy { it.date }.firstOrNull { r ->
                if (r.type == SessionType.GYM) gym++
                if (r.type == SessionType.TREADMILL) tm++
                gym >= GYM_TARGET && tm >= TREADMILL_TARGET
            }?.date
        }

        val prFirst = a.prs.firstOrNull()?.date

        return listOf(
            Badge("first_session", "First step", "Log your first session", nth(1)),
            Badge("first_gym", "Iron welcome", "Log your first gym session", first { it.type == SessionType.GYM }),
            Badge("first_treadmill", "On the belt", "Log your first treadmill session", first { it.type == SessionType.TREADMILL }),
            Badge("first_pr", "New high", "Set your first personal record", prFirst),
            Badge("sessions_5", "High five", "Log 5 sessions", nth(5)),
            Badge("sessions_10", "Double digits", "Log 10 sessions", nth(10)),
            Badge("sessions_25", "Quarter century", "Log 25 sessions", nth(25)),
            Badge("sessions_50", "Fifty strong", "Log 50 sessions", nth(50)),
            Badge("squat_100", "Hundred club", "Squat 100 kg", first { r -> r.exercises.any { it.exerciseId == "squat" && it.sets.any { s -> s.weightKg >= 100.0 && s.reps > 0 } } }),
            Badge("comeback", "Welcome back", "Return after 2 weeks or more away. Coming back is the hard part", comeback),
            Badge("all_styles", "Style collector", "Try every treadmill style", allStyles),
            Badge("long_walker", "Long walker", "Walk for 90 minutes or more in one treadmill session", first { r ->
                r.type == SessionType.TREADMILL && (r.treadmill?.completedSec?.div(60) ?: r.minutesActual) >= 90
            }),
            Badge("early_week", "Early bird week", "Hit the good-week target (2 gym + 3 treadmill) by Friday", earlyWeek),
            Badge("quiet_hero", "Quiet hero", "Felt rough, still showed up for 30 minutes or less", first { it.feeling == Feeling.ROUGH && it.minutesActual in 1..30 }),
            Badge("streak_4", "Month of momentum", "Be active 4 weeks in a row", streak.fourWeekOn),
        )
    }

    // ---------------------------------------------------------------- celebration

    private val xpPhrases = listOf(
        "+%d XP banked. Nice work.",
        "That's +%d XP in the bag.",
        "+%d XP. Every session counts.",
        "You earned +%d XP today.",
        "+%d XP. Showing up, again.",
        "+%d XP. Future you says thanks.",
    )
    private val levelPhrases = listOf(
        "Level up! You're now level %1\$d: %2\$s.",
        "New level: %2\$s (level %1\$d). Well earned.",
        "Level %1\$d reached. Welcome, %2\$s.",
        "You levelled up to %2\$s (level %1\$d)!",
        "Ding! Level %1\$d, %2\$s.",
        "Onwards and upwards: %2\$s, level %1\$d.",
    )
    private val prPhrases = listOf(
        "New personal record! %s",
        "PR! %s",
        "Personal best: %s",
        "You just beat your best. %s",
        "Record broken. %s",
        "Stronger than ever. %s",
    )
    private val badgePhrases = listOf(
        "Badge unlocked: %s.",
        "New badge: %s!",
        "You earned the %s badge.",
        "Trophy shelf update: %s.",
        "Unlocked: %s.",
        "A shiny new badge: %s.",
    )

    private fun pick(pool: List<String>, id: String, kind: String): String =
        pool[Math.floorMod("$id|$kind".hashCode(), pool.size)]

    /**
     * Up to 3 short upbeat messages for the finish screen. The XP message (with breakdown) always comes first,
     * then at most two of: level up, badge unlocked, personal record (in that priority).
     * Wording varies but is fully determined by the record id.
     */
    fun celebration(record: SessionRecord, before: List<SessionRecord>): List<String> {
        val prior = before.filter { it.id != record.id && it.date <= record.date }
        val withIt = prior + record
        val beforeSummary = summary(prior, record.date)
        val after = summary(withIt, record.date)
        val xp = after.sessionXp.getValue(record.id)

        val special = mutableListOf<String>()
        if (after.level > beforeSummary.level) {
            special += String.format(pick(levelPhrases, record.id, "level"), after.level, after.levelName)
        }
        val already = beforeSummary.unlockedBadges.map { it.id }.toSet()
        val newBadges = after.unlockedBadges.filter { it.id !in already }
        if (newBadges.isNotEmpty()) special += String.format(pick(badgePhrases, record.id, "badge"), newBadges.first().title)
        val prs = after.prs.filter { it.sessionId == record.id }
        if (prs.isNotEmpty()) special += String.format(pick(prPhrases, record.id, "pr"), prs.first().text)

        val xpMsg = String.format(pick(xpPhrases, record.id, "xp"), xp.total) + " (" + xp.lines.joinToString(", ") + ")"
        return (listOf(xpMsg) + special).take(3)
    }
}
