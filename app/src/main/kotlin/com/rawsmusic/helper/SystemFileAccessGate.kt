package com.rawsmusic.helper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.rawsmusic.R
import com.rawsmusic.core.common.ui.AppNoticeBus

/**
 * Android 11+ hard gate for every system file/document access path.
 *
 * RawSMusic deliberately requires MANAGE_EXTERNAL_STORAGE before entering a system picker on
 * Android R and newer. Some vendor ROMs expose a heavily restricted DocumentsUI surface to apps
 * without broad file access, which can hide locations or leave otherwise valid files disabled.
 * The picker still grants its own URI permission; this gate is an additional prerequisite.
 */
object SystemFileAccessPolicy {
    fun hasRequiredAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun appAccessIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )

    fun globalAccessIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
}

/**
 * Returns a gate function. Invoke it with the file-picker action that should run after access has
 * been granted. On Android 10 and older the action runs immediately.
 */
@Composable
fun rememberSystemFileAccessGate(): ((() -> Unit) -> Unit) {
    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val latestContext by rememberUpdatedState(context)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val action = pendingAction
        pendingAction = null
        if (SystemFileAccessPolicy.hasRequiredAccess()) {
            action?.invoke()
        } else {
            AppNoticeBus.error(latestContext.getString(R.string.scan_settings_legacy_access_denied_toast))
        }
    }

    return { action ->
        if (SystemFileAccessPolicy.hasRequiredAccess()) {
            action()
        } else {
            pendingAction = action
            val launched = runCatching {
                permissionLauncher.launch(SystemFileAccessPolicy.appAccessIntent(context))
                true
            }.recoverCatching {
                permissionLauncher.launch(SystemFileAccessPolicy.globalAccessIntent())
                true
            }.getOrDefault(false)

            if (!launched) {
                pendingAction = null
                AppNoticeBus.error(context.getString(R.string.scan_settings_legacy_access_denied_toast))
            }
        }
    }
}
