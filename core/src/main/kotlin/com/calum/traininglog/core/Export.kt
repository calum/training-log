package com.calum.traininglog.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class WeekStats(val gym: Int, val treadmill: Int, val other: Int)

object Stats {
    fun weekOf(date: LocalDate): ClosedRange<LocalDate> {
        val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return start..start.plusDays(6)
    }

    fun week(records: List<SessionRecord>, date: LocalDate): WeekStats {
        val r = weekOf(date)
        val inWeek = records.filter { it.date in r }
        return WeekStats(
            gym = inWeek.count { it.type == SessionType.GYM },
            treadmill = inWeek.count { it.type == SessionType.TREADMILL },
            other = inWeek.count { it.type.countsAsOther },
        )
    }

    fun daysSince(type: SessionType, records: List<SessionRecord>, today: LocalDate): Int? =
        records.filter { it.type == type }.maxOfOrNull { it.date }?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today).toInt() }
}

/** Plain, compact text meant to be pasted into Claude. Includes private fields; never send this to the public repo. */
object Export {
    fun summary(records: List<SessionRecord>, from: LocalDate, to: LocalDate): String {
        val rows = records.filter { it.date in from..to }.sortedBy { it.date }
        val sb = StringBuilder()
        sb.appendLine("TRAINING EXPORT $from to $to")
        sb.appendLine("Sessions done: ${rows.size} (gym ${rows.count { it.type == SessionType.GYM }}, treadmill ${rows.count { it.type == SessionType.TREADMILL }}, other ${rows.count { it.type.countsAsOther }})")
        if (rows.isEmpty()) sb.appendLine("No sessions in this period.")
        for (r in rows) {
            sb.appendLine()
            sb.append("${r.date} ${r.type.name.lowercase()}")
            r.dayLabel?.let { sb.append(" day $it") }
            if (r.deload) sb.append(" (deload)")
            sb.append(", ${r.minutesActual}/${r.minutesAvailable} min, felt ${r.feeling.name.lowercase()}")
            r.kneePain?.let { sb.append(", knee $it/10") }
            sb.appendLine()
            for (e in r.exercises) sb.appendLine("  " + exerciseLine(e))
            r.treadmill?.let { t ->
                val done = t.completedSec / 60
                val planned = t.plannedSec / 60
                val short = if (t.completedSec < t.plannedSec) " (shortened)" else ""
                val extras = listOfNotNull(t.distanceM?.let { "$it m" }, t.climbM?.let { "$it m climb" }, t.kcal?.let { "about $it kcal" })
                    .takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = ", ") ?: ""
                sb.appendLine("  ${t.style.label}: $done of $planned min$short$extras${t.effort?.let { ", effort ${it.name.lowercase().replace('_', ' ')}" } ?: ""}")
            }
            val sim = r.sim
            if (sim != null) simLines(sim, r.stations).forEach { sb.appendLine(it) }
            else for (st in r.stations) sb.appendLine("  " + stationLine(st))
            r.note?.let { sb.appendLine("  note: $it") }
        }
        val custom = rows.flatMap { it.exercises }.filter { ExerciseLibrary.isCustom(it.exerciseId) }
            .map { ExerciseLibrary.nameOf(it.exerciseId, it.customName) }.distinct()
        if (custom.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Custom exercises logged (please add properly): ${custom.joinToString(", ")}")
        }
        return sb.toString().trimEnd()
    }

    /** A readable split table for a Hyrox simulation: a header line, then one numbered line per leg in race order. */
    internal fun simLines(sim: SimMeta, logs: List<StationLog>): List<String> {
        val legs = HyroxSim.assign(sim, logs)
        val pattern = HyroxSim.pattern(sim.format)
        val out = mutableListOf<String>()
        val head = buildList {
            add("Simulation (${sim.format.label.lowercase()})")
            add("loads ${Math.round(sim.loadFactor * 100)}%")
            add("run legs: ${sim.runMode.label.lowercase()}")
            if (sim.totalSec > 0) add("total ${longClock(sim.totalSec)}")
            sim.targetSec?.let { add("target ${longClock(it)}") }
            if (sim.endedEarly) add("ended early")
        }
        out += "  " + head.joinToString(", ")
        out += "  Splits in race order:"
        pattern.forEachIndexed { i, (kind, station) ->
            val log = legs[i]
            val name = if (kind == SimLegKind.RUN) "Run leg (${sim.runMode.label.lowercase()})" else station!!.displayName
            val body = if (log == null) "skipped or not done" else buildList {
                log.weightKg?.let { add("${num(it)} kg") }
                log.distanceM?.let { add("$it m") }
                log.reps?.let { add("$it reps") }
                log.seconds?.let { add(clock(it)) }
                log.rpe?.let { add("RPE $it") }
            }.joinToString(", ")
            out += "    ${i + 1}. $name: $body"
            sim.transitionSec.getOrNull(i)?.takeIf { it > 0 && i < pattern.lastIndex }?.let { out += "       transition: ${clock(it)}" }
        }
        val run = legs.filterIndexed { i, _ -> pattern[i].first == SimLegKind.RUN }.sumOf { it?.seconds ?: 0 }
        val st = legs.filterIndexed { i, _ -> pattern[i].first == SimLegKind.STATION }.sumOf { it?.seconds ?: 0 }
        val rox = sim.transitionSec.sum()
        if (run + st > 0) {
            val all = run + st + rox
            out += "  Running ${clock(run)} (${Math.round(run * 100.0 / all)}%), stations ${clock(st)} (${Math.round(st * 100.0 / all)}%)" +
                if (rox > 0) ", transitions (Roxzone) ${clock(rox)} (${Math.round(rox * 100.0 / all)}%), allowance ${sim.allowanceSec} s each" else ""
        }
        return out
    }

    private fun longClock(sec: Int) = if (sec >= 3600) "${sec / 3600}:${"%02d".format(sec % 3600 / 60)}:${"%02d".format(sec % 60)}" else clock(sec)

    /** e.g. "Sled Push: 102.5 kg, 50 m, 1:12, RPE 8, technique 2/3, level 5". */
    internal fun stationLine(st: StationLog): String {
        if (HyroxRuns.isRun(st)) {
            val parts = listOfNotNull(
                st.distanceM?.let { "$it m" }, st.seconds?.let { "in ${clock(it)}" },
            )
            return ("Run " + parts.joinToString(" ")).trim() + (st.rpe?.let { ", RPE $it" } ?: "")
        }
        val name = HyroxStation.byId(st.stationId)?.displayName ?: st.stationId
        val parts = listOfNotNull(
            st.weightKg?.let { "${num(it)} kg" },
            st.distanceM?.let { "$it m" },
            st.reps?.let { "$it reps" },
            st.seconds?.let { clock(it) },
            st.rpe?.let { "RPE $it" },
            st.technique?.let { "technique $it/3" },
            st.level?.let { "level $it" },
        )
        return if (parts.isEmpty()) name else "$name: ${parts.joinToString(", ")}"
    }

    private fun clock(sec: Int) = "${sec / 60}:${"%02d".format(sec % 60)}"

    private fun exerciseLine(e: ExerciseLog): String {
        val name = (if (ExerciseLibrary.isCustom(e.exerciseId)) "CUSTOM: " else "") + ExerciseLibrary.nameOf(e.exerciseId, e.customName)
        val sets = e.sets
        if (sets.isEmpty()) return name
        val sameWeight = sets.map { it.weightKg }.distinct().size == 1
        val body = if (sameWeight) "${num(sets[0].weightKg)}kg x ${sets.joinToString("/") { it.reps.toString() }}"
        else sets.joinToString(", ") { "${num(it.weightKg)}x${it.reps}" }
        val rir = sets.lastOrNull()?.rir?.let { ", last set RIR $it" } ?: ""
        val missed = if (e.exerciseId != ExerciseLibrary.CALF_HOME_ID && sets.any { it.reps < it.targetReps }) ", MISSED reps" + (e.missReason?.let { " (${it.name.lowercase().replace('_', ' ')})" } ?: "") else ""
        return "$name $body$rir$missed"
    }

    private fun num(x: Double) = if (x % 1.0 == 0.0) x.toInt().toString() else x.toString()
}
