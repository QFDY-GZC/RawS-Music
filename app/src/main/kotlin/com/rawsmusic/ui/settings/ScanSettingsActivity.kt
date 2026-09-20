package com.rawsmusic.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import androidx.activity.result.contract.ActivityResultContracts
import com.rawsmusic.R
import com.rawsmusic.core.ui.scene.pages.ScanSettingsPage
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.scanner.ScanScheduler

class ScanSettingsActivity : BaseSettingsActivity() {

    private val legacyReadPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateLegacyAccessState(granted)
    }

    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
        updateLegacyAccessState(granted)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            legacyReadPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            updateLegacyAccessState(true)
        }
    }

    private fun updateLegacyAccessState(granted: Boolean) {
        AppPreferences.Scanner.legacyFileAccessEnabled = granted
        if (granted) {
            AppNoticeBus.post(
                message = getString(R.string.scan_settings_legacy_access_enabled_toast),
                icon = AppNoticeIcon.SCAN,
            )
        } else {
            AppNoticeBus.error(getString(R.string.scan_settings_legacy_access_denied_toast))
        }
        if (granted) {
            ScanScheduler.requestDirScan(this, getString(R.string.scan_settings_rescan_reason_manual))
        }
    }
}
