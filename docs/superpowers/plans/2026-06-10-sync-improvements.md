# Sync Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut pull-to-refresh from ~61 HTTP calls to 2, surface HealthKit sync errors in Settings, and add opportunistic background refresh.

**Architecture:** A `from`/`to` range mode on the existing `GET /v1/sleep` (server) feeds a single-call `pullDerivedWindow` (iOS). `HealthKitSync` becomes an `ObservableObject` exposing last-sync state. A `BGAppRefreshTask` reuses the lazy-open `MetricsRepository.refresh()`.

**Tech Stack:** FastAPI + psycopg (server), Swift/SwiftUI + GRDB + BackgroundTasks (iOS), pytest + XCTest.

**Branch:** `feat/sync-improvements` off `main` (create via using-git-worktrees before Task 1).

**Spec:** `docs/superpowers/specs/2026-06-10-sync-improvements-design.md`

**Verification environment notes:**
- Server unit tests run inside the container: `docker cp` the changed files + tests into `whoop-ingest`, then `docker exec -e PYTHONPATH=/app -w /app whoop-ingest python3 -m pytest tests/<file> -q`. Tests marked `@requires_docker` SKIP in-container (no docker-in-docker); run those from the host with `uv run --with-requirements server/ingest/requirements.txt --with pytest,fastapi[all] python -m pytest tests/test_read_api.py -q` (host has Docker), or fall back to the live-curl smoke steps included below.
- iOS tests: `xcodebuild test -project ios/OpenWhoop.xcodeproj -scheme OpenWhoop -destination 'platform=iOS Simulator,name=<sim>'` — pick a sim from `xcrun simctl list devices available`. If no simulator runtime is installed, build-only (`xcodebuild build`) plus user-run tests in Xcode is the fallback; say so in the report rather than skipping silently.

---

### Task 1: Server — `query_sleep_range`

**Files:**
- Modify: `server/ingest/app/read.py` (after `query_sleep`, ~line 262)
- Test: `server/ingest/tests/test_read_api.py` (append)

- [ ] **Step 1: Write the failing test**

Append to `server/ingest/tests/test_read_api.py` (it already imports `psycopg`, `store`, `requires_docker`, and the `client` fixture):

```python
# ── /v1/sleep range mode ──────────────────────────────────────────────────────

def _seed_sleep_range(dsn, device="devS"):
    """Three sessions: nights ending Jun 1 and Jun 2 2026 (UTC), plus a daytime
    nap on Jun 2 that the nap filter must exclude. 2026-06-01T00:00Z == 1780272000."""
    jun1 = 1780272000
    with psycopg.connect(dsn) as conn:
        store.ensure_device(conn, device)
        store.upsert_sleep_sessions(conn, device, [
            # Night A: May 31 23:00 → Jun 1 07:00 (start hour 23 — kept)
            {"start": jun1 - 3600, "end": jun1 + 7 * 3600, "efficiency": 0.9,
             "resting_hr": 55, "avg_hrv": 80.0,
             "stages": [{"start": jun1 - 3600, "end": jun1 + 7 * 3600, "stage": "light"}]},
            # Night B: Jun 2 00:30 → Jun 2 08:00 (start hour 0 — kept)
            {"start": jun1 + 86400 + 1800, "end": jun1 + 86400 + 8 * 3600,
             "efficiency": 0.85, "resting_hr": 57, "avg_hrv": 75.0,
             "stages": [{"start": jun1 + 86400 + 1800, "end": jun1 + 86400 + 8 * 3600,
                         "stage": "light"}]},
            # Daytime nap: Jun 2 12:00 → 13:30 (start hour 12 — EXCLUDED)
            {"start": jun1 + 86400 + 12 * 3600, "end": jun1 + 86400 + 13 * 3600 + 1800,
             "efficiency": 0.8, "resting_hr": 60, "avg_hrv": 70.0, "stages": []},
        ])
        conn.commit()


@requires_docker
def test_sleep_range_returns_nights_excludes_naps(client, clean_db):
    _seed_sleep_range(clean_db)
    rows = client.get("/v1/sleep", params={
        "device": "devS", "from": "2026-06-01", "to": "2026-06-02"}).json()
    assert isinstance(rows, list)
    assert len(rows) == 2, f"expected 2 nights (nap excluded), got {len(rows)}"
    # Ordered by start_ts; both carry stages.
    assert rows[0]["resting_hr"] == 55
    assert rows[1]["resting_hr"] == 57


@requires_docker
def test_sleep_range_outside_window_empty(client, clean_db):
    _seed_sleep_range(clean_db)
    rows = client.get("/v1/sleep", params={
        "device": "devS", "from": "2026-07-01", "to": "2026-07-02"}).json()
    assert rows == []
```

- [ ] **Step 2: Run the test to verify it fails**

From the host (Docker available):
```bash
cd /Users/poepoe/NYU/my-whoop/server/ingest
uv run --with-requirements requirements.txt --with pytest --with httpx \
  python -m pytest tests/test_read_api.py -k sleep_range -q
```
Expected: FAIL (422/400 — endpoint doesn't accept `from`/`to` yet). If the host env can't build the deps, defer to the live smoke in Task 3 Step 4 and note it.

- [ ] **Step 3: Implement `query_sleep_range`**

In `server/ingest/app/read.py`, directly after `query_sleep`:

```python
def query_sleep_range(conn, device_id, from_day, to_day):
    """Sleep sessions whose END date (UTC) falls in [from_day, to_day] inclusive.

    Same column list and daytime-nap exclusion as ``query_sleep`` — one ranged
    query replaces the per-day fan-out the iOS client used to make (~61 calls
    per refresh). Ordered by start_ts across the whole range."""
    cols = ["device_id", "start_ts", "end_ts", "efficiency", "resting_hr", "avg_hrv", "stages"]
    rows = conn.execute(
        f"SELECT {', '.join(cols)} FROM sleep_sessions "
        "WHERE device_id = %s "
        "  AND (end_ts AT TIME ZONE 'UTC')::date BETWEEN %s AND %s "
        "  AND NOT (EXTRACT(HOUR FROM start_ts AT TIME ZONE 'UTC') >= 10 "
        "           AND EXTRACT(HOUR FROM start_ts AT TIME ZONE 'UTC') < 22) "
        "ORDER BY start_ts",
        (device_id, from_day, to_day),
    ).fetchall()
    return [dict(zip(cols, r)) for r in rows]
```

- [ ] **Step 4: Run the test again** — still FAILS (endpoint not wired). That's expected; the endpoint lands in Task 2. Commit the query + test as one unit with Task 2, or proceed directly.

### Task 2: Server — `/v1/sleep` range mode

**Files:**
- Modify: `server/ingest/app/main.py:244-248` (`get_sleep`)
- Test: `server/ingest/tests/test_read_api.py` (append)

- [ ] **Step 1: Add the 400-behavior test**

```python
@requires_docker
def test_sleep_requires_date_or_range(client):
    r = client.get("/v1/sleep", params={"device": "devS"})
    assert r.status_code == 400
    r = client.get("/v1/sleep", params={"device": "devS", "from": "2026-06-01"})
    assert r.status_code == 400


@requires_docker
def test_sleep_date_param_still_works(client, clean_db):
    _seed_sleep_range(clean_db)
    rows = client.get("/v1/sleep", params={
        "device": "devS", "date": "2026-06-02"}).json()
    assert len(rows) == 1 and rows[0]["resting_hr"] == 57
```

- [ ] **Step 2: Rewrite `get_sleep`**

Replace `server/ingest/app/main.py:244-248` (`Query` and `HTTPException` are already imported):

```python
@app.get("/v1/sleep", dependencies=[Depends(require_auth)])
def get_sleep(device: str,
              date: str | None = None,
              from_: str | None = Query(None, alias="from"),
              to: str | None = None):
    """Sleep sessions. Modes: ``?date=`` — sessions whose night ENDS on that
    date (original behavior, unchanged response shape); ``?from=&to=`` — all
    sessions whose night ends inside the inclusive range (one call replaces the
    client's per-day fan-out). Exactly one mode must be supplied."""
    if date is not None:
        day = _parse_date(date)
        with psycopg.connect(cfg.db_dsn) as conn:
            return read.query_sleep(conn, device, day)
    if from_ is not None and to is not None:
        start, end = _parse_date(from_), _parse_date(to)
        with psycopg.connect(cfg.db_dsn) as conn:
            return read.query_sleep_range(conn, device, start, end)
    raise HTTPException(status_code=400, detail="provide either date or from+to")
```

- [ ] **Step 3: Run all four new tests**

```bash
cd /Users/poepoe/NYU/my-whoop/server/ingest
uv run --with-requirements requirements.txt --with pytest --with httpx \
  python -m pytest tests/test_read_api.py -k sleep -q
```
Expected: 4 passed.

- [ ] **Step 4: Run the in-container unit suite (regression check)**

```bash
docker cp /Users/poepoe/NYU/my-whoop/server/ingest/app/read.py whoop-ingest:/app/app/read.py
docker cp /Users/poepoe/NYU/my-whoop/server/ingest/app/main.py whoop-ingest:/app/app/main.py
docker exec -e PYTHONPATH=/app -w /app whoop-ingest python3 -m pytest tests/ -q 2>&1 | tail -3
```
Expected: same pass count as main (579 passed at last run), docker-marked tests skipped.

- [ ] **Step 5: Commit**

```bash
git add server/ingest/app/read.py server/ingest/app/main.py server/ingest/tests/test_read_api.py
git commit -m "feat(api): /v1/sleep range mode — one call replaces per-day fan-out"
```

### Task 3: Server — deploy + live smoke

**Files:** none (operational)

- [ ] **Step 1: Rebuild and restart the ingest container**

```bash
docker-compose -f /Users/poepoe/NYU/my-whoop/server/docker-compose.yml build whoop-ingest
docker-compose -f /Users/poepoe/NYU/my-whoop/server/docker-compose.yml up -d whoop-ingest
sleep 3
```

- [ ] **Step 2: Smoke the range mode against real data**

```bash
curl -s "http://100.79.107.77:8770/v1/sleep?device=my-whoop&from=2026-06-04&to=2026-06-10" \
  -H "Authorization: Bearer $(grep WHOOP_API_KEY /Users/poepoe/NYU/my-whoop/server/.env | cut -d= -f2)" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d), 'sessions')"
```
Expected: ~6 sessions (one per night Jun 4–10, no daytime naps).

- [ ] **Step 3: Smoke the date mode (compat)**

```bash
curl -s "http://100.79.107.77:8770/v1/sleep?device=my-whoop&date=2026-06-09" \
  -H "Authorization: Bearer $(grep WHOOP_API_KEY /Users/poepoe/NYU/my-whoop/server/.env | cut -d= -f2)" \
  | python3 -c "import json,sys; print(len(json.load(sys.stdin)), 'sessions')"
```
Expected: 1 session.

### Task 4: iOS — `getSleepRange`

**Files:**
- Modify: `ios/OpenWhoop/Upload/ServerSync.swift:379-411` (`getSleep`)
- Test: `ios/OpenWhoopTests/ServerSyncTests.swift` (append)

- [ ] **Step 1: Write the failing test**

Append to `ServerSyncTests.swift` (uses the existing `StubURLProtocol`, `makeSession`, `makeConfig` helpers):

```swift
// MARK: - getSleepRange (single ranged call)

func testGetSleepRangeParsesSessionsAcrossDays() async throws {
    let body = """
    [{"start_ts":"2026-05-22T23:05:00+00:00","end_ts":"2026-05-23T07:11:00+00:00",
      "efficiency":0.9,"resting_hr":55,"avg_hrv":80.0,
      "stages":[{"start":1779836700,"end":1779865860,"stage":"light"}]},
     {"start_ts":"2026-05-24T01:00:00+00:00","end_ts":"2026-05-24T08:00:00+00:00",
      "efficiency":0.85,"resting_hr":57,"avg_hrv":75.0,"stages":[]}]
    """
    StubURLProtocol.reset(responses: [:], bodies: ["/v1/sleep": body])

    let store = try await WhoopStore.inMemory()
    let sync = ServerSync(config: makeConfig(), store: store,
                          deviceId: "my-whoop", session: makeSession())
    let sessions = await sync.getSleepRange(from: "2026-05-23", to: "2026-05-24")
    XCTAssertEqual(sessions?.count, 2)
    XCTAssertEqual(sessions?[0].restingHr, 55)
    XCTAssertEqual(sessions?[1].restingHr, 57)

    let req = StubURLProtocol.captured.first { $0.url.path.hasSuffix("/v1/sleep") }
    XCTAssertTrue(req?.url.query?.contains("from=2026-05-23") ?? false)
    XCTAssertTrue(req?.url.query?.contains("to=2026-05-24") ?? false)
}
```

- [ ] **Step 2: Run to verify it fails** (no `getSleepRange` member). Use the simulator destination chosen per the header notes:

```bash
xcodebuild test -project /Users/poepoe/NYU/my-whoop/ios/OpenWhoop.xcodeproj \
  -scheme OpenWhoop -destination 'platform=iOS Simulator,name=iPhone 16' \
  -only-testing:OpenWhoopTests/ServerSyncTests 2>&1 | tail -5
```
Expected: compile FAIL on `getSleepRange`.

- [ ] **Step 3: Implement — extract the shared parser, add the range call**

In `ServerSync.swift`, replace `getSleep(date:)` (lines 379-411) with:

```swift
private func getSleep(date: String) async -> [CachedSleepSession]? {
    await getSleepPath("/v1/sleep?device=\(deviceId)&date=\(date)")
}

/// One ranged call covering [from, to] (YYYY-MM-DD, inclusive) — replaces the
/// per-day fan-out (~61 requests per refresh → 1). Same response shape.
func getSleepRange(from: String, to: String) async -> [CachedSleepSession]? {
    await getSleepPath("/v1/sleep?device=\(deviceId)&from=\(from)&to=\(to)")
}

private func getSleepPath(_ path: String) async -> [CachedSleepSession]? {
    guard let body = await get(path: path),
          let obj = try? JSONSerialization.jsonObject(with: body) else {
        return nil
    }
    // Accept either a single session object or an array of sessions.
    let dicts: [[String: Any]]
    if let arr = obj as? [[String: Any]] { dicts = arr }
    else if let d = obj as? [String: Any], !d.isEmpty { dicts = [d] }
    else { dicts = [] }

    let int = ServerSync.int
    let dbl = ServerSync.dbl
    func epoch(_ r: [String: Any], _ k: String) -> Int? {
        if let s = r[k] as? String { return ServerSync.parseEpoch(s) }
        return int(r, k)
    }
    return dicts.compactMap { r in
        guard let start = epoch(r, "start_ts") ?? epoch(r, "startTs"),
              let end = epoch(r, "end_ts") ?? epoch(r, "endTs") else { return nil }
        var stagesJSON: String? = nil
        if let stages = r["stages"],
           let data = try? JSONSerialization.data(withJSONObject: stages) {
            stagesJSON = String(decoding: data, as: UTF8.self)
        }
        return CachedSleepSession(startTs: start, endTs: end,
                                  efficiency: dbl(r, "efficiency"),
                                  restingHr: int(r, "resting_hr") ?? int(r, "restingHr"),
                                  avgHrv: dbl(r, "avg_hrv") ?? dbl(r, "avgHrv"),
                                  stagesJSON: stagesJSON)
    }
}
```

- [ ] **Step 4: Run the test — expect PASS.** Same command as Step 2.

- [ ] **Step 5: Commit**

```bash
git add ios/OpenWhoop/Upload/ServerSync.swift ios/OpenWhoopTests/ServerSyncTests.swift
git commit -m "feat(sync): getSleepRange — single ranged /v1/sleep call"
```

### Task 5: iOS — single-call `pullDerivedWindow`

**Files:**
- Modify: `ios/OpenWhoop/Upload/ServerSync.swift:316-346` (`pullDerivedWindow`)
- Test: `ios/OpenWhoopTests/ServerSyncTests.swift` (append)

- [ ] **Step 1: Write the failing tests**

```swift
// MARK: - pullDerived uses ONE ranged sleep call + evicts stale days

func testPullDerivedSingleSleepCallGroupsByDay() async throws {
    let daily = """
    [{"day":"2026-05-23"},{"day":"2026-05-24"}]
    """
    let sleep = """
    [{"start_ts":"2026-05-22T23:05:00+00:00","end_ts":"2026-05-23T07:11:00+00:00",
      "efficiency":0.9,"resting_hr":55,"avg_hrv":80.0,"stages":[]},
     {"start_ts":"2026-05-24T01:00:00+00:00","end_ts":"2026-05-24T08:00:00+00:00",
      "efficiency":0.85,"resting_hr":57,"avg_hrv":75.0,"stages":[]}]
    """
    StubURLProtocol.reset(responses: [:],
                          bodies: ["/v1/daily": daily, "/v1/sleep": sleep])

    let store = try await WhoopStore.inMemory()
    let sync = ServerSync(config: makeConfig(), store: store,
                          deviceId: "my-whoop", session: makeSession())
    await sync.pullDerived()

    let cached = try await store.sleepSessions(deviceId: "my-whoop",
                                               from: 0, to: Int.max, limit: 50)
    XCTAssertEqual(cached.count, 2)

    let sleepCalls = StubURLProtocol.captured.filter { $0.url.path.hasSuffix("/v1/sleep") }
    XCTAssertEqual(sleepCalls.count, 1, "must be ONE ranged call, not per-day fan-out")
}

func testPullDerivedEvictsDayServerNoLongerReports() async throws {
    // Pre-seed a stale session ending 2026-05-23; server's daily window includes
    // that day but the ranged sleep response has NO session for it.
    let store = try await WhoopStore.inMemory()
    let stale = CachedSleepSession(startTs: ServerSync.parseEpoch("2026-05-22T23:00:00Z")!,
                                   endTs: ServerSync.parseEpoch("2026-05-23T05:00:00Z")!,
                                   efficiency: 0.5, restingHr: 70, avgHrv: 40,
                                   stagesJSON: nil)
    try await store.upsertSleepSessions([stale], deviceId: "my-whoop")

    let daily = """
    [{"day":"2026-05-23"}]
    """
    StubURLProtocol.reset(responses: [:],
                          bodies: ["/v1/daily": daily, "/v1/sleep": "[]"])
    let sync = ServerSync(config: makeConfig(), store: store,
                          deviceId: "my-whoop", session: makeSession())
    await sync.pullDerived()

    let cached = try await store.sleepSessions(deviceId: "my-whoop",
                                               from: 0, to: Int.max, limit: 50)
    XCTAssertTrue(cached.isEmpty, "stale session must be evicted")
}

func testPullDerivedSleepFailureLeavesCacheUntouched() async throws {
    let store = try await WhoopStore.inMemory()
    let existing = CachedSleepSession(startTs: ServerSync.parseEpoch("2026-05-22T23:00:00Z")!,
                                      endTs: ServerSync.parseEpoch("2026-05-23T05:00:00Z")!,
                                      efficiency: 0.5, restingHr: 70, avgHrv: 40,
                                      stagesJSON: nil)
    try await store.upsertSleepSessions([existing], deviceId: "my-whoop")

    let daily = """
    [{"day":"2026-05-23"}]
    """
    StubURLProtocol.reset(responses: ["/v1/sleep": 500],
                          bodies: ["/v1/daily": daily])
    let sync = ServerSync(config: makeConfig(), store: store,
                          deviceId: "my-whoop", session: makeSession())
    await sync.pullDerived()

    let cached = try await store.sleepSessions(deviceId: "my-whoop",
                                               from: 0, to: Int.max, limit: 50)
    XCTAssertEqual(cached.count, 1, "failed range call must not delete anything")
}
```

- [ ] **Step 2: Run — first test FAILS** (current code makes one `/v1/sleep` call per daily row, so `sleepCalls.count == 1` may coincidentally pass with one day — the grouping/eviction tests are the real gates; eviction FAILS today because a failed/absent per-day fetch skips the delete).

- [ ] **Step 3: Rewrite the loop body**

Replace `ServerSync.swift:334-345` (the per-day loop and its comment) with:

```swift
        // ONE ranged /v1/sleep call covering the whole window, then group the
        // returned sessions by UTC end-day. Delete-then-upsert per daily-metric
        // day: stale sessions (recomputed, truncated, removed false-positives)
        // are evicted even when the server now reports NO sessions for a day.
        // If the range call fails entirely, skip all deletes — a stale cache
        // beats data loss.
        guard let sessions = await getSleepRange(from: fromDay, to: toDay) else { return }
        var byDay: [String: [CachedSleepSession]] = [:]
        for s in sessions {
            let day = fmt.string(from: Date(timeIntervalSince1970: TimeInterval(s.endTs)))
            byDay[day, default: []].append(s)
        }
        for metric in days {
            do { try await store.deleteSessionsForDay(deviceId: deviceId, day: metric.day) } catch {}
            if let group = byDay[metric.day], !group.isEmpty {
                do { try await store.upsertSleepSessions(group, deviceId: deviceId) } catch {}
            }
        }
```

(`fmt` is the UTC `yyyy-MM-dd` formatter already defined at the top of `pullDerivedWindow`.)

- [ ] **Step 4: Run all three tests + the full ServerSyncTests class — expect PASS.**

- [ ] **Step 5: Commit**

```bash
git add ios/OpenWhoop/Upload/ServerSync.swift ios/OpenWhoopTests/ServerSyncTests.swift
git commit -m "feat(sync): pullDerived single ranged sleep call + stale-day eviction"
```

### Task 6: iOS — HealthKitSync sync-state surface

**Files:**
- Modify: `ios/OpenWhoop/Sync/HealthKitSync.swift`
- Modify: `ios/OpenWhoop/Metrics/MetricsRepository.swift` (refresh(), the HealthKit block)
- Test: `ios/OpenWhoopTests/HealthKitSyncStateTests.swift` (create)

- [ ] **Step 1: Write the failing test**

Create `ios/OpenWhoopTests/HealthKitSyncStateTests.swift`:

```swift
import XCTest
@testable import OpenWhoop

/// State-transition tests for HealthKitSync's sync-status surface. HealthKit
/// store interactions stay untested (simulator auth makes them nondeterministic);
/// the begin/record/end pass logic is the testable unit.
@MainActor
final class HealthKitSyncStateTests: XCTestCase {

    func testSuccessfulPassSetsTimestampAndClearsError() {
        let sync = HealthKitSync()
        sync.beginSyncPass()
        sync.endSyncPass()
        XCTAssertNotNil(sync.lastSyncAt)
        XCTAssertNil(sync.lastSyncError)
    }

    func testFailureDuringPassRecordsErrorAndBlocksTimestamp() {
        let sync = HealthKitSync()
        let before = sync.lastSyncAt
        sync.beginSyncPass()
        sync.record(NSError(domain: "test", code: 1,
                            userInfo: [NSLocalizedDescriptionKey: "boom"]))
        sync.endSyncPass()
        XCTAssertEqual(sync.lastSyncError, "boom")
        XCTAssertEqual(sync.lastSyncAt, before, "failed pass must not update lastSyncAt")
    }

    func testNextSuccessfulPassClearsPreviousError() {
        let sync = HealthKitSync()
        sync.beginSyncPass()
        sync.record(NSError(domain: "test", code: 1))
        sync.endSyncPass()
        sync.beginSyncPass()
        sync.endSyncPass()
        XCTAssertNil(sync.lastSyncError)
        XCTAssertNotNil(sync.lastSyncAt)
    }
}
```

- [ ] **Step 2: Run — compile FAIL** (`HealthKitSync()` is private-init singleton without these members).

- [ ] **Step 3: Implement the state surface**

In `HealthKitSync.swift`:

1. Class declaration: `final class HealthKitSync: ObservableObject {` (keep `@MainActor`).
2. Below `static let shared`, add:

```swift
    @Published private(set) var lastSyncAt: Date?
    @Published private(set) var lastSyncError: String?

    /// Called by MetricsRepository at the start of a refresh's HealthKit writes.
    func beginSyncPass() { lastSyncError = nil }

    /// Called after the last write of a pass; a pass with no recorded error is a success.
    func endSyncPass() {
        if lastSyncError == nil { lastSyncAt = Date() }
    }

    func record(_ error: Error) { lastSyncError = error.localizedDescription }
```

3. Replace every silent `catch {}` in `requestAuthorizationIfNeeded`, `writeSessions`, `writeMetrics`, `writeWorkouts`, `writeHeartRate`, and `deleteExisting` with `catch { record(error) }` — EXCEPT `requestAuthorizationIfNeeded` and `deleteExisting`, which stay silent (auth denial is a user choice, not a sync error; delete failures self-heal on the next pass and would mask the more useful save error).

4. In `MetricsRepository.refresh()`, wrap the HealthKit block:

```swift
            await HealthKitSync.shared.beginSyncPass()
            await HealthKitSync.shared.writeSessions(sessions)
            await HealthKitSync.shared.writeMetrics(days)
            await HealthKitSync.shared.writeWorkouts(workouts)
            await HealthKitSync.shared.writeHeartRate(hrPoints)
            await HealthKitSync.shared.endSyncPass()
```

- [ ] **Step 4: Run HealthKitSyncStateTests — expect 3 PASS.**

- [ ] **Step 5: Commit**

```bash
git add ios/OpenWhoop/Sync/HealthKitSync.swift ios/OpenWhoop/Metrics/MetricsRepository.swift \
        ios/OpenWhoopTests/HealthKitSyncStateTests.swift
git commit -m "feat(health): surface last sync status from HealthKitSync"
```

### Task 7: iOS — Settings row

**Files:**
- Modify: `ios/OpenWhoop/Settings/SettingsView.swift` (Form at line ~135, sections at ~154)

- [ ] **Step 1: Add the section**

In `SettingsView`, add an observed reference near the top of the struct (with the other properties):

```swift
    @ObservedObject private var health = HealthKitSync.shared
```

Add a section builder alongside the existing ones (after `saveSection`):

```swift
    private var healthSection: some View {
        Section("Apple Health") {
            if let err = health.lastSyncError {
                Label(err, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.red)
            } else if let at = health.lastSyncAt {
                Label("Synced \(at.formatted(date: .omitted, time: .shortened))",
                      systemImage: "heart.fill")
                    .foregroundStyle(.secondary)
            } else {
                Text("Not synced yet").foregroundStyle(.secondary)
            }
        }
    }
```

Insert `healthSection` into the `Form` between `saveSection` and `footerSection`.

- [ ] **Step 2: Build**

```bash
xcodebuild -project /Users/poepoe/NYU/my-whoop/ios/OpenWhoop.xcodeproj -scheme OpenWhoop \
  -destination 'generic/platform=iOS' -configuration Debug \
  CODE_SIGN_IDENTITY="" CODE_SIGNING_REQUIRED=NO build 2>&1 | grep -E "error:|BUILD"
```
Expected: BUILD SUCCEEDED.

- [ ] **Step 3: Commit**

```bash
git add ios/OpenWhoop/Settings/SettingsView.swift
git commit -m "feat(settings): Apple Health sync status row"
```

### Task 8: iOS — background refresh

**Files:**
- Create: `ios/OpenWhoop/Sync/BackgroundRefresh.swift`
- Modify: `ios/OpenWhoop/App/OpenWhoopApp.swift`
- Modify: `ios/project.yml` (background modes + permitted identifiers)
- Test: `ios/OpenWhoopTests/BackgroundRefreshTests.swift` (create)

- [ ] **Step 1: Write the failing test**

Create `ios/OpenWhoopTests/BackgroundRefreshTests.swift`:

```swift
import XCTest
@testable import OpenWhoop

final class BackgroundRefreshTests: XCTestCase {

    func testRequestIdentifierMatchesPlistEntry() {
        XCTAssertEqual(BackgroundRefresh.taskIdentifier, "com.poemswe.mywhoop.refresh")
    }

    func testRequestSchedulesAtLeastFourHoursOut() throws {
        let now = Date()
        let request = BackgroundRefresh.makeRequest(now: now)
        XCTAssertEqual(request.identifier, BackgroundRefresh.taskIdentifier)
        let earliest = try XCTUnwrap(request.earliestBeginDate)
        XCTAssertGreaterThanOrEqual(earliest.timeIntervalSince(now), 4 * 3600 - 1)
    }
}
```

- [ ] **Step 2: Run — compile FAIL** (no `BackgroundRefresh`).

- [ ] **Step 3: Create `ios/OpenWhoop/Sync/BackgroundRefresh.swift`**

```swift
import BackgroundTasks
import Foundation

/// Opportunistic background refresh: iOS grants BGAppRefreshTask runs a few
/// times a day at its discretion. The handler reuses the lazy-open
/// MetricsRepository.refresh() (2 HTTP calls + HealthKit writes after the
/// batching change), so it fits comfortably in the ~30 s background budget.
enum BackgroundRefresh {
    static let taskIdentifier = "com.poemswe.mywhoop.refresh"
    static let minInterval: TimeInterval = 4 * 3600

    /// Must be called before the app finishes launching (OpenWhoopApp.init).
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskIdentifier,
                                        using: nil) { task in
            handle(task: task as! BGAppRefreshTask)
        }
    }

    /// Submit the next request. Safe to call repeatedly (duplicate submissions
    /// for the same identifier replace each other). Errors (e.g. simulator,
    /// Low Power Mode) are non-fatal — the next foreground triggers a retry.
    static func schedule(now: Date = Date()) {
        try? BGTaskScheduler.shared.submit(makeRequest(now: now))
    }

    static func makeRequest(now: Date = Date()) -> BGAppRefreshTaskRequest {
        let request = BGAppRefreshTaskRequest(identifier: taskIdentifier)
        request.earliestBeginDate = now.addingTimeInterval(minInterval)
        return request
    }

    private static func handle(task: BGAppRefreshTask) {
        schedule()  // always queue the next run first
        let work = Task { @MainActor in
            let repo = MetricsRepository(deviceId: AppConfig.deviceId)
            await repo.refresh()
            task.setTaskCompleted(success: true)
        }
        task.expirationHandler = {
            work.cancel()
            task.setTaskCompleted(success: false)
        }
    }
}
```

- [ ] **Step 4: Wire into `OpenWhoopApp.swift`**

Replace the `OpenWhoopApp` struct (keep `AppRoot` unchanged):

```swift
@main
struct OpenWhoopApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        BackgroundRefresh.register()
    }

    var body: some Scene {
        WindowGroup {
            AppRoot()
        }
        .onChange(of: scenePhase) { phase in
            // Schedule on backgrounding — the recommended submission point.
            if phase == .background { BackgroundRefresh.schedule() }
        }
    }
}
```

(Single-parameter `onChange` — deployment target is iOS 16.0.)

- [ ] **Step 5: Update `ios/project.yml`**

In the `info.properties` block, change `UIBackgroundModes` and add the identifier key:

```yaml
        UIBackgroundModes:
          - bluetooth-central
          - fetch
        BGTaskSchedulerPermittedIdentifiers:
          - com.poemswe.mywhoop.refresh
```

Then regenerate:

```bash
cd /Users/poepoe/NYU/my-whoop/ios && xcodegen generate
```

- [ ] **Step 6: Run BackgroundRefreshTests + full iOS test suite — expect PASS.**

- [ ] **Step 7: Commit**

```bash
git add ios/OpenWhoop/Sync/BackgroundRefresh.swift ios/OpenWhoop/App/OpenWhoopApp.swift \
        ios/project.yml ios/OpenWhoopTests/BackgroundRefreshTests.swift
git commit -m "feat(sync): opportunistic background refresh via BGAppRefreshTask"
```

### Task 9: Final verification + docs

**Files:**
- Modify: `ProjectContext.md` (or create), `KnowledgeGraph.md` (architecture changed: new endpoint mode, BG task)

- [ ] **Step 1: Full server suite (in-container) + host docker-marked tests** — commands from Tasks 2.3/2.4. Report exact counts.
- [ ] **Step 2: Full iOS suite** on the chosen simulator; if simulator unavailable, full device build + note.
- [ ] **Step 3: Run code-simplifier on modified files** (`code-simplifier:code-simplifier`), apply anything sensible, re-run touched tests.
- [ ] **Step 4: Update `KnowledgeGraph.md`** — `/v1/sleep` range mode, `BackgroundRefresh`, HealthKitSync status surface. Update `ProjectContext.md` — current state, manual BG-task verification still pending on-device.
- [ ] **Step 5: Commit docs**

```bash
git add KnowledgeGraph.md ProjectContext.md
git commit -m "docs: record sync-improvements architecture changes"
```

- [ ] **Step 6: Manual on-device step (user):** install the build, background the app, then trigger via Xcode debugger:
`e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"com.poemswe.mywhoop.refresh"]`
Verify Settings shows a fresh "Synced" time without foreground refresh.
