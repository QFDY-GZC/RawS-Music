package com.rawsmusic.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.rawsmusic.R
import com.rawsmusic.core.ui.scene.pages.ScanSettingsPage
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.scanner.ScanScheduler

class ScanSettingsActivity : BaseSettingsActivity() {

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // 权限结果不用强依赖；用户可能还需要去系统设置里打开文件访问权限。
    }

    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

        AppPreferences.Scanner.legacyFileAccessEnabled = granted

        Toast.makeText(
            this,
            if (granted) getString(R.string.scan_settings_legacy_access_enabled_toast) else getString(R.string.scan_settings_legacy_access_denied_toast),
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ScanSettingsPage(
                onBack = { finish() },
                onRescan = {
                    ScanScheduler.requestDirScan(this, getString(R.string.scan_settings_rescan_reason_manual))
                },
                onRequestLegacyAudioAccess = {
                    requestLegacyAudioAccess()
                }
            )
        }
    }

    private fun requestLegacyAudioAccess() {
        if (Build.VERSION.SDK_INT >= 33) {
            audioPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
            )
        } else {
            audioPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            )
        }

        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                allFilesAccessLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }.recoverCatching {
                allFilesAccessLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                )
            }
        } else {
            AppPreferences.Scanner.legacyFileAccessEnabled = true
        }
    }
}
