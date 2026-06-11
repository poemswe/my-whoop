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
