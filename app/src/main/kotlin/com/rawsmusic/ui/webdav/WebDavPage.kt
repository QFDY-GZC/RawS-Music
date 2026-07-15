package com.rawsmusic.ui.webdav

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.core.ui.scene.pages.themeColors
import com.rawsmusic.core.ui.widget.predictiveDialogMotion
import com.rawsmusic.core.ui.widget.rememberPredictiveDialogProgress
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.scanner.webdav.AuthMode
import com.rawsmusic.module.scanner.webdav.WebDavClient
import com.rawsmusic.module.scanner.webdav.WebDavConfig
import com.rawsmusic.module.scanner.webdav.WebDavItem
import com.rawsmusic.ui.songs.PlayerHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WebDAV 页面。
 * 从 WebDavFragment 迁移为纯 Compose。
 */
@Composable
fun WebDavPageCompose(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = themeColors()

    val webDavClient = remember { WebDavClient() }
    val items = remember { mutableStateListOf<WebDavItem>() }
    var currentUrl by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var emptyMessage by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }

    val config = remember {
        WebDavConfig(
            url = AppPreferences.WebDav.url,
            username = AppPreferences.WebDav.username,
            password = AppPreferences.WebDav.password,
            authMode = when (AppPreferences.WebDav.authMode) {
                1 -> AuthMode.BASIC
                2 -> AuthMode.DIGEST
                else -> AuthMode.AUTO
            }
        )
    }

    fun loadDirectory(url: String) {
        if (isLoading) return
        isLoading = true
        emptyMessage = ""
        scope.launch {
            try {
                val result = webDavClient.listDirectory(config, url)
                withContext(Dispatchers.Main) {
                    isLoading = false
                    currentUrl = url
                    AppPreferences.WebDav.lastUrl = url
                    items.clear()
                    items.addAll(result.sortedWith(compareByDescending<WebDavItem> { it.isDirectory }.thenBy { it.fileName }))
                    if (items.isEmpty()) emptyMessage = "此目录为空"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    emptyMessage = "加载失败: ${e.message}"
                }
            }
        }
    }

    fun playItem(item: WebDavItem) {
        val fileUrl = webDavClient.buildAuthenticatedUrl(config, item.href)
        val audioFile = AudioFile(
            id = item.href.hashCode().toLong(),
            path = fileUrl,
            title = item.fileName.substringBeforeLast("."),
            artist = "WebDAV",
            album = "WebDAV 远程音乐",
            duration = 0,
            format = item.fileName.substringAfterLast(".", "").uppercase(),
            fileSize = item.size
        )
        PlayerHolder.controller?.play(audioFile)
    }

    fun addToQueue(item: WebDavItem) {
        val fileUrl = webDavClient.buildAuthenticatedUrl(config, item.href)
        val audioFile = AudioFile(
            id = item.href.hashCode().toLong(),
            path = fileUrl,
            title = item.fileName.substringBeforeLast("."),
            artist = "WebDAV",
            album = "WebDAV 远程音乐",
            duration = 0,
            format = item.fileName.substringAfterLast(".", "").uppercase(),
            fileSize = item.size
        )
        PlayerHolder.controller?.addToQueue(audioFile)
        Toast.makeText(context, "已添加到播放队列", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) {
        val savedUrl = AppPreferences.WebDav.url
        if (savedUrl.isNotBlank()) {
            val startUrl = AppPreferences.WebDav.lastUrl.ifBlank { savedUrl }
            loadDirectory(startUrl)
        } else {
            emptyMessage = "请配置 WebDAV 连接"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        // 顶栏
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = {
                val parentUrl = webDavClient.getParentUrl(currentUrl, config)
                if (parentUrl != null) loadDirectory(parentUrl)
                else onBack()
            }) {
                Text("← 返回", color = colors.primary, fontSize = 14.sp)
            }
            Text("WebDAV", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
            TextButton(onClick = { showSettings = true }) {
                Text("设置", color = colors.primary, fontSize = 14.sp)
            }
        }

        // 当前路径
        if (currentUrl.isNotBlank()) {
            Text(
                currentUrl,
                fontSize = 11.sp,
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        // 内容
        when {
            isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.primary)
                }
            }
            emptyMessage.isNotBlank() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(emptyMessage, fontSize = 14.sp, color = colors.secondaryText)
                }
            }
            else -> {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(items) { item ->
                        WebDavItemRow(
                            item = item,
                            onClick = {
                                if (item.isDirectory) {
                                    val dirUrl = webDavClient.buildFileUrl(config, item.href)
                                    loadDirectory(dirUrl)
                                } else if (AudioUtils.isAudioFile(item.fileName)) {
                                    playItem(item)
                                }
                            },
                            onMore = {
                                if (!item.isDirectory && AudioUtils.isAudioFile(item.fileName)) {
                                    addToQueue(item)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 设置对话框
    if (showSettings) {
        WebDavSettingsDialog(
            onDismiss = { showSettings = false },
            onSave = { url, username, password, authMode ->
                AppPreferences.WebDav.url = url
                AppPreferences.WebDav.username = username
                AppPreferences.WebDav.password = password
                AppPreferences.WebDav.lastUrl = ""
                AppPreferences.WebDav.authMode = authMode
                showSettings = false
                loadDirectory(url)
            }
        )
    }
}

@Composable
private fun WebDavItemRow(item: WebDavItem, onClick: () -> Unit, onMore: () -> Unit) {
    val colors = themeColors()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (item.isDirectory) "📁" else "🎵",
            fontSize = 20.sp,
            modifier = Modifier.size(32.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.fileName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val info = if (item.isDirectory) "文件夹"
            else {
                val ext = item.fileName.substringAfterLast(".", "").uppercase()
                "$ext · ${AudioUtils.formatFileSize(item.size)}"
            }
            Text(info, fontSize = 12.sp, color = colors.secondaryText)
        }
        if (!item.isDirectory && AudioUtils.isAudioFile(item.fileName)) {
            TextButton(onClick = onMore) {
                Text("添加", color = colors.primary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun WebDavSettingsDialog(
    onDismiss: () -> Unit,
    onSave: (url: String, username: String, password: String, authMode: Int) -> Unit
) {
    val context = LocalContext.current
    val colors = themeColors()
    var url by remember { mutableStateOf(AppPreferences.WebDav.url) }
    var username by remember { mutableStateOf(AppPreferences.WebDav.username) }
    var password by remember { mutableStateOf(AppPreferences.WebDav.password) }
    var authMode by remember { mutableStateOf(AppPreferences.WebDav.authMode) }
    val authModes = arrayOf("自动选择", "Basic 认证", "Digest 认证")
    val dismissProgress = rememberPredictiveDialogProgress(enabled = true, onDismissRequest = onDismiss)

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .predictiveDialogMotion(dismissProgress)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .padding(20.dp)
        ) {
            Text("WebDAV 设置", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
            Spacer(Modifier.height(16.dp))

            androidx.compose.material3.OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("WebDAV 地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            Text("认证方式", fontSize = 13.sp, color = colors.secondaryText)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                authModes.forEachIndexed { index, mode ->
                    TextButton(onClick = { authMode = index }) {
                        Text(
                            mode,
                            fontSize = 12.sp,
                            color = if (authMode == index) colors.primary else colors.secondaryText
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消", color = colors.secondaryText) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    if (url.isBlank()) {
                        Toast.makeText(context, "请输入地址", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    onSave(url.trim(), username.trim(), password.trim(), authMode)
                }) {
                    Text("保存", color = colors.primary)
                }
            }
        }
    }
}
