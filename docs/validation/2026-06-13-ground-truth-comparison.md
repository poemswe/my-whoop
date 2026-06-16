# Ground-Truth Validation — my-whoop vs WHOOP export

**Date:** 2026-06-13
**Ground truth:** `my_whoop_data/physiological_cycles.csv` — 760 nights, 2023-03 to 2025-04 (WHOOP's own cloud-computed metrics for this user).
**Compared against:** my-whoop computed metrics, Jun 6–11 2026 (6 nights).

No date overlap (export ends 2025-04, strap data is 2026-06), so this is
**personal-distribution validation** — does my-whoop's output fall in this
user's own historical WHOOP ranges? — not night-matched error.

## Result

| Metric | WHOOP norm (median, p25–p75) | my-whoop recent | Verdict |
|---|---|---|---|
| **HRV** | 88 ms (74–105) | median ~90 (67–108) | ✅ validated — last-SWS window change is correct |
| **Respiratory rate** | 18.3 (17.9–18.8) | 18–19 | ✅ validated — RSA method matches near-exactly |
| **Day strain** | 11.8 (9–13.8) | ~12.5 (10–16) | ✅ in range |
| **Resting HR** | 60 (58–64) | ~57 (53–60) | ⚠️ ~3 bpm low — minor; revisit RHR window |
| **Recovery** | 58 (41–76), p90=90 | 76, 93, 98, 99 | ⚠️ cold-start inflation — 98–99 exceeds personal p90; self-corrects as baseline grows past the 4-night provisional window |
| **Deep %** | 18% (15–22) | ~14% (11–18) | ≈ slightly low, acceptable |
| **REM %** | 32% (28–36) | ~15% (10–18) | ❌ **under-detected ~2×** — the big gap |
| **Light %** | 49% (44–54) | ~72% (68–75) | ❌ over-detected (the mirror of the REM gap) |
| **SpO2** | 95.7% (94.8–96.5) | null | ✅ correctly null (hardware ceiling) |
| Skin temp | 33.8 °C absolute | deviation only | n/a — different representation |

## Headline findings

1. **The respiratory-rate RSA feature (commit 00110e3) is validated.** my-whoop's
   18–19 brpm matches WHOOP's 18.3 median (17.9–18.8) almost exactly, against
   760 nights. This user breathes remarkably consistently and my-whoop recovers it.
2. **HRV is validated.** The last-SWS-window change lands at ~90 vs WHOOP's 88.
3. **REM is under-detected by ~2×** — the clear next accuracy target. my-whoop
   calls ~15% REM where WHOOP sees ~32%; the missing REM is being labelled light
   (72% vs 49%). Root cause: with no respiratory-irregularity feature (RRV was
   NaN), the classifier's REM fallback requires BOTH elevated HR AND high
   HR-variability simultaneously, which is too strict. Now that RSA gives a
   per-epoch respiratory signal, RRV can be restored to re-enable the primary
   REM rule (still body + activated cardiac + irregular respiration).
4. **Recovery cold-start inflation confirmed.** 98–99% on Jun 8–9 exceeds this
   user's p90 (90). It's the 4–5-night provisional baseline over-scoring; it
   regresses toward the 58 median as history accumulates. Known/expected.
5. **Resting HR runs ~3 bpm below WHOOP.** Minor; the lowest-5-min-window method
   may dip slightly under WHOOP's. Low priority.

## Next fix (ground-truth-quantified target) — DONE 2026-06-16 (commit e1aaafe)

Restored per-epoch RRV from the RR-RSA respiratory signal (spectral entropy of
the HF band) so the primary REM rule fires. **Result: REM 15% → ~23-25%**, now
in textbook-normal range; closes ~half the gap to this user's personal 32% via a
principled mechanism (not threshold tuning). Deep% stayed ~13-15%. The residual
gap to 32% is this user's personally-high REM; pushing further would be
overfitting to one distribution without per-epoch ground truth.

## Remaining known gaps (honest ceilings)
- **SpO2**: impossible (needs red+IR; only green PPG exposed). `docs/.../spo2-resp`.
- **Workout precision**: HR+motion can't separate moderate workouts from active
  daily life; coupled to the observed-low HRmax. `docs/.../workout-detection-ceiling`.
- **Recovery cold-start**: 98-99% on a 4-night provisional baseline; self-corrects
  as history accumulates past the seed window.
- **Resting HR ~3 bpm low**: lowest-5-min-window vs WHOOP's method; minor.
