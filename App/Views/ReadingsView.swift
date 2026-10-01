import SwiftUI

struct ReadingsView: View {
    @EnvironmentObject private var store: ReadingStore
    @Environment(\.palette) private var palette
    @State private var starredOnly = false

    private var shown: [Reading] { starredOnly ? store.readings.filter(\.starred) : store.readings }

    var body: some View {
        VStack(spacing: 14) {
            HStack(spacing: 8) {
                Button("All") { DialClick.shared.play(); starredOnly = false }.buttonStyle(ConsoleStyle(selected: !starredOnly))
                Button("⭐ Starred") { DialClick.shared.play(); starredOnly = true }.buttonStyle(ConsoleStyle(selected: starredOnly))
                Spacer()
                ShareLink(item: store.exportText()) { Image(systemName: "square.and.arrow.up") }
                    .buttonStyle(ConsoleStyle())
                    .disabled(store.readings.isEmpty)
                    .accessibilityLabel("Export all readings as text")
            }
            .padding(.horizontal, 22).padding(.top, 6)
            if shown.isEmpty {
                Spacer()
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
    }

    /// Tap a reading to star it; swipe to delete.
    private func row(_ r: Reading) -> some View {
        Button {
            store.toggleStar(r.id)
        } label: {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .firstTextBaseline) {
                    Text("\(Int(r.heartRate.rounded()))").font(.rajdhani(48)).monospacedDigit().foregroundStyle(palette.readout)
                    Text("BPM").font(.chakra(11)).foregroundStyle(palette.readoutSecondary)
                    Spacer()
                    Image(systemName: r.starred ? "star.fill" : "star").foregroundStyle(r.starred ? palette.led : palette.readoutSecondary)
                }
                Rectangle().fill(palette.line).frame(height: 1)
                DataRow(title: r.date.formatted(date: .abbreviated, time: .shortened), value: r.bp.text)
            }
            .padding(18).frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.surface, in: CutCornerShape())
            .overlay(CutCornerShape().stroke(palette.edge, lineWidth: 1))
        }
        .buttonStyle(PressStyle())
        .accessibilityLabel("Heart rate \(Int(r.heartRate.rounded())), blood pressure estimate \(r.bp.text), \(r.starred ? "starred" : "not starred")")
        .accessibilityHint("Toggles the star")
    }
}
