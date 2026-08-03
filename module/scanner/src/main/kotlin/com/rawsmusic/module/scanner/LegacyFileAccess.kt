package com.rawsmusic.module.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.rawsmusic.module.data.prefs.AppPreferences

/**
 * Resolves the scanner permission required by the selected storage access mode.
 */
object LegacyFileAccess {
    fun isRequested(): Boolean = AppPreferences.Scanner.legacyFileAccessEnabled

    fun hasPermission(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        else -> true
    }

    fun hasRequiredScanPermission(context: Context): Boolean {
        return if (isRequested()) hasPermission(context) else AudioReadPermission.hasPermission(context)
    }

    fun unavailableMessage(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        "传统文件访问未获得所有文件访问权限"
    } else {
        "传统文件访问未获得存储读取权限"
    }
}
