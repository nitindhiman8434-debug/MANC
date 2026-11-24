# MANC Voice

Clean, lock, and send only **your** voice on Android calls — works with any
earbuds, cleaning happens on the phone.

MANC captures your microphone, removes background noise with a neural denoiser,
and opens the mic **only when it hears you**, so bystanders and room noise are
fully muted. One tap: the cleaned audio replaces your microphone in **every**
app that uses it, and in cellular calls on phones that allow it. Everything runs
on-device. It is free and open source.

## What works where

| Call type | Status | How |
|-----------|--------|-----|
| WhatsApp, Zoom, Meet, Telegram, Discord, Teams (VoIP) | Supported (Android 13+) | Virtual microphone via a Shizuku shell bridge |
| Cellular (phone-network) calls | Device-dependent, experimental | Only if the phone exposes call audio to software — check in Cellular Lab |

Cellular cleaning is impossible on phones that route call audio inside the modem
(most of them). The app tells you honestly after a one-tap device check instead
of pretending.

## Why it needs Shizuku

Android does not let an ordinary app replace the microphone inside other apps.
The Android **shell** user does hold the required audio permissions. [Shizuku](https://shizuku.rikka.app)
lets MANC run a tiny bridge as that shell user after a one-time, on-device
activation (wireless debugging on Android 11+, no PC, no root). MANC itself never
holds those permissions; the bridge does, and only while you run it.

## Install

1. Install Shizuku and start it (on-device wireless debugging works without a PC).
2. Install the MANC APK (see Releases, or build below).
3. Open MANC → Setup → Grant Shizuku.
4. Grant microphone + notification access when asked.

## Use

1. Tap **Start cleaning** once. It cleans everywhere by default (all apps, plus
   cellular where the phone allows it) and auto-starts next time the bridge connects.
2. Tap **Teach the app my voice** and talk for ~5 seconds once (voice-lock).
3. Make your call. Advanced: limit cleaning to one app from the Home screen.

## Build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/
```

CI builds the APK on every push and attaches it to the `ci-latest` prerelease.

## Neural models

CI bundles two models into the APK (`app/src/main/assets/models/`):

- `denoiser.onnx` — GTCRN streaming denoiser (background removal, real-time on CPU).
- `speaker.onnx` — wespeaker CAM++ speaker embedder (robust "only my voice").

Both models were run end-to-end through the app's own code on the JVM before shipping.
The neural speaker score is advisory (it can never mute you on its own). If your
voice sounds thin, lower **Neural depth** in Controls. The app also works with no
models through a tuned model-free pipeline; measured results and limits are in
`docs/ARCHITECTURE.md` §5, §8-9.

See `docs/ARCHITECTURE.md` for the full engineering audit, the signal-chain
analysis, measured voice-lock numbers, and the latency budget.

## License

Apache-2.0. See `NOTICE` for third-party attributions.
