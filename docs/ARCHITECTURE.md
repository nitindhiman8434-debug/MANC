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

## 5. The engine (v2)

16 kHz mono, 256-sample frames, 128-sample hop (8 ms), 50% overlap-add. One shared
pure-Kotlin pipeline, `CleanChain`, runs identically in the app, the unit tests and the
offline harness:

```
[GTCRN neural denoiser, when bundled]
   -> STFT -> noise estimate (minimum statistics + VAD-gated) -> VAD
   -> spectral denoise gain (fallback only when no neural denoiser)
   -> voice features on the DENOISED signal: robust 16-band shape + harmonic-sieve pitch
   -> owner similarity (model-free voiceprint, blended 50/50 with the neural speaker
      embedder when bundled) -> voice-lock gate (hard mute) -> ISTFT -> limiter
```

Design decisions that came out of measurement (see §9 for the numbers):

- **Noise estimate = minimum statistics.** A VAD-gated estimator alone deadlocks: a stale
  estimate makes new noise look like speech, so it is never learned. Tracking the floor of
  sub-window minima over ~1.3 s learns background regardless of the VAD, and cannot learn
  sustained speech (speech has valleys). A freeze flag suspends it during enrollment.
- **Features on the denoised signal.** Pitch/timbre computed on raw audio collapse in noise.
- **Pitch by harmonic sieve** (comb minus anti-comb on a log-compressed 1024-pt spectrum),
  noise-robust and alias-resistant; the speaker match is additionally **octave-tolerant**
  because every tracker makes octave errors in noise.
- **Fail-open safety.** Someone who is clearly speaking is never hard-muted merely because
  identity evidence is missing. Hard mute is for silence/background, or a bystander the
  voiceprint can positively tell apart. The user's own voice is the top priority.
- **Hard mute** (gate closed -> true silence) with full-strength defaults (strength 1.0).
- **Neural evidence is asymmetric.** The speaker embedder can confirm the owner (open the
  gate when the voiceprint is unsure) or reinforce muting when the voiceprint also says
  "not the owner"; a low neural score alone never mutes the owner. Measured reason: on
  noisy input the embedding of the owner can collapse to the same score as a stranger.
- **Start-up warm-up** (first 1.5 s): the noise floor is seeded fast and fail-open is off,
  so background does not leak before the estimator has learned it.
- **AGC + limiter**: the owner's level is driven slowly toward about -18 dBFS while they
  speak, so the caller hears a consistently loud, clear voice without clipping.
- **Device safety in the bridge**: the "clean everywhere" injector excludes the bridge's own
  UID (otherwise it would capture its own injected stream: a feedback loop), and the shell
  process is given a real Application so attribution resolves on Android 14-17.

### Neural path validated offline (real models through the app's own Kotlin)

| Component | Result on the JVM (x86-64) |
|-----------|-----------------------------|
| GTCRN denoiser (`OnnxDenoiser`) | loads, healthy, real-time factor ~0.09; background-only input -31 dB |
| wespeaker CAM++ (`OnnxVoiceEmbedder`, kaldi fbank) | loads, 27 ms per 1 s window; calibrated owner 0.79, strangers/noise ~0.35 |

Caveat: on synthetic harmonic "voice" GTCRN also attenuates the voice (~-8 dB) and the
noisy-owner embedding collapses; both models were trained on real speech, so the on-device
test with a real voice is the deciding measurement. The `Neural depth` control (wet/dry)
exists so this can be tuned on the phone without a rebuild.

## 5b. Hands-free operation (automatic on calls)

A `specialUse` foreground service stands by whenever protection is on. It listens to
the global audio mode (`AudioManager.OnModeChangedListener`, Android 12+): every VoIP
app and the telephony stack switch the mode to RINGTONE / IN_CALL / IN_COMMUNICATION
for the duration of a call and back to NORMAL afterwards, so the pipeline (bridge mic
+ injector + engine) is started at ring time and torn down at hang-up, for any app,
without the user opening MANC. Older releases fall back to telephony call state
(cellular only). A boot receiver restores standby after a reboot; Shizuku itself
must be started once per boot (Android restriction), and the notification says so.

Because the Shizuku bridge is the only process that records, the app needs no
`RECORD_AUDIO` and no microphone-type foreground service, which removes Android
14+'s while-in-use restrictions on background-started services.

Known race: the injector policy applies to recorders opened after it is registered.
Registering at ring/mode-change time precedes the call app's microphone open in
WebRTC-based apps (mode is set in audio init, before the record starts), so the
window is milliseconds; a persistent policy would require a permanently open
microphone and was rejected.

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

- **Heavy noise with no neural model.** At about -10 dB SNR (noise three times louder than
  the voice) the model-free VAD cannot separate speech from background, so the fallback
  path keeps the gate closed when the owner starts talking into learned noise. This is the
  case the bundled GTCRN denoiser exists for: it cleans the signal before the chain, and
  the neural speaker embedder gives identity evidence the heuristic cannot. Without
  models, expect reliable behaviour down to roughly 0 dB SNR (measured), not below.
- VoIP injection needs Android 13+ and the shell bridge; Android 16 is confirmed in the
  wild, 17 is expected to work but is less proven without root.
- Some OEM ROMs vary in audio-policy behaviour; the injector builds per-preset mixes and
  falls back from UID-matching to preset-only. "Clean everywhere" injects into every
  recorder (voice assistants and recorders included) by design.
- Cellular is device-gated (see §7); the app probes and reports instead of pretending.
- Shizuku must be restarted after a reboot (an Android restriction); Google has discussed
  restricting on-device ADB, which could affect Shizuku in future.
- The earbuds remain the quality ceiling (§2).

## 9. Measured results (offline harness, synthetic speech with word gaps, 16 kHz)

Model-free path (no ONNX models), strength 0.85, hard mute, realistic order
(background first, then the owner starts talking, then a bystander):

| Scenario (SNR ~0 dB)              | Result    | Gate open |
|-----------------------------------|-----------|-----------|
| Background before anyone speaks   | -93 dB    | 0 %       |
| Owner starts talking into noise   | -7 dB (noise stripped, voice kept) | 85 % |
| Bystander (different voice) talks | -44 dB    | 0 %       |
| Background afterwards             | -93 dB    | 0 %       |
| Owner, clean room                 | +1 dB (preserved) | 81 % |

At SNR ~-10 dB the model-free path mutes the owner (see §8); the neural denoiser is the
remedy. All numbers are reproduced by `EngineChainTest` on every CI run.

## 10. Test protocol (on the device)

1. **Reality check:** record yourself in a noisy place through the P40i; run the engine
   offline and A/B the before/after. This measures how much survives the earbuds.
2. **VoIP end-to-end:** start cleaning, target WhatsApp, call a second phone, have a
   bystander talk; confirm the far end hears you and not them. Measure added latency.
3. **Cellular:** run the Cellular Lab during an active call; believe the verdict.
