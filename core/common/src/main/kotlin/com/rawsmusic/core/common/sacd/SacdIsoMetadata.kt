package com.rawsmusic.core.common.sacd

import android.net.Uri
import com.rawsmusic.core.common.CoreInit
import java.io.File

/** Routes metadata readers to the lossless snapshot while audio readers stay on SACD sectors. */
object SacdIsoMetadata {
    private val readLock = Any()
    fun isCandidate(path: String): Boolean = path.substringAfterLast('.').equals("iso", true) ||
        path.startsWith("sacd://") || SacdIsoSourceRegistry.area(path) != null

    fun source(path: String, knownIso: Boolean = false): File? {
        return synchronized(readLock) { resolveSource(path, knownIso) }
    }

    fun <T> withSource(path: String, knownIso: Boolean = false, action: (File?) -> T): T =
        synchronized(readLock) { action(resolveSource(path, knownIso)) }

    private fun resolveSource(path: String, knownIso: Boolean): File? {
        if (!knownIso && !isCandidate(path)) return null
        return runCatching {
            val physical = if (path.startsWith("sacd://")) Uri.parse(path).pathSegments.getOrNull(1).orEmpty() else path
            val cache = File(CoreInit.getApp().cacheDir, "sacd_metadata")
            if (physical.startsWith("content://")) {
                CoreInit.getApp().contentResolver.openFileDescriptor(Uri.parse(physical), "r")?.use {
                    SacdMetadataArchive.restore(File("/proc/self/fd/${it.fd}"), cache)
                }
            } else {
                val file = File(physical)
                if (file.isFile) SacdMetadataArchive.restore(file, cache) else null
            }
        }.getOrNull()
    }

    /** Keep a cache entry leased until the native reader has opened/finished reading it. */
    fun <T> withReadablePath(path: String, action: (String) -> T): T {
        if (!isCandidate(path)) return action(path)
        return synchronized(readLock) { action(source(path)?.absolutePath ?: path) }
    }
}
