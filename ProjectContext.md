# ProjectContext — my-whoop

Rolling state. Prune entries older than ~3 weeks after each milestone.

## Current focus (2026-06-10)

`feat/sync-improvements` branch (worktree `.worktrees/feat-sync-improvements`):
sleep-fetch batching (DONE), HealthKit error surfacing (DONE), BGAppRefreshTask
background refresh (DONE). Spec + plan in `docs/superpowers/`. Awaiting merge.

## Recent decisions

- `/v1/sleep` gained `from`/`to` range mode; `date` mode kept for compat.
  Refresh dropped from ~61 HTTP calls to 2.
- SpO2 + resp rate are GATED to None on real data: the strap's 1 Hz spo2/resp
  channels are flat aggregates (no waveform — resp is constant ~3067 counts
  even during exercise). Gates live in `units.spo2_percent_nightly` /
  `resp_rate_nightly`; they self-heal if a future decoder exposes waveforms.
- Deep-sleep zeroing fixed (DEEP_FIRST_FRACTION 1/3→2/3 + HF guard): nights now
  show 50–80 min deep (11–20% TST). Side effect: nightly HRV now uses the
  last-SWS window (values shifted up, by design).
- Branch hygiene done 2026-06-10: main is the mainline (all work merged),
  `android/kotlin-protocol` pushed to origin, fix/* branches deleted.

## Open threads / blockers

- **Manual verification pending:** BGAppRefreshTask on-device (Xcode debugger
  `_simulateLaunchForTaskWithIdentifier`), Settings Apple Health row, and the
  faster pull-to-refresh feel.
- WHOOP data export would unlock calibration (`fit_spo2`, `fit_skin_temp`) and
  a staging-accuracy check; without it staging is plausibility-validated only.
- Two-strap device picker still hardcoded to one device id.
- Type-47 frame layout worth re-examining — "spo2"/"resp" fields may be
  mislabeled rather than truly dead.

## Gotchas

- Server code is baked into the docker image — rebuild after every change.
- The live whoop-ingest container can be rebuilt from a worktree compose file
  BUT `up -d` fails there (path not Docker-shared); run `up -d` from the main
  checkout (same compose project name → reuses the freshly built image).
- `requires_docker` server tests skip in-container; run from host via
  `uv run --python 3.12 --with-requirements requirements.txt --with pytest
  --with httpx --with ../packages/whoop-protocol python -m pytest …`.
- Simulator for iOS tests: iPhone SE 3rd gen, id
  `779D837A-2C06-4CAE-A5D8-3E0826593058` (OS 18.3.1).
- iOS 26.5 device runtime must be installed in Xcode for device installs.
- `xcodebuild test` on the simulator STRIPS the HealthKit key from
  OpenWhoop.entitlements (automatic signing rewrite). Check `git status` after
  simulator test runs and restore with `git checkout ios/OpenWhoop/OpenWhoop.entitlements`.
