import StoreKit
import SwiftUI

/// Three optional consumable donations. Nothing is unlocked by any of them.
/// Product ids come from the publisher config; if the store can't supply them
/// (unconfigured app record, or a free-signed development build) the buttons
/// say so rather than pretending to work.
@MainActor
final class Donations: ObservableObject {
    enum Status: Equatable { case loading, ready, unavailable, buying(Int), pending, thanks(Int), failed(String) }

    @Published private(set) var status: Status = .loading
    @Published private var products: [Int: Product] = [:]
    private var updates: Task<Void, Never>?
    private var recovery: Task<Void, Never>?
    private var handledTransactions = Set<UInt64>()
    private var buying = false

    init() {
        // Finish transactions that arrive outside a purchase call, so a consumable is never left pending.
        updates = Task {
            for await update in Transaction.updates {
                if case .verified(let transaction) = update {
                    await complete(transaction)
                }
            }
        }
        recovery = Task {
            for await update in Transaction.unfinished {
                if case .verified(let transaction) = update { await complete(transaction) }
            }
        }
    }

    deinit { updates?.cancel(); recovery?.cancel() }

    private func complete(_ transaction: StoreKit.Transaction) async {
        guard handledTransactions.insert(transaction.id).inserted else { return }
        await transaction.finish()
        if let tier = Publisher.store.donations.first(where: { $0.productId == transaction.productID })?.tier {
            status = .thanks(tier)
        }
    }

    func load() async {
        let ids = Publisher.store.donations.map(\.productId)
        do {
            let found = try await Product.products(for: ids)
            products.removeAll()
            for d in Publisher.store.donations {
                if let p = found.first(where: { $0.id == d.productId }) { products[d.tier] = p }
            }
            switch status {
            case .pending, .buying, .thanks: break
            default: status = products.isEmpty ? .unavailable : .ready
            }
        } catch {
            products.removeAll()
            switch status {
            case .pending, .buying, .thanks: break
            default: status = .unavailable
            }
        }
    }

    func price(_ tier: Int) -> String {
        products[tier]?.displayPrice ?? Publisher.store.donations.first { $0.tier == tier }.map { "US \($0.fallbackPrice)" } ?? ""
    }

    func available(_ tier: Int) -> Bool { products[tier] != nil }

    var canRetryProducts: Bool {
        guard products.isEmpty else { return false }
        switch status {
        case .loading, .buying, .pending: return false
        default: return true
        }
    }

    /// True when the purchase completed and was finished.
    func buy(_ tier: Int) async -> Bool {
        guard !buying, status != .pending, let product = products[tier] else { return false }
        buying = true
        defer { buying = false }
        status = .buying(tier)
        do {
            switch try await product.purchase() {
            case .success(let verification):
                guard case .verified(let transaction) = verification else {
                    status = .failed("The purchase could not be verified, so it was not completed.")
                    return false
                }
                await complete(transaction)
                return true
            case .pending:
                status = .pending
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
