package com.calum.traininglog

import android.content.Context
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.SessionRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AppData(
    val records: List<SessionRecord> = emptyList(),
    val profile: Profile = Profile(),
)

/** All data lives in one JSON file on the phone. Written to a temp file first so a crash can't corrupt it. */
class Store(context: Context) {
    private val file = File(context.filesDir, "training-data.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(): AppData = runCatching { json.decodeFromString<AppData>(file.readText()) }.getOrDefault(AppData())

    fun save(data: AppData) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(AppData.serializer(), data))
        tmp.renameTo(file)
    }
}
