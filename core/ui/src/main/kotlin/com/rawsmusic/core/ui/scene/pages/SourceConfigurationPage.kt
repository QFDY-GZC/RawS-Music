package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import com.rawsmusic.module.data.source.InstalledLxSource
import com.rawsmusic.module.data.source.InstalledMusicSource
import com.rawsmusic.module.data.source.MusicSourceOrigin
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SourceConfigurationPage(
    installedMusicFreeSources: List<InstalledMusicSource>,
    installedLxSources: List<InstalledLxSource>,
    importBusy: Boolean,
    statusMessage: String,
    onBack: () -> Unit,
    onImportMusicFreeFile: () -> Unit,
    onImportMusicFreeUrl: () -> Unit,
    onImportLxFile: () -> Unit,
    onImportLxUrl: () -> Unit,
    onToggleMusicFree: (String, Boolean) -> Unit,
    onToggleLx: (String, Boolean) -> Unit,
    onDeleteMusicFree: (InstalledMusicSource) -> Unit,
    onDeleteLx: (InstalledLxSource) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize()) {
        SourceSectionTopBar(stringResource(R.string.source_config_title), onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 174.dp),
        ) {
            SourceProtocolHeader(
                title = stringResource(R.string.source_musicfree_title),
                summary = stringResource(R.string.source_musicfree_summary),
                count = installedMusicFreeSources.size,
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                SourceImportMethodCard(
                    iconRes = R.drawable.ic_playlist_import,
                    title = stringResource(R.string.source_local_js),
                    summary = stringResource(R.string.source_musicfree_plugin),
                    actionLabel = if (importBusy) stringResource(R.string.source_processing) else stringResource(R.string.source_select),
                    enabled = !importBusy,
                    onClick = onImportMusicFreeFile,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                SourceImportMethodCard(
                    iconRes = R.drawable.ic_cloud,
                    title = stringResource(R.string.source_url_import),
                    summary = stringResource(R.string.source_remote_musicfree_plugin),
                    actionLabel = if (importBusy) stringResource(R.string.source_processing) else stringResource(R.string.source_enter),
                    enabled = !importBusy,
                    onClick = onImportMusicFreeUrl,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            if (installedMusicFreeSources.isEmpty()) {
                SourceEmptyPanel(stringResource(R.string.source_empty_musicfree), stringResource(R.string.source_empty_musicfree_summary))
            } else {
                installedMusicFreeSources.forEach { source ->
                    InstalledMusicFreeSourceCard(
                        source = source,
                        onToggle = { onToggleMusicFree(source.id, it) },
                        onDelete = { onDeleteMusicFree(source) },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }

            Spacer(Modifier.height(24.dp))
            SourceProtocolHeader(
                title = stringResource(R.string.source_lx_title),
                summary = stringResource(R.string.source_lx_summary),
                count = installedLxSources.size,
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                SourceImportMethodCard(
                    iconRes = R.drawable.ic_playlist_import,
                    title = stringResource(R.string.source_local_js),
                    summary = stringResource(R.string.source_lx_api),
                    actionLabel = if (importBusy) stringResource(R.string.source_processing) else stringResource(R.string.source_select),
                    enabled = !importBusy,
                    onClick = onImportLxFile,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                SourceImportMethodCard(
                    iconRes = R.drawable.ic_cloud,
                    title = stringResource(R.string.source_url_import),
                    summary = stringResource(R.string.source_remote_lx_api),
                    actionLabel = if (importBusy) stringResource(R.string.source_processing) else stringResource(R.string.source_enter),
                    enabled = !importBusy,
                    onClick = onImportLxUrl,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            if (installedLxSources.isEmpty()) {
                SourceEmptyPanel(stringResource(R.string.source_empty_lx), stringResource(R.string.source_empty_lx_summary))
            } else {
                installedLxSources.forEach { source ->
                    InstalledLxSourceCard(
                        source = source,
                        onToggle = { onToggleLx(source.id, it) },
                        onDelete = { onDeleteLx(source) },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }

            if (statusMessage.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    statusMessage,
                    color = scheme.onBackground,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(scheme.primary.copy(alpha = 0.10f))
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                )
            }
        }
    }
}

@Composable
private fun SourceProtocolHeader(title: String, summary: String, count: Int) {
    val scheme = MiuixTheme.colorScheme
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = scheme.onBackground, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(summary, color = scheme.onSurfaceVariantSummary, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Text(count.toString(), color = scheme.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SourceImportMethodCard(
    iconRes: Int,
    title: String,
    summary: String,
    actionLabel: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainer.copy(alpha = 0.78f))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.60f)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(scheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(scheme.primary),
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(title, color = scheme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            summary,
            color = scheme.onSurfaceVariantSummary,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        if (enabled) Text(actionLabel, color = scheme.primary, fontSize = 11.sp)
        else CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun InstalledMusicFreeSourceCard(
    source: InstalledMusicSource,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    InstalledSourceCardShell(
        name = source.name,
        versionAuthor = buildString {
            append(source.version.ifBlank { stringResource(R.string.source_undeclared_version) })
            if (source.author.isNotBlank()) append(" · ${source.author}")
        },
        capabilities = source.methods.sorted().joinToString(" · ").ifBlank { stringResource(R.string.source_unrecognized_capabilities) },
        origin = source.origin,
        sha256 = source.scriptSha256,
        enabled = source.enabled,
        lastError = source.lastError,
        onToggle = onToggle,
        onDelete = onDelete,
    )
}

@Composable
private fun InstalledLxSourceCard(
    source: InstalledLxSource,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val platformText = source.platforms.entries
        .sortedBy { it.key }
        .joinToString(" · ") { (platform, qualities) ->
            if (qualities.isEmpty()) platform else "$platform ${qualities.sorted().joinToString("/")}"
        }
        .ifBlank { stringResource(R.string.source_runtime_capabilities) }
    InstalledSourceCardShell(
        name = source.name,
        versionAuthor = buildString {
            append(source.version.ifBlank { stringResource(R.string.source_undeclared_version) })
            if (source.author.isNotBlank()) append(" · ${source.author}")
        },
        capabilities = "${if (source.format == "renderApi") "Render API" else stringResource(R.string.source_lx_api)} · $platformText\n${source.actions.sorted().joinToString(" · ").ifBlank { stringResource(R.string.source_music_url) }}",
        origin = source.origin,
        sha256 = source.scriptSha256,
        enabled = source.enabled,
        lastError = source.lastError,
        onToggle = onToggle,
        onDelete = onDelete,
    )
}

@Composable
private fun InstalledSourceCardShell(
    name: String,
    versionAuthor: String,
    capabilities: String,
    origin: MusicSourceOrigin,
    sha256: String,
    enabled: Boolean,
    lastError: String,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(scheme.surfaceContainer.copy(alpha = 0.74f))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(scheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_music_2_fill),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(scheme.primary),
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(name, color = scheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(versionAuthor, color = scheme.onSurfaceVariantSummary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.source_delete),
                    tint = scheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(capabilities, color = scheme.primary, fontSize = 11.sp, lineHeight = 16.sp)
        Spacer(Modifier.height(5.dp))
        Text(
            "${if (origin == MusicSourceOrigin.LocalFile) stringResource(R.string.source_local_file) else stringResource(R.string.source_remote_url)} · SHA-256 ${sha256.take(12)}…",
            color = scheme.onSurfaceVariantSummary,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (lastError.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(lastError, color = Color(0xFFE15B64), fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
internal fun SourceUrlImportDialog(
    title: String,
    summary: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var url by rememberSaveable(title) { mutableStateOf("") }
    val scheme = MiuixTheme.colorScheme
    RawMiuixOverlayDialog(
        show = true,
        title = title,
        summary = summary,
        backgroundColor = scheme.surface,
        onDismissRequest = if (busy) null else onDismiss,
        renderInRootScaffold = true,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = url,
                onValueChange = { url = it },
                label = "https://example.com/source.js",
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.common_cancel),
                    enabled = !busy,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = if (busy) stringResource(R.string.source_importing_action)
                    else stringResource(R.string.source_import_action),
                    enabled = !busy && url.isNotBlank(),
                    onClick = { onImport(url) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
