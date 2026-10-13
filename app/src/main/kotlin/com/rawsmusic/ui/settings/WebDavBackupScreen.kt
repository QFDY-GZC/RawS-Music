package com.rawsmusic.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.locale.localizedErrorMessage
import com.rawsmusic.module.data.prefs.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun WebDavBackupScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { BackupUiController() }
    val ui by controller.state.collectAsState()
    var exportSelection by remember { mutableStateOf(BackupSelection()) }
    var message by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    var previewError by remember { mutableStateOf("") }
    var resolving by remember { mutableStateOf(false) }
    val configured = AppPreferences.WebDav.url.isNotBlank()

    fun label(section: BackupSection): String = context.getString(when (section) {
        BackupSection.PREFERENCES -> R.string.backup_basic_preferences
        BackupSection.PLAYLISTS -> R.string.backup_playlist_paths
        BackupSection.PLAYBACK -> R.string.backup_listening_records
        BackupSection.DEVICE_REFERENCES -> R.string.backup_device_references
    })
    fun failed(error: Throwable) {
        message = context.localizedErrorMessage(error, R.string.ui_unknown_error)
    }
    fun back() {
        when (ui.phase) {
            BackupPhase.PREVIEW -> controller.cancel()
            BackupPhase.IDLE -> onBack()
            else -> Unit // Never leave the page halfway through persistence.
        }
    }
    BackHandler(enabled = ui.busy) { back() }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) { controller.finish(); return@rememberLauncherForActivityResult }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bytes = BackupArchiveManager.exportSelected(context, exportSelection)
                    context.contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(bytes); it.flush()
                    } ?: error(context.getString(R.string.settings_backup_write_failed))
                }
                message = context.getString(R.string.settings_backup_export_success)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failed(error) }
            finally { controller.finish() }
        }
    }
    // Import reads the chosen file immediately; it does not retain a document grant.
    // Use the system content picker and leave storage roots unfiltered. Providers
    // can report JSON as text/plain or an unknown type; the archive codec validates it.
    val open = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) { controller.finish(); return@rememberLauncherForActivityResult }
        scope.launch {
            try {
                val archive = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { BackupBoundedReader.read(it) }
                        ?: error(context.getString(R.string.settings_backup_read_failed))
                    BackupArchiveCodec.decode(bytes)
                }
                controller.loaded(archive) // No writes before explicit confirmation.
            } catch (cancelled: CancellationException) { controller.finish(); throw cancelled }
            catch (error: Exception) { controller.finish(); failed(error) }
        }
    }

    LaunchedEffect(ui.archive, ui.selection) {
        val archive = ui.archive ?: return@LaunchedEffect
        preview = null
        previewError = ""
        resolving = true
        try {
            preview = withContext(Dispatchers.IO) {
                val resolved = BackupArchiveManager.resolve(context, archive, ui.selection)
                val access = AndroidBackupPreferenceAccess(context.applicationContext)
                val existing = resolved.backup.preferences.mapNotNull { entry ->
                    val rule = BackupPreferenceRegistry.classify(entry.address) ?: return@mapNotNull null
                    entry.address.takeIf { access.read(it, rule.type) != null }
                }.toSet()
                BackupRestorePreview.build(resolved, ui.selection, existing)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { previewError = context.localizedErrorMessage(error, R.string.ui_unknown_error) }
        finally { resolving = false }
    }

    SettingsPage(title = stringResource(R.string.settings_webdav_backup_title), onBack = { back() }) {
        SettingsCard {
            Text(stringResource(R.string.backup_scope_title), color = MiuixTheme.colorScheme.onBackground, fontFamily = appFontFamily(), fontSize = 18.sp)
            Text(stringResource(R.string.backup_plaintext_notice), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontFamily = appFontFamily())
            BackupSection.entries.forEach { section ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label(section), Modifier.weight(1f).padding(vertical = 12.dp), color = MiuixTheme.colorScheme.onBackground, fontFamily = appFontFamily())
                    Checkbox(section in exportSelection.sections, enabled = !ui.busy, onCheckedChange = { enabled ->
                        exportSelection = BackupSelection(if (enabled) exportSelection.sections + section else exportSelection.sections - section)
                    })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        SettingsCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(enabled = !ui.busy && exportSelection.sections.isNotEmpty(), onClick = {
                    if (controller.begin(BackupPhase.EXPORTING)) {
                        message = ""
                        try { save.launch("rawsmusic_backup_${System.currentTimeMillis()}.json") }
                        catch (error: Exception) { controller.finish(); failed(error) }
                    }
                }) { BackupActionText(stringResource(R.string.settings_backup_export_action)) }
                TextButton(enabled = !ui.busy, onClick = {
                    if (controller.begin(BackupPhase.PICKING)) {
                        message = ""
                        try { open.launch("*/*") }
                        catch (error: Exception) { controller.finish(); failed(error) }
                    }
                }) { BackupActionText(stringResource(R.string.settings_backup_import_action)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(enabled = configured && !ui.busy && exportSelection.sections.isNotEmpty(), onClick = {
                    if (controller.begin(BackupPhase.EXPORTING)) {
                        message = ""
                        scope.launch {
                            try {
                                WebDavBackupManager.backupSelected(context, exportSelection).getOrThrow()
                                message = context.getString(R.string.settings_webdav_backup_success)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { failed(error) }
                            finally { controller.finish() }
                        }
                    }
                }) { BackupActionText(stringResource(R.string.settings_webdav_backup_action)) }
                TextButton(enabled = configured && !ui.busy, onClick = {
                    if (controller.begin(BackupPhase.LOADING)) {
                        message = ""
                        scope.launch {
                            try { controller.loaded(WebDavBackupManager.loadLatest()) }
                            catch (cancelled: CancellationException) { controller.finish(); throw cancelled }
                            catch (error: Exception) { controller.finish(); failed(error) }
                        }
                    }
                }) { BackupActionText(stringResource(R.string.settings_webdav_restore_action)) }
            }
            if (!configured) Text(stringResource(R.string.settings_webdav_not_configured), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontFamily = appFontFamily())
            if (ui.busy && ui.phase != BackupPhase.PREVIEW) CircularProgressIndicator(Modifier.size(24.dp), color = MiuixTheme.colorScheme.primary)
        }

        if (ui.phase == BackupPhase.PREVIEW) {
            Spacer(Modifier.height(12.dp))
            SettingsCard {
                Text(stringResource(R.string.backup_preview_title), color = MiuixTheme.colorScheme.onBackground, fontSize = 18.sp, fontFamily = appFontFamily())
                Text(stringResource(R.string.backup_replace_notice), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontFamily = appFontFamily())
                ui.archive!!.sections.forEach { section ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label(section), Modifier.weight(1f).padding(vertical = 12.dp), color = MiuixTheme.colorScheme.onBackground, fontFamily = appFontFamily())
                        Checkbox(section in ui.selection.sections, onCheckedChange = { controller.select(section, it) })
                    }
                }
                if (resolving) CircularProgressIndicator(Modifier.size(24.dp), color = MiuixTheme.colorScheme.primary)
                preview?.let { p ->
                    Text(stringResource(R.string.backup_preview_preferences, p.newPreferences, p.overwrittenPreferences), color = MiuixTheme.colorScheme.onBackground, fontFamily = appFontFamily())
                    p.playlists?.let { Text(stringResource(R.string.backup_preview_playlists, it, p.songs ?: 0), color = MiuixTheme.colorScheme.onBackground, fontFamily = appFontFamily()) }
                    p.stats?.let { Text(stringResource(R.string.backup_preview_playback, it, p.history ?: 0, p.daily ?: 0), color = MiuixTheme.colorScheme.onBackground, fontFamily = appFontFamily()) }
                    if (p.clearsPlaylists || p.clearsPlayback) Text(stringResource(R.string.backup_clear_warning), color = MiuixTheme.colorScheme.primary, fontFamily = appFontFamily())
                    p.skipped.take(30).forEach { Text(it, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontFamily = appFontFamily()) }
                    if (p.skipped.size > 30) Text(stringResource(R.string.backup_more_skipped, p.skipped.size - 30), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontFamily = appFontFamily())
                }
                if (previewError.isNotBlank()) Text(previewError, color = MiuixTheme.colorScheme.primary, fontFamily = appFontFamily())
                Row {
                    TextButton(onClick = { controller.cancel() }) { BackupActionText(stringResource(android.R.string.cancel)) }
                    TextButton(enabled = !resolving && preview != null && previewError.isBlank() && ui.selection.sections.isNotEmpty(), onClick = {
                        val request = controller.confirm() ?: return@TextButton
                        scope.launch {
                            try {
                                val report = BackupArchiveManager.restoreSelected(context, request.archive, request.selection)
                                message = buildList {
                                    add(context.getString(R.string.backup_restore_done, report.restoredMmkv + report.restoredSharedPreferences))
                                    addAll(report.skippedReasons)
                                    addAll(report.warnings)
                                    if (report.requiresRestart) add(context.getString(R.string.backup_restart_notice))
                                }.joinToString("\n")
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { failed(error) }
                            finally { controller.finish() }
                        }
                    }) { BackupActionText(stringResource(R.string.backup_confirm_restore)) }
                }
            }
        }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            SettingsCard { Text(message, color = MiuixTheme.colorScheme.onBackground, fontFamily = appFontFamily(), fontSize = 14.sp) }
        }
    }
}

@Composable
private fun BackupActionText(text: String) {
    Text(text, color = MiuixTheme.colorScheme.primary, fontFamily = appFontFamily(), fontSize = 15.sp)
}
