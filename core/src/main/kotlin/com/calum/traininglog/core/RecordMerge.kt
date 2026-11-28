package com.calum.traininglog.core

/**
 * Restoring a backup must never destroy sessions logged since it was taken.
 * The default restore therefore only ADDS what is missing, matched by record id; on a shared id the copy already on
 * the phone always wins (it may have been edited since). Pure: nothing here deletes or rewrites a record.
 */
object RecordMerge {
    /** The [incoming] records whose id is not already in [local], in their original order. */
    fun missing(local: List<SessionRecord>, incoming: List<SessionRecord>): List<SessionRecord> {
        val have = local.mapTo(HashSet()) { it.id }
        return incoming.filter { have.add(it.id) }
    }
}
