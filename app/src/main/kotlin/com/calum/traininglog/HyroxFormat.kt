package com.calum.traininglog

import com.calum.traininglog.core.HyroxDivision
import com.calum.traininglog.core.HyroxLadder
import com.calum.traininglog.core.HyroxStandards
import com.calum.traininglog.core.HyroxStation
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.StationEntry
import com.calum.traininglog.core.StationKind
import com.calum.traininglog.core.StationLevel
import com.calum.traininglog.core.StationLog
import com.calum.traininglog.core.StationPrescription
import com.calum.traininglog.core.StationStandard
import com.calum.traininglog.core.StationWeekBest
import java.util.Locale

/** Small pure helpers for the Hyrox screens (text formatting and history lookups). Unit tested. */
object HyroxFormat {
    fun kg(x: Double): String = if (x % 1.0 == 0.0) x.toInt().toString() else String.format(Locale.ENGLISH, "%.1f", x)

    fun time(seconds: Int): String = "${seconds / 60}:${"%02d".format(seconds % 60)}"

    /** "50 m · 152 kg", "100 reps · 6 kg", "200 m · 24 kg each", "1000 m". */
    fun standard(station: HyroxStation, std: StationStandard): String {
        val parts = mutableListOf<String>()
        std.distanceM?.let { parts += "$it m" }
        std.reps?.let { parts += "$it reps" }
        std.weightKg?.let { parts += "${kg(it)} kg" + if (station == HyroxStation.FARMERS_CARRY) " each" else "" }
        return parts.joinToString(" · ")
    }

    /** One line summarising the loads of a division. */
    fun divisionSummary(division: HyroxDivision, overrides: Map<String, Double> = emptyMap()): String {
        fun w(s: HyroxStation) = kg(HyroxStandards.of(division, s, overrides).weightKg ?: 0.0)
        return "Sled push ${w(HyroxStation.SLED_PUSH)} kg, pull ${w(HyroxStation.SLED_PULL)} kg, " +
            "carry 2 x ${w(HyroxStation.FARMERS_CARRY)} kg, lunges ${w(HyroxStation.SANDBAG_LUNGES)} kg, " +
            "wall ball ${w(HyroxStation.WALL_BALLS)} kg"
    }

    fun approx(division: HyroxDivision): Boolean =
        HyroxStation.entries.any { HyroxStandards.of(division, it).approx }

    private fun work(station: HyroxStation, distanceM: Int?, reps: Int?): String =
        if (station.kind == StationKind.REPS) "${reps ?: 0} reps" else "${distanceM ?: 0} m"

    private fun volume(station: HyroxStation, rounds: Int, distanceM: Int?, reps: Int?, weightKg: Double?, restSec: Int): String {
        val w = work(station, distanceM, reps)
        val base = if (rounds > 1) "$rounds x $w" else w
        val load = weightKg?.let { " at ${kg(it)} kg" } ?: ""
        val rest = if (rounds > 1 && restSec > 0) ", rest $restSec s" else ""
        return base + load + rest
    }

    /** "4 x 12 m at 40 kg, rest 90 s, stop at effort 8/10". */
    fun prescription(station: HyroxStation, p: StationPrescription): String =
        volume(station, p.rounds, p.distanceM, p.reps, p.weightKg, p.restSec) + ", stop at effort 8/10"

    fun level(station: HyroxStation, l: StationLevel): String =
        volume(station, l.rounds, l.distanceM, l.reps, l.weightKg, l.restSec)

    /** All earlier logs of [station] as progression history, newest first. */
    fun entries(records: List<SessionRecord>, station: HyroxStation): List<StationEntry> =
        records.flatMap { r -> r.stations.filter { it.stationId == station.id }.map { StationEntry(r.date, it) } }
            .sortedByDescending { it.date }

    /** Best pace (seconds per metre or per rep) among timed logs, or null. */
    fun bestLog(entries: List<StationEntry>): StationLog? =
        entries.map { it.log }
            .filter { (it.seconds ?: 0) > 0 && (it.distanceM ?: it.reps ?: 0) > 0 }
            .minByOrNull { it.seconds!!.toDouble() / (it.distanceM ?: it.reps!!) }

    fun logLine(station: HyroxStation, log: StationLog): String {
        val parts = mutableListOf(work(station, log.distanceM, log.reps))
        log.weightKg?.let { parts += "${kg(it)} kg" }
        log.seconds?.let { parts += time(it) }
        log.rpe?.let { parts += "effort $it/10" }
        return parts.joinToString(" · ")
    }

    /** "Not tried yet" or "Level 2 of 6 · best 50 m in 1:12". */
    fun status(station: HyroxStation, entries: List<StationEntry>, level: Int): String {
        if (entries.isEmpty()) return "Not tried yet"
        val best = bestLog(entries)
        val b = best?.let { " · best ${work(station, it.distanceM, it.reps)} in ${time(it.seconds!!)}" } ?: ""
        return "Level $level of ${HyroxLadder.LEVELS}$b"
    }

    /** Chart values per week. [weekIndexes] index into the weeks list (leading empty weeks dropped, gaps carried over). */
    class ChartData(val values: List<Float>, val weekIndexes: List<Int>, val byLoad: Boolean)

    /** Heaviest load when the weight varied, else best time in seconds. Null when nothing to draw. */
    fun chart(station: HyroxStation, weeks: List<StationWeekBest>): ChartData? {
        val loads = weeks.map { it.heaviestKg }
        val byLoad = station.weighted && loads.filterNotNull().distinct().size > 1
        val raw: List<Float?> = if (byLoad) loads.map { it?.toFloat() } else weeks.map { it.bestSeconds?.toFloat() }
        val first = raw.indexOfFirst { it != null }
        if (first < 0) return null
        val idx = (first until raw.size).toList()
        var last = 0f
        val vals = idx.map { i -> raw[i]?.also { last = it } ?: last }
        return ChartData(vals, idx, byLoad)
    }
}
