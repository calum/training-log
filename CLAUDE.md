# Training Log: notes for agents

## One source of truth for "what should I do next"

There are two planners, and they must never contradict each other on screen:

- **`RacePlanner`** (`core/.../RacePlanner.kt`): the Hyrox race plan, including the kick-off lead-in. Sessions can be rowing, ski-erg, incline walk, bike, strength and so on, and they respect the knee and run stage.
- **`WeekPlanner`** (`core/.../WeekPlan.kt`, used by `NextUp.describe`): the older weekly gym and treadmill targets. It knows nothing about the race plan.

**Rule: anything that says what to do next, today or soon (cards, notifications, reminders, widgets) must ask `Coach` (`core/.../Coach.kt`), never `RacePlanner`, `WeekPlanner`, `NextUp` or `Recommender` directly.** While the race plan is on (`profile.settings.racePlan.enabled`, the default) `Coach` answers from the race plan only; otherwise it falls back to the weekly-target text. This stops two cards disagreeing, e.g. "Next up: Kick-off: easy aerobic, 30 min, Sun 22 Nov" on the Hyrox card and "Treadmill day" on the NEXT UP card.

- Home's NEXT UP card and the evening reminder both call `Coach.nextUpText`. If nothing is left this week the card is hidden, not filled from the weekly planner.
- Build the race plan once per screen with `Coach.racePlan(...)` and pass it on; use `Coach.nextPlanned` for "the next open session".
- `CoachRulesTest` scans the sources and fails if anything other than `Coach` calls `NextUp.describe(`, or if a new file calls `WeekPlanner.plan(` or `Recommender.recommend(`. If you add a legitimate caller, say why in the test's allowlist comment.
- While the plan is on, reminder wording must not name an activity of its own (`Reminder.planPhrases`).

The agreed plan to remove the two-planner problem for good, plus the data-safety rules, is in `docs/unified-design.md`. Read it before touching planning, Home, rewards or storage, and never drop or rewrite saved records.

The "good week" (Home ring, its number and headline, and when the reminder stops nudging) comes from `Coach.goodWeek` / `Coach.weekProgress`: the plan's own sessions while a race plan is on, the old gym 2 / treadmill 3 targets otherwise. Screens never read `goodWeekDone` or `rewards.ring` themselves (`CoachRulesTest`). Streak and XP never depended on the targets. The "Early bird week" badge still uses the targets on purpose: badges are recomputed from history, so changing its rule could take away one you already earned.

Known gaps: the Start screen's free-choice picker below the planned session still uses `Recommender` on purpose; the maintenance plan after race day (phase 3) and the Home simplification (phase 4) are not built yet.

## Working here

- Unit tests: `./gradlew :app:testDebugUnitTest` and `:core:test`. The Android Gradle plugin can't be resolved offline in the cloud container, so rely on CI (`.github/workflows/ci.yml`) for the app module.
- Add a line to `CHANGELOG.md` for user-visible changes.
