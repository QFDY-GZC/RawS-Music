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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.module.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

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
    private var pendingFfmpegTitle: String? = null
    private var pendingFfmpegArtist: String? = null
    private var pendingFfmpegAlbum: String? = null
    private var pendingDeleteSong: AudioFile? = null
    private var editingSong: AudioFile? = null

    var isDeleteConfirmShowing by mutableStateOf(false)
        private set

    var isMetadataEditorShowing by mutableStateOf(false)
        private set

    var isMetadataSaving by mutableStateOf(false)
        private set

    var editTitle by mutableStateOf("")
    var editArtist by mutableStateOf("")
    var editAlbum by mutableStateOf("")
    var editGenre by mutableStateOf("")
    var editYear by mutableStateOf("")
    var editTrack by mutableStateOf("")

    val metadataWriteLauncher: ActivityResultLauncher<IntentSenderRequest> =
        (activity as androidx.activity.ComponentActivity).registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val ffmpegMeta = pendingFfmpegMeta
                val ffmpegPath = pendingFfmpegFilePath
                val ffmpegUri = pendingFfmpegUri
                if (ffmpegMeta != null && ffmpegPath != null && ffmpegUri != null) {
                    doFfmpegWrite(ffmpegPath, ffmpegMeta, ffmpegUri,
                        pendingFfmpegTitle ?: "", pendingFfmpegArtist ?: "",
                        pendingFfmpegAlbum ?: "")
                } else {
                    val uri = pendingMetadataUri ?: return@registerForActivityResult
                    val values = pendingMetadataValues ?: return@registerForActivityResult
                    performMetadataUpdate(uri, values)
                }
            } else {
                Toast.makeText(activity, "写入权限被拒绝", Toast.LENGTH_SHORT).show()
                isMetadataSaving = false
            }
            pendingMetadataUri = null
            pendingMetadataValues = null
            pendingFfmpegMeta = null
            pendingFfmpegFilePath = null
            pendingFfmpegUri = null
            pendingFfmpegTitle = null
            pendingFfmpegArtist = null
            pendingFfmpegAlbum = null
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

    fun editMetadata() {
        val song = getPlayerController()?.currentSong?.value ?: return
        editingSong = song
        editTitle = song.title
        editArtist = song.artist
        editAlbum = song.album
        editGenre = song.genre
        editYear = if (song.year > 0) song.year.toString() else ""
        editTrack = if (song.trackNumber > 0) song.trackNumber.toString() else ""
        isMetadataSaving = false
        isMetadataEditorShowing = true
        onVisibilityChanged(true)
    }

    fun dismissMetadataEditor() {
        if (isMetadataSaving) return
        if (!isMetadataEditorShowing) return
        isMetadataEditorShowing = false
        editingSong = null
        onVisibilityChanged(false)
    }

    fun saveMetadata() {
        val song = editingSong ?: getPlayerController()?.currentSong?.value ?: return
        val newTitle = editTitle.trim()
        val newArtist = editArtist.trim()
        val newAlbum = editAlbum.trim()
        val newGenre = editGenre.trim()
        val newYear = editYear.trim().toIntOrNull() ?: 0
        val newTrack = editTrack.trim().toIntOrNull() ?: 0

        val ffmpegMeta = mutableMapOf<String, String>()
        if (newTitle.isNotBlank()) ffmpegMeta["title"] = newTitle
        if (newArtist.isNotBlank()) ffmpegMeta["artist"] = newArtist
        if (newAlbum.isNotBlank()) ffmpegMeta["album"] = newAlbum
        if (newGenre.isNotBlank()) ffmpegMeta["genre"] = newGenre
        if (newYear > 0) ffmpegMeta["date"] = newYear.toString()
        if (newTrack > 0) ffmpegMeta["track"] = newTrack.toString()

        val filePath = song.path
        AppLogger.d("EditMetadata", "Save clicked. FFmpeg write to: $filePath, meta=$ffmpegMeta")

        if (filePath.isBlank() || !File(filePath).exists()) {
            Toast.makeText(activity, "文件不存在: $filePath", Toast.LENGTH_SHORT).show()
            return
        }

        isMetadataSaving = true
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent = MediaStore.createWriteRequest(
                    activity.contentResolver, listOf(uri)
                )
                pendingFfmpegMeta = ffmpegMeta
                pendingFfmpegFilePath = filePath
                pendingFfmpegUri = uri
                pendingFfmpegTitle = newTitle
                pendingFfmpegArtist = newArtist
                pendingFfmpegAlbum = newAlbum
                metadataWriteLauncher.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                )
            } catch (e: Exception) {
                AppLogger.e("EditMetadata", "createWriteRequest failed", e)
                Toast.makeText(activity, "权限请求失败: ${e.message}", Toast.LENGTH_SHORT).show()
                isMetadataSaving = false
            }
        } else {
            doFfmpegWrite(filePath, ffmpegMeta, uri, newTitle, newArtist, newAlbum)
        }
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
                val song = getPlayerController()?.currentSong?.value ?: return
                Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show()
                isMetadataSaving = false
                isMetadataEditorShowing = false
                editingSong = null
                onVisibilityChanged(false)
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
        uri: android.net.Uri,
        newTitle: String,
        newArtist: String,
        newAlbum: String
    ) {
        (activity as androidx.lifecycle.LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = activity.cacheDir.absolutePath
                val ret = com.rawsmusic.core.common.ffmpeg.FFmpegBridge.writeMetadata(filePath, ffmpegMeta, cacheDir)

                if (ret != 0) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "元数据写入失败 (错误码: $ret)", Toast.LENGTH_SHORT).show()
                        isMetadataSaving = false
                    }
                    return@launch
                }

                val ext = filePath.substringAfterLast(".", "").lowercase()
                val tmpFile = File(activity.cacheDir, "rawsmeta_tmp.$ext")
                if (!tmpFile.exists() || tmpFile.length() == 0L) {
                    AppLogger.e("EditMetadata", "Temp file missing or empty: ${tmpFile.absolutePath}, exists=${tmpFile.exists()}, size=${tmpFile.length()}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "临时文件不存在或为空", Toast.LENGTH_SHORT).show()
                        isMetadataSaving = false
                    }
                    return@launch
                }

                val tmpSize = tmpFile.length()
                val origFile = File(filePath)
                val origSize = origFile.length()
                AppLogger.d("EditMetadata", "Temp file: ${tmpFile.absolutePath}, size=$tmpSize bytes, origSize=$origSize bytes")

                if (tmpSize < origSize / 2) {
                    AppLogger.e("EditMetadata", "Temp file too small ($tmpSize) vs original ($origSize), aborting")
                    tmpFile.delete()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "临时文件异常，中止写入", Toast.LENGTH_SHORT).show()
                        isMetadataSaving = false
                    }
                    return@launch
                }

                var copySuccess = false
                var writtenSize = 0L

                try {
                    FileInputStream(tmpFile).use { input ->
                        FileOutputStream(origFile).use { output ->
                            val buf = ByteArray(65536)
                            var bytesRead: Int
                            while (input.read(buf).also { bytesRead = it } != -1) {
                                output.write(buf, 0, bytesRead)
                            }
                            output.flush()
                            output.fd.sync()
                            writtenSize = origFile.length()
                        }
                    }
                    AppLogger.d("EditMetadata", "Direct file write: written=$writtenSize bytes")
                    copySuccess = writtenSize == tmpSize
                } catch (e: Exception) {
                    AppLogger.e("EditMetadata", "Direct file write failed", e)
                }

                if (!copySuccess) {
                    try {
                        activity.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                            FileOutputStream(pfd.fileDescriptor).use { output ->
                                FileInputStream(tmpFile).use { input ->
                                    val buf = ByteArray(65536)
                                    var bytesRead: Int
                                    while (input.read(buf).also { bytesRead = it } != -1) {
                                        output.write(buf, 0, bytesRead)
                                    }
                                    output.flush()
                                    output.fd.sync()
                                }
                            }
                        }
                        writtenSize = origFile.length()
                        AppLogger.d("EditMetadata", "ParcelFileDescriptor write: written=$writtenSize bytes")
                        copySuccess = writtenSize == tmpSize
                    } catch (e2: Exception) {
                        AppLogger.e("EditMetadata", "ParcelFileDescriptor write failed", e2)
                    }
                }

                tmpFile.delete()

                if (!copySuccess) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "文件写回失败", Toast.LENGTH_SHORT).show()
                        isMetadataSaving = false
                    }
                    return@launch
                }

                val safeValues = ContentValues()
                if (newTitle.isNotBlank()) safeValues.put(MediaStore.Audio.Media.TITLE, newTitle)
                if (newArtist.isNotBlank()) safeValues.put(MediaStore.Audio.Media.ARTIST, newArtist)
                if (newAlbum.isNotBlank()) safeValues.put(MediaStore.Audio.Media.ALBUM, newAlbum)
                try {
                    activity.contentResolver.update(uri, safeValues, null, null)
                } catch (e: Exception) {
                    AppLogger.w("EditMetadata", "MediaStore update after FFmpeg write failed", e)
                }

                try {
                    activity.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
                } catch (_: Exception) {}

                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show()
                    isMetadataSaving = false
                    isMetadataEditorShowing = false
                    editingSong = null
                    onVisibilityChanged(false)
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
    MetadataEditorFormOverlay(helper = helper, modifier = modifier)
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
                        text = "编辑元数据",
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
                        MetadataField("标题", helper.editTitle, helper.isMetadataSaving) { helper.editTitle = it }
                        MetadataField("艺术家", helper.editArtist, helper.isMetadataSaving) { helper.editArtist = it }
                        MetadataField("专辑", helper.editAlbum, helper.isMetadataSaving) { helper.editAlbum = it }
                        MetadataField("流派", helper.editGenre, helper.isMetadataSaving) { helper.editGenre = it }
                        MetadataField("年份", helper.editYear, helper.isMetadataSaving) { helper.editYear = it }
                        MetadataField("音轨", helper.editTrack, helper.isMetadataSaving) { helper.editTrack = it }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (helper.isMetadataSaving) {
                            Text(
                                text = "正在保存...",
                                color = Color.White.copy(alpha = 0.58f),
                                fontSize = 13.sp
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "取消",
                            color = Color.White.copy(alpha = if (helper.isMetadataSaving) 0.35f else 0.65f),
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clickable(enabled = !helper.isMetadataSaving) { helper.dismissMetadataEditor() }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "保存",
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
    label: String,
    value: String,
    saving: Boolean,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = !saving,
        label = { Text(label) },
        singleLine = true,
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
