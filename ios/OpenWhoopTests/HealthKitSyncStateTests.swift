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
