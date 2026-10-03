# VitaLibre store launch

Produced from the user-approved VitaLibre payments and store-launch plan of 2026-10-04.

## Goal and owner decisions

Prepare the iOS and Android apps, their three optional payment products, and both store records for owner review before store submission. The agent handles implementation, configuration, tests, release assets, and correction work. The owner reviews the completed result and may request changes before store review. No store submission or public release is part of this execution.

Keep every existing app feature and presentation, including the experimental blood-pressure estimate. Do not add a subscription, paywall, account, analytics, advertising, payment backend, or other payment options. The three repeatable support amounts are exactly US$1.00, US$10.00, and US$50.00 in the US storefronts; the stores generate appropriate local prices elsewhere. Target all eligible countries with English launch metadata. Purchases unlock nothing. Do not claim they are charitable or tax-deductible donations.

Use the owner's existing App Store Connect and Google Play accounts. Skull is the intended Apple publisher; the current personal team is only for development installs and must not own the App Store app record, in-app purchases, signing, or Xcode Cloud release workflow. Paid enrollment of the Skull publisher account is deferred. iOS publishing builds use Xcode Cloud under that paid membership's included build allowance once available. The existing local Xcode environment may be used for development checks and needs no setup work. Do not contact Apple or Google support about blood-pressure eligibility; prepare the app as-is and record the policy risk honestly.

## Payment behavior

Both platforms offer the product identifiers in `publisher/config/store.config.json` as consumable one-time products, with store-returned localized prices. No purchase launches until the matching product is available. A single purchase may be in flight; repeat purchases are allowed after successful processing. Cancellation causes no success effect. Pending payments remain pending until the store reports completion. Success follows verification and transaction completion/consumption, and a completed transaction encountered after app restart is processed once. Errors state what happened without implying the customer was charged. If products cannot be fetched, controls remain disabled and can retry. Fallback USD text may explain the intended US amount, but must not present itself as a live localized price.

The Apple implementation uses StoreKit 2 verified transactions and finishes them. Android uses Google Play Billing, processes purchases only in the PURCHASED state, consumes each purchase token so its product remains repeatable, and queries existing purchases after reconnection. No receipt or health data goes to the publisher. The support screen remains visibly optional and leaves every app function available regardless of payment.

## Publishing requirements

Inspect both actual publisher accounts before registering permanent app and product identifiers. Reuse valid existing records. Apple requires the main account's active membership, Paid Apps Agreement, banking and tax setup for IAP. The owner handles only unavoidable payment, identity authentication, and legal attestations using real information. On Google, complete developer verification, merchant and payout setup, and any production-access test applicable to that account.

Create three Apple consumable IAPs and three Google consumable one-time products with accurate review names, descriptions, prices, tax settings, availability, and required images or evidence. The US price of each must match the exact stated amount; if a store cannot offer one, report that as a release gate rather than silently using a nearby amount. Configure availability in eligible storefronts and record store-generated local prices. Complete sandbox/license-tester setup and test all product states.

Xcode Cloud obtains source from an existing supported remote. Configure a shared-scheme test and release archive workflow, distribution signing, unique build numbers, TestFlight delivery, and artifact/symbol retention. The existing Mac is a development and optional initial configuration tool; all App Store publishing archives come from Xcode Cloud. The Android release is an AAB signed by a private upload key under Play App Signing, never the debug key.

Publish functioning support and privacy pages, link them in both stores and the apps, and ensure their statements match actual storage, sharing, permissions, and billing behavior. Complete Apple privacy, age-rating, export and regional entries; complete Google Data safety, content rating, target audience, ads, app access, and Health apps declarations. Produce actual-device screenshots, icon, feature graphic, descriptions, and review instructions. Audit licences and source attribution. Keep sensitive identities, credentials, signing material, host details, and device details outside tracked files and release descriptions.

The blood-pressure feature is a release eligibility risk. Apple's guideline 1.4.1 explicitly restricts blood-pressure measurement using device sensors and requires substantiated accuracy claims. Google's health policy disallows misleading or harmful functionality and requires the Health apps declaration and a public privacy policy. The app's unvalidated camera estimate and existing disclaimers do not establish approval. Do not call this resolved or promise approval without store-accepted evidence. Do not modify or remove the feature without a new owner decision.

## Acceptance

- All three tiers load at correct US prices on both stores and display store-localized prices elsewhere; repeat, cancel, pending, failure, offline, restart, and unavailable-product flows behave correctly.
- Swift core tests, Android core tests, release builds, and device checks pass. Camera, calibration, saved readings, sharing, permissions, and current UI behavior still work.
- All console validation issues within the chosen scope are resolved. Public links work and listing text and images match the exact uploaded candidates.
- Apple sandbox/TestFlight and Google tester transactions pass once each account permits them. Test evidence and build references are retained outside source control.
- The owner receives a review packet with exact build and store-record references, pricing by storefront, test evidence, and a short list of any gates still held by deferred enrollment or the BP policy issue. No review submission or public release occurs before owner review.
