package com.calum.traininglog.core

/**
 * Builds the files for the PUBLIC data repo. Only whitelisted, non-private columns may ever appear here
 * (see PublicExportTest). Filtering happens before anything leaves the phone, because git history is permanent.
 */
object PublicExport {
    /** Path to file content, e.g. "data/gym/2026-10.csv". Deterministic: same records give the same files. */
    fun files(records: List<SessionRecord>): Map<String, String> = emptyMap()
}
