# v5 — one app, pair once, then automatic

## The goal

Install one app. Do a one-time step. After that every call is protected, with any
earbuds, also after a restart, without opening anything.

## The wall

Android forbids a normal app from changing the microphone audio that *another* app
(WhatsApp, Zoom, the dialer) records. The permissions that allow it
(`MODIFY_AUDIO_ROUTING`, `CAPTURE_AUDIO_OUTPUT`, ...) belong only to:

1. **OEM-signed system apps** — needs Samsung. Out of reach.
2. **The shell user (UID 2000)** — reachable through ADB / wireless debugging.
3. **root** — riskier and harder for a normal user than anything below.

There is no permission-free trick. "No-root virtual microphone" apps play their output
into a call *playback* stream, which other apps do not read as microphone input. An
earbud companion app (Soundcore etc.) only controls the earbuds' own settings. So door 2
stays; what can go is the second app that used to open it.

## What changed

Until v4 the shell user came from the Shizuku app: install it, start it through
wireless debugging, grant MANC, and start it again after every reboot.

v5 does the same thing itself:

```
one-time                        after every boot (automatic, ~10 s, Wi-Fi needed)
MANC ── pair(6-digit code) ──►  MANC: wireless debugging ON → find port → connect
        phone's own adbd              → run one shell command → disconnect → OFF

the shell command (as UID 2000):
  pm grant <pkg> WRITE_SECURE_SETTINGS        # lets MANC flip wireless debugging itself next time
  cmd deviceidle whitelist +<pkg>             # keep the standby service alive
  CLASSPATH=<apk> setsid app_process /system/bin --nice-name=manc_voice_bridge \
      com.manc.voice.server.BridgeServer <pkg> <versionCode> <userId> &

BridgeServer (detached helper) ── Binder(IVoiceBridge) via BridgeProvider.call() ──► MANC
   hosts the unchanged VoiceBridgeService: ShellMic + AudioReflect injector + cellular
```

Key properties:

- **ADB is only a launcher.** Audio never travels over ADB. Once the helper has handed
  over its Binder, wireless debugging and Wi-Fi are not needed, so calls on mobile data
  are protected.
- **The helper outlives ADB.** It starts in its own session and moves itself out of
  adbd's control group (init kills that group when adbd stops). If a phone still kills
  it when wireless debugging goes off, `AdbLauncher` notices once and from then on leaves
  wireless debugging on instead.
- **The helper finds the app again.** It holds a liveness token from the app and re-sends
  its Binder whenever the app process restarts. It exits when its APK disappears
  (uninstall or update); the updated app starts a fresh one.
- **Self-healing.** `VoiceService` relaunches the helper with backoff whenever the bridge
  is missing and no step needs the user.
- **Shizuku is optional.** `BridgeHub` prefers the built-in helper and uses a running
  Shizuku automatically if that is all there is (the only route on Android 10).

## Pieces

| File | Role |
|------|------|
| `adb/AdbManager.kt` | Embedded ADB client (libadb-android, Apache-2.0); key + certificate generated once |
| `adb/AdbLauncher.kt` | Pair, connect (mDNS, then loopback port sweep), run launch command, adaptive wireless-debugging toggle, diagnostics |
| `adb/LaunchLogic.kt` | Pure decisions: which step the user is at, retry backoff, the launch script, trusted-caller check (unit-tested) |
| `adb/PairingNotifier.kt` | Inline-reply notification for the pairing code |
| `server/BridgeServer.kt` | The detached shell-user helper |
| `server/BinderSender.kt` | Shell side of the Binder hand-over (`getContentProviderExternal` + `IContentProvider.call`) |
| `bridge/BridgeProvider.kt` | App side of the hand-over; accepts only shell or root |
| `bridge/BridgeHub.kt` | Single source of truth for "which bridge do we have" |

## What cannot be removed

- **Developer options + one pairing code.** That is the doorway itself.
- **Wi-Fi once after each reboot.** Android only offers wireless debugging on Wi-Fi. On a
  network it has not seen, Android asks once whether to allow wireless debugging there.
- **Developer options must stay on.** Turning them off stops the helper.
- Cellular uplink stays device-gated (modem routing), as before.

## Not yet proven on a device

This was built and unit-tested without a phone. The two places most likely to need a
follow-up on One UI 8 are the helper surviving the end of the ADB session and the
provider call from the shell process. Both report into *Home → Advanced → Connection
diagnostics*, which prints the launcher state, the last launch output and the helper's
own log.
