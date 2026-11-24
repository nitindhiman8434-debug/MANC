# Optional ONNX models

Drop models here to enable the neural upgrades. The app works without them.

- `speaker.onnx`  — speaker-embedding voice-lock (input float [1,T,80] log-mel,
   output float [1,D] embedding). wespeaker / 3D-Speaker CAM++ exports fit this.
- `denoiser.onnx` — streaming speech denoiser (slot reserved, e.g. GTCRN).

Files here are git-ignored by default (see .gitignore) so the repo stays small;
CI can fetch them as a build step if you wire it up.
