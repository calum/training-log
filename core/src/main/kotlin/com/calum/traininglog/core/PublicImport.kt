package com.calum.traininglog.core

import java.time.LocalDate

/**
 * Inverse of [PublicExport]: rebuilds [SessionRecord]s from the public CSV files (paths like `data/gym/2026-10.csv`).
 * Only the public columns exist there, so private fields get neutral defaults: feeling GOOD, no knee score, no notes.
 * Ids are deterministic (`gh-<date>-<type>-<n>`), so importing the same files twice gives the same records.
 * Pure and forgiving: malformed lines are skipped rather than failing the whole import.
 */
object PublicImport {
    /** Minutes assumed per gym set; the export doesn't carry session length. */
    private const val GYM_MIN_PER_SET = 3

    fun records(files: Map<String, String>): List<SessionRecord> {
        val paths = files.keys.sorted()
        fun under(dir: String) = paths.filter { it.startsWith("data/$dir/") && it.endsWith(".csv") }.map { files.getValue(it) }
        val out = mutableListOf<SessionRecord>()
        out += gym(under("gym"))
        out += treadmill(under("treadmill"))
        out += other(under("other"))
        return out.sortedWith(compareBy({ it.date }, { it.type }, { it.id }))
    }

    /** The imported records whose (date, type) is not already in [local]. Never replaces anything. */
    fun newOnly(local: List<SessionRecord>, imported: List<SessionRecord>): List<SessionRecord> {
        val have = local.map { it.date to it.type }.toSet()
        return imported.filter { (it.date to it.type) !in have }
    }

    private fun gym(contents: List<String>): List<SessionRecord> {
        // date -> exercise id (in order of appearance) -> (set number, set)
        val byDate = sortedMapOf<LocalDate, LinkedHashMap<String, MutableList<Pair<Int, LoggedSet>>>>()
        for (c in contents) for (cells in rows(c)) {
            if (cells.size < 5) continue
            val date = date(cells[0]) ?: continue
            val ex = cells[1].trim()
            if (ex.isEmpty()) continue
            val n = cells[2].trim().toIntOrNull() ?: continue
            val kg = cells[3].trim().toDoubleOrNull() ?: continue
            val reps = cells[4].trim().toIntOrNull() ?: continue
            val target = cells.getOrNull(5)?.trim()?.toIntOrNull() ?: reps
            val rir = cells.getOrNull(6)?.trim()?.toIntOrNull()
            byDate.getOrPut(date) { LinkedHashMap() }.getOrPut(ex) { mutableListOf() }.add(n to LoggedSet(kg, reps, target, rir))
        }
        return byDate.map { (date, exs) ->
            val logs = exs.map { (id, sets) -> ExerciseLog(id, sets.sortedBy { it.first }.map { it.second }) }
            val mins = logs.sumOf { it.sets.size } * GYM_MIN_PER_SET
            SessionRecord(
                id = "gh-$date-gym-1", date = date, type = SessionType.GYM,
                minutesAvailable = mins, minutesActual = mins, feeling = Feeling.GOOD, exercises = logs,
            )
        }
    }

    private fun treadmill(contents: List<String>): List<SessionRecord> {
        val out = mutableListOf<SessionRecord>()
        val perDate = mutableMapOf<LocalDate, Int>()
        for (c in contents) for (cells in rows(c)) {
            if (cells.size < 4) continue
            val date = date(cells[0]) ?: continue
            val style = TreadmillStyle.entries.firstOrNull { it.name.equals(cells[1].trim(), ignoreCase = true) } ?: continue
            val planned = secs(cells[2]) ?: continue
            val done = secs(cells[3]) ?: continue
            val n = (perDate[date] ?: 0) + 1
            perDate[date] = n
            out += SessionRecord(
                id = "gh-$date-treadmill-$n", date = date, type = SessionType.TREADMILL,
                minutesAvailable = Math.round(planned / 60.0).toInt(), minutesActual = Math.round(done / 60.0).toInt(),
                feeling = Feeling.GOOD, treadmill = TreadmillLog(style, planned, done),
            )
        }
        return out
    }

    private fun other(contents: List<String>): List<SessionRecord> {
        val out = mutableListOf<SessionRecord>()
        val perKey = mutableMapOf<Pair<LocalDate, SessionType>, Int>()
        for (c in contents) for (cells in rows(c)) {
            if (cells.size < 3) continue
            val date = date(cells[0]) ?: continue
            val type = listOf(SessionType.CORE, SessionType.HIKE).firstOrNull { it.name.equals(cells[1].trim(), ignoreCase = true) } ?: continue
            val minutes = cells[2].trim().toDoubleOrNull()?.let { Math.round(it).toInt() } ?: continue
            val n = (perKey[date to type] ?: 0) + 1
            perKey[date to type] = n
            out += SessionRecord(
                id = "gh-$date-${type.name.lowercase()}-$n", date = date, type = type,
                minutesAvailable = minutes, minutesActual = minutes, feeling = Feeling.GOOD,
            )
        }
        return out
    }

    private fun date(s: String): LocalDate? = runCatching { LocalDate.parse(s.trim()) }.getOrNull()

    private fun secs(minutes: String): Int? = minutes.trim().toDoubleOrNull()?.let { Math.round(it * 60).toInt() }

    /** Data rows of a CSV (header skipped), honouring quotes as written by [PublicExport.csv]. */
    internal fun rows(csv: String): List<List<String>> =
        csv.lines().filter { it.isNotBlank() }.drop(1).map(::splitLine)

    private fun splitLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                quoted && ch == '"' && line.getOrNull(i + 1) == '"' -> { sb.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { cells += sb.toString(); sb.clear() }
                ch != '\r' -> sb.append(ch)
            }
            i++
        }
        cells += sb.toString()
        return cells
    }
}
