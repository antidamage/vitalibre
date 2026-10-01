import SwiftUI

/// One donation burst. Tier is the dollar amount: 1, 10 or 50.
struct ConfettiBurst: Identifiable {
    let id = UUID()
    let origin: CGPoint
    let tier: Int
    let start: Date

    /// count, seconds, gravity (points/s^2), top speed (points/s), sparks
    var spec: (count: Int, life: Double, gravity: Double, speed: Double, sparks: Int) {
        switch tier {
        case 50: return (520, 2.6, 2000, 1150, 140)
        case 10: return (220, 1.6, 1600, 850, 0)
        default: return (60, 0.9, 1200, 560, 0)
        }
    }
}

private struct Particle {
    var vx: Double, vy: Double
    var size: Double, spin: Double, colour: Int
    var life: Double
}

private struct SeededGenerator: RandomNumberGenerator {
    var state: UInt64
    mutating func next() -> UInt64 {
        state = state &* 6364136223846793005 &+ 1442695040888963407
        return state
    }
}

/// Glowing confetti with gravity, drawn in one Canvas from closed-form motion
/// (no state per particle per frame): p = origin + v0 (1 - e^-kt)/k + g t^2 / 2.
struct ConfettiLayer: View {
    @Environment(\.palette) private var palette
    let bursts: [ConfettiBurst]

    private static let drag = 1.6

    var body: some View {
        TimelineView(.animation) { timeline in
            Canvas { ctx, _ in
                for burst in bursts { draw(burst, at: timeline.date, in: &ctx) }
            }
        }
        .allowsHitTesting(false)
    }

    private func particles(for burst: ConfettiBurst) -> (body: [Particle], sparks: [Particle]) {
        var rng = SeededGenerator(state: UInt64(bitPattern: Int64(burst.id.hashValue)) | 1)
        let s = burst.spec
        func make(_ n: Int, speedScale: Double, spread: Double, life: Double) -> [Particle] {
            (0..<n).map { _ in
                let angle = Double.random(in: -spread...spread, using: &rng) - .pi / 2  // biased upward
                let speed = s.speed * Double.random(in: 0.25...1.0, using: &rng) * speedScale
                return Particle(vx: cos(angle) * speed, vy: sin(angle) * speed,
                                size: Double.random(in: 3...7, using: &rng), spin: Double.random(in: -12...12, using: &rng),
                                colour: Int.random(in: 0..<4, using: &rng), life: life * Double.random(in: 0.7...1.0, using: &rng))
            }
        }
        let spread = burst.tier == 1 ? 0.9 : (burst.tier == 10 ? 1.7 : .pi)
        return (make(s.count, speedScale: 1, spread: spread, life: s.life),
                make(s.sparks, speedScale: 1.15, spread: .pi, life: s.life * 0.8))
    }

    private var bodyColours: [Color] {
        [palette.led, palette.gridInk, palette.isLight ? Color(white: 0.35) : .white, palette.ringInk[1].mixed(with: .white, 0.5)]
    }

    private func draw(_ burst: ConfettiBurst, at date: Date, in ctx: inout GraphicsContext) {
        let t = date.timeIntervalSince(burst.start)
        let s = burst.spec
        guard t >= 0, t < s.life + 0.05 else { return }
        let (bodyParticles, sparks) = particles(for: burst)
        let k = Self.drag

        func position(_ p: Particle, _ t: Double) -> CGPoint {
            let f = (1 - exp(-k * t)) / k
            return CGPoint(x: burst.origin.x + p.vx * f, y: burst.origin.y + p.vy * f + 0.5 * s.gravity * t * t)
        }

        ctx.drawLayer { l in
            l.blendMode = palette.isLight ? .normal : .plusLighter
            for p in bodyParticles where t < p.life {
                let fade = 1 - pow(t / p.life, 2)
                let pt = position(p, t)
                var chip = Path(roundedRect: CGRect(x: -p.size / 2, y: -p.size / 4, width: p.size, height: p.size / 2), cornerRadius: 1)
                chip = chip.applying(CGAffineTransform(rotationAngle: p.spin * t)).applying(CGAffineTransform(translationX: pt.x, y: pt.y))
                l.fill(chip, with: .color(bodyColours[p.colour].opacity(fade)))
            }
        }
        ctx.drawLayer { l in
            l.addFilter(.blur(radius: 5))
            l.blendMode = palette.isLight ? .normal : .plusLighter
            for p in bodyParticles.prefix(60) where t < p.life {
                let fade = 1 - pow(t / p.life, 2)
                l.fill(Path(ellipseIn: CGRect(x: position(p, t).x - 5, y: position(p, t).y - 5, width: 10, height: 10)),
                       with: .color(bodyColours[p.colour].opacity(0.45 * fade)))
            }
        }
        // Glowing sparks: short streaks in the highlight colour, tier 3 only.
        guard !sparks.isEmpty else { return }
        ctx.drawLayer { l in
            l.blendMode = palette.isLight ? .normal : .plusLighter
            for p in sparks where t < p.life {
                let fade = 1 - pow(t / p.life, 1.5)
                let head = position(p, t), tail = position(p, max(0, t - 0.045))
                var streak = Path(); streak.move(to: tail); streak.addLine(to: head)
                l.drawLayer { g in
                    g.addFilter(.blur(radius: 4))
                    g.stroke(streak, with: .color(palette.spark.opacity(fade)), style: StrokeStyle(lineWidth: 5, lineCap: .round))
                }
                l.stroke(streak, with: .color(palette.spark.mixed(with: .white, 0.5).opacity(fade)), style: StrokeStyle(lineWidth: 1.6, lineCap: .round))
            }
        }
    }
}
