import Foundation

/// A synthetic fingertip for the simulator, which has no camera. Only offered
/// on simulator builds (see Measurer), so it can never be mistaken for a
/// reading on a device.
final class SimulatedSource {
    var onSample: ((PPGSample) -> Void)?
    private var timer: Timer?
    private var frame = 0
    static let bpm = 72.0

    func start() {
        frame = 0
        timer = Timer.scheduledTimer(withTimeInterval: 1.0 / 30, repeats: true) { [weak self] _ in
            guard let self else { return }
            let t = Double(self.frame) / 30
            self.frame += 1
            let phase = (t * Self.bpm / 60).truncatingRemainder(dividingBy: 1)
            let pulse = exp(-pow((phase - 0.18) / 0.07, 2)) + 0.35 * exp(-pow((phase - 0.45) / 0.09, 2))
            let noise = Double.random(in: -0.08...0.08)
            self.onSample?(PPGSample(t: t, r: 210, g: 60 - 1.2 * pulse + noise, b: 20, saturated: 0.1))
        }
    }

    func stop() { timer?.invalidate(); timer = nil; onSample = nil }
}
