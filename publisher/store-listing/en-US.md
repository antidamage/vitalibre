# VitaLibre store copy — en-US

Store copy for owner review. Keep the blood-pressure language aligned with the app and its unresolved store-eligibility gate in `specs/store-launch.md`.

## Both stores

- App name: VitaLibre
- Category: Health & Fitness
- Support: https://skull.nz/vitalibre-support.html
- Privacy: https://skull.nz/vitalibre-privacy.html
- Source: https://github.com/antidamage/vitalibre
- Audience: adults; the app offers no child-directed features or advertising
- Purchase model: free app, three optional repeatable support payments, no unlocked content

## Apple App Store

- Subtitle: Camera pulse and reading log
- Promotional text: Read your pulse with your phone camera, keep readings on your device, and learn how the estimate works.
- Description:

  VitaLibre uses your phone camera to estimate heart rate from changes in light at your fingertip. It shows the pulse trace and saves readings on your device. You can review, star, delete, and share readings you choose.

  The app also displays an experimental blood-pressure estimate. It is based on a population starting point, pulse-shape features, and any cuff calibration or resting pressure you enter. It has not been clinically validated. Do not use it to make medical decisions; use a validated cuff for an accurate blood-pressure reading.

  VitaLibre has no account, ads, analytics, subscription, or locked features. Three optional support payments help fund development and change nothing in the app.

  Not a medical device. The app does not diagnose, treat, cure, or prevent any condition. Consult a qualified health professional for medical advice.

- Keywords: pulse,heart rate,readings,wellness,photoplethysmography
- Review notes: All features are available without purchase or login. A finger placed over the rear camera is needed for a real scan; the app reports errors rather than a fabricated reading when the signal is insufficient. The BP value is experimental and unvalidated, uses a baseline and optional cuff calibration, and is not intended for diagnosis or treatment. The three IAPs are repeatable optional support; they grant no digital entitlement. The privacy policy explains local processing and store payments.

## Google Play

- Short description: Camera pulse readings and an experimental BP estimate, saved on your device.
- Full description:

  VitaLibre estimates your heart rate from light changes at your fingertip using your phone camera. Follow the live pulse trace, save readings on your device, review past results, and share one only when you choose.

  An experimental blood-pressure estimate is also shown. It starts from general values and can incorporate a validated cuff reading or your usual resting pressure. It has not been clinically validated. For an accurate blood-pressure reading, use a validated cuff.

  There is no account, advertising, analytics, subscription, or paywall. Everything works without a payment. You can make one of three optional, repeatable support payments through Google Play; none unlocks anything.

  This app is not a medical device and does not diagnose, treat, cure, or prevent any medical condition. Consult a healthcare professional for medical advice, diagnosis, or treatment. Do not use its readings to make medical decisions.

  VitaLibre needs a rear camera to read a fingertip pulse. A flash may help on compatible devices. Camera images are processed on your device and are not uploaded to the developer. Results depend on device camera and flash capability, lighting, skin contact, motion, and other factors; some devices cannot produce a usable reading.

- App access: no login or restricted area
- Advertising: none

## Payment products, each store

| US amount | Name | Description | Type |
| --- | --- | --- | --- |
| $1.00 | Support VitaLibre — $1 | Optional support for VitaLibre development. Unlocks nothing. | Consumable / repeatable |
| $10.00 | Support VitaLibre — $10 | Optional support for VitaLibre development. Unlocks nothing. | Consumable / repeatable |
| $50.00 | Support VitaLibre — $50 | Optional support for VitaLibre development. Unlocks nothing. | Consumable / repeatable |

Use the exact product identifiers already in `publisher/config/store.config.json`. Localize prices through each store from the US amounts. Do not configure introductory offers or subscriptions.

## Launch disclosures to verify in each console

- Health apps: camera pulse and experimental BP; no claim of validated BP measurement. Google Health apps declaration and public privacy URL required.
- Data: readings and camera processing remain on device; iOS system backups may include app data; Android app backup is disabled. The chosen share target receives only user-selected content. The store handles payment information and may supply sales reports without health readings.
- Permissions: camera for a scan; Android also declares vibration and Play Billing. No location, contacts, Health Connect, advertising ID, or account permission is requested by the app.
- Ratings, export declarations, country exclusions, pricing tiers, payout and tax settings must be verified against the actual console forms and publisher facts before marking ready.
