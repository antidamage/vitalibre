import Foundation

/// The phone model, so cuff calibrations and readings can be grouped by device.
/// The table (iphone-models.json) carries no correction values yet: a per-model
/// baseline needs paired cuff data from that model, and none exists.
enum DeviceInfo {
    struct Model: Decodable { let identifier: String; let name: String; let year: Int; let camera: String? }
    private struct Table: Decodable { let models: [Model] }

    /// e.g. "iPhone18,2". On the simulator this is the simulated model from the environment.
    static let identifier: String = {
        if let sim = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] { return sim }
        var info = utsname(); uname(&info)
        return withUnsafePointer(to: &info.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: 1) { String(cString: $0) }
        }
    }()

    static let model: Model? = {
        guard let url = BundleFinder.url("iphone-models", "json"), let data = try? Data(contentsOf: url),
              let table = try? JSONDecoder().decode(Table.self, from: data) else { return nil }
        return table.models.first { $0.identifier == identifier }
    }()

    static var name: String { model?.name ?? identifier }

    /// The rear camera a scan uses, from the table; the main (wide) camera by default.
    static var cameraType: String { model?.camera ?? "wide" }
}
