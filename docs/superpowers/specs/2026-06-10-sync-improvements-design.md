# Sync Improvements — Design

**Date:** 2026-06-10
**Branch:** `feat/sync-improvements` off `main`
**Order:** Part 1 → Part 2 → Part 3

## Problem

Pull-to-refresh makes ~61 sequential HTTP calls: one `GET /v1/daily` over the
60-day derived window, then one `GET /v1/sleep?date=` per returned day. Refresh
is slow, data only moves while the app is open, and HealthKit write failures
are invisible (the entitlement misconfiguration in June 2026 took a debugging
session to find because every error was silently swallowed).

## Part 1: Sleep-fetch batching

### Server

- `read.query_sleep_range(conn, device_id, from_day, to_day)` — same column
  list and daytime-nap exclusion as `query_sleep`, with
  `(end_ts AT TIME ZONE 'UTC')::date BETWEEN %s AND %s`, ordered by `start_ts`.
- `GET /v1/sleep` accepts either `date` (existing behavior, unchanged) or
  `from` + `to` (new). Neither, or only one of `from`/`to` → 400. Response
  shape is the same array of session objects in both modes, so existing
  clients are unaffected.

### iOS

- `ServerSync.getSleepRange(from:to:) async -> [CachedSleepSession]?` — one
  call to `/v1/sleep?device=…&from=…&to=…`, parsing identical to `getSleep`.
- `pullDerivedWindow`: after `getDaily`, make ONE `getSleepRange` call. Group
  returned sessions by UTC end-day (`date(endTs)`). For each day in the
  `/v1/daily` list: `deleteSessionsForDay` then upsert that day's group. An
  empty group still deletes — evicting stale sessions for days the server now
  reports none (better than today, where such days were skipped).
- Error handling: if the range call returns nil (network/parse failure), skip
  ALL deletes and upserts — stale cache is preferred over data loss, matching
  current per-day behavior.
- Result: refresh drops from ~61 requests to 2.

### Tests

- Server: range query returns sessions across multiple days; daytime-nap
  exclusion applies in range mode; `date` param still works; 400 on missing/
  partial params.
- iOS: grouping-by-end-day logic; delete-only path for empty days; nil range
  response leaves cache untouched.

## Part 2: HealthKit error surfacing

- `HealthKitSync` becomes an `ObservableObject` with
  `@Published private(set) var lastSyncAt: Date?` and
  `@Published private(set) var lastSyncError: String?`.
- Existing catch blocks record `error.localizedDescription` into
  `lastSyncError` instead of discarding it. Writes remain best-effort; nothing
  throws to callers. A fully successful write pass sets `lastSyncAt` and
  clears `lastSyncError`.
- Settings gains one row: "Apple Health — synced 9:41 AM" (green/normal) or
  the error text (red). No new screens.

### Tests

- State transitions: success sets `lastSyncAt` and clears error; failure sets
  `lastSyncError`. (HealthKit store interactions stay unmocked — the state
  logic is what's testable.)

## Part 3: Background sync

- `BGAppRefreshTask`, identifier `com.poemswe.mywhoop.refresh`.
- New `BackgroundRefresh.swift`: registers the handler at app launch;
  schedules the next request (earliest ~4 h out) after each run AND on app
  foregrounding; handler constructs the lazy-open `MetricsRepository`, awaits
  `refresh()`, and sets an expiration handler that cancels the task.
- After Parts 1–2, `refresh()` is two HTTP calls plus HealthKit writes — well
  inside the background time budget.
- `project.yml`: add `fetch` to `UIBackgroundModes`;
  `BGTaskSchedulerPermittedIdentifiers` = `[com.poemswe.mywhoop.refresh]`.

### Tests

- Unit-test the extractable scheduling policy (identifier, earliest-begin
  interval). `BGTaskScheduler` itself is not unit-testable; end-to-end runs
  verified manually via the Xcode debugger trigger
  (`_simulateLaunchForTaskWithIdentifier`).

## Non-goals

- No change to the decoded-stream pull (`pullDecoded`) or upload paths.
- No silent-push/APNs infrastructure.
- No HealthKit sync-history log — last status only.
