package com.rawsmusic.lyrico

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.model.LyricWord
import com.rawsmusic.core.common.taglib.TagLibBridge
import com.rawsmusic.lyrico.runtime.QuickJsHostApi
import com.rawsmusic.lyrico.runtime.QuickJsRuntime
import com.rawsmusic.module.scanner.LyricOverrideStore
import com.rawsmusic.module.scanner.parser.RawSLyricsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class LyricoSongCandidate(
    val pluginId: String,
    val pluginName: String,
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String,
    val supportsCoverSearch: Boolean,
    val fields: Map<String, String>,
    val internal: Map<String, String>
)


data class LyricoPreferredSource(
    val id: String,
    val name: String,
    val capabilities: Set<String>
)

data class LyricoCoverCandidate(
    val url: String,
    val title: String,
    val artist: String,
    val album: String,
    val pluginName: String
)

enum class LyricoLyricTiming {
    WORD_BY_WORD,
    LINE_BY_LINE
}

class LyricoSourceEngine(context: Context) {
    private val appContext = context.applicationContext
    private val store = LyricoPluginStore.get(appContext)
    private val gson = Gson()

    fun preferredSources(): List<LyricoPreferredSource> = store.enabledInPreferredOrder().map { plugin ->
        LyricoPreferredSource(
            id = plugin.manifest.id,
            name = plugin.manifest.name.ifBlank { plugin.manifest.id },
            capabilities = plugin.manifest.capabilities.orEmpty()
        )
    }

    suspend fun searchSource(
        song: AudioFile,
        sourceId: String,
        query: String = defaultQuery(song)
    ): List<LyricoSongCandidate> = withContext(Dispatchers.IO) {
        val plugin = store.enabledInPreferredOrder().firstOrNull { it.manifest.id == sourceId }
            ?: return@withContext emptyList()
        runCatching { searchPlugin(plugin, song, query) }
            .onFailure { Log.w(TAG, "Preferred source search failed for $sourceId", it) }
            .getOrDefault(emptyList())
            .sortedWith(
                compareByDescending<LyricoSongCandidate> { candidateTitleScore(song, it) }
                    .thenByDescending { candidateMetadataScore(song, it) }
            )
    }

    fun bestMatchingCandidate(
        song: AudioFile,
        candidates: List<LyricoSongCandidate>,
        minimumScore: Double = 0.62
    ): LyricoSongCandidate? = matchingCandidates(song, candidates, minimumScore).firstOrNull()

    fun matchingCandidates(
        song: AudioFile,
        candidates: List<LyricoSongCandidate>,
        minimumScore: Double = 0.62
    ): List<LyricoSongCandidate> = candidates
        .map { candidate ->
            val score = candidateTitleScore(song, candidate) * 0.72 +
                candidateMetadataScore(song, candidate) * 0.28
            candidate to score
        }
        .filter { (_, score) -> score >= minimumScore }
        .sortedByDescending { (_, score) -> score }
        .map { (candidate, _) -> candidate }

    suspend fun highestResolutionCover(
        candidates: List<LyricoCoverCandidate>
    ): LyricoCoverCandidate? = supervisorScope {
        val distinct = candidates.distinctBy { it.url }.take(12)
        if (distinct.isEmpty()) return@supervisorScope null
        val measured = distinct.mapIndexed { index, candidate ->
            async(Dispatchers.IO) {
                val size = probeRemoteImageSize(candidate.url)
                val area = size?.let { (width, height) -> width.toLong() * height.toLong() } ?: -1L
                Triple(candidate, area, index)
            }
        }.map { it.await() }
        measured.maxWithOrNull(
            compareBy<Triple<LyricoCoverCandidate, Long, Int>> { it.second }
                .thenBy { -it.third }
        )?.first
    }

    fun clearTransientCache() {
        listOf(
            File(appContext.cacheDir, "lyrico_covers"),
            File(appContext.cacheDir, "lyrico_plugins")
        ).forEach { directory -> runCatching { directory.deleteRecursively() } }
    }

    suspend fun search(song: AudioFile, query: String): List<LyricoSongCandidate> =
        supervisorScope {
            store.listInstalled()
                .filter { it.enabled }
                .map { plugin ->
                    async(Dispatchers.IO) {
                        runCatching { searchPlugin(plugin, song, query) }
                            .onFailure { Log.w(TAG, "Search failed for ${plugin.manifest.id}", it) }
                            .getOrDefault(emptyList())
                    }
                }
                .map { it.await() }
                .flatten()
                .sortedWith(
                    compareByDescending<LyricoSongCandidate> { candidateTitleScore(song, it) }
                        .thenByDescending { candidateMetadataScore(song, it) }
                        .thenBy { it.pluginName }
                )
        }

    suspend fun getLyrics(candidate: LyricoSongCandidate): LyricData = withContext(Dispatchers.IO) {
        candidate.fields["lyrics"]
            ?.takeIf { it.isNotBlank() }
            ?.let(::parseLyricsPayload)
            ?.takeUnless { it.isEmpty }
            ?.let { return@withContext it }

        val plugin = store.listInstalled().firstOrNull {
            it.enabled && it.manifest.id == candidate.pluginId
        } ?: error("The selected source is no longer enabled")

        withRuntime(plugin) { runtime ->
            val request = linkedMapOf<String, Any>(
                "song" to linkedMapOf(
                    "id" to candidate.id,
                    "title" to candidate.title,
                    "artist" to candidate.artist,
                    "album" to candidate.album,
                    "duration" to candidate.durationMs,
                    "sourceId" to candidate.pluginId,
                    "pluginId" to candidate.pluginId,
                    "fields" to candidate.fields,
                    "internal" to candidate.internal
                ),
                "config" to emptyMap<String, String>()
            )
            val raw = runtime.call("getLyrics", gson.toJson(request))
            parseLyricsPayload(raw).takeUnless { it.isEmpty }
                ?: error("The source returned no usable lyrics")
        }
    }

    suspend fun searchCovers(candidate: LyricoSongCandidate): List<LyricoCoverCandidate> =
        withContext(Dispatchers.IO) {
            val direct = candidate.coverUrl.takeIf { it.isNotBlank() }?.let {
                LyricoCoverCandidate(
                    url = it,
                    title = candidate.title,
                    artist = candidate.artist,
                    album = candidate.album,
                    pluginName = candidate.pluginName
                )
            }
            if (!candidate.supportsCoverSearch) return@withContext listOfNotNull(direct)

            val plugin = store.listInstalled().firstOrNull {
                it.enabled && it.manifest.id == candidate.pluginId
            } ?: return@withContext listOfNotNull(direct)

            val remote = runCatching {
                withRuntime(plugin) { runtime ->
                    val request = linkedMapOf<String, Any>(
                        "keyword" to listOf(candidate.title, candidate.artist)
                            .filter { it.isNotBlank() }
                            .joinToString(" "),
                        "song" to linkedMapOf(
                            "id" to candidate.id,
                            "title" to candidate.title,
                            "artist" to candidate.artist,
                            "album" to candidate.album,
                            "duration" to candidate.durationMs,
                            "sourceId" to candidate.pluginId,
                            "pluginId" to candidate.pluginId,
                            "fields" to candidate.fields,
                            "internal" to candidate.internal
                        ),
                        "pageSize" to 12,
                        "config" to emptyMap<String, String>()
                    )
                    parseSearchResults(runtime.call("searchCovers", gson.toJson(request)), plugin)
                        .mapNotNull { cover ->
                            cover.coverUrl.takeIf { it.isNotBlank() }?.let { url ->
                                LyricoCoverCandidate(
                                    url = url,
                                    title = cover.title,
                                    artist = cover.artist,
                                    album = cover.album,
                                    pluginName = cover.pluginName
                                )
                            }
                        }
                }
            }.onFailure {
                Log.w(TAG, "Cover search failed for ${candidate.pluginId}", it)
            }.getOrDefault(emptyList())

            (listOfNotNull(direct) + remote).distinctBy { it.url }.take(12)
        }

    fun prepareLyrics(
        lyrics: LyricData,
        timing: LyricoLyricTiming,
        includeTranslation: Boolean,
        includeRomanization: Boolean
    ): LyricData = lyrics.copy(
        lines = lyrics.lines.map { line ->
            line.copy(
                words = if (timing == LyricoLyricTiming.WORD_BY_WORD) line.words else emptyList(),
                translation = line.translation.takeIf { includeTranslation }.orEmpty(),
                romanization = line.romanization.takeIf { includeRomanization }.orEmpty()
            )
        }
    )

    suspend fun writeEmbeddedCover(song: AudioFile, coverUrl: String): File =
        withContext(Dispatchers.IO) {
            require(coverUrl.startsWith("http://") || coverUrl.startsWith("https://")) {
                "Unsupported cover address"
            }
            require(TagLibBridge.isLoaded()) { "The metadata writer is unavailable" }

            val audio = File(song.path)
            val access = LyricoAudioWriteAccess.inspect(appContext, song)
            require(access.canWrite) {
                "Audio write access was not granted"
            }

            val downloaded = downloadCover(coverUrl, song.id)
            val operationId = System.nanoTime()
            val extension = resolveAudioExtension(song, audio)
            val extensionSuffix = extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
            val verify = File(appContext.cacheDir, "lyrico_cover_verify_${song.id}_$operationId.img")
            val directWorking = audio.parentFile?.let { parent ->
                File(parent, ".${audio.nameWithoutExtension}.raws-cover-work-$operationId$extensionSuffix")
            }?.takeIf { access.canCreateSibling }

            if (directWorking != null && audio.isFile) {
                writeCoverWithAtomicSibling(audio, directWorking, downloaded, verify, operationId)
            } else {
                val working = File(
                    appContext.cacheDir,
                    "lyrico_cover_work_${song.id}_$operationId$extensionSuffix"
                )
                try {
                    copyAudioSource(song, access.mediaUri, working)
                    writeAndVerifyCover(working, downloaded, verify)
                    commitWorkingAudio(song, access.mediaUri, working)
                    Log.i(
                        TAG,
                        "LYRICO_COVER_WRITE committed strategy=${if (access.canOpenMediaUri) "media_store" else "direct_stream"} " +
                            "path=${song.path} bytes=${downloaded.length()}"
                    )
                    downloaded
                } catch (error: Throwable) {
                    Log.e(TAG, "LYRICO_COVER_WRITE failed path=${song.path}", error)
                    throw error
                } finally {
                    verify.delete()
                    working.delete()
                }
            }
        }


    private fun resolveAudioExtension(song: AudioFile, audio: File): String {
        val pathCandidate = sequenceOf(
            audio.extension,
            runCatching { Uri.parse(song.path).lastPathSegment.orEmpty().substringAfterLast('.', "") }
                .getOrDefault(""),
            song.path.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
        ).map { it.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit) }
            .firstOrNull { it in AUDIO_FILE_EXTENSIONS }
        if (pathCandidate != null) return pathCandidate

        val format = listOf(song.format, song.encodingFormat)
            .joinToString(" ")
            .uppercase(Locale.ROOT)
        return when {
            "DSDIFF" in format || "DFF" in format -> "dff"
            "DSF" in format || "DSD" in format -> "dsf"
            "WAVPACK" in format -> "wv"
            "WAVE" in format || "WAV" in format -> "wav"
            "AIFF" in format || "AIF" in format -> "aiff"
            "FLAC" in format -> "flac"
            "ALAC" in format || "M4A" in format || "MP4" in format -> "m4a"
            "MPEG" in format || "MP3" in format -> "mp3"
            "AAC" in format -> "aac"
            "OPUS" in format -> "opus"
            "OGG" in format || "VORBIS" in format -> "ogg"
            "MONKEY" in format || "APE" in format -> "ape"
            "WMA" in format || "ASF" in format -> "wma"
            else -> error("Unable to determine the audio container format")
        }
    }

    private fun writeCoverWithAtomicSibling(
        audio: File,
        working: File,
        downloaded: File,
        verify: File,
        operationId: Long
    ): File {
        val parent = audio.parentFile ?: error("The audio file has no parent directory")
        val backup = File(parent, ".${audio.name}.raws-cover-$operationId.bak")
        var originalMoved = false
        var replacementMoved = false
        try {
            audio.copyTo(working, overwrite = false)
            writeAndVerifyCover(working, downloaded, verify)
            originalMoved = audio.renameTo(backup)
            require(originalMoved) { "Unable to prepare the original audio for replacement" }
            replacementMoved = working.renameTo(audio)
            if (!replacementMoved) {
                backup.renameTo(audio)
                originalMoved = false
                error("Unable to activate the updated audio file")
            }
            Log.i(TAG, "LYRICO_COVER_WRITE committed strategy=atomic path=${audio.absolutePath}")
            return downloaded
        } catch (error: Throwable) {
            if (originalMoved && !replacementMoved && !audio.exists() && backup.exists()) {
                if (!backup.renameTo(audio)) backup.copyTo(audio, overwrite = true)
            }
            throw error
        } finally {
            verify.delete()
            working.delete()
            if (replacementMoved) backup.delete()
        }
    }

    private fun writeAndVerifyCover(working: File, downloaded: File, verify: File) {
        val mimeType = detectImageMime(downloaded)
        Log.i(TAG, "LYRICO_COVER_WRITE prepare path=${working.absolutePath} mime=$mimeType")
        require(TagLibBridge.writeEmbeddedArtwork(working.absolutePath, downloaded.absolutePath, mimeType)) {
            "This audio format does not support embedded cover writing"
        }
        require(TagLibBridge.extractEmbeddedArtworkToFile(working.absolutePath, verify.absolutePath)) {
            "Cover verification failed"
        }
        require(verify.length() > 1_024L) { "The written cover is invalid" }
    }

    private fun copyAudioSource(song: AudioFile, mediaUri: Uri?, target: File) {
        if (target.exists()) target.delete()
        target.parentFile?.mkdirs()
        openAudioInput(song, mediaUri).use { input ->
            target.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        require(target.length() > 0L) { "The audio source could not be copied for editing" }
    }

    private fun openAudioInput(song: AudioFile, mediaUri: Uri?): InputStream {
        val file = File(song.path)
        if (file.isFile) {
            runCatching { file.inputStream().buffered() }.getOrNull()?.let { return it }
        }
        if (mediaUri != null) {
            return appContext.contentResolver.openInputStream(mediaUri)?.buffered()
                ?: error("The audio source cannot be opened")
        }
        error("The audio source cannot be opened")
    }

    private fun commitWorkingAudio(song: AudioFile, mediaUri: Uri?, working: File) {
        val access = LyricoAudioWriteAccess.inspect(appContext, song)
        if (mediaUri != null && access.canOpenMediaUri) {
            val resolver = appContext.contentResolver
            val descriptor = resolver.openFileDescriptor(mediaUri, "rw")
                ?: error("The MediaStore audio descriptor cannot be opened for writing")
            descriptor.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { output ->
                    working.inputStream().use { input ->
                        val source = input.channel
                        val target = output.channel
                        target.position(0L)
                        var transferred = 0L
                        while (transferred < source.size()) {
                            val count = source.transferTo(transferred, source.size() - transferred, target)
                            if (count <= 0L) error("The updated audio could not be fully written")
                            transferred += count
                        }
                        target.truncate(transferred)
                        target.force(true)
                    }
                }
            }
            val statSize = resolver.openFileDescriptor(mediaUri, "r")?.use { it.statSize } ?: -1L
            require(statSize <= 0L || statSize == working.length()) {
                "The updated audio size does not match the prepared file"
            }
        } else {
            val target = File(song.path)
            require(access.canOpenDirectFile) { "The audio file cannot be opened for writing" }
            FileOutputStream(target, false).use { output ->
                working.inputStream().buffered().use { input -> input.copyTo(output) }
                output.flush()
                runCatching { output.fd.sync() }
            }
            require(target.length() == working.length()) {
                "The updated audio size does not match the prepared file"
            }
        }
        if (!song.path.startsWith("content://", ignoreCase = true)) {
            runCatching {
                MediaScannerConnection.scanFile(appContext, arrayOf(song.path), null, null)
            }
        }
    }

    suspend fun writeOverride(song: AudioFile, lyrics: LyricData): File = withContext(Dispatchers.IO) {
        require(!lyrics.isEmpty) { "Lyrics are empty" }
        val content = toTtml(lyrics)
        val audio = File(song.path)
        val suffix = if (song.cueTrackIndex > 0 || song.cueOffsetMs > 0L) {
            ".track${song.cueTrackIndex}.raws.ttml"
        } else {
            ".raws.ttml"
        }

        val sidecar = runCatching {
            val parent = audio.parentFile ?: error("The audio file has no parent directory")
            require(parent.isDirectory) { "The audio directory is unavailable" }
            val output = File(parent, audio.nameWithoutExtension + suffix)
            val temporary = File(parent, output.name + ".tmp-${System.nanoTime()}")
            temporary.writeText(content, Charsets.UTF_8)
            require(temporary.length() > 0L) { "Generated lyric file is empty" }
            if (output.exists()) require(output.delete()) { "Unable to replace the previous lyric override" }
            if (!temporary.renameTo(output)) {
                temporary.copyTo(output, overwrite = true)
                temporary.delete()
            }
            output
        }.onFailure {
            Log.w(TAG, "Sidecar lyric write unavailable; using private override for ${song.path}", it)
        }.getOrNull()

        sidecar ?: LyricOverrideStore.write(song, content)
    }

    private fun searchPlugin(
        plugin: InstalledLyricoPlugin,
        song: AudioFile,
        query: String
    ): List<LyricoSongCandidate> = withRuntime(plugin) { runtime ->
        val request = linkedMapOf<String, Any>(
            "keyword" to query,
            "page" to 1,
            "pageSize" to 20,
            "separator" to "/",
            "config" to emptyMap<String, String>()
        )
        val raw = runtime.call("searchSongs", gson.toJson(request))
        parseSearchResults(raw, plugin).filter { candidate ->
            candidate.title.isNotBlank() || candidate.artist.isNotBlank()
        }.take(30)
    }

    private fun <T> withRuntime(
        plugin: InstalledLyricoPlugin,
        block: (QuickJsRuntime) -> T
    ): T {
        val script = buildScript(plugin)
        val cacheRoot = File(appContext.cacheDir, "lyrico_plugins/${plugin.manifest.id}")
        return QuickJsRuntime(
            memoryLimitBytes = 64L * 1024L * 1024L,
            timeoutMs = 15_000L,
            hostApi = QuickJsHostApi(
                pluginId = plugin.manifest.id,
                cacheRootDir = cacheRoot
            )
        ).use { runtime ->
            runtime.eval(script, plugin.manifest.entry)
            block(runtime)
        }
    }

    private fun buildScript(plugin: InstalledLyricoPlugin): String {
        val includeFiles = plugin.manifest.includeDirs.orEmpty()
            .flatMap { includeDir ->
                File(plugin.directory, includeDir)
                    .walkTopDown()
                    .filter { it.isFile && it.extension.equals("js", ignoreCase = true) }
                    .sortedBy { it.relativeTo(plugin.directory).invariantSeparatorsPath }
                    .toList()
            }
        val paths = includeFiles.map { it.relativeTo(plugin.directory).invariantSeparatorsPath }
        val bootstrap = """
            (function() {
              var declared = ${gson.toJson(paths)};
              var lookup = Object.create(null);
              declared.forEach(function(path) { lookup[path] = true; });
              globalThis.include = function(path) {
                path = String(path || "");
                if (!Object.prototype.hasOwnProperty.call(lookup, path)) {
                  throw new Error("Include path is not declared: " + path);
                }
              };
            })();
        """.trimIndent()
        return buildString {
            appendLine(bootstrap)
            includeFiles.forEach { file ->
                appendLine("\n;// ===== Lyrico include: ${file.relativeTo(plugin.directory).invariantSeparatorsPath} =====")
                appendLine(file.readText())
            }
            appendLine("\n;// ===== Lyrico entry: ${plugin.manifest.entry} =====")
            appendLine(File(plugin.directory, plugin.manifest.entry).readText())
        }
    }

    private fun parseSearchResults(
        raw: String,
        plugin: InstalledLyricoPlugin
    ): List<LyricoSongCandidate> {
        val root = parseJson(raw) ?: return emptyList()
        val items = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.firstArray("items", "results", "songs", "data")
            else -> null
        } ?: return emptyList()
        return items.mapNotNull { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = obj.firstString("id", "songId", "trackId") ?: return@mapNotNull null
            LyricoSongCandidate(
                pluginId = plugin.manifest.id,
                pluginName = plugin.manifest.name,
                id = id,
                title = obj.firstString("title", "name", "songName").orEmpty(),
                artist = obj.firstString("artist", "artists", "singer").orEmpty(),
                album = obj.firstString("album", "albumName").orEmpty(),
                durationMs = obj.firstLong("duration", "durationMs", "duration_ms") ?: 0L,
                coverUrl = obj.firstString("picUrl", "coverUrl", "cover_url", "artworkUrl")
                    .orEmpty()
                    .ifBlank {
                        obj.firstObject("fields", "metadata")
                            ?.firstString("picUrl", "coverUrl", "cover_url", "artworkUrl")
                            .orEmpty()
                    },
                supportsCoverSearch = plugin.manifest.capabilities.orEmpty()
                    .contains("searchCovers"),
                fields = obj.firstObject("fields", "metadata").toStringMap(),
                internal = obj.firstObject("internal").toStringMap()
            )
        }
    }

    private fun parseLyricsPayload(raw: String): LyricData = sanitizeLyricEntities(
        parseLyricsPayloadInternal(raw)
    )

    private fun parseLyricsPayloadInternal(raw: String): LyricData {
        if (raw.isBlank() || raw == "null") return LyricData()
        val root = parseJson(raw)
        if (root == null) return RawSLyricsParser.parse(raw)
        if (root.isJsonPrimitive && root.asJsonPrimitive.isString) {
            return RawSLyricsParser.parse(root.asString)
        }
        val obj = root.takeIf { it.isJsonObject }?.asJsonObject ?: return LyricData()
        if (obj.get("notFound")?.asBoolean == true) return LyricData()

        val type = obj.firstString("type").orEmpty()
        if (!type.equals("structured", ignoreCase = true)) {
            val payload = when (type) {
                "rawTtml", "raw_ttml", "RAW_TTML", "ttml" -> obj.firstString("rawTtml", "raw_ttml")
                "rawEnhancedLrc", "raw_enhanced_lrc", "RAW_ENHANCED_LRC" -> obj.firstString("rawEnhancedLrc", "raw_enhanced_lrc")
                "rawVerbatimLrc", "raw_verbatim_lrc", "RAW_VERBATIM_LRC" -> obj.firstString("rawVerbatimLrc", "raw_verbatim_lrc")
                "rawMultiPersonEnhancedLrc", "raw_multi_person_enhanced_lrc", "RAW_MULTI_PERSON_ENHANCED_LRC" ->
                    obj.firstString("rawMultiPersonEnhancedLrc", "raw_multi_person_enhanced_lrc")
                else -> obj.firstString("rawPlainLrc", "raw_plain_lrc", "plainLrc", "lrc", "original")
            }
            return payload?.let(RawSLyricsParser::parse) ?: LyricData()
        }

        val translations = obj.firstArray("translated", "translation", "translations").toTextLineMap()
        val romanizations = obj.firstArray("romanization", "romanized", "roma").toTextLineMap()
        val lines = obj.firstArray("original", "lines")?.mapNotNull { element ->
            val row = element.takeIf { it.isJsonArray }?.asJsonArray ?: return@mapNotNull null
            val start = row.longAt(0) ?: return@mapNotNull null
            val end = row.longAt(1) ?: start
            val wordsValue = row.getOrNull(2)
            val words = if (wordsValue?.isJsonArray == true) {
                wordsValue.asJsonArray.mapNotNull { wordElement ->
                    val word = wordElement.takeIf { it.isJsonArray }?.asJsonArray ?: return@mapNotNull null
                    val text = word.stringAt(2).orEmpty()
                    if (text.isEmpty()) return@mapNotNull null
                    val wordStart = word.longAt(0) ?: start
                    val wordEnd = word.longAt(1) ?: end
                    LyricWord(text = text, begin = wordStart, end = wordEnd)
                }
            } else {
                row.stringAt(2)?.takeIf { it.isNotEmpty() }
                    ?.let { listOf(LyricWord(it, start, end)) }
                    .orEmpty()
            }
            if (words.isEmpty()) return@mapNotNull null
            LyricLine(
                timeStamp = start,
                endTime = end,
                text = words.joinToString("") { it.text },
                words = words,
                translation = translations[start].orEmpty(),
                romanization = romanizations[start].orEmpty(),
                isTtml = true
            )
        }.orEmpty()
        return LyricData(lines.sortedBy { it.timeStamp })
    }

    private fun sanitizeLyricEntities(lyrics: LyricData): LyricData = lyrics.copy(
        lines = lyrics.lines.map { line ->
            line.copy(
                text = decodeXmlEntities(line.text),
                translation = decodeXmlEntities(line.translation),
                romanization = decodeXmlEntities(line.romanization),
                words = line.words.map { word -> word.copy(text = decodeXmlEntities(word.text)) },
                backgroundText = line.backgroundText?.let(::decodeXmlEntities),
                backgroundTranslation = line.backgroundTranslation?.let(::decodeXmlEntities),
                backgroundWords = line.backgroundWords.map { word ->
                    word.copy(text = decodeXmlEntities(word.text))
                }
            )
        }
    )

    private fun toTtml(lyrics: LyricData): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<tt xmlns=\"http://www.w3.org/ns/ttml\" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\">")
        appendLine("  <body><div>")
        lyrics.lines.forEach { line ->
            val end = line.endTime.takeIf { it > line.timeStamp }
                ?: line.words.lastOrNull()?.end?.takeIf { it > line.timeStamp }
                ?: (line.timeStamp + 3_000L)
            append("    <p begin=\"").append(formatTtmlTime(line.timeStamp)).append("\" end=\"")
                .append(formatTtmlTime(end)).appendLine("\">")
            if (line.words.isNotEmpty()) {
                line.words.forEach { word ->
                    append("      <span begin=\"").append(formatTtmlTime(word.begin)).append("\" end=\"")
                        .append(formatTtmlTime(word.end.coerceAtLeast(word.begin))).append("\">")
                        .append(xmlEscape(word.text)).appendLine("</span>")
                }
            } else {
                append("      <span>").append(xmlEscape(line.text)).appendLine("</span>")
            }
            if (line.translation.isNotBlank()) {
                append("      <span ttm:role=\"x-translation\">")
                    .append(xmlEscape(line.translation)).appendLine("</span>")
            }
            if (line.romanization.isNotBlank()) {
                append("      <span ttm:role=\"x-romanization\">")
                    .append(xmlEscape(line.romanization)).appendLine("</span>")
            }
            appendLine("    </p>")
        }
        appendLine("  </div></body>")
        appendLine("</tt>")
    }

    private fun defaultQuery(song: AudioFile): String = listOf(song.title, song.artist)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { song.displayName }

    private fun probeRemoteImageSize(url: String): Pair<Int, Int>? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val connection = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull()
            ?: return null
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "RawSMusic")
            connection.inputStream.use { input ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, options)
                if (options.outWidth > 0 && options.outHeight > 0) {
                    options.outWidth to options.outHeight
                } else {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun candidateTitleScore(song: AudioFile, candidate: LyricoSongCandidate): Double {
        return smartTextSimilarity(song.title, candidate.title)
    }

    private fun candidateMetadataScore(song: AudioFile, candidate: LyricoSongCandidate): Double {
        val artistScore = smartTextSimilarity(song.artist, candidate.artist)
        val albumScore = smartTextSimilarity(song.album, candidate.album)
        val durationScore = durationSimilarity(song.duration, candidate.durationMs)
        return artistScore * 0.68 + durationScore * 0.22 + albumScore * 0.10
    }

    private fun smartTextSimilarity(left: String?, right: String?): Double {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return 0.0
        val normalizedLeft = normalizeForMatch(left)
        val normalizedRight = normalizeForMatch(right)
        if (normalizedLeft.isBlank() || normalizedRight.isBlank()) return 0.0
        if (normalizedLeft == normalizedRight) return 1.0

        val compactLeft = normalizedLeft.replace(" ", "")
        val compactRight = normalizedRight.replace(" ", "")
        if (compactLeft == compactRight) return 1.0

        val shorter = if (compactLeft.length <= compactRight.length) compactLeft else compactRight
        val longer = if (compactLeft.length <= compactRight.length) compactRight else compactLeft
        val containsScore = if (longer.contains(shorter)) {
            0.80 + 0.20 * shorter.length.toDouble() / longer.length.toDouble()
        } else {
            0.0
        }
        val editScore = 1.0 - levenshteinDistance(compactLeft, compactRight).toDouble() /
            maxOf(compactLeft.length, compactRight.length)
        val characterScore = characterDiceSimilarity(compactLeft, compactRight)
        return maxOf(containsScore, editScore * 0.45 + characterScore * 0.55).coerceIn(0.0, 1.0)
    }

    private fun normalizeForMatch(value: String): String {
        return value
            .replace(VERSION_NOISE_REGEX, " ")
            .lowercase(Locale.ROOT)
            .replace('　', ' ')
            .replace(MATCH_PUNCTUATION_REGEX, " ")
            .replace(MATCH_WHITESPACE_REGEX, " ")
            .trim()
    }

    private fun levenshteinDistance(left: String, right: String): Int {
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (leftIndex in left.indices) {
            current[0] = leftIndex + 1
            for (rightIndex in right.indices) {
                val substitutionCost = if (left[leftIndex] == right[rightIndex]) 0 else 1
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + substitutionCost
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }

    private fun characterDiceSimilarity(left: String, right: String): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val counts = HashMap<Int, Int>()
        left.codePoints().forEach { codePoint ->
            counts[codePoint] = (counts[codePoint] ?: 0) + 1
        }
        var common = 0
        right.codePoints().forEach { codePoint ->
            val count = counts[codePoint] ?: 0
            if (count > 0) {
                common++
                counts[codePoint] = count - 1
            }
        }
        val leftLength = left.codePointCount(0, left.length)
        val rightLength = right.codePointCount(0, right.length)
        return 2.0 * common / (leftLength + rightLength).toDouble()
    }

    private fun durationSimilarity(localDurationMs: Long, remoteDurationMs: Long): Double {
        if (localDurationMs <= 0L || remoteDurationMs <= 0L) return 0.0
        return when (kotlin.math.abs(localDurationMs - remoteDurationMs)) {
            in 0L..1_500L -> 1.0
            in 1_501L..3_000L -> 0.85
            in 3_001L..5_000L -> 0.60
            in 5_001L..8_000L -> 0.30
            in 8_001L..12_000L -> 0.10
            else -> 0.0
        }
    }

    private fun parseJson(raw: String): JsonElement? = runCatching { JsonParser.parseString(raw) }.getOrNull()

    private fun downloadCover(url: String, songId: Long): File {
        val targetDir = File(appContext.cacheDir, "lyrico_covers").apply { mkdirs() }
        val target = File(targetDir, "${songId}_${url.hashCode().toUInt()}.img")
        if (target.isFile && target.length() in 1_025L..MAX_COVER_BYTES) return target
        val temporary = File(targetDir, target.name + ".tmp")
        temporary.delete()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "RawSMusic/${appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName}")
        }
        try {
            connection.connect()
            require(connection.responseCode in 200..299) { "Cover download failed (${connection.responseCode})" }
            val declaredLength = connection.contentLengthLong
            require(declaredLength <= 0L || declaredLength <= MAX_COVER_BYTES) { "Cover image is too large" }
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_COVER_BYTES) { "Cover image is too large" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            require(temporary.length() > 1_024L) { "The cover image is empty" }
            require(detectImageMime(temporary).isNotBlank()) { "Unsupported cover image" }
            if (target.exists()) target.delete()
            require(temporary.renameTo(target)) { "Unable to cache the cover image" }
            return target
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    private fun detectImageMime(file: File): String {
        val header = ByteArray(12)
        val size = file.inputStream().use { it.read(header) }
        return when {
            size >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() -> "image/jpeg"
            size >= 8 && header.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
            size >= 6 && String(header, 0, 6, Charsets.US_ASCII).startsWith("GIF8") -> "image/gif"
            size >= 2 && header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte() -> "image/bmp"
            else -> ""
        }
    }
    private fun formatTtmlTime(ms: Long): String = String.format(Locale.US, "%02d:%02d.%03d", ms / 60_000L, (ms / 1_000L) % 60L, ms % 1_000L)
    private fun xmlEscape(text: String): String = decodeXmlEntities(text)
        // Quotes and apostrophes are valid unescaped characters in XML text nodes. Escaping them
        // exposed literal entities in parsers that intentionally preserve lyric source text.
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun decodeXmlEntities(text: String): String {
        var decoded = text
        repeat(2) {
            decoded = NUMERIC_ENTITY_REGEX.replace(decoded) { match ->
                val token = match.groupValues[1]
                val codePoint = if (token.startsWith("x", ignoreCase = true)) {
                    token.drop(1).toIntOrNull(16)
                } else {
                    token.toIntOrNull()
                }
                codePoint?.takeIf { Character.isValidCodePoint(it) }
                    ?.let { String(Character.toChars(it)) }
                    ?: match.value
            }
            decoded = decoded
                .replace("&lt;", "<", ignoreCase = true)
                .replace("&gt;", ">", ignoreCase = true)
                .replace("&quot;", "\"", ignoreCase = true)
                .replace("&apos;", "'", ignoreCase = true)
                .replace("&nbsp;", " ", ignoreCase = true)
                .replace("&amp;", "&", ignoreCase = true)
        }
        return decoded
    }

    private fun JsonObject.firstString(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        get(key)?.let { value ->
            when {
                value.isJsonPrimitive -> runCatching { value.asString }.getOrNull()
                value.isJsonArray -> value.asJsonArray.joinToString("/") { item ->
                    if (item.isJsonObject) item.asJsonObject.firstString("name", "title").orEmpty()
                    else runCatching { item.asString }.getOrDefault("")
                }.takeIf { it.isNotBlank() }
                else -> null
            }
        }
    }
    private fun JsonObject.firstLong(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
        get(key)?.let { runCatching { it.asLong }.getOrNull() }
    }
    private fun JsonObject.firstArray(vararg keys: String): JsonArray? = keys.firstNotNullOfOrNull { get(it)?.takeIf(JsonElement::isJsonArray)?.asJsonArray }
    private fun JsonObject.firstObject(vararg keys: String): JsonObject? = keys.firstNotNullOfOrNull { get(it)?.takeIf(JsonElement::isJsonObject)?.asJsonObject }
    private fun JsonObject?.toStringMap(): Map<String, String> = this?.entrySet()?.associate { (key, value) ->
        key to if (value.isJsonPrimitive) runCatching { value.asString }.getOrDefault("") else value.toString()
    }.orEmpty()
    private fun JsonArray?.toTextLineMap(): Map<Long, String> = this?.mapNotNull { element ->
        val row = element.takeIf { it.isJsonArray }?.asJsonArray ?: return@mapNotNull null
        val start = row.longAt(0) ?: return@mapNotNull null
        start to row.stringAt(2).orEmpty()
    }?.toMap().orEmpty()
    private fun JsonArray.getOrNull(index: Int): JsonElement? = if (index in 0 until size()) get(index) else null
    private fun JsonArray.longAt(index: Int): Long? = getOrNull(index)?.let { runCatching { it.asLong }.getOrNull() }
    private fun JsonArray.stringAt(index: Int): String? = getOrNull(index)?.let { runCatching { it.asString }.getOrNull() }

    companion object {
        private const val TAG = "LyricoSourceEngine"
        private val AUDIO_FILE_EXTENSIONS = setOf(
            "aac", "aif", "aiff", "alac", "ape", "dff", "dsdiff", "dsf", "flac",
            "m4a", "m4b", "m4p", "mp2", "mp3", "mp4", "mpga", "ogg", "opus",
            "wav", "wma", "wv"
        )
        private const val MAX_COVER_BYTES = 20L * 1024L * 1024L
        private val NUMERIC_ENTITY_REGEX = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")
        private val VERSION_NOISE_REGEX = Regex(
            """[(\[（【《]?\s*(?:official\s*(?:video|audio|mv)|music\s*video|lyric[s]?\s*video|lyrics?|完整版|高清|无损|动态歌词|歌词版|instrumental|inst\.?|off\s*vocal|伴奏|纯音乐|live|现场版?|remix|remaster(?:ed)?|acoustic|cover|sped\s*up|slowed|nightcore|demo|edit|radio\s*edit)\s*[)\]）】》]?""",
            RegexOption.IGNORE_CASE
        )
        private val MATCH_PUNCTUATION_REGEX = Regex("""[【】\[\]（）()《》<>「」『』\"'._/\\|,:;，。！？!?#&＆+×~·・\-–—]+""")
        private val MATCH_WHITESPACE_REGEX = Regex("""\s+""")
    }
}
