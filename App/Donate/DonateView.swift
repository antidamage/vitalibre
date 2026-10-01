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
                VStack(spacing: 14) {
                    VStack(alignment: .leading, spacing: 10) {
                        BodyText(text: "VitaLibre is free and always will be. Donations are entirely optional and unlock nothing. Thank you for your support.")
                    }
                    .padding(16).frame(maxWidth: .infinity, alignment: .leading).panel()

                    VStack(spacing: 12) {
                        ForEach(Publisher.store.donations) { d in tierButton(d) }
                        statusLine
                    }
                    .padding(16).panel()
                }
                .padding(.horizontal, 16).padding(.vertical, 10)
            }
            ConfettiLayer(bursts: bursts)
        }
        .coordinateSpace(name: "donate")
        .onPreferenceChange(TierFrames.self) { frames = $0 }
        .task { await donations.load() }
    }

    private func tierButton(_ d: StoreConfig.Donation) -> some View {
        let enabled = donations.available(d.tier) && !isBuying
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
                Text(donations.price(d.tier)).font(.chakra(28, .light))
                Spacer()
            }
            .foregroundStyle(enabled ? palette.readout : palette.readoutSecondary)
            .padding(.horizontal, 18).frame(minHeight: 60)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(LinearGradient(colors: [palette.background.mixed(with: .white, palette.isLight ? 0.55 : 0.07),
                                                  palette.background.mixed(with: .black, palette.isLight ? 0.07 : 0.35)],
                                         startPoint: .top, endPoint: .bottom))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(LinearGradient(colors: [.white.opacity(palette.isLight ? 0.95 : 0.22), .clear,
                                                          .black.opacity(palette.isLight ? 0.18 : 0.7)],
                                                 startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 1.5)
            )
            .shadow(color: .black.opacity(palette.isLight ? 0.22 : 0.6), radius: 7, y: 4)
            .opacity(enabled ? 1 : 0.75)
        }
        .buttonStyle(PressStyle())
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
            note("Donations are not available yet.")
        case .loading, .ready, .buying:
            EmptyView()
        case .thanks:
            Text("Thank you for your support.")
                .font(.chakra(15, .medium)).foregroundStyle(palette.led).multilineTextAlignment(.center)
        case .failed(let m):
            note(m)
        }
    }

    private func note(_ text: String) -> some View {
        Text(text).font(.rajdhani(14)).foregroundStyle(palette.readoutSecondary).multilineTextAlignment(.center)
    }
}
