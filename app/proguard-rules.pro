# RawSMusic ProGuard Rules

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.rawsmusic.core.common.model.** { *; }
-keep class com.rawsmusic.module.data.db.** { *; }

# Gson specific rules
-keep class * implements com.google.gson.TypeAdapter { *; }
-keep class * implements com.google.gson.TypeAdapterFactory { *; }
-keep class * implements com.google.gson.JsonSerializer { *; }
-keep class * implements com.google.gson.JsonDeserializer { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep TypeToken and its subclasses
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }

# Keep Gson internal data classes used in PlaylistDao and other DAOs
-keep class com.rawsmusic.module.data.db.PlaylistDao$* { *; }
-keep class com.rawsmusic.module.data.db.SongDao$* { *; }
-keep class com.rawsmusic.module.data.repository.MusicRepository$* { *; }
-keep class com.rawsmusic.module.player.dsp.ParametricEQController$* { *; }
-keep class com.rawsmusic.module.data.repository.EqualizerRepository$* { *; }
-keep class com.rawsmusic.module.player.dsp.AutoEqPreset$* { *; }

# Kotlin Parcelize
-keep class * implements android.os.Parcelable { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# MMKV
-keep class com.tencent.mmkv.** { *; }
-dontwarn com.tencent.mmkv.**

# SAF / ActivityResult — 防止 release 版文件夹选择器失效
-keep class androidx.activity.result.** { *; }
-keep class androidx.activity.result.contract.** { *; }
-keep class com.rawsmusic.ui.settings.SettingsFragment { *; }
-keep class com.rawsmusic.ui.songs.SongsFragment { *; }

# JNI Native 方法
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.rawsmusic.module.player.dsp.NativeDSPEngine { *; }
-keep class com.rawsmusic.core.common.ffmpeg.FFmpegBridge { *; }

# Lyric module - 防止歌词视图被混淆导致崩溃
-keep class io.github.proify.lyricon.** { *; }
-keepclassmembers class io.github.proify.lyricon.** { *; }

# 保留自定义 View 类（只保留项目内的）
-keep class com.rawsmusic.core.ui.widget.** { *; }
-keep class com.rawsmusic.ui.widget.** { *; }

# PlayerService - 防止 MediaSession 封面传递被优化
-keep class com.rawsmusic.module.player.PlayerService { *; }
-keep class com.rawsmusic.module.player.PlayerService$* { *; }

# Compose
-dontwarn androidx.compose.**

# Kotlin Coroutines
-dontwarn kotlinx.coroutines.**
