import SwiftUI

struct HelpView: View {
    @Environment(\.palette) private var palette
    @State private var showGuide = false

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
                SectionPanel(title: Publisher.policy.readingGuideTitle, symbol: "figure.seated.side") {
                    ForEach(Publisher.policy.readingGuide.components(separatedBy: "\n\n"), id: \.self) { text($0) }
                    Button("Open with the five-minute timer") { DialClick.shared.play(); showGuide = true }
                        .buttonStyle(ConsoleStyle())
                }
                SectionPanel(title: "How it works", symbol: "waveform.path") {
                    text("Each time your heart beats, a little more blood fills the fingertip. Blood absorbs green light, so the fingertip lets through very slightly less green on every beat.")
                    text("With your finger over the rear camera, the app averages the green channel of a square in the middle of every frame. That gives one number per frame. The pulse is only about 1% of that number, so most of the work is recovering it.")
                    text("The signal is resampled to an even rate and band-passed between 0.5 and 5 Hz forwards and backwards, which removes slow drift and fast noise without shifting the beats in time.")
                    text("Beats are found with a two-moving-average detector (Elgendi 2013). Intervals outside the range a fingertip can show — 30 to 240 beats a minute — are thrown away, and the rate is the median of the rest, so a missed beat or an extra one cannot swing it.")
                }
                SectionPanel(title: "Camera feed quality, and a rhythm that swings", symbol: "checkmark.seal") {
                    text("Once a second the app scores the camera feed itself: how much of the lens the finger covers, whether the picture is clipped, too dark or too bright, whether frames were dropped, how noisy it is, whether the picture jumped, and how much the phone moved or shook (from its motion sensors). The score is the worst of those. It never looks at the beats, so a weak or uneven pulse does not lower it. It is the thin line over the graph on a saved reading.")
                    text("A poor feed is marked low signal quality and says nothing about your heart. When the feed is usable and the beats come unevenly, the reading is marked an irregular pulse: a reason to check with a cuff or a clinician. If two of your last three scans within half an hour are irregular, the note says so more strongly. A fingertip camera cannot tell atrial fibrillation from extra beats or movement, so it never uses the word arrhythmia, and it shows no blood pressure for an irregular pulse.")
                    text("Pressing too hard and a genuinely weak pulse look alike in the feed, so the app asks for lighter pressure before it calls a pulse weak. A pulse too weak to read at all is reported as an error instead of a value; anything else is kept.")
                    text("On a saved reading you can leave out stretches of the graph, for example where the feed was poor, and the numbers are worked out again from the rest. Nothing is deleted, and you can put a stretch back.")
                }
                SectionPanel(title: "Blood pressure", symbol: "drop") {
                    text("The blood pressure figure is an estimate derived from pulse-shape features. A camera cannot measure blood pressure on its own, so the app shows a figure only while a cuff calibration is current: a paired cuff reading from the last \(Publisher.policy.bpCalibrationValidDays) days. Until then the figure is worked out and kept but not shown. It starts from typical values for your age and sex, or your own typical pressure, and adjusts them by a small, capped amount. It has not been clinically validated, and a normal-looking estimate does not rule out high blood pressure.")
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
        .sheet(isPresented: $showGuide) { ReadingGuideSheet().environment(\.palette, palette) }
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
