import Foundation

struct Reading: Identifiable, Codable, Equatable {
    var id = UUID()
    var date: Date
    var heartRate: Double
    var bp: BPRange
    var quality: Double
    var level: QualityLevel
    var duration: Double
    var modelVersion: String
    var starred = false
}

/// The user's own readings, one JSON file in Application Support. Written
/// atomically; never read by anything but this app, never transmitted.
final class ReadingStore: ObservableObject {
    @Published private(set) var readings: [Reading] = []
    private let url: URL

    init(directory: URL? = nil) {
        let base = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("VitaLibre", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        url = base.appendingPathComponent("readings.json")
        load()
    }

    private func load() {
        guard let data = try? Data(contentsOf: url) else { return }
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        readings = ((try? decoder.decode([Reading].self, from: data)) ?? []).sorted { $0.date > $1.date }
    }

    private func save() {
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        if let data = try? encoder.encode(readings) { try? data.write(to: url, options: .atomic) }
    }

    @discardableResult
    func add(_ result: ScanResult, at date: Date = Date()) -> Reading {
        let r = Reading(date: date, heartRate: result.heartRate, bp: result.bp, quality: result.quality,
                        level: result.level, duration: result.duration, modelVersion: result.modelVersion)
        readings.insert(r, at: 0)
        save()
        return r
    }

    /// Every reading, oldest first, as plain text one reading per line.
    func exportText() -> String {
        let stamp = DateFormatter(); stamp.dateFormat = "yyyy-MM-dd HH:mm"
        var lines = ["VitaLibre readings (\(readings.count))",
                     "Heart rate in bpm; blood pressure in mmHg. Values are estimates. Not a medical device.", ""]
        for r in readings.sorted(by: { $0.date < $1.date }) {
            lines.append("\(stamp.string(from: r.date))  HR \(Int(r.heartRate.rounded()))  BP \(r.bp.text)  quality \(r.level.rawValue)\(r.starred ? "  starred" : "")")
        }
        return lines.joined(separator: "\n")
    }

    func toggleStar(_ id: UUID) {
        guard let i = readings.firstIndex(where: { $0.id == id }) else { return }
        readings[i].starred.toggle()
        save()
    }

    func delete(_ ids: [UUID]) {
        readings.removeAll { ids.contains($0.id) }
        save()
    }
}
