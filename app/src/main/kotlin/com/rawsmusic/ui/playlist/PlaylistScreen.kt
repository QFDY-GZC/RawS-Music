package com.rawsmusic.ui.playlist

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.pages.LibraryListScaffold
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.ui.settings.appFontFamily
import com.rawsmusic.ui.settings.themeColors
import kotlinx.coroutines.launch

@Composable
fun PlaylistScreen(
    playlistStore: PlaylistStore,
    onBack: () -> Unit,
    onPlaylistClick: (String, String) -> Unit
) {
    val context = LocalContext.current
    val playlists by playlistStore.playlists.collectAsState()
    val librarySongs by MusicRepository.songs.collectAsState()
    val scope = rememberCoroutineScope()
    val colors = themeColors()
    var createDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<UserPlaylist?>(null) }
    var deleteTarget by remember { mutableStateOf<UserPlaylist?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = playlistStore.importTextPlaylist(uri, librarySongs)
            val message = if (result.importedCount > 0) {
                context.getString(R.string.playlist_import_success, result.importedCount, result.missingCount)
            } else {
                context.getString(R.string.playlist_import_empty)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    LibraryListScaffold(
        title = stringResource(com.rawsmusic.core.ui.R.string.library_title_playlists),
        sceneId = NavScene.PLAYLISTS.name,
        onBack = onBack,
        onCreatePlaylist = { createDialog = true },
        onImportPlaylist = { importLauncher.launch(arrayOf("text/plain", "audio/x-mpegurl", "application/octet-stream")) },
        contentOverlap = 0.dp
    ) { topPadding, backdropSource ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(backdropSource),
            contentPadding = PaddingValues(
                top = topPadding + 8.dp,
                start = 16.dp,
                end = 16.dp,
                bottom = 150.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(playlists, key = { it.id }) { playlist ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onPlaylistClick(playlist.id, playlist.name) },
                            onLongClick = { if (!playlist.isFavorites) renameTarget = playlist }
                        ),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.86f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(
                                if (playlist.isFavorites) R.drawable.ic_heart_fill else R.drawable.ic_music_note
                            ),
                            contentDescription = null,
                            tint = if (playlist.isFavorites) Color(0xFFEF476F) else colors.primary,
                            modifier = Modifier.size(25.dp)
                        )
                        Spacer(Modifier.width(13.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                playlist.name,
                                color = colors.onSurface,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = appFontFamily()
                            )
                            Text(
                                stringResource(R.string.playlist_song_count, playlist.songs.size),
                                color = colors.onSurface.copy(alpha = 0.52f),
                                fontSize = 13.sp,
                                fontFamily = appFontFamily()
                            )
                        }
                        if (!playlist.isFavorites) {
                            IconButton(onClick = { deleteTarget = playlist }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete_bin_6_fill),
                                    contentDescription = stringResource(R.string.playlist_delete_action),
                                    tint = colors.onSurface.copy(alpha = 0.42f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (createDialog) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_create),
            initialName = "",
            onDismiss = { createDialog = false },
            onConfirm = { name ->
                scope.launch {
                    val created = playlistStore.createPlaylist(name)
                    if (created == null) Toast.makeText(context, R.string.playlist_name_exists, Toast.LENGTH_SHORT).show()
                }
                createDialog = false
            }
        )
    }

    renameTarget?.let { playlist ->
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_rename),
            initialName = playlist.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                scope.launch {
                    if (!playlistStore.renamePlaylist(playlist.id, name)) {
                        Toast.makeText(context, R.string.playlist_name_exists, Toast.LENGTH_SHORT).show()
                    }
                }
                renameTarget = null
            }
        )
    }

    deleteTarget?.let { playlist ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.playlist_delete_title), fontFamily = appFontFamily()) },
            text = { Text(stringResource(R.string.playlist_delete_message), fontFamily = appFontFamily()) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { playlistStore.deletePlaylist(playlist.id) }
                    deleteTarget = null
                }) { Text(stringResource(R.string.playlist_delete_action), color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.playlist_cancel_action))
                }
            }
        )
    }
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val colors = themeColors()
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontFamily = appFontFamily()) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.playlist_name_hint)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.primary,
                    cursorColor = colors.primary
                )
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) },
                colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)
            ) { Text(stringResource(R.string.playlist_confirm_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.playlist_cancel_action)) }
        }
    )
}
