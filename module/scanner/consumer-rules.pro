# Scanner module ProGuard rules

# Keep scanner classes
-keep class com.rawsmusic.module.scanner.MediaStoreScanner { *; }
-keep class com.rawsmusic.module.scanner.FfmpegMetadataReader { *; }
-keep class com.rawsmusic.module.scanner.ScanManager { *; }
-keep class com.rawsmusic.module.scanner.ScannerViewModel { *; }

# Keep data classes used for scanning
-keep class com.rawsmusic.module.scanner.ScanProgress { *; }
-keep class com.rawsmusic.module.scanner.FfmpegMetadataReader$ExtendedTags { *; }
-keep class com.rawsmusic.module.scanner.FfmpegMetadataReader$AudioStreamInfo { *; }
-keep class com.rawsmusic.module.scanner.FfmpegMetadataReader$FullAudioInfo { *; }
-keep class com.rawsmusic.module.scanner.FfmpegMetadataReader$DsdInfo { *; }
