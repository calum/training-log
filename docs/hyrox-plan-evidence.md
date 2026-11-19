# Race plan: what it is and what it rests on

This is the evidence note for the race-preparation plan engine (`core/.../RacePlanner.kt`, `RunStage.kt`, `IntensityBalance.kt`). It is written to be honest about how strong each rule is. Where the research is thin, it says so.

**Not medical advice.** The app is a training tool, not a medical device. The knee rules below come from physio practice and a clinical review, not from trials. If knee pain persists for more than a couple of weeks, please see your physio.

## The plan in plain language

- **Event:** a HYROX simulation on Saturday 27 February 2027, assumed to be the full format (8 x [1 km run + station]) at Men's Open loads, until told otherwise.
- **Length:** 14 Monday-Sunday weeks, counted back from the race week. Week 1 starts Monday 23 November 2026; race week starts Monday 22 February 2027.
- **Phases:** Base (weeks 1-3, lighter week 4), Build (weeks 5-7, lighter week 8), Hyrox-specific (weeks 9-11), Sharpen (week 12), Taper (week 13), Race week (week 14).
- **Mostly easy:** about 80% of endurance time is easy (RPE 4 or less), about 20% is hard or specific. Strength is counted separately.
- **Weekly template at the default 5 sessions:** 2 strength sessions on non-consecutive days, 1 key session (the hard part), 1 long easy session (RPE 4 or less, about 40 minutes growing to about 105), 1 easy aerobic session (25-55 minutes). With 3-6 sessions a week the easy extras go first (second easy, then easy, then long) and the key session and two strength sessions always stay.
- **Knee-sparing volume:** the rower, SkiErg, incline walking and the bike carry the aerobic volume while running is brought back in stages, gated by the logged knee scores.
- **Adaptive, never guilt:** a week with under 60% of its planned time simply repeats its targets, there is no catching up, feeling ill or rough drops the key session first, a break of 10+ days steps a week of load back, and the taper and race week stay fixed to the race date.

### Default 5-session outline (starting from the 200 min/week default baseline)

| Wk | Starts | Phase | Planned min | Key session |
|---|---|---|---|---|
| 1 | 23 Nov | Base | 200 | Aerobic intervals 3 x 6 min, RPE 6-7 |
| 2 | 30 Nov | Base | 215 | 4 x 6 min, RPE 6-7 |
| 3 | 7 Dec | Base | 235 | 4 x 6 min, RPE 6-7 |
| 4 | 14 Dec | Base, lighter | 185 | 3 x 6 min |
| 5 | 21 Dec | Build | 255 | VO2 intervals 6 x 4 min, RPE 8-9 |
| 6 | 28 Dec | Build | 275 | Threshold 4 x 8 min, RPE 7-8 |
| 7 | 4 Jan | Build | 300 | VO2 intervals 6 x 4 min |
| 8 | 11 Jan | Build, lighter | 225 | 4 x 4 min |
| 9 | 18 Jan | Specific | 325 | Half simulation, stations 1-4 at race standard |
| 10 | 25 Jan | Specific | 350 | Circuit, stations 5-8 at race standard |
| 11 | 1 Feb | Specific | 380 (peak) | Near-full simulation rehearsal, RPE 7-8 |
| 12 | 8 Feb | Sharpen | 325 | Race-pace pieces |
| 13 | 15 Feb | Taper | 220 | Short race-pace pieces |
| 14 | 22 Feb | Race week | 160 | Sharpener Monday; rest Friday; race Saturday |

The numbers move with the logs: the starting volume comes from the last 4 weeks of logged minutes (default 200 when there is little history), and any week can be repeated or stepped back. Use `RacePlanner.build` for the live values.

## Rules, sources and how strong the evidence is

### 1. Mostly easy, about 20% hard

- **Rule:** target at least about 80% of endurance time easy, at most about 20% moderate to hard. Weekly easy share is planned (every week of the default plan is 82% or higher) and measured from the logs (`IntensityBalance`).
- **Source:** Silva Oliveira, Boppre and Fonseca, Sports Medicine 2024, a meta-analysis of 17 studies: https://pmc.ncbi.nlm.nih.gov/articles/PMC11329428
- **What it found:** polarized training modestly improved VO2peak (standardised mean difference 0.24), mostly in shorter interventions and in highly trained athletes. There was no clear advantage for time-trial or threshold performance, and in interventions over 12 weeks the distributions performed similarly.
- **Strength: moderate for VO2peak, weak for race performance.** So a low-intensity-dominant plan is a sound, injury-safe default, not a magic formula. The exact 80/20 line is a convention. The app's RPE 7 or more line for "hard" is also approximate (see Limits).

### 2. Taper: about 2 weeks, volume down 41-60%, intensity and frequency kept

- **Rule:** week 13 is 42% below the peak week and race week 58% below, with the key session still at race pace, the same number of sessions in the taper week, and strength kept at the same weights with fewer sets. Week 12 (sharpen) is about 85% of the peak.
- **Source:** Bosquet et al., Medicine and Science in Sports and Exercise 2007, a meta-analysis of 27 studies: DOI 10.1249/mss.0b013e31806010e0, summary https://coachsci.sdsu.edu/csa/vol131/bosquet.htm
- **What it found:** the best taper was about 2 weeks with training volume cut exponentially by 41-60% while keeping intensity and frequency. Cutting intensity alone did not help. The gain was about 3%.
- **Strength: strong** as meta-analytic evidence for endurance athletes. It is less directly proven for a mixed strength-and-endurance event like Hyrox, and a 3% average gain is an average, not a promise.

### 3. Run volume matters for Hyrox, and so do the stations

- **Rule:** keep the aerobic base large (long easy and easy sessions), and also practise every station: every station is visited at least once per two weeks, and every station is done at race standard (ladder level 5) at least once before the week-11 rehearsal (stations 1-4 in the week-9 half-simulation, stations 5-8 in week 10).
- **Source:** the first scientific Hyrox study, n = 11 recreational athletes, simulated race: https://www.ncbi.nlm.nih.gov/pmc/articles/PMC11994925/
- **What it found:** running was the largest share of time (median 51.2 of 86.5 minutes, about 59%); aerobic fitness (VO2max) correlated with total and run time but not with station time. The authors suggest prioritising endurance.
- **Strength: weak.** Eleven people, one simulation, correlations only. Treat it as a hint. Coaching sources say a weak station can undo a good runner, which is why stations are trained too; that point is practitioner opinion, not research.

### 4. Strength and endurance on separate days

- **Rule:** two strength sessions a week on non-consecutive days, never the day before the key session if it can be avoided, and the hard endurance session is never on a strength day. If they must share a day, keep the hard endurance away from the strength.
- **Source:** concurrent strength and endurance training meta-analysis (Schumann et al.), summary: https://sci-sport.com/en/concurrent-training-does-cardio-affect-muscle-mass-and-strength-gains-233/
- **What it found:** maximal strength and hypertrophy are not compromised by concurrent training; explosive strength is attenuated when both are done in the same session compared with sessions separated by about 3 hours or more.
- **Strength: moderate.** Good evidence that maximal strength is safe; the explosive-power effect depends on the study set. Separating days is a cheap precaution, not a necessity.

### 5. Knee-gated return to running (ITB)

- **Rule:** see "Knee safety rules" below. Strength with hip-abductor and glute work (10 minutes in every strength session), then graded run-walk, with running only added to the plan once the logs say the knee allows it.
- **Sources:** https://www.physiotutors.com/wiki/itb-syndrome-rehab and a systematic review in Frontiers in Sports and Active Living 2024: https://frontiersin.org/journals/sports-and-active-living/articles/10.3389/fspor.2024.1386456/pdf
- **What it says:** a staged return (calm irritability, hip-abductor and heavy-slow strength work, then graded running re-introduction with walk/run intervals and lower treadmill incline over time); persistent pain means seeing a physio.
- **Strength: clinical consensus.** It rests on clinical practice, not randomised trials. The specific thresholds the app uses (knee 2/10 or less, 7 days after a flare, 2 or more clean runs) are sensible heuristics set by this app, not published cut-offs.

### 6. Things that are heuristics, not strong evidence

These were not researched and are labelled as such in the code:

- Weekly load increases of roughly 10% or less (the common "10% rule" has weak support in the literature).
- A lighter week every 4th week (weeks 4 and 8, about 20-25% less).
- Session RPE for monitoring.
- The exact session lengths, shares, hard-minute budgets and the 60% "repeat the week" threshold.
- Run-walk ladder rungs (1/2, 2/1, 3/1, 5/1 minutes) and the continuous steps (10, 15, 20, 30 minutes).

## Knee safety rules

The plan only schedules running that the current `RunStage` allows. The stage is computed from the logs on every build:

| Stage | What it allows |
|---|---|
| NONE | Walking and incline walking only; rower, SkiErg, bike |
| RUN_WALK | Run-walk at 1% incline: 1/2 min x 8, then 2/1, 3/1, 5/1 |
| CONTINUOUS | 10, then 15, 20, 30 minutes of continuous easy running |
| INTERVALS | Run intervals; 400 m of each run leg in the half-simulation |
| RACE_RUNS | Compromised runs after stations; 1 km run legs in the rehearsal |

- **Start:** NONE, because he has been walking only. He leaves NONE after 2 clean runs (if he is already running) or after 14+ calm days with at least 4 knee-rated sessions, all 2/10 or less, and no flare in the last 7 days. A history of 8+ clean running days in 28 lifts the stage to at least CONTINUOUS.
- **Advance:** a stage advances only after enough consecutive clean runs on different days (2 from NONE, 4 from RUN_WALK, 4 from CONTINUOUS, 2 from INTERVALS), each with knee 2/10 or less and at least 7 days after any knee score of 3 or more.
- **Hold:** a knee score of 3-4 holds the stage (the clean-run count restarts). Nothing is lost.
- **Step back:** a knee score of 5 or more, or two sessions in a row at 4 or more, steps back one stage. The message says so gently and adds: "If the knee pain persists for more than a couple of weeks, please see your physio. This app is not a medical tool."
- **Running volume:** the run-walk and continuous steps are gated by knee reports rather than the calendar, so single steps can be larger than 10%. Overall weekly training time never rises by more than about 10% a week. No two run days in a row before the INTERVALS stage.
- **Knee-heavy stations** (burpee broad jumps, sandbag lunges) ramp more slowly than the others (level 2 by the end of Base, not 3) and are capped at level 3 while the knee is flared or has just stepped back. Where the plan cannot use running, it says so and uses the rower, SkiErg or incline walking for the run legs instead.
- **What counts as a run:** a Hyrox session with `run` station logs, a treadmill session averaging 6.6 km/h or more (from the estimated distance and time), or a treadmill session whose label starts with "Run". Run-walk sessions logged as ordinary walking will not move the stage, so the screens should label them.
- **Honest outlook:** if the knee stays calm, the earliest the plan projects the INTERVALS stage is about week 9 and RACE_RUNS about week 11. If it takes longer, the rehearsal uses substitutes for the run legs. Running 8 x 1 km in the event itself may need a run-walk or walk plan; that is a decision to make with a physio, not with this app.

## Assumptions

- **Format:** the full Hyrox format (8 x [1 km run + station]) at Men's Open loads. If the event is a half or a doubles format, change the plan (the station ladder and loads come from the Hyrox standards in the app and can be edited).
- **Availability:** a parent of two young kids training in the evenings, 3-6 sessions a week (default 5). Sessions go on days that are not set as rest days; if fewer days are free, the easy extras drop first.
- **Starting fitness:** a strong aerobic base from a half-marathon background (50-60 km/week before a recent break) but out of routine, so the plan starts from the logged volume, default 200 min a week, not from the old running volume.
- **Division:** Men's Open (from Settings), with kit from the gym and home kit ticks. Stations without kit at the gym are replaced by the guide's alternatives.
- **Today:** the plan was designed for a start on Monday 23 November 2026, with the first week ahead from Thursday 19 November 2026.

## Limits

- **The easy/hard classification is approximate.** The app does not record a session RPE, so logged time is sorted by session type and station RPE (see `IntensityBalance`). Use "about 80/20" as a rough nudge, not a measurement.
- **Three sessions a week cannot be 80/20 by time.** With two strength sessions and one key session, the key session is the only endurance time, so the planned share is about 55-60% easy. The plan says so and still keeps the key session short.
- **Four sessions a week is about 80/20, plus or minus 3 points.**
- **The plan is a guide.** It adjusts to what was done, never marks anything as a miss, and nothing in it is a guarantee about race day.

## Sources

1. Silva Oliveira R, Boppre G, Fonseca H. Sports Medicine 2024, intensity distribution meta-analysis (17 studies): https://pmc.ncbi.nlm.nih.gov/articles/PMC11329428
2. Bosquet L et al. Medicine and Science in Sports and Exercise 2007, taper meta-analysis (27 studies), DOI 10.1249/mss.0b013e31806010e0; summary: https://coachsci.sdsu.edu/csa/vol131/bosquet.htm
3. First scientific Hyrox study (n = 11): https://www.ncbi.nlm.nih.gov/pmc/articles/PMC11994925/
4. Schumann et al., concurrent strength and endurance training meta-analysis; summary: https://sci-sport.com/en/concurrent-training-does-cardio-affect-muscle-mass-and-strength-gains-233/
5. ITB rehab (practitioner): https://www.physiotutors.com/wiki/itb-syndrome-rehab
6. Frontiers in Sports and Active Living 2024, systematic review: https://frontiersin.org/journals/sports-and-active-living/articles/10.3389/fspor.2024.1386456/pdf
