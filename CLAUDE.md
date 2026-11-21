# Training Log: notes for agents

## One source of truth for "what should I do next"

There are two planners, and they must never contradict each other on screen:

- **`RacePlanner`** (`core/.../RacePlanner.kt`): the Hyrox race plan, including the kick-off lead-in. Sessions can be rowing, ski-erg, incline walk, bike, strength and so on, and they respect the knee and run stage.
- **`WeekPlanner`** (`core/.../WeekPlan.kt`, used by `NextUp.describe`): the older weekly gym and treadmill targets. It knows nothing about the race plan.

**Rule: when the race plan is enabled (`profile.settings.racePlan.enabled`), anything on screen that says what to do next, today or soon must come from the race plan, never from `WeekPlanner`/`NextUp`.** Otherwise Home shows, for example, "Next up: Kick-off: easy aerobic, 30 min, Sun 22 Nov" on the Hyrox card and "Treadmill day" on the NEXT UP card below it.

- Home's NEXT UP card uses `RacePlanFormat.nextUpText` when a race plan exists, and `NextUp.describe` only when there is none. If nothing is left this week the card is hidden, not filled from the weekly planner.
- Reuse the plan helpers (`RacePlanFormat.nextSession`, `todaysOpenSession`, `doneSessions`) rather than re-deriving a suggestion.
- Any new surface that suggests a session (notifications, reminders such as `core/.../Reminder.kt`, widgets, the Start screen) needs the same check: race plan on means ask the plan.
- Add a test that the text names the plan's session (see `nextUpTextFollowsThePlan` in `RacePlanFormatTest`).

The agreed plan to remove the two-planner problem for good, plus the data-safety rules, is in `docs/unified-design.md`. Read it before touching planning, Home, rewards or storage, and never drop or rewrite saved records.

Known gap: `Reminder.kt` still calls `NextUp.describe` directly, and the "Start a session" button opens the generic Start screen. Neither has been checked against the plan yet.

## Working here

- Unit tests: `./gradlew :app:testDebugUnitTest` and `:core:test`. The Android Gradle plugin can't be resolved offline in the cloud container, so rely on CI (`.github/workflows/ci.yml`) for the app module.
- Add a line to `CHANGELOG.md` for user-visible changes.
