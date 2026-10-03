import CoreMotion
import Foundation

/// The phone's own movement while a scan runs: rotation rate and linear acceleration at 100 Hz, so the
/// feed-quality index can tell a shaking hand or a moving phone from a fault in the picture (4-12 Hz
/// tremor needs at least 25 Hz). CoreMotion needs no permission for this. Times are on the same clock as
/// the camera frames: seconds since the scan began, taken from the system uptime at start.
final class MotionSource {
    private let manager = CMMotionManager()
    private let queue: OperationQueue = {
        let q = OperationQueue(); q.name = "nz.skull.vitalibre.motion"; q.maxConcurrentOperationCount = 1; return q
    }()
    /// Called on the main queue for every motion sample, with `t` already on the camera's clock.
    var onSample: ((MotionSample) -> Void)?

    /// `cameraEpoch` is the system uptime at which the camera's clock read zero: the camera's first frame
    /// timestamp. nil before the first frame; samples before then are dropped.
    var cameraEpoch: (() -> Double?)?

    var isAvailable: Bool { manager.isDeviceMotionAvailable }

    func start() {
        guard manager.isDeviceMotionAvailable else { return }
        manager.deviceMotionUpdateInterval = 1.0 / 100.0
        manager.startDeviceMotionUpdates(to: queue) { [weak self] motion, _ in
            guard let self, let m = motion, let epoch = self.cameraEpoch?() else { return }
            let g = 9.80665
            let sample = MotionSample(t: m.timestamp - epoch,
                                      gx: m.rotationRate.x, gy: m.rotationRate.y, gz: m.rotationRate.z,
                                      ax: m.userAcceleration.x * g, ay: m.userAcceleration.y * g, az: m.userAcceleration.z * g)
            DispatchQueue.main.async { [weak self] in self?.onSample?(sample) }
        }
    }

    func stop() {
        manager.stopDeviceMotionUpdates()
        onSample = nil
    }
}
