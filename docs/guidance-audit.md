# Guidance audit

Goal: everything is guided, recorded, tracked and progressive. Rep-based work gets big buttons ("do 15 reps, then press Next"); time-based work keeps its timers. Each row says what the flow was BEFORE this change and what it is AFTER. Gaps are marked honestly.

Legend: GAP = something missing; ok = fine as it was; FIXED = changed in this release.

| Flow | Guided how | Rep-based steps | Recorded | Tracked | Progressive |
|---|---|---|---|---|---|
| Gym (working sets) | BEFORE: scrolling list of exercise cards, tick Done per set (GAP: not step by step). AFTER: Guided mode (default, Settings switch) shows one set at a time; list still available as Overview | BEFORE: stepper rows, no "do N reps" prompt (GAP). AFTER: `RepStepCard`, huge reps, load, small steppers, one 96 dp "Done, next" | Yes, same record from either mode (`finishGym`) | Last-time line, set progress bar, session clock | Yes: add weight at RIR >= 2, back-off on misses, deload, easing back in (unchanged) |
| Gym warm-up sets | BEFORE: tappable chips (GAP). AFTER: "Warm-up 2 of 5" steps with Done, next; "Skip warm-ups" in More | Same card, no steppers | Not saved (by design, as before) | Shown in "Next up" | Ladder follows the working weight |
| Accessory / upper work | Same as gym (steps) | Same | Yes | Yes | Double progression (reps to top of range, then weight), unchanged |
| Between exercises | AFTER: "Next exercise" card with the plan note, load, rest, last time, Start | n/a | n/a | n/a | Shows the progression note ("add 2.5 kg") |
| Rest between sets | BEFORE: bottom bar. AFTER (guided): ring, optional RIR row, Skip rest, Next up; beep and flash as before | n/a | RIR saved on the set | n/a | RIR feeds progression |
| Missed reps | BEFORE: inline question. AFTER: two large buttons, never blocks | n/a | `missReason` saved | n/a | Drives back-off |
| Station finisher | Offered after Finish, then guided Hyrox session | See Hyrox rows | Yes | Yes | Level ladder, unchanged |
| Treadmill (walk, run-walk, intervals, ladders, pyramids) | Step by step timer, beeps, auto-saved when finished | None (all timed) | Yes, auto-saved, effort edits record | Distance, climb, kcal | Calibration from effort; run stage gate; unchanged |
| Core / mat routine | Step by step timer (ok). Some moves say "about N slow reps" inside a timed window (ok, timed) | Timed window, rep pill | Autosaved when it ends (or is ended early, one minute or more); the finish card only edits it | Moves and minutes | BEFORE: GAP, none. AFTER: FIXED, per-exercise seconds and variant in `Profile.coreProgress`: finished and fine = +5 s (cap 60 s, then a harder variant), skipped or too hard = a step back, rough day or long break = repeat. Setup card shows "Progress: Front plank 30 s -> 35 s", finish card "You progressed" |
| Knee care | Step by step, autosaved | Calf sets: big rep steps, rest steps | Yes, autosaved | Calf load and reps, last time | Calf double progression (own track, untouched) |
| Guided rower / SkiErg / bike timer | Step by step timer, autosaved | None (timed) | Yes | Distance, RPE | Plan ladder |
| Hyrox station session | Step by step: Go, Done, rest, rate | Wall balls and other rep stations: BEFORE a plain "Done" (GAP in wording). AFTER FIXED: "Do 25 reps, then press Done", 96 dp button | Yes, autosave-style save at end | Per-station logs | Station level ladder |
| Hyrox simulation | Step by step legs and transitions | Wall ball leg: AFTER FIXED "Do 100 reps, then press Done" | Autosaved when it ends | Splits, comparison | Preset loads, targets |
| Hike / walk | BEFORE: log only (GAP, nothing guided). AFTER FIXED: big stopwatch, Pause, double-tap Finish, optional target with halfway and finish beeps, distance and climb steppers | n/a | BEFORE: only after the fact. AFTER: saved the moment Finish is pressed; the card only edits it | Minutes, km and climb in the note | AFTER: offer of the next long walk, about +10% (90 -> 100 min), never forced, same length after a rough day or sore knee, shorter after a long break |
| Quick logs / manual log | Form (by design, for sessions already done) | n/a | Yes | Appears in history and export | n/a. Kept; hike has "Already done it? Log it" |

Remaining honest gaps: the hike beeps only play while the app is open (the clock itself keeps counting with the screen off); warm-up sets are still not stored; the manual log has no progression by design.
