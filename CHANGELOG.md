# Changelog

What changed in each release, in plain language, newest first. The release workflow copies the matching `## vX.Y.Z` section into the GitHub release, and the app shows it as "What's new" before you tap Update.

## v0.12.2

- Tell the app how much time you have: the Today's session card now asks "How much time do you have for lifting?" with big chips (20, 25, 30, 40, 50, 60 minutes, with your planned time marked). Tap one and the session rebuilds straight away: more time brings the upper-body lifts back, less time trims it, and the card shows what was added or left out. The chips also appear in the list view, and the choice only applies to that session.
- Include the hip prehab: a switch on the same card. Turn it off and those 10 minutes go to lifting and the prehab prompt does not appear afterwards.
- Squat stays at 3 sets when time is tight. The app now trims the Romanian deadlift or deadlift to 2 sets first, then the calf raise, then the warm-up ramps, and only then the squat. A 20 minute window now gives squat 3 sets, Romanian deadlift 2 and calf raise 2 (about 21 minutes, shown honestly), and 30 minutes gives 3 sets of squat and calf raise plus an upper-body lift.
- If you swap calf raises to seated before changing the time, the swap is kept.

## v0.12.1

- Pick what you are doing: a planned easy cardio session (the 30 minute cardio, for example) can be done on the rower, SkiErg, bike or the treadmill. The timer's setup now has a clear "What are you using?" card with big choices, instead of a small row of chips you could miss, and the Today card lists the options and says you choose when you start. Before, it started on the rower and saved the session as a rower session even when you were on the treadmill. The treadmill options open the treadmill screen, which sets the speed and incline for you.
- Your pick is remembered for the next easy session. Run-walk, hard and Hyrox sessions always follow your plan, so a remembered choice can never change a knee-gated run.
- If you picked wrong, fix it: the finish screen has "What did you use?" (rower, SkiErg or bike), and any session already in History that came from the guided timer can be changed afterwards. Long-press it, tap "Change equipment" and choose what you used, including the incline treadmill walk. Only the equipment changes; the date, time and how you felt stay, nothing is duplicated, and a safety copy of your data is taken first. A session with hard intervals keeps its structure, so the treadmill is not offered for it.
- Long-pressing a session in History now shows its date and type, and says that Delete is recoverable from "Recently deleted" for 90 days.

## v0.12.0

- One answer for "what next": the Home card, the evening reminder and the plan screens all get the same answer from one place, so they can no longer disagree (before, Home could say "easy aerobic" on one card and "treadmill day" on another).
- A simpler Home: one Today card shows today's planned session with a big Start button, or the next one when nothing is planned today (starting early is fine), or a calm rest or "nice work" message. Under it are "I have some time", "Skip today" and "Something different", which opens the time, energy and knee picker. This week is a compact ring with the week strip, level, XP and streak are one slim card, and the rarely used buttons (log a session, settings, export, backup, updates) are under "More...".
- Your good week now follows your plan: while the Hyrox plan is on, the ring counts the sessions the plan asked for that week, and extra sessions are a bonus, never a requirement. The evening reminder stops once the plan's week is done and no longer names an activity of its own. Your streak, XP, rest tokens and trophies are unchanged.
- After the race: from the Monday after race week (27 February 2027) the plan rolls into a steady maintenance plan: 2 strength sessions with hip prehab and 2 or 3 easy aerobic sessions a week, nothing hard, sized from what you have really been doing. Moves, skips, rest days, knee care and the knee-friendly running return all work the same. Set a new race date in Settings and it switches back to a race plan.
- Your goal on the plan: the current training block's goal (for example "Squat 90 of 100 kg") now shows at the top of the Plan screen as well as on Home. A goal never changes your sessions.
- Your data is safer than ever:
  - Restoring from Google Drive now adds only the sessions your phone is missing and keeps everything else; "Replace everything" needs a second confirmation. A safety copy is always taken first.
  - Deleting a session moves it to "Recently deleted" for 90 days, with a Restore button on Home.
  - If a save ever fails (for example the phone is full), Home says so with a Try again button instead of looking saved.
  - A save that would lose sessions is refused, and the app keeps daily safety copies plus one a week for 12 weeks.
  - If the app is closed or the phone dies in the middle of a gym session, the sets you had done are saved as a session next time you open it, with a note.
- Your saved data is upgraded to a new format the first time you open this version, and a safety copy is taken first. Nothing is rewritten or removed. Older versions of the app cannot open the new format, so update rather than going back.

## v0.11.3

- Seated calf raises: a Standing | Seated toggle sits right on the calf screen (and on other exercises that have a swap), so one tap switches. Seated calf raises are tracked as their own exercise with their own weight, starting at 50 kg, so they never drag down your standing weight, and each progresses on its own. If you switch after the first set, the sets you have done stay logged under the original variant. The app remembers whichever you used last.
- Today's session overview: a guided gym session now opens with a card listing every exercise with its sets, reps and load, the estimated time, a note if anything was left out to fit your time, and "Then: hip prehab (about 10 min)" when one follows. A big Start button takes you into the first exercise.

## v0.11.2

- Fixed: a planned strength session now builds the session it describes. Before, tapping Start treated the planned time like total time available and took 20 minutes off for gym travel, so only squats and calf raises were built. Now a 35 minute strength session is 25 minutes of lifting plus a 10 minute hip prehab.
- When time is tight, the app now trims each main lift to 2 sets (and shortens the squat warm-up) before dropping a lift, so you still get squat, a hinge (Romanian deadlift or deadlift) and calf raises, plus an upper-body lift when there is time.
- The hip prehab is now a real guided step: after the lifting is saved, the app offers "Next: hip prehab (about 10 min)". It is a guided hip-only version of Knee care using only the kit you own, and it counts toward your weekly Knee care. Skipping it is fine.
- The planned session text now matches what the app does instead of naming specific exercises it might not build.

## v0.11.1

- Knee care tells you what you need before you start: a "You'll need" checklist on the setup card and a "Get ready" screen with a big Ready button, so nothing has to be fetched mid-routine.
- My home kit: set what you own at home (step, band, chair, mat, kettlebell weight). The routine only ever uses what is on that list, and the setup card shows the real length (single-leg work takes both sides, so it can be about 18 minutes).
- No step? No problem: the step-downs are replaced by single-leg Romanian deadlifts and slow single-leg squats to a chair, which get harder with a slower lowering, the kettlebell and more reps.
- Home calf raises now progress without heavy weights: one leg, then one leg with the kettlebell, then a slow 4 second lowering with a pause, then more reps up to about 25, with a longer pause. You start at the level that suits you, and move up when sets feel easy. Your heavy gym calf raises stay the strength work.
- After each set in Knee care, tap Too easy, Just right or Too hard. Too easy makes the next set harder straight away and shortens the rest to 30 seconds. It also moves you up a level next time.

## v0.11.0

- Guided gym sessions, now the default: one set at a time with a huge "5 reps", the load and your last time's numbers, small buttons to correct the weight or reps, and one large "Done, next" button. Then an optional effort row, a rest countdown with a beep and a "Next up" preview. Warm-up sets are steps too, there is a short "Next exercise" screen between exercises, and if you miss reps, "Rough day" and "At my limit" are two large buttons. Swap, skip, add and reorder live in a small More menu, and Overview opens the old list. A guided session saves exactly the same record as the list, so your progression is unchanged. You can switch it off in Settings.
- The mat/core routine is now progressive: planks and other holds grow by 5 seconds (up to 60) after sessions that feel easy, or move to a harder variant, and step back after a hard or skipped session. The setup card shows it, for example "plank 30 s to 35 s". Core sessions and hikes now save the moment they end.
- A guided timer for hikes and walks: a big stopwatch with Pause and Finish, optional halfway and finish beeps, distance and climb at the end, and a gentle offer for how long to go next time (never more than about 10% more).
- Rep-based steps in Hyrox sessions read "Do 25 reps, then press Done" with a large button.
- A written audit of every kind of session is in docs/guidance-audit.md: how it is guided, recorded, tracked and progressive.

## v0.10.0

- Knee care: a guided 15-minute routine for the hips and calves, from Home or the Race plan. Hip abductor and glute work, then weighted calf raises, then a cool-down. You can do it any day, even a rest day or after another session. It is not counted as one of your workouts, never uses up a rest day, and skipping it never affects your plan.
- Calf raises are heavy by default: 3 or 4 sets of 6 to 10 slow reps (3 seconds up, 3 down) with a challenging load, or switch to higher reps (3 x 12 to 15). It tracks your home calf weight separately from the gym, shows what you did last time, and adds weight when you reach the top of the rep range. A quick "Just calf raises (8 min)" option is on the setup card.
- Rep-based steps are simple: a huge "Do 8 reps" card with the tempo and load, small buttons to correct the weight or reps, and one large "Done, next" button, then an optional effort row and a rest countdown.
- The Race plan shows "Knee care 0 of 2 this week" with Start buttons, plus a gentle nudge if you have done fewer than two calf sessions by Thursday evening.

## v0.9.1

- Swiping back (or the back key) now goes to the previous screen instead of closing the app. Home still closes the app, as usual.
- During a live session (gym, treadmill, guided timer, Hyrox session or simulation) a swipe back asks "Leave this session?" with Keep going as the default, so you cannot lose a session by accident. Nothing is deleted without a second, deliberate tap.
- On the treadmill's "How did it feel?" screen, back acts like Skip (the session is already saved), and on Home it first dismisses the celebration card.

## v0.9.0

- Hyrox simulation mode: on the Hyrox page, start a full simulation (8 runs and 8 stations) or a half (stations 1 to 4 or 5 to 8) in true race order at your division's loads, or at 90% or 80% for a first go. The week 9 half sim, the week 10 circuit and the week 11 rehearsal open it straight from your plan.
- Timed transitions: between every run and station there is a transition with a countdown (race-like 20 s, gym setup 45 s, home setup 90 s, or your own), a prep prompt for what comes next ("Load the sled to 152 kg", "Grab the 6 kg wall ball"), and a Ready, go button. The race clock never stops, so the time counts in your total, as in a real race. Going over is shown calmly and is never a penalty.
- Run legs follow your knee: if running is not ready, each 1 km run becomes a knee-safe substitute (1 km incline walk, 1000 m on the rower or SkiErg, or 3 km on the bike). Treadmill runs count down at your set speed.
- Results: your total time, a table of splits for every leg, running, stations and Roxzone time shown separately, a comparison with your last sim of the same format in kind words, and one thing to work on. A sim saves as soon as it ends, and you can pause, skip a leg or end early.
- Station finisher: after a strength session with a finisher, the app now offers to start it as a guided station session instead of only showing a card.

## v0.8.2

- Tuesday and Friday are now your default rest days, and the plan schedules around them. You can still train on a rest day if you fancy it. A week strip on the Race plan screen shows which days are lift, hard, long, free or rest.
- Rearrange a week: every planned session has a Move button. Pick another day this week, with rest days marked and a gentle hint if it puts a hard session next to a strength day.
- Skip a session: tell it why (not feeling well, no time, other). It is never a failure. The session moves to the next free day if one is left that week, otherwise it is simply dropped for the week with no catching up, and later weeks stay as planned. Undo is always there.
- "I have some time": say how long you have and how you feel, and it suggests the most useful planned session that fits, with an alternative. On a rest day it says so. If the week's sessions are done, it offers an easy bonus session.
- A calm "rough stretch" card appears if several planned sessions have been skipped in about 10 days, or the last two weeks were light. It has a button to copy a summary to paste to Claude for a fresh plan. It never nags.

## v0.8.1

- The race plan now starts from tomorrow. A short Kick-off covers Friday to Sunday (one easy session and one light strength session), so "This week" is no longer next week's sessions. Week 1 still starts on Monday 23 Nov, and the 14-week structure and the race-week timing are unchanged.
- The plan now starts from the minutes you have really been training (about 115 a week) instead of a fixed minimum, and builds about 10% a week from there. Your first week is about 125 minutes. The peak is lower than before (about 240 minutes) because it follows your real starting point, which is the safer way to rebuild with a knee to protect.
- If you have already trained today, the plan says so plainly: nothing more needed today, it starts tomorrow.

## v0.8.0

- A Hyrox race plan for your simulation on Saturday 27 February 2027: 14 weeks of base, build, specific work, a sharpening week, a two-week taper and race week. It is mostly easy work with about 20% hard and Hyrox-specific sessions, strength on separate days, and a taper that cuts volume while keeping intensity. It starts from what you actually do, never asks you to catch up, and repeats a week if it was a light one.
- Set for you: 4 sessions a week and at most about 5 hours (300 minutes) in any week. You can change the race date, sessions (3 to 6) and the weekly cap on the Race plan screen.
- Your knee comes first: running returns in stages (run-walk, continuous, intervals, runs between stations) and only moves on after two calm runs in a row (knee 2 out of 10 or less). The rower, SkiErg, incline walking and bike carry the aerobic work meanwhile. If pain persists, it suggests seeing your physio.
- The Race plan screen: a countdown, a 14-week timeline, this week's sessions with a Start button for each, an easy/hard balance gauge and the running stages. The same session shows on Home and as the first suggestion when you start a session.
- Every planned session can be started: strength opens the gym (with a station finisher card), Hyrox sessions open the guided Hyrox session, treadmill sessions (including run-walk) open the treadmill setup, and rower, SkiErg and bike sessions open a new guided timer with beeps. They are all saved and counted towards your balance automatically.
- The evidence and its limits are written up in docs/hyrox-plan-evidence.md.

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
