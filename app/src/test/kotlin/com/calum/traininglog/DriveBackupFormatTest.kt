package com.calum.traininglog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriveBackupFormatTest {
    private val data = AppData()

    @Test fun envelopeRoundTrips() {
        val text = DriveFormat.encode(data, "2026-10-30T10:00:00Z")
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals(setOf("version", "savedAt", "data"), root.keys)
        val b = DriveFormat.decode(text)
        assertEquals(1, b.version)
        assertEquals("2026-10-30T10:00:00Z", b.savedAt)
        assertEquals(data, b.data)
        assertFalse(b.newerVersion)
    }

    @Test fun newerVersionWithExtraFieldsStillParses() {
        val text = """{"version":7,"savedAt":"x","future":1,"data":{"records":[],"newThing":true}}"""
        val b = DriveFormat.decode(text)
        assertTrue(b.newerVersion)
        assertEquals(data, b.data)
    }

    @Test fun bareAppDataFileParses() {
        val b = DriveFormat.decode("""{"records":[]}""")
        assertEquals(0, b.version)
        assertEquals(data, b.data)
    }

    @Test fun garbageThrows() {
        assertNull(runCatching { DriveFormat.decode("not json") }.getOrNull())
    }

    @Test fun snapshotNaming() {
        assertEquals("training-data-2026-03.json", DriveFormat.snapshotName(LocalDate.of(2026, 3, 31)))
        assertEquals(
            listOf("training-data-backup.json", "training-data-2026-01.json", "training-data-2025-12.json"),
            DriveFormat.restoreOrder(LocalDate.of(2026, 1, 15)),
        )
    }

    @Test fun searchQueryEscapes() {
        assertEquals(
            "(name = 'a.json' or name = 'b.json') and trashed = false",
            DriveFormat.searchQuery(listOf("a.json", "b.json")),
        )
        assertTrue(DriveFormat.searchQuery(listOf("it's")).contains("it\\'s"))
    }

    @Test fun appDataHasNoCredentials() {
        val names = AppData.serializer().descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName) }
        assertEquals(listOf("records", "profile"), names)
        val text = DriveFormat.encode(data, "t").lowercase()
        listOf("token", "password", "secret", "credential", "apikey").forEach { assertFalse(text.contains(it), it) }
    }
}
