# Keep Shizuku AIDL + provider
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
# Keep the bridge UserService entry points referenced by name via Shizuku
-keep class com.manc.voice.bridge.** { *; }
# Keep AIDL interfaces
-keep interface com.manc.voice.** { *; }
# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
# Reflection targets in android.media.* are system classes; nothing to keep locally.
