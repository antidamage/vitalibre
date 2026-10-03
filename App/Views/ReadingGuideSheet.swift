import SwiftUI

/// "Before you measure": how to take a good reading, with an optional five-minute rest timer. Opened
/// automatically before the first scan, and from Measure and Help afterwards. The timer never blocks a scan:
/// it is there so the rest is easy to do, not a gate.
struct ReadingGuideSheet: View {
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @State private var endsAt: Date?
    @State private var remaining = ReadingGuideSheet.restSeconds
    @State private var finished = false
    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    static let restSeconds = 300

    var body: some View {
        ZStack {
            palette.backgroundGradient.ignoresSafeArea()
            VStack(spacing: 16) {
                HStack {
                    Text(Publisher.policy.readingGuideTitle.uppercased()).font(.chakra(15, .medium)).tracking(3).foregroundStyle(palette.clock)
                    Spacer()
                    Button("Done") { dismiss() }.font(.chakra(14, .medium)).foregroundStyle(palette.led)
                }
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        ForEach(Publisher.policy.readingGuide.components(separatedBy: "\n\n"), id: \.self) { paragraph($0) }
                        timer
                    }
                    .padding(16).frame(maxWidth: .infinity, alignment: .leading).panel()
                }
            }
            .padding(20)
        }
        .onReceive(tick) { now in
            guard let end = endsAt else { return }
            remaining = max(0, Int(end.timeIntervalSince(now).rounded(.up)))
            if remaining == 0 { endsAt = nil; finished = true; Sounds.play(Sounds.done); Haptics.end() }
        }
    }

    /// The first sentence leads: "Rest first. Sit quietly …".
    private func paragraph(_ text: String) -> some View {
        let split = text.range(of: ". ")
        let lead = split.map { String(text[..<$0.lowerBound]) + "." } ?? ""
        let rest = split.map { String(text[$0.upperBound...]) } ?? text
        return (Text(lead.isEmpty ? "" : lead + " ").font(.rajdhani(17, semibold: true)).foregroundColor(palette.readout)
                + Text(rest).font(.rajdhani(17)).foregroundColor(palette.readoutSecondary))
    }

    private var timer: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionTitle(text: Publisher.policy.restTimerTitle)
            if finished {
                BodyText(text: Publisher.policy.restTimerDone)
            } else if endsAt != nil {
                Text(String(format: "%d:%02d", remaining / 60, remaining % 60))
                    .font(.rajdhani(40)).monospacedDigit().foregroundStyle(palette.readout)
            }
            if endsAt == nil {
                RingButton(title: finished ? "Rest again" : "Start the timer") {
                    DialClick.shared.play()
                    finished = false
                    remaining = Self.restSeconds
                    endsAt = Date().addingTimeInterval(Double(Self.restSeconds))
                }
            } else {
                Button("Stop the timer") { endsAt = nil; remaining = Self.restSeconds }
                    .font(.chakra(13, .medium)).foregroundStyle(palette.led)
            }
        }
        .padding(.top, 6)
    }
}
