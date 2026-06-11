# KnowledgeGraph — my-whoop

Stable architecture reference. Update only when architecture actually changes.

## System shape

```
WHOOP 4.0 strap ──BLE──> iOS app (OpenWhoop) ──HTTP──> FastAPI ingest ──> TimescaleDB
                                   │                        │
                              GRDB cache <──pullDerived── compute_day (analysis)
                                   │
                              Apple Health (HealthKitSync)
```

Local-first: the strap's type-47 historical frames are the canonical 1 Hz
metric source (hr, rr, spo2, skin_temp, resp, gravity). The phone decodes and
uploads; the server computes all derived metrics; the phone caches them.

## Server (server/ingest)

- **FastAPI app** `app/main.py` — Bearer auth on all endpoints (`WHOOP_API_KEY`).
- **Endpoints:** `/v1/ingest-decoded` (write), `/v1/streams/<kind>`,
  `/v1/daily?device&from&to`, `/v1/sleep` (`?date=` single night OR
  `?from=&to=` range — range added 2026-06, one call replaces per-day fan-out),
  `/v1/workouts`, `/v1/profile`, `POST /v1/compute-daily` (JSON body
  `{device, date}`), `POST /v1/backfill-workouts`.
- **Analysis** `app/analysis/`:
  - `sleep.py` — gravity-stillness spine + Cole–Kripke; daytime-nap exclusion
    (start hour 10–22 UTC) applied in `daily_sleep_summary` AND the read API.
  - `sleep_features.py` — 30 s epoch staging classifier (wake/light/deep/rem).
    DEEP_FIRST_FRACTION=2/3; degenerate-HF guard; session-relative percentile
    bands. The classifier function is the model seam.
  - `hrv.py` — Task Force RMSSD; Kubios cleaning; last-SWS tiered nightly window.
  - `units.py` — SpO2/resp **gated estimators** (`spo2_percent_nightly`,
    `resp_rate_nightly`): real strap channels are flat aggregates (no waveform),
    so both emit None unless ≥30% of 2-min windows carry signal.
  - `daily.py` — `compute_day` orchestrator; 30 h sleep-aware read window;
    delete-then-insert sessions per day (idempotent recompute).
- **Deploy:** code is BAKED INTO the image — `docker-compose build whoop-ingest
  && up -d` after any server change. Tests marked `@requires_docker` need a
  host Docker (skip in-container).

## iOS (ios/OpenWhoop)

- **Entry:** `OpenWhoopApp` → `AppRoot` (sync `@StateObject` MetricsRepository
  + LiveViewModel) → `RootTabView`. `BackgroundRefresh.register()` in app init;
  BGAppRefreshTask `com.poemswe.mywhoop.refresh` scheduled on backgrounding.
- **BLE:** `BLEManager` (@MainActor, @preconcurrency CB delegates) →
  `FrameRouter` → `Collector`/`Backfiller`. Backfiller refuses to ack a chunk
  with no type-47 frames (CONSOLE_LOGS data-loss guard).
- **Sync:** `ServerSync.pullDerived()` = ONE `/v1/daily` + ONE ranged
  `/v1/sleep`, grouped by UTC end-day, delete-then-upsert per day into GRDB
  (`WhoopStore`, Packages/WhoopStore). Failed range call deletes nothing.
- **HealthKit:** `HealthKitSync` (@MainActor ObservableObject singleton) writes
  sleep stages, HRV, resting HR, SpO2, resp rate, workouts, 48 h HR series on
  each refresh; delete-then-write per type window evicts stale samples even
  when a signal goes null. `beginSyncPass`/`endSyncPass` expose
  `lastSyncAt`/`lastSyncError` (shown in Settings → Apple Health).
- **Project:** `project.yml` + xcodegen (regenerate after target changes).
  `Secrets.xcconfig` is gitignored — copy into any new worktree.

## Test infrastructure

- Server: pytest; throwaway TimescaleDB container fixture (host Docker only).
- iOS: XCTest on simulator; `StubURLProtocol` (UploaderTests.swift) stubs
  HTTP by path; `WhoopStore.inMemory()` for GRDB.
- Test fixtures must use RELATIVE dates — hardcoded days rot out of
  load()'s 14-day window (bit us 2026-06).
