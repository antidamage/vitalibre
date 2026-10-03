import SwiftUI

struct ReadingsView: View {
    @EnvironmentObject private var store: ReadingStore
    @EnvironmentObject private var prefs: Preferences
    @EnvironmentObject private var measurer: Measurer
    @Environment(\.palette) private var palette
    @State private var starredOnly = false
    /// The reading whose graph is open, if one is.
    @State private var openGraph: Reading?

    private func showsBP(_ r: Reading) -> Bool { r.bpHidden(prefs) == nil }

    private var shown: [Reading] { starredOnly ? store.savedReadings.filter(\.starred) : store.savedReadings }

    var body: some View {
        VStack(spacing: 14) {
            HStack(spacing: 8) {
                Button("All") { DialClick.shared.play(); starredOnly = false }.buttonStyle(ConsoleStyle(selected: !starredOnly))
                Button("⭐ Starred") { DialClick.shared.play(); starredOnly = true }.buttonStyle(ConsoleStyle(selected: starredOnly))
                Spacer()
                ShareLink(item: store.validationCSV(calibration: prefs.calibration, appVersion: DeviceInfo.appVersion,
                                                    modelVersion: measurer.modelVersion, showBP: showsBP)) {
                    Image(systemName: "tablecells")
                }
                .buttonStyle(ConsoleStyle())
                .disabled(store.savedReadings.isEmpty && prefs.calibration.count == 0)
                .accessibilityLabel("Export cuff comparisons and raw values as CSV")
                ShareLink(item: store.exportText(showBP: showsBP)) { Image(systemName: "square.and.arrow.up") }
                    .buttonStyle(ConsoleStyle())
                    .disabled(store.savedReadings.isEmpty)
                    .accessibilityLabel("Export all readings as text")
            }
            .padding(.horizontal, 22).padding(.top, 6)
            if shown.isEmpty {
                VStack {
                    Spacer()
                    Text(starredOnly
                         ? "Nothing starred yet."
                         : "Nothing kept yet. A reading is filed on Measure the moment it finishes — tap it there to keep it.")
                        .font(.rajdhani(17)).foregroundStyle(palette.readoutSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 34)
                    Spacer()
                }
            } else {
                List {
                    ForEach(shown) { reading in
                        row(reading)
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                            .listRowInsets(EdgeInsets(top: 7, leading: 22, bottom: 7, trailing: 22))
                    }
                    .onDelete { offsets in store.delete(offsets.map { shown[$0].id }) }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
        .fullScreenCover(item: $openGraph) { reading in
            ReadingGraphView(readingID: reading.id) { openGraph = nil }
        }
    }

    /// Tap the reading to star it, or its band to open the graph; swipe to delete.
    ///
    /// The band is its own button rather than part of the row's: one tap cannot both star a
    /// reading and open its graph.
    private func row(_ r: Reading) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Button {
                store.toggleStar(r.id)
            } label: {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(alignment: .firstTextBaseline) {
                        Text("\(Int(r.displayHeartRate.rounded()))").font(.rajdhani(48)).monospacedDigit().foregroundStyle(palette.readout)
                        Text("BPM").font(.chakra(11)).foregroundStyle(palette.readoutSecondary)
                        Spacer()
                        Image(systemName: r.starred ? "star.fill" : "star").foregroundStyle(r.starred ? palette.led : palette.readoutSecondary)
                    }
                    Rectangle().fill(palette.line).frame(height: 1)
                    DataRow(title: r.date.formatted(date: .abbreviated, time: .shortened),
                            value: r.bpHidden(prefs) == nil ? r.displayBP.text : "BP hidden")
                    // The mark a reading carries instead of being thrown away.
                    if let note = store.noteText(for: r) {
                        Text(note).font(.chakra(11, .medium)).foregroundStyle(palette.led)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(PressStyle())
            .accessibilityLabel("Heart rate \(Int(r.displayHeartRate.rounded())), \(r.bpHidden(prefs) == nil ? "blood pressure estimate \(r.displayBP.text)" : "blood pressure hidden"), \(r.starred ? "starred" : "not starred")\(store.noteText(for: r).map { ", \($0)" } ?? "")")
            .accessibilityHint("Toggles the star")

            if let trace = r.trace, trace.count > 3 {
                Button {
                    DialClick.shared.play()
                    openGraph = r
                } label: {
                    TraceBand(trace: trace)
                }
                .buttonStyle(PressStyle())
                .accessibilityLabel("Heart rate graph for \(r.date.formatted(date: .abbreviated, time: .shortened))")
                .accessibilityHint("Opens the graph full screen")
            }
        }
        .padding(18).frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surface, in: CutCornerShape())
        .overlay(CutCornerShape().stroke(palette.edge, lineWidth: 1))
    }
}
