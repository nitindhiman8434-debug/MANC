// AIDL interface exposed by the shell-privileged bridge process (started via Shizuku).
package com.manc.voice.bridge;

import android.os.ParcelFileDescriptor;

interface IVoiceBridge {
    // Basic liveness / environment info as a JSON string.
    String describeEnvironment();

    // Start the virtual-microphone path for a target app (by its UID).
    // The bridge captures the real microphone and returns a pipe:
    //   returned FD = read end the APP reads raw mic PCM from (16 kHz mono s16le).
    // The app processes it and writes clean PCM back through the FD given to openCleanSink().
    ParcelFileDescriptor startMicCapture(int sampleRate);

    // The app hands the bridge the read end of a pipe carrying CLEAN PCM.
    // The bridge injects it so that the target UID records it as its microphone.
    boolean startInjectionForUid(int targetUid, in ParcelFileDescriptor cleanPcmReadEnd, int sampleRate);

    // Match by capture preset instead of UID (fallback for apps whose record UID is hard to pin).
    boolean startInjectionForPreset(int capturePreset, in ParcelFileDescriptor cleanPcmReadEnd, int sampleRate);

    void stopInjection();
    void stopMicCapture();

    // ---- Cellular (experimental) ----
    // Runs a non-destructive probe and returns a JSON report of what the OS allows.
    String probeCellular();

    // Attempt the cellular redirect path. Returns a JSON result; never throws across binder.
    String startCellularRedirect(in ParcelFileDescriptor cleanPcmReadEnd, int sampleRate);
    void stopCellularRedirect();

    // Default mode: inject the cleaned voice into EVERY recorder (all call presets,
    // no UID filter) and, when the device allows it, into the cellular uplink too.
    // Returns JSON describing which sinks are active.
    String startCleanEverywhere(in ParcelFileDescriptor cleanPcmReadEnd, int sampleRate);

    int getProtocolVersion();

    // ---- Binder audio transport (protocol 2) ----
    // No file descriptor crosses the process boundary, so SELinux rules about
    // pipes between the shell domain and the app domain cannot get in the way.

    // Open the real microphone inside the bridge. Returns false if no configuration worked.
    boolean openMic(int sampleRate);

    // Blocks for about 20 ms and returns that much mic PCM (16-bit LE mono at the
    // requested rate). An empty array means the microphone is closed or failed.
    byte[] readMic();

    // Open where the cleaned voice goes. mode: 0 = every recorder (+ cellular when
    // allowed), 1 = one app (arg = its UID), 2 = one capture preset (arg = preset),
    // 3 = cellular uplink only. Returns JSON with at least "started".
    String openSink(int mode, int arg, int sampleRate);

    // Cleaned PCM (16-bit LE mono) for the sink opened above.
    void writeClean(in byte[] pcm);
}
