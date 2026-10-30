package com.calum.traininglog.core

object Warmup {
    const val BAR_KG = 20.0

    /** Squat pattern: 5 bar only, 5 at 40, then +10 per set until the working weight. */
    fun squatSets(workingKg: Double, barKg: Double = BAR_KG): List<LoggedSet> {
        val weights = mutableListOf<Double>()
        if (workingKg > barKg) weights += barKg
        var w = 40.0
        while (w < workingKg) {
            weights += w
            w += 10.0
        }
        return weights.map { LoggedSet(it, 5, 5) }
    }
}
