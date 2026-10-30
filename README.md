# Training Log

Personal progressive training app for Android (Pixel 6a). Asks how much time and energy you have, then recommends and builds the session. No-guilt by design: missed weeks are normal. Full spec: *Personal Training App – Spec v1*.

## What's in v0.1 (minimal first version, spec section 14)

- **Start flow**: time available, how you feel, knee 0-10, then one recommendation plus one alternative. You can always pick something else.
- **Gym**: a recommended session built from slots instead of a fixed template: squat and calf raise every time, one hinge that alternates deadlift and RDL (RDL first), and the two upper-body movements (vertical/horizontal pull/push) trained least recently, using the exercise you last did in each. With 75+ lifting minutes the other hinge is offered as an optional extra. It is trimmed to fit your time (second upper, extras, first upper, then hinge; squat and calf stay). Flexible on the day: every exercise card can be removed, swapped within its movement group (including deadlift <-> RDL) or moved up/down, and "Add exercise" adds anything from the library or a free-text "Something else". Free-text exercises plan 3 x 10 at the last logged weight with no automatic progression, are flagged `CUSTOM:` in the export (with a footer asking Claude to add them properly) and earn no personal records. Also pre-filled sets (tap Done), reps-in-reserve rating, automatic squat warm-ups, rest timer with beep and flash. Gym options subtract 10 minutes of travel each way.
- **Progression**: main lifts add weight only when all reps are hit with 2+ reps in reserve; accessories use double progression; missed reps ask "rough day or at my limit"; two misses in a row back off; deload suggested after about 6 consistent weeks; about 90% restart after 3+ weeks off.
- **Treadmill**: steady, pyramid, ladder, intervals, long rolling and long interval sessions (20 min to 2 hours), segments of at least 2 minutes, beep plus flash 15 seconds before each change, pause and +/-30 s. Runs in a foreground service and floats in picture-in-picture when you leave the app. Speed and incline calibrate from your "too easy / just right / too hard" feedback.
- **Core**: a guided mat routine (10-30 min, `core/.../CoreRoutine.kt`): warm-up, three blocks of work/rest intervals, hip-flexor and figure-4 cool-down, no impact, a knee-sensitive switch that drops knee-loaded moves, and a different mix each time. Beep 3 s before each work interval ends and a double beep at the switch. The timer runs inside the app (no foreground service): leaving the app pauses the session and it says so when you return.
- **Hike**: lightweight log.
- **History and export**: everything stored on the phone; one-tap text export (last week, last 4 weeks, everything) to paste into Claude.

## Added since v0.1

- **Redesign**: new dark design system (`docs/design-system.md`), Home dashboard with weekly ring, XP and level, no-guilt streak with rest tokens, trophies and personal records, confetti on finish.
- **Rest days**: tap a day in the week strip to mark it as rest (long-press to repeat weekly). On a rest day you can still tap "I've got time" to build a session.
- **Plan**: 12-week forecast per lift (conservative / expected / best), headline target (default squat 100 kg), treadmill totals, milestones, "what if" pace.
- **Manual log**: add a session you already did without starting an activity.
- **In-app updater**: checks the latest GitHub release and installs it (Android asks you to confirm).

## Backup and sync (v0.3)

- **GitHub**: after each session the app pushes only the public numbers (weights, reps, effort, treadmill sessions) to the `data` branch of this repo; an Action on that branch redraws the progress chart (![chart](https://raw.githubusercontent.com/calum/training-log/data/progress.png)). Never sent: feelings, knee scores, notes, travel time, custom exercise names. Setup: `docs/data-repo-template/SETUP.md` (use branch `data`).
- **Google Drive**: your full private data is backed up to the hidden app folder in your own Drive. Setup: `docs/google-drive-setup.md`.
- **Safety**: the release workflow only runs when dispatched by hand (no tag trigger), and `main` should be protected so a leaked sync token cannot change code.

## Added in v0.4

- **Settings**: edit starting weights, increments, rest times, weekly targets, gym travel time, deload cadence and treadmill warm-up. Defaults match the old behaviour.
- **Training blocks**: 4-8 week blocks with a headline target and extra goals, progress on Home, a block-end export for Claude and a suggested next block.
- **Easing back in**: after 21+ days off, the first 3 gym sessions are lighter with reps in reserve guidance.
- **Plan** now forecasts upper-body lifts too; the Home "Next up" card names the real session.
- **Treadmill reliability**: audible beeps over YouTube (audio focus ducking), catch-up after stalls, notification and picture-in-picture buttons for pause and +/-30 s. On-phone checklist in `docs/treadmill-testing.md`.
- **Custom exercises** remember their movement group.

Not yet built: training blocks, in-app settings for increments and starting weights.

## Starting weights are placeholders

Defaults live in `core/.../Exercises.kt` and are deliberately lighter than the last known numbers (squat 80, deadlift 100, RDL 70, calf 60 kg) because of the break from lifting. Edit them there until a settings screen exists.

## Layout

- `core/`: pure Kotlin logic (progression, builder, recommender, treadmill planner, export) with unit tests. Run `./gradlew :core:test`.
- `app/`: Jetpack Compose Android app. Build with `./gradlew :app:assembleDebug`.

## Releases

Pushing a tag like `v0.1.0` builds and signs an APK and attaches it to a GitHub Release (`.github/workflows/release.yml`). One-off setup: create a keystore, back it up privately, and add `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` as repository secrets. Never commit the keystore. Sideload the APK from the Releases page.
