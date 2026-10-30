# Design review of the rendered screens

Reviewed from the PNGs here (dark theme, 411x914 dp). Not opened in detail: export, manual-log, light-hike/core, plan-empty,
home-empty, start-default, treadmill-player-flashing (they rendered without errors). Scrollable screens only show the first viewport,
so anything below the fold is unreviewed.

## Real problems
1. **FIXED** **Plan, lift forecast chart y-axis**: labels read `30517578`, `35058594`, `64941406`, `94824219`, and are clipped at the left edge. Looks like
   raw float/bit values rather than kg (the stat tiles say 102/115/125 kg). Fix the `yFormat` (round to whole kg) and give the axis a left gutter.
2. **FIXED** (tiles shrink-to-fit, compact padding; lower plan now shot in plan-2..5.png) **Plan, week tiles**: "WEEK 4" value shows `102.` and is cut off while WEEK 8/12 fit; the three tiles are too narrow for 5 characters + unit. Put
   the unit on its own line or shrink `numberM`, and use `maxLines=1` with autosize.
3. **FIXED** (per-badge glyphs, lock on locked, "4 Sep" dates, no duplicate level name; see trophies-2..4.png) **Trophies, badges**: every badge uses the identical white star, so unlocked badges are indistinguishable; dates are raw ISO
   ("Earned 2026-09-04"). Give badges distinct icons and format dates ("4 Sep"). The level card repeats "Trail Blazer" twice (title and XP bar).
4. **Gym, set cards**: each set card is ~350 dp tall (weight stepper plus reps stepper plus RIR row), so one exercise with 3 sets fills about
   three screens; the progress is hard to scan. Collapse completed sets to one line (set, kg x reps, RIR) and keep only the active set expanded.
   The warm-up chip row is cut off at the right edge with no scroll hint (4th chip half visible). The rest bar overlaps/clips the card behind it
   (hard cut line); add a scrim or bottom padding. The orange selected "3+" RIR chip uses the energy colour, inconsistent with mint "done".
5. **Treadmill setup**: the segment list repeats 15 near-identical rows ("2:35 Push +2 ..."), long and monotone. Group runs of equal values or show
   only the chart plus the next 3 segments. The chart has no axes or units, the speed line and incline bars share an unlabelled scale.
6. **Treadmill player (normal)**: big and legible, but "Push +2" pill at the very top with SPEED label right under looks cramped against the
   status bar; huge empty gap between the unit "km/h" and the INCLINE/TIME tiles is fine. Incline "10.0 %" has a floating small %.
7. **Treadmill flash state**: full-screen mint with dark text is high contrast (good) but the small "CHANGE" label and the "Next:" line are the
   only context; no timer is visible at all while flashing. PiP (320x180) shows only "5.9 km/h / 10% incline": good and readable, but
   no remaining time or segment name, so it is not obvious when the change is coming.
8. **Start**: knee row is two wrapped lines of 0-10 circles plus an inline "Skip" chip in blue (a third accent colour for a non-action);
   the time chips wrap into 4+2. Consider a slider or a single row, and make Skip a text button. Suggested-card text is white on a
   blue/violet gradient: contrast is OK but the secondary line is lower (~4:1).
9. **Home**: dense but good. "0 rest tokens" chip looks like an error/empty state (dim blue on dark); the day dots have no weekday-today
   emphasis beyond the ring; "Tap a day to make it a rest day..." helper text is permanent and low contrast. Celebration overlay: card is
   fine but the stat line "(Session 40, Effort 67, New style 15)" reads like debug output.
10. **Settings**: a plain 20+ row list with tiny green "Edit" text links (touch target well under 48 dp, row is tappable only on the link?).
    Mint on dark for text links is OK contrast but looks unstyled compared with other screens; the Lifts block is far too long to be the
    first card. Group by lift category and make the whole row tappable with a chevron.
11. **Blocks**: reasonable; the chips for "Optional smaller goals" are full-width-ish pills stacked one per line, wasting space; violet selection
    colour differs from mint selection elsewhere (deliberate "reward" colour, but inconsistent with Start/Plan chips). Primary button is below the fold.
12. **Backup & sync**: very text-heavy; four stacked outlined buttons (Show/Paste/Save/Test connection) of equal weight, with Save in mint
    text and Test disabled dim (low contrast). Token field has no label unlike the others.
13. **Treadmill done**: top-aligned content with ~55% empty screen below; the three effort cards are good, but center/space the content.
14. **Back button inconsistency**: Plan/Blocks/Trophies have a top-right or top-left "Back" outlined button (Trophies at left, others at
    right), Settings/Backup/Treadmill-setup have none visible.

## Probably fixture/Robolectric artefacts, not real bugs
- Home/Plan/Settings are cut at the viewport; the real screens scroll.
- Emoji on the Start feeling chips render via Robolectric's bundled font and may differ on devices.
- Dates are relative to the run day, so "Earned" dates and weekday dots vary between runs.
- Animations (confetti, count-ups) are captured at their settled/first frame.

## Update (plan/trophies pass)
Chart now has rounded y ticks, left gutter sized to labels, grid lines, end-of-line labels and "Wk n" x labels. Also fixed: truncated what-if chips (shorter labels), milestone pill alignment.
