# Core common module ProGuard rules

# Keep FFmpegBridge and related classes
-keep class com.rawsmusic.core.common.ffmpeg.FFmpegBridge { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep model classes
-keep class com.rawsmusic.core.common.model.** { *; }
