# MANC Voice — deep audit and architecture

This document is both the engineering audit of the problem and the design of the app
that addresses it. It is deliberately honest about what is possible on Android and
what is not.

## 1. The problem, stated precisely

On a call, the person on the other end hears background noise and nearby voices louder
than the wearer, and the wearer's own voice is suppressed or drops out. The user blamed
the earbuds' noise cancellation. That is the wrong layer.

Active Noise Cancellation (ANC) only changes what the **wearer** hears. What the
**far end** hears is produced by the earbuds' *call* microphone array and its uplink
noise suppressor (ENC/CVC), then the Bluetooth link, then the phone, then the network.
ANC has no part in it.

## 2. Signal chain and where the damage happens

```
mouth + room ─► earbud call mics ─► earbud uplink DSP (ENC) ─► Bluetooth HFP/LE
      ─► phone audio HAL ─► calling app / modem ─► network ─► far end
```

Two independent failures, both upstream of anything an app can normally touch:

1. **Earbud ENC over-suppression.** In loud places the earbud's own suppressor treats
   the wearer's speech as noise and gates it. Budget earbuds (e.g. Soundcore P40i) have
   weak, non-personalised suppression. Whatever it deletes is gone before the phone.
2. **Bluetooth HFP narrowband.** Hands-free call audio is mono and often 8–16 kHz, so
   detail is lost in the link itself.

Conclusion: the quality ceiling is set by the earbuds and the link. Software **after**
that point can clean what survives and protect it, but cannot restore deleted phonemes.
This is why the app processes the phone-side stream and does not pretend to be perfect.

## 3. Why "an app on the phone" is constrained

- **Cellular (PSTN) uplink** runs through the modem/telephony HAL. A normal app cannot
  read or replace it. The only official hook is `CALL_AUDIO_INTERCEPTION`
  (`protectionLevel=signature|privileged|role`) plus the platform actually exposing
  `TELEPHONY_TX`/`TELEPHONY_RX` audio devices (`AudioManager.isPstnCallAudioInterceptable()`).
  Most consumer phones route cellular audio inside the modem and expose neither.
- **VoIP apps** (WhatsApp, Zoom, Meet, Telegram, Discord) each open the microphone
  themselves; you cannot edit their input from an unprivileged app.
- **No virtual microphone** is available to ordinary apps.

What changes the picture: the Android **shell** user (UID 2000, `com.android.shell`)
*does* hold the needed permissions — verified against AOSP `packages/Shell/AndroidManifest.xml`:
`MODIFY_AUDIO_ROUTING`, `CAPTURE_AUDIO_OUTPUT`, `CAPTURE_MEDIA_OUTPUT`,
`CAPTURE_VOICE_COMMUNICATION_OUTPUT`, `CALL_AUDIO_INTERCEPTION`, `MODIFY_PHONE_STATE`.
**Shizuku** lets a normal app run a small service *as that shell user* after a one-time,
user-granted activation (on-device wireless debugging on Android 11+, no PC, no root).

So the design is: keep the heavy audio work in the app, and do only the privileged
audio-routing in a shell-side **bridge** started through Shizuku.

## 4. The virtual-microphone mechanism (VoIP path — the reliable one)

Android's audio policy framework can register a **loopback injector** mix
(`AudioMixingRule` + `AudioMix(ROUTE_FLAG_LOOP_BACK)` + `AudioPolicy`), then obtain an
`AudioTrack` from `AudioPolicy.createAudioTrackSource(mix)`. When a target app records,
it reads from that injected track instead of the device microphone — a true virtual mic.

Verified against the real Android 16 and 17 framework (`android.media.audiopolicy.*`,
decompiled from the platform `android.jar`): `MIX_ROLE_INJECTOR`, `RULE_MATCH_UID (4)`,
`RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET (2)`. `RULE_MATCH_UID` is neither a "player" nor a
"recorder" rule, so it composes with a capture-preset rule under the injector role. We
match the target app by **UID AND capture preset** (VOICE_COMMUNICATION, MIC, DEFAULT,
VOICE_RECOGNITION), which is exactly how the OS builds its own call-injection track in
`AudioManager.getCallUplinkInjectionAudioTrack()`.

Data flow:

```
bridge(shell): real mic ──pipe──► app: denoise + voice-lock ──pipe──► bridge(shell): injector track ──► target app
```

The bridge captures the mic as shell (so the VoIP app's record is free to be the
injected virtual source), the app does the DSP, and the bridge injects the clean result
into the target UID. Audio crosses process boundaries over `ParcelFileDescriptor` pipes,
not binder calls, so there is no per-frame IPC overhead.

## 5. The engine

16 kHz mono, 256-sample frames, 128-sample hop (8 ms), 50% overlap-add:

```
STFT ─► noise estimate ─► VAD ─► spectral denoise gain
     ─► voice features ─► owner similarity ─► voice-lock gate ─► combine ─► ISTFT ─► limiter
```

- **Denoise:** decision-directed Wiener suppressor (pure Kotlin, always on). An ONNX
  denoiser (e.g. GTCRN) can be dropped in via the model slot.
- **Voice-lock (the differentiator):** opens the mic only for the enrolled owner. A
  model-free voiceprint (f0 range + 16-band log-spectrum shape) gives a per-frame
  similarity; an optional ONNX speaker embedder (wespeaker/3D-Speaker CAM++) refines it
  as a slow prior. The gate is a dual-threshold hysteresis with hangover and
  asymmetric attack/release.

### Why the voice-lock matters

Generic denoisers keep *all* speech, so a bystander talking next to you still passes.
The voice-lock is what delivers "send only my voice". Measured on a 60 s turn-taking
simulation (ported verbatim into the unit test `VoiceLockGateTest`):

| Signal                | Result          |
|-----------------------|-----------------|
| Owner speech retained | ~0.0 dB (full)  |
| Bystander speech      | about -22 dB    |
| Sustained background  | about -42 dB    |
| Mic-open latency (p90)| ~12 ms          |

A `strength` slider interpolates from calm to aggressive muting.

## 6. Latency budget

| Stage                         | Budget (ms) |
|-------------------------------|-------------|
| STFT window/overlap           | ~16         |
| Pipe hops + block (20 ms)     | ~20–30      |
| Target (our added delay)      | ≤ 60–80     |
| Hard ceiling before awkward   | ITU-T G.114 one-way 400 |

Bluetooth HFP already adds ~100–200 ms; we keep our own addition small and measurable.

## 7. Cellular path (experimental, honest)

The bridge holds `CALL_AUDIO_INTERCEPTION`, so permission is not the blocker — device
capability is. `CallInterceptProbe` reads `isPstnCallAudioInterceptable()` and the
telephony device list and returns a verdict. Only when the device reports support does
`CellularRedirectEngine` obtain `getCallUplinkInjectionAudioTrack()` and feed it the
cleaned voice. On devices that route cellular audio in the modem (most, including many
Samsung models per public scrcpy testing), the probe returns NOT_SUPPORTED and the app
says so rather than failing silently. The Cellular Lab screen surfaces this to the user.

## 8. Limitations and risks

- VoIP injection needs Android 13+ and the shell bridge; Android 16 is confirmed in the
  wild, 17 is expected to work but is less proven without root.
- Some OEM ROMs vary in audio-policy behaviour; the injector builds per-preset mixes and
  falls back from UID-matching to preset-only.
- Shizuku must be restarted after a reboot (an Android restriction).
- Google has discussed restricting on-device ADB, which could affect Shizuku in future.
- The earbuds remain the quality ceiling (section 2).
- The ONNX models are optional; without them the app uses the model-free path.

## 9. Test protocol (on the device)

1. **Reality check:** record yourself in a noisy place through the P40i; run the engine
   offline and A/B the before/after. This measures how much survives the earbuds.
2. **VoIP end-to-end:** start cleaning, target WhatsApp, call a second phone, have a
   bystander talk; confirm the far end hears you and not them. Measure added latency.
3. **Cellular:** run the Cellular Lab during an active call; believe the verdict.
