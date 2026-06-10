import Foundation
import HealthKit
import WhoopStore

// MARK: - HealthKitSync
//
// Writes server-computed data to Apple Health on every pull-to-refresh.
// All writes are best-effort: errors are silently swallowed and never thrown to callers.
// Authorization is requested lazily on the first write; denial is silently ignored.
//
// SLEEP STAGE MAPPING (AASM → HealthKit):
//   "light"  → .asleepCore      (N1/N2 combined)
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
        HKQuantityType(.heartRate),
        HKQuantityType(.heartRateVariabilitySDNN),
        HKQuantityType(.restingHeartRate),
        HKQuantityType(.oxygenSaturation),
        HKQuantityType(.respiratoryRate),
        HKObjectType.workoutType(),
    ]

    // MARK: - Authorization

    func requestAuthorizationIfNeeded() async {
        guard HKHealthStore.isHealthDataAvailable() else { return }
        guard !authorized else { return }
        do {
            try await store.requestAuthorization(toShare: Self.writeTypes, read: [])
            authorized = true
        } catch {}
    }

    // MARK: - Write sleep sessions (sleep stages + HRV)

    func writeSessions(_ sessions: [CachedSleepSession]) async {
        guard HKHealthStore.isHealthDataAvailable() else { return }
        await requestAuthorizationIfNeeded()
        guard !sessions.isEmpty else { return }

        let sleepSamples = sessions.flatMap { makeSleepSamples(for: $0) }
        let hrvSamples   = sessions.compactMap { makeHRVSample(for: $0) }
        let samples: [HKSample] = sleepSamples + hrvSamples
        guard !samples.isEmpty else { return }

        let minStart = sessions.map { $0.startTs }.min()!
        let maxEnd   = sessions.map { $0.endTs }.max()!
        await deleteExisting(types: [HKCategoryType(.sleepAnalysis),
                                     HKQuantityType(.heartRateVariabilitySDNN)],
                             from: minStart, to: maxEnd)
        do { try await store.save(samples) } catch {}
    }

    // MARK: - Write daily metrics (resting HR, SpO2, respiratory rate)

    func writeMetrics(_ days: [DailyMetric]) async {
        guard HKHealthStore.isHealthDataAvailable() else { return }
        await requestAuthorizationIfNeeded()
        guard !days.isEmpty else { return }

        let (fromEpoch, toEpoch) = metricEpochRange(days)

        // Save each type independently — one bad value (e.g. low SpO2) won't block the others.
        let typeGroups: [(HKObjectType, [HKSample])] = [
            (HKQuantityType(.restingHeartRate), days.compactMap { makeRestingHRSample(for: $0) }),
            (HKQuantityType(.oxygenSaturation), days.compactMap { makeSpO2Sample(for: $0) }),
            (HKQuantityType(.respiratoryRate),  days.compactMap { makeRespRateSample(for: $0) }),
        ]
        for (type, samples) in typeGroups {
            guard !samples.isEmpty else { continue }
            await deleteExisting(types: [type], from: fromEpoch, to: toEpoch)
            do { try await store.save(samples) } catch {}
        }
    }

    // MARK: - Write raw HR series (downsampled)

    func writeHeartRate(_ points: [(ts: Int, bpm: Int)]) async {
        guard HKHealthStore.isHealthDataAvailable() else { return }
        await requestAuthorizationIfNeeded()
        guard !points.isEmpty else { return }

        let unit = HKUnit.count().unitDivided(by: .minute())
        let type = HKQuantityType(.heartRate)
        let samples = points.compactMap { p -> HKQuantitySample? in
            guard p.bpm > 0 else { return nil }
            let date = Date(timeIntervalSince1970: TimeInterval(p.ts))
            return HKQuantitySample(type: type,
                                    quantity: HKQuantity(unit: unit, doubleValue: Double(p.bpm)),
                                    start: date, end: date,
                                    metadata: [HKMetadataKeyWasUserEntered: false])
        }
        guard !samples.isEmpty else { return }

        let minTs = points.map { $0.ts }.min()!
        let maxTs = points.map { $0.ts }.max()!
        await deleteExisting(types: [type], from: minTs, to: maxTs)
        do { try await store.save(samples) } catch {}
    }

    // MARK: - Write workouts

    func writeWorkouts(_ workouts: [Workout]) async {
        guard HKHealthStore.isHealthDataAvailable() else { return }
        await requestAuthorizationIfNeeded()
        guard !workouts.isEmpty else { return }

        let samples = workouts.compactMap { makeWorkoutSample(for: $0) }
        guard !samples.isEmpty else { return }

        let minStart = workouts.map { $0.startTs }.min()!
        let maxEnd   = workouts.map { $0.endTs }.max()!
        await deleteExisting(types: [HKObjectType.workoutType()], from: minStart, to: maxEnd)
        do { try await store.save(samples) } catch {}
    }

    // MARK: - Sleep sample construction

    private func makeSleepSamples(for session: CachedSleepSession) -> [HKCategorySample] {
        let type   = HKCategoryType(.sleepAnalysis)
        let stages = parseStages(session.stagesJSON)

        if stages.isEmpty {
            let start = Date(timeIntervalSince1970: TimeInterval(session.startTs))
            let end   = Date(timeIntervalSince1970: TimeInterval(session.endTs))
            return [HKCategorySample(type: type, value: HKCategoryValueSleepAnalysis.inBed.rawValue,
                                     start: start, end: end, device: nil,
                                     metadata: [HKMetadataKeyWasUserEntered: false])]
        }

        var out: [HKCategorySample] = []
        let sessionStart = Date(timeIntervalSince1970: TimeInterval(session.startTs))
        let sessionEnd   = Date(timeIntervalSince1970: TimeInterval(session.endTs))
        out.append(HKCategorySample(type: type, value: HKCategoryValueSleepAnalysis.inBed.rawValue,
                                    start: sessionStart, end: sessionEnd, device: nil,
                                    metadata: [HKMetadataKeyWasUserEntered: false]))
        for seg in stages {
            let start = Date(timeIntervalSince1970: seg.start)
            let end   = Date(timeIntervalSince1970: seg.end)
            guard end > start else { continue }
            out.append(HKCategorySample(type: type, value: hkSleepValue(for: seg.stage).rawValue,
                                        start: start, end: end, device: nil,
                                        metadata: [HKMetadataKeyWasUserEntered: false]))
        }
        return out
    }

    private func hkSleepValue(for stage: String) -> HKCategoryValueSleepAnalysis {
        switch stage {
        case "deep":  return .asleepDeep
        case "rem":   return .asleepREM
        case "light": return .asleepCore
        case "wake":  return .awake
        default:      return .asleepUnspecified
        }
    }

    private func makeHRVSample(for session: CachedSleepSession) -> HKQuantitySample? {
        guard let hrv = session.avgHrv, hrv > 0 else { return nil }
        let start = Date(timeIntervalSince1970: TimeInterval(session.startTs))
        let end   = Date(timeIntervalSince1970: TimeInterval(session.endTs))
        return HKQuantitySample(type: HKQuantityType(.heartRateVariabilitySDNN),
                                quantity: HKQuantity(unit: .secondUnit(with: .milli), doubleValue: hrv),
                                start: start, end: end,
                                metadata: [HKMetadataKeyWasUserEntered: false])
    }

    // MARK: - Daily metric sample construction

    private func makeRestingHRSample(for day: DailyMetric) -> HKQuantitySample? {
        guard let hr = day.restingHr, hr > 0 else { return nil }
        let (start, end) = dayBounds(day.day)
        return HKQuantitySample(type: HKQuantityType(.restingHeartRate),
                                quantity: HKQuantity(unit: .count().unitDivided(by: .minute()),
                                                     doubleValue: Double(hr)),
                                start: start, end: end,
                                metadata: [HKMetadataKeyWasUserEntered: false])
    }

    private func makeSpO2Sample(for day: DailyMetric) -> HKQuantitySample? {
        guard let spo2 = day.spo2Pct, spo2 > 0 else { return nil }
        let (start, end) = dayBounds(day.day)
        return HKQuantitySample(type: HKQuantityType(.oxygenSaturation),
                                quantity: HKQuantity(unit: .percent(), doubleValue: spo2 / 100.0),
                                start: start, end: end,
                                metadata: [HKMetadataKeyWasUserEntered: false])
    }

    private func makeRespRateSample(for day: DailyMetric) -> HKQuantitySample? {
        guard let rr = day.respRateBpm, rr > 0 else { return nil }
        let (start, end) = dayBounds(day.day)
        return HKQuantitySample(type: HKQuantityType(.respiratoryRate),
                                quantity: HKQuantity(unit: .count().unitDivided(by: .minute()),
                                                     doubleValue: rr),
                                start: start, end: end,
                                metadata: [HKMetadataKeyWasUserEntered: false])
    }

    private func dayBounds(_ day: String) -> (Date, Date) {
        let noon = isoMidnight(day).addingTimeInterval(12 * 3600)
        return (noon, noon)
    }

    private func metricEpochRange(_ days: [DailyMetric]) -> (Int, Int) {
        let start = isoMidnight(days.first!.day)
        let end   = isoMidnight(days.last!.day).addingTimeInterval(86_400)
        return (Int(start.timeIntervalSince1970), Int(end.timeIntervalSince1970))
    }

    private func isoMidnight(_ day: String) -> Date {
        let fmt = DateFormatter()
        fmt.calendar = Calendar(identifier: .gregorian)
        fmt.timeZone = TimeZone(identifier: "UTC")
        fmt.dateFormat = "yyyy-MM-dd"
        return fmt.date(from: day) ?? Date()
    }

    // MARK: - Workout sample construction

    private func makeWorkoutSample(for w: Workout) -> HKWorkout? {
        let start = Date(timeIntervalSince1970: TimeInterval(w.startTs))
        let end   = Date(timeIntervalSince1970: TimeInterval(w.endTs))
        guard end > start else { return nil }
        let calories = w.caloriesKcal.map { HKQuantity(unit: .kilocalorie(), doubleValue: $0) }
        return HKWorkout(activityType: hkActivityType(for: w.kind),
                         start: start, end: end,
                         workoutEvents: nil,
                         totalEnergyBurned: calories,
                         totalDistance: nil,
                         metadata: [HKMetadataKeyWasUserEntered: false])
    }

    private func hkActivityType(for kind: String?) -> HKWorkoutActivityType {
        switch kind {
        case "running":            return .running
        case "cycling":            return .cycling
        case "swimming":           return .swimming
        case "walking":            return .walking
        case "hiking":             return .hiking
        case "yoga":               return .yoga
        case "strength_training":  return .traditionalStrengthTraining
        case "rowing":             return .rowing
        case "elliptical":         return .elliptical
        case "stair_climbing":     return .stairClimbing
        case "pilates":            return .pilates
        case "basketball":         return .basketball
        case "soccer":             return .soccer
        case "tennis":             return .tennis
        case "golf":               return .golf
        case "boxing":             return .boxing
        case "martial_arts":       return .martialArts
        case "dance":              return .cardioDance
        case "skiing":             return .downhillSkiing
        case "snowboarding":       return .snowboarding
        default:                   return .other
        }
    }

    // MARK: - Deletion

    private func deleteExisting(types: [HKObjectType], from startEpoch: Int, to endEpoch: Int) async {
        let start = Date(timeIntervalSince1970: TimeInterval(startEpoch))
        let end   = Date(timeIntervalSince1970: TimeInterval(endEpoch))
        let timePred   = HKQuery.predicateForSamples(withStart: start, end: end, options: [])
        let sourcePred = HKQuery.predicateForObjects(from: [HKSource.default()])
        let predicate  = NSCompoundPredicate(andPredicateWithSubpredicates: [timePred, sourcePred])
        for type in types {
            do { try await store.deleteObjects(of: type, predicate: predicate) } catch {}
        }
    }

    // MARK: - Stage JSON parsing

    private struct StageSegment { let start: Double; let end: Double; let stage: String }

    private func parseStages(_ json: String?) -> [StageSegment] {
        guard let json,
              let data = json.data(using: .utf8),
              let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return arr.compactMap { d in
            guard let start = (d["start"] as? NSNumber)?.doubleValue,
                  let end   = (d["end"]   as? NSNumber)?.doubleValue,
                  let stage = d["stage"]  as? String else { return nil }
            return StageSegment(start: start, end: end, stage: stage)
        }
    }
}
