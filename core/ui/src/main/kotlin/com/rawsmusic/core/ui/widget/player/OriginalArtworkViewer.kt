package com.rawsmusic.core.ui.widget.player

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.rawsmusic.core.common.artwork.EmbeddedArtworkRegion
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.taglib.TagLibBridge
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.DefaultAlbumArtworkPolicy
import com.rawsmusic.core.ui.widget.bitmaps.FolderArtworkLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

private const val MIN_ARTWORK_BYTES = 1024L
private const val MAX_ARTWORK_SCALE = 8f

internal data class OriginalArtworkSource(
    val file: File,
    val mimeType: String,
    val width: Int,
    val height: Int
)

/** Observes a long press without consuming events used by player swipe gestures. */
internal fun Modifier.observeArtworkLongPress(onLongPress: () -> Unit): Modifier = composed {
    val latestOnLongPress by rememberUpdatedState(onLongPress)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var cancelled = false
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed || event.changes.size > 1) {
                        cancelled = true
                        break
                    }
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        cancelled = true
                        break
                    }
                }
            }
            if (!cancelled) latestOnLongPress()
        }
    }
}

@Composable
internal fun OriginalArtworkViewerDialog(
    show: Boolean,
    song: AudioFile?,
    coverKey: String?,
    onDismiss: () -> Unit
) {
    if (!show) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember(song?.path, song?.dateModified, coverKey) {
        mutableStateOf<OriginalArtworkSource?>(null)
    }
    var loading by remember(song?.path, song?.dateModified, coverKey) { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(song?.path, song?.dateModified, coverKey) {
        loading = true
        source = OriginalArtworkResolver.resolve(context, song, coverKey)
        loading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.artwork_original_title), color = Color.White, fontSize = 18.sp)
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.artwork_close), color = Color.White)
                }
            }

            when {
                loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
                source == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.artwork_original_unavailable), color = Color.White.copy(alpha = 0.72f))
                }
                else -> ZoomableOriginalArtwork(
                    source = source!!,
                    contentDescription = song?.displayName,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }

            source?.let { art ->
                Text(
                    stringResource(R.string.artwork_resolution, art.width, art.height),
                    color = Color.White.copy(alpha = 0.68f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)
                )
                Button(
                    onClick = {
                        if (saving) return@Button
                        saving = true
                        scope.launch {
                            val saved = OriginalArtworkResolver.saveToGallery(context, art, song)
                            saving = false
                            Toast.makeText(
                                context,
                                if (saved) R.string.artwork_saved else R.string.artwork_save_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    enabled = !saving,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(if (saving) stringResource(R.string.artwork_saving) else stringResource(R.string.artwork_save))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ZoomableOriginalArtwork(
    source: OriginalArtworkSource,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    var scale by remember(source.file.absolutePath) { mutableFloatStateOf(1f) }
    var offset by remember(source.file.absolutePath) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    fun clampOffset(candidate: Offset, targetScale: Float): Offset {
        if (viewport.width <= 0 || viewport.height <= 0 || source.width <= 0 || source.height <= 0) {
            return Offset.Zero
        }
        val fit = min(
            viewport.width.toFloat() / source.width.toFloat(),
            viewport.height.toFloat() / source.height.toFloat()
        )
        val fittedWidth = source.width * fit
        val fittedHeight = source.height * fit
        val maxX = max(0f, (fittedWidth * targetScale - viewport.width) / 2f)
        val maxY = max(0f, (fittedHeight * targetScale - viewport.height) / 2f)
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged {
                viewport = it
                offset = clampOffset(offset, scale)
            }
            .pointerInput(source.file.absolutePath, viewport) {
                detectTransformGestures(panZoomLock = false) { centroid, pan, zoom, _ ->
                    val oldScale = scale
                    val newScale = (oldScale * zoom).coerceIn(1f, MAX_ARTWORK_SCALE)
                    val center = Offset(viewport.width / 2f, viewport.height / 2f)
                    val focus = centroid - center
                    val ratio = newScale / oldScale
                    val anchored = offset * ratio + focus * (1f - ratio) + pan
                    scale = newScale
                    offset = if (newScale <= 1.001f) Offset.Zero else clampOffset(anchored, newScale)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(source.file)
                .size(Size.ORIGINAL)
                .crossfade(false)
                .build(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
                transformOrigin = TransformOrigin.Center
            }
        )
    }
}

private object OriginalArtworkResolver {
    suspend fun resolve(context: Context, song: AudioFile?, coverKey: String?): OriginalArtworkSource? =
        withContext(Dispatchers.IO) {
            val key = coverKey.orEmpty()
            existingImage(BitmapProvider.originalArtworkSourcePath(key))?.let { return@withContext it }
            directCoverSource(context, key)?.let { return@withContext it }

            val audioPath = song?.path.orEmpty().ifBlank { audioPathFromKey(key) }
            if (audioPath.isNotBlank()) {
                EmbeddedArtworkRegion.find(audioPath)?.let { region ->
                    cacheStream(context, cacheStem(song, key) + "_region", region.mime, region::openStream)
                        ?.let { return@withContext it }
                }
                extractWithTagLib(context, audioPath, cacheStem(song, key))?.let { return@withContext it }
                extractWithFfmpeg(context, audioPath, cacheStem(song, key))?.let { return@withContext it }
                extractWithRetriever(context, audioPath, cacheStem(song, key))?.let { return@withContext it }
            }

            // External images are fallback-only when a real audio file is available. This keeps the
            // original-art viewer consistent with BitmapProvider and prevents folder.jpg from
            // replacing an embedded picture owned by the current track.
            existingImage(song?.albumArtPath)?.let { return@withContext it }
            folderCover(audioPath)?.let { return@withContext sourceFor(it) }

            if (DefaultAlbumArtworkPolicy.enabled) defaultArtwork(context) else null
        }

    suspend fun saveToGallery(
        context: Context,
        source: OriginalArtworkSource,
        song: AudioFile?
    ): Boolean = withContext(Dispatchers.IO) {
        val extension = extensionForMime(source.mimeType, source.file)
        val title = sanitizeFileName(song?.title.orEmpty().ifBlank { song?.album.orEmpty() }.ifBlank { "RawS_Artwork" })
        val displayName = "${title}_${System.currentTimeMillis()}.$extension"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, source.mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/RawSMusic")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext false
                try {
                    resolver.openOutputStream(uri)?.use { output ->
                        source.file.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("Unable to open MediaStore output")
                    resolver.update(uri, ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }, null, null)
                    true
                } catch (_: Throwable) {
                    resolver.delete(uri, null, null)
                    false
                }
            } else {
                @Suppress("DEPRECATION")
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "RawSMusic"
                )
                if (!directory.exists() && !directory.mkdirs()) return@withContext false
                val output = File(directory, displayName)
                source.file.inputStream().use { input -> output.outputStream().use { target -> input.copyTo(target) } }
                MediaScannerConnection.scanFile(context, arrayOf(output.absolutePath), arrayOf(source.mimeType), null)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun existingImage(pathOrUri: String?): OriginalArtworkSource? {
        val path = pathOrUri.orEmpty().removePrefix("file://")
        if (path.isBlank()) return null
        val file = File(path)
        return if (file.exists() && file.canRead() && file.length() > MIN_ARTWORK_BYTES) sourceFor(file) else null
    }

    private fun directCoverSource(context: Context, key: String): OriginalArtworkSource? {
        if (key.startsWith("content://")) {
            val uri = Uri.parse(key.substringBefore('|'))
            val mime = context.contentResolver.getType(uri)
            return cacheStream(context, "content_${key.hashCode()}", mime) {
                context.contentResolver.openInputStream(uri)
            }
        }
        if (key.startsWith("file://")) return existingImage(key.substringBefore('|'))
        if (!key.startsWith("audio://")) return existingImage(key.substringBefore('|'))
        return null
    }

    private fun audioPathFromKey(key: String): String =
        if (key.startsWith("audio://")) key.removePrefix("audio://").substringBefore('|') else ""

    private fun folderCover(audioPath: String): File? =
        FolderArtworkLocator.find(audioPath, minimumBytes = MIN_ARTWORK_BYTES)

    private fun extractWithTagLib(context: Context, audioPath: String, stem: String): OriginalArtworkSource? {
        if (audioPath.isBlank() || !TagLibBridge.isLoaded()) return null
        val output = cacheFile(context, "${stem}_taglib.art")
        if (output.length() > MIN_ARTWORK_BYTES) return sourceFor(output)
        output.delete()
        return if (TagLibBridge.extractEmbeddedArtworkToFile(audioPath, output.absolutePath)) sourceFor(output) else null
    }

    private fun extractWithFfmpeg(context: Context, audioPath: String, stem: String): OriginalArtworkSource? {
        if (audioPath.isBlank()) return null
        val output = cacheFile(context, "${stem}_ffmpeg.jpg")
        if (output.length() > MIN_ARTWORK_BYTES) return sourceFor(output)
        output.delete()
        return if (FFmpegBridge.extractCover(audioPath, output.absolutePath) == 0) sourceFor(output) else null
    }

    private fun extractWithRetriever(context: Context, audioPath: String, stem: String): OriginalArtworkSource? {
        if (audioPath.isBlank()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(audioPath)
            val bytes = retriever.embeddedPicture ?: return null
            if (bytes.size <= MIN_ARTWORK_BYTES) return null
            val mime = detectMime(bytes)
            val output = cacheFile(context, "${stem}_mmr.${extensionForMime(mime, null)}")
            if (!output.exists() || output.length() != bytes.size.toLong()) output.writeBytes(bytes)
            sourceFor(output, mime)
        } catch (_: Throwable) {
            null
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
    }

    private fun defaultArtwork(context: Context): OriginalArtworkSource? {
        val output = cacheFile(context, "default_album_art.jpg")
        if (!output.exists() || output.length() <= MIN_ARTWORK_BYTES) {
            context.resources.openRawResource(com.rawsmusic.core.common.R.drawable.default_album_art).use { input ->
                output.outputStream().use { target -> input.copyTo(target) }
            }
        }
        return sourceFor(output, "image/jpeg")
    }

    private fun cacheStream(
        context: Context,
        stem: String,
        declaredMime: String?,
        openStream: () -> InputStream?
    ): OriginalArtworkSource? {
        val temporary = cacheFile(context, "$stem.tmp")
        temporary.delete()
        val stream = openStream() ?: return null
        stream.use { input -> temporary.outputStream().use { target -> input.copyTo(target) } }
        if (temporary.length() <= MIN_ARTWORK_BYTES) {
            temporary.delete()
            return null
        }
        val mime = declaredMime?.takeIf { it.startsWith("image/") } ?: detectMime(temporary)
        val output = cacheFile(context, "$stem.${extensionForMime(mime, temporary)}")
        if (output.exists()) output.delete()
        if (!temporary.renameTo(output)) {
            temporary.inputStream().use { input -> output.outputStream().use { target -> input.copyTo(target) } }
            temporary.delete()
        }
        return sourceFor(output, mime)
    }

    private fun cacheFile(context: Context, name: String): File {
        val directory = File(context.cacheDir, "original_artwork")
        if (!directory.exists()) directory.mkdirs()
        return File(directory, name)
    }

    private fun cacheStem(song: AudioFile?, key: String): String {
        val identity = "${song?.path}|${song?.fileSize}|${song?.dateModified}|$key"
        return "art_${identity.hashCode().toUInt().toString(16)}"
    }

    private fun sourceFor(file: File, knownMime: String? = null): OriginalArtworkSource? {
        if (!file.exists() || !file.canRead() || file.length() <= MIN_ARTWORK_BYTES) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        val mime = knownMime?.takeIf { it.startsWith("image/") }
            ?: options.outMimeType
            ?: detectMime(file)
        return OriginalArtworkSource(file, mime, options.outWidth, options.outHeight)
    }

    private fun detectMime(file: File): String = file.inputStream().use { input ->
        detectMime(input.readNBytesCompat(16))
    }

    private fun detectMime(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        bytes.size >= 6 && String(bytes, 0, 3, Charsets.US_ASCII) == "GIF" -> "image/gif"
        else -> "image/jpeg"
    }

    private fun extensionForMime(mime: String, file: File?): String = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heif", "image/heic" -> "heic"
        else -> file?.extension?.lowercase()?.takeIf { it in setOf("jpg", "jpeg", "png", "webp", "gif", "heic") }
            ?: "jpg"
    }

    private fun sanitizeFileName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim()
        .take(80)
        .ifBlank { "RawS_Artwork" }

    private fun InputStream.readNBytesCompat(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var total = 0
        while (total < count) {
            val read = read(buffer, total, count - total)
            if (read <= 0) break
            total += read
        }
        return if (total == count) buffer else buffer.copyOf(total)
    }
}
