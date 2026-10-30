# Training Log

Personal progressive training app for Android (Pixel 6a). Asks how much time and energy you have, then recommends and builds the session. No-guilt by design: missed weeks are normal. Full spec: *Personal Training App – Spec v1*.

## What's in v0.1 (minimal first version, spec section 14)

- **Start flow**: time available, how you feel, knee 0-10, then one recommendation plus one alternative. You can always pick something else.
- **Gym**: alternating A/B days, pre-filled sets (tap Done), reps-in-reserve rating, automatic squat warm-ups, rest timer with beep and flash, one-tap swaps for upper-body exercises. Sessions are trimmed to fit your time (accessories drop first). Gym options subtract 10 minutes of travel each way.
- **Progression**: main lifts add weight only when all reps are hit with 2+ reps in reserve; accessories use double progression; missed reps ask "rough day or at my limit"; two misses in a row back off; deload suggested after about 6 consistent weeks; about 90% restart after 3+ weeks off.
- **Treadmill**: steady, pyramid, ladder, intervals, long rolling and long interval sessions (20 min to 2 hours), segments of at least 2 minutes, beep plus flash 15 seconds before each change, pause and +/-30 s. Runs in a foreground service and floats in picture-in-picture when you leave the app. Speed and incline calibrate from your "too easy / just right / too hard" feedback.
- **Core / hike**: lightweight log.
- **History and export**: everything stored on the phone; one-tap text export (last week, last 4 weeks, everything) to paste into Claude.

## Added since v0.1

- **Redesign**: new dark design system (`docs/design-system.md`), Home dashboard with weekly ring, XP and level, no-guilt streak with rest tokens, trophies and personal records, confetti on finish.
- **Rest days**: tap a day in the week strip to mark it as rest (long-press to repeat weekly). On a rest day you can still tap "I've got time" to build a session.
- **Plan**: 12-week forecast per lift (conservative / expected / best), headline target (default squat 100 kg), treadmill totals, milestones, "what if" pace.
- **Manual log**: add a session you already did without starting an activity.
- **In-app updater**: checks the latest GitHub release and installs it (Android asks you to confirm).

Not yet built: training blocks, GitHub sync and README chart (section 15), Google Drive backup (17), in-app settings for increments and starting weights.

## Starting weights are placeholders

Defaults live in `core/.../Exercises.kt` and are deliberately lighter than the last known numbers (squat 80, deadlift 100, RDL 70, calf 60 kg) because of the break from lifting. Edit them there until a settings screen exists.

## Layout

- `core/`: pure Kotlin logic (progression, builder, recommender, treadmill planner, export) with unit tests. Run `./gradlew :core:test`.
- `app/`: Jetpack Compose Android app. Build with `./gradlew :app:assembleDebug`.

## Releases

Pushing a tag like `v0.1.0` builds and signs an APK and attaches it to a GitHub Release (`.github/workflows/release.yml`). One-off setup: create a keystore, back it up privately, and add `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` as repository secrets. Never commit the keystore. Sideload the APK from the Releases page.
