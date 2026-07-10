package com.rawsmusic.ui.folderfilter

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rawsmusic.R
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.scanner.ScanScheduler
import com.rawsmusic.module.scanner.SafUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

private const val TAG = "MusicFoldersDialog"

@Composable
fun MusicFoldersDialog(
    onDismiss: () -> Unit,
    onFolderPickerLauncher: () -> Unit,
    pendingFolderUri: Uri? = null,
    onFolderUriConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var roots by remember { mutableStateOf<List<FolderPickerNode>>(emptyList()) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableLongStateOf(0L) }

    val visibleNodes by remember(roots, refreshTick) {
        derivedStateOf {
            roots.flatMap { it.flattenVisible() }
        }
    }

    val normalizedSelected by remember(selectedPaths) {
        derivedStateOf {
            minimizeSelectedPaths(selectedPaths)
        }
    }

    fun updateNode(
        path: String,
        transform: (FolderPickerNode) -> FolderPickerNode
    ) {
        roots = roots.updateNode(path, transform)
        refreshTick++
    }

    fun toggleSelection(path: String) {
        val normalized = normalizePath(path)
        val state = checkStateForPath(normalized, selectedPaths)

        selectedPaths = when (state) {
            FolderCheckState.Checked -> removePathSelection(selectedPaths, normalized)
            FolderCheckState.Partial,
            FolderCheckState.Unchecked -> addPathSelection(selectedPaths, normalized)
        }
    }

    fun loadChildren(node: FolderPickerNode) {
        if (!node.canExpand || node.loading) return

        if (node.childrenLoaded) {
            updateNode(node.path) {
                it.copy(expanded = !it.expanded)
            }
            return
        }

        updateNode(node.path) {
            it.copy(
                loading = true,
                expanded = true
            )
        }

        scope.launch {
            val children = withContext(Dispatchers.IO) {
                loadChildFolders(
                    parent = node,
                    selectedPaths = selectedPaths
                )
            }

            updateNode(node.path) {
                it.copy(
                    loading = false,
                    expanded = true,
                    childrenLoaded = true,
                    children = children,
                    hasChildren = children.isNotEmpty()
                )
            }
        }
    }

    fun addLocalPath(path: String) {
        val normalized = normalizePath(path)
        val file = File(normalized)

        if (!file.exists() || !file.isDirectory) {
            Toast.makeText(context, context.getString(R.string.folder_filter_folder_unavailable), Toast.LENGTH_SHORT).show()
            return
        }

        val exists = roots.any { root ->
            root.path == normalized || normalized.startsWith(root.path + "/")
        }

        if (!exists) {
            val node = file.toFolderPickerNode(
                level = 0,
                isStorage = true,
                isRemovable = false,
                isUsb = false
            )

            roots = (roots + node).sortedBy { it.displayName.lowercase() }
        }

        selectedPaths = addPathSelection(selectedPaths, normalized)
        Toast.makeText(context, context.getString(R.string.folder_filter_folder_added, file.name.ifBlank { normalized }), Toast.LENGTH_SHORT).show()
    }

    fun handleFolderUri(uri: Uri?) {
        uri ?: return

        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "takePersistableUriPermission failed", e)
        }

        val realPath = extractRealPathFromUri(context, uri)
            ?: SafUtils.uriToPath(uri)

        if (realPath == null) {
            Toast.makeText(
                context,
                context.getString(R.string.folder_filter_saf_unsupported),
                Toast.LENGTH_LONG
            ).show()
            AppLogger.w(TAG, "Unsupported SAF uri: $uri")
            return
        }

        addLocalPath(realPath)
    }

    fun saveAndScan() {
        val pathsToSave = normalizedSelected.toList()

        if (pathsToSave.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.folder_filter_select_at_least_one), Toast.LENGTH_SHORT).show()
            return
        }

        saving = true

        AppPreferences.UI.scanPaths = pathsToSave
        AppPreferences.UI.rootScanPaths = roots.map { it.path }

        Toast.makeText(
            context,
            context.getString(R.string.folder_filter_saved_start_scan, pathsToSave.size),
            Toast.LENGTH_SHORT
        ).show()

        onDismiss()
        ScanScheduler.requestDirScan(context, "folders selected")
    }

    LaunchedEffect(Unit) {
        loading = true
        loadError = null

        runCatching {
            withContext(Dispatchers.IO) {
                buildInitialRoots(context)
            }
        }.onSuccess { initialRoots ->
            val savedPaths = AppPreferences.UI.scanPaths
                .map(::normalizePath)
                .filter { it.isNotBlank() }
                .toSet()

            selectedPaths = minimizeSelectedPaths(savedPaths).toSet()

            val savedRootPaths = AppPreferences.UI.rootScanPaths
                .map(::normalizePath)
                .filter { it.isNotBlank() }

            val promotedRoots = savedRootPaths
                .mapNotNull { path ->
                    val file = File(path)
                    if (file.exists() && file.isDirectory) {
                        file.toFolderPickerNode(
                            level = 0,
                            isStorage = true,
                            isRemovable = false,
                            isUsb = false
                        )
                    } else {
                        null
                    }
                }

            val mergedRoots = (initialRoots + promotedRoots)
                .distinctBy { it.path }
                .sortedWith(
                    compareByDescending<FolderPickerNode> { it.path == Environment.getExternalStorageDirectory().absolutePath }
                        .thenBy { it.displayName.lowercase() }
                )

            roots = mergedRoots
            loading = false
        }.onFailure { e ->
            loading = false
            loadError = e.message ?: context.getString(R.string.folder_filter_load_failed)
            AppLogger.e(TAG, "load roots failed", e)
        }
    }

    LaunchedEffect(pendingFolderUri) {
        if (pendingFolderUri != null) {
            handleFolderUri(pendingFolderUri)
            onFolderUriConsumed()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(min = 420.dp, max = 680.dp),
            shape = RoundedCornerShape(28.dp),
            color = MiuixTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = stringResource(R.string.folder_filter_title),
                color = MiuixTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.folder_filter_summary),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(16.dp))

            FolderPickerHeader(
                selectedCount = normalizedSelected.size,
                rootCount = roots.size,
                loading = loading
            )

            Spacer(Modifier.height(12.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(22.dp),
                color = MiuixTheme.colorScheme.surface.copy(alpha = 0.62f)
            ) {
                when {
                    loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp
                            )
                        }
                    }

                    loadError != null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = loadError ?: stringResource(R.string.folder_filter_load_failed),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    roots.isEmpty() -> {
                        EmptyFolderState(
                            onAddFolder = onFolderPickerLauncher
                        )
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(
                                items = visibleNodes,
                                key = { it.path }
                            ) { item ->
                                val checkState = checkStateForPath(
                                    path = item.path,
                                    selectedPaths = selectedPaths
                                )

                                FolderPickerRow(
                                    node = item,
                                    checkState = checkState,
                                    onToggleExpand = { loadChildren(item) },
                                    onToggleCheck = { toggleSelection(item.path) }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            FolderPickerActions(
                canSave = normalizedSelected.isNotEmpty() && !loading && !saving,
                onAddFolder = onFolderPickerLauncher,
                onClear = {
                    selectedPaths = emptySet()
                },
                onCancel = onDismiss,
                onSaveAndScan = { saveAndScan() }
            )
        }
        }
    }
}

@Composable
private fun FolderPickerHeader(
    selectedCount: Int,
    rootCount: Int,
    loading: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InfoChip(text = stringResource(R.string.folder_filter_selected_count, selectedCount))
            InfoChip(text = stringResource(R.string.folder_filter_root_count, rootCount))
            if (loading) {
                InfoChip(text = stringResource(R.string.folder_filter_loading))
            }
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = MiuixTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}

@Composable
private fun EmptyFolderState(
    onAddFolder: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.folder_filter_empty),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(14.dp))

        Button(
            onClick = onAddFolder,
            colors = ButtonDefaults.buttonColorsPrimary()
        ) {
            Text(stringResource(R.string.folder_filter_add_folder))
        }
    }
}

@Composable
private fun FolderPickerRow(
    node: FolderPickerNode,
    checkState: FolderCheckState,
    onToggleExpand: () -> Unit,
    onToggleCheck: () -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (node.expanded) 90f else 0f,
        label = "folder_arrow_rotation"
    )

    val alpha = if (node.canRead) 1f else 0.45f
    val startPadding = 10.dp + (node.level * 18).dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .clickable(enabled = node.canRead) {
                if (node.canExpand) onToggleExpand()
            }
            .padding(
                start = startPadding,
                end = 10.dp,
                top = 7.dp,
                bottom = 7.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clickable(enabled = node.canExpand && node.canRead) {
                    onToggleExpand()
                },
            contentAlignment = Alignment.Center
        ) {
            if (node.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            } else if (node.canExpand) {
                Text(
                    text = "›",
                    modifier = Modifier.rotate(arrowRotation),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        FolderGlyph(
            isStorage = node.isStorage,
            isUsb = node.isUsb,
            isRemovable = node.isRemovable
        )

        Spacer(Modifier.width(10.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = node.displayName,
                color = MiuixTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (node.isStorage) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = node.subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(10.dp))

        TriStateMiuixCheckbox(
            state = checkState,
            enabled = node.canRead,
            onClick = onToggleCheck
        )
    }
}

@Composable
private fun FolderGlyph(
    isStorage: Boolean,
    isUsb: Boolean,
    isRemovable: Boolean
) {
    val text = when {
        isUsb -> "U"
        isRemovable -> "S"
        isStorage -> "M"
        else -> "F"
    }

    val color = when {
        isUsb -> Color(0xFF74A8FF)
        isRemovable -> Color(0xFF9E8CFF)
        isStorage -> MiuixTheme.colorScheme.primary
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

    Box(
        modifier = Modifier
            .size(32.dp)
            .background(color.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun TriStateMiuixCheckbox(
    state: FolderCheckState,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        val toggleState = when (state) {
            FolderCheckState.Checked -> androidx.compose.ui.state.ToggleableState.On
            FolderCheckState.Partial -> androidx.compose.ui.state.ToggleableState.Indeterminate
            FolderCheckState.Unchecked -> androidx.compose.ui.state.ToggleableState.Off
        }
        Checkbox(
            state = toggleState,
            onClick = { onClick() },
            enabled = enabled
        )

        AnimatedVisibility(
            visible = state == FolderCheckState.Partial
        ) {
            Box(
                modifier = Modifier
                    .size(width = 14.dp, height = 3.dp)
                    .background(
                        color = MiuixTheme.colorScheme.primary,
                        shape = RoundedCornerShape(999.dp)
                    )
            )
        }
    }
}

@Composable
private fun FolderPickerActions(
    canSave: Boolean,
    onAddFolder: () -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onSaveAndScan: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TextButton(
                text = stringResource(R.string.folder_filter_add_folder),
                onClick = onAddFolder,
                modifier = Modifier.weight(1f)
            )

            TextButton(
                text = stringResource(R.string.folder_filter_clear),
                onClick = onClear,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TextButton(
                text = stringResource(R.string.folder_filter_cancel),
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            )

            Button(
                onClick = onSaveAndScan,
                enabled = canSave,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 46.dp)
            ) {
                Text(stringResource(R.string.folder_filter_save_and_scan))
            }
        }
    }
}

@Stable
private data class FolderPickerNode(
    val path: String,
    val displayName: String,
    val subtitle: String,
    val level: Int,
    val isStorage: Boolean,
    val isRemovable: Boolean,
    val isUsb: Boolean,
    val canRead: Boolean,
    val canExpand: Boolean,
    val hasChildren: Boolean,
    val childrenLoaded: Boolean = false,
    val expanded: Boolean = false,
    val loading: Boolean = false,
    val children: List<FolderPickerNode> = emptyList()
)

private enum class FolderCheckState {
    Unchecked,
    Partial,
    Checked
}

private fun FolderPickerNode.flattenVisible(): List<FolderPickerNode> {
    val result = mutableListOf<FolderPickerNode>()
    result += this

    if (expanded) {
        for (child in children) {
            result += child.flattenVisible()
        }
    }

    return result
}

private fun List<FolderPickerNode>.updateNode(
    path: String,
    transform: (FolderPickerNode) -> FolderPickerNode
): List<FolderPickerNode> {
    return map { node ->
        when {
            node.path == path -> transform(node)
            node.children.isNotEmpty() -> node.copy(
                children = node.children.updateNode(path, transform)
            )
            else -> node
        }
    }
}

private suspend fun buildInitialRoots(
    context: Context
): List<FolderPickerNode> {
    val roots = mutableListOf<FolderPickerNode>()

    val internal = Environment.getExternalStorageDirectory()
    if (internal.exists()) {
        roots += internal.toFolderPickerNode(
            level = 0,
            isStorage = true,
            isRemovable = false,
            isUsb = false,
            displayNameOverride = context.getString(R.string.folder_filter_internal_storage)
        )
    }

    val mountedVolumes = SafUtils.getMountedVolumes(context)

    for (volume in mountedVolumes) {
        val path = normalizePath(volume.path)

        if (path == normalizePath(internal.absolutePath)) {
            continue
        }

        val file = File(path)
        if (!file.exists() || !file.isDirectory) {
            continue
        }

        val isUsb = path.contains("usb", ignoreCase = true)
        val displayName = when {
            isUsb -> context.getString(R.string.folder_filter_usb_storage)
            volume.isRemovable -> context.getString(R.string.folder_filter_sd_card)
            else -> file.name.ifBlank { path }
        }

        roots += file.toFolderPickerNode(
            level = 0,
            isStorage = true,
            isRemovable = volume.isRemovable,
            isUsb = isUsb,
            displayNameOverride = displayName
        )
    }

    return roots
        .distinctBy { it.path }
        .sortedWith(
            compareByDescending<FolderPickerNode> {
                it.path == normalizePath(internal.absolutePath)
            }.thenBy {
                it.displayName.lowercase()
            }
        )
}

private fun loadChildFolders(
    parent: FolderPickerNode,
    selectedPaths: Set<String>
): List<FolderPickerNode> {
    val parentFile = File(parent.path)
    val files = try {
        parentFile.listFiles()
    } catch (e: Exception) {
        AppLogger.w(TAG, "list children failed: ${parent.path}", e)
        null
    } ?: return emptyList()

    return files
        .asSequence()
        .filter { it.isDirectory }
        .filter { !it.name.startsWith(".") && !it.name.startsWith("_") }
        .map { child ->
            child.toFolderPickerNode(
                level = parent.level + 1,
                isStorage = false,
                isRemovable = parent.isRemovable,
                isUsb = parent.isUsb
            )
        }
        .sortedWith(
            compareByDescending<FolderPickerNode> {
                selectedPaths.any { selected ->
                    selected == it.path || selected.startsWith(it.path + "/")
                }
            }.thenBy {
                it.displayName.lowercase()
            }
        )
        .toList()
}

private fun File.toFolderPickerNode(
    level: Int,
    isStorage: Boolean,
    isRemovable: Boolean,
    isUsb: Boolean,
    displayNameOverride: String? = null
): FolderPickerNode {
    val normalized = normalizePath(absolutePath)
    val canReadDir = canRead()
    val hasVisibleChildren = hasVisibleChildDirectory()

    return FolderPickerNode(
        path = normalized,
        displayName = displayNameOverride ?: name.ifBlank { normalized },
        subtitle = normalized,
        level = level,
        isStorage = isStorage,
        isRemovable = isRemovable,
        isUsb = isUsb,
        canRead = canReadDir,
        canExpand = canReadDir && hasVisibleChildren,
        hasChildren = hasVisibleChildren
    )
}

private fun File.hasVisibleChildDirectory(): Boolean {
    return try {
        listFiles()?.any { it.isDirectory && !it.name.startsWith(".") && !it.name.startsWith("_") } == true
    } catch (_: Exception) {
        false
    }
}

private fun checkStateForPath(
    path: String,
    selectedPaths: Set<String>
): FolderCheckState {
    val normalized = normalizePath(path)

    if (selectedPaths.any { selected ->
            selected == normalized || normalized.startsWith(selected + "/")
        }
    ) {
        return FolderCheckState.Checked
    }

    if (selectedPaths.any { selected ->
            selected.startsWith(normalized + "/")
        }
    ) {
        return FolderCheckState.Partial
    }

    return FolderCheckState.Unchecked
}

private fun addPathSelection(
    current: Set<String>,
    path: String
): Set<String> {
    val normalized = normalizePath(path)

    if (current.any { selected ->
            selected == normalized || normalized.startsWith(selected + "/")
        }
    ) {
        return minimizeSelectedPaths(current).toSet()
    }

    val result = current
        .filterNot { selected ->
            selected.startsWith(normalized + "/")
        }
        .toMutableSet()

    result += normalized

    return minimizeSelectedPaths(result).toSet()
}

private fun removePathSelection(
    current: Set<String>,
    path: String
): Set<String> {
    val normalized = normalizePath(path)

    val ancestor = current
        .sortedByDescending { it.length }
        .firstOrNull { selected ->
            normalized.startsWith(selected + "/")
        }

    if (ancestor != null) {
        return current - ancestor
    }

    return current
        .filterNot { selected ->
            selected == normalized || selected.startsWith(normalized + "/")
        }
        .toSet()
}

private fun minimizeSelectedPaths(
    paths: Collection<String>
): List<String> {
    val sorted = paths
        .map(::normalizePath)
        .filter { it.isNotBlank() }
        .distinct()
        .sortedBy { it.length }

    val result = mutableListOf<String>()

    for (path in sorted) {
        val covered = result.any { parent ->
            path == parent || path.startsWith(parent + "/")
        }

        if (!covered) {
            result += path
        }
    }

    return result
}

private fun normalizePath(path: String): String {
    return path
        .replace('\\', '/')
        .trim()
        .trimEnd('/')
}

private fun extractRealPathFromUri(
    context: Context,
    uri: Uri
): String? {
    return try {
        val authority = uri.authority ?: ""
        val path = uri.path ?: ""

        val docId: String? = when {
            DocumentsContract.isDocumentUri(context, uri) -> {
                runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            }

            path.startsWith("/tree/") -> {
                Uri.decode(path.removePrefix("/tree/"))
            }

            else -> null
        }

        if (docId.isNullOrBlank()) return null

        when {
            authority == "com.android.externalstorage.documents" -> {
                documentIdToStoragePath(context, docId)
            }

            docId.startsWith("raw:") -> {
                docId.removePrefix("raw:")
            }

            else -> null
        }
    } catch (e: Exception) {
        AppLogger.e(TAG, "extractRealPathFromUri failed", e)
        null
    }
}

private fun documentIdToStoragePath(
    context: Context,
    documentId: String
): String? {
    val parts = documentId.split(":", limit = 2)
    val volume = parts.getOrNull(0) ?: return null
    val subPath = parts.getOrNull(1).orEmpty()

    val basePath = if (volume.equals("primary", ignoreCase = true)) {
        Environment.getExternalStorageDirectory().absolutePath
    } else {
        SafUtils.getMountedVolumes(context)
            .firstOrNull { it.uuid.equals(volume, ignoreCase = true) }
            ?.path
            ?: "/storage/$volume"
    }

    return if (subPath.isBlank()) {
        basePath
    } else {
        "$basePath/$subPath"
    }
}