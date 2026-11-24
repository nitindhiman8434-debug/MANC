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

    int getProtocolVersion();
}
