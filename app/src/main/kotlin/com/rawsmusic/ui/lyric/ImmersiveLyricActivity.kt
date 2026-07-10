package com.rawsmusic.ui.lyric

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rawsmusic.R
import com.rawsmusic.core.common.model.toLyriconSong
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.widget.ImmersiveBackgroundCompose
import com.rawsmusic.core.ui.widget.player.ComposeLyricView
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.module.player.PlayerService
import com.rawsmusic.module.scanner.LyricReader
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class ImmersiveLyricActivity : ComponentActivity() {

    private var playerController: PlayerController? = null
    private var displayTranslation by mutableStateOf(false)
    private var displayRoma by mutableStateOf(false)
    private var coverPath by mutableStateOf<String?>(null)
    private var lyricSong by mutableStateOf<Song?>(null)
    private var positionMs by mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏沉浸
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // 获取当前 runtime controller，优先复用 Service 持有的播放时环境
        playerController = PlayerService.currentRuntimeController()
            ?: PlayerController.getInstanceOrNull()
            ?: PlayerService.obtainRuntimeController(
                this,
                "immersive_lyric_activity",
                ensureService = true
            )
        displayTranslation = AppPreferences.Lyricon.displayTranslation
        displayRoma = AppPreferences.Lyricon.displayRoma

        setContent {
            ImmersiveLyricScreen(
                coverPath = coverPath,
                lyricSong = lyricSong,
                positionMs = positionMs,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                onBack = { finish() },
                onToggleTranslation = {
                    val newState = !displayTranslation
                    displayTranslation = newState
                    displayRoma = !newState
                    AppPreferences.Lyricon.displayTranslation = newState
                    AppPreferences.Lyricon.displayRoma = !newState
                }
            )
        }

        loadLyrics()
        // 同步播放位置
        observePosition()
    }

    @Composable
    private fun ImmersiveLyricScreen(
        coverPath: String?,
        lyricSong: Song?,
        positionMs: Long,
        displayTranslation: Boolean,
        displayRoma: Boolean,
        onBack: () -> Unit,
        onToggleTranslation: () -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ComposeColor.Black)
        ) {
            ImmersiveBackgroundCompose(
                coverPath = coverPath,
                isDarkMode = !ThemeManager.isLightBackground,
                modifier = Modifier.fillMaxSize()
            )

            ComposeLyricView(
                song = lyricSong,
                positionMs = positionMs,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                topPadding = 0.dp,
                bottomPadding = 96.dp,
                textColor = ComposeColor.White,
                dimColor = ComposeColor.White.copy(alpha = 0.32f),
                secondaryColor = ComposeColor.White.copy(alpha = 0.66f),
                onLineClick = { beginMs -> playerController?.seekTo(beginMs) },
                onSwipeRight = onBack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 340.dp)
            )

            Icon(
                painter = painterResource(id = R.drawable.ic_back),
                contentDescription = null,
                tint = ComposeColor(0xB0FFFFFF),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 8.dp, y = 48.dp)
                    .size(48.dp)
                    .clickable(onClick = onBack)
                    .padding(10.dp)
            )

            Text(
                text = "译",
                color = ComposeColor(0xB0FFFFFF),
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-20).dp, y = (-32).dp)
                    .size(36.dp)
                    .clickable(onClick = onToggleTranslation)
                    .padding(top = 8.dp)
            )
        }
    }

    private fun loadLyrics() {
        val songPath = intent.getStringExtra("song_path") ?: return
        val songTitle = intent.getStringExtra("song_title") ?: ""
        val songArtist = intent.getStringExtra("song_artist") ?: ""

        // 设置封面背景
        val song = playerController?.currentSong?.value
        if (song != null) {
            coverPath = song.coverKey
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val lyricData = LyricReader.readLyrics(songPath)
            val lyriconSong = lyricData.toLyriconSong(songTitle, songArtist)

            launch(Dispatchers.Main) {
                // 同步当前位置
                positionMs = playerController?.position?.value ?: 0L
                lyricSong = lyriconSong
                displayTranslation = AppPreferences.Lyricon.displayTranslation
                displayRoma = AppPreferences.Lyricon.displayRoma
            }
        }
    }

    private fun observePosition() {
        lifecycleScope.launch {
            playerController?.position?.collectLatest { pos ->
                positionMs = pos
            }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
    }
}
