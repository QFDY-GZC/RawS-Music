package com.rawsmusic

import android.net.Uri
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.rawsmusic.core.ui.scene.ExternalPageRenderer
import com.rawsmusic.core.ui.scene.GlobalSearchScope
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.NavigationState
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.module.data.prefs.PlaybackStatsStore
import com.rawsmusic.module.player.dsp.GraphicEQController
import com.rawsmusic.module.player.dsp.DynamicEqController
import com.rawsmusic.module.player.dsp.LoudnessBalanceController
import com.rawsmusic.module.player.dsp.MonoBassController
import com.rawsmusic.module.player.dsp.NativeDSPEngine
import com.rawsmusic.module.player.dsp.MoogLadderController
import com.rawsmusic.ui.analytics.AnalyticsScreen
import com.rawsmusic.ui.playlist.PlaylistDetailScreen
import com.rawsmusic.core.common.model.toAudioFile
import com.rawsmusic.core.common.model.playlistIdentityKey
import com.rawsmusic.ui.playlist.PlaylistScreen
import com.rawsmusic.ui.search.GlobalSearchScreen
import com.rawsmusic.ui.settings.LiquidGlassAudioSettingsScreen
import com.rawsmusic.ui.settings.LiquidGlassBassTrebleBoostScreen
import com.rawsmusic.ui.settings.LiquidGlassCompressorScreen
import com.rawsmusic.ui.settings.LiquidGlassAudioEffectsScreen
import com.rawsmusic.ui.settings.LiquidGlassPEQScreen
import com.rawsmusic.ui.settings.LiquidGlassPanoramic360Screen
import com.rawsmusic.ui.settings.LiquidGlassSpatialSoundScreen
import com.rawsmusic.ui.settings.LiquidGlassSurround360Screen
import com.rawsmusic.ui.settings.LiquidGlassUsbDacSettingsScreen
import com.rawsmusic.ui.settings.TransitionSettingsActivity
import com.rawsmusic.ui.albums.AlbumDetailPageCompose
import com.rawsmusic.ui.webdav.WebDavPageCompose
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.launch

// 设置页面 Activity 映射（internal 供 MainActivity 使用）
internal val SETTINGS_ACTIVITY_MAP = mapOf<NavScene, Class<*>>(
    NavScene.APPEARANCE to com.rawsmusic.ui.settings.AppearanceActivity::class.java,
    NavScene.ALBUM_ART_SETTINGS to com.rawsmusic.ui.settings.AlbumArtActivity::class.java,
    NavScene.GLOBAL_FONT_SETTINGS to com.rawsmusic.ui.settings.GlobalFontSettingsActivity::class.java,
    NavScene.LYRIC_FONT_SETTINGS to com.rawsmusic.ui.settings.LyricFontSettingsActivity::class.java,
    NavScene.LYRIC_MANAGEMENT to com.rawsmusic.ui.settings.LyricManagementActivity::class.java,
    NavScene.PLAYER_INTERFACE to com.rawsmusic.ui.settings.PlayerInterfaceActivity::class.java,
    NavScene.STATUS_BAR_LYRIC to com.rawsmusic.ui.settings.StatusBarLyricActivity::class.java,
    NavScene.WEBDAV_BACKUP to com.rawsmusic.ui.settings.WebDavBackupActivity::class.java,
    NavScene.AUDIO_SETTINGS to com.rawsmusic.ui.settings.AudioSettingsActivity::class.java,
    NavScene.USB_DAC_SETTINGS to com.rawsmusic.ui.settings.UsbDacSettingsActivity::class.java,
    NavScene.ABOUT to com.rawsmusic.ui.settings.AboutActivity::class.java,
    NavScene.SCAN_SETTINGS to com.rawsmusic.ui.settings.ScanSettingsActivity::class.java,
    NavScene.TRANSITION_SETTINGS to TransitionSettingsActivity::class.java,
    NavScene.PERSONALIZATION_SETTINGS to com.rawsmusic.ui.settings.PersonalizationSettingsActivity::class.java,
)

/**
 * app 模块页面渲染器。
 *
 * - 设置子页面：启动独立 Activity（系统默认过渡动画），不修改 NavigationState
 * - 根设置页面：inline 渲染（主页 tab），回调直接启动 Activity
 * - 功能页面：inline 渲染
 */
class AppPageRendererImpl(
    private val navState: NavigationState? = null
) : ExternalPageRenderer {

    /** 启动设置子页面 Activity，不修改 NavigationState */
    private fun launchSettings(context: android.content.Context, scene: NavScene) {
        val cls = SETTINGS_ACTIVITY_MAP[scene] ?: return
        context.startActivity(Intent(context, cls))
    }

    @Composable
    override fun RenderPage(scene: NavScene, onBack: () -> Unit, argument: String): Boolean {
        val context = LocalContext.current
        val activity = context as? MainActivity
        val pc = activity?.playerController

        // ==================== 设置子页面 → 启动 Activity，不渲染 ====================
        if (scene in SETTINGS_ACTIVITY_MAP) {
            // LaunchedEffect(Unit) 确保只启动一次，返回 true 避免底层渲染占位页。
            LaunchedEffect(Unit) {
                launchSettings(context, scene)
                navState?.navigateToSettings()
            }
            return true
        }

        when (scene) {
            // ==================== 功能页面（inline） ====================
            NavScene.WEBDAV -> WebDavPageCompose(onBack = onBack)

            NavScene.ANALYTICS -> {
                val statsStore = PlaybackStatsStore.getInstance(context)
                AnalyticsScreen(statsStore = statsStore, onBack = onBack)
            }

            NavScene.PLAYLISTS,
            NavScene.PLAYLIST_LIST -> {
                val playlistStore = PlaylistStore.getInstance(context)
                PlaylistScreen(
                    playlistStore = playlistStore,
                    onBack = onBack,
                    onPlaylistClick = { playlistId, _ ->
                        navState?.navigateTo(NavScene.PLAYLIST_DETAIL_PAGE, playlistId)
                    }
                )
            }

            NavScene.PLAYLIST_DETAIL_PAGE -> {
                val playlistStore = PlaylistStore.getInstance(context)
                val playlists by playlistStore.playlists.collectAsState()
                val librarySongs by com.rawsmusic.module.data.repository.MusicRepository.songs.collectAsState()
                val scope = rememberCoroutineScope()
                val playlist = playlists.find { it.id == argument }
                PlaylistDetailScreen(
                    playlistStore = playlistStore,
                    playlist = playlist,
                    librarySongs = librarySongs,
                    playingSongId = pc?.currentSong?.collectAsState()?.value?.id ?: -1L,
                    onBack = onBack,
                    onPlaySong = { songs, index ->
                        pc?.playQueue(songs, index)
                    },
                    onRemoveSong = { song ->
                        val playlistId = playlist?.id ?: return@PlaylistDetailScreen
                        scope.launch { playlistStore.removeSongFromPlaylist(playlistId, song.playlistIdentityKey()) }
                    }
                )
            }

            NavScene.SEARCH -> {
                GlobalSearchScreen(
                    initialScope = GlobalSearchScope.fromToken(argument),
                    onBack = onBack,
                    onSongClick = { song -> activity?.playSongFromSearch(song) },
                    onCategoryClick = { scope, key ->
                        val target = when (scope) {
                            GlobalSearchScope.ALBUM -> NavScene.ALBUM_DETAIL
                            GlobalSearchScope.ARTIST -> NavScene.ARTIST_DETAIL
                            GlobalSearchScope.FOLDER -> NavScene.FOLDER_HIERARCHY
                            GlobalSearchScope.GENRE -> NavScene.GENRE_DETAIL
                            GlobalSearchScope.YEAR -> NavScene.YEAR_DETAIL
                            GlobalSearchScope.COMPOSER -> NavScene.COMPOSER_DETAIL
                            GlobalSearchScope.SONG -> null
                        }
                        target?.let { navState?.navigateTo(it, Uri.encode(key)) }
                    },
                    onShuffle = { songs -> activity?.playShuffledSearchResults(songs) }
                )
            }

            NavScene.AUDIO_EFFECTS -> {
                val peqController = remember(pc) {
                    try {
                        pc?.ensurePEQConnected()
                        pc?.peqController
                    } catch (_: Exception) {
                        null
                    }
                }
                val graphicEqController = remember(pc) {
                    try {
                        pc?.ensureGraphicEQConnected()
                        pc?.graphicEqController
                    } catch (_: Exception) {
                        null
                    }
                }
                val experimentalGainController = remember(pc) {
                    try {
                        pc?.ensureExperimentalGainConnected()
                        pc?.experimentalGainController
                    } catch (_: Exception) {
                        null
                    }
                }
                val loudnessBalanceController = remember(pc) {
                    try {
                        pc?.ensureLoudnessBalanceConnected()
                        pc?.loudnessBalanceController
                            ?: LoudnessBalanceController(NativeDSPEngine())
                    } catch (_: Exception) {
                        LoudnessBalanceController(NativeDSPEngine())
                    }
                }
                val monoBassController = remember(pc) {
                    try {
                        pc?.ensureMonoBassConnected()
                        pc?.monoBassController ?: MonoBassController(NativeDSPEngine())
                    } catch (_: Exception) {
                        MonoBassController(NativeDSPEngine())
                    }
                }
                val dynamicEqController = remember(pc) {
                    try {
                        pc?.ensureDynamicEqConnected()
                        pc?.dynamicEqController ?: DynamicEqController(NativeDSPEngine())
                    } catch (_: Exception) {
                        DynamicEqController(NativeDSPEngine())
                    }
                }
                val moogLadderController = remember(pc) {
                    try {
                        pc?.ensureMoogLadderConnected()
                        pc?.moogLadderController ?: MoogLadderController(NativeDSPEngine())
                    } catch (_: Exception) {
                        MoogLadderController(NativeDSPEngine())
                    }
                }
                var pendingPeqExportJson by remember { mutableStateOf<String?>(null) }
                var importedPeqFileContent by remember { mutableStateOf<String?>(null) }
                val peqExportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/json")
                ) { uri: Uri? ->
                    uri?.let { writeJsonToUri(context, it, pendingPeqExportJson.orEmpty()) }
                    pendingPeqExportJson = null
                }
                val peqImportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    uri?.let { importedPeqFileContent = readJsonFromUri(context, it) }
                }
                val fftConvolverController = remember(pc) {
                    try {
                        pc?.ensureFftConvolverConnected()
                        pc?.fftConvolverController
                    } catch (_: Exception) {
                        null
                    }
                }
                val compressorController = remember(pc) {
                    try {
                        pc?.ensureCompressorConnected()
                        pc?.compressorController
                    } catch (_: Exception) {
                        null
                    }
                }
                val bassBoostController = remember(pc) {
                    try {
                        pc?.ensureBassBoostConnected()
                        pc?.bassBoostController
                    } catch (_: Exception) {
                        null
                    }
                }
                val trebleBoostController = remember(pc) {
                    try {
                        pc?.ensureTrebleBoostConnected()
                        pc?.trebleBoostController
                    } catch (_: Exception) {
                        null
                    }
                }
                val speakerOutputElasticityController = remember(pc) {
                    try {
                        pc?.ensureSpeakerOutputElasticityConnected()
                        pc?.speakerOutputElasticityController
                    } catch (_: Exception) {
                        null
                    }
                }
                val surround360Controller = remember(pc) {
                    try {
                        pc?.ensureSurround360Connected()
                        pc?.surround360Controller
                    } catch (_: Exception) {
                        null
                    }
                }
                val panoramic360Controller = remember(pc) {
                    try {
                        pc?.ensurePanoramic360Connected()
                        pc?.panoramic360Controller
                    } catch (_: Exception) {
                        null
                    }
                }

                LiquidGlassAudioEffectsScreen(
                    onNavigateToPEQ = { navState?.navigateTo(NavScene.PEQ) },
                    onTogglePEQ = { enabled -> peqController?.setEnabled(enabled) },
                    peqController = peqController,
                    graphicEqController = graphicEqController,
                    experimentalGainController = experimentalGainController,
                    loudnessBalanceController = loudnessBalanceController,
                    monoBassController = monoBassController,
                    dynamicEqController = dynamicEqController,
                    moogLadderController = moogLadderController,
                    fftConvolverController = fftConvolverController,
                    compressorController = compressorController,
                    bassBoostController = bassBoostController,
                    trebleBoostController = trebleBoostController,
                    speakerOutputElasticityController = speakerOutputElasticityController,
                    surround360Controller = surround360Controller,
                    panoramic360Controller = panoramic360Controller,
                    onExportPeqToFile = { json ->
                        pendingPeqExportJson = json
                        peqExportLauncher.launch("PEQ_preset_${System.currentTimeMillis()}.peq.json")
                    },
                    onImportPeqFromFile = {
                        peqImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    },
                    importedPeqFileContent = importedPeqFileContent,
                    onImportedPeqFileContentConsumed = { importedPeqFileContent = null },
                    onBack = onBack
                )
            }

            NavScene.PEQ -> {
                val controller = remember(pc) {
                    try {
                        pc?.ensurePEQConnected()
                        pc?.peqController
                    } catch (_: Exception) {
                        null
                    }
                }
                var pendingExportJson by remember { mutableStateOf<String?>(null) }
                var importedFileContent by remember { mutableStateOf<String?>(null) }
                val exportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/json")
                ) { uri: Uri? ->
                    uri?.let { writeJsonToUri(context, it, pendingExportJson.orEmpty()) }
                    pendingExportJson = null
                }
                val importLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    uri?.let { importedFileContent = readJsonFromUri(context, it) }
                }

                if (controller != null) {
                    LiquidGlassPEQScreen(
                        peqController = controller,
                        onBack = onBack,
                        onExportToFile = { json ->
                            pendingExportJson = json
                            exportLauncher.launch("PEQ_preset_${System.currentTimeMillis()}.peq.json")
                        },
                        onImportFromFile = {
                            importLauncher.launch(arrayOf("application/json", "*/*"))
                        },
                        importedFileContent = importedFileContent,
                        onImportedFileContentConsumed = { importedFileContent = null }
                    )
                }
            }

            NavScene.SPATIAL_SOUND -> {
                LiquidGlassSpatialSoundScreen(onBack = onBack)
            }

            NavScene.COMPRESSOR -> {
                val controller = remember(pc) {
                    try {
                        pc?.ensureCompressorConnected()
                        pc?.compressorController
                    } catch (_: Exception) {
                        null
                    }
                }
                if (controller != null) {
                    LiquidGlassCompressorScreen(
                        compressorController = controller,
                        onBack = onBack
                    )
                }
            }

            NavScene.BASS_TREBLE_BOOST -> {
                val bassController = remember(pc) {
                    try {
                        pc?.ensureBassBoostConnected()
                        pc?.bassBoostController
                    } catch (_: Exception) {
                        null
                    }
                }
                val trebleController = remember(pc) {
                    try {
                        pc?.ensureTrebleBoostConnected()
                        pc?.trebleBoostController
                    } catch (_: Exception) {
                        null
                    }
                }
                if (bassController != null && trebleController != null) {
                    LiquidGlassBassTrebleBoostScreen(
                        bassBoostController = bassController,
                        trebleBoostController = trebleController,
                        onBack = onBack
                    )
                }
            }

            NavScene.SURROUND_360 -> {
                val controller = remember(pc) {
                    try {
                        pc?.ensureSurround360Connected()
                        pc?.surround360Controller
                    } catch (_: Exception) {
                        null
                    }
                }
                if (controller != null) {
                    LiquidGlassSurround360Screen(
                        controller = controller,
                        onBack = onBack
                    )
                }
            }

            NavScene.PANORAMIC_360 -> {
                val controller = remember(pc) {
                    try {
                        pc?.ensurePanoramic360Connected()
                        pc?.panoramic360Controller
                    } catch (_: Exception) {
                        null
                    }
                }
                if (controller != null) {
                    LiquidGlassPanoramic360Screen(
                        controller = controller,
                        onBack = onBack
                    )
                }
            }

            NavScene.AUDIO_SETTINGS -> {
                LaunchedEffect(Unit) {
                    context.startActivity(
                        Intent(context, com.rawsmusic.ui.settings.AudioSettingsActivity::class.java)
                            .setPackage(context.packageName)
                    )
                    navState?.navigateHome()
                }
            }

            NavScene.USB_DAC_SETTINGS -> {
                LaunchedEffect(Unit) {
                    context.startActivity(
                        Intent(context, com.rawsmusic.ui.settings.UsbDacSettingsActivity::class.java)
                            .setPackage(context.packageName)
                    )
                    navState?.navigateHome()
                }
            }

            NavScene.ALBUM_DETAIL -> {
                val parts = argument.split("|")
                if (parts.size >= 2) {
                    AlbumDetailPageCompose(
                        albumName = parts[0],
                        albumArtist = parts[1],
                        coverPath = parts.getOrElse(2) { "" },
                        onBack = onBack
                    )
                }
            }

            else -> return false
        }
        return true
    }

    private fun writeJsonToUri(context: android.content.Context, uri: Uri, json: String) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            Toast.makeText(context, "预设已保存", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun readJsonFromUri(context: android.content.Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "读取失败: ${e.message}", Toast.LENGTH_SHORT).show()
            null
        }
    }
}
