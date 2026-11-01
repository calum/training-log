# Changelog

What changed in each release, in plain language, newest first. The release workflow copies the matching `## vX.Y.Z` section into the GitHub release, and the app shows it as "What's new" before you tap Update.

## v0.6.1

- A slim session clock at the top of the gym screen: time so far against the lifting time the plan was fitted to. If you go over, it just turns amber and says how many minutes over.
- When you finish a gym session you now see how long it took compared with the plan, and the duration is saved with the session.

## v0.6.0

- A new Progress screen: how your lifts have really moved, sessions done each week, treadmill minutes, totals and personal records. It only ever shows what you did.
- Your saved data is now protected: the app keeps daily safety copies and restores from one automatically if the main file is ever damaged.
- Release notes: this "What's new" list now appears before you tap Update.
- A polish pass on the core session, Blocks, Backup & sync, Manual log, Export and the update banner.

## v0.5.0

- A guided core routine: 10 to 30 minute knee-friendly mat sessions with timed work and rest, form tips and beeps. It changes every time.
- An optional, gentle evening reminder. It is off by default and never makes you feel guilty.
- Badges now follow the weekly targets you set in Settings.
- Sign in to GitHub from the app with a short code, restore your numbers from GitHub, and deleted sessions now sync too.
- A design pass: more compact gym sets, a readable forecast chart, clearer badges and a tidier Settings and treadmill setup.

## v0.4.0

- Settings: change starting weights, increments, rest times, weekly targets, gym travel time, deload timing and treadmill warm-up.
- Training blocks of 4 to 8 weeks with a headline target, progress on Home, a block-end summary to paste into Claude and a suggested next block.
- Easing back in: after 3 weeks or more off, your first 3 gym sessions are lighter.
- The Plan screen now forecasts upper-body lifts too, and the "Next up" card names the real session.
- More reliable treadmill sessions: beeps still play over YouTube, the timer catches up after a stall, and pause and plus/minus 30 seconds work from the notification and the floating window.
- Your own exercises remember which movement group they belong to.

## v0.3.0

- GitHub sync: after each session the app sends only your public numbers (weights, reps, treadmill sessions) to your data repo, which redraws a progress chart. Feelings, knee scores and notes are never sent.
- Google Drive backup: your full private data is saved to a hidden app folder in your own Drive, with a restore button.
- A new Backup and sync screen to set both up.

## v0.2.1

- A more flexible gym routine: sessions are built from slots (squat, calf, a hinge that alternates deadlift and Romanian deadlift, and the upper-body movements you trained least recently).
- Remove, swap or reorder any exercise on the day, or add one from the library.
- Add your own free-text exercise. It is flagged "CUSTOM" in the export so it can be added properly later.

## v0.2.0

- A full redesign with a dark look, a Home dashboard with a weekly ring, XP and levels, trophies and personal records, and confetti when you finish.
- A no-guilt streak with rest tokens, so a missed week is normal.
- Rest days: tap a day on the week strip to mark it. You can still tap "I've got time" to train anyway.
- A 12-week Plan screen with conservative, expected and best-case forecasts for each lift.

## v0.1.2

- Behind the scenes: the rewards engine and the long-term forecast that the redesign builds on.

## v0.1.1

- Update from inside the app: it checks the latest GitHub release and installs it, and Android asks you to confirm.
- Rest days and the week plan, behind the scenes.
- Log a session you already did without starting an activity.

## v0.1.0

- The first version. Tell it how much time and energy you have, and it recommends and builds a session.
- Gym sessions with pre-filled sets, reps-in-reserve ratings, warm-ups and a rest timer.
- Progression that adds weight only when you hit every rep with 2 or more in reserve, with deloads and an easier restart after time off.
- Treadmill sessions (steady, pyramid, ladder, intervals and long variants) with beeps before each change and calibration from your "too easy / just right / too hard" feedback.
- A light hike log, session history and a one-tap text export to paste into Claude.
