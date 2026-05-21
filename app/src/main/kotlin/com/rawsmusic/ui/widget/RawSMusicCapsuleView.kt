package com.rawsmusic.ui.widget

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import coil.load
import com.rawsmusic.R

class RawSMusicCapsuleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val rootLayout: CapsuleRootLayout
    private val foldContainer: NavbarExtension
    private val miniPlayer: LinearLayout
    private val ivCover: ImageView
    private val tvTitle: TextView
    private val tvArtist: TextView
    private val btnPlayPause: ImageButton
    private val seekBar: CapsuleSeekBar
    private val libraryCardContainer: NavbarExtension
    private val btnAlbums: TextView
    private val btnArtists: TextView
    private val btnHomeFromLibrary: TextView
    private val eqCardContainer: NavbarExtension
    private val btnAudioQuality: TextView
    private val btnSoundEffect: TextView
    private val btnUsbDac: TextView
    private val settingsCardContainer: NavbarExtension
    private val btnSettingsDetail: TextView
    private val btnAboutDetail: TextView
    private val navBar: ConstraintLayout

    private val navLibrary: LinearLayout
    private val navEq: LinearLayout
    private val navSearch: LinearLayout
    private val navSettings: LinearLayout
    private val ivNavLibrary: ImageView
    private val ivNavEq: ImageView
    private val ivNavSearch: ImageView
    private val ivNavSettings: ImageView
    private val navLibraryBg: View
    private val navEqBg: View
    private val navSearchBg: View
    private val navSettingsBg: View

    private var isSeekingByUser = false
    private var currentNavId: Int = R.id.nav_library
    private var expandedCard: ExpandedCard = ExpandedCard.NONE
    private var isRotating = false

    private val rotateAnimator by lazy {
        ObjectAnimator.ofFloat(ivCover, View.ROTATION, 0f, 360f).apply {
            duration = 20000L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
        }
    }

    enum class ExpandedCard { NONE, LIBRARY, EQ, SETTINGS }

    var onBarClick: (() -> Unit)? = null
    var onPlayPauseClick: (() -> Unit)? = null
    var onSeekTo: ((Long) -> Unit)? = null
    var onNavClick: ((Int) -> Unit)? = null
    var onAlbumsClick: (() -> Unit)? = null
    var onArtistsClick: (() -> Unit)? = null
    var onHomeClick: (() -> Unit)? = null
    var onAudioQualityClick: (() -> Unit)? = null
    var onSoundEffectClick: (() -> Unit)? = null
    var onUsbDacClick: (() -> Unit)? = null
    var onSettingsClick: (() -> Unit)? = null
    var onAboutClick: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_rawsmusic_capsule, this, true)

        rootLayout = findViewById(R.id.capsule_root)
        foldContainer = findViewById(R.id.fold_container)
        miniPlayer = findViewById(R.id.mini_player)
        ivCover = findViewById(R.id.iv_cover)
        tvTitle = findViewById(R.id.tv_title)
        tvArtist = findViewById(R.id.tv_artist)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        seekBar = findViewById(R.id.seek_bar)
        libraryCardContainer = findViewById(R.id.library_card_container)
        btnAlbums = findViewById(R.id.btn_albums)
        btnArtists = findViewById(R.id.btn_artists)
        btnHomeFromLibrary = findViewById(R.id.btn_home_from_library)
        eqCardContainer = findViewById(R.id.eq_card_container)
        btnAudioQuality = findViewById(R.id.btn_audio_quality)
        btnSoundEffect = findViewById(R.id.btn_sound_effect)
        btnUsbDac = findViewById(R.id.btn_usb_dac)
        settingsCardContainer = findViewById(R.id.settings_card_container)
        btnSettingsDetail = findViewById(R.id.btn_settings_detail)
        btnAboutDetail = findViewById(R.id.btn_about_detail)
        navBar = findViewById(R.id.nav_bar)

        navLibrary = findViewById(R.id.nav_library)
        navEq = findViewById(R.id.nav_eq)
        navSearch = findViewById(R.id.nav_search)
        navSettings = findViewById(R.id.nav_settings)
        ivNavLibrary = findViewById(R.id.iv_nav_library)
        ivNavEq = findViewById(R.id.iv_nav_eq)
        ivNavSearch = findViewById(R.id.iv_nav_search)
        ivNavSettings = findViewById(R.id.iv_nav_settings)
        navLibraryBg = findViewById(R.id.nav_library_bg)
        navEqBg = findViewById(R.id.nav_eq_bg)
        navSearchBg = findViewById(R.id.nav_search_bg)
        navSettingsBg = findViewById(R.id.nav_settings_bg)

        tvTitle.isSelected = true
        tvArtist.isSelected = true

        libraryCardContainer.setInitiallyFolded()
        eqCardContainer.setInitiallyFolded()
        settingsCardContainer.setInitiallyFolded()

        setupBackgrounds()
        setupListeners()
    }

    private fun setupBackgrounds() {
        val expandedBg: Drawable? = ContextCompat.getDrawable(context, R.drawable.bg_capsule_round)
        val collapsedBg: Drawable? = ContextCompat.getDrawable(context, R.drawable.bg_capsule_round)
        if (expandedBg != null && collapsedBg != null) {
            rootLayout.setBackgrounds(expandedBg, collapsedBg)
        }
        rootLayout.background = expandedBg
    }

    private fun setupListeners() {
        miniPlayer.setOnClickListener {
            onBarClick?.invoke()
        }

        btnPlayPause.setOnClickListener {
            onPlayPauseClick?.invoke()
        }

        seekBar.onSeekStartListener = {
            isSeekingByUser = true
        }

        seekBar.onSeekListener = { fraction ->
            val duration = playerDuration
            if (duration > 0) {
                onSeekTo?.invoke((fraction * duration).toLong())
            }
        }

        seekBar.onSeekStopListener = { fraction ->
            isSeekingByUser = false
            val duration = playerDuration
            if (duration > 0) {
                onSeekTo?.invoke((fraction * duration).toLong())
            }
        }

        navLibrary.setOnClickListener {
            if (expandedCard == ExpandedCard.LIBRARY) {
                collapseAllCards()
            } else {
                collapseAllCards()
                expandCard(ExpandedCard.LIBRARY)
            }
        }
        navEq.setOnClickListener {
            if (expandedCard == ExpandedCard.EQ) {
                collapseAllCards()
            } else {
                collapseAllCards()
                expandCard(ExpandedCard.EQ)
            }
        }
        navSearch.setOnClickListener { onNavClick?.invoke(R.id.nav_search) }
        navSettings.setOnClickListener {
            if (expandedCard == ExpandedCard.SETTINGS) {
                collapseAllCards()
            } else {
                collapseAllCards()
                expandCard(ExpandedCard.SETTINGS)
            }
        }

        btnAlbums.setOnClickListener {
            onAlbumsClick?.invoke()
            collapseAllCards()
        }
        btnArtists.setOnClickListener {
            onArtistsClick?.invoke()
            collapseAllCards()
        }
        btnHomeFromLibrary.setOnClickListener {
            onHomeClick?.invoke()
            collapseAllCards()
        }
        btnAudioQuality.setOnClickListener {
            onAudioQualityClick?.invoke()
            collapseAllCards()
        }
        btnSoundEffect.setOnClickListener {
            onSoundEffectClick?.invoke()
            collapseAllCards()
        }
        btnUsbDac.setOnClickListener {
            onUsbDacClick?.invoke()
            collapseAllCards()
        }
        btnSettingsDetail.setOnClickListener {
            onSettingsClick?.invoke()
            collapseAllCards()
        }
        btnAboutDetail.setOnClickListener {
            onAboutClick?.invoke()
            collapseAllCards()
        }
    }

    private var playerDuration: Long = 0L

    fun isCardExpanded(): Boolean = expandedCard != ExpandedCard.NONE

    private fun expandCard(card: ExpandedCard) {
        expandedCard = card
        when (card) {
            ExpandedCard.LIBRARY -> {
                libraryCardContainer.unfold(300L)
                selectNav(R.id.nav_library)
            }
            ExpandedCard.EQ -> {
                eqCardContainer.unfold(300L)
                selectNav(R.id.nav_eq)
            }
            ExpandedCard.SETTINGS -> {
                settingsCardContainer.unfold(300L)
                selectNav(R.id.nav_settings)
            }
            ExpandedCard.NONE -> {}
        }
    }

    fun collapseAllCards() {
        if (expandedCard == ExpandedCard.NONE) return
        libraryCardContainer.fold(250L)
        eqCardContainer.fold(250L)
        settingsCardContainer.fold(250L)
        expandedCard = ExpandedCard.NONE
    }

    fun expandSettingsCard() {
        collapseAllCards()
        expandCard(ExpandedCard.SETTINGS)
    }

    fun collapseSettingsCard() {
        collapseAllCards()
    }

    fun fold() {
        foldContainer.fold()
        animateRootBg(1f, 350L)
    }

    fun unfold() {
        foldContainer.unfold()
        animateRootBg(0f, 400L)
    }

    fun foldImmediate() {
        foldContainer.foldImmediate()
        rootLayout.foldProgress = 1f
        rootLayout.invalidate()
    }

    fun unfoldImmediate() {
        foldContainer.unfoldImmediate()
        rootLayout.foldProgress = 0f
        rootLayout.invalidate()
    }

    private fun animateRootBg(target: Float, duration: Long) {
        ValueAnimator.ofFloat(rootLayout.foldProgress, target).apply {
            this.duration = duration
            addUpdateListener {
                rootLayout.foldProgress = it.animatedValue as Float
                rootLayout.invalidate()
            }
            start()
        }
    }

    fun hide() {
        animate()
            .translationY(height.toFloat())
            .alpha(0f)
            .setDuration(300)
            .start()
    }

    fun show() {
        animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(300)
            .start()
    }

    fun hideNavBar() {
        navBar.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction { navBar.visibility = View.GONE }
            .start()
    }

    fun showNavBar() {
        navBar.visibility = View.VISIBLE
        navBar.animate()
            .alpha(1f)
            .setDuration(200)
            .start()
    }

    fun updatePlaybackState(isPlaying: Boolean, title: String, artist: String, coverPath: String?) {
        if (alpha == 0f && title.isNotBlank()) {
            animate().alpha(1f).setDuration(300).start()
        }
        lastTitle = title
        lastArtist = artist
        tvTitle.text = title
        tvArtist.text = artist
        tvArtist.visibility = View.VISIBLE
        btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

        if (!coverPath.isNullOrBlank()) {
            ivCover.load(coverPath) {
                crossfade(true)
                error(R.drawable.ic_music_note)
            }
        } else {
            ivCover.setImageResource(R.drawable.ic_music_note)
        }

        if (isPlaying && !isRotating) {
            isRotating = true
            rotateAnimator.start()
        } else if (!isPlaying && isRotating) {
            isRotating = false
            rotateAnimator.cancel()
        }
    }

    private var lastTitle: String = ""
    private var lastArtist: String = ""

    fun updateLyric(lyricText: String?, translationText: String?, hasTranslation: Boolean) {
        if (!lyricText.isNullOrBlank()) {
            // 只在文本真正变化时更新，避免跑马灯重置
            if (tvTitle.text.toString() != lyricText) {
                tvTitle.text = lyricText
                tvTitle.isSelected = true // 保持跑马灯状态
            }
            if (hasTranslation && !translationText.isNullOrBlank()) {
                if (tvArtist.text.toString() != translationText) {
                    tvArtist.text = translationText
                    tvArtist.isSelected = true
                }
                tvArtist.visibility = View.VISIBLE
            } else {
                tvArtist.visibility = View.GONE
            }
        } else {
            // 恢复显示歌曲标题
            if (tvTitle.text.toString() != lastTitle) {
                tvTitle.text = lastTitle
                tvTitle.isSelected = true
            }
            if (tvArtist.text.toString() != lastArtist) {
                tvArtist.text = lastArtist
                tvArtist.isSelected = true
            }
            tvArtist.visibility = View.VISIBLE
        }
    }

    fun clearLyric() {
        tvTitle.text = lastTitle
        tvArtist.text = lastArtist
        tvArtist.visibility = View.VISIBLE
    }

    fun updateProgress(position: Long, duration: Long) {
        playerDuration = duration
        if (!isSeekingByUser) {
            seekBar.setProgress(position, duration)
        }
    }

    fun selectNav(navId: Int) {
        currentNavId = if (navId != R.id.nav_settings) navId else currentNavId
        
        // 更新背景可见性
        navLibraryBg.visibility = if (navId == R.id.nav_library) View.VISIBLE else View.GONE
        navEqBg.visibility = if (navId == R.id.nav_eq) View.VISIBLE else View.GONE
        navSearchBg.visibility = if (navId == R.id.nav_search) View.VISIBLE else View.GONE
        navSettingsBg.visibility = if (navId == R.id.nav_settings) View.VISIBLE else View.GONE
    }
}
