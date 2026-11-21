package com.calum.traininglog.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GymDraftTest {
    private val day = LocalDate.of(2026, 11, 21)
    private val squat = ExerciseLog("squat", listOf(LoggedSet(80.0, 5, 5, 2), LoggedSet(80.0, 5, 5, 1)))
    private val draft = GymDraft(day, 60, 0, Feeling.GOOD, 1, "Squat day", false, listOf(squat))

    @Test fun becomesAnOrdinarySessionWithEverythingKept() {
        val r = draft.toRecord("id-1", "Recovered")
        assertEquals(SessionType.GYM, r.type)
        assertEquals(listOf(squat), r.exercises)
        assertEquals(day, r.date)
        assertEquals("Squat day", r.dayLabel)
        assertEquals(1, r.kneePain)
        assertEquals("Recovered", r.note)
        assertEquals(1, r.minutesActual) // never zero minutes
        assertEquals(2, draft.setCount)
    }

    @Test fun knowsWhenTheSameSessionIsAlreadyInHistory() {
        assertFalse(draft.alreadySavedIn(emptyList()))
        assertTrue(draft.alreadySavedIn(listOf(draft.toRecord("x"))))
        // A different day, or different sets, is a different session.
        assertFalse(draft.alreadySavedIn(listOf(draft.toRecord("x").copy(date = day.minusDays(1)))))
        assertFalse(draft.alreadySavedIn(listOf(draft.toRecord("x").copy(exercises = listOf(squat.copy(sets = squat.sets.take(1)))))))
    }
}
