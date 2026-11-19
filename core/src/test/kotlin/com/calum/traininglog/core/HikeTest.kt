package com.calum.traininglog.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HikeTest {
    private val today = LocalDate.of(2026, 11, 20)
    private fun hike(daysAgo: Int, min: Int, feeling: Feeling = Feeling.GOOD, knee: Int? = null) = SessionRecord(
        "h$daysAgo-$min", today.minusDays(daysAgo.toLong()), SessionType.HIKE, min, min, feeling, kneePain = knee,
    )

    @Test fun noHistoryNoSuggestion() {
        assertNull(Hike.suggest(emptyList(), today))
        assertNull(Hike.suggest(listOf(SessionRecord("g", today, SessionType.GYM, 60, 60, Feeling.GOOD)), today))
    }

    @Test fun ninetyBecomesAHundred() {
        val s = assertNotNull(Hike.suggest(listOf(hike(7, 90)), today))
        assertEquals(100, s.minutes)
        assertEquals(90, s.basedOnMinutes)
    }

    @Test fun neverMoreThanAboutTenPercent() {
        listOf(20, 30, 45, 60, 75, 90, 120, 150, 180, 240).forEach { m ->
            val s = Hike.suggest(listOf(hike(7, m)), today)!!
            assertTrue(s.minutes > m, "$m -> ${s.minutes}")
            assertTrue(s.minutes <= Math.ceil(m * 1.10 / 5.0).toInt() * 5, "$m -> ${s.minutes}")
            assertEquals(0, s.minutes % 5)
        }
    }

    @Test fun usesTheMostRecentHike() {
        val s = Hike.suggest(listOf(hike(30, 150), hike(5, 60)), today)!!
        assertEquals(60, s.basedOnMinutes)
    }

    @Test fun roughDayOrSoreKneeRepeatsTheLength() {
        assertEquals(90, Hike.suggest(listOf(hike(5, 90, Feeling.ROUGH)), today)!!.minutes)
        assertEquals(90, Hike.suggest(listOf(hike(5, 90, knee = 5)), today)!!.minutes)
    }

    @Test fun afterALongBreakItEasesBack() {
        val s = Hike.suggest(listOf(hike(60, 100)), today)!!
        assertEquals(80, s.minutes)
    }

    @Test fun noteReadsPlainly() {
        assertEquals("8.2 km, 450 m climb. Lovely views", Hike.note(8.2, 450, "Lovely views"))
        assertEquals("10 km", Hike.note(10.0, null))
        assertEquals("", Hike.note(null, null))
        assertEquals("Lovely views", Hike.note(0.0, 0, " Lovely views "))
    }
}
