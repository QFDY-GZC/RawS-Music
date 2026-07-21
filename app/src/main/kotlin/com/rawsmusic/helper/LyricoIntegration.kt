package com.rawsmusic.helper

import android.app.Activity
import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.rawsmusic.core.common.model.AudioFile
import java.io.File

object LyricoIntegration {
    const val LOG_TAG = "LyricoLaunchTrace"
    const val PACKAGE_NAME = "com.lonx.lyrico"
    private const val DEBUG_PACKAGE_NAME = "com.lonx.lyrico.debug"
    const val ACTION_EDIT_TAG = "com.lonx.lyrico.action.EDIT_TAG"
    const val PROJECT_URL = "https://github.com/Replica0110/Lyrico"

    private val packageNames = listOf(PACKAGE_NAME, DEBUG_PACKAGE_NAME)

    fun isInstalled(context: Context): Boolean {
        val states = packageNames.associateWith { packageName ->
            runCatching { context.packageManager.getApplicationInfo(packageName, 0) }
                .fold(
                    onSuccess = { "installed(enabled=${it.enabled})" },
                    onFailure = { "missing(${it.javaClass.simpleName})" }
                )
        }
        val installed = states.values.any { it.startsWith("installed") }
        Log.d(LOG_TAG, "installed_check result=$installed packages=$states")
        return installed
    }

    fun buildEditIntent(context: Context, song: AudioFile): Intent? {
        Log.i(
            LOG_TAG,
            "edit_build_start sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL} " +
                "songId=${song.id} path=${song.path}"
        )
        val resolvedAudio = resolveAudioUri(context, song)
        if (resolvedAudio == null) {
            Log.e(LOG_TAG, "edit_build_failed reason=no_audio_uri songId=${song.id} path=${song.path}")
            return null
        }
        val uri = resolvedAudio.uri
        val actions = listOf(ACTION_EDIT_TAG, Intent.ACTION_VIEW, Intent.ACTION_SEND)

        for (packageName in packageNames) {
            if (!isPackageInstalled(context, packageName)) {
                Log.d(LOG_TAG, "edit_package_skip package=$packageName reason=not_visible_or_missing")
                continue
            }
            for (action in actions) {
                val intent = buildAudioIntent(
                    context = context,
                    song = song,
                    uri = uri,
                    action = action,
                    grantFlags = resolvedAudio.grantFlags
                ).setPackage(packageName)
                val resolved = runCatching {
                    context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                }.onFailure { error ->
                    Log.e(LOG_TAG, "edit_resolve_error package=$packageName action=$action", error)
                }.getOrNull()
                if (resolved == null) {
                    Log.w(
                        LOG_TAG,
                        "edit_resolve_miss package=$packageName action=$action uri=$uri type=${intent.type}"
                    )
                    continue
                }
                intent.component = resolved.activityInfo?.let {
                    android.content.ComponentName(it.packageName, it.name)
                }
                Log.d(
                    LOG_TAG,
                    "edit_uri_ready strategy=${resolvedAudio.strategy} uri=$uri " +
                        "grant=${grantModeLabel(resolvedAudio.grantFlags)}"
                )
                traceIntent(context, intent, "edit_build_ready")
                return intent
            }
        }
        Log.e(LOG_TAG, "edit_build_failed reason=no_resolvable_activity uri=$uri")
        return null
    }

    fun buildLaunchIntent(context: Context): Intent? {
        Log.i(
            LOG_TAG,
            "app_build_start sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL}"
        )
        for (packageName in packageNames) {
            runCatching { context.packageManager.getLaunchIntentForPackage(packageName) }
                .onFailure { Log.e(LOG_TAG, "app_launch_intent_error package=$packageName", it) }
                .getOrNull()
                ?.let { intent ->
                    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    traceIntent(context, intent, "app_build_ready")
                    return intent
                }
            val fallback = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName)
            val resolved = runCatching {
                context.packageManager.resolveActivity(fallback, PackageManager.MATCH_DEFAULT_ONLY)
            }.onFailure { Log.e(LOG_TAG, "app_fallback_resolve_error package=$packageName", it) }
                .getOrNull() ?: continue
            fallback.component = resolved.activityInfo?.let {
                android.content.ComponentName(it.packageName, it.name)
            }
            if (context !is Activity) fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            traceIntent(context, fallback, "app_fallback_ready")
            return fallback
        }
        Log.e(LOG_TAG, "app_build_failed reason=no_launcher_activity")
        return null
    }

    fun buildProjectIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL))

    fun traceIntent(context: Context, intent: Intent, stage: String) {
        val resolved = runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.onFailure { Log.e(LOG_TAG, "$stage resolve_exception", it) }.getOrNull()
        val candidates = runCatching {
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.onFailure { Log.e(LOG_TAG, "$stage query_exception", it) }.getOrDefault(emptyList())
        Log.i(
            LOG_TAG,
            "$stage action=${intent.action} package=${intent.`package`} " +
                "component=${intent.component?.flattenToShortString()} data=${intent.data} type=${intent.type} " +
                "flags=0x${intent.flags.toString(16)} clipItems=${intent.clipData?.itemCount ?: 0} " +
                "resolved=${resolved?.activityInfo?.packageName}/${resolved?.activityInfo?.name} " +
                "candidates=${candidates.joinToString { "${it.activityInfo.packageName}/${it.activityInfo.name}" }}"
        )
    }

    fun traceLaunchFailure(stage: String, error: Throwable) {
        Log.e(LOG_TAG, "$stage failed type=${error.javaClass.name} message=${error.message}", error)
    }

    private fun buildAudioIntent(
        context: Context,
        song: AudioFile,
        uri: Uri,
        action: String,
        grantFlags: Int
    ): Intent = Intent(action).apply {
        addCategory(Intent.CATEGORY_DEFAULT)
        if (action == Intent.ACTION_SEND) {
            type = "audio/*"
        } else {
            setDataAndType(uri, "audio/*")
        }
        clipData = ClipData.newUri(context.contentResolver, song.displayName, uri)
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, song.displayName)
        putExtra("title", song.title)
        putExtra("artist", song.artist)
        putExtra("album", song.album)
        addFlags(grantFlags)
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0)
    }.isSuccess

    private data class ResolvedAudioUri(
        val uri: Uri,
        val grantFlags: Int,
        val strategy: String
    )

    /**
     * Resolve a URI that Lyrico can actually open without asking ActivityManager
     * to grant permissions RawSMusic itself does not hold.
     *
     * Local files prefer RawSMusic's own FileProvider when it can expose a writable
     * descriptor. This is the most reliable external-editor path because the app
     * owns the provider and may grant temporary read/write access directly.
     *
     * MediaStore and third-party content URIs are granted only the access modes
     * verified by opening the descriptor. In particular, READ_MEDIA_AUDIO does not
     * imply write access, so a MediaStore URI is commonly forwarded read-only and
     * the editor can request its own scoped-storage write consent.
     */
    private fun resolveAudioUri(context: Context, song: AudioFile): ResolvedAudioUri? {
        if (song.path.startsWith("content://", ignoreCase = true)) {
            val uri = runCatching { Uri.parse(song.path) }
                .onFailure { Log.e(LOG_TAG, "uri_parse_failed path=${song.path}", it) }
                .getOrNull() ?: return null
            return resolveAccessibleUri(context, uri, "existing_content")
        }

        val source = File(song.path)
        var readableFileProviderFallback: ResolvedAudioUri? = null
        if (source.isFile) {
            val fileProviderUri = runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    source
                )
            }.onFailure { Log.e(LOG_TAG, "uri_file_provider_failed path=${song.path}", it) }
                .getOrNull()

            if (fileProviderUri != null) {
                val candidate = resolveAccessibleUri(context, fileProviderUri, "file_provider")
                if (candidate != null) {
                    if (candidate.grantFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
                        return candidate.copy(strategy = "file_provider_rw")
                    }
                    readableFileProviderFallback = candidate.copy(strategy = "file_provider_read")
                }
            }
        }

        resolveMediaStoreUri(context, song)?.let { mediaUri ->
            resolveAccessibleUri(context, mediaUri, "media_store")?.let { candidate ->
                return candidate.copy(
                    strategy = if (
                        candidate.grantFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
                    ) {
                        "media_store_rw"
                    } else {
                        "media_store_read"
                    }
                )
            }
        }

        if (readableFileProviderFallback != null) {
            return readableFileProviderFallback
        }

        Log.e(
            LOG_TAG,
            "uri_file_unavailable path=${song.path} exists=${source.exists()} " +
                "readable=${source.canRead()} writable=${source.canWrite()}"
        )
        return null
    }

    private fun resolveAccessibleUri(
        context: Context,
        uri: Uri,
        strategy: String
    ): ResolvedAudioUri? {
        val canRead = canOpenUri(context, uri, "r")
        if (!canRead) {
            Log.w(LOG_TAG, "uri_access_denied strategy=$strategy mode=r uri=$uri")
            return null
        }
        val canWrite = canOpenUri(context, uri, "rw")
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            if (canWrite) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        Log.d(
            LOG_TAG,
            "uri_resolved strategy=$strategy uri=$uri grant=${grantModeLabel(flags)}"
        )
        return ResolvedAudioUri(uri, flags, strategy)
    }

    private fun canOpenUri(context: Context, uri: Uri, mode: String): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(uri, mode)?.use { true } == true
    }.onFailure {
        Log.d(
            LOG_TAG,
            "uri_access_probe_failed mode=$mode uri=$uri " +
                "type=${it.javaClass.simpleName} message=${it.message}"
        )
    }.getOrDefault(false)

    private fun resolveMediaStoreUri(context: Context, song: AudioFile): Uri? {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        if (song.id > 0L) {
            val candidate = ContentUris.withAppendedId(collection, song.id)
            val matchesPath = runCatching {
                context.contentResolver.query(
                    candidate,
                    arrayOf(MediaStore.Audio.Media.DATA),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    cursor.moveToFirst() && (
                        song.path.isBlank() ||
                            File(cursor.getString(0).orEmpty()).canonicalPath ==
                            File(song.path).canonicalPath
                        )
                } == true
            }.onFailure { Log.e(LOG_TAG, "uri_media_id_query_failed candidate=$candidate", it) }
                .getOrDefault(false)
            if (matchesPath) {
                Log.d(LOG_TAG, "uri_media_match strategy=media_id uri=$candidate")
                return candidate
            }
            Log.d(LOG_TAG, "uri_media_id_mismatch candidate=$candidate path=${song.path}")
        }

        if (song.path.isBlank()) return null
        return runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.DATA} = ?",
                arrayOf(song.path),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(collection, cursor.getLong(0))
                } else {
                    null
                }
            }
        }.onSuccess { uri ->
            if (uri != null) Log.d(LOG_TAG, "uri_media_match strategy=media_path uri=$uri")
        }.onFailure { Log.e(LOG_TAG, "uri_media_path_query_failed path=${song.path}", it) }
            .getOrNull()
    }

    private fun grantModeLabel(flags: Int): String = when {
        flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0 -> "rw"
        flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 -> "r"
        else -> "none"
    }
}
