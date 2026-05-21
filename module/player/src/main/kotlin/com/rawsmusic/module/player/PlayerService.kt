package com.rawsmusic.module.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.module.player.lyrics.BluetoothLyricBridge
import com.rawsmusic.module.player.lyrics.PlaybackTickerState
import com.rawsmusic.module.player.lyrics.PlayerServiceProxy
import com.rawsmusic.module.player.lyrics.TickerBridge
import com.rawsmusic.module.player.usb.UsbVolumeController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLDecoder

class PlayerService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "rawsmusic_player_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY = "com.rawsmusic.action.PLAY"
        const val ACTION_PAUSE = "com.rawsmusic.action.PAUSE"
        const val ACTION_NEXT = "com.rawsmusic.action.NEXT"
        const val ACTION_PREVIOUS = "com.rawsmusic.action.PREVIOUS"
        const val ACTION_STOP = "com.rawsmusic.action.STOP"
        const val ACTION_UPDATE = "com.rawsmusic.action.UPDATE"
        const val ACTION_UPDATE_LYRICS = "com.rawsmusic.action.UPDATE_LYRICS"
        const val ACTION_ENSURE_WAKELOCK = "com.rawsmusic.action.ENSURE_WAKELOCK"

        var isRunning = false
            private set

        private var _instance: PlayerService? = null

        private var cachedGetTokenMethod: java.lang.reflect.Method? = null
        private var cachedMTokenField: java.lang.reflect.Field? = null
        private var reflectionCacheInitialized = false
            private set

        /** 当前歌词数据 — 由MainActivity加载后设置 */
        private val _currentLyrics = MutableStateFlow<LyricData?>(null)
        val currentLyrics: StateFlow<LyricData?> = _currentLyrics.asStateFlow()

        /** 更新当前歌词 — 供外部（MainActivity）调用 */
        fun updateLyrics(lyricData: LyricData?) {
            _currentLyrics.value = lyricData
        }

        fun pushLyricsToMediaSession() {
            _instance?.updateLyricsInMetadata()
        }
    }

    private var mediaSessionCompat: MediaSessionCompat? = null
    private var currentSong: AudioFile? = null
    private var currentPlayState: PlayState = PlayState.IDLE
    private var coverBitmap: Bitmap? = null
    /** 记录当前正在加载封面的 albumArtPath，防止竞态 */
    private var loadingArtPath: String? = null
    /** 上次收到的播放位置，用于通知栏进度条更新 */
    private var lastKnownPosition: Long = 0L
    /** 上次收到精确位置时的系统时间，用于估算当前播放位置 */
    private var lastPositionTime: Long = 0L
    /** 播放位置更新协程 */
    private var positionUpdateJob: kotlinx.coroutines.Job? = null
    /** WakeLock — 防止CPU休眠导致后台播放被杀 */
    private var wakeLock: PowerManager.WakeLock? = null
    /** USB 独占播放专用 WakeLock — 在 usbExclusiveMode 期间持有，防止 OTG 被系统关闭 */
    private var usbWakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        _instance = this

        // Service 持有 PlayerController 单例，避免 UI 层重建导致重复创建
        val controller = PlayerController.getInstance(this)
        // Service 启动时扫描一次已连接的 USB 音频设备
        controller.scanForUsbDevice()

        // 获取 WakeLock 防止后台播放被杀
        acquireWakeLock()

        createNotificationChannel()

        // 创建MediaSessionCompat用于通知栏和系统媒体控制
        mediaSessionCompat = MediaSessionCompat(this, "RawSMusic").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    val ctrl = PlayerController.getInstance(this@PlayerService)
                    ctrl.resume()
                    notifyActivity(ACTION_PLAY)
                }
                override fun onPause() {
                    val ctrl = PlayerController.getInstance(this@PlayerService)
                    ctrl.pause()
                    notifyActivity(ACTION_PAUSE)
                }
                override fun onSkipToNext() {
                    val ctrl = PlayerController.getInstance(this@PlayerService)
                    ctrl.next()
                    notifyActivity(ACTION_NEXT)
                }
                override fun onSkipToPrevious() {
                    val ctrl = PlayerController.getInstance(this@PlayerService)
                    ctrl.previous()
                    notifyActivity(ACTION_PREVIOUS)
                }
                override fun onStop() {
                    val ctrl = PlayerController.getInstance(this@PlayerService)
                    ctrl.stop()
                    notifyActivity(ACTION_STOP)
                }
                override fun onSeekTo(pos: Long) {
                    val ctrl = PlayerController.getInstance(this@PlayerService)
                    ctrl.seekTo(pos)
                    lastKnownPosition = pos
                    updateMediaSessionPlaybackState(currentPlayState, pos)
                    PlayerEventBus.emit("com.rawsmusic.action.SEEK", pos)
                }
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val ev = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (ev?.action == KeyEvent.ACTION_DOWN) {
                        val ctrl = PlayerController.getInstance(this@PlayerService)
                        if (ctrl.isUsbExclusiveActive() && ctrl.canControlUsbVolume()) {
                            when (ev.keyCode) {
                                KeyEvent.KEYCODE_VOLUME_UP -> {
                                    ctrl.stepUsbVolume(UsbVolumeController.DEFAULT_STEP)
                                    return true
                                }
                                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                                    ctrl.stepUsbVolume(-UsbVolumeController.DEFAULT_STEP)
                                    return true
                                }
                                KeyEvent.KEYCODE_VOLUME_MUTE -> {
                                    ctrl.setUsbVolumeLinear(0f)
                                    return true
                                }
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
            isActive = true
        }

        // 启动前台通知
        startForegroundCompat(NOTIFICATION_ID, buildNotification())

        TickerBridge.init(this)
        PlayerServiceProxy.setUpdateCallback {
            rebuildMetadataWithBluetoothLyric()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        intent?.action?.let { handleAction(it, intent) }
        // START_STICKY: 确保服务被杀后系统自动重启
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 应用从最近任务移除时，重启服务确保后台保活
        val restartIntent = Intent(this, PlayerService::class.java)
        val pendingIntent = PendingIntent.getService(
            this, 0, restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
        alarmManager?.set(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 1000,
            pendingIntent
        )
        super.onTaskRemoved(rootIntent)
    }

    private fun handleAction(action: String, intent: Intent) {
        when (action) {
            ACTION_PLAY -> {
                notifyActivity(action)
                PlayerController.getInstance(this).resume()
            }
            ACTION_PAUSE -> {
                notifyActivity(action)
                PlayerController.getInstance(this).pause()
            }
            ACTION_NEXT -> {
                notifyActivity(action)
                PlayerController.getInstance(this).next()
            }
            ACTION_PREVIOUS -> {
                notifyActivity(action)
                PlayerController.getInstance(this).previous()
            }
            ACTION_STOP -> {
                notifyActivity(action)
                PlayerController.getInstance(this).stop()
            }
            "com.rawsmusic.action.SYNC_POSITION" -> {
                // 从应用同步播放进度，避免通知栏进度条漂移
                val pos = intent.getLongExtra("position", 0L)
                if (pos > 0) {
                    lastKnownPosition = pos
                    lastPositionTime = SystemClock.elapsedRealtime()
                    if (currentPlayState == PlayState.PLAYING) {
                        updateMediaSessionPlaybackState(PlayState.PLAYING, pos)
                    }
                }
            }
            ACTION_UPDATE -> {
                // 从Intent读取歌曲信息并更新通知
                val title = intent.getStringExtra("title") ?: return
                val artist = intent.getStringExtra("artist") ?: ""
                val album = intent.getStringExtra("album") ?: ""
                val albumArtPath = intent.getStringExtra("albumArtPath") ?: ""
                val duration = intent.getLongExtra("duration", 0L)
                val state = intent.getIntExtra("playState", PlayState.IDLE.ordinal)
                val position = intent.getLongExtra("position", 0L)

                lastKnownPosition = position
                lastPositionTime = SystemClock.elapsedRealtime()

                val songChanged = currentSong?.albumArtPath != albumArtPath ||
                        currentSong?.title != title

                currentSong = AudioFile(
                    id = 0, path = "", title = title, artist = artist,
                    album = album, duration = duration, albumArtPath = albumArtPath
                )
                currentPlayState = PlayState.entries.getOrElse(state) { PlayState.IDLE }

                // 始终更新完整元数据+播放状态（解决切歌后封面不更新）
                updateMediaSessionMetadata(title, artist, album, albumArtPath, duration)

                // 显式更新播放状态，触发系统通知栏刷新元数据
                updateMediaSessionPlaybackState(currentPlayState, lastKnownPosition)

                // 播放中：启动位置定期更新（通知栏进度条）
                startPositionUpdates()
            }
            ACTION_UPDATE_LYRICS -> {
                // 歌词已更新，仅更新歌词和元数据（保留已有封面）
                updateLyricsInMetadata()
            }
            ACTION_ENSURE_WAKELOCK -> {
                // 确保 WakeLock 持有（切歌期间防止被系统挂起）
                acquireWakeLockIfNeeded()
            }
            "com.rawsmusic.action.ACQUIRE_USB_WAKELOCK" -> {
                acquireUsbWakeLock()
            }
            "com.rawsmusic.action.RELEASE_USB_WAKELOCK" -> {
                releaseUsbWakeLock()
            }
        }
    }

    /**
     * 通过广播通知MainActivity执行播放控制
     */
    private fun notifyActivity(action: String) {
        PlayerEventBus.emit(action)

        // 本地状态更新 — NEXT/PREVIOUS 不在此更新播放状态
        // 等待 MainActivity 通过 ACTION_UPDATE 回传真实状态
        when (action) {
            ACTION_PLAY -> {
                currentPlayState = PlayState.PLAYING
                acquireWakeLock()
                startPositionUpdates()
                updateMediaSessionPlaybackState(currentPlayState, lastKnownPosition)
                updateNotification()
            }
            ACTION_PAUSE -> {
                currentPlayState = PlayState.PAUSED
                positionUpdateJob?.cancel()
                // 暂停时保留WakeLock，避免蓝牙场景下被杀后台
                updateMediaSessionPlaybackState(currentPlayState, lastKnownPosition)
                updateNotification()
            }
            ACTION_NEXT, ACTION_PREVIOUS -> {
                acquireWakeLock()
                startPositionUpdates()
            }
        }
    }

    /**
     * 更新MediaSession的元数据（标题、艺术家、封面等）
     */
    private fun updateMediaSessionMetadata(title: String, artist: String, album: String, albumArtPath: String, duration: Long) {
        coverBitmap = null

        val lrcText = _currentLyrics.value?.let { lyrics ->
            if (!lyrics.isEmpty) buildLrcText(lyrics) else null
        }

        val displayArtist = BluetoothLyricBridge.currentDisplayArtist() ?: artist

        val metadata = MediaMetadataCompat.Builder().apply {
            putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayArtist)
            putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            if (!lrcText.isNullOrBlank()) {
                putString(MediaMetadataCompat.METADATA_KEY_GENRE, lrcText)
            }
        }.build()
        mediaSessionCompat?.setMetadata(metadata)

        // 先用无封面更新通知
        updateNotification()

        // 异步加载新封面
        if (albumArtPath.isNotBlank()) {
            loadCoverBitmap(albumArtPath)
        }

        // 强制更新播放状态，触发系统通知栏刷新元数据（尤其是封面）
        updateMediaSessionPlaybackState(currentPlayState, lastKnownPosition)
    }

    /**
     * 仅更新歌词到MediaSession元数据，保留已有封面（不清除coverBitmap）
     * 解决：自然切歌时歌词异步加载完成后，不清空已加载的封面
     */
    private fun updateLyricsInMetadata() {
        val song = currentSong ?: return
        val lrcText = _currentLyrics.value?.let { lyrics ->
            if (!lyrics.isEmpty) buildLrcText(lyrics) else null
        }

        val displayArtist = BluetoothLyricBridge.currentDisplayArtist() ?: song.artist

        val metadata = MediaMetadataCompat.Builder().apply {
            putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayArtist)
            putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
            putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
            coverBitmap?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
            if (!lrcText.isNullOrBlank()) {
                putString(MediaMetadataCompat.METADATA_KEY_GENRE, lrcText)
            }
        }.build()
        mediaSessionCompat?.setMetadata(metadata)
        updateNotification()
    }

    /**
     * 更新MediaSession的播放状态
     */
    private fun updateMediaSessionPlaybackState(state: PlayState, position: Long) {
        val (stateCompat, speed) = when (state) {
            PlayState.PLAYING -> PlaybackStateCompat.STATE_PLAYING to 1.0f
            PlayState.PAUSED -> PlaybackStateCompat.STATE_PAUSED to 0f
            PlayState.PREPARING -> PlaybackStateCompat.STATE_BUFFERING to 0f
            PlayState.STOPPED -> PlaybackStateCompat.STATE_STOPPED to 0f
            PlayState.IDLE -> PlaybackStateCompat.STATE_NONE to 0f
            PlayState.ERROR -> PlaybackStateCompat.STATE_ERROR to 0f
        }

        val playbackState = PlaybackStateCompat.Builder().apply {
            setState(stateCompat, position, speed, SystemClock.elapsedRealtime())
            setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP
            )
        }.build()
        mediaSessionCompat?.setPlaybackState(playbackState)
    }

    private fun rebuildMetadataWithBluetoothLyric() {
        val song = currentSong ?: return
        val lrcText = _currentLyrics.value?.let { lyrics ->
            if (!lyrics.isEmpty) buildLrcText(lyrics) else null
        }
        val displayArtist = BluetoothLyricBridge.currentDisplayArtist() ?: song.artist
        val metadata = MediaMetadataCompat.Builder().apply {
            putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayArtist)
            putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
            putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
            coverBitmap?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
            if (!lrcText.isNullOrBlank()) {
                putString(MediaMetadataCompat.METADATA_KEY_GENRE, lrcText)
            }
        }.build()
        mediaSessionCompat?.setMetadata(metadata)
        updateNotification()
    }

    /**
     * 构建LRC格式歌词文本
     */
    private fun buildLrcText(lyricData: LyricData): String {
        val lrcBuilder = StringBuilder()
        for (line in lyricData.lines) {
            if (line.timeStamp < 0) continue
            val ts = formatTimestamp(line.timeStamp)
            val endTime = if (line.endTime > 0) "<${formatTimestamp(line.endTime)}>" else ""
            lrcBuilder.append("[$ts]$endTime${line.text}")
            if (line.translation.isNotBlank()) {
                lrcBuilder.append(" / ${line.translation}")
            }
            lrcBuilder.append("\n")
        }
        return lrcBuilder.toString().trim()
    }

    private fun formatTimestamp(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val millis = (ms % 1000) / 10
        return String.format("%02d:%02d.%02d", minutes, seconds, millis)
    }

    /**
     * 异步加载封面Bitmap
     * 支持：content:// URI（含 albumart 高清提取）、文件路径
     */
    private fun loadCoverBitmap(albumArtPath: String) {
        loadingArtPath = albumArtPath
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val path = try { URLDecoder.decode(albumArtPath, "UTF-8") } catch (_: Exception) { albumArtPath }
                var bitmap: Bitmap? = null

                if (path.startsWith("file://")) {
                    val filePath = path.removePrefix("file://")
                    val file = File(filePath)
                    if (file.exists()) {
                        bitmap = decodeSampledFile(filePath, 512, 512)
                    }
                } else if (path.startsWith("content://") && path.contains("albumart")) {
                    // albumart URI — 优先从音频文件内嵌封面提取高清原图
                    bitmap = extractEmbeddedArtwork(path)
                    // 回退到 content URI 缩略图
                    if (bitmap == null) {
                        bitmap = loadFromContentUri(path, 512, 512)
                    }
                } else if (path.startsWith("content://")) {
                    // 其他 content URI
                    bitmap = loadFromContentUri(path, 512, 512)
                } else {
                    // 文件路径 — 尝试直接解码
                    val file = File(path)
                    if (file.exists()) {
                        bitmap = decodeSampledFile(path, 512, 512)
                    }
                    // 文件路径解码失败时，尝试作为内嵌封面从音频文件提取
                    if (bitmap == null && path.isNotBlank()) {
                        bitmap = extractEmbeddedFromAudioFile(path)
                    }
                }

                // 防竞态：如果歌曲已切换，丢弃旧封面的加载结果
                if (loadingArtPath != albumArtPath) return@launch

                if (bitmap != null) {
                    coverBitmap = bitmap
                    val song = currentSong ?: return@launch
                    val lrcText = _currentLyrics.value?.let { lyrics ->
                        if (!lyrics.isEmpty) buildLrcText(lyrics) else null
                    }
                    val displayArtist = BluetoothLyricBridge.currentDisplayArtist() ?: song.artist
                    val metadata = MediaMetadataCompat.Builder().apply {
                        putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                        putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayArtist)
                        putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
                        putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
                        putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                        if (!lrcText.isNullOrBlank()) {
                            putString(MediaMetadataCompat.METADATA_KEY_GENRE, lrcText)
                        }
                    }.build()
                    mediaSessionCompat?.setMetadata(metadata)
                    // 强制使用startForeground更新通知（确保封面图显示）
                    launch(Dispatchers.Main) {
                        try {
                            startForegroundCompat(NOTIFICATION_ID, buildNotification())
                        } catch (_: Exception) {
                            try { updateNotification() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * 从音频文件内嵌封面提取高清原图
     */
    private fun extractEmbeddedArtwork(albumArtUri: String): Bitmap? {
        val uri = Uri.parse(albumArtUri)
        val albumId = uri.lastPathSegment?.toLongOrNull() ?: return null

        // 查询该专辑的第一首音频文件路径
        val audioPath = queryFirstAudioPathForAlbum(albumId) ?: return null

        val ext = audioPath.substringAfterLast(".", "").uppercase()
        // WAV/DSF/DFF/AIFF 等格式：MediaMetadataRetriever 无法提取封面，使用 FFmpegKit
        if (ext in setOf("WAV", "DSF", "DFF", "AIFF", "AIF")) {
            return extractCoverWithFfmpeg(audioPath)
        }

        // 其他格式：使用 MediaMetadataRetriever
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(audioPath)
            val bytes = retriever.embeddedPicture ?: return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, 512, 512)
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (_: Exception) {
            // MediaMetadataRetriever 失败时回退到 FFmpegKit
            return extractCoverWithFfmpeg(audioPath)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * 使用 FFmpegKit 从音频文件提取嵌入封面
     */
    private fun extractCoverWithFfmpeg(audioPath: String): Bitmap? {
        return try {
            val coverFile = java.io.File(cacheDir, "albumart/cover_${audioPath.hashCode()}.jpg")
            val coverDir = coverFile.parentFile
            if (coverDir != null && !coverDir.exists()) coverDir.mkdirs()

            val ret = com.rawsmusic.core.common.ffmpeg.FFmpegBridge.extractCover(audioPath, coverFile.absolutePath)
            if (ret == 0 && coverFile.exists() && coverFile.length() > 1024) {
                val bitmap = decodeSampledFile(coverFile.absolutePath, 512, 512)
                coverFile.delete()
                bitmap
            } else {
                if (coverFile.exists()) coverFile.delete()
                null
            }
        } catch (_: Exception) { null }
    }

    /**
     * 查询指定专辑的第一首音频文件路径
     */
    private fun queryFirstAudioPathForAlbum(albumId: Long): String? {
        val projection = arrayOf(MediaStore.Audio.Media.DATA)
        val selection = "${MediaStore.Audio.Media.ALBUM_ID} = ?"
        val selectionArgs = arrayOf(albumId.toString())
        try {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * 从 content URI 加载封面（降采样到指定尺寸）
     */
    private fun loadFromContentUri(uriString: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val uri = Uri.parse(uriString)
        val inputStream = contentResolver.openInputStream(uri) ?: return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(inputStream, null, options)
        inputStream.close()
        val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, reqWidth, reqHeight)
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val inputStream2 = contentResolver.openInputStream(uri) ?: return null
        val bitmap = BitmapFactory.decodeStream(inputStream2, null, decodeOptions)
        inputStream2.close()
        return bitmap
    }

    private fun calculateSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var sampleSize = 1
        if (width > reqWidth || height > reqHeight) {
            val halfW = width / 2
            val halfH = height / 2
            while (halfW / sampleSize >= reqWidth && halfH / sampleSize >= reqHeight) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    /** 从文件路径降采样解码 */
    private fun decodeSampledFile(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, reqWidth, reqHeight)
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(path, decodeOptions)
        } catch (_: Exception) { null }
    }

    /** 从音频文件内嵌封面提取（albumArtPath可能是音频文件路径本身） */
    private fun extractEmbeddedFromAudioFile(audioPath: String): Bitmap? {
        val extensions = setOf("mp3", "flac", "m4a", "wav", "ogg", "aac", "wma", "ape", "opus", "dsf", "dff", "aiff")
        val ext = audioPath.substringAfterLast(".", "").lowercase()
        if (ext !in extensions) return null

        // WAV/DSF/DFF/AIFF：直接用 FFmpegKit
        if (ext in setOf("wav", "dsf", "dff", "aiff", "aif")) {
            return extractCoverWithFfmpeg(audioPath)
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(audioPath)
            val bytes = retriever.embeddedPicture ?: return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, 512, 512)
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        } catch (_: Exception) {
            return extractCoverWithFfmpeg(audioPath)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private var isForegroundStarted = false

    private fun updateNotification() {
        val notification = buildNotification()
        if (!isForegroundStarted) {
            startForegroundCompat(NOTIFICATION_ID, notification)
            isForegroundStarted = true
        } else {
            // 后续更新使用 notify()，避免 startForeground() 可能的封面图更新问题
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(id, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Music playback controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val song = currentSong
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val tickerText = PlaybackTickerState.tickerText
        val tickerTranslation = PlaybackTickerState.tickerTranslation
        val samsungTranslation = com.rawsmusic.module.data.prefs.AppPreferences.Lyrics.samsungFloatingLyricTranslation

        val displayContentText = if (samsungTranslation && tickerTranslation.isNotBlank() && tickerText.isNotBlank()) {
            "${song?.artist ?: ""} · $tickerTranslation"
        } else {
            song?.artist ?: "准备播放"
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(song?.title ?: "RawSMusic")
            .setContentText(displayContentText)
            .setSubText(song?.album)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(contentIntent)

        if (tickerText.isNotBlank()) {
            builder.setTicker(tickerText)
        }

        // 设置MediaStyle（通过session token关联MediaSession）
        mediaSessionCompat?.let { session ->
            val compatToken = session.sessionToken
            // 获取framework MediaSession.Token用于Notification.MediaStyle
            val frameworkToken = try {
                if (!reflectionCacheInitialized) {
                    reflectionCacheInitialized = true
                    try {
                        cachedGetTokenMethod = compatToken.javaClass.getMethod("getToken")
                    } catch (_: NoSuchMethodException) {
                        try {
                            val field = compatToken.javaClass.getDeclaredField("mToken")
                            field.isAccessible = true
                            cachedMTokenField = field
                        } catch (_: Exception) {}
                    }
                }
                val method = cachedGetTokenMethod
                if (method != null) {
                    method.invoke(compatToken) as? android.media.session.MediaSession.Token
                } else {
                    cachedMTokenField?.get(compatToken) as? android.media.session.MediaSession.Token
                }
            } catch (e: Exception) {
                Log.w("PlayerService", "Failed to get framework token", e)
                null
            }

            val mediaStyle = Notification.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
            if (frameworkToken != null) {
                mediaStyle.setMediaSession(frameworkToken)
            }
            builder.setStyle(mediaStyle)
        }

        // 添加媒体控制按钮
        val prevIntent = PendingIntent.getService(
            this, 0,
            Intent(this, PlayerService::class.java).setAction(ACTION_PREVIOUS),
            flags
        )
        builder.addAction(
            Notification.Action.Builder(
                null, "上一曲", prevIntent
            ).build()
        )

        val isPlaying = currentPlayState == PlayState.PLAYING
        if (isPlaying) {
            val pauseIntent = PendingIntent.getService(
                this, 1,
                Intent(this, PlayerService::class.java).setAction(ACTION_PAUSE),
                flags
            )
            builder.addAction(
                Notification.Action.Builder(
                    null, "暂停", pauseIntent
                ).build()
            )
        } else {
            val playIntent = PendingIntent.getService(
                this, 2,
                Intent(this, PlayerService::class.java).setAction(ACTION_PLAY),
                flags
            )
            builder.addAction(
                Notification.Action.Builder(
                    null, "播放", playIntent
                ).build()
            )
        }

        val nextIntent = PendingIntent.getService(
            this, 3,
            Intent(this, PlayerService::class.java).setAction(ACTION_NEXT),
            flags
        )
        builder.addAction(
            Notification.Action.Builder(
                null, "下一曲", nextIntent
            ).build()
        )

        // 设置封面
        coverBitmap?.let { builder.setLargeIcon(it) }

        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        _instance = null
        positionUpdateJob?.cancel()
        releaseWakeLock()
        releaseUsbWakeLock()
        TickerBridge.destroy(this)
        BluetoothLyricBridge.destroy()
        PlayerServiceProxy.setUpdateCallback(null)
        mediaSessionCompat?.isActive = false
        mediaSessionCompat?.release()
        mediaSessionCompat = null
    }

    /** USB 独占播放时获取专用 WakeLock，防止 OTG 被系统关闭 */
    fun acquireUsbWakeLock() {
        try {
            if (usbWakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            usbWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RawSMusic::UsbExclusivePlayback"
            ).apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L)
            }
            Log.d("PlayerService", "USB WakeLock acquired")
        } catch (_: Exception) {}
    }

    /** 释放 USB 专用 WakeLock */
    fun releaseUsbWakeLock() {
        try {
            if (usbWakeLock?.isHeld == true) {
                usbWakeLock?.release()
            }
            usbWakeLock = null
            Log.d("PlayerService", "USB WakeLock released")
        } catch (_: Exception) {}
    }

    /** 获取 WakeLock — 防止CPU休眠导致后台播放被系统杀死 */
    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RawSMusic::PlayerWakeLock"
            ).apply {
                acquire(4 * 60 * 60 * 1000L) // 最长4小时
            }
        } catch (_: Exception) {}
    }

    /** 公共方法 — 供 PlayerController 等外部调用，确保 WakeLock 持有 */
    fun acquireWakeLockIfNeeded() {
        if (wakeLock?.isHeld != true) {
            acquireWakeLock()
        }
    }

    /** 释放 WakeLock */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (_: Exception) {}
    }

    /** 播放中时定期更新通知栏进度条位置 — 使用elapsedRealtime估算+系统插值 */
    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        if (currentPlayState == PlayState.PLAYING) {
            positionUpdateJob = lifecycleScope.launch(Dispatchers.Main) {
                while (true) {
                    kotlinx.coroutines.delay(2000)
                    if (currentPlayState == PlayState.PLAYING) {
                        val elapsed = if (lastPositionTime > 0) SystemClock.elapsedRealtime() - lastPositionTime else 0L
                        val estimatedPosition = (lastKnownPosition + elapsed).coerceAtLeast(0L)
                        val duration = currentSong?.duration ?: 0L
                        if (duration > 0 && estimatedPosition <= duration) {
                            updateMediaSessionPlaybackState(PlayState.PLAYING, estimatedPosition)
                        }
                    }
                }
            }
        }
    }
}
