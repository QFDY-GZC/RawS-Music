# Data module ProGuard rules

# Keep repository classes
-keep class com.rawsmusic.module.data.repository.MusicRepository { *; }
-keep class com.rawsmusic.module.data.prefs.AppPreferences { *; }
-keep class com.rawsmusic.module.data.prefs.AppPreferences$* { *; }

# Keep MMKV
-keep class com.tencent.mmkv.** { *; }
-dontwarn com.tencent.mmkv.**

# Keep Gson
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**
