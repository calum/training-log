package com.calum.traininglog.core

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A deleted session, kept so the delete can be undone. [record] is stored exactly as it was, never edited. */
@Serializable
data class TrashedRecord(
    val record: SessionRecord,
    @Serializable(with = LocalDateSerializer::class) val deletedOn: LocalDate,
)

/**
 * Deleting a session never destroys it: it moves to a trash that keeps it for [KEEP_DAYS] days, so a mistaken
 * long-press can be undone from Home. Only after that does it leave the phone's data file for good (the daily and
 * weekly safety copies may still hold it). Pure functions, nothing here touches storage.
 */
object Trash {
    const val KEEP_DAYS = 90L

    /** [trash] with [record] added as deleted [today] (a record already in the trash is replaced, never duplicated). */
    fun add(trash: List<TrashedRecord>, record: SessionRecord, today: LocalDate): List<TrashedRecord> =
        trash.filterNot { it.record.id == record.id } + TrashedRecord(record, today)

    /** Entries deleted more than [KEEP_DAYS] days before [today]. */
    fun expired(trash: List<TrashedRecord>, today: LocalDate): List<TrashedRecord> =
        trash.filter { ChronoUnit.DAYS.between(it.deletedOn, today) > KEEP_DAYS }

    /** [trash] without the expired entries. */
    fun purge(trash: List<TrashedRecord>, today: LocalDate): List<TrashedRecord> =
        trash - expired(trash, today).toSet()

    /** Whole days left before [entry] is removed for good (never negative). */
    fun daysLeft(entry: TrashedRecord, today: LocalDate): Long =
        (KEEP_DAYS - ChronoUnit.DAYS.between(entry.deletedOn, today)).coerceAtLeast(0)
}
