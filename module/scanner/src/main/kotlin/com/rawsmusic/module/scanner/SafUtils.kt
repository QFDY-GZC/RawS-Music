package com.rawsmusic.module.scanner

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import com.rawsmusic.core.common.utils.AppLogger
import java.io.File

/**
 * SAF 工具类。
 *
 * 提供 SAF 路径判断、URI 转换、文件操作等功能。
 */
object SafUtils {

    private const val TAG = "SafUtils"

    /**
     * 判断路径是否需要 SAF 访问
     * SAF 路径：不以 / 开头，不是 content:，不是 http:
     */
    fun isSafPath(path: String): Boolean {
        if (path.startsWith("/")) return false
        if (path.startsWith("content:")) return false
        if (path.startsWith("http:")) return false
        if (path.startsWith("https:")) return false
        return true
    }

    /**
     * 从 document URI 提取路径
     */
    fun uriToPath(uri: Uri): String? {
        try {
            val docId = DocumentsContract.getDocumentId(uri)
            if (docId.startsWith("primary:")) {
                val path = docId.removePrefix("primary:")
                return "${android.os.Environment.getExternalStorageDirectory()}/$path"
            }
            // 外部存储
            if (docId.contains(":")) {
                val parts = docId.split(":", limit = 2)
                if (parts.size == 2) {
                    val volume = parts[0]
                    val path = parts[1]
                    // 尝试找到挂载点
                    val mountPoint = findVolumeMountPoint(volume)
                    if (mountPoint != null) {
                        return "$mountPoint/$path"
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "uriToPath failed for $uri: ${e.message}")
        }
        return null
    }

    /**
     * 从文件路径构建 document URI
     */
    fun pathToUri(path: String): Uri? {
        try {
            val file = File(path)
            if (!file.exists()) return null

            val storageDir = android.os.Environment.getExternalStorageDirectory()
            if (path.startsWith(storageDir.absolutePath)) {
                val relativePath = path.removePrefix(storageDir.absolutePath).removePrefix("/")
                val docId = "primary:$relativePath"
                return DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    docId
                )
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "pathToUri failed for $path: ${e.message}")
        }
        return null
    }

    /**
     * 枚举所有挂载的存储卷
     */
    fun getMountedVolumes(context: Context): List<StorageVolume> {
        val volumes = mutableListOf<StorageVolume>()
        try {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val storageVolumes = storageManager.storageVolumes

            for (volume in storageVolumes) {
                val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    volume.directory?.absolutePath
                } else {
                    try {
                        val method = volume.javaClass.getMethod("getPath")
                        method.invoke(volume) as? String
                    } catch (_: Exception) { null }
                }

                if (path != null) {
                    volumes.add(StorageVolume(
                        path = path,
                        isPrimary = volume.isPrimary,
                        isRemovable = volume.isRemovable,
                        uuid = try { volume.uuid } catch (_: Exception) { null }
                    ))
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "getMountedVolumes failed", e)
        }
        return volumes
    }

    /**
     * 检查存储卷是否已挂载
     */
    fun isVolumeMounted(context: Context, uuid: String): Boolean {
        val volumes = getMountedVolumes(context)
        return volumes.any { it.uuid == uuid }
    }

    private fun findVolumeMountPoint(volumeName: String): String? {
        // 常见的卷名映射
        return when (volumeName) {
            "primary" -> android.os.Environment.getExternalStorageDirectory().absolutePath
            else -> {
                // 尝试 /storage/$volumeName
                val path = "/storage/$volumeName"
                if (File(path).exists()) path else null
            }
        }
    }

    data class StorageVolume(
        val path: String,
        val isPrimary: Boolean,
        val isRemovable: Boolean,
        val uuid: String?
    )
}
