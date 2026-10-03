# VitaLibre

**Branch `android` targets Android** (Kotlin, Jetpack Compose, CameraX). The `main` branch targets iOS.
Releases are tagged per platform as `<version>-ios` and `<version>-android`; the first Android release is `0.1-android`.

Native SwiftUI camera-PPG app (iPhone first). Heart rate from a fingertip on the rear camera with the torch on,
a per-second score of the camera feed itself (kept apart from the heart), and a blood-pressure estimate that is
unvalidated: on iOS it is shown only against a recent cuff calibration. Readings stay on the device. See `specs/ppg-vitals-app.md` for everything.

- `android/` the Android app (`core` is the Kotlin port of the pure logic, with its own tests; `app` is the Compose UI)
- `Core/` pure Swift: orb geometry, filters, beat detector, HR, quality, BP estimator, scan session
- `App/` SwiftUI app, fonts, sounds, `bp-model.json`
- `publisher/config/` publisher-owned values (bundle id, product ids, policy text)
- `Tests/VitaLibreCoreTests` run with `swift test` on any Mac
- `tools/gen_xcodeproj.py` regenerates `VitaLibre.xcodeproj` (synchronised folders);
  `tools/make_icon.py` writes the icon set from the master image

## Licence

GNU General Public License, version 3 or later (see `LICENSE`). The bundled fonts are under the SIL Open Font License (see `App/Resources/Fonts`).
