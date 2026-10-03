# App review notes

What the app does, how it does it, and how accurate it is, for store review (Apple App Review Guidelines
1.4.1 asks for the data and methodology behind health accuracy claims). Kept in step with
`ppg-vitals-app.md`; where the two differ, that spec wins.

## What it is

A free, open-source (GPL-3.0-or-later) app that reads the pulse from a fingertip held on the rear camera with the
torch on. It shows heart rate, a note when the pulse is irregular, and a blood-pressure estimate. It is not a
medical device, is not FDA-cleared or CE-marked, and says so on the first screen, in Settings and in About.
Readings stay on the device; nothing is sent anywhere, and there is no account.

## Method

- The green channel of a central square of each camera frame (about 30 fps) is averaged to one number per frame.
  Each heartbeat absorbs slightly more green light, so the pulse is about 1% of that number.
- The series is resampled, band-passed from 0.5 to 5 Hz forwards and backwards, and beats are found with the
  published two-moving-average detector (Elgendi et al., PLoS ONE 2013). Heart rate is the median of the accepted
  beat intervals.
- Once a second the camera feed itself is scored from measures that do not use the beats: finger coverage,
  clipping, light level, dropped frames, noise, jumps in the picture, and the phone's own movement and tremor from
  its motion sensors. A weak or uneven pulse does not lower the score. The user can leave poor stretches out of a
  reading; nothing is deleted.
- Blood pressure is a linear model over pulse-shape features (heart rate, interval spread, crest time, skewness,
  reflected-wave ratio), starting from a typical value for age and sex, or the person's own typical pressure, and
  adjusted by a small capped amount. A camera cannot measure blood pressure without a reference.

## What is shown, and when

- On iOS a blood-pressure figure is shown only while a cuff calibration is current: a paired reading from a real
  cuff, taken in the last 30 days. Without one the figure is worked out but not shown. A figure is never shown for
  an irregular pulse. Every figure carries its average error and a line saying that a normal-looking estimate does
  not rule out high blood pressure and that a cuff reading takes precedence.
- The app never says "arrhythmia". An irregular pulse is noted as such, with a suggestion to check with a cuff
  or a clinician when it repeats.
- The first scan opens "Before you measure": posture and hand support following the American Heart Association's
  statement on measuring blood pressure (Muntner et al., Hypertension 2019), plus camera-specific advice.

## Accuracy

- Heart rate: finger-camera heart rate is typically within about 2 bpm of ECG at rest (PMC5368348).
- Blood pressure without calibration: published calibration-free camera methods have a mean absolute error of
  about 13-16 mmHg systolic and 7-9 mmHg diastolic (doi 10.1038/s41746-022-00629-2), two to three times worse than
  the ISO 81060-2 criterion (mean difference within 5 mmHg, standard deviation within 8 mmHg). This app's model has
  not been validated against that standard.
- With three or more paired cuff readings the app reports the spread it actually saw against that person's cuff,
  which is in-sample and optimistic, and says so.
- Training: model version 1 is a prior and was not trained. A trained version was tried on open data (CP-PPG,
  BUT PPG, 4-wavelength) under subject-wise cross-validation and a held-out set, and did not beat the age and sex
  prior by the margin fixed in advance, so it is not shipped. The pulse-shape adjustment is capped at +/-8 mmHg
  systolic and +/-5 diastolic and is not shown to improve accuracy. Details: `ppg-vitals-app.md`.

## If the blood-pressure display is rejected

`iosBpDisplay` in `publisher/config/policy.json` is `calibrated` by default. Setting it to `never` removes the
figure from every iOS screen and share, and leaves every model blood-pressure value (raw, base and shown) out of the
validation export, while heart rate, notes and the cuff's own numbers keep working.
