# Workout Detection — Signal Ceiling & HRmax Coupling

**Date:** 2026-06-16

## What was fixed
`MIN_INTENSITY_Z2PLUS` 0.50 → 0.10 (commit 45736eb) — recovered moderate
workouts WHOOP logs (avg HR down to 89) that the strict gate discarded. Jun 12
0→6, Jun 13 0→2. Resolves the user-reported "workouts not detected anymore".

## The coupling (important)
The 0.10 gate works **because my-whoop's observed HRmax is currently ~137**
(90-day p99.5 of ~12 days of data). The user's TRUE HRmax from their WHOOP
export is ~192. Seeding the true HRmax shifts the zone-2 boundary from ~103 bpm
up to ~135 bpm, which **re-breaks** Jun 12 (0 workouts) — the moderate bouts
(avg 88–92) are genuinely far below zone 2 at the correct HRmax.

**Decision: do NOT seed the true HRmax.** It would regress workout detection,
and zone-based gating is the wrong tool anyway (see below). HRmax stays observed.

## The ceiling
With the intensity gate disabled, every recent day yields ~20 candidate bouts
(HR-floor + wrist-motion + 5-min duration), and their mean HR elevations
overlap completely across workout days and ordinary active days (all ~24–42 bpm
above resting). **HR + wrist-motion alone cannot separate a moderate workout
from active daily life.** No elevation or %HRR threshold cleanly splits them.

WHOOP separates them with signals my-whoop does not capture: GPS, accelerometer
activity-type classification, and an ML detector. This is a signal-availability
ceiling, like SpO2 (needs red+IR) — not a tuning bug.

## Consequence for parity
Workout detection is "good enough" (catches real workouts, occasional moderate
false positives that carry low strain — WHOOP's own model tolerates this) but
will not reach WHOOP's precision without richer sensors/ML. Documented as a
known ceiling; revisit only if live optical / accelerometer-class data becomes
capturable, or with a labelled workout dataset to train a classifier.
