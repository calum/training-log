package com.calum.traininglog.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Correcting the equipment of a session that was logged on the wrong thing (started on the rower, done on the treadmill). */
class GuidedEquipmentTest {
    private val day = LocalDate.of(2026, 11, 22)
    private fun easyRower(min: Int = 30) = GuidedRecord.build("g1", day, min, Modality.ROW, min * 60, 0, 2000, 3, Feeling.GOOD)!!
    private fun hardRower() = GuidedRecord.build("g2", day, 40, Modality.ROW, 40 * 60, 16 * 60, 1500, 8, Feeling.OKAY)!!

    @Test fun recognisesTheEquipmentOfASessionMadeHere() {
        assertEquals(Modality.ROW, GuidedRecord.equipmentOf(easyRower()))
        val bike = GuidedRecord.build("b", day, 30, Modality.BIKE, 1800, 0, null, 3, Feeling.GOOD)!!
        assertEquals(Modality.BIKE, GuidedRecord.equipmentOf(bike))
        // A normal treadmill or gym session is not one of these.
        val tread = SessionRecord("t", day, SessionType.TREADMILL, 30, 30, Feeling.GOOD, treadmill = TreadmillLog(TreadmillStyle.STEADY, 1800, 1800))
        assertNull(GuidedRecord.equipmentOf(tread))
        assertNull(GuidedRecord.equipmentOf(SessionRecord("g", day, SessionType.GYM, 60, 60, Feeling.GOOD)))
    }

    @Test fun rowerToSkiErgChangesOnlyTheEquipment() {
        val r = easyRower()
        val s = assertNotNull(GuidedRecord.withEquipment(r, Modality.SKIERG))
        assertEquals(r.id, s.id)
        assertEquals(r.date, s.date)
        assertEquals(r.minutesActual, s.minutesActual)
        assertEquals(r.feeling, s.feeling)
        assertEquals(HyroxStation.SKIERG.id, s.stations.single().stationId)
        assertEquals(r.stations.single().seconds, s.stations.single().seconds)
        assertEquals(r.stations.single().distanceM, s.stations.single().distanceM)
        assertEquals("Race plan: easy 30 min, skierg", s.note)
        assertEquals(Modality.SKIERG, GuidedRecord.equipmentOf(s))
    }

    @Test fun rowerToTreadmillBecomesARealTreadmillSession() {
        val r = easyRower(30)
        val t = assertNotNull(GuidedRecord.withEquipment(r, Modality.TREADMILL_WALK))
        assertEquals(SessionType.TREADMILL, t.type)
        assertEquals(emptyList(), t.stations)
        assertEquals(30 * 60, t.treadmill!!.completedSec)
        assertEquals(TreadmillStyle.STEADY, t.treadmill!!.style)
        assertEquals(r.id, t.id)
        assertEquals(30, t.minutesActual)
        assertEquals("Race plan: easy 30 min, ${Modality.TREADMILL_WALK.label.lowercase()}", t.note)
        assertEquals(Modality.TREADMILL_WALK, GuidedRecord.equipmentOf(t))
        // It counts as easy time like any other treadmill walk.
        assertEquals(30, IntensityBalance.classify(t).easyMin)
    }

    @Test fun andBackAgainWithoutLosingAnything() {
        val r = easyRower(30)
        val t = assertNotNull(GuidedRecord.withEquipment(r, Modality.TREADMILL_WALK))
        val back = assertNotNull(GuidedRecord.withEquipment(t, Modality.ROW))
        assertEquals(r.id, back.id)
        assertEquals(r.minutesActual, back.minutesActual)
        assertEquals(HyroxStation.ROW.id, back.stations.single().stationId)
        assertEquals(30 * 60, back.stations.single().seconds)
        assertEquals(SessionType.HYROX, back.type)
    }

    @Test fun aHardSessionKeepsItsIntervalsSoTheTreadmillIsNotOffered() {
        val r = hardRower()
        assertEquals(listOf(Modality.SKIERG, Modality.BIKE), GuidedRecord.correctionOptions(r))
        assertNull(GuidedRecord.withEquipment(r, Modality.TREADMILL_WALK))
        val s = assertNotNull(GuidedRecord.withEquipment(r, Modality.SKIERG))
        assertEquals(r.stations.size, s.stations.size)
        assertEquals(r.stations.map { it.rpe }, s.stations.map { it.rpe })
        assertTrue(s.stations.all { it.stationId == HyroxStation.SKIERG.id })
    }

    @Test fun nothingChangesForTheSameEquipmentOrForOtherSessions() {
        assertNull(GuidedRecord.withEquipment(easyRower(), Modality.ROW))
        assertNull(GuidedRecord.withEquipment(easyRower(), Modality.GYM))
        assertNull(GuidedRecord.withEquipment(SessionRecord("g", day, SessionType.GYM, 60, 60, Feeling.GOOD), Modality.BIKE))
        assertEquals(emptyList(), GuidedRecord.correctionOptions(SessionRecord("g", day, SessionType.GYM, 60, 60, Feeling.GOOD)))
    }
}
