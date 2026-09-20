package com.rawsmusic.transcode

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.rawsmusic.module.scanner.LibraryScanForegroundService
import java.io.File

internal data class AudioTranscodePostActionReport(
    val detail: String? = null,
    val sourceDeleted: Boolean? = null,
    val sourceDeleteNeedsConsent: Boolean = false,
    val mediaScanRequested: Boolean = false,
    val libraryScanRequested: Boolean = false,
)

private data class SourceDeleteResult(
    val deleted: Boolean,
    val needsConsent: Boolean = false,
    val detail: String,
)

internal data class AudioTranscodeDeleteConsentRequest(
    val intentSender: IntentSender? = null,
    val alreadyDeleted: Boolean = false,
    val detail: String? = null,
)

/**
 * Side effects which are intentionally outside the encoder/verification transaction.
 *
 * The converted output has already passed metadata verification, fsync and atomic commit before
 * this object is called. A post-action failure therefore never re-labels a valid conversion as an
 * encode failure, and — most importantly — source deletion can never run for a failed conversion.
 */
internal object AudioTranscodePostProcessor {
    fun afterCommittedSuccess(
        context: Context,
        request: AudioTranscodeRequest,
        output: File,
    ): AudioTranscodePostActionReport {
        val messages = mutableListOf<String>()
        val source = File(request.inputPath)
        var sourceDeleted: Boolean? = null
        var sourceDeleteNeedsConsent = false

        if (request.deleteSourceOnSuccess) {
            val sameFile = runCatching { source.canonicalFile == output.canonicalFile }.getOrDefault(false)
            val deleteResult = when {
                sameFile -> SourceDeleteResult(
                    deleted = false,
                    detail = "源文件与输出文件指向同一路径，已拒绝删除",
                )
                !source.exists() -> SourceDeleteResult(
                    deleted = true,
                    detail = "源文件已不存在",
                )
                else -> deleteSource(context.applicationContext, source)
            }
            sourceDeleted = deleteResult.deleted
            sourceDeleteNeedsConsent = deleteResult.needsConsent
            messages += deleteResult.detail
        }

        // A committed output must become visible to Android's media database regardless of
        // whether the user asked RawSMusic itself to rescan the library. The latter is an app
        // library policy; MediaStore visibility is part of successfully publishing the file.
        val scanPaths = buildList {
            add(output.absolutePath)
            if (request.deleteSourceOnSuccess) add(source.absolutePath)
        }.distinct()
        val mediaScanRequested = runCatching {
            MediaScannerConnection.scanFile(
                context.applicationContext,
                scanPaths.toTypedArray(),
                null,
                null,
            )
            true
        }.getOrDefault(false)
        messages += if (mediaScanRequested) {
            "已刷新系统媒体库"
        } else {
            "系统媒体库刷新请求失败"
        }

        return AudioTranscodePostActionReport(
            detail = messages.takeIf(List<String>::isNotEmpty)?.joinToString(" · "),
            sourceDeleted = sourceDeleted,
            sourceDeleteNeedsConsent = sourceDeleteNeedsConsent,
            mediaScanRequested = mediaScanRequested,
            libraryScanRequested = request.autoScanOnSuccess,
        )
    }

    fun requestRawSMusicLibraryScan(context: Context): Boolean = runCatching {
        LibraryScanForegroundService.start(
            context.applicationContext,
            reason = "格式转换完成",
        )
        true
    }.getOrDefault(false)

    fun createSourceDeleteConsentRequest(
        context: Context,
        sourcePath: String,
    ): AudioTranscodeDeleteConsentRequest {
        val source = File(sourcePath)
        if (!source.exists()) {
            return AudioTranscodeDeleteConsentRequest(alreadyDeleted = true)
        }
        val mediaUri = findMediaUri(context.applicationContext, source.absolutePath)
            ?: return AudioTranscodeDeleteConsentRequest(
                detail = "系统媒体库中找不到源文件记录",
            )
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> runCatching {
                AudioTranscodeDeleteConsentRequest(
                    intentSender = MediaStore.createDeleteRequest(
                        context.contentResolver,
                        listOf(mediaUri),
                    ).intentSender,
                )
            }.getOrElse {
                AudioTranscodeDeleteConsentRequest(detail = "无法创建系统删除授权请求")
            }

            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                try {
                    context.contentResolver.delete(mediaUri, null, null)
                    AudioTranscodeDeleteConsentRequest(
                        alreadyDeleted = !source.exists(),
                        detail = if (source.exists()) "系统未删除源文件" else null,
                    )
                } catch (recoverable: RecoverableSecurityException) {
                    AudioTranscodeDeleteConsentRequest(
                        intentSender = recoverable.userAction.actionIntent.intentSender,
                    )
                } catch (_: Throwable) {
                    AudioTranscodeDeleteConsentRequest(detail = "无法创建系统删除授权请求")
                }
            }

            else -> AudioTranscodeDeleteConsentRequest(detail = "当前系统不支持媒体删除授权流程")
        }
    }

    fun finishAuthorizedSourceDelete(
        context: Context,
        sourcePath: String,
    ): Boolean {
        val source = File(sourcePath)
        val deleted = !source.exists()
        if (deleted) {
            runCatching {
                MediaScannerConnection.scanFile(
                    context.applicationContext,
                    arrayOf(source.absolutePath),
                    null,
                    null,
                )
            }
            requestRawSMusicLibraryScan(context.applicationContext)
        }
        return deleted
    }

    private fun deleteSource(context: Context, source: File): SourceDeleteResult {
        val directDeleted = runCatching { source.isFile && source.delete() }.getOrDefault(false)
        if (directDeleted || !source.exists()) {
            return SourceDeleteResult(
                deleted = true,
                detail = "源文件已删除",
            )
        }

        val mediaUri = findMediaUri(context, source.absolutePath)
            ?: return SourceDeleteResult(
                deleted = false,
                detail = "源文件删除失败，已保留原文件",
            )
        return try {
            val affected = context.contentResolver.delete(mediaUri, null, null)
            if (affected > 0 || !source.exists()) {
                SourceDeleteResult(
                    deleted = true,
                    detail = "源文件已删除",
                )
            } else {
                SourceDeleteResult(
                    deleted = false,
                    detail = "系统媒体库未允许删除，已保留源文件",
                )
            }
        } catch (_: RecoverableSecurityException) {
            SourceDeleteResult(
                deleted = false,
                needsConsent = true,
                detail = "删除源文件需要系统授权，已保留原文件",
            )
        } catch (_: SecurityException) {
            SourceDeleteResult(
                deleted = false,
                needsConsent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                detail = "删除源文件需要系统授权，已保留原文件",
            )
        } catch (_: Throwable) {
            SourceDeleteResult(
                deleted = false,
                detail = "源文件删除失败，已保留原文件",
            )
        }
    }

    private fun findMediaUri(context: Context, path: String): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        return runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.MediaColumns.DATA} = ?",
                arrayOf(path),
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                ContentUris.withAppendedId(collection, id)
            }
        }.getOrNull()
    }
}
