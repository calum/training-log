package com.calum.traininglog.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class TrashTest {
    private val today = LocalDate.of(2026, 11, 21)
    private fun rec(id: String) = SessionRecord(id, today.minusDays(5), SessionType.HIKE, 30, 30, Feeling.GOOD, note = id)

    @Test fun addKeepsTheRecordUnchangedAndStampsTheDay() {
        val t = Trash.add(emptyList(), rec("a"), today)
        assertEquals(rec("a"), t.single().record)
        assertEquals(today, t.single().deletedOn)
    }

    @Test fun addingTheSameRecordTwiceDoesNotDuplicate() {
        val t = Trash.add(Trash.add(emptyList(), rec("a"), today.minusDays(3)), rec("a"), today)
        assertEquals(1, t.size)
        assertEquals(today, t.single().deletedOn)
    }

    @Test fun nothingIsPurgedUntilMoreThanNinetyDaysHavePassed() {
        val t = Trash.add(emptyList(), rec("a"), today.minusDays(90))
        assertEquals(t, Trash.purge(t, today)) // exactly 90 days: still kept
        assertEquals(emptyList(), Trash.purge(t, today.plusDays(1)))
    }

    @Test fun purgeOnlyRemovesTheExpiredOnes() {
        val old = Trash.add(emptyList(), rec("old"), today.minusDays(120))
        val t = Trash.add(old, rec("new"), today.minusDays(2))
        assertEquals(listOf("old"), Trash.expired(t, today).map { it.record.id })
        assertEquals(listOf("new"), Trash.purge(t, today).map { it.record.id })
    }

    @Test fun daysLeftCountsDown() {
        val e = TrashedRecord(rec("a"), today.minusDays(10))
        assertEquals(80, Trash.daysLeft(e, today))
        assertEquals(0, Trash.daysLeft(e, today.plusDays(200)))
    }
}
