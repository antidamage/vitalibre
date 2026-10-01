import SwiftUI

private struct TierFrames: PreferenceKey {
    static var defaultValue: [Int: CGRect] = [:]
    static func reduce(value: inout [Int: CGRect], nextValue: () -> [Int: CGRect]) {
        value.merge(nextValue()) { $1 }
    }
}

struct DonateView: View {
    @Environment(\.palette) private var palette
    @StateObject private var donations = Donations()
    @State private var frames: [Int: CGRect] = [:]
    @State private var bursts: [ConfettiBurst] = []

    var body: some View {
        ZStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    ScreenHeading(title: "Support VitaLibre", subtitle: "Optional. Appreciated. Never required.")
                    SectionPanel(title: "Free forever", symbol: "heart") {
                        Text("No ads. No subscription. Nothing to unlock.").font(.chakra(22)).foregroundStyle(palette.readout)
                        Text("If this app is useful to you, a one-time donation helps support its development. The app works exactly the same whether you donate or not.")
                            .font(.rajdhani(18)).foregroundStyle(palette.readoutSecondary)
                    }
                    ForEach(Publisher.store.donations) { d in tierButton(d) }
                    statusLine
                    if case .unavailable = donations.status {
                        Button("Retry App Store") { Task { await donations.load() } }.buttonStyle(ConsoleStyle())
                    }
                }.padding(.horizontal, 22).padding(.bottom, 30).frame(maxWidth: 640).frame(maxWidth: .infinity)
            }
            ConfettiLayer(bursts: bursts)
        }
        .coordinateSpace(name: "donate")
        .onPreferenceChange(TierFrames.self) { frames = $0 }
        .task { await donations.load() }
    }

    private static let titles = ["A little support", "A generous contribution", "Something extraordinary"]
    private static let symbols = ["heart", "heart.circle", "sparkles"]

    private func tierButton(_ d: StoreConfig.Donation) -> some View {
        let enabled = donations.available(d.tier) && !isBuying
        let index = Publisher.store.donations.firstIndex { $0.tier == d.tier } ?? 0
        return Button {
            DialClick.shared.play()
            Task {
                if await donations.buy(d.tier) {
                    let frame = frames[d.tier] ?? .zero
                    bursts.append(ConfettiBurst(origin: CGPoint(x: frame.midX, y: frame.midY), tier: d.tier, start: Date()))
                    try? await Task.sleep(for: .seconds(4))
                    bursts.removeAll { Date().timeIntervalSince($0.start) > 3.5 }
                }
            }
        } label: {
            HStack {
                Image(systemName: Self.symbols[min(index, 2)]).font(.system(size: 24, weight: .light))
                VStack(alignment: .leading, spacing: 5) {
                    Text(Self.titles[min(index, 2)]).font(.chakra(15))
                    Text("One-time donation").font(.rajdhani(15)).foregroundStyle(palette.readoutSecondary)
                }
                Spacer()
                Text(donations.price(d.tier)).font(.rajdhani(27))
            }.frame(maxWidth: .infinity).padding(.vertical, 12)
        }
        .buttonStyle(ConsoleStyle())
        .disabled(!enabled)
        .background(GeometryReader { g in
            Color.clear.preference(key: TierFrames.self, value: [d.tier: g.frame(in: .named("donate"))])
        })
        .accessibilityLabel("Donate \(donations.price(d.tier))")
    }

    private var isBuying: Bool { if case .buying = donations.status { return true } else { return false } }

    @ViewBuilder private var statusLine: some View {
        switch donations.status {
        case .unavailable:
            note("Donations are not available yet. Every feature remains free.")
        case .loading, .ready, .buying:
            EmptyView()
        case .thanks:
            Text("Thank you for your support.")
                .font(.rajdhani(17)).foregroundStyle(palette.led)
        case .failed(let m):
            note(m)
        }
    }

    private func note(_ text: String) -> some View {
        Text(text).font(.rajdhani(17)).foregroundStyle(palette.readoutSecondary)
    }
}
