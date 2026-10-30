package com.calum.traininglog.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.IsoFields

/**
 * Builds the files for the PUBLIC data repo. Only whitelisted, non-private columns may ever appear here
 * (see PublicExportTest). Filtering happens before anything leaves the phone, because git history is permanent.
 *
 * Never read: feeling, kneePain, note, dayLabel, minutesAvailable, missReason, effort, customName, custom exercises.
 */
object PublicExport {
    const val GYM_HEADER = "date,exercise,set,weight_kg,reps,target_reps,rir"
    const val TREADMILL_HEADER = "date,style,planned_min,completed_min"
    const val OTHER_HEADER = "date,type,minutes"
    const val SUMMARY_PATH = "data/summary.json"

    /** Path to file content, e.g. "data/gym/2026-10.csv". Deterministic: same records give the same files. */
    fun files(records: List<SessionRecord>): Map<String, String> {
        val sorted = records.withIndex()
            .sortedWith(compareBy({ it.value.date }, { it.value.id }, { it.index }))
            .map { it.value }

        val gym = sortedMapOf<YearMonth, MutableList<String>>()
        val treadmill = sortedMapOf<YearMonth, MutableList<String>>()
        val other = sortedMapOf<YearMonth, MutableList<String>>()

        for (r in sorted) {
            val month = YearMonth.from(r.date)
            when (r.type) {
                SessionType.GYM -> for (e in r.exercises) {
                    // Whitelist: only exercises that exist in the built-in library. Custom/unknown ids stay out.
                    if (ExerciseLibrary.find(e.exerciseId) == null) continue
                    e.sets.forEachIndexed { i, s ->
                        gym.getOrPut(month) { mutableListOf() }.add(
                            row(r.date, e.exerciseId, i + 1, num(s.weightKg), s.reps, s.targetReps, s.rir?.toString() ?: "")
                        )
                    }
                }
                SessionType.TREADMILL -> r.treadmill?.let { t ->
                    treadmill.getOrPut(month) { mutableListOf() }.add(
                        row(r.date, t.style.name.lowercase(), minutes(t.plannedSec), minutes(t.completedSec))
                    )
                }
                SessionType.CORE, SessionType.HIKE ->
                    other.getOrPut(month) { mutableListOf() }.add(row(r.date, r.type.name.lowercase(), r.minutesActual))
            }
        }

        val out = sortedMapOf<String, String>()
        fun emit(dir: String, header: String, rows: Map<YearMonth, List<String>>) {
            for ((m, lines) in rows) out["data/$dir/$m.csv"] = (listOf(header) + lines).joinToString("\n", postfix = "\n")
        }
        emit("gym", GYM_HEADER, gym)
        emit("other", OTHER_HEADER, other)
        emit("treadmill", TREADMILL_HEADER, treadmill)
        out[SUMMARY_PATH] = summary(sorted)
        return out
    }

    private fun weekKey(d: LocalDate): String =
        "%04d-W%02d".format(d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    private fun summary(sorted: List<SessionRecord>): String {
        val mainLifts = ExerciseLibrary.all.filter { it.role == Role.MAIN }
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"generated_from_records\": ${sorted.size},\n")
        sb.append("  \"top_weight_kg_by_week\": {\n")
        sb.append(mainLifts.joinToString(",\n") { lift ->
            val byWeek = sortedMapOf<String, Double>()
            for (r in sorted) if (r.type == SessionType.GYM) for (e in r.exercises) if (e.exerciseId == lift.id) {
                for (s in e.sets) if (s.reps > 0) byWeek.merge(weekKey(r.date), s.weightKg) { a, b -> maxOf(a, b) }
            }
            "    \"${lift.id}\": " + pairs(byWeek.map { it.key to num(it.value) })
        })
        sb.append("\n  },\n")
        val sessions = sortedMapOf<String, Int>()
        val tread = sortedMapOf<String, Int>()
        for (r in sorted) {
            sessions.merge(weekKey(r.date), 1, Int::plus)
            if (r.type == SessionType.TREADMILL) r.treadmill?.let { tread.merge(weekKey(r.date), it.completedSec, Int::plus) }
        }
        sb.append("  \"sessions_per_week\": ${pairs(sessions.map { it.key to it.value.toString() })},\n")
        sb.append("  \"treadmill_min_per_week\": ${pairs(tread.map { it.key to minutes(it.value) })}\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun pairs(items: List<Pair<String, String>>) =
        items.joinToString(", ", "[", "]") { "[\"${it.first}\", ${it.second}]" }

    private fun row(vararg cells: Any): String = cells.joinToString(",") { csv(it.toString()) }

    internal fun csv(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun num(x: Double) = if (x % 1.0 == 0.0) x.toLong().toString() else x.toString()

    /** Seconds to minutes with at most one decimal, locale independent. */
    private fun minutes(sec: Int): String {
        val tenths = Math.round(sec / 6.0)
        return if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
    }
}
