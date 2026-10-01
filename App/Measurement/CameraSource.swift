import AVFoundation
import SwiftUI
import UIKit

enum CameraError: LocalizedError {
    case denied, noCamera, noTorch, failed(String)

    var errorDescription: String? {
        switch self {
        case .denied: return "Camera access is off. Allow it in Settings > VitaLibre to take a reading."
        case .noCamera: return "No rear camera was found."
        case .noTorch: return "This device has no flash, so a reading can't be taken."
        case .failed(let m): return "The camera could not start: \(m)"
        }
    }
}

/// Rear camera with the torch on, reducing each frame to channel means over a
/// central ROI. Exposure, white balance and focus lock once a fingertip has
/// covered the lens for a second, and release when it is lifted.
final class CameraSource: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    let session = AVCaptureSession()
    /// Called on the main queue for every frame.
    var onSample: ((PPGSample) -> Void)?

    private let queue = DispatchQueue(label: "nz.skull.vitalibre.camera")
    private var device: AVCaptureDevice?
    private var configured = false
    private var t0: CMTime?
    private var locked = false
    private var coverStart: Double?
    /// When the lock was last released, so the light is given a second to settle before it is re-taken.
    private var unlockedAt = -10.0
    private var lastCoveredT = -10.0
    private var wantTorch = false
    private var lastTorchCheck = -10.0
    /// The flash decision, from the samples alone. See FlashPolicy.
    private var policy = FlashPolicy()
    /// The flash state the last reading settled on; a scan starts from it. Set by the Measurer before start.
    var remembered: Bool?
    /// True/false once the flash usage is settled, nil while it is still being decided. Written on the camera
    /// queue while a frame is handled and read from the main queue, so access is guarded here (Android marks the
    /// same two fields `@Volatile`).
    private let stateLock = NSLock()
    private var stateFlashUsed: Bool?
    private var stateDecisionPending = false

    var flashUsed: Bool? {
        stateLock.lock(); defer { stateLock.unlock() }
        return stateFlashUsed
    }

    /// The flash decision is still running, so the reading in hand is not final.
    var decisionPending: Bool {
        stateLock.lock(); defer { stateLock.unlock() }
        return stateDecisionPending
    }

    private func publish(_ flash: Bool?, _ pending: Bool) {
        stateLock.lock()
        stateFlashUsed = flash
        stateDecisionPending = pending
        stateLock.unlock()
    }

    static func authorise() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return true
        case .notDetermined: return await AVCaptureDevice.requestAccess(for: .video)
        default: return false
        }
    }

    func start() async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            queue.async {
                do {
                    try self.configureIfNeeded()
                    self.t0 = nil; self.lastTorchCheck = -10; self.locked = false; self.coverStart = nil; self.lastCoveredT = -10; self.unlockedAt = -10
                    self.session.startRunning()
                    // With no memory the flash starts off and is switched on only if the signal turns out too
                    // flat (see FlashPolicy). A remembered state is used straight away and not re-tested.
                    self.policy.start(remembered: self.remembered)
                    self.wantTorch = self.policy.torchWanted
                    self.publish(self.policy.flashUsed, self.policy.decisionPending)
                    try self.setTorch(on: self.wantTorch)
                    cont.resume()
                } catch { cont.resume(throwing: error) }
            }
        }
    }

    func stop() {
        onSample = nil
        queue.async {
            self.wantTorch = false
            try? self.setTorch(on: false)
            self.policy.stop()
            self.publish(nil, false)
            self.setLock(false)
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    private func configureIfNeeded() throws {
        guard !configured else { return }
        let type: AVCaptureDevice.DeviceType = DeviceInfo.cameraType == "ultraWide" ? .builtInUltraWideCamera : .builtInWideAngleCamera
        guard let dev = AVCaptureDevice.default(type, for: .video, position: .back)
                ?? AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else { throw CameraError.noCamera }
        guard dev.hasTorch else { throw CameraError.noTorch }
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = .vga640x480
        let input = try AVCaptureDeviceInput(device: dev)
        guard session.canAddInput(input) else { throw CameraError.failed("input rejected") }
        session.addInput(input)
        let out = AVCaptureVideoDataOutput()
        out.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        out.alwaysDiscardsLateVideoFrames = true
        out.setSampleBufferDelegate(self, queue: queue)
        guard session.canAddOutput(out) else { throw CameraError.failed("output rejected") }
        session.addOutput(out)
        try dev.lockForConfiguration()
        dev.activeVideoMinFrameDuration = CMTime(value: 1, timescale: 30)
        dev.activeVideoMaxFrameDuration = CMTime(value: 1, timescale: 30)
        dev.unlockForConfiguration()
        device = dev
        configured = true
    }

    private func setTorch(on: Bool) throws {
        guard let dev = device, dev.hasTorch else { return }
        try dev.lockForConfiguration()
        defer { dev.unlockForConfiguration() }
        if on {
            try dev.setTorchModeOn(level: min(0.8, AVCaptureDevice.maxAvailableTorchLevel))
            if dev.isTorchModeSupported(.on) { dev.torchMode = .on }
        } else { dev.torchMode = .off }
    }

    private func setLock(_ lock: Bool) {
        guard let dev = device, (try? dev.lockForConfiguration()) != nil else { return }
        defer { dev.unlockForConfiguration() }
        let exposure: AVCaptureDevice.ExposureMode = lock ? .locked : .continuousAutoExposure
        if dev.isExposureModeSupported(exposure) { dev.exposureMode = exposure }
        let balance: AVCaptureDevice.WhiteBalanceMode = lock ? .locked : .continuousAutoWhiteBalance
        if dev.isWhiteBalanceModeSupported(balance) { dev.whiteBalanceMode = balance }
        let focus: AVCaptureDevice.FocusMode = lock ? .locked : .continuousAutoFocus
        if dev.isFocusModeSupported(focus) { dev.focusMode = focus }
        locked = lock
    }

    /// The flash must stay lit for the whole reading. iOS can switch it off (lock changes,
    /// thermal limits), so it is checked twice a second and turned back on.
    private func holdTorch(_ t: Double) {
        guard wantTorch, t - lastTorchCheck >= 0.5, let dev = device else { return }
        lastTorchCheck = t
        if dev.torchMode != .on || !dev.isTorchActive { try? setTorch(on: true) }
    }

    private func manageLock(_ s: PPGSample) {
        holdTorch(s.t)
        if s.covered {
            lastCoveredT = s.t
            if coverStart == nil { coverStart = s.t }
            if !locked, let c = coverStart, s.t - c > 1.0, s.t - unlockedAt > 1.0 { setLock(true) }
        } else if s.t - lastCoveredT > 0.5 {
            coverStart = nil
            if locked { setLock(false) }
            unlockedAt = s.t
        }
    }

    /// Applies the flash policy's decision for this sample.
    private func apply(_ action: FlashPolicy.Action, at t: Double) {
        if case .setTorch(let on) = action {
            wantTorch = on
            try? setTorch(on: on)
            // The lighting is changing, so the exposure lock is released and the light given a second to settle
            // before exposure, white balance and focus are frozen again: a trial measured through the flash-off
            // scene's settings would say nothing about the trial.
            setLock(false)
            unlockedAt = t
        }
        publish(policy.flashUsed, policy.decisionPending)
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let pixels = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let stamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        if t0 == nil { t0 = stamp }
        let t = CMTimeGetSeconds(CMTimeSubtract(stamp, t0!))

        CVPixelBufferLockBaseAddress(pixels, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixels, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pixels) else { return }
        let w = CVPixelBufferGetWidth(pixels), h = CVPixelBufferGetHeight(pixels)
        let stride = CVPixelBufferGetBytesPerRow(pixels)
        let side = Int(Double(min(w, h)) * 0.4)
        let x0 = (w - side) / 2, y0 = (h - side) / 2
        let bytes = base.assumingMemoryBound(to: UInt8.self)
        var sumR = 0, sumG = 0, sumB = 0, hot = 0, n = 0
        for y in Swift.stride(from: y0, to: y0 + side, by: 2) {
            let row = bytes + y * stride
            for x in Swift.stride(from: x0, to: x0 + side, by: 2) {
                let p = row + x * 4
                sumB += Int(p[0]); sumG += Int(p[1]); sumR += Int(p[2])
                // The green channel carries the pulse; red clips on nearly every pixel of a covered fingertip.
                if p[1] >= 250 { hot += 1 }
                n += 1
            }
        }
        guard n > 0 else { return }
        let s = PPGSample(t: t, r: Double(sumR) / Double(n), g: Double(sumG) / Double(n),
                          b: Double(sumB) / Double(n), saturated: Double(hot) / Double(n))
        manageLock(s)
        apply(policy.regulate(s), at: t)
        DispatchQueue.main.async { [weak self] in self?.onSample?(s) }
    }
}

/// The live camera image, clipped to the orb's dome by the caller.
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.backgroundColor = .black
        v.previewLayer.session = session
        v.previewLayer.videoGravity = .resizeAspectFill
        if let c = v.previewLayer.connection, c.isVideoRotationAngleSupported(90) { c.videoRotationAngle = 90 }
        return v
    }

    func updateUIView(_ v: PreviewView, context: Context) {
        if let c = v.previewLayer.connection, c.isVideoRotationAngleSupported(90) { c.videoRotationAngle = 90 }
    }
}
