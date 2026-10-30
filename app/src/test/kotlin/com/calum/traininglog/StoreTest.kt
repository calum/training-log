package com.calum.traininglog

import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {
    private lateinit var dir: File
    private var t = LocalDateTime.of(2026, 3, 1, 9, 0)

    @BeforeTest fun setUp() { dir = Files.createTempDirectory("store-test").toFile() }
    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private fun store(migrations: List<Migration> = emptyList(), version: Int = 1) =
        Store(dir, migrations, version) { t }

    private fun rec(id: String, day: Int = 1) = SessionRecord(
        id, LocalDate.of(2026, 2, day), SessionType.HIKE, 60, 60, Feeling.GOOD, note = "n$id",
    )

    private fun data(vararg ids: String) = AppData(ids.map { rec(it) }, Profile())
    private val main get() = File(dir, "training-data.json")
    private val backups get() = File(dir, "backups").listFiles().orEmpty().map { it.name }.sorted()

    @Test fun freshInstallIsEmptyWithoutNote() {
        val s = store()
        assertEquals(emptyList(), s.load().records)
        assertNull(s.lastLoadNote)
        assertFalse(main.exists())
    }

    @Test fun roundTripWritesSchemaVersionAndNoTempFile() {
        val s = store()
        assertTrue(s.save(data("a", "b")))
        assertEquals(listOf("a", "b"), store().load().records.map { it.id })
        assertTrue(main.readText().contains("\"schemaVersion\": 1"))
        assertFalse(File(dir, "training-data.json.tmp").exists())
    }

    @Test fun oldFileWithoutVersionDecodesAsOne() {
        main.writeText("""{"records": [], "profile": {}}""")
        val s = store()
        assertEquals(1, s.load().schemaVersion)
        assertNull(s.lastLoadNote)
        assertEquals(emptyList(), backups) // no migration, so no pre-migration copy
    }

    @Test fun oneSafetyCopyPerDayHoldingThePreviousState() {
        val s = store()
        s.save(data("a"))
        assertEquals(emptyList(), backups) // nothing to protect yet
        s.save(data("a", "b"))
        assertEquals(listOf("training-data-20260301-0900.json"), backups)
        t = t.plusHours(3)
        s.save(data("a", "b", "c"))
        assertEquals(1, backups.size) // still the same day
        // The copy is the state BEFORE the first overwrite of the day.
        val copy = File(dir, "backups").listFiles()!!.single().readText()
        assertTrue(copy.contains("\"id\": \"a\"") && !copy.contains("\"id\": \"b\""))
        t = t.plusDays(1)
        s.save(data("z"))
        assertEquals(2, backups.size)
    }

    @Test fun keepsOnlyNewestFourteen() {
        val s = store()
        s.save(data("seed"))
        repeat(20) { i -> t = t.plusDays(1); s.save(data("seed", "r$i")) }
        val names = backups
        assertEquals(14, names.size)
        assertEquals("training-data-20260321-0900.json", names.last())
        assertFalse(names.contains("training-data-20260302-0900.json"))
    }

    @Test fun corruptFileFallsBackToNewestValidBackupAndIsMovedAside() {
        val s = store()
        s.save(data("a"))
        s.save(data("a", "b")) // backup of {a}
        t = t.plusDays(1)
        s.save(data("a", "b", "c")) // backup of {a,b}
        main.writeText("{\"records\": [{\"id\": \"x\", ") // truncated
        val loaded = store()
        val d = loaded.load()
        assertEquals(listOf("a", "b"), d.records.map { it.id })
        assertEquals(StoreNote.Kind.RESTORED_FROM_BACKUP, loaded.lastLoadNote?.kind)
        assertEquals("We restored your data from a safety copy.", loaded.lastLoadNote?.message?.trimEnd('.')?.plus("."))
        val aside = dir.listFiles()!!.single { it.name.startsWith("training-data.corrupt-") }
        assertTrue(aside.readText().startsWith("{\"records\""))
        // The main file is healthy again.
        assertEquals(listOf("a", "b"), store().load().records.map { it.id })
    }

    @Test fun restoreNoteSurvivesUntilDismissedEvenIfAnotherStoreLoadedFirst() {
        val s = store()
        s.save(data("a")); s.save(data("a", "b"))
        main.writeText("")
        store().load() // e.g. a background worker
        val ui = store()
        ui.load()
        assertNotNull(ui.lastLoadNote)
        ui.dismissNote()
        assertNull(ui.lastLoadNote)
        val again = store(); again.load()
        assertNull(again.lastLoadNote)
    }

    @Test fun skipsACorruptBackupAndUsesAnOlderGoodOne() {
        val s = store()
        s.save(data("a")); s.save(data("a", "b"))
        t = t.plusDays(1); s.save(data("a", "b", "c"))
        File(dir, "backups").listFiles()!!.maxByOrNull { it.name }!!.writeText("garbage")
        main.writeText("not json")
        assertEquals(listOf("a"), store().load().records.map { it.id })
    }

    @Test fun emptyFileWithBackupRestoresAndEmptyFileWithoutBackupDoesNotCrash() {
        val s = store()
        s.save(data("a")); s.save(data("a", "b"))
        main.writeText("")
        assertEquals(listOf("a"), store().load().records.map { it.id })

        val other = Files.createTempDirectory("store-test2").toFile()
        try {
            File(other, "training-data.json").writeText("")
            val st = Store(other, emptyList(), 1) { t }
            assertEquals(emptyList(), st.load().records)
            assertTrue(File(other, "training-data.json").length() == 0L || !File(other, "training-data.json").exists())
        } finally { other.deleteRecursively() }
    }

    @Test fun corruptWithNoBackupKeepsTheOriginalAsideAndSaysSo() {
        main.writeText("{broken")
        val s = store()
        assertEquals(emptyList(), s.load().records)
        assertEquals(StoreNote.Kind.COULD_NOT_READ, s.lastLoadNote?.kind)
        assertEquals("{broken", dir.listFiles()!!.single { it.name.startsWith("training-data.corrupt-") }.readText())
    }

    @Test fun corruptMainIsNeverCopiedIntoTheBackups() {
        val s = store()
        s.save(data("a"))
        main.writeText("{broken")
        val st = store(); st.load() // moves it aside, nothing to restore
        st.save(data("fresh"))
        assertEquals(emptyList(), backups) // main was missing when saving, nothing to back up
    }

    @Test fun newerVersionIsNotLoadedAndNeverOverwritten() {
        val original = """{"schemaVersion": 7, "records": [], "profile": {}, "future": true}"""
        main.writeText(original)
        val s = store()
        assertEquals(emptyList(), s.load().records)
        assertTrue(s.blocked)
        assertEquals(StoreNote.Kind.TOO_NEW, s.lastLoadNote?.kind)
        assertFalse(s.save(data("x")))
        assertEquals(original, main.readText())
        assertEquals(emptyList(), backups)
        assertEquals(1, dir.listFiles()!!.count { it.name.startsWith("training-data") })
    }

    // ---- migrations (fake list) ----

    private val addNote = Migration(1) { o ->
        // v1 -> v2: tag every record's note
        val recs = (o["records"] as JsonArray).map { r ->
            val ro = r as JsonObject
            JsonObject(ro + ("note" to JsonPrimitive((ro["note"] as? JsonPrimitive)?.content.orEmpty() + "+m1")))
        }
        JsonObject(o + ("records" to JsonArray(recs)))
    }
    private val addMore = Migration(2) { o ->
        val recs = (o["records"] as JsonArray).map { r ->
            val ro = r as JsonObject
            JsonObject(ro + ("note" to JsonPrimitive((ro["note"] as JsonPrimitive).content + "+m2")))
        }
        JsonObject(o + ("records" to JsonArray(recs)))
    }

    @Test fun migrationChainRunsInOrderTakesACopyFirstAndPersists() {
        store().save(data("a"))
        val original = main.readText()
        val s = store(listOf(addMore, addNote), version = 3) // deliberately unsorted
        val d = s.load()
        assertEquals("na+m1+m2", d.records.single().note)
        assertEquals(3, d.schemaVersion)
        assertTrue(main.readText().contains("\"schemaVersion\": 3"))
        val pre = File(dir, "backups").listFiles()!!.single()
        assertTrue(pre.name.endsWith("-pre-v1.json"))
        assertEquals(original, pre.readText())
        // Loading again does not migrate again.
        assertEquals("na+m1+m2", store(listOf(addMore, addNote), 3).load().records.single().note)
    }

    @Test fun partialChainStartsFromTheFileVersion() {
        store(listOf(addNote), 2).save(data("a")) // written at v2
        val d = store(listOf(addNote, addMore), 3).load()
        assertEquals("na+m2", d.records.single().note)
    }

    @Test fun failingMigrationKeepsDataSafe() {
        store().save(data("a"))
        val original = main.readText()
        val boom = Migration(1) { error("bug in migration") }
        val s = store(listOf(boom), 2)
        val d = s.load()
        // Fell back to a safe state, but the original is still on disk somewhere, never lost.
        val all = dir.walkTopDown().filter { it.isFile }.map { it.readText() }.toList()
        assertTrue(all.any { it == original }, "original must survive a failed migration")
        assertNotNull(d)
    }

    @Test fun backupsFromOlderVersionsAreMigratedWhenRestored() {
        store().save(data("a")); store().save(data("a", "b"))
        main.writeText("{broken")
        val d = store(listOf(addNote), 2).let { it.load() }
        assertEquals(listOf("a"), d.records.map { it.id })
        assertEquals("na+m1", d.records.single().note)
    }
}
