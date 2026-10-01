import SwiftUI

struct ReadingsView: View {
    @EnvironmentObject private var store: ReadingStore
    @Environment(\.palette) private var palette
    @State private var starredOnly = false

    private var shown: [Reading] { starredOnly ? store.readings.filter(\.starred) : store.readings }

    var body: some View {
        VStack(spacing: 12) {
            HStack(spacing: 8) {
                filter
                ShareLink(item: store.exportText()) {
                    Image(systemName: "square.and.arrow.up").font(.system(size: 17))
                        .foregroundStyle(palette.clock).frame(width: 44, height: 44)
                }
                .disabled(store.readings.isEmpty)
                .accessibilityLabel("Export all readings as text")
            }
            .padding(.horizontal, 20).padding(.top, 6)
            if shown.isEmpty {
                Spacer()
            } else {
                List {
                    ForEach(shown) { reading in
                        row(reading)
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                            .listRowInsets(EdgeInsets(top: 5, leading: 16, bottom: 5, trailing: 16))
                    }
                    .onDelete { offsets in store.delete(offsets.map { shown[$0].id }) }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
    }

    private var filter: some View {
        HStack(spacing: 0) {
            segment("All", on: !starredOnly) { starredOnly = false }
            segment("⭐ Starred", on: starredOnly) { starredOnly = true }
        }
        .padding(3).panel(radius: 12)
    }

    private func segment(_ title: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button {
            DialClick.shared.play()
            action()
        } label: {
            Text(title).font(.chakra(13, .medium))
                .foregroundStyle(on ? palette.led : palette.clock)
                .frame(maxWidth: .infinity, minHeight: 34)
                .background(RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .fill(on ? palette.background.mixed(with: .black, palette.isLight ? 0.06 : 0.35) : .clear))
        }
        .buttonStyle(.plain)
    }

    private func row(_ r: Reading) -> some View {
        Button {
            store.toggleStar(r.id)
        } label: {
            HStack(spacing: 14) {
                Text(r.starred ? "⭐" : "☆").font(.system(size: 20))
                    .foregroundStyle(palette.clock).frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text(r.date.formatted(date: .abbreviated, time: .shortened))
                        .font(.rajdhani(14)).foregroundStyle(palette.readoutSecondary)
                    Text(r.bp.text).font(.chakra(14, .medium)).foregroundStyle(palette.readout)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 0) {
                    Text("\(Int(r.heartRate.rounded()))").font(.chakra(30, .light)).foregroundStyle(palette.readout)
                    Text("BPM").font(.rajdhani(11, semibold: true))
                        .tracking(1).foregroundStyle(palette.readoutSecondary)
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 10).panel(radius: 14)
        }
        .buttonStyle(PressStyle())
        .accessibilityLabel("Heart rate \(Int(r.heartRate.rounded())), blood pressure estimate \(r.bp.text), \(r.starred ? "starred" : "not starred")")
    }
}
