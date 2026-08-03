package com.rawsmusic.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.palette.graphics.Palette
import com.rawsmusic.MainActivity
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.decodeDefaultAlbumArtwork
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.PlayerService
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal object PlaybackWidgetUpdater {
    private const val WIDE_BACKGROUND_MAX_WIDTH_PX = 560
    private const val WIDE_BACKGROUND_MAX_HEIGHT_PX = 300
    private const val COMPACT_BACKGROUND_MAX_SIDE_PX = 380
    private const val FLOW_BACKGROUND_MAX_WIDTH_PX = 240
    private const val FLOW_BACKGROUND_MAX_HEIGHT_PX = 120
    private const val FLOW_FRAME_INTERVAL_MS = 16L
    private const val FLOW_LYRIC_TRANSITION_MS = 520L
    private const val WIDE_PROGRESS_INTERVAL_MS = 2_000L
    private const val MAX_ARTWORK_SIDE_PX = 220
    private const val WIDGET_PROGRESS_MAX = 1000

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RawS-PlaybackWidget").apply { isDaemon = true }
    }
    private val updateRunning = AtomicBoolean(false)
    private val updateAgain = AtomicBoolean(false)
    private val flowAnimatorStarted = AtomicBoolean(false)
    private val flowAnimationExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "RawS-FlowWidget").apply { isDaemon = true }
    }
    @Volatile
    private var flowAnimationSpec: FlowAnimationSpec? = null
    private var lastWideProgressUpdateElapsed = 0L
    @Volatile private var lastFlowLyricIdentity = ""
    @Volatile private var flowLyricDisplayedChild = 0
    @Volatile private var flowLyricAnimation: FlowLyricAnimation? = null

    fun requestUpdate(context: Context) {
        val appContext = context.applicationContext
        updateAgain.set(true)
        if (!updateRunning.compareAndSet(false, true)) return

        executor.execute {
            try {
                do {
                    updateAgain.set(false)
                    updateWidgets(appContext)
                } while (updateAgain.get())
            } finally {
                updateRunning.set(false)
                if (updateAgain.get()) requestUpdate(appContext)
            }
        }
    }

    fun requestProgressUpdate(context: Context) {
        val appContext = context.applicationContext
        executor.execute {
            val manager = AppWidgetManager.getInstance(appContext)
            val wideIds = manager.getAppWidgetIds(
                ComponentName(appContext, PlaybackWidgetProvider::class.java)
            )
            val flowIds = manager.getAppWidgetIds(
                ComponentName(appContext, FlowPlaybackWidgetProvider::class.java)
            )
            if (wideIds.isEmpty() && flowIds.isEmpty()) return@execute
            val snapshot = currentSnapshot()
            val now = SystemClock.elapsedRealtime()
            if (wideIds.isNotEmpty() && now - lastWideProgressUpdateElapsed >= WIDE_PROGRESS_INTERVAL_MS) {
                lastWideProgressUpdateElapsed = now
                wideIds.forEach { widgetId ->
                    val views = RemoteViews(appContext.packageName, R.layout.widget_playback_wide)
                    applyProgress(views, snapshot)
                    manager.partiallyUpdateAppWidget(widgetId, views)
                }
            }
            if (flowIds.isNotEmpty()) {
                val lyricRender = resolveFlowLyricChild(snapshot)
                if (lyricRender.changed) {
                    flowIds.forEach { widgetId ->
                        val views = RemoteViews(appContext.packageName, R.layout.widget_playback_flow)
                        applyFlowLyric(views, snapshot, lyricRender.displayedChild)
                        applyFlowLyricMotion(views, SystemClock.elapsedRealtime(), 1f)
                        manager.partiallyUpdateAppWidget(widgetId, views)
                    }
                }
            }
        }
    }

    private fun updateWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val wideIds = manager.getAppWidgetIds(
            ComponentName(context, PlaybackWidgetProvider::class.java)
        )
        val compactIds = manager.getAppWidgetIds(
            ComponentName(context, CompactPlaybackWidgetProvider::class.java)
        )
        val flowIds = manager.getAppWidgetIds(
            ComponentName(context, FlowPlaybackWidgetProvider::class.java)
        )
        if (wideIds.isEmpty() && compactIds.isEmpty() && flowIds.isEmpty()) {
            flowAnimationSpec = null
            return
        }

        BitmapProvider.init(context)
        val snapshot = currentSnapshot()
        val sourceArtwork = snapshot.song?.let { song ->
            BitmapProvider.execute(song.coverKey, 768, 768)
        } ?: if (AppPreferences.AlbumArt.useDefaultArtwork) {
            decodeDefaultAlbumArtwork(context.resources, 768)
        } else {
            null
        }

        wideIds.forEach { widgetId ->
            val options = manager.getAppWidgetOptions(widgetId)
            val minWidthDp = options.getInt(
                AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                280
            )
            val minHeightDp = options.getInt(
                AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                128
            )
            val views = buildRemoteViews(
                context = context,
                snapshot = snapshot,
                sourceArtwork = sourceArtwork,
                wide = true,
                widthDp = minWidthDp,
                heightDp = minHeightDp
            )
            manager.updateAppWidget(widgetId, views)
        }

        compactIds.forEach { widgetId ->
            val options = manager.getAppWidgetOptions(widgetId)
            val minWidthDp = options.getInt(
                AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                130
            )
            val minHeightDp = options.getInt(
                AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                130
            )
            val views = buildRemoteViews(
                context = context,
                snapshot = snapshot,
                sourceArtwork = sourceArtwork,
                wide = false,
                widthDp = minWidthDp,
                heightDp = minHeightDp
            )
            manager.updateAppWidget(widgetId, views)
        }

        if (flowIds.isNotEmpty()) {
            val lyricChild = resolveFlowLyricChild(snapshot).displayedChild
            flowIds.forEach { widgetId ->
                val options = manager.getAppWidgetOptions(widgetId)
                val minWidthDp = options.getInt(
                    AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                    280
                )
                val minHeightDp = options.getInt(
                    AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                    128
                )
                val views = buildFlowRemoteViews(
                    context = context,
                    snapshot = snapshot,
                    sourceArtwork = sourceArtwork,
                    widthDp = minWidthDp,
                    heightDp = minHeightDp,
                    lyricChild = lyricChild
                )
                manager.updateAppWidget(widgetId, views)
            }
            val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
            val firstOptions = manager.getAppWidgetOptions(flowIds.first())
            val animationWidth = (firstOptions.getInt(
                AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                280
            ) * density).roundToInt().coerceIn(180, FLOW_BACKGROUND_MAX_WIDTH_PX)
            val animationHeight = (firstOptions.getInt(
                AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                128
            ) * density).roundToInt().coerceIn(90, FLOW_BACKGROUND_MAX_HEIGHT_PX)
            flowAnimationSpec = FlowAnimationSpec(
                widgetIds = flowIds.copyOf(),
                colors = resolveFlowColors(sourceArtwork),
                width = animationWidth,
                height = animationHeight,
                density = density
            )
            ensureFlowAnimatorStarted(context)
        } else {
            flowAnimationSpec = null
        }

    }

    private fun ensureFlowAnimatorStarted(context: Context) {
        if (!flowAnimatorStarted.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        flowAnimationExecutor.scheduleAtFixedRate(
            {
                val spec = flowAnimationSpec ?: return@scheduleAtFixedRate
                if (!powerManager.isInteractive || spec.widgetIds.isEmpty()) {
                    return@scheduleAtFixedRate
                }
                runCatching {
                    val views = RemoteViews(appContext.packageName, R.layout.widget_playback_flow)
                    views.setImageViewBitmap(
                        R.id.widget_flow_background,
                        createAnimatedFlowBackground(
                            colors = spec.colors,
                            width = spec.width,
                            height = spec.height,
                            timeSeconds = SystemClock.elapsedRealtime() / 1000f
                        )
                    )
                    applyFlowLyricMotion(
                        views = views,
                        nowElapsed = SystemClock.elapsedRealtime(),
                        density = spec.density
                    )
                    AppWidgetManager.getInstance(appContext)
                        .partiallyUpdateAppWidget(spec.widgetIds, views)
                }
            },
            0L,
            FLOW_FRAME_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun buildRemoteViews(
        context: Context,
        snapshot: PlaybackWidgetSnapshot,
        sourceArtwork: Bitmap?,
        wide: Boolean,
        widthDp: Int,
        heightDp: Int
    ): RemoteViews {
        val layout = if (wide) R.layout.widget_playback_wide else R.layout.widget_playback_compact
        val views = RemoteViews(context.packageName, layout)
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val backgroundWidth: Int
        val backgroundHeight: Int
        if (wide) {
            backgroundWidth = (widthDp * density).roundToInt()
                .coerceIn(420, WIDE_BACKGROUND_MAX_WIDTH_PX)
            backgroundHeight = (heightDp * density).roundToInt()
                .coerceIn(220, WIDE_BACKGROUND_MAX_HEIGHT_PX)
        } else {
            val side = (minOf(widthDp, heightDp) * density).roundToInt()
                .coerceIn(280, COMPACT_BACKGROUND_MAX_SIDE_PX)
            backgroundWidth = side
            backgroundHeight = side
        }
        val artworkSide = ((if (wide) 50 else 74) * density).roundToInt()
            .coerceIn(150, MAX_ARTWORK_SIDE_PX)

        val title = snapshot.song?.displayName?.ifBlank {
            context.getString(R.string.widget_nothing_playing)
        } ?: context.getString(R.string.widget_nothing_playing)
        val artist = snapshot.song?.artist?.ifBlank {
            context.getString(R.string.widget_unknown_artist)
        } ?: context.getString(R.string.widget_tap_to_open)

        views.setTextViewText(R.id.widget_title, title)
        views.setTextViewText(R.id.widget_artist, artist)
        if (wide) {
            applyProgress(views, snapshot)
        }
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (snapshot.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        views.setImageViewResource(R.id.widget_previous, R.drawable.ic_rewind_fill)
        views.setImageViewResource(R.id.widget_next, R.drawable.ic_speed_fill)
        views.setContentDescription(
            R.id.widget_play_pause,
            context.getString(
                if (snapshot.isPlaying) R.string.widget_pause else R.string.widget_play
            )
        )

        val background = if (wide) {
            createWidePaletteBackground(
                source = sourceArtwork,
                width = backgroundWidth,
                height = backgroundHeight
            )
        } else {
            createCompactArtworkBackground(
                source = sourceArtwork,
                width = backgroundWidth,
                height = backgroundHeight
            )
        }
        views.setImageViewBitmap(R.id.widget_background, background)

        if (sourceArtwork != null && !sourceArtwork.isRecycled) {
            views.setImageViewBitmap(
                R.id.widget_artwork,
                createRoundedArtwork(
                    sourceArtwork,
                    artworkSide,
                    if (wide) 12f * density else 17f * density
                )
            )
        } else {
            views.setImageViewResource(R.id.widget_artwork, R.mipmap.ic_launcher)
        }

        val openIntent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_PLAYER_FROM_WIDGET, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPendingIntent = PendingIntent.getActivity(
            context,
            40,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openPendingIntent)
        views.setOnClickPendingIntent(R.id.widget_artwork, openPendingIntent)
        views.setOnClickPendingIntent(R.id.widget_title, openPendingIntent)
        views.setOnClickPendingIntent(R.id.widget_artist, openPendingIntent)
        if (wide) {
            views.setOnClickPendingIntent(R.id.widget_app_logo, openPendingIntent)
            views.setOnClickPendingIntent(
                R.id.widget_playlist,
                playerActivityPendingIntent(
                    context = context,
                    requestCode = 44,
                    openQueue = true
                )
            )
            views.setOnClickPendingIntent(
                R.id.widget_favorite,
                playerActivityPendingIntent(
                    context = context,
                    requestCode = 45,
                    openPlaylistPicker = true
                )
            )
        }

        views.setOnClickPendingIntent(
            R.id.widget_play_pause,
            servicePendingIntent(context, PlayerService.ACTION_TOGGLE_PLAYBACK, 41)
        )
        views.setViewVisibility(R.id.widget_previous, View.VISIBLE)
        views.setViewVisibility(R.id.widget_next, View.VISIBLE)
        views.setOnClickPendingIntent(
            R.id.widget_previous,
            servicePendingIntent(context, PlayerService.ACTION_PREVIOUS, if (wide) 42 else 52)
        )
        views.setOnClickPendingIntent(
            R.id.widget_next,
            servicePendingIntent(context, PlayerService.ACTION_NEXT, if (wide) 43 else 53)
        )
        return views
    }

    private fun buildFlowRemoteViews(
        context: Context,
        snapshot: PlaybackWidgetSnapshot,
        sourceArtwork: Bitmap?,
        widthDp: Int,
        heightDp: Int,
        lyricChild: Int
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_playback_flow)
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val backgroundWidth = (widthDp * density).roundToInt()
            .coerceIn(180, FLOW_BACKGROUND_MAX_WIDTH_PX)
        val backgroundHeight = (heightDp * density).roundToInt()
            .coerceIn(90, FLOW_BACKGROUND_MAX_HEIGHT_PX)
        val artworkSide = (84 * density).roundToInt().coerceIn(150, MAX_ARTWORK_SIDE_PX)
        val title = snapshot.song?.displayName?.ifBlank {
            context.getString(R.string.widget_nothing_playing)
        } ?: context.getString(R.string.widget_nothing_playing)
        val artist = snapshot.song?.artist?.ifBlank {
            context.getString(R.string.widget_unknown_artist)
        } ?: context.getString(R.string.widget_tap_to_open)

        views.setTextViewText(R.id.widget_flow_title, title)
        views.setTextViewText(R.id.widget_flow_artist, artist)
        applyFlowLyric(views, snapshot, lyricChild)

        views.setImageViewBitmap(
            R.id.widget_flow_background,
            createAnimatedFlowBackground(
                source = sourceArtwork,
                width = backgroundWidth,
                height = backgroundHeight,
                timeSeconds = SystemClock.elapsedRealtime() / 1000f
            )
        )

        if (sourceArtwork != null && !sourceArtwork.isRecycled) {
            views.setImageViewBitmap(
                R.id.widget_flow_artwork,
                createRoundedArtwork(sourceArtwork, artworkSide, 16f * density)
            )
        } else {
            views.setImageViewResource(R.id.widget_flow_artwork, R.mipmap.ic_launcher)
        }

        views.setImageViewResource(
            R.id.widget_flow_play_pause,
            if (snapshot.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        views.setImageViewResource(R.id.widget_flow_previous, R.drawable.ic_rewind_fill)
        views.setImageViewResource(R.id.widget_flow_next, R.drawable.ic_speed_fill)
        views.setContentDescription(
            R.id.widget_flow_play_pause,
            context.getString(if (snapshot.isPlaying) R.string.widget_pause else R.string.widget_play)
        )

        val openIntent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_PLAYER_FROM_WIDGET, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openPendingIntent = PendingIntent.getActivity(
            context,
            60,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_flow_root, openPendingIntent)
        views.setOnClickPendingIntent(R.id.widget_flow_artwork, openPendingIntent)
        views.setOnClickPendingIntent(R.id.widget_flow_title, openPendingIntent)
        views.setOnClickPendingIntent(R.id.widget_flow_artist, openPendingIntent)
        views.setOnClickPendingIntent(R.id.widget_flow_lyric_container, openPendingIntent)
        views.setOnClickPendingIntent(
            R.id.widget_flow_play_pause,
            servicePendingIntent(context, PlayerService.ACTION_TOGGLE_PLAYBACK, 61)
        )
        views.setOnClickPendingIntent(
            R.id.widget_flow_previous,
            servicePendingIntent(context, PlayerService.ACTION_PREVIOUS, 62)
        )
        views.setOnClickPendingIntent(
            R.id.widget_flow_next,
            servicePendingIntent(context, PlayerService.ACTION_NEXT, 63)
        )
        return views
    }

    private fun resolveFlowLyricChild(snapshot: PlaybackWidgetSnapshot): FlowLyricRender {
        val changed = snapshot.lyricIdentity != lastFlowLyricIdentity
        if (changed) {
            val hadPreviousLyric = lastFlowLyricIdentity.isNotEmpty()
            val previousChild = flowLyricDisplayedChild
            if (hadPreviousLyric) {
                flowLyricDisplayedChild = 1 - flowLyricDisplayedChild
            }
            lastFlowLyricIdentity = snapshot.lyricIdentity
            flowLyricAnimation = if (hadPreviousLyric) {
                FlowLyricAnimation(
                    fromChild = previousChild,
                    toChild = flowLyricDisplayedChild,
                    startedAtElapsed = SystemClock.elapsedRealtime()
                )
            } else {
                null
            }
        }
        return FlowLyricRender(flowLyricDisplayedChild, changed)
    }

    private fun applyFlowLyric(
        views: RemoteViews,
        snapshot: PlaybackWidgetSnapshot,
        displayedChild: Int
    ) {
        val lyricId = if (displayedChild == 0) {
            R.id.widget_flow_lyric_0
        } else {
            R.id.widget_flow_lyric_1
        }
        val translationId = if (displayedChild == 0) {
            R.id.widget_flow_translation_0
        } else {
            R.id.widget_flow_translation_1
        }
        views.setTextViewText(lyricId, snapshot.lyricText)
        views.setTextViewText(translationId, snapshot.lyricTranslation)
        views.setViewVisibility(
            translationId,
            if (snapshot.lyricTranslation.isBlank()) View.GONE else View.VISIBLE
        )
        applyFlowLyricMotion(views, SystemClock.elapsedRealtime(), 1f)
    }

    private fun applyFlowLyricMotion(
        views: RemoteViews,
        nowElapsed: Long,
        density: Float
    ) {
        val animation = flowLyricAnimation
        if (animation == null) {
            val firstVisible = flowLyricDisplayedChild == 0
            applyFlowLyricLayerTransform(views, 0, firstVisible, 0f, if (firstVisible) 1f else 0f)
            applyFlowLyricLayerTransform(views, 1, !firstVisible, 0f, if (firstVisible) 0f else 1f)
            return
        }

        val linear = ((nowElapsed - animation.startedAtElapsed).toFloat() /
            FLOW_LYRIC_TRANSITION_MS).coerceIn(0f, 1f)
        val eased = 1f - (1f - linear) * (1f - linear) * (1f - linear)
        val travelPx = 12f * density
        applyFlowLyricLayerTransform(
            views = views,
            child = animation.fromChild,
            visible = true,
            translationY = -travelPx * eased,
            alpha = 1f - eased
        )
        applyFlowLyricLayerTransform(
            views = views,
            child = animation.toChild,
            visible = true,
            translationY = travelPx * (1f - eased),
            alpha = eased
        )
        if (linear >= 1f) flowLyricAnimation = null
    }

    private fun applyFlowLyricLayerTransform(
        views: RemoteViews,
        child: Int,
        visible: Boolean,
        translationY: Float,
        alpha: Float
    ) {
        val layerId = if (child == 0) {
            R.id.widget_flow_lyric_layer_0
        } else {
            R.id.widget_flow_lyric_layer_1
        }
        views.setViewVisibility(layerId, if (visible || alpha > 0f) View.VISIBLE else View.INVISIBLE)
        views.setFloat(layerId, "setTranslationY", translationY)
        views.setFloat(layerId, "setAlpha", alpha.coerceIn(0f, 1f))
        val scale = 0.96f + 0.04f * alpha.coerceIn(0f, 1f)
        views.setFloat(layerId, "setScaleX", scale)
        views.setFloat(layerId, "setScaleY", scale)
    }

    private fun playerActivityPendingIntent(
        context: Context,
        requestCode: Int,
        openQueue: Boolean = false,
        openPlaylistPicker: Boolean = false
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_PLAYER_FROM_WIDGET, true)
            .putExtra(MainActivity.EXTRA_OPEN_QUEUE_FROM_WIDGET, openQueue)
            .putExtra(MainActivity.EXTRA_OPEN_PLAYLIST_PICKER_FROM_WIDGET, openPlaylistPicker)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun servicePendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, PlayerService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, requestCode, intent, flags)
        } else {
            PendingIntent.getService(context, requestCode, intent, flags)
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs.coerceAtLeast(0L) / 1000L)
        val seconds = totalSeconds % 60L
        val minutes = (totalSeconds / 60L) % 60L
        val hours = totalSeconds / 3600L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private fun applyProgress(views: RemoteViews, snapshot: PlaybackWidgetSnapshot) {
        val duration = snapshot.durationMs.coerceAtLeast(0L)
        val position = snapshot.positionMs.coerceIn(
            0L,
            duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        )
        val progress = if (duration > 0L) {
            (position.toDouble() / duration.toDouble() * WIDGET_PROGRESS_MAX)
                .roundToInt()
                .coerceIn(0, WIDGET_PROGRESS_MAX)
        } else {
            0
        }
        val chronometerBase = SystemClock.elapsedRealtime() - position
        views.setChronometer(
            R.id.widget_position,
            chronometerBase,
            null,
            snapshot.isPlaying && (duration <= 0L || position < duration)
        )
        views.setTextViewText(R.id.widget_duration, formatDuration(duration))
        views.setProgressBar(R.id.widget_progress, WIDGET_PROGRESS_MAX, progress, false)
    }

    private fun currentSnapshot(): PlaybackWidgetSnapshot {
        val controller = PlayerService.currentRuntimeController()
        val liveSong = controller?.currentSong?.value
        val persistedSong = persistedSong()
        val resolvedSong = liveSong ?: persistedSong
        val state = controller?.playState?.value ?: PlayState.entries.getOrElse(
            AppPreferences.Player.lastPlayStateOrdinal
        ) { PlayState.IDLE }
        val position = controller?.position?.value ?: AppPreferences.Player.lastPosition
        val lyricData = PlayerService.currentLyrics.value
        val lyricIndex = lyricData?.findCurrentLine(position) ?: -1
        val lyricLine = lyricData?.getLine(lyricIndex)
        val fallbackLyric = resolvedSong?.displayName?.takeIf { it.isNotBlank() }.orEmpty()
        return PlaybackWidgetSnapshot(
            song = resolvedSong,
            isPlaying = state == PlayState.PLAYING,
            positionMs = position,
            durationMs = controller?.duration?.value?.takeIf { it > 0L }
                ?: liveSong?.duration?.takeIf { it > 0L }
                ?: persistedSong?.duration
                ?: 0L,
            lyricText = lyricLine?.text?.trim().takeUnless { it.isNullOrBlank() } ?: fallbackLyric,
            lyricTranslation = lyricLine?.translation?.trim().orEmpty(),
            lyricIdentity = buildString {
                append(resolvedSong?.path.orEmpty())
                append('|')
                append(resolvedSong?.cueTrackIndex ?: 0)
                append('|')
                append(lyricIndex)
            }
        )
    }

    private fun persistedSong(): AudioFile? {
        val path = AppPreferences.Player.lastSongPath
        if (path.isBlank()) return null
        val file = File(path)
        return AudioFile(
            id = AppPreferences.Player.lastSongId,
            path = path,
            title = AppPreferences.Player.lastSongTitle,
            artist = AppPreferences.Player.lastSongArtist,
            album = AppPreferences.Player.lastSongAlbum,
            albumId = AppPreferences.Player.lastSongAlbumId,
            duration = AppPreferences.Player.lastSongDuration,
            fileSize = file.takeIf { it.exists() }?.length() ?: 0L,
            dateModified = file.takeIf { it.exists() }?.lastModified() ?: 0L,
            albumArtPath = AppPreferences.Player.lastSongAlbumArtPath
        )
    }

    private fun createWidePaletteBackground(
        source: Bitmap?,
        width: Int,
        height: Int
    ): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val radius = minOf(width, height) * 0.13f
        canvas.clipPath(Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) })

        val colors = source
            ?.takeIf { !it.isRecycled }
            ?.let(::extractCompactFlowColors)
            .orEmpty()
            .ifEmpty {
                listOf(Color.rgb(70, 91, 112), Color.rgb(78, 76, 104), Color.rgb(49, 91, 94))
            }
        val base = adjustFlowColor(colors.first(), value = 0.38f, saturationScale = 0.72f)
        canvas.drawColor(base)

        val horizontal = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height * 0.72f,
            intArrayOf(
                adjustFlowColor(colors[0], value = 0.54f, saturationScale = 0.88f),
                adjustFlowColor(colors.getOrElse(1) { colors[0] }, value = 0.42f, saturationScale = 0.76f),
                adjustFlowColor(colors.getOrElse(2) { colors.last() }, value = 0.34f, saturationScale = 0.70f)
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(bounds, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = horizontal })

        colors.take(4).forEachIndexed { index, color ->
            val x = if (index % 2 == 0) width * 0.18f else width * 0.82f
            val y = if (index < 2) height * 0.16f else height * 0.88f
            val tuned = adjustFlowColor(color, value = 0.62f, saturationScale = 0.92f)
            val glow = RadialGradient(
                x,
                y,
                maxOf(width, height) * 0.62f,
                intArrayOf(withAlpha(tuned, 96), withAlpha(tuned, 36), Color.TRANSPARENT),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(bounds, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = glow })
        }
        canvas.drawColor(Color.argb(22, 0, 0, 0))
        return output
    }

    private fun createAnimatedFlowBackground(
        source: Bitmap?,
        width: Int,
        height: Int,
        timeSeconds: Float
    ): Bitmap = createAnimatedFlowBackground(
        colors = resolveFlowColors(source),
        width = width,
        height = height,
        timeSeconds = timeSeconds
    )

    private fun resolveFlowColors(source: Bitmap?): List<Int> = source
        ?.takeIf { !it.isRecycled }
        ?.let(::extractCompactFlowColors)
        .orEmpty()
        .ifEmpty {
            listOf(
                Color.rgb(70, 104, 132),
                Color.rgb(117, 79, 124),
                Color.rgb(55, 116, 110)
            )
        }
        .take(5)

    private fun createAnimatedFlowBackground(
        colors: List<Int>,
        width: Int,
        height: Int,
        timeSeconds: Float
    ): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val radius = minOf(width, height) * 0.13f
        canvas.clipPath(Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) })
        canvas.drawColor(adjustFlowColor(colors.first(), value = 0.34f, saturationScale = 0.82f))

        val maxDimension = maxOf(width, height).toFloat()
        val countScale = when (colors.size) {
            1 -> 1.18f
            2 -> 1.05f
            else -> 0.92f
        }
        flowWidgetSeeds.forEachIndexed { index, seed ->
            if (index >= colors.size) return@forEachIndexed
            val phase = seed.phase + index * 0.83f
            val x = width * seed.baseX +
                width * seed.amplitudeX * sin(timeSeconds * seed.speedX + phase)
            val y = height * seed.baseY +
                height * seed.amplitudeY * cos(timeSeconds * seed.speedY + phase * 1.21f)
            val radiusPulse = 1f + 0.11f * sin(timeSeconds * seed.radiusSpeed + phase)
            val blobRadius = maxDimension * seed.radius * countScale * radiusPulse
            val tuned = adjustFlowColor(colors[index], value = 0.72f, saturationScale = 1.08f)
            val gradient = RadialGradient(
                x,
                y,
                blobRadius,
                intArrayOf(
                    withAlpha(tuned, 224),
                    withAlpha(tuned, 162),
                    withAlpha(tuned, 62),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.38f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(bounds, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradient })
        }
        canvas.drawColor(Color.argb(30, 0, 0, 0))
        return output
    }

    private fun createCompactArtworkBackground(
        source: Bitmap?,
        width: Int,
        height: Int
    ): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val validSource = source?.takeIf { !it.isRecycled }
        val colors = validSource?.let(::extractCompactFlowColors)
            .orEmpty()
            .ifEmpty {
                listOf(
                    Color.rgb(76, 92, 126),
                    Color.rgb(126, 75, 105),
                    Color.rgb(62, 108, 112)
                )
            }
        canvas.drawColor(adjustFlowColor(colors.first(), value = 0.48f, saturationScale = 0.68f))

        val maxDimension = maxOf(width, height).toFloat()
        val anchors = arrayOf(
            0.18f to 0.18f,
            0.86f to 0.22f,
            0.24f to 0.84f,
            0.84f to 0.82f
        )
        colors.take(4).forEachIndexed { index, color ->
            val anchor = anchors[index]
            val tuned = adjustFlowColor(
                color,
                value = if (index == 0) 0.86f else 0.76f,
                saturationScale = 1.08f
            )
            val gradient = RadialGradient(
                width * anchor.first,
                height * anchor.second,
                maxDimension * if (colors.size <= 2) 0.92f else 0.68f,
                intArrayOf(
                    withAlpha(tuned, 220),
                    withAlpha(tuned, 108),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.46f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradient }
            )
        }

        // Fixed 20% mask keeps white artwork readable while preserving album color.
        canvas.drawColor(Color.argb(51, 0, 0, 0))
        val bottomGradient = LinearGradient(
            0f,
            height * 0.34f,
            0f,
            height.toFloat(),
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(18, 0, 0, 0),
                Color.argb(82, 0, 0, 0)
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = bottomGradient }
        )
        return output
    }

    private fun extractCompactFlowColors(source: Bitmap): List<Int> {
        val palette = Palette.from(source)
            .maximumColorCount(12)
            .resizeBitmapArea(96 * 96)
            .generate()
        val candidates = palette.swatches
            .asSequence()
            .filter { it.population > 0 }
            .map { swatch ->
                val hsv = FloatArray(3)
                Color.colorToHSV(swatch.rgb, hsv)
                CompactFlowColor(
                    color = swatch.rgb,
                    population = swatch.population,
                    saturation = hsv[1],
                    value = hsv[2]
                )
            }
            .filter { it.value in 0.08f..0.96f }
            .sortedByDescending {
                it.population * (0.72f + it.saturation * 0.62f)
            }
            .toList()
        if (candidates.isEmpty()) return emptyList()

        val strongestPopulation = candidates.first().population.coerceAtLeast(1)
        val selected = mutableListOf<Int>()
        candidates.forEach { candidate ->
            if (selected.size >= 5) return@forEach
            if (candidate.population < strongestPopulation * 0.04f) return@forEach
            if (selected.none { colorDistance(it, candidate.color) >= 0.16f }) {
                selected += candidate.color
            }
        }
        return selected.ifEmpty { listOf(candidates.first().color) }
    }

    private fun adjustFlowColor(color: Int, value: Float, saturationScale: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * saturationScale + 0.04f).coerceIn(0.16f, 0.94f)
        hsv[2] = value.coerceIn(0.16f, 0.94f)
        return Color.HSVToColor(hsv)
    }

    private fun colorDistance(first: Int, second: Int): Float {
        val firstHsv = FloatArray(3)
        val secondHsv = FloatArray(3)
        Color.colorToHSV(first, firstHsv)
        Color.colorToHSV(second, secondHsv)
        val rawHue = abs(firstHsv[0] - secondHsv[0])
        val hue = minOf(rawHue, 360f - rawHue) / 180f
        val saturation = abs(firstHsv[1] - secondHsv[1])
        val value = abs(firstHsv[2] - secondHsv[2])
        return hue * 0.62f + saturation * 0.24f + value * 0.30f
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun createRoundedArtwork(source: Bitmap, side: Int, radius: Float): Bitmap {
        val output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val bounds = RectF(0f, 0f, side.toFloat(), side.toFloat())
        canvas.clipPath(Path().apply {
            addRoundRect(bounds, radius, radius, Path.Direction.CW)
        })
        val crop = centerCropRect(source.width, source.height, side, side)
        canvas.drawBitmap(
            source,
            crop,
            Rect(0, 0, side, side),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        return output
    }

    private fun centerCropRect(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Rect {
        val sourceRatio = sourceWidth.toFloat() / sourceHeight.coerceAtLeast(1)
        val targetRatio = targetWidth.toFloat() / targetHeight.coerceAtLeast(1)
        return if (sourceRatio > targetRatio) {
            val cropWidth = (sourceHeight * targetRatio).roundToInt().coerceIn(1, sourceWidth)
            val left = (sourceWidth - cropWidth) / 2
            Rect(left, 0, left + cropWidth, sourceHeight)
        } else {
            val cropHeight = (sourceWidth / targetRatio).roundToInt().coerceIn(1, sourceHeight)
            val top = (sourceHeight - cropHeight) / 2
            Rect(0, top, sourceWidth, top + cropHeight)
        }
    }

    private data class PlaybackWidgetSnapshot(
        val song: AudioFile?,
        val isPlaying: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val lyricText: String,
        val lyricTranslation: String,
        val lyricIdentity: String
    )

    private data class FlowLyricRender(
        val displayedChild: Int,
        val changed: Boolean
    )

    private data class FlowAnimationSpec(
        val widgetIds: IntArray,
        val colors: List<Int>,
        val width: Int,
        val height: Int,
        val density: Float
    )

    private data class FlowLyricAnimation(
        val fromChild: Int,
        val toChild: Int,
        val startedAtElapsed: Long
    )

    private data class CompactFlowColor(
        val color: Int,
        val population: Int,
        val saturation: Float,
        val value: Float
    )

    private data class FlowWidgetSeed(
        val baseX: Float,
        val baseY: Float,
        val amplitudeX: Float,
        val amplitudeY: Float,
        val radius: Float,
        val phase: Float,
        val speedX: Float,
        val speedY: Float,
        val radiusSpeed: Float
    )

    private val flowWidgetSeeds = listOf(
        FlowWidgetSeed(0.08f, 0.17f, 0.38f, 0.30f, 0.45f, 0.20f, 0.91f, 0.63f, 0.43f),
        FlowWidgetSeed(0.86f, 0.14f, 0.31f, 0.33f, 0.40f, 1.70f, 0.57f, 0.83f, 0.55f),
        FlowWidgetSeed(0.28f, 0.63f, 0.34f, 0.35f, 0.50f, 3.10f, 0.77f, 0.45f, 0.38f),
        FlowWidgetSeed(0.88f, 0.78f, 0.30f, 0.29f, 0.43f, 4.40f, 0.43f, 0.73f, 0.49f),
        FlowWidgetSeed(0.48f, 0.42f, 0.29f, 0.38f, 0.39f, 5.35f, 0.69f, 0.57f, 0.52f)
    )
}
