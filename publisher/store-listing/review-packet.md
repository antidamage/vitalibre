# VitaLibre launch review packet

This packet tracks the owner review before either store review. It does not authorize submission.

## Prepared

- Three existing support tiers are wired as repeatable consumables with US target prices of $1.00, $10.00, and $50.00. They unlock nothing.
- The Android release uses Google Play Billing and a signed upload AAB. The upload key and credentials are held outside source control.
- The iOS release uses StoreKit 2. Its publishing archive must come from Xcode Cloud on the owner's main paid developer account.
- Public [privacy](https://skull.nz/vitalibre-privacy.html) and [support](https://skull.nz/vitalibre-support.html) pages are live. App links and store copy point to them.
- English store descriptions, product copy, Android screenshots, and a feature graphic are in this directory. The current screenshots show an emulator preview; replace or supplement them with accepted device captures before submission.

## Evidence to collect before owner sign-off

| Item | Status |
| --- | --- |
| Google Play app record, product IDs, active offers, exact US prices, and country availability | Waiting for authenticated Play Console access |
| Google merchant payments profile, developer verification, production access, and declarations | Waiting for authenticated Play Console access and account facts |
| Android internal-track upload and license-tester purchase flows | Waiting for Play record and products |
| Apple app record under Skull, bundle ID, three IAPs, US price points, storefronts, and tax category | Waiting for Skull's paid enrollment and App Store Connect access; do not use the personal team |
| Apple Paid Apps Agreement, banking, tax, privacy, age rating, and export declarations | Waiting for paid enrollment and account facts |
| Xcode Cloud workflow under Skull, distribution archive, TestFlight build, and sandbox purchase flows | Waiting for Skull's paid enrollment and App Store Connect access |
| Actual-store localized prices, purchase receipts, pending/cancel/repeat/restart checks | Waiting for active store products and test tracks |
| Blood-pressure store eligibility | Open: the existing unvalidated camera estimate may not satisfy Apple's guideline 1.4.1 or Google's health policy |

The blood-pressure estimate remains as requested. The app and listing identify it as experimental and unvalidated; that wording does not guarantee store acceptance. Resolve this gate through accepted evidence or an owner-approved product change before describing the release as ready for review.
