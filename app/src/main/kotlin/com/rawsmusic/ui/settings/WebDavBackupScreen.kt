package com.rawsmusic.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.BackupArchiveManager
import com.rawsmusic.module.data.prefs.WebDavBackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun WebDavBackupScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var isBackingUp by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var isLocalBusy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var statusSuccess by remember { mutableStateOf(false) }

    fun showFailure(message: String) {
        statusSuccess = false
        statusMessage = message
    }

    fun showSuccess(message: String) {
        statusSuccess = true
        statusMessage = message
    }

    fun formatRestoreReport(report: BackupArchiveManager.RestoreReport): String {
        val parts = buildList {
            add(context.getString(R.string.settings_backup_report_settings, report.restoredMmkv + report.restoredSharedPreferences))
            if (report.playlistSectionRestored) add(context.getString(R.string.settings_backup_report_playlists))
            if (report.playbackSectionRestored) add(context.getString(R.string.settings_backup_report_playback))
            if (report.skippedPreferences > 0) {
                add(context.getString(R.string.settings_backup_report_skipped, report.skippedPreferences))
            }
            if (report.externalReferenceCount > 0) {
                add(context.getString(R.string.settings_backup_report_external, report.externalReferenceCount))
            }
        }
        return parts.joinToString(context.getString(R.string.settings_backup_report_separator))
    }

    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri == null) {
            isLocalBusy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = BackupArchiveManager.exportBytes(context)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        output.write(bytes)
                        output.flush()
                    } ?: error(context.getString(R.string.settings_backup_write_failed))
                }
            }
            isLocalBusy = false
            result.fold(
                onSuccess = { showSuccess(context.getString(R.string.settings_backup_export_success)) },
                onFailure = { showFailure(context.getString(R.string.settings_backup_export_failed, it.message.orEmpty())) },
            )
        }
    }

    val openBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        isLocalBusy = true
        statusMessage = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error(context.getString(R.string.settings_backup_read_failed))
                    BackupArchiveManager.restoreBytes(context, bytes)
                }
            }
            isLocalBusy = false
            result.fold(
                onSuccess = { report ->
                    showSuccess(
                        context.getString(
                            R.string.settings_backup_import_success,
                            formatRestoreReport(report),
                        )
                    )
                },
                onFailure = { error ->
                    showFailure(context.getString(R.string.settings_backup_import_failed, error.message.orEmpty()))
                },
            )
        }
    }

    val isConfigured = AppPreferences.WebDav.url.isNotBlank()

    SettingsPage(title = stringResource(R.string.settings_webdav_backup_title), onBack = onBack) {
        SettingsCard {
            Text(
                stringResource(R.string.settings_backup_local_header),
                fontSize = 18.sp,
                color = MiuixTheme.colorScheme.onBackground,
                fontFamily = appFontFamily(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_backup_local_desc),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontFamily = appFontFamily(),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = {
                        if (isLocalBusy) return@TextButton
                        isLocalBusy = true
                        statusMessage = ""
                        createBackupLauncher.launch("rawsmusic_backup.json")
                    },
                    enabled = !isLocalBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(R.string.settings_backup_export_action),
                        color = if (!isLocalBusy) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline,
                        fontSize = 15.sp,
                        fontFamily = appFontFamily(),
                    )
                }
                TextButton(
                    onClick = { if (!isLocalBusy) openBackupLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
                    enabled = !isLocalBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(R.string.settings_backup_import_action),
                        color = if (!isLocalBusy) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline,
                        fontSize = 15.sp,
                        fontFamily = appFontFamily(),
                    )
                }
            }
            if (isLocalBusy) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            Text(
                stringResource(R.string.settings_webdav_backup_restore_header),
                fontSize = 18.sp,
                color = MiuixTheme.colorScheme.onBackground,
                fontFamily = appFontFamily(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_webdav_backup_restore_desc),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontFamily = appFontFamily(),
            )

            if (!isConfigured) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.settings_webdav_not_configured),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontFamily = appFontFamily(),
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = {
                        if (isBackingUp) return@TextButton
                        isBackingUp = true
                        statusMessage = ""
                        scope.launch {
                            val result = WebDavBackupManager.backup(context)
                            isBackingUp = false
                            result.fold(
                                onSuccess = { showSuccess(context.getString(R.string.settings_webdav_backup_success)) },
                                onFailure = { showFailure(context.getString(R.string.settings_webdav_backup_failed, it.message.orEmpty())) },
                            )
                        }
                    },
                    enabled = isConfigured && !isBackingUp,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (isBackingUp) stringResource(R.string.settings_webdav_backing_up)
                        else stringResource(R.string.settings_webdav_backup_action),
                        color = if (isConfigured && !isBackingUp) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline,
                        fontSize = 15.sp,
                        fontFamily = appFontFamily(),
                    )
                }

                TextButton(
                    onClick = {
                        if (isRestoring) return@TextButton
                        isRestoring = true
                        statusMessage = ""
                        scope.launch {
                            val result = WebDavBackupManager.restore(context)
                            isRestoring = false
                            result.fold(
                                onSuccess = { report ->
                                    showSuccess(
                                        context.getString(
                                            R.string.settings_webdav_restore_success,
                                            formatRestoreReport(report),
                                        )
                                    )
                                },
                                onFailure = { showFailure(context.getString(R.string.settings_webdav_restore_failed, it.message.orEmpty())) },
                            )
                        }
                    },
                    enabled = isConfigured && !isRestoring,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (isRestoring) stringResource(R.string.settings_webdav_restoring)
                        else stringResource(R.string.settings_webdav_restore_action),
                        color = if (isConfigured && !isRestoring) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline,
                        fontSize = 15.sp,
                        fontFamily = appFontFamily(),
                    )
                }
            }
        }

        if (statusMessage.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            SettingsCard {
                Text(
                    statusMessage,
                    fontSize = 14.sp,
                    color = if (statusSuccess) Color(0xFF2E7D32) else Color(0xFFC62828),
                    fontFamily = appFontFamily(),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader(stringResource(R.string.settings_webdav_config_info))
            Spacer(Modifier.height(4.dp))
            ConfigInfoRow(
                stringResource(R.string.settings_webdav_address),
                AppPreferences.WebDav.url.ifBlank { stringResource(R.string.settings_not_configured) },
            )
            ConfigInfoRow(
                stringResource(R.string.settings_webdav_username),
                AppPreferences.WebDav.username.ifBlank { stringResource(R.string.settings_not_configured) },
            )
            ConfigInfoRow(
                stringResource(R.string.settings_webdav_auth_mode),
                when (AppPreferences.WebDav.authMode) {
                    1 -> "Basic"
                    2 -> "Digest"
                    else -> stringResource(R.string.settings_auto)
                },
            )
        }
    }
}

@Composable
private fun ConfigInfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontFamily = appFontFamily(),
        )
        Text(
            value,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onBackground,
            fontFamily = appFontFamily(),
        )
    }
}
