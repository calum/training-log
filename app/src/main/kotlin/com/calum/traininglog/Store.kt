package com.calum.traininglog

import android.content.Context
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.SessionRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Serializable
data class AppData(
    val records: List<SessionRecord> = emptyList(),
    val profile: Profile = Profile(),
    /** Format of the saved file. 1 = the format as it was before versioning existed (files without the field decode as 1). */
    val schemaVersion: Int = Store.CURRENT_SCHEMA,
)

/** One step of the data-format upgrade chain: turns a tree written at [from] into one at [from] + 1. */
class Migration(val from: Int, val transform: (JsonObject) -> JsonObject)

/** Something the user should be told once about how their data was loaded. */
data class StoreNote(val kind: Kind, val message: String) {
    enum class Kind { RESTORED_FROM_BACKUP, COULD_NOT_READ, TOO_NEW }
}

/**
 * All data lives in one JSON file on the phone, with safety nets:
 *  - written atomically (temp file, fsync, rename);
 *  - a rolling safety copy in `backups/` (one per day, plus one before any migration, newest 14 kept);
 *  - a file that fails to read is moved aside and the newest valid safety copy is used instead, never silently replaced by empty data;
 *  - older formats are upgraded by [migrations]; data from a NEWER app is never overwritten ([blocked]).
 */
class Store(
    private val dir: File,
    private val migrations: List<Migration> = emptyList(),
    private val currentVersion: Int = CURRENT_SCHEMA,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) {
    constructor(context: Context) : this(context.filesDir)

    private val file = File(dir, "training-data.json")
    private val backupDir = File(dir, "backups")
    private val noteFile = File(dir, "load-note.txt")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** Set when the last [load] had something to tell the user. Restore notes persist until [dismissNote]. */
    var lastLoadNote: StoreNote? = null
        private set

    /** True when the file on disk was written by a newer app; [save] then refuses so that data is never overwritten. */
    var blocked: Boolean = false
        private set

    private class TooNew(val version: Int) : Exception()

    fun load(): AppData {
        blocked = false
        lastLoadNote = readNote()
        if (!file.exists()) return recoverFromBackups(corruptName = null) ?: AppData(schemaVersion = currentVersion)
        val text = runCatching { file.readText() }.getOrDefault("")
        return try {
            val tree = parseTree(text)
            val version = versionOf(tree)
            if (version in 1 until currentVersion) backup(suffix = "-pre-v$version", copyOf = file)
            val data = decode(tree, version)
            if (version < currentVersion) write(data)
            data
        } catch (e: TooNew) {
            blocked = true
            lastLoadNote = StoreNote(
                StoreNote.Kind.TOO_NEW,
                "Your data was saved by a newer version of the app. Update Training Log to open it. Nothing has been changed.",
            )
            AppData(schemaVersion = currentVersion)
        } catch (e: Exception) {
            recoverFromBackups(corruptName = "training-data.corrupt-${now().format(STAMP_SECONDS)}.json")
                ?: AppData(schemaVersion = currentVersion)
        }
    }

    /** Returns false when nothing was written (data from a newer app is on disk). */
    fun save(data: AppData): Boolean {
        if (blocked) return false
        if (file.exists()) dailyBackup()
        write(data)
        return true
    }

    fun dismissNote() {
        noteFile.delete()
        if (lastLoadNote?.kind != StoreNote.Kind.TOO_NEW) lastLoadNote = null
    }

    fun backups(): List<File> = backupFiles()

    // ---- reading ----

    private fun parseTree(text: String): JsonObject {
        if (text.isBlank()) throw IllegalStateException("empty")
        return json.parseToJsonElement(text).jsonObject
    }

    private fun versionOf(tree: JsonObject): Int = tree["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1

    /** Migrates [tree] from [version] to [currentVersion] and decodes it. Throws if it is newer or unreadable. */
    private fun decode(tree: JsonObject, version: Int): AppData {
        if (version > currentVersion) throw TooNew(version)
        var t = tree
        for (v in version until currentVersion) {
            val m = migrations.firstOrNull { it.from == v } ?: throw IllegalStateException("no migration from $v")
            t = m.transform(t)
        }
        return json.decodeFromJsonElement(AppData.serializer(), JsonObject(t + ("schemaVersion" to JsonPrimitive(currentVersion))))
    }

    private fun recoverFromBackups(corruptName: String?): AppData? {
        for (b in backupFiles().asReversed()) {
            val data = runCatching {
                val tree = parseTree(b.readText())
                decode(tree, versionOf(tree))
            }.getOrNull() ?: continue
            if (corruptName != null) moveAside(corruptName)
            write(data)
            val note = StoreNote(StoreNote.Kind.RESTORED_FROM_BACKUP, "We restored your data from a safety copy.")
            lastLoadNote = note
            writeNote(note)
            return data
        }
        if (corruptName != null) {
            moveAside(corruptName)
            val note = StoreNote(
                StoreNote.Kind.COULD_NOT_READ,
                "Your saved data could not be read and no safety copy was found. The original file was kept aside.",
            )
            lastLoadNote = note
            writeNote(note)
        }
        return null
    }

    private fun moveAside(name: String) {
        if (!file.exists()) return
        if (file.length() == 0L) { file.delete(); return }
        val dest = File(dir, name)
        if (!file.renameTo(dest)) runCatching { file.copyTo(dest, overwrite = true); file.delete() }
    }

    // ---- writing ----

    private fun write(data: AppData) {
        dir.mkdirs()
        val tree = json.encodeToJsonElement(AppData.serializer(), data).jsonObject
        val text = json.encodeToString(JsonElement.serializer(), JsonObject(tree + ("schemaVersion" to JsonPrimitive(currentVersion))))
        val tmp = File(dir, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            if (!tmp.renameTo(file)) throw e
        }
    }

    // ---- safety copies ----

    private fun backupFiles(): List<File> =
        backupDir.listFiles { f -> BACKUP_NAME.matches(f.name) }?.sortedBy { it.name } ?: emptyList()

    /** One copy per calendar day, taken from the file as it is just before the first overwrite of that day. */
    private fun dailyBackup() {
        val day = now().format(DAY)
        if (backupFiles().any { it.name.startsWith("training-data-$day-") && !it.name.contains("-pre-") }) return
        // Only keep a copy of something that actually reads, so one bad day cannot push good copies out.
        val ok = runCatching { parseTree(file.readText()); true }.getOrDefault(false)
        if (ok) backup(suffix = "", copyOf = file)
    }

    private fun backup(suffix: String, copyOf: File) {
        runCatching {
            backupDir.mkdirs()
            val dest = File(backupDir, "training-data-${now().format(STAMP)}$suffix.json")
            Files.copy(copyOf.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            backupFiles().dropLast(KEEP).forEach { it.delete() }
        }
    }

    private fun writeNote(n: StoreNote) { runCatching { noteFile.writeText("${n.kind.name}\n${n.message}") } }

    private fun readNote(): StoreNote? = runCatching {
        if (!noteFile.exists()) return@runCatching null
        val (k, m) = noteFile.readText().split("\n", limit = 2)
        StoreNote(StoreNote.Kind.valueOf(k), m)
    }.getOrNull()

    companion object {
        /** Bump together with a [Migration] from the previous number. */
        const val CURRENT_SCHEMA = 1
        const val KEEP = 14
        private val BACKUP_NAME = Regex("""training-data-\d{8}-\d{4}(-pre-v\d+)?\.json""")
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
        private val STAMP_SECONDS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val DAY = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
