import SwiftUI
import UIKit

struct MeasureView: View {
    @EnvironmentObject private var measurer: Measurer
    @EnvironmentObject private var readings: ReadingStore
    @EnvironmentObject private var prefs: Preferences
    @Environment(\.palette) private var palette
    @State private var orbFrame: CGRect = .zero
    @State private var sharedImage: SharedImage?
    @State private var idleOrigin = Date()
    @State private var calibrating = false
    @State private var showGuide = false
    /// Whether the fold is open: the line's own state, and what a pull changes.
    @State private var foldOpen = false
    @State private var fold = FoldCommands()

    private var isSimulator: Bool {
        #if targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
    }

    var body: some View {
        // The whole screen is one page behind the fold's line: pulling it up slides everything
        // above the line away and brings the day's readings in from the foot. The page is hosted
        // in its own scroller, so the app's environment has to be handed to it explicitly.
        FoldPage(isOpen: $foldOpen, commands: fold, hasContent: !readings.todaysReadings.isEmpty) { viewport in
            page(viewport)
                .environmentObject(measurer)
                .environmentObject(readings)
                .environmentObject(prefs)
                .environment(\.palette, palette)
        }
        .onChange(of: readings.todaysReadings.isEmpty) { _, empty in
            // Nothing left past the line: the fold has nothing to guard, so it shuts.
            if empty { fold.close() }
        }
        .sheet(isPresented: $showGuide) {
            ReadingGuideSheet().environment(\.palette, palette)
        }
        .sheet(isPresented: $calibrating) {
            if case .result(let r) = measurer.phase {
                CalibrateSheet(result: r) { point in
                    prefs.calibration.add(point)
                    measurer.recalibrate(prefs.calibration, age: prefs.age > 0 ? prefs.age : nil, sex: prefs.sex, usual: prefs.usual)
                }
                .environment(\.palette, palette)
                .presentationDetents([.medium])
            }
        }
    }

    // MARK: The page, above and past the line

    @ViewBuilder private func page(_ viewport: CGSize) -> some View {
        VStack(spacing: 0) {
            // Everything above the line: the orb and what it is saying, centred in the room the
            // line leaves. It is the part that slides away as the fold opens.
            VStack(spacing: 0) {
                Spacer(minLength: 8)
                OrbView(input: orb, origin: measurer.phaseIsLive ? measurer.sweepOrigin : idleOrigin) {
                    CameraPreview(session: measurer.camera.session)
                }
                // Hosted in its own tree, this view cannot send a preference out to MeasureView:
                // the orb's frame comes back as a callback, which `shareOrb` needs.
                .background(GeometryReader { g in
                    let frame = g.frame(in: .global)
                    Color.clear
                        .onAppear { orbFrame = frame }
                        .onChange(of: frame) { _, new in orbFrame = new }
                })
                .frame(maxWidth: 430)
                .padding(.horizontal, 8)
                .contentShape(Circle())
                .onTapGesture(perform: tapOrb)
                statusArea.padding(.top, 18).frame(minHeight: 70, alignment: .top)
                Spacer(minLength: 8)
            }
            .frame(width: viewport.width, height: max(0, viewport.height - FoldMetrics.rest))
            .clipped()

            FoldDivider(label: "Today's readings", count: readings.todaysReadings.count,
                        isOpen: foldOpen, canOpen: readings.todaysReadings.count > 0) {
                fold.toggle()
            }

            // With nothing taken today there is nothing past the line, so the page is exactly the
            // viewport and cannot be pulled at all: the fold does not exist until there is. The
            // scroller is told the same thing, so no pull can open a band over an empty log.
            if !readings.todaysReadings.isEmpty { todayPanel }
        }
        .frame(width: viewport.width)
    }

    // MARK: Orb state

    private var orb: OrbInput {
        switch measurer.phase {
        case .idle, .starting:
            return OrbInput(centre: "Start")
        case .scanning:
            // The running estimate is shown only when a result of this kind would be (a calibration is current).
            var input = OrbInput(centre: measurer.liveHeartRate.map { "\(Int($0.rounded()))" } ?? "",
                                 sub: BPPresentation.eligible(prefs) ? measurer.liveBP?.text : nil,
                                 caption: measurer.liveHeartRate == nil ? nil : "bpm")
            input.lastBeat = measurer.lastBeat
            input.showsCamera = measurer.usingCamera
            input.scanning = true
            input.progress = measurer.progress
            input.trace = measurer.trace
            input.traceEnd = measurer.traceEnd
            return input
        case .analysing:
            return OrbInput(centre: "…")
        case .result(let r):
            var input = OrbInput(centre: "\(Int(r.heartRate.rounded()))", sub: hidden(r) == nil ? r.bp.text : nil, caption: "bpm")
            input.keptTrace = measurer.keptTrace; input.keptTraceEnd = measurer.keptTraceEnd
            input.showsSweep = false
            return input
        case .failed:
            var input = OrbInput(centre: "Retry")
            input.keptTrace = measurer.keptTrace; input.keptTraceEnd = measurer.keptTraceEnd
            return input
        }
    }

    private func tapOrb() {
        switch measurer.phase {
        case .idle, .result, .failed:
            // The first scan opens "Before you measure"; the user starts when they are ready.
            if !prefs.guideSeen { prefs.guideSeen = true; showGuide = true; return }
            Sounds.play(Sounds.start)
            // The flash the reading settles on becomes the state the next reading starts from.
            measurer.onFlashLearned = { used in prefs.workingFlash = used }
            measurer.start(simulate: isSimulator && prefs.simulatedPulse,
                           age: prefs.age > 0 ? prefs.age : nil, sex: prefs.sex, usual: prefs.usual, calibration: prefs.calibration,
                           flash: prefs.workingFlash)
        case .starting, .scanning:
            DialClick.shared.play()
            measurer.cancel()
        case .analysing:
            break
        }
    }

    // MARK: Today's readings, past the line

    /// The rows flow on the page itself: pulling the page up is what brings them in, and the page
    /// scrolls through them like anything else.
    private var todayPanel: some View {
        VStack(spacing: 0) {
            Text("Taken today. Tap a reading to keep it in Readings, or to drop it again.")
                .font(.rajdhani(13)).foregroundStyle(palette.readoutSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16).padding(.top, 10).padding(.bottom, 8)
            ForEach(readings.todaysReadings) { r in todayRow(r) }
        }
        .background(palette.surface)
    }

    /// Tap a reading to keep it, or to drop it again.
    private func todayRow(_ r: Reading) -> some View {
        Button {
            DialClick.shared.play()
            readings.toggleSaved(r.id)
        } label: {
            HStack(alignment: .center, spacing: 8) {
                Text("\(Int(r.displayHeartRate.rounded()))").font(.rajdhani(30)).monospacedDigit().foregroundStyle(palette.readout)
                Text("BPM").font(.chakra(10)).foregroundStyle(palette.readoutSecondary)
                Spacer(minLength: 8)
                VStack(alignment: .trailing, spacing: 1) {
                    Text(r.bpHidden(prefs) == nil ? r.displayBP.text : "BP hidden").font(.rajdhani(15)).foregroundStyle(palette.readout)
                    Text(r.date.formatted(date: .omitted, time: .shortened))
                        .font(.rajdhani(12)).foregroundStyle(palette.readoutSecondary)
                }
                // Not a star: the star is the reading's own mark in Readings, and one glyph
                // cannot mean two things. A bookmark is what keeping it looks like.
                Image(systemName: r.isSaved ? "bookmark.fill" : "bookmark")
                    .font(.system(size: 15))
                    .foregroundStyle(r.isSaved ? palette.led : palette.readoutSecondary)
                    .padding(.leading, 4)
            }
            .padding(.horizontal, 16).padding(.vertical, 8)
            .overlay(alignment: .bottom) { Rectangle().fill(palette.line).frame(height: 1) }
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
        .accessibilityLabel("\(Int(r.displayHeartRate.rounded())) bpm, \(r.bpHidden(prefs) == nil ? r.displayBP.text : "blood pressure hidden"), \(r.date.formatted(date: .omitted, time: .shortened))\(r.isSaved ? ", kept" : "")")
    }

    // MARK: Under the orb

    @ViewBuilder private var statusArea: some View {
        VStack(spacing: 12) {
            switch measurer.phase {
            case .scanning:
                Text(measurer.guidance.text).font(.chakra(15, .medium)).foregroundStyle(palette.readout)
            case .idle:
                Button {
                    DialClick.shared.play()
                    showGuide = true
                } label: {
                    Text("Before you measure").font(.chakra(13, .medium)).foregroundStyle(palette.led).underline()
                }
                .accessibilityHint("How to take a good reading")
            case .result(let r):
                actions(r)
                bpLines(r)
            case .failed(let message):
                Text(message)
                    .font(.rajdhani(17, semibold: true)).foregroundStyle(palette.led)
                    .multilineTextAlignment(.center).padding(.horizontal, 28)
            default:
                EmptyView()
            }
        }
        .animation(.easeOut(duration: 0.2), value: measurer.phase)
        .sheet(item: $sharedImage) { item in
            ShareSheet(items: [item.image]).presentationDetents([.medium, .large])
        }
    }

    private func actions(_ r: ScanResult) -> some View {
        let kept = readings.reading(forScan: measurer.scanID)?.isSaved ?? false
        return HStack(spacing: 12) {
            RingButton(title: kept ? "Saved" : "Save", disabled: kept) {
                DialClick.shared.play()
                // The scan is already in today's log by the time this is on screen
                // (RootView files it); this is what keeps it in Readings. Filing
                // again is harmless: it lands on the one reading this scan has.
                readings.setSaved(readings.file(r, scanID: measurer.scanID).id, true)
            }
            RingButton(title: "Calibrate") {
                DialClick.shared.play()
                calibrating = true
            }
            ShareLink(item: shareText(r)) {
                Image(systemName: "square.and.arrow.up").font(.system(size: 17))
                    .foregroundStyle(palette.clock).frame(width: 40, height: 44)
            }
            Button(action: shareOrb) {
                Image(systemName: "photo").font(.system(size: 17))
                    .foregroundStyle(palette.clock).frame(width: 40, height: 44)
            }
            .accessibilityLabel("Share image of the reading")
        }
    }

    private func hidden(_ r: ScanResult) -> BPHidden? {
        BPDisplay.hidden(rhythm: r.rhythm, calibration: prefs.calibration, platform: .ios, mode: BPPresentation.mode,
                         validDays: BPPresentation.validDays)
    }

    /// Under the buttons: the figure's margin and caveat, or why there is no figure, and the reading's note.
    private func bpLines(_ r: ScanResult) -> some View {
        let note = readings.reading(forScan: measurer.scanID).flatMap { readings.noteText(for: $0) }
        return VStack(spacing: 4) {
            if let h = hidden(r) {
                Text(h.line)
            } else {
                Text(BPPresentation.margin(prefs, model: measurer.bpModel))
                Text(Publisher.policy.bpCaveat)
            }
            if let note { Text(note).font(.chakra(12, .medium)).foregroundStyle(palette.led) }
        }
        .font(.rajdhani(12)).foregroundStyle(palette.readoutSecondary)
        .multilineTextAlignment(.center).padding(.horizontal, 28)
    }

    private func shareText(_ r: ScanResult) -> String {
        let bp = hidden(r) == nil ? " Blood pressure estimate \(r.bp.text) mmHg." : ""
        return "Heart rate \(Int(r.heartRate.rounded())) bpm.\(bp) "
            + "Estimates only, not a medical device. VitaLibre, \(Date().formatted(date: .abbreviated, time: .shortened))."
    }

    /// A cropped capture of what is on screen inside the orb's frame: no buttons, nothing else.
    private func orbSnapshot() -> UIImage? {
        guard orbFrame.width > 10, let window = keyWindow else { return nil }
        let renderer = UIGraphicsImageRenderer(bounds: CGRect(origin: .zero, size: orbFrame.size))
        return renderer.image { _ in
            window.drawHierarchy(in: CGRect(x: -orbFrame.minX, y: -orbFrame.minY,
                                            width: window.bounds.width, height: window.bounds.height),
                                 afterScreenUpdates: true)
        }
    }

    private var keyWindow: UIWindow? {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows).first(where: \.isKeyWindow)
    }

    private func shareOrb() {
        guard let image = orbSnapshot() else { return }
        sharedImage = SharedImage(image: image)
    }
}

/// Pairs a cuff reading with the scan on screen.
struct CalibrateSheet: View {
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    let result: ScanResult
    let onSave: (CalibrationPoint) -> Void
    @State private var systolic = 120
    @State private var diastolic = 80
    /// Which cuff the numbers came from, remembered between calibrations so it is typed once.
    @AppStorage("lastCuffName") private var cuffName = ""

    var body: some View {
        ZStack {
            palette.backgroundGradient.ignoresSafeArea()
            VStack(spacing: 20) {
                HStack {
                    Text("CALIBRATE").font(.chakra(15, .medium)).tracking(3).foregroundStyle(palette.clock)
                    Spacer()
                    Button("Cancel") { dismiss() }.font(.chakra(14, .medium)).foregroundStyle(palette.clock)
                }
                HStack(spacing: 16) {
                    picker("Systolic", $systolic, 70...250)
                    picker("Diastolic", $diastolic, 40...150)
                }
                TextField("Which cuff? (optional)", text: $cuffName)
                    .font(.rajdhani(16)).foregroundStyle(palette.readout)
                    .padding(12).panel(radius: 12)
                RingButton(title: "Save", disabled: !CalibrationPoint.isPlausible(systolic: Double(systolic), diastolic: Double(diastolic))) {
                    onSave(CalibrationPoint(rawSystolic: result.rawSystolic, rawDiastolic: result.rawDiastolic,
                                            cuffSystolic: Double(systolic), cuffDiastolic: Double(diastolic), date: Date(),
                                            device: DeviceInfo.identifier,
                                            baseSystolic: result.baseSystolic, baseDiastolic: result.baseDiastolic,
                                            cuffName: cuffName.trimmingCharacters(in: .whitespaces).isEmpty ? nil : cuffName))
                    dismiss()
                }
                Spacer()
            }
            .padding(20)
        }
    }

    private func picker(_ title: String, _ value: Binding<Int>, _ range: ClosedRange<Int>) -> some View {
        VStack(spacing: 4) {
            Text(title.uppercased()).font(.rajdhani(12, semibold: true)).tracking(1.5).foregroundStyle(palette.readoutSecondary)
            Picker(title, selection: value) { ForEach(range, id: \.self) { Text("\($0)").tag($0) } }
                .pickerStyle(.wheel).frame(height: 130)
        }
        .frame(maxWidth: .infinity).padding(10).panel(radius: 14)
    }
}

struct SharedImage: Identifiable {
    let id = UUID()
    let image: UIImage
}

/// The system share sheet (includes Save Image).
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
