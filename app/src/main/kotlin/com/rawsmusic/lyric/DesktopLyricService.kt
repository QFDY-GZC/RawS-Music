package com.rawsmusic.lyric

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.rawsmusic.R
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.model.LyricWord
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.PlayerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A transparent desktop lyric overlay backed by RawSMusic's playback timeline.
 * It intentionally owns no playback state: controls are routed back to PlayerService.
 */
class DesktopLyricService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var notificationManager: NotificationManager
    private var rootView: LinearLayout? = null
    private var lyricView: DesktopLyricView? = null
    private var controlsView: LinearLayout? = null
    private var playPauseButton: ImageButton? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var playbackJob: Job? = null
    private var playbackSongIdentity: String? = null

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var movedDuringTouch = false
    private var longPressTriggered = false
    private var lastTapTimeMs = 0L

    private val hideControlsRunnable = Runnable { hideControls() }
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        showControlsWithAnimation()
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        collectLyrics()
        collectPlayback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> disableAndStop()
            ACTION_UNLOCK -> {
                AppPreferences.Lyrics.desktopLyricLocked = false
                applySettings(recreate = false, revealControls = true)
            }
            ACTION_APPLY_SETTINGS -> applySettings(recreate = false)
            ACTION_RESET_POSITION -> resetPosition()
            ACTION_FONT_SMALLER -> updateFontScale(-10)
            ACTION_FONT_LARGER -> updateFontScale(10)
            else -> showOverlayIfAllowed()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateVisibility()
        updateWindowLayout()
    }

    override fun onDestroy() {
        rootView?.removeCallbacks(hideControlsRunnable)
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        playbackJob?.cancel()
        serviceScope.cancel()
        notificationManager.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun collectLyrics() {
        serviceScope.launch {
            PlayerService.currentLyrics.collectLatest { data ->
                // Audio-focus loss can briefly clear the service payload. Keep the last valid
                // lyric for the same song; a real song change is cleared by collectPlayback.
                if (data != null && !data.isEmpty) {
                    lyricView?.setLyrics(data)
                }
            }
        }
    }

    private fun collectPlayback() {
        playbackJob?.cancel()
        playbackJob = serviceScope.launch {
            while (isActive) {
                val controller = PlayerService.currentRuntimeController()
                if (controller != null) {
                    val song = controller.currentSong.value
                    val identity = song?.let {
                        "${it.path}|${it.cueTrackIndex}|${it.cueOffsetMs}"
                    }
                    if (identity != playbackSongIdentity) {
                        playbackSongIdentity = identity
                        lyricView?.setLyrics(null)
                        PlayerService.currentLyrics.value
                            ?.takeUnless { it.isEmpty }
                            ?.let { lyricView?.setLyrics(it) }
                    }
                    val position = controller.position.value
                    val playing = controller.playState.value == PlayState.PLAYING
                    lyricView?.setPlayback(position, playing)
                    playPauseButton?.setImageResource(
                        if (playing) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    updateVisibility(playing)
                }
                delay(POSITION_POLL_MS)
            }
        }
    }

    private fun showOverlayIfAllowed() {
        if (!AppPreferences.Lyrics.desktopLyricEnabled || !canDrawOverlay()) {
            stopSelf()
            return
        }
        if (rootView == null) addOverlay()
        applySettings(recreate = false, revealControls = false)
        lyricView?.setLyrics(PlayerService.currentLyrics.value)
    }

    private fun addOverlay() {
        val statusBarMode = AppPreferences.Lyrics.desktopLyricStatusBarMode
        val locked = AppPreferences.Lyrics.desktopLyricLocked
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val lyric = DesktopLyricView(this).apply {
            touchHandler = ::onDrag
        }
        val controls = createControls()
        root.addView(
            lyric,
            LinearLayout.LayoutParams(overlayWidth(), overlayHeight())
        )
        root.addView(
            controls,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(44)
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )

        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            if (statusBarMode || locked) {
                baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                baseFlags
            },
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = if (statusBarMode) statusBarWindowX() else AppPreferences.Lyrics.desktopLyricX
                .takeUnless { it == Int.MIN_VALUE } ?: 0
            y = if (statusBarMode) {
                dp(AppPreferences.Lyrics.desktopLyricStatusTopOffset)
            } else AppPreferences.Lyrics.desktopLyricY
                .takeUnless { it == Int.MIN_VALUE } ?: dp(96)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        rootView = root
        lyricView = lyric
        controlsView = controls
        layoutParams = params
        controls.visibility = View.GONE
        windowManager.addView(root, params)
        clampToScreen(root, params)
        windowManager.updateViewLayout(root, params)
    }

    private fun createControls(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(4), dp(5), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(176, 30, 30, 30))
            }
            addIconControl(R.drawable.ic_skip_previous, R.string.desktop_lyric_previous) {
                dispatchPlayerAction(PlayerService.ACTION_PREVIOUS)
            }
            playPauseButton = addIconControl(
                R.drawable.ic_play,
                R.string.desktop_lyric_play_pause
            ) {
                dispatchPlayerAction(PlayerService.ACTION_TOGGLE_PLAYBACK)
            }
            addIconControl(R.drawable.ic_skip_next, R.string.desktop_lyric_next) {
                dispatchPlayerAction(PlayerService.ACTION_NEXT)
            }
            addIconControl(
                R.drawable.ic_desktop_lyric_text_smaller,
                R.string.desktop_lyric_font_smaller
            ) { updateFontScale(-10) }
            addIconControl(
                R.drawable.ic_desktop_lyric_text_larger,
                R.string.desktop_lyric_font_larger
            ) { updateFontScale(10) }
            addIconControl(
                R.drawable.ic_desktop_lyric_palette,
                R.string.desktop_lyric_change_color
            ) { cycleTextColor() }
            addTextControl("L", R.string.desktop_lyric_lock) { setLocked(true) }
            addIconControl(R.drawable.ic_desktop_lyric_close, R.string.desktop_lyric_close) {
                disableAndStop()
            }
        }
    }

    private fun LinearLayout.addIconControl(
        icon: Int,
        description: Int,
        action: () -> Unit
    ): ImageButton {
        val button = ImageButton(context).apply {
            setImageResource(icon)
            setColorFilter(Color.WHITE)
            contentDescription = getString(description)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = controlBackground()
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                action()
                scheduleControlsAutoHide()
            }
        }
        addView(button, controlLayoutParams())
        return button
    }

    private fun LinearLayout.addTextControl(label: String, description: Int, action: () -> Unit) {
        addView(TextView(context).apply {
            text = label
            contentDescription = getString(description)
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = controlBackground()
            setOnClickListener {
                action()
                scheduleControlsAutoHide()
            }
        }, controlLayoutParams())
    }

    private fun controlLayoutParams() = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
        marginStart = dp(2)
        marginEnd = dp(2)
    }

    private fun controlBackground() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.argb(52, 255, 255, 255))
    }

    private fun dispatchPlayerAction(action: String) {
        runCatching {
            startService(Intent(this, PlayerService::class.java).setAction(action))
        }
    }

    private fun applySettings(recreate: Boolean, revealControls: Boolean = false) {
        if (!AppPreferences.Lyrics.desktopLyricEnabled) {
            disableAndStop()
            return
        }
        if (!canDrawOverlay()) {
            stopSelf()
            return
        }
        if (recreate && rootView != null) {
            rootView?.let { runCatching { windowManager.removeView(it) } }
            rootView = null
            lyricView = null
            controlsView = null
            layoutParams = null
            addOverlay()
        } else if (rootView == null) {
            addOverlay()
        }
        lyricView?.applyPreferences()
        lyricView?.setLyrics(PlayerService.currentLyrics.value)
        updateWindowLayout()
        setLocked(AppPreferences.Lyrics.desktopLyricLocked, revealControls)
        updateVisibility()
    }

    private fun updateWindowLayout() {
        val root = rootView ?: return
        val params = layoutParams ?: return
        val statusBarMode = AppPreferences.Lyrics.desktopLyricStatusBarMode
        lyricView?.layoutParams = lyricView?.layoutParams?.apply {
            width = overlayWidth()
            height = overlayHeight()
        }
        if (statusBarMode) {
            params.x = statusBarWindowX()
            params.y = dp(AppPreferences.Lyrics.desktopLyricStatusTopOffset)
        }
        clampToScreen(root, params)
        runCatching { windowManager.updateViewLayout(root, params) }
    }

    private fun updateVisibility(playing: Boolean? = null) {
        val isPlaying = playing ?: (
            PlayerService.currentRuntimeController()?.playState?.value == PlayState.PLAYING
        )
        val statusMode = AppPreferences.Lyrics.desktopLyricStatusBarMode
        val hidePaused = if (statusMode) {
            AppPreferences.Lyrics.desktopLyricStatusHideWhenPaused
        } else {
            AppPreferences.Lyrics.desktopLyricHideWhenPaused
        }
        val hideLandscape = if (statusMode) {
            AppPreferences.Lyrics.desktopLyricStatusHideInLandscape
        } else {
            AppPreferences.Lyrics.desktopLyricHideInLandscape
        }
        val hidden = (hidePaused && !isPlaying) ||
            (
                hideLandscape &&
                    resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                )
        rootView?.visibility = if (hidden) View.GONE else View.VISIBLE
    }

    private fun setLocked(lock: Boolean, revealControls: Boolean = true) {
        AppPreferences.Lyrics.desktopLyricLocked = lock
        val statusBarMode = AppPreferences.Lyrics.desktopLyricStatusBarMode
        val params = layoutParams ?: return
        params.flags = if (lock || statusBarMode) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        controlsView?.visibility =
            if (!lock && !statusBarMode && revealControls) View.VISIBLE else View.GONE
        rootView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
        if (lock && !statusBarMode) postUnlockNotification() else {
            notificationManager.cancel(NOTIFICATION_ID)
        }
        if (!lock && revealControls) scheduleControlsAutoHide()
    }

    private fun updateFontScale(delta: Int) {
        AppPreferences.Lyrics.desktopLyricFontScale =
            AppPreferences.Lyrics.desktopLyricFontScale + delta
        lyricView?.applyPreferences()
    }

    private fun cycleTextColor() {
        val current = AppPreferences.Lyrics.desktopLyricTextColor
        val index = QUICK_COLORS.indexOf(current).takeIf { it >= 0 } ?: 0
        AppPreferences.Lyrics.desktopLyricTextColor =
            QUICK_COLORS[(index + 1) % QUICK_COLORS.size]
        lyricView?.applyPreferences()
    }

    private fun resetPosition() {
        AppPreferences.Lyrics.desktopLyricX = 0
        AppPreferences.Lyrics.desktopLyricY = dp(96)
        layoutParams?.let { params ->
            params.x = 0
            params.y = dp(96)
            rootView?.let { windowManager.updateViewLayout(it, params) }
        }
    }

    private fun disableAndStop() {
        AppPreferences.Lyrics.desktopLyricEnabled = false
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        lyricView = null
        controlsView = null
        playPauseButton = null
        layoutParams = null
        notificationManager.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun onDrag(view: View, event: MotionEvent): Boolean {
        if (AppPreferences.Lyrics.desktopLyricLocked ||
            AppPreferences.Lyrics.desktopLyricStatusBarMode
        ) return false
        val params = layoutParams ?: return false
        val root = rootView ?: view
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = params.x
                startY = params.y
                movedDuringTouch = false
                longPressTriggered = false
                root.removeCallbacks(longPressRunnable)
                root.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (abs(dx) > dp(4) || abs(dy) > dp(4)) {
                    movedDuringTouch = true
                    root.removeCallbacks(longPressRunnable)
                }
                params.x = startX + dx.roundToInt()
                params.y = startY + dy.roundToInt()
                clampToScreen(root, params)
                windowManager.updateViewLayout(root, params)
            }
            MotionEvent.ACTION_UP -> {
                root.removeCallbacks(longPressRunnable)
                if (!movedDuringTouch && !longPressTriggered) handleTap()
                AppPreferences.Lyrics.desktopLyricX = params.x
                AppPreferences.Lyrics.desktopLyricY = params.y
            }
            MotionEvent.ACTION_CANCEL -> root.removeCallbacks(longPressRunnable)
        }
        return true
    }

    private fun handleTap() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapTimeMs <= DOUBLE_TAP_TIMEOUT_MS) {
            showControlsWithAnimation()
            lastTapTimeMs = 0L
        } else {
            lastTapTimeMs = now
        }
    }

    private fun scheduleControlsAutoHide() {
        rootView?.removeCallbacks(hideControlsRunnable)
        rootView?.postDelayed(hideControlsRunnable, CONTROLS_AUTO_HIDE_MS)
    }

    private fun showControlsWithAnimation() {
        if (AppPreferences.Lyrics.desktopLyricLocked ||
            AppPreferences.Lyrics.desktopLyricStatusBarMode
        ) return
        controlsView?.apply {
            animate().cancel()
            visibility = View.VISIBLE
            alpha = 0f
            scaleX = CONTROL_HIDDEN_SCALE
            scaleY = CONTROL_HIDDEN_SCALE
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(CONTROL_ANIMATION_MS)
                .start()
        }
        scheduleControlsAutoHide()
    }

    private fun hideControls() {
        if (!AppPreferences.Lyrics.desktopLyricLocked) {
            controlsView?.apply {
                animate().cancel()
                animate()
                    .alpha(0f)
                    .scaleX(CONTROL_HIDDEN_SCALE)
                    .scaleY(CONTROL_HIDDEN_SCALE)
                    .setDuration(CONTROL_ANIMATION_MS)
                    .withEndAction {
                        visibility = View.GONE
                        alpha = 1f
                        scaleX = 1f
                        scaleY = 1f
                    }
                    .start()
            }
        }
    }

    private fun clampToScreen(view: View, params: WindowManager.LayoutParams) {
        if (AppPreferences.Lyrics.desktopLyricStatusBarMode) return
        val width = view.width.takeIf { it > 0 } ?: overlayWidth()
        val height = view.height.takeIf { it > 0 } ?: overlayHeight()
        val maxX = (resources.displayMetrics.widthPixels / 2 - width / 2).coerceAtLeast(0)
        val maxY = (resources.displayMetrics.heightPixels - height).coerceAtLeast(0)
        params.x = params.x.coerceIn(-maxX, maxX)
        params.y = params.y.coerceIn(-statusBarHeight(), maxY)
    }

    private fun overlayWidth(): Int {
        val percent = if (AppPreferences.Lyrics.desktopLyricStatusBarMode) {
            AppPreferences.Lyrics.desktopLyricStatusWidthPercent
        } else {
            AppPreferences.Lyrics.desktopLyricWidthPercent
        }
        return (resources.displayMetrics.widthPixels * percent / 100f)
            .roundToInt()
            .coerceIn(dp(180), resources.displayMetrics.widthPixels - dp(12))
    }

    private fun overlayHeight(): Int =
        if (AppPreferences.Lyrics.desktopLyricStatusBarMode) dp(82) else dp(150)

    private fun statusBarWindowX(): Int {
        val width = overlayWidth()
        val sideOffset = ((resources.displayMetrics.widthPixels - width) / 2 - dp(8))
            .coerceAtLeast(0)
        val anchored = when (AppPreferences.Lyrics.desktopLyricStatusPosition) {
            0 -> -sideOffset
            2 -> sideOffset
            else -> 0
        }
        return anchored + dp(AppPreferences.Lyrics.desktopLyricStatusXOffset)
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun postUnlockNotification() {
        ensureNotificationChannel()
        val unlockIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, DesktopLyricService::class.java).setAction(ACTION_UNLOCK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        runCatching {
            notificationManager.notify(
                NOTIFICATION_ID,
                builder
                    .setSmallIcon(R.drawable.ic_music_2_fill)
                    .setContentTitle(getString(R.string.desktop_lyric_locked_title))
                    .setContentText(getString(R.string.desktop_lyric_locked_description))
                    .setContentIntent(unlockIntent)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .build()
            )
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.desktop_lyric_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    private fun canDrawOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val ACTION_ENABLE = "com.rawsmusic.action.ENABLE_DESKTOP_LYRIC"
        const val ACTION_HIDE = "com.rawsmusic.action.HIDE_DESKTOP_LYRIC"
        const val ACTION_UNLOCK = "com.rawsmusic.action.UNLOCK_DESKTOP_LYRIC"
        const val ACTION_APPLY_SETTINGS = "com.rawsmusic.action.APPLY_DESKTOP_LYRIC_SETTINGS"
        const val ACTION_RESET_POSITION = "com.rawsmusic.action.RESET_DESKTOP_LYRIC_POSITION"
        const val ACTION_FONT_SMALLER = "com.rawsmusic.action.DESKTOP_LYRIC_FONT_SMALLER"
        const val ACTION_FONT_LARGER = "com.rawsmusic.action.DESKTOP_LYRIC_FONT_LARGER"

        private const val CHANNEL_ID = "raws_desktop_lyric"
        private const val NOTIFICATION_ID = 0x52444C59
        private const val POSITION_POLL_MS = 100L
        private const val CONTROLS_AUTO_HIDE_MS = 4_000L
        private const val DOUBLE_TAP_TIMEOUT_MS = 360L
        private const val LONG_PRESS_TIMEOUT_MS = 460L
        private const val CONTROL_ANIMATION_MS = 220L
        private const val CONTROL_HIDDEN_SCALE = 0.8f
        private val QUICK_COLORS = intArrayOf(
            Color.WHITE,
            Color.rgb(191, 191, 191),
            Color.rgb(145, 205, 255),
            Color.rgb(166, 235, 203),
            Color.rgb(179, 136, 255),
            Color.rgb(255, 188, 214),
            Color.rgb(255, 224, 150)
        )

        fun canDraw(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        fun permissionIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )

        fun sync(context: Context) {
            val enabled = AppPreferences.Lyrics.desktopLyricEnabled && canDraw(context)
            val intent = Intent(context, DesktopLyricService::class.java).setAction(
                if (enabled) ACTION_ENABLE else ACTION_HIDE
            )
            runCatching { context.startService(intent) }
        }

        fun applySettings(context: Context) {
            if (!AppPreferences.Lyrics.desktopLyricEnabled || !canDraw(context)) return
            runCatching {
                context.startService(
                    Intent(context, DesktopLyricService::class.java)
                        .setAction(ACTION_APPLY_SETTINGS)
                )
            }
        }

        fun resetPosition(context: Context) {
            if (!AppPreferences.Lyrics.desktopLyricEnabled || !canDraw(context)) return
            runCatching {
                context.startService(
                    Intent(context, DesktopLyricService::class.java)
                        .setAction(ACTION_RESET_POSITION)
                )
            }
        }
    }
}

private class DesktopLyricView(context: Context) : View(context) {
    var touchHandler: ((View, MotionEvent) -> Boolean)? = null

    private var lyricData: LyricData? = null
    private var anchorPositionMs = 0L
    private var anchorRealtimeMs = SystemClock.elapsedRealtime()
    private var playing = false

    private var textColor = Color.WHITE
    private var opacity = 1f
    private var fontScale = 1f
    private var secondaryScale = 0.88f
    private var statusBarMode = false
    private var showTranslation = true
    private var showRomanization = false
    private var showBackground = true
    private var statusTextAlign = 0
    private var statusVerticalAlign = 0
    private var statusSecondaryMode = 0
    private var statusSecondaryOpacity = 0.67f
    private var statusMergeSecondary = false

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        setShadowLayer(5f, 0f, 1f, Color.argb(190, 0, 0, 0))
    }
    private val highlightPaint = Paint(basePaint)
    private val secondaryPaint = Paint(basePaint).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        applyPreferences()
    }

    fun setLyrics(data: LyricData?) {
        lyricData = data
        invalidate()
    }

    fun setPlayback(positionMs: Long, isPlaying: Boolean) {
        anchorPositionMs = positionMs
        anchorRealtimeMs = SystemClock.elapsedRealtime()
        playing = isPlaying
        invalidate()
    }

    fun applyPreferences() {
        statusBarMode = AppPreferences.Lyrics.desktopLyricStatusBarMode
        textColor = if (statusBarMode) {
            AppPreferences.Lyrics.desktopLyricStatusTextColor
        } else {
            AppPreferences.Lyrics.desktopLyricTextColor
        }
        opacity = (
            if (statusBarMode) AppPreferences.Lyrics.desktopLyricStatusOpacity
            else AppPreferences.Lyrics.desktopLyricOpacity
            ) / 100f
        fontScale = (
            if (statusBarMode) AppPreferences.Lyrics.desktopLyricStatusFontScale
            else AppPreferences.Lyrics.desktopLyricFontScale
            ) / 100f
        secondaryScale = (
            if (statusBarMode) AppPreferences.Lyrics.desktopLyricStatusSecondaryScale
            else AppPreferences.Lyrics.desktopLyricSecondaryScale
            ) / 100f
        showTranslation = AppPreferences.Lyrics.desktopLyricShowTranslation
        showRomanization = AppPreferences.Lyrics.desktopLyricShowRomanization
        showBackground = AppPreferences.Lyrics.desktopLyricShowBackground
        statusTextAlign = AppPreferences.Lyrics.desktopLyricStatusTextAlign
        statusVerticalAlign = AppPreferences.Lyrics.desktopLyricStatusVerticalAlign
        statusSecondaryMode = AppPreferences.Lyrics.desktopLyricStatusSecondary
        statusSecondaryOpacity =
            AppPreferences.Lyrics.desktopLyricStatusSecondaryOpacity / 100f
        statusMergeSecondary = AppPreferences.Lyrics.desktopLyricStatusMergeSecondary
        val lyricTypeface = resolveLyricTypeface()
        basePaint.typeface = lyricTypeface
        highlightPaint.typeface = lyricTypeface
        secondaryPaint.typeface = lyricTypeface
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean =
        touchHandler?.invoke(this, event) ?: true

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = lyricData
        val position = currentPosition()
        val index = data?.findCurrentLine(position) ?: -1
        val line = data?.getLine(index)
        if (line == null) {
            drawCenteredLine(canvas, context.getString(R.string.desktop_lyric_waiting), height * 0.48f)
        } else {
            drawLyricLine(canvas, line, position)
        }
        if (playing) postInvalidateOnAnimation()
    }

    private fun drawLyricLine(canvas: Canvas, line: LyricLine, positionMs: Long) {
        val mainSize = sp(if (statusBarMode) 18f else 25f) * fontScale
        val secondarySize = sp(if (statusBarMode) 12f else 15f) * secondaryScale
        val isRight = if (statusBarMode) {
            statusTextAlign == 2
        } else {
            line.agent == "2" || line.agent.equals("v2", ignoreCase = true)
        }
        val isCentered = statusBarMode && statusTextAlign == 1
        val maxWidth = width - dp(20)
        val originalText = line.text.ifBlank { line.backgroundText.orEmpty() }.ifBlank { "\u266a" }
        val statusSecondary = when (statusSecondaryMode) {
            1 -> line.translation
            2 -> line.romanization
            else -> ""
        }
        val text = if (
            statusBarMode && statusMergeSecondary && statusSecondary.isNotBlank()
        ) {
            "$originalText  $statusSecondary"
        } else {
            originalText
        }
        val lines = mutableListOf<Pair<String, List<LyricWord>>>()
        if (!statusBarMode && showBackground &&
            !line.backgroundText.isNullOrBlank() && line.backgroundText != text
        ) {
            lines += line.backgroundText.orEmpty() to line.backgroundWords
        }
        if (!statusBarMode && showRomanization && line.romanization.isNotBlank()) {
            lines += line.romanization to emptyList()
        }
        if (!statusBarMode && showTranslation && line.translation.isNotBlank()) {
            lines += line.translation to emptyList()
        }
        if (!statusBarMode && showBackground && !line.backgroundTranslation.isNullOrBlank()) {
            lines += line.backgroundTranslation.orEmpty() to emptyList()
        }
        if (statusBarMode && !statusMergeSecondary && statusSecondary.isNotBlank()) {
            lines += statusSecondary to emptyList()
        }

        val lineHeight = mainSize * 1.12f
        val secondaryHeight = secondarySize * 1.2f
        val totalHeight = lineHeight + lines.size * secondaryHeight
        basePaint.textSize = mainSize
        highlightPaint.textSize = mainSize
        secondaryPaint.textSize = secondarySize
        var baseline = when {
            !statusBarMode || statusVerticalAlign == 1 ->
                (height - totalHeight) / 2f - basePaint.fontMetrics.top
            statusVerticalAlign == 2 ->
                height - totalHeight - dp(5) - basePaint.fontMetrics.top
            else -> dp(5) - basePaint.fontMetrics.top
        }

        basePaint.color = withAlpha(textColor, (opacity * 0.38f))
        highlightPaint.color = withAlpha(textColor, opacity)
        val clippedText = ellipsize(text, basePaint, maxWidth)
        val x = alignedX(clippedText, basePaint, isRight, isCentered, maxWidth)
        canvas.drawText(clippedText, x, baseline, basePaint)
        val progress = wordProgress(line.words, line, positionMs)
        val wordLift = activeWordLift(line.words, positionMs)
        val textWidth = basePaint.measureText(clippedText)
        val left = if (isRight) x - textWidth else x
        canvas.save()
        canvas.clipRect(
            if (isRight) left + textWidth * (1f - progress) else left,
            baseline + basePaint.fontMetrics.top,
            if (isRight) left + textWidth else left + textWidth * progress,
            baseline + basePaint.fontMetrics.bottom
        )
        canvas.drawText(clippedText, x, baseline - wordLift, highlightPaint)
        canvas.restore()

        secondaryPaint.color = withAlpha(
            textColor,
            opacity * if (statusBarMode) statusSecondaryOpacity else 0.72f
        )
        lines.forEach { (secondary, timedWords) ->
            baseline += secondaryHeight
            val display = ellipsize(secondary, secondaryPaint, maxWidth)
            val secondaryX = alignedX(
                display,
                secondaryPaint,
                isRight,
                isCentered,
                maxWidth
            )
            if (timedWords.isEmpty()) {
                canvas.drawText(display, secondaryX, baseline, secondaryPaint)
            } else {
                secondaryPaint.color = withAlpha(textColor, opacity * 0.34f)
                canvas.drawText(display, secondaryX, baseline, secondaryPaint)
                val timedProgress = wordProgress(timedWords, line, positionMs)
                val secondaryLift = activeWordLift(timedWords, positionMs)
                val secondaryWidth = secondaryPaint.measureText(display)
                canvas.save()
                canvas.clipRect(
                    if (isRight) {
                        secondaryX + secondaryWidth * (1f - timedProgress)
                    } else {
                        secondaryX
                    },
                    baseline + secondaryPaint.fontMetrics.top,
                    if (isRight) {
                        secondaryX + secondaryWidth
                    } else {
                        secondaryX + secondaryWidth * timedProgress
                    },
                    baseline + secondaryPaint.fontMetrics.bottom
                )
                secondaryPaint.color = withAlpha(textColor, opacity * 0.76f)
                canvas.drawText(display, secondaryX, baseline - secondaryLift, secondaryPaint)
                canvas.restore()
                secondaryPaint.color = withAlpha(
                    textColor,
                    opacity * if (statusBarMode) statusSecondaryOpacity else 0.72f
                )
            }
        }
    }

    private fun drawCenteredLine(canvas: Canvas, text: String, centerY: Float) {
        basePaint.textSize = sp(22f) * fontScale
        basePaint.color = withAlpha(textColor, opacity)
        val x = (width - basePaint.measureText(text)) / 2f
        canvas.drawText(text, x, centerY - (basePaint.fontMetrics.ascent + basePaint.fontMetrics.descent) / 2f, basePaint)
    }

    private fun alignedX(
        text: String,
        paint: Paint,
        right: Boolean,
        centered: Boolean,
        maxWidth: Int
    ): Float {
        val actualWidth = paint.measureText(ellipsize(text, paint, maxWidth))
        return when {
            centered -> (width - actualWidth) / 2f
            right -> width - dp(10) - actualWidth
            else -> dp(10).toFloat()
        }
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Int): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "\u2026"
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + ellipsis) > maxWidth) end--
        return text.substring(0, end) + ellipsis
    }

    private fun wordProgress(words: List<LyricWord>, line: LyricLine, positionMs: Long): Float {
        if (words.isEmpty()) {
            val end = line.endTime.takeIf { it > line.timeStamp } ?: (line.timeStamp + 3_000L)
            return ((positionMs - line.timeStamp).toFloat() / (end - line.timeStamp).coerceAtLeast(1L))
                .coerceIn(0f, 1f)
        }
        val totalChars = words.sumOf { it.text.length }.coerceAtLeast(1)
        var completed = 0f
        words.forEach { word ->
            val length = word.text.length.toFloat()
            completed += when {
                positionMs >= word.end -> length
                positionMs <= word.begin -> 0f
                else -> length * (
                    (positionMs - word.begin).toFloat() /
                        (word.end - word.begin).coerceAtLeast(1L)
                    ).coerceIn(0f, 1f)
            }
        }
        return (completed / totalChars).coerceIn(0f, 1f)
    }

    private fun currentPosition(): Long {
        if (!playing) return anchorPositionMs
        return anchorPositionMs + (SystemClock.elapsedRealtime() - anchorRealtimeMs)
    }

    private fun activeWordLift(words: List<LyricWord>, positionMs: Long): Float {
        if (!AppPreferences.UI.lyricKaraokeLiftEnabled) return 0f
        val active = words.firstOrNull { positionMs in it.begin until maxOf(it.end, it.begin + 1L) }
            ?: return 0f
        val progress = (
            (positionMs - active.begin).toFloat() /
                (active.end - active.begin).coerceAtLeast(1L)
            ).coerceIn(0f, 1f)
        return dp(1) * sin(PI * progress).toFloat()
    }

    private fun resolveLyricTypeface(): Typeface {
        if (!AppPreferences.Lyrics.desktopLyricUseLyricFont) {
            return Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val path = AppPreferences.LyricFont.fontPath
        if (path.isBlank() || !File(path).isFile) {
            return Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.Builder(path)
                    .setWeight(AppPreferences.LyricFont.fontWeight)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Typeface.createFromFile(path)
            }
        }.getOrElse { Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb(
            (255 * alpha.coerceIn(0f, 1f)).roundToInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
