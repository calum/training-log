# Changelog

What changed in each release, in plain language, newest first. The release workflow copies the matching `## vX.Y.Z` section into the GitHub release, and the app shows it as "What's new" before you tap Update.

## v0.7.1

- Hyrox at home: choose Gym or Home & garden when you set up a session. Tick the equipment you have in each place on the Hyrox page (the gym defaults to everything; at home you start with just the treadmill, so tick the wall ball or sandbag when you buy them). Home sessions only use stations you can do there, and tell you what is missing with a substitute.
- Treadmill runs between stations: with a treadmill ticked, a run block tells you the speed to set, counts down the time for the distance with a warning beep and a finish beep, and lets you add 30 seconds, finish early or skip. Choose 200, 400, 600 or 800 m. Runs are off by default and are not offered when your last knee score was 4 or more.
- Runs are logged with distance and time, appear in your history and Claude export, and you can log a run done outside the app.
- A small Needs: line on each station page, and the no-equipment alternatives are more prominent when you cannot do a station where you are.

## v0.7.0

- Hyrox is here. A new Hyrox button on Home opens a hub with all eight stations in race order: SkiErg, sled push, sled pull, burpee broad jumps, rowing, farmers carry, sandbag lunges and wall balls. Pick your division (Men's or Women's, Open or Pro) and you see the loads for it.
- Each station has a page with short technique tips, common mistakes, a knee note where it matters, a pacing tip, what to use if your gym lacks the equipment, and a six-step progression from learning the movement up to race standard. The app suggests your next session and moves you up when a session feels controlled; a hard day just repeats the level.
- A guided Hyrox session picks a few stations to fit your time, with a lap timer, effort and technique ratings, and rest timers. Easy runs between stations are off by default.
- A quick log for a station you have already done, with weight, distance or reps, and time.
- Hyrox sessions count in your history, progress, trophies (three new badges), personal records and the Claude export.
- Station standards come from third-party sources; confirm them at hyrox.com. You can edit the race weights.
- Shortened Hyrox blocks no longer count as "incomplete", and the intro cards on Hyrox and Progress are easier to read.

## v0.6.2

- Fixed the freeze at the end of a treadmill session. The screen could get stuck on the green flash with no way to finish. A finished session is now also saved the moment it ends, so it can never be lost; the "how did it feel?" question just adds to it.
- The treadmill screen now shows small boxes for distance (metres), elevation (metres climbed) and calories, and the finish screen shows the totals. They are estimates from your speed, incline and body weight, which you can set in Settings under Treadmill. The numbers are saved with the session and included in the Claude export.

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
