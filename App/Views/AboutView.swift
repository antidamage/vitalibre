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
            VStack(spacing: 14) {
                VStack(alignment: .leading, spacing: 10) {
                    SectionTitle(text: Publisher.store.displayName)
                    row("Version", version)
                    row("Model", measurer.modelVersion)
                    row("Licence", "GPL-3.0-or-later")
                }
                .padding(16).panel()

                VStack(alignment: .leading, spacing: 10) {
                    SectionTitle(text: "Regulatory")
                    BodyText(text: Publisher.policy.regulatory)
                }
                .padding(16).frame(maxWidth: .infinity, alignment: .leading).panel()

                VStack(alignment: .leading, spacing: 10) {
                    SectionTitle(text: "Third-party notices")
                    BodyText(text: "Chakra Petch, © 2018 The Chakra Petch Project Authors, SIL Open Font License 1.1.")
                    BodyText(text: "Rajdhani, by Indian Type Foundry, SIL Open Font License 1.1.")
                    BodyText(text: "The beat detector reimplements the published algorithm of Elgendi et al. (PLoS ONE 2013). The blood pressure approach follows the survey by Frey, Menon and Elgendi (npj Digital Medicine 2022, CC BY 4.0). No code or figures are copied from either.")
                    BodyText(text: "The dial click is from the owner's own dashboard sound set.")
                    if !Publisher.store.sourceURL.isEmpty, let url = URL(string: Publisher.store.sourceURL) {
                        Link("Source code", destination: url).font(.rajdhani(16, semibold: true)).foregroundStyle(palette.led)
                    }
                }
                .padding(16).frame(maxWidth: .infinity, alignment: .leading).panel()
            }
            .padding(.horizontal, 16).padding(.vertical, 10)
        }
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(.rajdhani(15)).foregroundStyle(palette.readoutSecondary)
            Spacer()
            Text(value).font(.chakra(13, .medium)).foregroundStyle(palette.readout)
        }
    }
}
