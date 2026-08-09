package com.rawsmusic.lyric

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.palette.graphics.Palette
import com.rawsmusic.MainActivity
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.XiaomiSuperIslandSettings
import com.rawsmusic.module.player.PlayerService
import com.xzakota.hyper.notification.focus.FocusNotification
import com.xzakota.hyper.notification.focus.template.CustomFocusTemplate
import com.xzakota.hyper.notification.focus.template.CustomFocusTemplateV3
import com.xzakota.hyper.notification.island.model.BigIslandArea
import com.xzakota.hyper.notification.island.model.TextInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** HyperOS Focus bridge. The player sends only lyric state; this class owns vendor payloads. */
internal class XiaomiSuperIslandLyricBridge(
    context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        const val TAG = "RawSuperIsland"
        const val CHANNEL_ID = "rawsmusic_xiaomi_super_island_v2"
        const val NOTIFICATION_ID = 0x454c4c53
        private const val DEFAULT_ACCENT = 0xFF3482FF.toInt()
        private const val MIN_RENDER_INTERVAL_MS = 1_500L
    }

    private val appContext = context.applicationContext
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val appIcon by lazy { Icon.createWithResource(appContext, R.mipmap.ic_launcher) }
    private val networkMutex = Mutex()
    private var enabled = false
    private var settings = XiaomiSuperIslandSettings()
    private var lastKey: String? = null
    private var pendingRequest: RenderRequest? = null
    private var renderJob: Job? = null
    private var lastRenderElapsed = 0L
    private var artwork: ArtworkCache? = null
    private var networkJob: Job? = null
    private var dispatchGeneration = 0L
    private var xmsfNetworkingBlocked = false

    private data class ArtworkCache(
        val source: Bitmap,
        val island: Icon,
        val smallIsland: Icon,
        val share: Icon,
        val expandBitmap: Bitmap,
        val tinyBitmap: Bitmap
    )
    private data class RenderRequest(
        val song: AudioFile,
        val displayLyric: String,
        val fullLyric: String,
        val progress: Int,
        val accent: Int,
        val artwork: Bitmap?,
        val settings: XiaomiSuperIslandSettings
    )

    fun setEnabled(value: Boolean) {
        enabled = value
        lastKey = null
        if (value) ensureChannel() else clear()
    }

    fun setSettings(value: XiaomiSuperIslandSettings) {
        val previousMode = settings.xmsfBypassMode
        settings = value.sanitized()
        lastKey = null
        if (
            previousMode == XiaomiSuperIslandSettings.XMSF_MODE_AGGRESSIVE &&
            settings.xmsfBypassMode != XiaomiSuperIslandSettings.XMSF_MODE_AGGRESSIVE
        ) {
            restoreXmsfNetworkingAsync()
        }
    }

    fun sendLyric(
        song: AudioFile,
        line: LyricLine,
        positionMs: Long,
        durationMs: Long,
        artworkBitmap: Bitmap?
    ) {
        if (!enabled) return
        val active = settings
        val selected = when (active.lyricTextMode) {
            XiaomiSuperIslandSettings.TEXT_TRANSLATION -> line.translation.ifBlank { line.text }
            XiaomiSuperIslandSettings.TEXT_PRONUNCIATION -> line.romanization.ifBlank { line.text }
            else -> line.text
        }.replace(Regex("\\s+"), " ").trim()
        if (selected.isBlank()) return
        val title = song.title.ifBlank { song.displayName }.ifBlank { lyricPlaceholder }
        val fullLyric = selected
        val displayLyric = if (active.lyricMode == XiaomiSuperIslandSettings.LYRIC_MODE_FULL ||
            !active.scrollingEnabled
        ) fullLyric else selected
        val progress = if (durationMs > 0L) {
            (positionMs.coerceIn(0L, durationMs) * 100L / durationMs).toInt().coerceIn(0, 100)
        } else 0
        val accent = resolveAccent(active, artworkBitmap)
        val key = listOf(song.path, line.timeStamp, displayLyric, active.encode(), accent).joinToString("|")
        if (key == lastKey) return
        lastKey = key
        renderThrottled(
            RenderRequest(song, displayLyric, fullLyric, progress, accent, artworkBitmap, active)
        )
    }

    fun onPlaybackPaused() {
        restoreXmsfNetworkingAsync()
        if (settings.dismissDelayMs <= 0) clear() else {
            renderJob?.cancel()
            renderJob = scope.launch {
                delay(settings.dismissDelayMs.toLong())
                clear()
            }
        }
    }

    fun clear() {
        renderJob?.cancel()
        renderJob = null
        pendingRequest = null
        lastKey = null
        lastRenderElapsed = 0L
        artwork = null
        XiaomiSuperIslandLyricService.stop(appContext)
        notificationManager.cancel(NOTIFICATION_ID)
        networkJob?.cancel()
        val generation = synchronized(this) { ++dispatchGeneration }
        networkJob = scope.launch(Dispatchers.IO) {
            networkMutex.withLock {
                if (generation == dispatchGeneration) restoreXmsfNetworking()
            }
        }
    }

    fun destroy() {
        enabled = false
        clear()
    }

    private fun renderThrottled(request: RenderRequest) {
        val remaining = (lastRenderElapsed + MIN_RENDER_INTERVAL_MS - SystemClock.elapsedRealtime())
            .coerceAtLeast(0L)
        if (remaining == 0L) {
            renderJob?.cancel()
            renderJob = null
            lastRenderElapsed = SystemClock.elapsedRealtime()
            renderAndDispatch(request)
            return
        }
        pendingRequest = request
        renderJob?.cancel()
        renderJob = scope.launch {
            delay(remaining)
            val latest = pendingRequest ?: return@launch
            pendingRequest = null
            renderJob = null
            lastRenderElapsed = SystemClock.elapsedRealtime()
            renderAndDispatch(latest)
        }
    }

    private fun renderAndDispatch(request: RenderRequest) {
        val notification = runCatching { buildNotification(request) }.getOrElse { error ->
            AppLogger.w(TAG, "Focus payload rejected; using direct notification fallback", error)
            buildFallbackNotification(request)
        }
        AppLogger.d(
            TAG,
            "Publishing Super Island lyric advanced=${request.settings.notificationStyle == XiaomiSuperIslandSettings.NOTIFICATION_STYLE_ADVANCED} " +
                "text=${request.displayLyric.take(48)}"
        )
        dispatch(notification, request.settings)
    }

    private fun dispatch(notification: Notification, activeSettings: XiaomiSuperIslandSettings) {
        val mode = activeSettings.xmsfBypassMode
        val generation = synchronized(this) { ++dispatchGeneration }
        networkJob?.cancel()
        if (mode == XiaomiSuperIslandSettings.XMSF_MODE_DISABLED) {
            restoreXmsfNetworkingAsync(expectedGeneration = generation)
            XiaomiSuperIslandLyricService.publish(appContext, notification)
            return
        }

        networkJob = scope.launch(Dispatchers.IO) {
            networkMutex.withLock {
                if (generation != dispatchGeneration || !enabled) return@withLock
                if (!xmsfNetworkingBlocked) blockXmsfNetworking()
                if (generation != dispatchGeneration || !enabled) return@withLock
                XiaomiSuperIslandLyricService.publish(appContext, notification)
                if (mode == XiaomiSuperIslandSettings.XMSF_MODE_AGGRESSIVE) return@withLock
                val duration = if (mode == XiaomiSuperIslandSettings.XMSF_MODE_CUSTOM) {
                    activeSettings.xmsfCustomDurationMs.toLong()
                } else {
                    XiaomiSuperIslandSettings.XMSF_STANDARD_DURATION_MS.toLong()
                }
                try {
                    delay(duration)
                } catch (_: CancellationException) {
                    return@withLock
                }
                if (generation == dispatchGeneration) restoreXmsfNetworking()
            }
        }
    }

    private suspend fun blockXmsfNetworking() {
        val blocked = withContext(NonCancellable) {
            XiaomiXmsfNetworkHelper.setNetworkingEnabled(appContext, false)
        }
        xmsfNetworkingBlocked = blocked
        if (!blocked) AppLogger.w(TAG, "XMSF bypass unavailable; sending Focus notification directly")
    }

    private fun restoreXmsfNetworkingAsync(expectedGeneration: Long? = null) {
        scope.launch(Dispatchers.IO) {
            networkMutex.withLock {
                if (expectedGeneration != null && expectedGeneration != dispatchGeneration) return@withLock
                restoreXmsfNetworking()
            }
        }
    }

    private suspend fun restoreXmsfNetworking() {
        if (!xmsfNetworkingBlocked) return
        withContext(NonCancellable) {
            XiaomiXmsfNetworkHelper.setNetworkingEnabled(appContext, true)
        }
        xmsfNetworkingBlocked = false
    }

    private fun buildNotification(request: RenderRequest): Notification {
        val active = request.settings
        val actionBundle = Bundle()
        val cachedArtwork = cachedArtwork(request.artwork)
        val title = request.song.title.ifBlank { request.song.displayName }.ifBlank { lyricPlaceholder }
        val subtitle = listOf(request.song.artist, request.song.album)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        val accentHex = String.format("#FF%06X", request.accent and 0xFFFFFF)
        val textColor = if (active.textColorEnabled) accentHex else "#757575"
        val progressColor = if (active.progressColorEnabled) accentHex else "#757575"

        val standardExtras = FocusNotification.buildV3 {
            business = "lyric_display"
            isShowNotification = true
            enableFloat = false
            updatable = true
            islandFirstFloat = false
            aodTitle = request.displayLyric.take(20).ifBlank { lyricPlaceholder }
            val islandKey = cachedArtwork?.island?.let { createPicture("miui.focus.pic_island", it) }
            val smallKey = cachedArtwork?.smallIsland?.let { createPicture("miui.land.pic_island", it) }
            val avatarKey = cachedArtwork?.island?.let { createPicture("miui.focus.pic_avatar", it) }
            val appKey = createPicture("miui.focus.pic_app", appIcon)
            ticker = request.displayLyric
            tickerPic = appKey
            chatInfo {
                picProfile = avatarKey
                this.title = request.fullLyric
                content = subtitle.ifBlank { title }
                appIconPkg = appContext.packageName
            }
            if (active.actionStyle == XiaomiSuperIslandSettings.ACTION_STYLE_MEDIA_CONTROLS) {
                val showThree = active.notificationStyle == XiaomiSuperIslandSettings.NOTIFICATION_STYLE_ADVANCED ||
                    active.mediaButtonLayout == XiaomiSuperIslandSettings.MEDIA_BUTTON_LAYOUT_THREE
                actions {
                    if (showThree) addActionInfo {
                        action = createMediaAction(actionBundle, "prev", 3610, PlayerService.ACTION_PREVIOUS, R.drawable.ic_skip_previous, actionPrevious)
                        actionIcon = createPicture("miui.focus.pic_btn_prev", Icon.createWithResource(appContext, R.drawable.ic_skip_previous))
                        clickWithCollapse = false
                    }
                    addActionInfo {
                        action = createMediaAction(actionBundle, "play_pause", 3611, PlayerService.ACTION_TOGGLE_PLAYBACK, R.drawable.ic_pause, actionPlayPause)
                        actionIcon = createPicture("miui.focus.pic_btn_play_pause", Icon.createWithResource(appContext, R.drawable.ic_pause))
                        clickWithCollapse = false
                    }
                    addActionInfo {
                        action = createMediaAction(actionBundle, "next", 3612, PlayerService.ACTION_NEXT, R.drawable.ic_skip_next, actionNext)
                        actionIcon = createPicture("miui.focus.pic_btn_next", Icon.createWithResource(appContext, R.drawable.ic_skip_next))
                        clickWithCollapse = false
                    }
                }
            } else {
                progressInfo {
                    progress = request.progress
                    colorProgress = progressColor
                    colorProgressEnd = progressColor
                }
            }
            island {
                islandProperty = 1
                if (active.textColorEnabled) highlightColor = accentHex
                bigIslandArea {
                    applyLyrics(active, request.displayLyric, request.fullLyric, title, subtitle, islandKey)
                }
                if (active.shareEnabled) {
                    shareData {
                        pic = islandKey
                        this.title = title
                        content = request.fullLyric
                        shareContent = buildShareContent(request.song, request.fullLyric, active.shareFormat)
                    }
                }
                smallIslandArea {
                    combinePicInfo {
                        if (smallKey != null) picInfo { type = 1; pic = smallKey }
                        progressInfo {
                            progress = request.progress
                            colorReach = textColor
                            colorUnReach = "#333333"
                        }
                    }
                }
            }
        }
        val useAdvancedFocus = active.notificationStyle == XiaomiSuperIslandSettings.NOTIFICATION_STYLE_ADVANCED &&
            active.actionStyle == XiaomiSuperIslandSettings.ACTION_STYLE_MEDIA_CONTROLS
        val extras = if (useAdvancedFocus) {
            buildAdvancedFocusExtras(
                request = request,
                title = title,
                subtitle = subtitle,
                cachedArtwork = cachedArtwork,
                standardExtras = standardExtras
            )
        } else {
            standardExtras
        }
        if (useAdvancedFocus) {
            AppLogger.d(TAG, "Publishing Super Island lyric with advanced RemoteViews")
        }
        return Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(request.fullLyric)
            .setContentText(subtitle.ifBlank { title })
            .setContentIntent(createContentIntent(active.clickStyle))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setLocalOnly(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(request.accent)
            .addExtras(extras)
            .apply { if (!actionBundle.isEmpty) addExtras(actionBundle) }
            .build()
    }

    /**
     * A custom Focus template may be used when media controls are enabled. Keep the standard
     * island payload merged into it so HyperOS can still render the compact and expanded island
     * on firmware versions that ignore one of the custom RemoteViews variants.
     */
    private fun buildAdvancedFocusExtras(
        request: RenderRequest,
        title: String,
        subtitle: String,
        cachedArtwork: ArtworkCache?,
        standardExtras: Bundle
    ): Bundle {
        val active = request.settings
        val accentHex = String.format("#FF%06X", request.accent and 0xFFFFFF)
        val progressColor = if (active.progressColorEnabled) accentHex else "#FF757575"
        val customExtras = FocusNotification.buildCustomV3 {
            business = "lyric_display"
            isShowNotification = true
            enableFloat = false
            updatable = true
            islandFirstFloat = false
            hideDeco = true
            aodTitle = request.displayLyric.take(20).ifBlank { lyricPlaceholder }

            val islandKey = cachedArtwork?.island?.let { createPicture("miui.focus.pic_island", it) }
            val smallKey = cachedArtwork?.smallIsland?.let { createPicture("miui.land.pic_island", it) }
            val shareKey = cachedArtwork?.share?.let { createPicture("miui.focus.pic_share", it) }
            val appKey = createPicture("miui.focus.pic_app", appIcon)
            ticker = request.displayLyric.ifBlank { request.fullLyric }
            tickerPic = appKey

            val lightViews = createAdvancedExpandViews(
                lyric = request.displayLyric,
                subtitle = subtitle,
                progress = request.progress,
                accent = request.accent,
                artwork = cachedArtwork?.expandBitmap ?: request.artwork,
                darkMode = false
            )
            val darkViews = createAdvancedExpandViews(
                lyric = request.displayLyric,
                subtitle = subtitle,
                progress = request.progress,
                accent = request.accent,
                artwork = cachedArtwork?.expandBitmap ?: request.artwork,
                darkMode = true
            )
            val tinyViews = createAdvancedTinyViews(
                lyric = request.displayLyric,
                subtitle = subtitle,
                progress = request.progress,
                accent = request.accent,
                artwork = cachedArtwork?.tinyBitmap ?: request.artwork
            )
            createRemoteViews(CustomFocusTemplate.LAYOUT, lightViews)
            createRemoteViews(CustomFocusTemplate.LAYOUT_NIGHT, darkViews)
            createRemoteViews(CustomFocusTemplate.LAYOUT_FLIP_TINY, tinyViews)
            createRemoteViews(CustomFocusTemplate.LAYOUT_FLIP_TINY_NIGHT, tinyViews)
            createRemoteViews(CustomFocusTemplateV3.LAYOUT_ISLAND_EXPAND, darkViews)

            island {
                islandProperty = 1
                if (active.textColorEnabled) highlightColor = accentHex
                bigIslandArea {
                    applyLyrics(active, request.displayLyric, request.fullLyric, title, subtitle, islandKey)
                }
                if (active.shareEnabled) {
                    shareData {
                        pic = shareKey
                        this.title = title
                        content = request.fullLyric
                        shareContent = buildShareContent(request.song, request.fullLyric, active.shareFormat)
                    }
                }
                smallIslandArea {
                    combinePicInfo {
                        if (smallKey != null) picInfo { type = 1; pic = smallKey }
                        progressInfo {
                            progress = request.progress
                            colorReach = progressColor
                            colorUnReach = "#333333"
                        }
                    }
                }
            }
        }
        return mergeCustomFocusWithStandardIsland(customExtras, standardExtras)
    }

    private fun createAdvancedTinyViews(
        lyric: String,
        subtitle: String,
        progress: Int,
        accent: Int,
        artwork: Bitmap?
    ): RemoteViews {
        return RemoteViews(appContext.packageName, R.layout.super_island_custom_tiny).apply {
            setTextViewText(R.id.super_island_tiny_lyric, lyric.ifBlank { lyricPlaceholder })
            setTextViewText(R.id.super_island_tiny_subtitle, subtitle)
            setTextColor(R.id.super_island_tiny_lyric, Color.WHITE)
            setTextColor(R.id.super_island_tiny_subtitle, Color.argb(180, 255, 255, 255))
            setImageViewBitmap(R.id.super_island_tiny_progress, createProgressBitmap(44, 4, progress, accent, true))
            applyArtwork(R.id.super_island_tiny_cover, artwork, 64)
            setOnClickPendingIntent(
                R.id.super_island_tiny_lyric,
                createContentIntent(XiaomiSuperIslandSettings.CLICK_STYLE_OPEN_APP)
            )
        }
    }

    private fun createAdvancedExpandViews(
        lyric: String,
        subtitle: String,
        progress: Int,
        accent: Int,
        artwork: Bitmap?,
        darkMode: Boolean
    ): RemoteViews {
        val primaryColor = if (darkMode) Color.WHITE else Color.rgb(17, 17, 17)
        val secondaryColor = if (darkMode) Color.argb(180, 255, 255, 255) else Color.argb(150, 0, 0, 0)
        return RemoteViews(appContext.packageName, R.layout.super_island_custom_expand).apply {
            setTextViewText(R.id.super_island_expand_lyric, lyric.ifBlank { lyricPlaceholder })
            setTextViewText(R.id.super_island_expand_subtitle, subtitle)
            setTextColor(R.id.super_island_expand_lyric, primaryColor)
            setTextColor(R.id.super_island_expand_subtitle, secondaryColor)
            setImageViewBitmap(
                R.id.super_island_expand_progress,
                createProgressBitmap(320, 6, progress, accent, darkMode)
            )
            applyArtwork(R.id.super_island_expand_cover, artwork, 116)
            setViewVisibility(R.id.super_island_expand_controls, android.view.View.VISIBLE)
            setImageViewResource(R.id.super_island_expand_play_pause, R.drawable.ic_pause)
            setOnClickPendingIntent(
                R.id.super_island_expand_previous,
                createMediaCommandIntent(3610, PlayerService.ACTION_PREVIOUS)
            )
            setOnClickPendingIntent(
                R.id.super_island_expand_play_pause,
                createMediaCommandIntent(3611, PlayerService.ACTION_TOGGLE_PLAYBACK)
            )
            setOnClickPendingIntent(
                R.id.super_island_expand_next,
                createMediaCommandIntent(3612, PlayerService.ACTION_NEXT)
            )
            setInt(R.id.super_island_expand_previous, "setColorFilter", primaryColor)
            setInt(R.id.super_island_expand_play_pause, "setColorFilter", primaryColor)
            setInt(R.id.super_island_expand_next, "setColorFilter", primaryColor)
            setOnClickPendingIntent(
                R.id.super_island_expand_lyric,
                createContentIntent(XiaomiSuperIslandSettings.CLICK_STYLE_OPEN_APP)
            )
        }
    }

    private fun RemoteViews.applyArtwork(viewId: Int, source: Bitmap?, size: Int) {
        if (source == null || source.isRecycled) {
            setImageViewResource(viewId, R.drawable.ic_music_note)
        } else {
            setImageViewBitmap(viewId, Bitmap.createScaledBitmap(source, size, size, true))
        }
    }

    private fun createProgressBitmap(
        width: Int,
        height: Int,
        progress: Int,
        accent: Int,
        darkMode: Boolean
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = height / 2f
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (darkMode) Color.argb(85, 255, 255, 255) else Color.argb(56, 0, 0, 0)
        }
        val foreground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, background)
        val completedWidth = width * (progress.coerceIn(0, 100) / 100f)
        if (completedWidth > 0f) {
            canvas.drawRoundRect(0f, 0f, completedWidth, height.toFloat(), radius, radius, foreground)
        }
        return bitmap
    }

    private fun mergeCustomFocusWithStandardIsland(customExtras: Bundle, standardExtras: Bundle): Bundle {
        val merged = Bundle(customExtras)
        val customJson = customExtras.getString("miui.focus.param.custom") ?: return merged
        val standardJson = standardExtras.getString("miui.focus.param") ?: return merged
        runCatching {
            val customRoot = JSONObject(customJson)
            val standardRoot = JSONObject(standardJson)
            val island = standardRoot.optJSONObject("param_v2")?.optJSONObject("param_island")
            if (island != null) {
                customRoot.put("param_island", island)
                merged.putString("miui.focus.param.custom", customRoot.toString())
            }
        }.onFailure { error -> AppLogger.w(TAG, "Unable to merge standard island parameters", error) }
        return merged
    }

    private fun buildFallbackNotification(request: RenderRequest): Notification =
        Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(request.displayLyric)
            .setContentText(request.song.title.ifBlank { request.song.displayName })
            .setContentIntent(createContentIntent(request.settings.clickStyle))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()

    private fun BigIslandArea.applyLyrics(
        active: XiaomiSuperIslandSettings,
        displayLyric: String,
        fullLyric: String,
        title: String,
        subtitle: String,
        islandKey: String?
    ) {
        val showCover = islandKey != null &&
            (active.lyricMode != XiaomiSuperIslandSettings.LYRIC_MODE_FULL || active.fullLyricShowLeftCover)
        val leftLimit = XiaomiSuperIslandLyricLayout.weightForCharacters(
            if (showCover) active.leftWithCoverTextChars else active.leftWithoutCoverTextChars
        )
        val rightLimit = XiaomiSuperIslandLyricLayout.weightForCharacters(active.rightTextChars)
        val split = if (active.lyricMode == XiaomiSuperIslandSettings.LYRIC_MODE_FULL) {
            XiaomiSuperIslandLyricLayout.splitFullLyric(fullLyric, showCover, leftLimit, rightLimit)
        } else {
            XiaomiSuperIslandLyricLayout.Split(
                left = XiaomiSuperIslandLyricLayout.takeByWeight(
                    listOf(title, subtitle).filter { it.isNotBlank() }.joinToString(" - "), leftLimit
                ),
                right = XiaomiSuperIslandLyricLayout.takeByWeight(displayLyric, rightLimit)
            )
        }
        imageTextInfoLeft {
            type = 1
            if (showCover) picInfo { type = 1; pic = islandKey }
            textInfo {
                this.title = split.left.ifBlank { lyricPlaceholder }
                showHighlightColor = active.textColorEnabled
                narrowFont = false
            }
        }
        textInfo = TextInfo().apply {
            this.title = split.right.ifBlank { lyricPlaceholder }
            showHighlightColor = active.textColorEnabled
            narrowFont = false
        }
    }

    private fun createMediaCommandIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            appContext,
            requestCode,
            Intent(appContext, PlayerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createMediaAction(bundle: Bundle, key: String, requestCode: Int, action: String, icon: Int, title: String): String {
        val pending = PendingIntent.getService(
            appContext,
            requestCode,
            Intent(appContext, PlayerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        bundle.putParcelable(key, Notification.Action.Builder(Icon.createWithResource(appContext, icon), title, pending).build())
        return key
    }

    private fun createContentIntent(clickStyle: Int): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (clickStyle == XiaomiSuperIslandSettings.CLICK_STYLE_MEDIA_CONTROLS) {
                putExtra("open_player", true)
            }
        }
        return PendingIntent.getActivity(
            appContext,
            3600 + clickStyle,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildShareContent(song: AudioFile, lyric: String, format: Int): String {
        val title = song.title.ifBlank { song.displayName }.ifBlank { unknownSong }
        val artist = song.artist.ifBlank { unknownArtist }
        return when (format) {
            XiaomiSuperIslandSettings.SHARE_FORMAT_INLINE -> "$lyric - $artist，$title"
            XiaomiSuperIslandSettings.SHARE_FORMAT_ARTIST_AND_SONG -> "$lyric\n$artist，$title"
            else -> "$lyric\n$title by $artist"
        }
    }

    private fun cachedArtwork(source: Bitmap?): ArtworkCache? {
        if (source == null || source.isRecycled) return null
        artwork?.takeIf { it.source === source }?.let { return it }
        val value = ArtworkCache(
            source,
            Icon.createWithBitmap(scale(source, 120)),
            Icon.createWithBitmap(scale(source, 88)),
            Icon.createWithBitmap(scale(source, 224)),
            scale(source, 116),
            scale(source, 64)
        )
        artwork = value
        return value
    }

    private fun scale(source: Bitmap, size: Int): Bitmap =
        if (source.width == size && source.height == size) source else
            Bitmap.createScaledBitmap(source, size, size, true)

    private fun resolveAccent(active: XiaomiSuperIslandSettings, artwork: Bitmap?): Int {
        if (active.colorSource == XiaomiSuperIslandSettings.COLOR_SOURCE_CUSTOM) return active.customColor
        return artwork?.let { Palette.from(it).generate().getVibrantColor(DEFAULT_ACCENT) } ?: DEFAULT_ACCENT
    }

    private fun ensureChannel() {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = channelDescription
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private val lyricPlaceholder: String
        get() = appContext.getString(R.string.super_island_lyric_placeholder)
    private val actionPrevious: String
        get() = appContext.getString(R.string.super_island_action_previous)
    private val actionPlayPause: String
        get() = appContext.getString(R.string.super_island_action_play_pause)
    private val actionNext: String
        get() = appContext.getString(R.string.super_island_action_next)
    private val unknownSong: String
        get() = appContext.getString(R.string.super_island_unknown_song)
    private val unknownArtist: String
        get() = appContext.getString(R.string.super_island_unknown_artist)
    private val channelName: String
        get() = appContext.getString(R.string.super_island_channel_name)
    private val channelDescription: String
        get() = appContext.getString(R.string.super_island_channel_description)
}
