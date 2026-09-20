package com.rawsmusic.separation

import android.content.Context
import android.util.Log
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricTimingCorrection
import com.rawsmusic.core.common.model.LyricTimingCorrectionStatus
import com.rawsmusic.core.common.model.isTimelineCompatibleWith
import com.rawsmusic.lyrico.LyricOverrideFormat
import com.rawsmusic.lyrico.LyricoSourceEngine
import com.rawsmusic.module.scanner.LyricOverrideStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Writes an accepted timing correction as a lyric sidecar without touching the audio file.
 *
 * The selected output format is chosen by the caller. Original LRC/QRC files remain untouched.
 */
data class AiLyricTimingWritebackReceipt(
    val songPath: String,
    val correctionId: String,
    val outputPath: String,
    val backupPath: String?,
    val backupTargetPath: String?,
    val createdOutput: Boolean,
)

enum class AiLyricTimingOutputMode {
    KEEP_CURRENT,
    LINE,
    ENHANCED_WORD,
    TTML,
}

class AiLyricTimingOutputException(message: String) : IllegalArgumentException(message)

class AiLyricTimingWritebackService private constructor(
    context: Context,
    private val sourceEngine: LyricoSourceEngine,
) {
    private val appContext = context.applicationContext

    /**
     * Converts a correction to one explicit on-disk format before it is accepted. In
     * particular, an enhanced word file must contain word timing for every non-empty line;
     * writing only the CTC lines would create a mixed lyric file that changes format halfway
     * through the song.
     */
    fun prepareForOutput(
        correction: LyricTimingCorrection,
        outputMode: AiLyricTimingOutputMode,
    ): Result<LyricTimingCorrection> = runCatching {
        val targetMode = when (outputMode) {
            AiLyricTimingOutputMode.KEEP_CURRENT -> when {
                correction.original.lines.any { it.isTtml } -> AiLyricTimingOutputMode.TTML
                correction.original.lines.any { it.words.isNotEmpty() } -> AiLyricTimingOutputMode.ENHANCED_WORD
                else -> AiLyricTimingOutputMode.LINE
            }
            else -> outputMode
        }

        if (targetMode == AiLyricTimingOutputMode.ENHANCED_WORD) {
            if (correction.mode != com.rawsmusic.core.common.model.LyricTimingCorrectionMode.WORD_FORCE_ALIGNMENT) {
                throw AiLyricTimingOutputException(
                    appContext.getString(R.string.ai_lyric_timing_word_requires_ctc),
                )
            }
            if (!correction.corrected.hasCompleteWordTiming()) {
                throw AiLyricTimingOutputException(
                    appContext.getString(R.string.ai_lyric_timing_word_incomplete),
                )
            }
        }

        val normalizedLines = correction.corrected.lines.map { line ->
            when (targetMode) {
                AiLyricTimingOutputMode.LINE -> line.copy(words = emptyList(), isTtml = false)
                AiLyricTimingOutputMode.ENHANCED_WORD -> line.copy(isTtml = false)
                AiLyricTimingOutputMode.TTML -> line.copy(isTtml = true)
                AiLyricTimingOutputMode.KEEP_CURRENT -> line
            }
        }
        correction.copy(corrected = correction.corrected.copy(lines = normalizedLines))
    }

    fun canWriteOutput(
        correction: LyricTimingCorrection,
        outputMode: AiLyricTimingOutputMode,
    ): Boolean = prepareForOutput(correction, outputMode).isSuccess

    suspend fun apply(
        song: AudioFile,
        correction: LyricTimingCorrection,
        outputMode: AiLyricTimingOutputMode = AiLyricTimingOutputMode.KEEP_CURRENT,
    ): Result<AiLyricTimingWritebackReceipt> = withContext(Dispatchers.IO) {
        runCatching {
            require(correction.status == LyricTimingCorrectionStatus.ACCEPTED) {
                "歌词时间轴必须先明确接受后才能写入"
            }
            require(correction.original.isTimelineCompatibleWith(correction.corrected)) {
                "歌词修正包含非时间轴变化，拒绝写入"
            }

            val prepared = prepareForOutput(correction, outputMode).getOrThrow()
            val format = outputMode.toOverrideFormat(prepared.corrected)
            if (outputMode == AiLyricTimingOutputMode.KEEP_CURRENT && !prepared.changed) {
                return@runCatching AiLyricTimingWritebackReceipt(
                    songPath = song.path,
                    correctionId = correction.correctionId,
                    outputPath = "",
                    backupPath = null,
                    backupTargetPath = null,
                    createdOutput = false,
                )
            }

            val existing = findExistingOverride(song)
            val backup = existing?.let { backupExisting(it, song, correction) }
            try {
                val output = sourceEngine.writeOverride(song, prepared.corrected, format)
                AiLyricTimingWritebackReceipt(
                    songPath = song.path,
                    correctionId = correction.correctionId,
                    outputPath = output.absolutePath,
                    backupPath = backup?.absolutePath,
                    backupTargetPath = existing?.absolutePath,
                    createdOutput = existing?.canonicalPath != output.canonicalPath,
                )
            } catch (error: Throwable) {
                if (backup != null && !existing.isFile) {
                    runCatching { restoreBackup(backup, existing) }
                        .onFailure { Log.e(TAG, "Unable to restore lyric timing backup", it) }
                }
                throw error
            }
        }
    }

    suspend fun rollback(
        receipt: AiLyricTimingWritebackReceipt,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (receipt.outputPath.isBlank()) return@runCatching Unit
            val output = File(receipt.outputPath)
            val backup = receipt.backupPath?.let(::File)
            val backupTarget = receipt.backupTargetPath?.let(::File)
            if (backup?.isFile == true) {
                restoreBackup(backup, backupTarget)
                if (output.canonicalPath != backupTarget?.canonicalPath && output.exists()) {
                    require(output.delete()) { "无法删除 AI 歌词时间轴覆盖文件" }
                }
            } else if (receipt.createdOutput && output.exists()) {
                require(output.delete()) { "无法删除 AI 歌词时间轴覆盖文件" }
            }
        }
    }

    private fun findExistingOverride(song: AudioFile): File? {
        val privateFiles = LyricOverrideStore.filesFor(song)
        if (song.path.startsWith("content://", ignoreCase = true)) {
            return privateFiles.filter(File::isFile).maxByOrNull(File::lastModified)
        }
        val audio = File(song.path)
        if (!audio.isFile) {
            return privateFiles.filter(File::isFile).maxByOrNull(File::lastModified)
        }
        val suffixes = listOf(".raws.ttml", ".raws.enhanced.lrc", ".raws.lrc")
        val parent = audio.parentFile ?: return null
        val trackPrefix = if (song.cueTrackIndex > 0 || song.cueOffsetMs > 0L) {
            ".track${song.cueTrackIndex}"
        } else {
            ""
        }
        val publicFiles = suffixes.asSequence()
            .map { suffix -> File(parent, audio.nameWithoutExtension + trackPrefix + suffix) }
            .toList()
        return (publicFiles + privateFiles)
            .filter(File::isFile)
            .maxByOrNull(File::lastModified)
    }

    private fun backupExisting(
        existing: File,
        song: AudioFile,
        correction: LyricTimingCorrection,
    ): File {
        val key = digest("${song.path}\u0000${correction.correctionId}")
        val backupRoot = File(appContext.filesDir, "ai_separation/lyric_timing/backups")
        require(backupRoot.isDirectory || backupRoot.mkdirs()) {
            "无法创建歌词时间轴备份目录"
        }
        val backup = File(backupRoot, "$key-${existing.name}")
        if (!backup.isFile) {
            existing.copyTo(backup, overwrite = false)
        }
        return backup
    }

    private fun restoreBackup(backup: File, target: File?) {
        val destination = target ?: error("歌词时间轴备份目标不存在")
        if (destination.exists()) require(destination.delete()) { "无法删除待恢复的歌词覆盖文件" }
        if (!backup.renameTo(destination)) {
            backup.copyTo(destination, overwrite = true)
            require(backup.delete()) { "无法清理歌词时间轴备份" }
        }
    }

    private fun digest(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
        .take(24)

    private fun AiLyricTimingOutputMode.toOverrideFormat(lyrics: com.rawsmusic.core.common.model.LyricData): LyricOverrideFormat = when (this) {
        AiLyricTimingOutputMode.KEEP_CURRENT -> when {
            lyrics.lines.any { it.isTtml } -> LyricOverrideFormat.TTML
            lyrics.lines.any { it.words.isNotEmpty() } -> LyricOverrideFormat.ENHANCED_LRC
            else -> LyricOverrideFormat.LRC
        }
        AiLyricTimingOutputMode.LINE -> LyricOverrideFormat.LRC
        AiLyricTimingOutputMode.ENHANCED_WORD -> LyricOverrideFormat.ENHANCED_LRC
        AiLyricTimingOutputMode.TTML -> LyricOverrideFormat.TTML
    }

    private fun com.rawsmusic.core.common.model.LyricData.hasCompleteWordTiming(): Boolean =
        lines.filter { it.text.isNotBlank() }.all { line ->
            line.words.isNotEmpty() &&
                line.words.all { it.begin >= 0L && it.end > it.begin } &&
                line.words.joinToString("") { it.text }
                    .filterNot(Char::isWhitespace) == line.text.filterNot(Char::isWhitespace)
        }

    companion object {
        private const val TAG = "AiLyricTiming"
        @Volatile private var instance: AiLyricTimingWritebackService? = null

        fun get(context: Context): AiLyricTimingWritebackService = instance ?: synchronized(this) {
            instance ?: AiLyricTimingWritebackService(
                context = context.applicationContext,
                sourceEngine = LyricoSourceEngine(context.applicationContext),
            ).also { instance = it }
        }
    }
}
