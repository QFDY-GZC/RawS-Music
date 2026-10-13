# Core common module ProGuard rules

# Keep FFmpegBridge and related classes
-keep class com.rawsmusic.core.common.ffmpeg.FFmpegBridge { *; }
-keep class com.rawsmusic.core.common.sacd.SacdIsoBridge { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep model classes
-keep class com.rawsmusic.core.common.model.** { *; }

# ONNX Runtime Java classes are loaded through RawSMusic's reflection-isolated adapter.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
