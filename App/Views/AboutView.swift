import SwiftUI

struct AboutView: View {
    @Environment(\.palette) private var palette
    @EnvironmentObject private var measurer: Measurer

    private var version: String {
        let info = Bundle.main.infoDictionary
        return "\(info?["CFBundleShortVersionString"] as? String ?? "0") (\(info?["CFBundleVersion"] as? String ?? "0"))"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                SectionPanel(title: Publisher.store.displayName, symbol: "info.circle") {
                    DataRow(title: "Version", value: version)
                    DataRow(title: "Model", value: measurer.modelVersion)
                    DataRow(title: "Licence", value: "GPL-3.0-or-later")
                }
                SectionPanel(title: "Regulatory", symbol: "cross.case") { text(Publisher.policy.regulatory) }
                if let url = URL(string: Publisher.store.supportURL) {
                    Link("Support ↗", destination: url).font(.rajdhani(17)).tint(palette.led)
                }
                SectionPanel(title: "Third-party notices", symbol: "doc.text") {
                    text("Chakra Petch, © 2018 The Chakra Petch Project Authors, SIL Open Font License 1.1.")
                    text("Rajdhani, by Indian Type Foundry, SIL Open Font License 1.1.")
                    text("The beat detector reimplements the published algorithm of Elgendi et al. (PLoS ONE 2013). The blood pressure approach follows the survey by Frey, Menon and Elgendi (npj Digital Medicine 2022, CC BY 4.0). No code or figures are copied from either.")
                    text("The dial click is from the owner's own dashboard sound set.")
                    if !Publisher.store.sourceURL.isEmpty, let url = URL(string: Publisher.store.sourceURL) {
                        Link("Source code ↗", destination: url).font(.rajdhani(17)).tint(palette.led)
                    }
                }
            }.padding(.horizontal, 22).padding(.vertical, 12).padding(.bottom, 18).frame(maxWidth: 640).frame(maxWidth: .infinity)
        }
    }

    private func text(_ s: String) -> some View {
        Text(s).font(.rajdhani(17)).foregroundStyle(palette.readoutSecondary)
    }
}
