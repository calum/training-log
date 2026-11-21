package com.calum.traininglog.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Architecture rule (see CLAUDE.md and docs/unified-design.md): only [Coach] decides what to suggest next, so two
 * screens can never disagree. This scans the production sources and fails when a new caller appears.
 */
class CoachRulesTest {
    private fun sources(): List<File> {
        val roots = listOf(File("src/main/kotlin"), File("../core/src/main/kotlin"), File("../app/src/main/kotlin")).filter { it.isDirectory }
        return roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }.distinctBy { it.canonicalPath }
    }

    private fun callers(needle: String): Set<String> =
        sources().filter { needle in it.readText() }.map { it.name }.toSet()

    @Test fun theScanSeesTheSources() {
        assertTrue("Coach.kt" in sources().map { it.name }, "the scan found no sources; check the working directory")
    }

    @Test fun onlyCoachAsksTheOldNextUpText() {
        assertEquals(setOf("Coach.kt"), callers("NextUp.describe("))
    }

    @Test fun onlyAllowedPlacesBuildTheWeeklyPlan() {
        // HomeScreen still draws the week strip and ring from it; that goes away with phases 2 and 4 of the design.
        assertEquals(setOf("Coach.kt", "HomeScreen.kt"), callers("WeekPlanner.plan("))
    }

    @Test fun onlyTheFreeChoicePickerUsesTheRecommender() {
        // "Time and energy" below the planned session on the Start screen; the plan stays first.
        assertEquals(setOf("AppViewModel.kt"), callers("Recommender.recommend("))
    }
}
