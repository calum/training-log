package com.calum.traininglog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.LocalDate

/** A parsed backup file. [newerVersion] is true when it was written by a newer app than this one. */
data class Backup(val version: Int, val savedAt: String, val data: AppData, val newerVersion: Boolean = false)

/** Pure helpers for the Drive backup file format and file naming (no Android or network). */
internal object DriveFormat {
    const val VERSION = 1
    const val LATEST_NAME = "training-data-backup.json"
    private val json = Json { ignoreUnknownKeys = true }

    /** `{"version":1,"savedAt":"<ISO>","data":{...AppData...}}`. Contains only AppData, never credentials. */
    fun encode(data: AppData, savedAtIso: String): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("version", VERSION)
            put("savedAt", savedAtIso)
            put("data", json.encodeToJsonElement(AppData.serializer(), data))
        },
    )

    /** Accepts the envelope (any version) or a bare AppData file. Throws on unreadable content. */
    fun decode(text: String): Backup {
        val root = json.parseToJsonElement(text).jsonObject
        val dataEl = root["data"]
            ?: return Backup(0, "", json.decodeFromJsonElement(AppData.serializer(), root))
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: 0
        val savedAt = root["savedAt"]?.jsonPrimitive?.contentOrNull ?: ""
        return Backup(version, savedAt, json.decodeFromJsonElement(AppData.serializer(), dataEl), version > VERSION)
    }

    /** `training-data-YYYY-MM.json`, overwritten within the month. */
    fun snapshotName(date: LocalDate): String = "training-data-%04d-%02d.json".format(date.year, date.monthValue)

    /** Restore order: rolling latest, then this month's snapshot, then last month's. */
    fun restoreOrder(today: LocalDate): List<String> =
        listOf(LATEST_NAME, snapshotName(today), snapshotName(today.minusMonths(1)))

    /** Drive `q` for files with any of [names] that are not in the bin. */
    fun searchQuery(names: List<String>): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")
        return "(" + names.joinToString(" or ") { "name = '${esc(it)}'" } + ") and trashed = false"
    }
}
