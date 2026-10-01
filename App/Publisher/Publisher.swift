import Foundation

/// Everything that belongs to the publisher (bundle id, product ids, URLs,
/// policy text) is read from the bundled JSON in publisher/config, never
/// written into app code. A fork replaces those files and nothing else.
struct StoreConfig: Decodable {
    struct Donation: Decodable, Identifiable {
        let tier: Int
        let productId: String
        let fallbackPrice: String
        var id: Int { tier }
    }
    let displayName: String
    let bundleId: String
    let supportEmail: String
    let privacyURL: String
    let sourceURL: String
    let donations: [Donation]
}

struct Policy: Decodable {
    let freeForeverTitle: String
    let freeForever: String
    let nothingSentTitle: String
    let nothingSent: String
    let disclaimer: String
    let disclaimerBody: String
    let regulatory: String
    let calibrationHowTitle: String
    let calibrationHow: String
}

enum Publisher {
    static let store: StoreConfig = load("store.config")
    static let policy: Policy = load("policy")

    private static func load<T: Decodable>(_ name: String) -> T {
        guard let url = BundleFinder.url(name, "json"), let data = try? Data(contentsOf: url),
              let value = try? JSONDecoder().decode(T.self, from: data) else {
            preconditionFailure("publisher config \(name).json is missing or invalid")
        }
        return value
    }
}
