package com.rawsmusic.helper

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.module.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class MetadataEditField {
    TITLE,
    ARTIST,
    ALBUM,
    ALBUM_ARTIST,
    GENRE,
    COMPOSER,
    YEAR,
    TRACK,
    DISC,
    BPM
}

class MetadataEditorHelper(
    private val activity: Activity,
    private val getPlayerController: () -> PlayerController?,
    private val resolveCoverUri: (AudioFile) -> String,
    private val syncMirrorCover: (String?) -> Unit,
    private val hideActionSheet: () -> Unit,
    private val setCustomCover: (Boolean) -> Unit,
    private val updateCoverRestoreButton: () -> Unit,
    private val onVisibilityChanged: (Boolean) -> Unit = {}
) {
    private var pendingMetadataUri: android.net.Uri? = null
    private var pendingMetadataValues: ContentValues? = null

    private var pendingFfmpegMeta: Map<String, String>? = null
    private var pendingFfmpegFilePath: String? = null
    private var pendingFfmpegUri: android.net.Uri? = null
    private var pendingUpdatedSong: AudioFile? = null
    private var pendingDeleteSong: AudioFile? = null
    private var editingSong: AudioFile? = null

    var isDeleteConfirmShowing by mutableStateOf(false)
        private set

    // Legacy state is kept private to this helper until the old form code is fully removed.
    var isMetadataEditorShowing by mutableStateOf(false)
        private set

    var isMetadataSaving by mutableStateOf(false)
        private set

    var editTitle by mutableStateOf("")
    var editArtist by mutableStateOf("")
    var editAlbum by mutableStateOf("")
    var editAlbumArtist by mutableStateOf("")
    var editGenre by mutableStateOf("")
    var editComposer by mutableStateOf("")
    var editYear by mutableStateOf("")
    var editTrack by mutableStateOf("")
    var editDisc by mutableStateOf("")
    var editBpm by mutableStateOf("")

    var requestedFocusField by mutableStateOf<MetadataEditField?>(null)
        private set

    var onMetadataSaved: ((AudioFile) -> Unit)? = null

    val metadataWriteLauncher: ActivityResultLauncher<IntentSenderRequest> =
        (activity as androidx.activity.ComponentActivity).registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val ffmpegMeta = pendingFfmpegMeta
                val ffmpegPath = pendingFfmpegFilePath
                val ffmpegUri = pendingFfmpegUri
                val updatedSong = pendingUpdatedSong
                if (ffmpegMeta != null && ffmpegPath != null && ffmpegUri != null && updatedSong != null) {
                    doFfmpegWrite(ffmpegPath, ffmpegMeta, ffmpegUri, updatedSong)
                } else {
                    val uri = pendingMetadataUri
                    val values = pendingMetadataValues
                    if (uri != null && values != null) {
                        performMetadataUpdate(uri, values)
                    } else {
                        AppLogger.e("EditMetadata", "Write permission returned without pending metadata")
                        Toast.makeText(activity, "保存状态已失效，请重试", Toast.LENGTH_SHORT).show()
                        isMetadataSaving = false
                    }
                }
            } else {
                Toast.makeText(activity, "写入权限被拒绝", Toast.LENGTH_SHORT).show()
                isMetadataSaving = false
            }
            clearPendingMetadataWrite()
        }

    val coverImageLauncher: ActivityResultLauncher<Intent> =
        (activity as androidx.activity.ComponentActivity).registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = result.data?.data ?: return@registerForActivityResult
                val song = getPlayerController()?.currentSong?.value ?: return@registerForActivityResult
                val cacheDir = File(activity.cacheDir, "custom_covers")
                cacheDir.mkdirs()
                val destFile = File(cacheDir, "${song.id}.jpg")
                try {
                    activity.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    setCustomCover(true)
                    syncMirrorCover(destFile.absolutePath)
                    updateCoverRestoreButton()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

    fun prepareInlineEdit(@Suppress("UNUSED_PARAMETER") initialFocus: MetadataEditField? = null) {
        val song = getPlayerController()?.currentSong?.value ?: return
        editingSong = song
        editTitle = song.title
        editArtist = song.artist
        editAlbum = song.album
        editAlbumArtist = song.albumArtist
        editGenre = song.genre
        editComposer = song.composer
        editYear = if (song.year > 0) song.year.toString() else ""
        editTrack = if (song.trackNumber > 0) song.trackNumber.toString() else ""
        editDisc = if (song.discNumber > 0) song.discNumber.toString() else ""
        editBpm = if (song.bpm > 0) song.bpm.toString() else ""
        isMetadataSaving = false
    }

    fun cancelInlineEdit() {
        if (isMetadataSaving) return
        editingSong = null
    }

    fun valueFor(field: MetadataEditField): String = when (field) {
        MetadataEditField.TITLE -> editTitle
        MetadataEditField.ARTIST -> editArtist
        MetadataEditField.ALBUM -> editAlbum
        MetadataEditField.ALBUM_ARTIST -> editAlbumArtist
        MetadataEditField.GENRE -> editGenre
        MetadataEditField.COMPOSER -> editComposer
        MetadataEditField.YEAR -> editYear
        MetadataEditField.TRACK -> editTrack
        MetadataEditField.DISC -> editDisc
        MetadataEditField.BPM -> editBpm
    }

    fun updateValue(field: MetadataEditField, value: String) {
        when (field) {
            MetadataEditField.TITLE -> editTitle = value
            MetadataEditField.ARTIST -> editArtist = value
            MetadataEditField.ALBUM -> editAlbum = value
            MetadataEditField.ALBUM_ARTIST -> editAlbumArtist = value
            MetadataEditField.GENRE -> editGenre = value
            MetadataEditField.COMPOSER -> editComposer = value
            MetadataEditField.YEAR -> editYear = value
            MetadataEditField.TRACK -> editTrack = value
            MetadataEditField.DISC -> editDisc = value
            MetadataEditField.BPM -> editBpm = value
        }
    }

    fun dismissMetadataEditor() {
        isMetadataEditorShowing = false
    }

    fun markFocusHandled(field: MetadataEditField) {
        if (requestedFocusField == field) requestedFocusField = null
    }

    fun saveMetadata() {
        if (isMetadataSaving) return
        val song = editingSong ?: getPlayerController()?.currentSong?.value ?: return
        val newTitle = editTitle.trim()
        val newArtist = editArtist.trim()
        val newAlbum = editAlbum.trim()
        val newAlbumArtist = editAlbumArtist.trim()
        val newGenre = editGenre.trim()
        val newComposer = editComposer.trim()
        val newYear = editYear.trim().toIntOrNull() ?: 0
        val newTrack = editTrack.trim().toIntOrNull() ?: 0
        val newDisc = editDisc.trim().toIntOrNull() ?: 0
        val newBpm = editBpm.trim().toIntOrNull() ?: 0

        // Blank values are deliberately included so users can remove an existing tag.
        val ffmpegMeta = linkedMapOf(
            "title" to newTitle,
            "artist" to newArtist,
            "album" to newAlbum,
            "album_artist" to newAlbumArtist,
            "genre" to newGenre,
            "composer" to newComposer,
            "date" to newYear.takeIf { it > 0 }?.toString().orEmpty(),
            "track" to newTrack.takeIf { it > 0 }?.toString().orEmpty(),
            "disc" to newDisc.takeIf { it > 0 }?.toString().orEmpty(),
            "bpm" to newBpm.takeIf { it > 0 }?.toString().orEmpty()
        )
        val updatedSong = song.copy(
            title = newTitle,
            artist = newArtist,
            album = newAlbum,
            albumArtist = newAlbumArtist,
            genre = newGenre,
            composer = newComposer,
            year = newYear,
            trackNumber = newTrack,
            discNumber = newDisc,
            bpm = newBpm
        )

        val filePath = song.path
        AppLogger.d("EditMetadata", "Save clicked. FFmpeg write to: $filePath, meta=$ffmpegMeta")

        if (filePath.isBlank() || !File(filePath).exists()) {
            Toast.makeText(activity, "文件不存在: $filePath", Toast.LENGTH_SHORT).show()
            return
        }

        isMetadataSaving = true
        clearPendingMetadataWrite()
        val uri = resolveMediaStoreAudioUri(filePath)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uri != null) {
            try {
                val pendingIntent = MediaStore.createWriteRequest(
                    activity.contentResolver, listOf(uri)
                )
                pendingFfmpegMeta = ffmpegMeta
                pendingFfmpegFilePath = filePath
                pendingFfmpegUri = uri
                pendingUpdatedSong = updatedSong
                metadataWriteLauncher.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                )
            } catch (e: Exception) {
                AppLogger.e("EditMetadata", "createWriteRequest failed", e)
                clearPendingMetadataWrite()
                Toast.makeText(activity, "权限请求失败: ${e.message}", Toast.LENGTH_LONG).show()
                isMetadataSaving = false
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !File(filePath).canWrite()) {
                AppLogger.e("EditMetadata", "No MediaStore item and source is not directly writable: $filePath")
                Toast.makeText(activity, "未在系统媒体库中找到该歌曲，无法获取写入权限", Toast.LENGTH_LONG).show()
                isMetadataSaving = false
                return
            }
            doFfmpegWrite(filePath, ffmpegMeta, uri, updatedSong)
        }
    }

    private fun resolveMediaStoreAudioUri(filePath: String): android.net.Uri? {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        return try {
            activity.contentResolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.DATA} = ?",
                arrayOf(filePath),
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                ContentUris.withAppendedId(collection, cursor.getLong(0))
            }.also { uri ->
                AppLogger.d("EditMetadata", "Resolved MediaStore URI path=$filePath uri=$uri")
            }
        } catch (error: Exception) {
            AppLogger.w("EditMetadata", "Could not resolve MediaStore URI for $filePath", error)
            null
        }
    }

    private fun clearPendingMetadataWrite() {
        pendingMetadataUri = null
        pendingMetadataValues = null
        pendingFfmpegMeta = null
        pendingFfmpegFilePath = null
        pendingFfmpegUri = null
        pendingUpdatedSong = null
    }

    private fun performMetadataUpdate(uri: android.net.Uri, values: ContentValues) {
        try {
            var exists = false
            activity.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), null, null, null)?.use { cursor ->
                exists = cursor.moveToFirst()
            }
            AppLogger.d("EditMetadata", "URI exists: $exists, URI: $uri")

            if (!exists) {
                Toast.makeText(activity, "歌曲未在 MediaStore 中找到", Toast.LENGTH_SHORT).show()
                return
            }

            val safeValues = ContentValues()
            values.getAsString(MediaStore.Audio.Media.TITLE)?.let {
                safeValues.put(MediaStore.Audio.Media.TITLE, it)
            }
            values.getAsString(MediaStore.Audio.Media.ARTIST)?.let {
                safeValues.put(MediaStore.Audio.Media.ARTIST, it)
            }
            values.getAsString(MediaStore.Audio.Media.ALBUM)?.let {
                safeValues.put(MediaStore.Audio.Media.ALBUM, it)
            }
            if (values.containsKey(MediaStore.Audio.Media.YEAR)) {
                safeValues.put(MediaStore.Audio.Media.YEAR, values.getAsInteger(MediaStore.Audio.Media.YEAR))
            }
            if (values.containsKey(MediaStore.Audio.Media.TRACK)) {
                safeValues.put(MediaStore.Audio.Media.TRACK, values.getAsInteger(MediaStore.Audio.Media.TRACK))
            }

            AppLogger.d("EditMetadata", "Safe values: $safeValues")
            val rows = activity.contentResolver.update(uri, safeValues, null, null)
            AppLogger.d("EditMetadata", "Updated rows: $rows")

            if (rows > 0) {
                Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show()
                finishMetadataSave(editingSong ?: getPlayerController()?.currentSong?.value)
            } else {
                Toast.makeText(activity, "保存失败，MediaStore 更新返回 0", Toast.LENGTH_SHORT).show()
                isMetadataSaving = false
            }
        } catch (e: Exception) {
            AppLogger.e("EditMetadata", "Update failed", e)
            Toast.makeText(activity, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            isMetadataSaving = false
        }
    }

    private fun doFfmpegWrite(
        filePath: String,
        ffmpegMeta: Map<String, String>,
        uri: android.net.Uri?,
        updatedSong: AudioFile
    ) {
        (activity as androidx.lifecycle.LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) {
            try {
                val originalFile = File(filePath)
                val parentDir = originalFile.parentFile
                    ?: throw IllegalStateException("音频文件没有可写父目录")
                val extension = originalFile.extension.lowercase()
                val tempFile = File(parentDir, "rawsmeta_tmp${if (extension.isBlank()) "" else ".$extension"}")
                if (tempFile.exists() && !tempFile.delete()) {
                    throw IllegalStateException("无法清理旧的元数据临时文件")
                }

                // TagLib edits a same-filesystem copy and never remuxes the encoded audio frames.
                // Raw ADTS AAC is not a TagLib container, so it uses the FFmpeg path below.
                val tagLibSupported = com.rawsmusic.core.common.taglib.TagLibBridge.isSupported(filePath)
                val ret = if (tagLibSupported) {
                    originalFile.inputStream().buffered().use { input ->
                        tempFile.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                    if (com.rawsmusic.core.common.taglib.TagLibBridge.writeMetadata(
                            tempFile.absolutePath,
                            ffmpegMeta
                        )) 0 else -1
                } else {
                    com.rawsmusic.core.common.ffmpeg.FFmpegBridge.writeMetadata(
                        filePath,
                        ffmpegMeta,
                        parentDir.absolutePath
                    )
                }
                AppLogger.d(
                    "EditMetadata",
                    "Metadata writer=${if (tagLibSupported) "taglib" else "ffmpeg"} ext=$extension ret=$ret"
                )

                if (ret != 0) {
                    tempFile.delete()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "元数据写入失败 (错误码: $ret)", Toast.LENGTH_SHORT).show()
                        isMetadataSaving = false
                    }
                    return@launch
                }

                if (!tempFile.exists() || tempFile.length() == 0L) {
                    AppLogger.e("EditMetadata", "Temp file missing or empty: ${tempFile.absolutePath}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "临时文件不存在或为空", Toast.LENGTH_SHORT).show()
                        isMetadataSaving = false
                    }
                    return@launch
                }

                val tempSize = tempFile.length()
                val originalSize = originalFile.length()
                val tempDuration = com.rawsmusic.core.common.ffmpeg.FFmpegBridge.probeDuration(tempFile.absolutePath)
                // FFmpeg duration probing is unreliable for DSF/DFF and some lossless containers.
                // TagLib has edited an exact byte-for-byte copy, so size + successful save are the
                // appropriate integrity checks for that path.
                val durationLooksValid = tagLibSupported || updatedSong.duration <= 0L ||
                    (tempDuration > 0L && kotlin.math.abs(tempDuration - updatedSong.duration) <= 2_000L)
                AppLogger.d(
                    "EditMetadata",
                    "Prepared temp=${tempFile.absolutePath} size=$tempSize original=$originalSize duration=$tempDuration"
                )

                if (tempSize < originalSize / 2 || !durationLooksValid) {
                    AppLogger.e("EditMetadata", "Temp verification failed size=$tempSize duration=$tempDuration")
                    tempFile.delete()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "临时文件校验失败，原音频未改动", Toast.LENGTH_SHORT).show()
                        isMetadataSaving = false
                    }
                    return@launch
                }

                if (!replaceAudioFileAtomically(originalFile, tempFile)) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "文件替换失败，已恢复原音频", Toast.LENGTH_SHORT).show()
                        isMetadataSaving = false
                    }
                    return@launch
                }

                val safeValues = ContentValues()
                safeValues.put(MediaStore.Audio.Media.TITLE, updatedSong.title)
                safeValues.put(MediaStore.Audio.Media.ARTIST, updatedSong.artist)
                safeValues.put(MediaStore.Audio.Media.ALBUM, updatedSong.album)
                safeValues.put(MediaStore.Audio.Media.YEAR, updatedSong.year)
                safeValues.put(MediaStore.Audio.Media.TRACK, updatedSong.trackNumber)
                safeValues.put(MediaStore.Audio.Media.COMPOSER, updatedSong.composer)
                if (uri != null) {
                    try {
                        activity.contentResolver.update(uri, safeValues, null, null)
                    } catch (e: Exception) {
                        AppLogger.w("EditMetadata", "MediaStore update after FFmpeg write failed", e)
                    }
                }

                try {
                    android.media.MediaScannerConnection.scanFile(
                        activity,
                        arrayOf(filePath),
                        null,
                        null
                    )
                } catch (_: Exception) {}

                val committedSong = updatedSong.copy(
                    fileSize = originalFile.length(),
                    dateModified = originalFile.lastModified()
                )
                MusicRepository.updateSong(committedSong)
                getPlayerController()?.updateCurrentSongIfSamePath(committedSong)

                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show()
                    finishMetadataSave(committedSong)
                }
            } catch (e: Exception) {
                AppLogger.e("EditMetadata", "doFfmpegWrite failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    isMetadataSaving = false
                }
            }
        }
    }

    private fun replaceAudioFileAtomically(original: File, temp: File): Boolean {
        val backup = File(
            original.parentFile,
            ".${original.name}.rawsmeta-${System.nanoTime()}.bak"
        )
        val expectedSize = temp.length()
        val readable = original.canRead()
        val writable = original.canWrite()
        var originalMoved = false
        var replacementMoved = false
        return try {
            originalMoved = original.renameTo(backup)
            if (!originalMoved) {
                AppLogger.e("EditMetadata", "Could not move original to backup: ${original.absolutePath}")
                false
            } else {
                replacementMoved = temp.renameTo(original)
                if (!replacementMoved || !original.exists() || original.length() != expectedSize) {
                    if (replacementMoved) original.delete()
                    val restored = backup.renameTo(original)
                    AppLogger.e("EditMetadata", "Replacement failed; restored=$restored")
                    false
                } else {
                    if (readable) original.setReadable(true, false)
                    if (writable) original.setWritable(true, false)
                    if (!backup.delete()) backup.deleteOnExit()
                    AppLogger.d("EditMetadata", "Atomic metadata replacement committed: ${original.absolutePath}")
                    true
                }
            }
        } catch (error: Throwable) {
            AppLogger.e("EditMetadata", "Atomic replacement crashed", error)
            if (replacementMoved) original.delete()
            if (originalMoved && backup.exists() && !original.exists()) backup.renameTo(original)
            false
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun finishMetadataSave(song: AudioFile?) {
        isMetadataSaving = false
        editingSong = null
        song?.let { onMetadataSaved?.invoke(it) }
    }

    fun pickCoverImage() {
        hideActionSheet()
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        coverImageLauncher.launch(intent)
    }

    fun restoreOriginalCover() {
        val song = getPlayerController()?.currentSong?.value ?: return
        val cacheDir = File(activity.cacheDir, "custom_covers")
        val cachedFile = File(cacheDir, "${song.id}.jpg")
        if (cachedFile.exists()) cachedFile.delete()
        setCustomCover(false)
        val coverUri = resolveCoverUri(song)
        syncMirrorCover(coverUri.ifBlank { null })
        updateCoverRestoreButton()
    }

    fun deleteCurrentSong() {
        val song = getPlayerController()?.currentSong?.value ?: return
        pendingDeleteSong = song
        isDeleteConfirmShowing = true
        onVisibilityChanged(true)
    }

    fun dismissDeleteConfirm() {
        if (!isDeleteConfirmShowing) return
        isDeleteConfirmShowing = false
        pendingDeleteSong = null
        onVisibilityChanged(false)
    }

    fun confirmDeleteCurrentSong() {
        val song = pendingDeleteSong ?: return
        dismissDeleteConfirm()
        getPlayerController()?.next()
        val deleted = MusicRepository.deleteSongFromDevice(activity, song)
        Toast.makeText(activity, if (deleted) "已删除" else "删除失败", Toast.LENGTH_SHORT).show()
    }

    fun pendingDeleteTitle(): String = pendingDeleteSong?.title.orEmpty()
}

@Composable
fun MetadataEditorOverlay(
    helper: MetadataEditorHelper,
    modifier: Modifier = Modifier
) {
    DeleteConfirmOverlay(helper = helper, modifier = modifier)
}

@Composable
private fun MetadataEditorFormOverlay(
    helper: MetadataEditorHelper,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = helper.isMetadataEditorShowing,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120)),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { helper.dismissMetadataEditor() }
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = helper.isMetadataEditorShowing,
                enter = fadeIn(tween(140)) + scaleIn(tween(180), initialScale = 0.95f),
                exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.98f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xF21B1816))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                ) {
                    Text(
                        text = stringResource(R.string.metadata_editor_title),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(390.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        MetadataField(helper, MetadataEditField.TITLE, stringResource(R.string.metadata_field_title), helper.editTitle) { helper.editTitle = it }
                        MetadataField(helper, MetadataEditField.ARTIST, stringResource(R.string.metadata_field_artist), helper.editArtist) { helper.editArtist = it }
                        MetadataField(helper, MetadataEditField.ALBUM, stringResource(R.string.metadata_field_album), helper.editAlbum) { helper.editAlbum = it }
                        MetadataField(helper, MetadataEditField.ALBUM_ARTIST, stringResource(R.string.metadata_field_album_artist), helper.editAlbumArtist) { helper.editAlbumArtist = it }
                        MetadataField(helper, MetadataEditField.GENRE, stringResource(R.string.metadata_field_genre), helper.editGenre) { helper.editGenre = it }
                        MetadataField(helper, MetadataEditField.COMPOSER, stringResource(R.string.metadata_field_composer), helper.editComposer) { helper.editComposer = it }
                        MetadataField(helper, MetadataEditField.YEAR, stringResource(R.string.metadata_field_year), helper.editYear, numeric = true) { helper.editYear = it }
                        MetadataField(helper, MetadataEditField.TRACK, stringResource(R.string.metadata_field_track), helper.editTrack, numeric = true) { helper.editTrack = it }
                        MetadataField(helper, MetadataEditField.DISC, stringResource(R.string.metadata_field_disc), helper.editDisc, numeric = true) { helper.editDisc = it }
                        MetadataField(helper, MetadataEditField.BPM, stringResource(R.string.metadata_field_bpm), helper.editBpm, numeric = true) { helper.editBpm = it }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (helper.isMetadataSaving) {
                            Text(
                                text = stringResource(R.string.metadata_saving),
                                color = Color.White.copy(alpha = 0.58f),
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.metadata_cancel),
                            color = Color.White.copy(alpha = if (helper.isMetadataSaving) 0.35f else 0.65f),
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clickable(enabled = !helper.isMetadataSaving) { helper.dismissMetadataEditor() }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.metadata_save),
                            color = if (helper.isMetadataSaving) Color.White.copy(alpha = 0.35f) else Color(0xFF4CAF50),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable(enabled = !helper.isMetadataSaving) { helper.saveMetadata() }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataField(
    helper: MetadataEditorHelper,
    field: MetadataEditField,
    label: String,
    value: String,
    numeric: Boolean = false,
    onValueChange: (String) -> Unit
) {
    val focusRequester = remember(field) { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val autoFocus = helper.requestedFocusField == field
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            focusRequester.requestFocus()
            keyboardController?.show()
            helper.markFocusHandled(field)
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = !helper.isMetadataSaving,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text
        ),
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White.copy(alpha = 0.88f),
            disabledTextColor = Color.White.copy(alpha = 0.45f),
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color(0xFF4CAF50),
            unfocusedIndicatorColor = Color.White.copy(alpha = 0.22f),
            disabledIndicatorColor = Color.White.copy(alpha = 0.12f),
            focusedLabelColor = Color(0xFF4CAF50),
            unfocusedLabelColor = Color.White.copy(alpha = 0.56f),
            disabledLabelColor = Color.White.copy(alpha = 0.35f),
            cursorColor = Color(0xFF4CAF50)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .padding(bottom = 10.dp)
    )
}

@Composable
private fun DeleteConfirmOverlay(
    helper: MetadataEditorHelper,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = helper.isDeleteConfirmShowing,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120)),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { helper.dismissDeleteConfirm() }
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = helper.isDeleteConfirmShowing,
                enter = fadeIn(tween(140)) + scaleIn(tween(180), initialScale = 0.95f),
                exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.98f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.86f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xF21B1816))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .padding(horizontal = 22.dp, vertical = 20.dp)
                ) {
                    Text(
                        text = "删除歌曲",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "确定要删除\"${helper.pendingDeleteTitle()}\"吗？此操作不可撤销。",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(22.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "取消",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clickable { helper.dismissDeleteConfirm() }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "删除",
                            color = Color(0xFFFF5252),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { helper.confirmDeleteCurrentSong() }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
