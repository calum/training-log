# Design system (`com.calum.traininglog.ui`)

Dark-first, bold, rounded, no guilt. Wrap everything in `TrainingTheme { }` (dark default, `darkTheme = false` for light). Read colours via `AppColors.*`, spacing via `Spacing.*`, shapes via `AppShapes.*`.

## Palette (dark)
| Token | Hex | Use |
|---|---|---|
| background / surface / surfaceRaised / surfaceHigh | 0B0E14 / 131824 / 1B2230 / 252E40 | canvas, cards, tiles, pressed |
| primary (mint) / primaryDeep | 3DF5B0 / 14B987 | progress, done, main action |
| energy (orange) / energyDeep | FF9A3D / FF6B2C | effort, streak, bonus ring, timer warning |
| reward (violet) / rewardDeep | B08CFF / 7B5CFF | XP, levels, badges, extra-credit |
| info | 4CC9F0 | neutral data, incline, rest |
| textPrimary / textMuted | F4F7FB / 9BA7BA | text |
| gradientHero / gradientPrimary / gradientEnergy / gradientReward | | brushes |

There is no red. Missed days are a faint neutral dot (`DayState.PastEmpty`).

## Type
Material scale in `TrainingTheme` (display 72/56/40, headline 32/28/22, title 20/17/15, body 17/15/13, label 16/13/11) plus `AppType.numberHero` (128), `numberXL` (72), `numberL` (48), `numberM` (32), `numberS` (22), `eyebrow` (12 caps). Numbers use tabular figures.

## Components (Components.kt)
- `ProgressRing(progress, bonusProgress, size, strokeWidth, centerContent)` inner mint ring + outer orange bonus ring.
- `HeroCard`, `SectionCard(title, action, onClick)`, `StatTile(label, value, unit, accent)`, `Pill(text, color, filled)`, `SelectChip(text, selected, onClick)`.
- `DayDot(label, DayState)`: Done, Today, Rest, Open, RestTrained, PastEmpty. `BadgeTile(title, unlocked, subtitle)`.
- `BigActionButton(text, onClick)` (one per screen), `SecondaryButton`.
- `BigNumberStepper(value, unit, onMinus, onPlus, label, onValueClick)` hold-to-repeat 64dp buttons.
- `SetRow(setNumber, weight, reps, done, onDoneChange, rir, onRirChange, ...)`, `DoneToggle`.
- `RestTimerBar(remainingSec, totalSec, onSkip)` mint, warms to orange at 5s, flashes at 0.
- `ConfettiBurst(trigger)` put in a full-size Box; bump the Int to fire. `XpBar`, `AnimatedNumber(value: Float, format)`.
- `LineChart(series: List<Series>, xLabels, yFormat)` with `Series(name, values, color, min, max, dashed)`.
- `TreadmillReadout(speed, incline, elapsed, distanceKm, phaseLabel)`.

`Gallery.kt` has `GalleryScreen()` (interactive) and dark/light previews. Every component also has a `@Preview` in Components.kt.

## Notes
Buttons use haptics (`LongPress`/`TextHandleMove`). Minimum tap target 48dp, main controls 64-72dp. Animations are cheap (state-driven, Canvas), no extra libraries beyond `ui-tooling-preview`.
