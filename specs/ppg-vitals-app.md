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
  Share (text, and image of the orb) appears with it.
- **Front disclaimer** ("Not a medical device. Estimates only.") sits on
  Measure with an **Acknowledge** link at its bottom. Acknowledging stores the
  timestamp and hides it permanently. Settings shows "Acknowledged <date>" and
  "Show it again".
- **Readings**: newest first; tapping a row stars it (☆ drawn on every row,
  ⭐ when set); filter above the list: All / Starred; swipe to delete.
  Row: date, HR, BP range, quality.
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
  it is the user's own data) holds only readings and preferences.
- `publisher/config/*.json` is bundled read-only and holds everything that is
  the publisher's: bundle id, product ids, URLs, policy text. A test fails if a
  publisher identifier appears in `App/**/*.swift`.
- Model weights are in the bundle, not in the readings store.

## Errors

Explicit and specific: camera permission denied, no torch, no pulse found, lens
not covered, too much motion, scan too short. A wrong number is worse than no
number, so an error never shows a value.

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
- Not yet on Android: Play Billing donations (the buttons are inactive) and the confetti.
- **Licences on Android**: the app is GPL-3.0-or-later like the iOS build (same `LICENSE`). Compose, CameraX and AndroidX are Apache-2.0, which is compatible with GPLv3 and is credited in About. The Android manifest requests only CAMERA and VIBRATE; there is no network permission, so the "nothing is sent" statement holds there too. Both font licences (Chakra Petch, Rajdhani) ship in `App/Resources/Fonts`.
