package com.calum.traininglog.core

import java.time.LocalDate

/**
 * Pure decision logic for the optional, opt-in evening nudge. Warm and quiet by design: it only ever
 * invites, and never mentions missed sessions, streaks or anything guilt-based.
 */
object Reminder {
    /** Warm phrasings; one is chosen deterministically per date. */
    val phrases = listOf(
        "Kids down? You've got time for a 30-minute walk if you fancy it.",
        "Quiet house? A little movement could be a nice way to end the day.",
        "If you've got the energy, the treadmill is there for you tonight.",
        "No pressure at all. A short session is on offer if the evening suits.",
        "Evening's yours. Fancy a gentle stroll on the belt?",
        "Feet up is fine too. But if you feel like moving, 20 minutes would be lovely.",
        "Whenever you're ready, something easy is waiting. Totally optional.",
        "A calm bit of exercise could be a good way to unwind tonight, if you like.",
    )

    fun phrase(date: LocalDate): String = phrases[Math.floorMod(date.toEpochDay(), phrases.size.toLong()).toInt()]

    /** True when a nudge is appropriate today. [sentThisWeek] is how many were already sent this Mon-Sun week. */
    fun shouldNudge(records: List<SessionRecord>, profile: Profile, today: LocalDate, sentThisWeek: Int): Boolean {
        val s = profile.settings
        if (!s.reminderEnabled) return false
        if (today.dayOfWeek !in s.reminderDays) return false
        if (sentThisWeek >= s.reminderMaxPerWeek) return false
        if (RestDays.isRestDay(profile, today)) return false
        if (records.any { it.date == today }) return false
        val week = Stats.week(records.filter { it.date <= today }, today)
        if (minOf(week.gym, s.gymTarget) + minOf(week.treadmill, s.treadmillTarget) >= s.gymTarget + s.treadmillTarget) return false
        val lastFeeling = records.filter { it.date <= today && it.date >= today.minusDays(2) }.maxByOrNull { it.date }?.feeling
        if (lastFeeling == Feeling.ILL || lastFeeling == Feeling.ROUGH) return false
        return true
    }

    /** The notification text for [today], or null when no nudge should be sent. */
    fun nudgeText(records: List<SessionRecord>, profile: Profile, today: LocalDate, sentThisWeek: Int): String? {
        if (!shouldNudge(records, profile, today, sentThisWeek)) return null
        val base = phrase(today)
        val view = WeekPlanner.plan(records, profile, today)
        val suggestion = NextUp.describe(records, view, today)
        return if (suggestion == null) base else "$base $suggestion"
    }
}
