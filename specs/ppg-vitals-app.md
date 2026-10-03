# VitaLibre — native SwiftUI camera-PPG app

Produced from the Hermes plan `.hermes/plans/2026-09-27_233747-vitals-ppg-app.md`
and the UX corrections in Hermes session `c5192b72f3dd`, then re-targeted to
native Swift. Third build: `vitals-libre` (Hermes, React Native) and
`vitals-libre-codex` (Codex) exist separately and are not touched.

## Scope of this build

- **Platform: Swift / SwiftUI, iPhone first.** Chosen so the UX code can be
  shared with `nova-appletv-dashboard` (knob face, fonts, colour maths). iPad and
  Android are out of scope for now.
- Built on a Mac build host, signed with the free **personal team**
  (7-day profiles, no push, no App Groups, no IAP capability on a device build),
  installed on the test iPhone (iPhone 17 Pro Max).
- Bundle id `nz.skull.vitalibre.claude` (distinct from the Hermes build's
  `nz.skull.vitals`, so both can be on the phone).
- Deployment target iOS 17.0. No third-party dependencies.
- Not at store-publishing stage. Store submission, licence choice and privacy
  URL are deferred; the Hermes plan holds the store checklists.

## What it does

Reads heart rate and a **blood-pressure trend estimate** from a fingertip
photoplethysmogram: the rear camera looks at the fingertip with the torch on.
Readings are stored on the device only. Nothing is transmitted; the only way
data leaves is the user's own Share action. The app has no network code.

Technique (all in `Core/`, pure Swift, unit-tested):

1. **Acquisition** — per frame, mean of the red, green and blue channels over a
   centred square ROI (40% of the shorter side, at least 150 px), plus the
   saturated-pixel fraction and the ROI's standard deviation for coverage
   testing. Exposure, focus and white balance are locked once the finger
   covers the lens. Timestamps are the sample-buffer presentation times.
   The green channel is the signal; red is kept for coverage and the perfusion
   index. (The study, Frey/Menon/Elgendi 2022, and Lee et al. name green as the
   least motion-sensitive channel.)
2. **Conditioning** — resample to a uniform 30 Hz by linear interpolation,
   remove the mean, band-pass 0.5–5 Hz with a 2nd-order Butterworth high-pass
   and low-pass applied forward and backward (zero phase, so peak times are not
   shifted).
3. **Beats** — Elgendi's two-moving-average detector: `W1 = 111 ms`,
   `W2 = 667 ms`, offset `beta = 0.02`, blocks of interest where
   `MApeak > MAbeat + beta*mean(y)` and width at least `W1`, one peak per block
   (maximum of the squared, clipped signal).
4. **Heart rate** — inter-beat intervals outside 0.33–1.5 s are dropped, as are
   those more than 30% from the running median of the last 10. Rate is the
   median of the accepted intervals. At least 8 accepted beats are required.
   The window is rejected if the median interval moves more than 15% between
   its two halves.
5. **Quality** — three indices: skewness, beat-template correlation, perfusion
   index (`AC/DC`, percent). Skewness and template correlation rank; perfusion
   index only gates. A scan whose usable signal never reaches the minimum
   produces an **error, never a number**.
6. **Blood pressure** — a small linear model over a fixed, versioned feature
   vector (HR, pulse interval variability, crest time fraction, pulse
   skewness, reflection index when a second peak is detectable, plus optional
   age and sex from Settings). The weights are a JSON file
   (`bp-model.json`) so they can be replaced without a code change.
   **v1 has no trained weights.** There is no licensed training set on this
   machine, and inventing coefficients would be fabrication. v1 is a
   population prior (120/78, adjusted by age and sex when given) with
   feature adjustments capped at ±8 systolic / ±5 diastolic, and it says so:
   the label is "Experimental estimate", the display is always a **range**
   (point ± 14 systolic, ± 9 diastolic, the published calibration-free error
   of MAE 13–16 / 7–9 mmHg), and Help states that v1 is unvalidated.
   Changing the model means training in `tools/` against subject-wise splits
   and bumping `version` in the weights file.

Accuracy statements used in Help: finger heart rate about ±2 bpm at rest
against ECG; calibration-free camera BP MAE about 13–16 / 7–9 mmHg, 2–3× worse
than the ISO 81060-2 criterion (mean difference ≤5 mmHg, SD ≤8 mmHg).
Sources: the study (doi 10.1038/s41746-022-00629-2, CC BY 4.0, linked, not
bundled) and PMC5368348 / PMC10030661.

## Theme — copied from the live dashboard, applied, not raw

`GET http://nova.local/api/theme` (2026-09-30). A theme colour is a raw `rgb`
plus an `intensity`; the applied channel is `round(rgb * intensity / 100)`.
Copied into `App/Theme/Palette.swift` as literals. There is no dashboard
reference at runtime.

| Slot | Dark | Light |
|---|---|---|
| background | `#121212` (7%) | `#E8E8E8` (91%) |
| panel | `#171717` (9%) | `#FFFFFF` |
| border | `#1F1F1F` at 15% | `#FFFFFF` at 75% |
| accent | `#42322A` (raw 255,193,162 at 26%) | `#FFFDFD` |
| highlight | `#802E00` (raw 255,91,0 at 50%) | `#C4C4C4` (77%) |
| clock / title | `#919191` | `#A1A1A1` |
| LED | `#FF8340` | `#FF7D5E` |
| orb gradientOuter | `#1C1C1C` (11%) | `#FFFFFF` |
| orb alert | `#FF2F00` | `#93FFF9` |
| orb face treatment | dark face (knobTheme 2) | light face (knobTheme 0) |

Corrections against the Hermes plan's table: dark accent is `#42322A` (plan had
`#3B3532`), dark highlight `#802E00` (plan `#802C00`), the LED is applied at
100% so `#FF8340` (plan `#E78B5A`), and the light-theme border is 75% opacity.

**Ring colour** (the deep green-blue the owner asked for) is the light theme's
orb linework, `rgb(0,245,255)`, `rgb(0,255,115)`, `rgb(0,143,255)` at
23 / 30 / 33%, which applies to `#00383B`, `#004D23`, `#002F54`. It is used in
**both** themes; only the surrounding surfaces change.

Fonts: Chakra Petch (UI, numerals; OFL, bundled) and Rajdhani Medium/SemiBold
(secondary text; taken from the Apple TV app). Theme mode is Auto / Light /
Dark, **default Dark**, chosen in Settings.

## The orb (centre of Measure)

Unit space: outer rim = 1.0 of the orb radius.

| Band | Radius (unit) |
|---|---|
| dome (camera preview / readout) | 0 – 0.457 |
| unlock ring | 0.457 – 0.510 |
| **colour ring + grid** | **0.510 – 0.966** |
| lip | 0.966 – 1.000 |

The dashboard's reference colour channel is 0.152 wide in the same unit
(knob face 0.761, unlock ring 0.761–0.814, channel 0.814–0.966). The ring here
is `ringWidthRatio = 3.0` times that: 0.456. The 3× is a constant in code and a
unit test.

- Ring paint: angular gradient through the three ring colours plus a 160°
  linear shading pass and an inset shadow (the knob's ring-shade treatment).
- Grid inside the ring: a radial spoke every 5° (72) and concentric circles
  every 0.045 of the unit radius, hairline 0.5–0.75 pt, alpha ≤ 0.12, brightest
  within ±30° of the sweep. It must not read as a grid at arm's length.
- **Sweep**: one revolution per **5000 ms**, angle `= (t mod 5000)/5000 * 2π`
  from the clock, so frame rate cannot change it. Core 2 pt line in the
  theme background colour; glow is that colour lifted 82% toward white, drawn
  as a ~24° blurred arc with a 3° bright leading edge, composited with the
  overlay blend, plus a wider plus-lighter halo at low alpha.
- **Heartbeat arc**: the filtered PPG plotted around the ring, angle = time
  (same 5 s period), radius = ring middle plus amplitude × half the ring
  width (clamped to the ring). Drawn in the LED colour with a lit crest per
  detected beat. The previous revolution stays visible and fades behind the
  sweep. Rejected samples leave a gap.
- **Camera in the dome**: while a scan is running the live camera preview is the
  centre of the orb (circular clip, slightly darkened at the edge). It is shown
  only when the camera is the actual source of the scan.
- Idle: the dome shows the word **Start**. Tapping the orb starts a scan; there
  is no separate Start button. After a result, the dome shows the heart rate
  and the blood-pressure range; tapping the orb again records again.
- Tap plays the dashboard's `soft-mechanical-click` (bundled copy) and a light
  haptic.

## Screens and navigation

Bottom bar of plastic console buttons, same moulded treatment as the orb but
subtler: **Measure · Readings · Help · About · Donate**. The active label takes
the LED colour. A gear at the top right of every screen opens Settings.

- **Measure**: orb centred vertically. State line under it; guidance prompts
  while scanning ("cover the lens and flash completely", "press more lightly",
  "keep still"); a three-state quality lamp in the LED colour. Scan stops at
  **15 seconds of usable signal** (minimum 8 s before a result, hard cap 45 s).
  After a result: a **Save** button below the orb in the ring gradient colours;
  Share (text, and image of the orb) appears with it. The reading is filed in
  **Today's readings** the moment it finishes, saved or not, so nothing is lost by
  not pressing Save; Save then marks it kept (it reads Saved and is disabled), and a
  recalibration updates that filed reading instead of adding another. Filing is keyed by
  the scan's own id and happens in the tab host rather than on Measure, so a scan that
  finishes while another tab is showing is filed just the same, and no scan is filed
  twice. **The fold line rests just above the bottom bar** - see the revision of
  2026-10-03.
- **Front disclaimer** ("Not a medical device. Estimates only.") sits on
  Measure with an **Acknowledge** link at its bottom. Acknowledging stores the
  timestamp and hides it permanently. Settings shows "Acknowledged <date>" and
  "Show it again".
- **Readings**: the readings the user kept, newest first; tapping a row stars it (☆
  drawn on every row, ⭐ when set); filter above the list: All / Starred; swipe to
  delete. Row: date, HR, BP range, quality. A reading taken today and not kept is not
  listed here - it is in Today's readings on Measure, and one tap there keeps it or
  drops it again. Empty states: "Nothing kept yet. …" and "Nothing starred yet."
- **Help**: how the technique works (green light, ~1% AC component, filter,
  beat detector, quality gate, feature-based estimate), honest accuracy,
  caveats (skin tone, age, motion, pressure, cold hands, ambient light, device
  differences), and links (study DOI; the ResearchGate PDF; markolalovic/
  ppg-vitals; Elgendi 2013; Liang 2018; ISO 81060-2), plus the
  free-forever and nothing-is-sent declarations.
- **About**: version, model weights version, the regulatory line ("Not a
  medical device; not FDA-cleared or CE-marked"), third-party notices.
- **Settings**: theme Auto/Light/Dark, age and sex (optional, used only by the
  estimate, stored locally), disclaimer state, "Simulated pulse" (simulator
  builds only).
- **Donate**: three optional consumables ($1 / $10 / $50) via StoreKit 2; a
  visible "completely optional, nothing is unlocked" line; a thank-you after a
  purchase. Product IDs come from `publisher/config/store.config.json`. When
  the products cannot be fetched (unconfigured or free-signed build) the buttons
  show the price from config, are disabled, and say why. Confetti bursts from the
  chosen button: $1 ~60 particles / 0.9 s; $10 ~220 / 1.6 s; $50 ~520 / 2.6 s
  plus sparks in the highlight colour; gravity in screen points/s².

## Data separation

- `readings.json` (Application Support, excluded from backup? no — included,
  it is the user's own data) holds only readings and preferences. It carries two kinds
  of reading: the ones the user kept (`saved`), which Readings lists, and the ones taken
  today but not kept, which only the fold on Measure shows. A file written before the log
  existed has no `saved` field and reads as kept; an unkept reading from an earlier day is
  dropped when the file is next written, so only today's unkept readings are ever held. A
  reading also carries the `scan` it came from, which is what makes filing that scan again
  refresh its row instead of adding another.
- `publisher/config/*.json` is bundled read-only and holds everything that is
  the publisher's: bundle id, product ids, URLs, policy text. A test fails if a
  publisher identifier appears in `App/**/*.swift`.
- Model weights are in the bundle, not in the readings store.

## Errors

Explicit and specific: camera permission denied, no torch, no pulse found, lens
not covered, too much motion, scan too short. A wrong number is worse than no
number, so an error never shows a value.

## Delivery — deploys are automatic

Standing rule (Adeline, 2026-10-03): once a change is merged into `android` it is deployed to the
test phones without being asked again, unless she says otherwise for that change. A deploy states
which revision went out and where it went.

Two limits, so a deploy is never promised for something that cannot happen:

- **An iOS install needs the phone on the home network.** Apple's tooling finds devices by
  Bonjour/CoreDevice discovery, which does not cross a Tailscale tailnet; measured 2026-10-03,
  `devicectl` answers `device was not found` for the phone's Tailscale address and for its
  MagicDNS name. A phone that is away takes the next deploy when it is home.
- **The Android phone is reached over USB** from the PC that builds the APK.

The iOS build is signed with a free personal team, so an install expires about a week after it is
made; a fresh deploy brings it back.

## Definition of done (this build)

1. `swift test` passes for `Core` (geometry, filters, detector on synthetic
   pulse trains with known rate, HR rejection rules, quality, estimator caps).
2. The app builds signed for device with the personal team on the build host.
3. Installs and launches on the test iPhone; the orb, ring, grid, sweep and camera
   centre render; a real fingertip scan yields a result or an honest error.
4. Dark and light themes both correct against the table above.


## Revisions, 2026-10-01 (after the first device run)

These supersede anything above that disagrees.

- **Name**: VitaLibre (bundle id `nz.skull.vitalibre.claude`, project `VitaLibre.xcodeproj`,
  scheme `VitaLibre`). The first install, `nz.skull.vitals.claude`, is a separate app
  on the phone and can be deleted.
- **Sweep**: one revolution per reading, `ScanSession.targetSeconds` (15 s), zero at the
  moment the finger covers the lens. The heartbeat trace covers the whole run around
  the ring, so the waveform is drawn at that scale. Idle sweep uses the same period.
- **Ring**: half the earlier thickness, `ringWidthRatio = 1.5` times the reference
  channel (was 3.0). Bands: dome 0-0.685, unlock 0.685-0.738, ring 0.738-0.966, lip
  0.966-1.0. Grid circle pitch 0.038.
- **Colours**: the ring uses the orb linework of the current mode, not one set for both.
  Dark: raw (255,5,6)@94, (255,0,44)@100, (255,160,0)@87, painted at the dashboard's line
  opacities 54/70/64%. Light: (0,245,255)@23, (0,255,115)@30, (0,143,255)@33, applied to
  `#00383B #004D23 #002F54`, opaque. Heartbeat trace core is `#FFE6D6` on dark and the LED
  colour on light, with the LED colour as its glow.
- **Clipping**: the saturated fraction is measured on the GREEN channel (the one the pulse is read from). Red clips on nearly every pixel of a fingertip under the torch (measured on an Android phone: about 78-88% at red 250), so counting red made a correctly covered finger flip between "press more lightly" and "not covered".
- **Flash**: held on for the whole scan. iOS can switch it off, so the torch state is
  checked twice a second and re-applied.
- **Age** is a menu (Not set, 18-100). Sex is a menu.
- **Blood pressure calibration** (reverses the Hermes plan's calibration-free decision):
  a camera cannot measure blood pressure without a reference. After a reading, Calibrate
  takes a cuff systolic/diastolic and stores it with the model's raw value for that scan.
  The estimate shifts by the mean gap. With three or more points the range narrows to
  1.64 x the residual SD (minimum 6/4 mmHg, never wider than the model's 14/9). Offset
  capped at 60 mmHg; cuff values outside 70-250 / 40-150 or with systolic within 10 of
  diastolic are rejected. Reset in Settings. Heart rate needs no calibration.
- **Wording** follows ordinary consumer health-product language: "Not a medical device,
  results provided are an estimate only." followed by the wellness/no-diagnosis/consult-a-
  professional paragraph (`publisher/config/policy.json`). Invented sub-labels and hints
  were removed: empty-state prose, quality lamp, idle hints, tier labels, progress text.
- **SpO2** was considered and dropped. It needs per-device calibration across a
  desaturation range that cannot be gathered safely, and store rules prohibit it.

- **Usual blood pressure**: Settings takes the person's usual resting systolic and diastolic
  (for example from a medical record). It replaces the age and sex starting point; the small
  pulse-shape adjustments and any cuff calibration still apply on top. With it set, the result
  is a single figure per component.
- **Device table** (`App/Resources/iphone-models.json`): iPhone 16, 17 and 18 families plus
  earlier models, keyed by identifier. Carries the rear camera a scan uses (`camera`, main
  camera everywhere for now, `ultraWide` selectable per model), Apple's flash wording where
  read, and `flashSpectrum`. Apple does not publish LED wavelengths, so `flashSpectrum` and
  per-model baselines stay null until a published or measured source exists. Which lens sits
  nearest the flash was not established; to be tried on the phone per model.
- **iPad (later)**: no rear-flash finger method. Use the front camera on the face
  (remote PPG), which is a separate and weaker technique; plan it as its own feature.

- **Trace colour and grid (2026-10-01)**: the trace is coloured by height in one red family (`#4A0709` to `#9E0F16` to `#E11D25` to `#FF5A4F`). Grid alpha is 0.10 at rest, up to 0.24 near the sweep; hairlines 0.7-0.9 pt. The live trace pulses on each beat (decay 0.3 s) and the finished graph stays until the next reading.
- **Dark ring colours (2026-10-01)**: red `#F00506`, red `#FF002C`, red-pink `#FF2A66`, painted at 80/90/80%. This departs from the dashboard's orange third stop (255,160,0) because the owner found it washed-out.
- **First load**: a three-step intro (big text, big confirm button, disclaimer at the bottom); the disclaimer no longer sits on the Measure screen. Typical resting blood pressure is set under Calibration. The image share is a cropped screen capture of the orb.
- **Licence**: GPL-3.0-or-later (`LICENSE`), shown in About. The Hermes plan chose the same licence.

## Revisions, 2026-10-02 (waveform line, flash memory)

- **Waveform line**: one stroke at 1 pt on iOS and 1 dp on Android - the same hairline on a phone, though a
  point is 2-3 device pixels and not one - with butt caps and round joins, over two narrow
  additive strokes that stand in for a glow (2.6 pt at 10%, 1.8 pt at 16%). The earlier 7 pt/4.5 pt
  pair at 18%/30% read as a fat pixellated band rather than a line. The same numbers on both platforms
  (`OrbPainter+Live.swift`; `OrbPainter.kt`, scaled by density).  
  The crest dots moved out of the additive layer with it and shrank to `1.6 + 0.6 * pulse` pt, matching
  Android; they were drawn additively at `2.4 + 1.2 * pulse` before.
- **No curve fitting**: the trace is straight segments between samples, three samples per segment, with
  the wrap back to the start of the circle skipped. Nothing is smoothed or overshot, so what is on
  screen is the data that arrived.
- **Clipped to the graph**: the trace layer, glow strokes included, is clipped to the ring band
  (`OrbGeometry.ringInner`..`ringOuter`) on both platforms, so a sample at the end of the range cannot
  draw outside the graph.
- **Trace colours are the same on both platforms**: one height ramp per mode — dark `#4A0709`,
  `#9E0F16`, `#E11D25`, `#FF5A4F`; light `#06262B`, `#0B5F63`, `#129C8E`, `#4FE3C1` — with the crest
  flash `#FF2B2B` (dark) or `#1FD6B8` (light) added to the segment colour. The core stroke is painted
  with ordinary blending so the red stays ruby; the orange cast came from the wide additive glow, which
  is gone.
- **Flash memory** (both platforms): a reading starts in the flash state the last reading with a result
  settled on (`Preferences.workingFlash`, `Prefs.workingFlash`; nil = not known). With no memory the
  flash starts off. The policy is one implementation per platform with the same constants
  (`FlashPolicy.swift`; `CameraSource.regulateLight`):
  - the flash is offered when 10 s of cover with the flash off still gives a weak pulse
    (quality < 20), or when no finger has been recognised for 10 s;
  - it is tried for 4 s and kept unless the flash-off figure was at least 5 better; a flash that fails
    that comparison is switched off again and that preference is remembered;
  - a scene whose mean channel value stays under 12 for a full second switches the flash on at once,
    including against a remembered "off": a scene with nothing lit has nothing to read;
  - a reading that ends in a result records the state it used; one that fails clears the memory, and so
    does a camera reading that ends before the policy settled: a state the reading did not prove is never
    kept. While a reading runs the torch is still re-applied twice a second;
  - the reading is finalised as soon as the decision lands, and at the scan's own 45 s maximum either way,
    so a trial that can never settle cannot hold a reading open;
  - "no finger for 10 s" is measured from the last *covered* sample, so a finger that is on the lens never
    trips it;
  - the exposure, white-balance and focus lock is released when the torch switches and re-taken only after
    a second of stable light, so the 4 s trial is compared on its own exposure, not the flash-off scene's.
- **Saturation is counted on the green channel, on both platforms**: the `saturated` fraction that `covered`
  and "press more lightly" use counts green pixels at full scale. iOS counted red, which clips on nearly
  every pixel of a correctly covered fingertip under the torch, so a good finger could read as uncovered.
  `Core/ScanSession.swift` and `android/core`'s `ScanSession.kt` both say green now.

## Revisions, 2026-10-03 (the session fold on Measure)

Adeline, 2026-10-03: "place the fold line just above the bottom menu".

- **Today's readings is a fold on Measure**, the dashboard's advanced fold
  (`nova-ha-dashboard/specs/advanced-fold.md`) on a phone screen, one implementation per
  platform (`App/Views/FoldPage.swift` and `App/Views/FoldDivider.swift`;
  `android/app/src/main/java/nz/skull/vitalibre/FoldBand.kt`).
- **The line rests on the bottom of the page, just above the bottom bar**, with the
  caption `TODAY'S READINGS`, the count of today's readings and a solid flattened triangle
  pointing at what it guards, right-aligned above it. The line is the theme accent
  (`palette.line`, this app's port of the dashboard's `--cyber-line`) with the accent's lit
  edge under it: the dashboard's sunken bevel, at 45% instead of the dashboard's 18%, which
  does not read against a near-black foot of screen. `FoldMetrics.rest` (30 pt) is its height, on
  both platforms.
- **The whole screen is one page** (Adeline, 2026-10-03: "the whole screen (minus menu) should slide
  up when the today's readings fold goes up"). Measure is one vertical scroller holding the orb, what
  it is saying, the line, and the day's readings under it. Closed, the line rests just above the
  bottom bar with the readings laid out under it: the page's content is already the viewport plus
  the panel's own height, and the band is what keeps that overflow out of sight, which is why the
  pull meets resistance rather than a page with nothing past it. Opened, the readings come in from
  the foot as everything above the line slides away, and then scroll like any list. With nothing
  taken today the page is exactly the viewport and the fold does not exist at all.
- **The band** (Adeline: "pulling up grows in resistance against being dragged, before snapping at a
  certain point and letting the hidden area scroll freely") is 80 pt of upward pull caught by
  `d(p) = 28(1 - (1 - p/80)^2)` — nearly 1:1 at first, moving nothing by 80 pt — so the page moves by
  the band's allowance and never by the finger. Those numbers are the dashboard's round-1 touch band
  and its wheel band; the dashboard's current drag breaks at 160 px with 14 px of give
  (`nova-ha-dashboard/specs/advanced-fold.md`, Round 2), sized for a full desktop page, where 80 pt
  is the same fraction of a phone's height. Released inside the band the page returns to rest on the
  platform's own animation — an 180 ms ease-out on Android, the scroll view's own return on iOS,
  which is not a spring and does not need to be — and the release's own fling goes with it.
- **The break**: at 80 pt the band lets go, the page catches up the 52 pt the band had been holding
  back (220 ms, `cubic-bezier(0.2, 0.9, 0.3, 1.15)`, the dashboard's slight overshoot), and from
  there the finger's own travel is the page's, so the hidden area scrolls freely with it. A finger
  that lifts, or a new touch, part-way through the catch-up finishes it in one step rather than
  cancelling what is left: the page always ends where following the input 1:1 would have put it.
- **Re-lock** (Adeline: "when the page is scrolled back out of sight, the rubber band heals"):
  brought back to its edge the fold closes — the triangle flips back to pointing up — and the next
  pull meets the 80 pt band again rather than sliding straight through. There is no resistance on the
  way back.
- **With nothing past the line it does not open**: the fold exists only when there is a reading to
  open into *and* the page overflows the viewport by at least a row (`FoldBand.minReveal`, 44 pt).
  With nothing taken today the page is exactly the viewport and cannot be pulled at all. Geometry
  alone is not enough: `FoldPage` takes whether there is anything to open into explicitly
  (`hasContent`) rather than leaving the fold's existence to the caption's measured height. If the
  day's log empties while the fold is open, the page falls back to the viewport and stops scrolling.
- **A tap opens or closes it fully**, which is also what VoiceOver and TalkBack activate, and the
  triangle flips to point down while it is open. With reduced motion the break and the spring land on
  their positions with no animation.
- **The band's numbers and its two rules are pure and shared**: `Core/FoldBand.swift` and its Kotlin
  port in `android/core`, with the same tests on both sides — the near-1:1 start, the exact break at
  80, the catch-up that lands the content where a pull that never met the band would have put it, and
  the re-lock.
- **Android still carries the reveal fold** (2026-10-03): the Kotlin band and its tests are in
  `android/core`, but the Android measure screen keeps the earlier fold — a room at the foot of the
  screen that the panel opens into (`FoldMetrics.room`) — until the page-wide slide can be finished
  there. The iOS build leads the Android one, as always; the row's kept mark is a bookmark on both.
- **A finished scan is filed the moment it lands**, unsaved, in today's log: that is what stops
  the last reading being lost by not saving it. Filing is keyed by the scan's own id and happens
  in the tab host, which outlives the measure screen: a scan that finishes while another tab is
  showing still lands in the log, and returning to Measure, pressing Save or recalibrating
  refreshes that one reading instead of adding another. Measure is the only screen that shows an
  unkept reading; Readings lists the kept ones. A row reads heart rate, `BPM`, the
  blood-pressure text (one figure per component once calibrated, the population range until
  then), the time, and a **bookmark for kept** — not a star, which is the reading's own mark in
  Readings and cannot mean two things at once (Adeline, 2026-10-03: "don't use a star as the icon to
  save it, as we already use that for favouriting") — tap the row to keep it, tap again to drop it.
- **The rules live in `Core/ReadingLog.swift`**, pure, with no file and no view; `ReadingStore`
  is the observable wrapper over `readings.json`. Seven tests in
  `Tests/VitaLibreCoreTests/ReadingLogTests.swift` cover what the fold turns on: filing one scan
  twice leaves one reading, a recalibration refreshes in place, Save adds no second reading, two
  scans are two readings newest first, the prune drops only an earlier day's unkept reading, a
  file written before the log existed reads as kept, and the scan key survives the file.
- Verified on the simulator, iOS (2026-10-03): closed, the line and its caption sit at the foot of the
  measure area with only the bottom bar below them, nothing of the readings shows, and the orb is
  centred in the room above the line. The band itself is the part the shared tests hold
  (`FoldBandTests`, both languages): the near-1:1 start, the break at exactly 80, the catch-up, and
  the re-lock.

## Revisions, 2026-10-04 (the graph is kept with the reading; a swinging rhythm is a note)

Adeline, 2026-10-04: *"I want to save the heart rate graph with the saved readings. just show it as
a horizontal band under the readin though. users should be able to tap it to make it bigger and
scroll throuhg it, with two finger pinch to zoom and tap to dismiss again."* — and, on the reading
rules: *"we're currently rejecting recordings that might include arrhythmia. don't abandon
recordings due to unstable heart rate, and mark the reading as 'low quality or arrhythmia'"*, then
*"note we should only reject/restart readings if the finger clearly isn't stable on the camera. you
can work out the correct ranges, but timing shouldn't be a disqualifying factor"*.

### The graph travels with the reading

- **`Reading.trace`**: the run's filtered waveform, normalised -1...1, at the analysis rate
  (60 Hz), rounded to three decimals. Optional, so a reading saved before this existed decodes
  with none and simply has no graph. `ReadingLog.file` is the only writer, taking
  `ScanResult.trace`.
- **Full rate, not a decimated copy.** 15 s is about 900 samples, roughly 6 KB of JSON per
  reading. Half the rate would halve that, and stair-step once the expanded view is zoomed: the
  point of the bigger view is to see what arrived.
- **Nothing else is stored.** A sample's time is its index over `ScanSession.analysisRate`, so the
  array needs no start, no end and no timestamp of its own.
- **It lives in `readings.json`**, not a second file and not the photo library: deleting the
  reading has to delete its graph.
- `exportText` carries the note (below) on the line it belongs to, so a shared reading is not
  quieter than the app.
- The fold on Measure is unchanged: its rows stay one line each, and it is a picker, not a viewer.

### The band, under the reading

- Each Readings row gains a **band** under its data: the whole run across the row's width, 44 pt
  tall, on the same cut-corner panel as everything else, with a hairline through the middle.
- Drawn exactly as the orb draws its trace, in the same per-mode height ramp (dark `#4A0709` ->
  `#9E0F16` -> `#E11D25` -> `#FF5A4F`; light `#06262B` -> `#0B5F63` -> `#129C8E` -> `#4FE3C1`): one
  1 pt core stroke over two narrow additive strokes (2.6 pt at 10%, 1.8 pt at 16%), butt caps,
  clipped to the band. No grid, no sweep, no crest dots — at 44 pt the line is the reading.
- **The band is its own target.** Tapping the row still stars it; tapping the band opens the
  graph. One tap cannot mean both, so the band sits beside the row's own gesture rather than
  inside it.
- A reading with no stored graph shows no band, and nothing takes its place.

### Expanded: the whole graph

- Tapping the band opens the reading full screen: the background gradient, then the date, heart
  rate, blood-pressure text and the note if it has one, then the graph.
- The graph is drawn at **60 pt per second of the run** (15 s is 900 pt, about three phone widths),
  so a single beat is legible, inside a scroller that moves horizontally, and takes the middle 40%
  of the screen. Nothing overlaps the header.
- **Two fingers pinch to zoom** (0.5x...8x on the graph's own width; the scroll position is kept
  where it was, proportionally), **one finger scrolls it**, and **a tap anywhere dismisses**.
- **A Done control also closes it.** A tap-to-dismiss surface cannot be the only way out: VoiceOver
  and anyone who does not find the tap need a control that says what it does.
- No animation of its own: the expanded view adds no transition, so there is nothing for Reduce
  Motion to suppress, and the way it opens is the platform's own presentation.

### A swinging rhythm is a note, not a refusal

- **Both rhythm rejections are gone.** `HeartRate.estimate` no longer refuses a window because too
  small a share of intervals stayed in band (`minAcceptedShare`), nor because the two halves'
  medians drifted apart (`maxHalfDrift`). A rate that swings mid-scan is measured and reported
  instead. `ScanFailure.unstable` and `HeartRateFailure.unstable` no longer exist.
- **The running-median outlier drop is gone too** (`maxDeviationFromRunning`, 30%). It is a timing
  rule, and it is what threw away ectopic beats — the beats the note exists for.
- What is still dropped, and why none of it is a timing judgement:
  - **Intervals outside 0.25...2.0 s** (30...240 bpm; widened from 0.33...1.5 s, 40...180 bpm).
    Outside that is a dropout or one beat counted twice, not a heartbeat. This is the "correct
    ranges" the change was given room for.
  - **Beats whose amplitude is outside 0.5...2.0x the window's median**: the detector's confidence
    in the beat, not its timing.
  - **Fewer than 8 accepted intervals**: not enough beats to take a median of. A count, not a
    duration.
- **`Rhythm`** (`steady` / `irregular`), on the estimate, the result and the reading, is the
  interval spread — standard deviation over the mean, the same statistic the estimator already
  carries as `intervalCV`. **`irregular` at 0.10 and above**: ordinary respiratory variation sits
  near 0.03 and stays steady, and a rhythm that genuinely swings is well past it. One constant,
  `HeartRate.irregularVariation`, the same number on both platforms.
- **The note.** A reading carries **"Low quality or arrhythmia"** when its level is `poor` or its
  rhythm is `irregular` — `Reading.note`, from `ReadingNote.lowQualityOrArrhythmia`. One phrase
  covers both because the numbers cannot tell them apart and the app does not pretend otherwise: a
  weak signal and a swinging rhythm come out of the same three indices. It shows on the Readings
  row, in the expanded graph's header, and in `exportText`.
- **The quality gate no longer refuses a reading for shape.** `level == .poor` used to be a
  failure; it is now the note. The one quality failure left is the **perfusion index below
  `SignalQuality.minPerfusionIndex`** — a pulse that never rises above the light level, which is a
  finger problem (too light, too heavy, off the lens) and not something a number can come from.
- **Every rejection left is the finger's**: `notCovered` (no covered run), `tooShort` (under 8 s of
  cover — a scan cut off, not a rhythm), `noPulse` (fewer than 8 beats to measure), `poorSignal`
  (the perfusion floor). Nothing else refuses a reading.
- **Help copy follows on both platforms.** The beat paragraph: intervals outside what a fingertip
  can show are dropped and the rate is the median of the rest, so a missed or extra beat cannot
  swing it. The quality card: a pulse too weak to read is an error, and anything else is kept, with
  a rhythm that swings noted on the reading rather than thrown away.

### Tests

- `ReadingLogTests`: a reading keeps its graph through the file; a reading with no graph reads as
  one with none; the note is present for a poor level and for an irregular rhythm and absent
  otherwise; `exportText` carries it.
- `CoreTests`: a drifting rhythm (0.6 s then 1.0 s intervals) is now a **success** with
  `rhythm == .irregular` rather than a failure; a steady train is `.steady`; the widened range
  accepts an interval of 0.3 s (200 bpm) and still refuses one of 0.2 s (300 bpm); a noisy scan is
  **never silently dropped** — `ScanSession.analyse` returns either a failure carrying a message or
  a reading marked `poor`, and `testANoisyScanIsEitherAnErrorOrAMarkedReading` pins exactly that
  either-or. Of the four refusals left, `notCovered` and `tooShort` each have a test of their own
  (`testUncoveredIsAnError`, `testTooShortIsAnError`); `noPulse` and `poorSignal` do not.
  `poorSignal` is the perfusion floor, `SignalQuality.minPerfusionIndex` = 0.05 % of the window's
  DC — a value from the first commit, below what a fingertip produces, so it fires on a window with
  no readable pulse in it rather than on a weak one.
- What was driven, and what was not. On the Android emulator, through its own UI: the band under a
  reading, the note, the band opening the graph, a drag scrolling it, a tap dismissing it. On the
  iOS simulator, seeded readings photographed: the band, the note line, and a reading with no graph
  showing no band. **Not driven anywhere**: pinch, on either platform (neither `simctl` nor `adb`
  injects a second finger here), and the iOS tap or drag (the simulator cannot be tapped from a
  script on this machine). The iOS full-screen graph was photographed through a temporary local
  harness that opened it without a tap; that harness was reverted before the commit.

## Android build (branch `android`)

Native Kotlin, Jetpack Compose and CameraX under `android/`; same design, theme, copy and numbers as the iOS build.
`android/core` is a pure-JVM Kotlin port of `Core/` (geometry, filters, beat detector, heart rate, quality, BP estimator,
calibration, scan session) with the same tests; `android/app` shares fonts, sounds, the model weights and the publisher
config with the iOS build through the asset path instead of copying them.

- **Readings run off the UI thread.** The camera analyser (raised priority) hands samples to a scan engine on its own
  high-priority thread, which does the filtering, beat detection, live estimate, trace image and final analysis. The
  UI receives finished updates only, so a slow frame cannot disturb a reading and a reading cannot stall the screen.
  A watchdog ends a scan whose camera stops delivering frames.
- **Cheap frames.** Everything that does not move is rendered once into an image; each frame draws only the lit grid,
  the trace image, the sweep and the progress arc, in their own layer. The trace is rendered off the UI thread.
- Torch held on by CameraX (state checked and re-applied); exposure and white balance locked after a second of cover.
- Haptics through the vibrator, sounds through a sound pool, both from the engine thread.
- The iOS build is the source of truth for screens, copy and behaviour; the Android screens match it (the calibration screen has the typical resting pressure and the cuff-calibration count with Reset, nothing more).
- The synthetic pulse (for testing the scan path) exists only in debug builds; a release build cannot run it.
- Not yet on Android: Play Billing donations (the buttons are inactive), the confetti, and the
  measure page's whole-screen slide — the fold there still opens into its own room at the foot of the
  screen, and its row's kept mark is now a bookmark like the iOS build's.
- **Licences on Android**: the app is GPL-3.0-or-later like the iOS build (same `LICENSE`). Compose, CameraX and AndroidX are Apache-2.0, which is compatible with GPLv3 and is credited in About. The Android manifest requests only CAMERA and VIBRATE; there is no network permission, so the "nothing is sent" statement holds there too. Both font licences (Chakra Petch, Rajdhani) ship in `App/Resources/Fonts`.
