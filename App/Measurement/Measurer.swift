import Foundation
import SwiftUI

/// Drives one scan: source -> ScanSession -> result. All state the Measure
/// screen shows comes from here.
@MainActor
final class Measurer: ObservableObject {
    enum Phase: Equatable {
        case idle, starting, scanning, analysing
        case result(ScanResult)
        case failed(String)
    }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var progress = 0.0
    @Published private(set) var guidance: Guidance = .coverLens
    @Published private(set) var trace: [Double] = []
    /// Seconds since the sweep's zero (the start of the covered run) at the trace's last sample.
    @Published private(set) var traceEnd = 0.0
    @Published private(set) var usingCamera = false
    @Published private(set) var liveHeartRate: Double?
    @Published private(set) var liveBP: BPRange?
    @Published private(set) var lastBeat: Double?
    /// The finished reading's graph, kept on screen until the next reading starts.
    @Published private(set) var keptTrace: [Double] = []
    @Published private(set) var keptTraceEnd = 0.0
    @Published private(set) var scanStart = Date()
    /// The identity of the scan in progress. A filed reading is keyed by it, so
    /// one scan can only ever land in the log once.
    @Published private(set) var scanID = UUID()
    /// Zero of the sweep: the moment the finger covered the lens, so one revolution is one reading.
    @Published private(set) var sweepOrigin = Date()

    let camera = CameraSource()
    private let motion = MotionSource()
    private var simulated: SimulatedSource?
    private var session = ScanSession()
    private var lastUIUpdate = -1.0
    private let model: BPModel = Measurer.loadModel()
    private var age: Int?
    private var sex: Sex = .unspecified
    private var calibration = BPCalibration()
    /// Called when a reading settles the flash state, so it can be remembered for the next scan. nil clears it.
    var onFlashLearned: ((Bool?) -> Void)?
    private var usual: UsualBP?

    static func loadModel() -> BPModel {
        if let url = BundleFinder.url("bp-model", "json"), let data = try? Data(contentsOf: url),
           let model = try? BPModel.load(from: data) { return model }
        return .prior1
    }

    var modelVersion: String { model.version }
    var bpModel: BPModel { model }
    /// The sweep is anchored to the scan's own clock only while a scan is running.
    var phaseIsLive: Bool { phase == .scanning }
    var isBusy: Bool { phase == .starting || phase == .scanning || phase == .analysing }

    func start(simulate: Bool, age: Int?, sex: Sex, usual: UsualBP?, calibration: BPCalibration, flash: Bool?) {
        guard !isBusy else { return }
        scanID = UUID()
        self.age = age; self.sex = sex; self.usual = usual; self.calibration = calibration
        session = ScanSession()
        progress = 0; trace = []; traceEnd = 0; lastUIUpdate = -1
        liveHeartRate = nil; liveBP = nil; lastBeat = nil; keptTrace = []; keptTraceEnd = 0
        guidance = .coverLens
        scanStart = Date(); sweepOrigin = Date()
        phase = .starting
        usingCamera = !simulate

        if simulate {
            let source = SimulatedSource()
            source.onSample = { [weak self] s in Task { @MainActor in self?.receive(s) } }
            simulated = source
            source.start()
            phase = .scanning
            Haptics.start()
            return
        }
        Task {
            guard await CameraSource.authorise() else { fail(CameraError.denied.errorDescription ?? "Camera access is off."); return }
            camera.remembered = flash
            camera.onSample = { [weak self] s in Task { @MainActor in self?.receive(s) } }
            do {
                try await camera.start()
                // The phone's own movement, on the camera's clock, so a shaking hand can be told from a bad picture.
                motion.cameraEpoch = { [weak self] in self?.camera.clockEpoch }
                motion.onSample = { [weak self] m in Task { @MainActor in self?.session.addMotion(m) } }
                motion.start()
                scanStart = Date()
                phase = .scanning
                Haptics.start()
            } catch {
                fail((error as? CameraError)?.errorDescription ?? error.localizedDescription)
            }
        }
    }

    func cancel() {
        stopSources()
        phase = .idle
    }

    /// Re-derives the shown blood pressure after a new calibration point.
    func recalibrate(_ calibration: BPCalibration, age: Int?, sex: Sex, usual: UsualBP?) {
        guard case .result(var r) = phase else { return }
        r.bp = BPEstimator.estimate(r.features, model: model, age: age, sex: sex, usual: usual, calibration: calibration)
        phase = .result(r)
    }

    func reset() { if !isBusy { phase = .idle } }

    private func fail(_ message: String) {
        stopSources()
        phase = .failed(message)
        Haptics.fail()
    }

    private func stopSources() {
        motion.stop()
        camera.stop()
        simulated?.stop(); simulated = nil
    }

    private func receive(_ s: PPGSample) {
        guard phase == .scanning else { return }
        session.add(s)
        if s.t - lastUIUpdate >= 0.1 {
            lastUIUpdate = s.t
            let run = session.currentRunSeconds
            progress = min(1, run / ScanSession.targetSeconds)
            sweepOrigin = Date(timeIntervalSinceNow: -run)
            guidance = session.guidance
            if let live = session.live(model: model, age: age, sex: sex, usual: usual, calibration: calibration) {
                trace = live.trace; traceEnd = live.traceEnd
                liveHeartRate = live.heartRate; liveBP = live.bp
                // A beat time can shift slightly as the window is refiltered, so only a clearly later one counts.
                if let beat = live.lastBeat, beat > (lastBeat ?? -1) + 0.3 { Haptics.beat() }
                if let beat = live.lastBeat, beat > (lastBeat ?? -1) + 0.3 || lastBeat == nil { lastBeat = beat }
            }
        }
        // The flash decision may still be running, and that decides how this reading was lit: not final until it
        // lands - but the scan's own maximum ends it either way, so a trial that can never settle cannot hold the
        // reading open (as on Android).
        if session.finished, !camera.decisionPending || session.elapsed >= ScanSession.maxSeconds { finish() }
    }

    private func finish() {
        // Only a camera scan ran the policy, so only it can say anything about the flash.
        let flashed = usingCamera ? camera.flashUsed : nil
        stopSources()
        phase = .analysing
        let snapshot = session, model = model, age = age, sex = sex, usual = usual, calibration = calibration
        Task {
            let outcome = await Task.detached(priority: .userInitiated) {
                snapshot.analyse(model: model, age: age, sex: sex, usual: usual, calibration: calibration)
            }.value
            switch outcome {
            case .success(let r):
                progress = 1; keptTrace = r.trace; keptTraceEnd = r.traceEnd
                phase = .result(r)
                Haptics.end(); Sounds.play(Sounds.done)
                // A reading that produced a result is evidence for the next one's flash state - including that the
                // policy never settled: a state this reading did not prove is cleared rather than kept.
                if usingCamera { onFlashLearned?(flashed) }
            case .failure(let f):
                phase = .failed(f.message); Haptics.fail()
                // A reading that failed says nothing about the flash, so the memory is cleared, not kept.
                if usingCamera { onFlashLearned?(nil) }
            }
        }
    }
}
