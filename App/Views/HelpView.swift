import SwiftUI

struct HelpView: View {
    @Environment(\.palette) private var palette

    private struct Source: Identifiable { let id = UUID(); let title: String; let url: String }
    private let sources: [Source] = [
        .init(title: "Blood pressure measurement using only a smartphone (Frey, Menon, Elgendi 2022)", url: "https://doi.org/10.1038/s41746-022-00629-2"),
        .init(title: "The same study, PDF on ResearchGate", url: "https://www.researchgate.net/publication/361819163_Blood_pressure_measurement_using_only_a_smartphone/link/62c6fd02d7bd92231f9e50cd/download?_tp=eyJjb250ZXh0Ijp7ImZpcnN0UGFnZSI6InB1YmxpY2F0aW9uIiwicGFnZSI6InB1YmxpY2F0aW9uIn19"),
        .init(title: "ppg-vitals, camera PPG code this work draws on (markolalovic, MIT)", url: "https://github.com/markolalovic/ppg-vitals"),
        .init(title: "Open-source photoplethysmogram projects on GitHub", url: "https://github.com/topics/photoplethysmogram"),
        .init(title: "Elgendi et al. 2013, systolic peak detection in PPG (PLoS ONE)", url: "https://doi.org/10.1371/journal.pone.0076585"),
        .init(title: "Liang et al. 2018, PPG database and filter choice (Scientific Data)", url: "https://doi.org/10.1038/sdata.2018.20"),
        .init(title: "Finger-camera heart rate accuracy against ECG (PMC5368348)", url: "https://pmc.ncbi.nlm.nih.gov/articles/PMC5368348/"),
        .init(title: "Calibration-free PPG blood pressure benchmark (PMC10030661)", url: "https://pmc.ncbi.nlm.nih.gov/articles/PMC10030661/"),
        .init(title: "ISO 81060-2:2018 blood pressure device validation", url: "https://www.iso.org/standard/73339.html"),
    ]

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                card("How it works", [
                    "Each time your heart beats, a little more blood fills the fingertip. Blood absorbs green light, so the fingertip lets through very slightly less green on every beat.",
                    "With your finger over the rear camera and the flash on, the app averages the green channel of a square in the middle of every frame. That gives one number per frame. The pulse is only about 1% of that number, so most of the work is recovering it.",
                    "The signal is resampled to an even rate and band-passed between 0.5 and 5 Hz forwards and backwards, which removes slow drift and fast noise without shifting the beats in time.",
                    "Beats are found with a two-moving-average detector (Elgendi 2013). Intervals that are physically implausible, or far from their neighbours, are thrown away. Heart rate is the median of what is left.",
                ])
                card("The quality gate", [
                    "Each scan is scored on the shape of the pulse (skewness), on how closely every beat matches the average beat, and on the strength of the pulse compared with the light level. If the score is too low, the app reports an error instead of a value.",
                ])
                card("Blood pressure", [
                    "The blood pressure figure is an estimate derived from pulse-shape features. Before calibration it is shown as a range; after calibration, as a single figure for each component. A camera cannot measure blood pressure on its own. Without calibration, version 1 starts from typical values for your age and sex and adjusts them by a small, capped amount. It has not been clinically validated.",
                    "For reference, published calibration-free camera methods have a typical error of about 13–16 mmHg systolic and 7–9 mmHg diastolic. That is two to three times worse than the ISO 81060-2 criterion (mean difference within 5 mmHg, standard deviation within 8 mmHg). Finger-camera heart rate is typically within about 2 beats per minute of an ECG at rest.",
                    "Calibration: after a reading, tap Calibrate and enter the value shown by a validated cuff taken at the same time. The estimate shifts to match your cuff. The remaining error is about the spread of your own calibration readings, and it is only as good as the cuff used. Calibration improves how well the estimate follows your own readings; it does not make it a medical measurement.",
                    "Do not use these results to make medical decisions. For an accurate blood pressure reading, use a clinically validated blood pressure monitor.",
                ])
                card("Getting a good reading", [
                    "Sit still for a few minutes first. Rest your fingertip lightly over the lens and flash together, with no pressure. Keep the hand at heart height and don't talk.",
                    "Cold hands, pressing hard, movement, bright sunlight, dark skin tones and some devices all reduce accuracy. Optical pulse sensing is less reliable on darker skin, and blood pressure error is larger at high and low pressures and in older people.",
                ])
                card(Publisher.policy.freeForeverTitle, [Publisher.policy.freeForever])
                card(Publisher.policy.nothingSentTitle, [Publisher.policy.nothingSent])
                VStack(alignment: .leading, spacing: 10) {
                    SectionTitle(text: "Further reading and sources")
                    ForEach(sources) { s in
                        Link(destination: URL(string: s.url)!) {
                            HStack(alignment: .top) {
                                Text(s.title).font(.rajdhani(15, semibold: true)).foregroundStyle(palette.led)
                                    .multilineTextAlignment(.leading)
                                Spacer(minLength: 6)
                                Image(systemName: "arrow.up.right").font(.system(size: 12)).foregroundStyle(palette.led)
                            }
                        }
                    }
                }
                .padding(16).panel()
            }
            .padding(.horizontal, 16).padding(.vertical, 10)
        }
    }

    private func card(_ title: String, _ paragraphs: [String]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionTitle(text: title)
            ForEach(paragraphs.indices, id: \.self) { BodyText(text: paragraphs[$0]) }
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading).panel()
    }
}
