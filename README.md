# VitaLibre

Native SwiftUI camera-PPG app (iPhone first). Heart rate plus an experimental
blood-pressure range from a fingertip on the rear camera with the torch on.
Readings stay on the device. See `specs/ppg-vitals-app.md` for everything.

- `Core/` pure Swift: orb geometry, filters, beat detector, HR, quality, BP estimator, scan session
- `App/` SwiftUI app, fonts, sounds, `bp-model.json`
- `publisher/config/` publisher-owned values (bundle id, product ids, policy text)
- `Tests/VitaLibreCoreTests` run with `swift test` on any Mac
- `tools/gen_xcodeproj.py` regenerates `VitaLibre.xcodeproj` (synchronised folders);
  `tools/make_icon.py` redraws the icon

## Licence

GNU General Public License, version 3 or later (see `LICENSE`). The bundled fonts are under the SIL Open Font License (see `App/Resources/Fonts`).
