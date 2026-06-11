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
