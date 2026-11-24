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

## Install (once)

1. Install the MANC APK (see Releases) and open it. Protection turns on by itself.
2. The "Get ready" screen walks you through Shizuku: **Install** (Play Store) →
   **Open Shizuku** and tap Start → **Allow**. The bridge connects automatically.
3. Tap **Record** and talk for 5 seconds so the mic opens only for you.

That is all. The app never asks for microphone access: the Shizuku bridge captures
the microphone, the app only processes audio.

### "App not installed" on a Samsung phone

Samsung's installer shows this generic message for every failure. On Galaxy phones
that shipped with One UI 6.1.1 or later (Z Fold 7, S25, ...) the usual cause is
**Auto Blocker**, which is on by default and silently rejects any app that does not
come from the Play Store or Galaxy Store:

1. **Settings → Security and privacy → Auto Blocker → Off** (turn it back on after
   installing if you like; updates of an installed app are then still blocked, so
   switch it off again for each update).
2. If an older MANC build is already on the phone, **uninstall it first**. Builds
   before November 2026 were signed with a one-off key, so Android refuses to update
   them in place. Every CI build is now signed with the same committed debug key and
   updates in place from here on.
3. Download the APK again (about 100 MB; a cut-off download also fails this way) and
   open it from Chrome's downloads or **My Files**. If a "Safe Install" warning
   appears, choose **Install anyway**.

If it still fails, install **SAI (Split APKs Installer)** from the Play Store and
install the APK through it: it shows the real reason (for example
`INSTALL_FAILED_VERIFICATION_FAILURE` means a blocker, `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
means an older build with a different key is still installed).

## Use

Nothing to do. Protection stands by in the background and **starts by itself the
moment a call connects** (WhatsApp, Zoom, Meet, Telegram, Discord, Teams, or a
cellular call where the phone allows it) and stops when the call ends. It comes
back after a reboot; you only start Shizuku once after rebooting (the notification
tells you when). Advanced knobs (strength, neural depth, limit to one app, a
"run now" test switch, Cellular Lab) are under **Advanced** on the Home screen.

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
