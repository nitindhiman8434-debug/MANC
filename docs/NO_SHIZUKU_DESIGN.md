# v5 — one app, self-pairing (no separate Shizuku app)

## The goal the user keeps asking for

Install one app. Do one or two one-time steps. After that it just works on every
call, with any earbuds, and survives reboots without being reopened.

## The wall (why this is hard at all)

Android deliberately forbids a normal app from changing the microphone audio that
*another* app (WhatsApp, Zoom, the dialer) records. That is the app sandbox. To
replace another app's mic input you must hold privileged audio permissions
(`MODIFY_AUDIO_ROUTING`, `CAPTURE_AUDIO_OUTPUT`, ...), which Google only grants to:

1. **System / OEM-signed apps** — needs Samsung. Not available to us.
2. **The shell user (UID 2000)** — reachable through ADB / wireless debugging.
3. **root** — needs the phone rooted.

Root is *more* complex and risky for a normal user than wireless debugging, and OEM
signing is out of reach. So door #2 (shell UID via ADB) is the only sane path. There
is no zero-permission trick: "no-root virtual mic" apps route to a playback stream
that other apps do **not** read as microphone input, so they do not actually reach
WhatsApp. We verified this.

## What changes in v5

Until v4 the shell UID came from the **Shizuku app**: install Shizuku, start it via
wireless debugging, grant MANC permission, and reopen Shizuku after every reboot. Two
apps, several steps, repeated after reboot.

v5 removes Shizuku entirely. MANC embeds its own ADB client
([libadb-android](https://github.com/MuntashirAkon/libadb-android), Apache-2.0) and
pairs with the **phone's own wireless-debugging daemon** on `127.0.0.1`. That is the
same thing Shizuku does internally — we just do it in-process.

Result for the user:

- **One app.** No Shizuku, no second install, no permission hand-off.
- **Setup once:** turn on Wireless debugging, enter a 6-digit pairing code into MANC.
- **After reboot:** MANC finds the daemon again over mDNS and relaunches its bridge by
  itself. Nothing to open. (If the OEM turns wireless debugging off on reboot, the user
  flips one switch — still no app to open.)

This is the simplest "install and done" that is possible on an unrooted phone. The
one-time pairing cannot be removed: it is the security doorway itself.

## Architecture

```
 MANC app (UID 10xxx)                         shell server (UID 2000)
 ┌─────────────────────┐   ADB (127.0.0.1)    ┌──────────────────────────┐
 │ AdbManager (libadb) │◄───TLS, paired once──►│ adbd                      │
 │  - pair(code)       │                      │   │ launches via shell:    │
 │  - autoConnect mDNS │   shell: app_process │   ▼                        │
 │                     │─────────────────────►│ BridgeServer.main()        │
 │ AdbBridge           │                      │  - ShellContext (fake      │
 │  localabstract:ctl  │◄──── JSON status ────│    com.android.shell)      │
 │  localabstract:mic  │◄──── mic PCM ────────│  - ShellMic (real mic)     │
 │  localabstract:inj  │───── clean PCM ─────►│  - AudioReflect injector   │
 └─────────┬───────────┘                      └──────────────────────────┘
           │ mic PCM in / clean PCM out
           ▼
   AudioEngine (denoise + voice-lock + AGC)  ← unchanged from v4
```

- **Transport:** everything rides the one ADB connection as `localabstract:` streams.
  adbd bridges them to `LocalServerSocket`s the server opens in the abstract namespace.
  This avoids a direct shell-domain ↔ app-domain socket (SELinux would block that); it
  is exactly how `adb forward localabstract:` works.
- **The server** (`server/BridgeServer`) is launched with
  `CLASSPATH=<app.apk> app_process /system/bin com.manc.voice.server.BridgeServer <appId>`
  — the scrcpy pattern. It reuses the existing `ShellContext`, `ShellMic` and
  `AudioReflect` unchanged (they were already written to run as the shell UID), and
  touches no AndroidX so it loads cleanly under `app_process`.
- **Shizuku stays as an automatic fallback:** if Shizuku is already installed and
  running, MANC uses it; otherwise it uses the embedded ADB path. Nobody is broken.

## Honest limits

- Wireless debugging must be enabled once, and paired once. Unavoidable without root.
- On Android 10 (API 29) there is no wireless debugging; those devices still need the
  Shizuku fallback (or a PC). Fold 7 is far newer, so this is a non-issue for the user.
- Cellular-call uplink remains device-gated (the modem path); unchanged from before.
- The Soundcore P40i (or any earbuds) can't do this themselves — their companion app
  only controls the earbuds' own settings, not other apps' call audio. MANC cleans on
  the phone side, which is why it works with *any* earbuds.

## Staging

- **A:** deps + `AdbManager` + `BridgeProtocol` + `BridgeServer` + test. (this change)
- **B:** `AdbBridge` transport + a `Bridge` interface; wire `VoiceService` to prefer a
  live Shizuku bridge, else the embedded ADB bridge.
- **C:** self-pairing setup UI (deep-link to Wireless debugging, mDNS auto port, code
  entry), make Shizuku optional in first-run.
- **D:** docs + on-device test guide.
