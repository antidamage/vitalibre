import AVFoundation
import UIKit

/// Finds a bundled resource wherever the build put it. Synchronised folders
/// may or may not keep their subfolders, so this searches the bundle.
enum BundleFinder {
    static func url(_ name: String, _ ext: String) -> URL? {
        if let u = Bundle.main.url(forResource: name, withExtension: ext) { return u }
        guard let files = FileManager.default.enumerator(at: Bundle.main.bundleURL, includingPropertiesForKeys: nil) else { return nil }
        for case let u as URL in files where u.lastPathComponent == "\(name).\(ext)" { return u }
        return nil
    }
}

/// The dashboard's rotary dial click with a light tap, for buttons.
final class DialClick {
    static let shared = DialClick()
    private let impact = UIImpactFeedbackGenerator(style: .light)

    func prepare() {
        try? AVAudioSession.sharedInstance().setCategory(.ambient, options: [.mixWithOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
        // A running capture session otherwise silences haptics and system sounds.
        try? AVAudioSession.sharedInstance().setAllowHapticsAndSystemSoundsDuringRecording(true)
        Sounds.prepare()
        Haptics.prepare()
        impact.prepare()
    }

    func play() {
        Sounds.play(Sounds.click)
        impact.impactOccurred()
    }
}

/// The dashboard's UX sounds, bundled as copies and keyed by their dashboard names.
/// Dark theme's selections: unlockDial `light-mechanical-click`, dialClick and lockDial
/// `soft-mechanical-click`, reminderConfirm `chime-motion-tracker`.
enum Sounds {
    static let start = "light-mechanical-click"
    static let click = "soft-mechanical-click"
    static let done = "chime-motion-tracker"
    private static var players: [String: AVAudioPlayer] = [:]

    static func prepare() {
        for name in [start, click, done] {
            guard players[name] == nil, let url = BundleFinder.url(name, "mp3") else { continue }
            let player = try? AVAudioPlayer(contentsOf: url)
            player?.volume = 0.6
            player?.prepareToPlay()
            players[name] = player
        }
    }

    /// Rewinds rather than stacking.
    static func play(_ name: String) {
        guard let player = players[name] else { return }
        player.currentTime = 0
        player.play()
    }
}

/// Subtle haptics for a scan: a light tap to begin, a softer tap on each beat, and a more
/// defined pair of taps at the end.
enum Haptics {
    private static let light = UIImpactFeedbackGenerator(style: .light)
    private static let soft = UIImpactFeedbackGenerator(style: .soft)
    private static let note = UINotificationFeedbackGenerator()

    static func prepare() { light.prepare(); soft.prepare(); note.prepare() }
    static func start() { light.impactOccurred(intensity: 0.55) }
    static func beat() { soft.impactOccurred(intensity: 0.35) }
    static func end() { note.notificationOccurred(.success) }
    static func fail() { note.notificationOccurred(.warning) }
}
