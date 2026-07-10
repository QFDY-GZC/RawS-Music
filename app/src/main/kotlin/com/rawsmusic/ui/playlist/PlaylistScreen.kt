package com.rawsmusic.ui.playlist
import com.rawsmusic.R

import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.ui.settings.SettingsPage
import com.rawsmusic.ui.settings.appFontFamily
import com.rawsmusic.ui.settings.themeColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    playlistStore: PlaylistStore,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onPlaylistClick: (String, String) -> Unit
) {
    val playlists by playlistStore.playlists.collectAsState()
    val scope = rememberCoroutineScope()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }

    val colors = themeColors()

    SettingsPage(title = "歌单", onBack = onBack) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { onImport() }) {
                Text("导入歌单", color = colors.primary, fontFamily = appFontFamily())
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { showCreateDialog = true }) {
                Text("新建歌单", color = colors.primary, fontFamily = appFontFamily())
            }
        }

        playlists.forEach { playlist ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onPlaylistClick(playlist.id, playlist.name) },
                colors = CardDefaults.cardColors(containerColor = colors.surface)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(
                            if (playlist.isFavorites) R.drawable.ic_heart_fill
                            else R.drawable.ic_music_note
                        ),
                        contentDescription = null,
                        tint = if (playlist.isFavorites) Color(0xFFEF476F)
                        else colors.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            playlist.name,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = appFontFamily()
                        )
                        Text(
                            "${playlist.songs.size} 首",
                            fontSize = 13.sp,
                            color = colors.onSurface.copy(alpha = 0.5f),
                            fontFamily = appFontFamily()
                        )
                    }
                    if (!playlist.isFavorites) {
                        IconButton(onClick = { showDeleteDialog = playlist.id }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete_bin_6_fill),
                                contentDescription = "删除",
                                tint = colors.onSurface.copy(alpha = 0.4f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(80.dp))
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新建歌单", fontFamily = appFontFamily()) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("歌单名称", fontFamily = appFontFamily()) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                        cursorColor = colors.primary
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (name.isNotBlank()) {
                            scope.launch { playlistStore.createPlaylist(name) }
                            showCreateDialog = false
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)
                ) { Text("创建", fontFamily = appFontFamily()) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("取消", fontFamily = appFontFamily()) }
            }
        )
    }

    showDeleteDialog?.let { playlistId ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除歌单", fontFamily = appFontFamily()) },
            text = { Text("确定要删除这个歌单吗？此操作不可恢复。", fontFamily = appFontFamily()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { playlistStore.deletePlaylist(playlistId) }
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) { Text("删除", fontFamily = appFontFamily()) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("取消", fontFamily = appFontFamily()) }
            }
        )
    }
}
