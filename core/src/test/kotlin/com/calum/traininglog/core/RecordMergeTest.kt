package com.calum.traininglog.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordMergeTest {
    private fun rec(id: String, note: String? = null) =
        SessionRecord(id, LocalDate.of(2026, 11, 1), SessionType.HIKE, 30, 30, Feeling.GOOD, note = note)

    @Test fun addsOnlyWhatIsMissingAndKeepsLocalOnSharedIds() {
        val local = listOf(rec("a", "edited on phone"), rec("c"))
        val incoming = listOf(rec("a", "older copy"), rec("b"), rec("c"))
        assertEquals(listOf("b"), RecordMerge.missing(local, incoming).map { it.id })
    }

    @Test fun nothingMissingMeansNothingToAdd() {
        val same = listOf(rec("a"), rec("b"))
        assertEquals(emptyList(), RecordMerge.missing(same, same))
        assertEquals(emptyList(), RecordMerge.missing(same, emptyList()))
    }

    @Test fun duplicatesWithinTheBackupAreAddedOnce() {
        assertEquals(listOf("x"), RecordMerge.missing(emptyList(), listOf(rec("x"), rec("x"))).map { it.id })
    }

    @Test fun everythingIsAddedToAnEmptyPhone() {
        val incoming = listOf(rec("a"), rec("b"))
        assertEquals(incoming, RecordMerge.missing(emptyList(), incoming))
    }
}
