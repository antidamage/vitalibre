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

    private var seconds: Int { Int(ScanSession.targetSeconds) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                ScreenHeading(title: "A little guidance", subtitle: "A steady finger. A clearer signal.")
                SectionPanel(title: "Taking a reading", symbol: "hand.point.up") {
                    step("01", "Sit comfortably", "Rest your hand and allow a moment to settle.")
                    step("02", "Cover the camera", "Use your fingertip, with light pressure. Keep the hand at heart height and don't talk.")
                    step("03", "Tap the orb", "Hold still for \(seconds) seconds.")
                }
                SectionPanel(title: "How it works", symbol: "waveform.path") {
                    text("Each time your heart beats, a little more blood fills the fingertip. Blood absorbs green light, so the fingertip lets through very slightly less green on every beat.")
                    text("With your finger over the rear camera, the app averages the green channel of a square in the middle of every frame. That gives one number per frame. The pulse is only about 1% of that number, so most of the work is recovering it.")
                    text("The signal is resampled to an even rate and band-passed between 0.5 and 5 Hz forwards and backwards, which removes slow drift and fast noise without shifting the beats in time.")
                    text("Beats are found with a two-moving-average detector (Elgendi 2013). Intervals outside the range a fingertip can show — 30 to 240 beats a minute — are thrown away, and the rate is the median of the rest, so a missed beat or an extra one cannot swing it.")
                }
                SectionPanel(title: "Quality, and a rhythm that swings", symbol: "checkmark.seal") {
                    text("Each scan is scored on the shape of the pulse (skewness), on how closely every beat matches the average beat, and on the strength of the pulse compared with the light level. A pulse too weak to read is reported as an error instead of a value; anything else is kept.")
                    text("A rhythm that comes unevenly is noted on the reading — low quality or arrhythmia — rather than thrown away. A camera cannot tell a poor signal from an irregular rhythm, and the app does not try to diagnose either. That note is what it is: a reason to treat the numbers on that reading with more caution.")
                }
                SectionPanel(title: "Blood pressure", symbol: "drop") {
                    text("The blood pressure figure is an estimate derived from pulse-shape features. Before calibration it is shown as a range; after calibration, as a single figure for each component. A camera cannot measure blood pressure on its own. Without calibration, version 1 starts from typical values for your age and sex and adjusts them by a small, capped amount. It has not been clinically validated.")
                    text("For reference, published calibration-free camera methods have a typical error of about 13–16 mmHg systolic and 7–9 mmHg diastolic. That is two to three times worse than the ISO 81060-2 criterion (mean difference within 5 mmHg, standard deviation within 8 mmHg). Finger-camera heart rate is typically within about 2 beats per minute of an ECG at rest.")
                    text("Do not use these results to make medical decisions. For an accurate blood pressure reading, use a clinically validated blood pressure monitor.")
                }
                SectionPanel(title: Publisher.policy.calibrationHowTitle, symbol: "slider.horizontal.3") {
                    ForEach(Publisher.policy.calibrationHow.components(separatedBy: "\n\n"), id: \.self) { text($0) }
                }
                SectionPanel(title: "Getting a good reading", symbol: "thermometer.medium") {
                    text("Cold hands, pressing hard, movement, bright sunlight, dark skin tones and some devices all reduce accuracy. Optical pulse sensing is less reliable on darker skin, and blood pressure error is larger at high and low pressures and in older people.")
                }
                SectionPanel(title: "Further reading", symbol: "book.closed") {
                    ForEach(sources) { s in
                        Link(s.title + " ↗", destination: URL(string: s.url)!)
                    }
                }.font(.rajdhani(17)).tint(palette.led)
                SectionPanel(title: Publisher.policy.freeForeverTitle, symbol: "heart") { text(Publisher.policy.freeForever) }
                SectionPanel(title: Publisher.policy.nothingSentTitle, symbol: "lock.shield") { text(Publisher.policy.nothingSent) }
            }.padding(.horizontal, 22).padding(.bottom, 30).frame(maxWidth: 640).frame(maxWidth: .infinity)
        }
    }

    private func text(_ s: String) -> some View {
        Text(s).font(.rajdhani(17)).foregroundStyle(palette.readoutSecondary)
    }

    private func step(_ number: String, _ title: String, _ detail: String) -> some View {
        HStack(alignment: .top, spacing: 16) {
            Text(number).font(.rajdhani(25)).foregroundStyle(palette.led)
            VStack(alignment: .leading, spacing: 5) {
                Text(title).font(.chakra(16)).foregroundStyle(palette.readout)
                Text(detail).font(.rajdhani(16)).foregroundStyle(palette.readoutSecondary)
            }
        }
    }
}
