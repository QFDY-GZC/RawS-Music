package com.rawsmusic.ui.transcode

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import com.rawsmusic.transcode.AudioTranscodeForegroundService
import com.rawsmusic.transcode.AudioTranscodePostProcessor
import com.rawsmusic.transcode.AudioTranscodeQueue
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.ui.settings.BaseSettingsActivity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Host Activity for the Scheme-B multi-file audio conversion UI. */
class AudioTranscodeActivity : BaseSettingsActivity() {
    private val importedPaths = MutableStateFlow<List<String>>(emptyList())
    private val directOutputStorageAccess = MutableStateFlow(hasDirectOutputStorageAccess())
    private var pendingDeleteTaskId: String? = null
    private var pendingDeleteSourcePath: String? = null

    private val outputStorageAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val granted = hasDirectOutputStorageAccess()
        directOutputStorageAccess.value = granted
        if (granted) {
            AppNoticeBus.post(
                message = "已获得文件访问权限，可以开始转换",
                icon = AppNoticeIcon.TRANSCODE,
            )
        } else {
            AppNoticeBus.error("未获得文件访问权限，无法写入转换结果")
        }
    }

    private val openAudioDocuments = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val copied = withContext(Dispatchers.IO) {
                uris.mapNotNull(::materializeImportedAudio)
            }
            if (copied.isNotEmpty()) importedPaths.value = copied
        }
    }

    private val deleteSourceConsent = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val taskId = pendingDeleteTaskId
        val sourcePath = pendingDeleteSourcePath
        pendingDeleteTaskId = null
        pendingDeleteSourcePath = null
        if (taskId.isNullOrBlank() || sourcePath.isNullOrBlank()) {
            return@registerForActivityResult
        }
        if (result.resultCode != Activity.RESULT_OK) {
            AudioTranscodeQueue.markAuthorizedSourceDeleteResult(
                id = taskId,
                deleted = false,
                detail = "用户未授权删除源文件，已保留原文件",
            )
            return@registerForActivityResult
        }
        val deleted = AudioTranscodePostProcessor.finishAuthorizedSourceDelete(
            context = applicationContext,
            sourcePath = sourcePath,
        )
        AudioTranscodeQueue.markAuthorizedSourceDeleteResult(
            id = taskId,
            deleted = deleted,
            detail = if (deleted) {
                "系统授权后源文件已删除"
            } else {
                "系统授权已返回，但源文件仍存在"
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AudioTranscodeQueue.initialize(applicationContext)
        AudioTranscodeForegroundService.sync(applicationContext)
        // This Activity can be opened directly from settings or an external intent before
        // MainActivity has requested its deferred library warm start. Make the in-app picker own
        // that dependency so "软件内添加" never opens against a permanently empty snapshot.
        MusicRepository.warmStartCacheAsync("audio_transcode_activity")
        val initialPaths = intent
            .getStringArrayListExtra(EXTRA_AUDIO_PATHS)
            ?.filter(String::isNotBlank)
            .orEmpty()

        setContent {
            val librarySongs by MusicRepository.songs.collectAsState()
            val imports by importedPaths.collectAsState()
            val outputAccessGranted by directOutputStorageAccess.collectAsState()
            // AudioTranscodeScreen owns MIUIX overlay/dialog content (library picker,
            // per-track settings and dropdown windows). BaseSettingsActivity itself only
            // provides a Compose Box, so give this feature a real MIUIX root scaffold.
            // Without it, root-scaffold overlays can mount without an interactive host.
            top.yukonga.miuix.kmp.basic.Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                AudioTranscodeScreen(
                    librarySongs = librarySongs,
                    initialPaths = initialPaths,
                    importedPaths = imports,
                    onImportedPathsConsumed = { importedPaths.value = emptyList() },
                    hasDirectOutputStorageAccess = outputAccessGranted,
                    onRequestDirectOutputStorageAccess = ::requestDirectOutputStorageAccess,
                    onPickExternalFiles = {
                        openAudioDocuments.launch(
                            arrayOf(
                                "audio/*",
                                "application/octet-stream",
                                "application/ogg",
                                "application/x-ogg",
                                "application/x-flac",
                            )
                        )
                    },
                    onAuthorizeSourceDelete = ::requestSourceDeleteConsent,
                    onBack = { finish() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        directOutputStorageAccess.value = hasDirectOutputStorageAccess()
    }

    private fun requestDirectOutputStorageAccess() {
        if (hasDirectOutputStorageAccess()) {
            directOutputStorageAccess.value = true
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                outputStorageAccessLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }.recoverCatching {
                outputStorageAccessLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                )
            }.onFailure {
                AppNoticeBus.error("无法打开文件访问权限设置")
            }
        }
    }

    private fun hasDirectOutputStorageAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun requestSourceDeleteConsent(taskId: String, sourcePath: String) {
        val request = AudioTranscodePostProcessor.createSourceDeleteConsentRequest(
            context = applicationContext,
            sourcePath = sourcePath,
        )
        if (request.alreadyDeleted) {
            AudioTranscodePostProcessor.finishAuthorizedSourceDelete(applicationContext, sourcePath)
            AudioTranscodeQueue.markAuthorizedSourceDeleteResult(
                id = taskId,
                deleted = true,
                detail = "源文件已不存在",
            )
            return
        }
        val intentSender = request.intentSender
        if (intentSender == null) {
            AudioTranscodeQueue.markAuthorizedSourceDeleteResult(
                id = taskId,
                deleted = false,
                detail = request.detail ?: "无法发起系统删除授权",
            )
            return
        }
        pendingDeleteTaskId = taskId
        pendingDeleteSourcePath = sourcePath
        deleteSourceConsent.launch(IntentSenderRequest.Builder(intentSender).build())
    }

    private fun materializeImportedAudio(uri: Uri): String? {
        val displayName = queryDisplayName(uri).orEmpty().ifBlank { "audio-${UUID.randomUUID()}" }
        val cleanName = displayName
            .replace(Regex("[\\/:*?\"<>|]"), "_")
            .take(180)
            .ifBlank { "audio-${UUID.randomUUID()}" }
        val importDir = File(cacheDir, "transcode-import").apply { mkdirs() }
        var target = File(importDir, cleanName)
        if (target.exists()) {
            val stem = target.nameWithoutExtension.ifBlank { "audio" }
            val ext = target.extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
            target = File(importDir, "$stem-${UUID.randomUUID().toString().take(8)}$ext")
        }
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().buffered().use(input::copyTo)
            } ?: return null
            target.takeIf { it.isFile && it.length() > 0L }?.absolutePath
        }.getOrNull()
    }

    private fun queryDisplayName(uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )
            if (cursor?.moveToFirst() == true) cursor.getString(0) else null
        } catch (_: Throwable) {
            null
        } finally {
            cursor?.close()
        }
    }

    companion object {
        private const val EXTRA_AUDIO_PATHS = "audio_transcode_paths"

        fun createIntent(context: Context, songs: List<AudioFile> = emptyList()): Intent =
            Intent(context, AudioTranscodeActivity::class.java).apply {
                putStringArrayListExtra(
                    EXTRA_AUDIO_PATHS,
                    ArrayList(songs.map(AudioFile::path).filter(String::isNotBlank).distinct()),
                )
            }
    }
}
