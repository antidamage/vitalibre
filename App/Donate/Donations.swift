import StoreKit
import SwiftUI

/// Three optional consumable donations. Nothing is unlocked by any of them.
/// Product ids come from the publisher config; if the store can't supply them
/// (unconfigured app record, or a free-signed development build) the buttons
/// say so rather than pretending to work.
@MainActor
final class Donations: ObservableObject {
    enum Status: Equatable { case loading, ready, unavailable, buying(Int), thanks(Int), failed(String) }

    @Published private(set) var status: Status = .loading
    private var products: [Int: Product] = [:]
    private var updates: Task<Void, Never>?

    init() {
        // Finish transactions that arrive outside a purchase call, so a consumable is never left pending.
        updates = Task {
            for await update in Transaction.updates {
                if case .verified(let t) = update { await t.finish() }
            }
        }
    }

    deinit { updates?.cancel() }

    func load() async {
        let ids = Publisher.store.donations.map(\.productId)
        do {
            let found = try await Product.products(for: ids)
            for d in Publisher.store.donations {
                if let p = found.first(where: { $0.id == d.productId }) { products[d.tier] = p }
            }
            status = products.isEmpty ? .unavailable : .ready
        } catch {
            status = .unavailable
        }
    }

    func price(_ tier: Int) -> String {
        products[tier]?.displayPrice ?? Publisher.store.donations.first { $0.tier == tier }?.fallbackPrice ?? ""
    }

    func available(_ tier: Int) -> Bool { products[tier] != nil }

    /// True when the purchase completed and was finished.
    func buy(_ tier: Int) async -> Bool {
        guard let product = products[tier] else { return false }
        status = .buying(tier)
        do {
            switch try await product.purchase() {
            case .success(let verification):
                guard case .verified(let transaction) = verification else {
                    status = .failed("The purchase could not be verified, so it was not completed.")
                    return false
                }
                await transaction.finish()
                status = .thanks(tier)
                return true
            case .pending:
                status = .failed("The purchase is waiting for approval. Nothing has been charged yet.")
            case .userCancelled:
                status = .ready
            @unknown default:
                status = .ready
            }
        } catch {
            status = .failed("The purchase didn't go through: \(error.localizedDescription)")
        }
        return false
    }
}
