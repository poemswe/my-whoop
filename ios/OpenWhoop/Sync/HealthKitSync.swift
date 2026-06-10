import Foundation
import HealthKit
import WhoopStore

// MARK: - HealthKitSync
//
// Writes server-computed sleep sessions to Apple Health as HKCategorySample entries.
// One sample per stage segment; the full in-bed span is always written as .inBed so
// Health's Sleep timeline shows the correct block even when staging is incomplete.
//
// Authorization is requested lazily on the first write attempt; if the user denies it
// the write is silently skipped (never throws to the caller).
//
// STAGE MAPPING (AASM → HealthKit):
//   "light"  → .asleepCore      (N1/N2 combined — the closest HK equivalent)
//   "deep"   → .asleepDeep
//   "rem"    → .asleepREM
//   "wake"   → .awake
//   fallback → .asleepUnspecified

@MainActor
final class HealthKitSync {
    static let shared = HealthKitSync()

    private let store = HKHealthStore()
    private var authorized = false

    private static let writeTypes: Set<HKSampleType> = [
        HKCategoryType(.sleepAnalysis),
    ]

    // MARK: - Authorization

    func requestAuthorizationIfNeeded() async {
        guard HKHealthStore.isHealthDataAvailable() else { return }
        guard !authorized else { return }
        do {
            try await store.requestAuthorization(toShare: Self.writeTypes, read: [])
            authorized = true
        } catch {
            // Silently ignore — permission denied or unavailable.
        }
    }

    // MARK: - Write sleep sessions

    /// Write `sessions` to HealthKit. Deletes any existing OpenWhoop-sourced samples that
    /// overlap the written date range first so recomputes don't duplicate entries.
    func writeSessions(_ sessions: [CachedSleepSession]) async {
        guard HKHealthStore.isHealthDataAvailable() else { return }
        await requestAuthorizationIfNeeded()

        guard !sessions.isEmpty else { return }

        let samples = sessions.flatMap { makeSamples(for: $0) }
        guard !samples.isEmpty else { return }

        // Delete existing samples from this source for the covered span before inserting.
        let minStart = sessions.map { $0.startTs }.min()!
        let maxEnd   = sessions.map { $0.endTs }.max()!
        await deleteExisting(from: minStart, to: maxEnd)

        do {
            try await store.save(samples)
        } catch {
            // Best-effort — silently ignore write failures.
        }
    }

    // MARK: - Sample construction

    private func makeSamples(for session: CachedSleepSession) -> [HKCategorySample] {
        let type = HKCategoryType(.sleepAnalysis)
        let source = HKSource.default()
        var out: [HKCategorySample] = []

        // Parse the stages JSON if present.
        let stages = parseStages(session.stagesJSON)

        if stages.isEmpty {
            // No stage detail — write a single in-bed block for the full session.
            let start = Date(timeIntervalSince1970: TimeInterval(session.startTs))
            let end   = Date(timeIntervalSince1970: TimeInterval(session.endTs))
            let sample = HKCategorySample(type: type,
                                          value: HKCategoryValueSleepAnalysis.inBed.rawValue,
                                          start: start, end: end,
                                          device: nil, metadata: [HKMetadataKeyWasUserEntered: false])
            return [sample]
        }

        // In-bed wrapper (full session span).
        let sessionStart = Date(timeIntervalSince1970: TimeInterval(session.startTs))
        let sessionEnd   = Date(timeIntervalSince1970: TimeInterval(session.endTs))
        out.append(HKCategorySample(type: type,
                                    value: HKCategoryValueSleepAnalysis.inBed.rawValue,
                                    start: sessionStart, end: sessionEnd,
                                    device: nil, metadata: [HKMetadataKeyWasUserEntered: false]))

        // Per-stage samples.
        for seg in stages {
            let start = Date(timeIntervalSince1970: seg.start)
            let end   = Date(timeIntervalSince1970: seg.end)
            guard end > start else { continue }
            let value = hkValue(for: seg.stage)
            out.append(HKCategorySample(type: type,
                                        value: value.rawValue,
                                        start: start, end: end,
                                        device: nil, metadata: [HKMetadataKeyWasUserEntered: false]))
        }
        return out
    }

    private func hkValue(for stage: String) -> HKCategoryValueSleepAnalysis {
        switch stage {
        case "deep":  return .asleepDeep
        case "rem":   return .asleepREM
        case "light": return .asleepCore
        case "wake":  return .awake
        default:      return .asleepUnspecified
        }
    }

    // MARK: - Deletion of stale samples

    private func deleteExisting(from startEpoch: Int, to endEpoch: Int) async {
        let type = HKCategoryType(.sleepAnalysis)
        let start = Date(timeIntervalSince1970: TimeInterval(startEpoch))
        let end   = Date(timeIntervalSince1970: TimeInterval(endEpoch))
        let predicate = HKQuery.predicateForSamples(withStart: start, end: end, options: [])
        let sourcePredicate = HKQuery.predicateForObjects(from: [HKSource.default()])
        let combined = NSCompoundPredicate(andPredicateWithSubpredicates: [predicate, sourcePredicate])

        do {
            try await store.deleteObjects(of: type, predicate: combined)
        } catch {
            // Non-fatal — old samples may remain; duplicates are visible but not harmful.
        }
    }

    // MARK: - Stage JSON parsing

    private struct StageSegment {
        let start: Double
        let end: Double
        let stage: String
    }

    private func parseStages(_ json: String?) -> [StageSegment] {
        guard let json,
              let data = json.data(using: .utf8),
              let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return []
        }
        return arr.compactMap { d -> StageSegment? in
            guard let start = (d["start"] as? NSNumber)?.doubleValue,
                  let end   = (d["end"]   as? NSNumber)?.doubleValue,
                  let stage = d["stage"]  as? String else { return nil }
            return StageSegment(start: start, end: end, stage: stage)
        }
    }
}
