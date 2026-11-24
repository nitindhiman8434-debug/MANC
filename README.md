# MANC Voice

Clean, lock, and send only **your** voice on Android calls — works with any
earbuds, cleaning happens on the phone.

MANC captures your microphone, removes background noise, and (optionally) opens
the mic **only when it hears you**, so bystanders and room noise are muted. The
cleaned audio replaces your microphone inside the calling app. Everything runs
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

1. Pick your calling app (App calls mode) or open Cellular Lab (Cellular mode).
2. Tap **Teach the app my voice** and talk for ~5 seconds once (enables voice-lock).
3. Tap **Start cleaning**, then make your call.

## Build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/
```

CI builds the APK on every push and attaches it to the `ci-latest` prerelease.

## Optional neural models

The app ships a working model-free pipeline. To use neural upgrades, drop ONNX
models into `app/src/main/assets/models/`:

- `speaker.onnx` — a speaker-embedding model (wespeaker / 3D-Speaker CAM++ export)
  for a stronger voice-lock.
- `denoiser.onnx` — a streaming denoiser (e.g. GTCRN) [slot reserved].

See `docs/ARCHITECTURE.md` for the full engineering audit, the signal-chain
analysis, measured voice-lock numbers, and the latency budget.

## License

Apache-2.0. See `NOTICE` for third-party attributions.
