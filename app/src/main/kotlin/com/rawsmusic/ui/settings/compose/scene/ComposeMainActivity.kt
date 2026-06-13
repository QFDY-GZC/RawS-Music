package com.rawsmusic.ui.settings.compose.scene

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.rawsmusic.ui.settings.compose.scene.container.ComposeUnifiedPlayerContainer

/**
 * 纯 Compose 版本的主 Activity
 * 替代原版 MainActivity (3484行)
 *
 * 连接所有组件：
 * - ComposeUnifiedPlayerContainer (顶层场景容器)
 * - ComposeUnifiedMainContainer (内容页面容器)
 * - ComposePowerList (歌曲列表)
 */
class ComposeMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ComposeUnifiedPlayerContainer()
                }
            }
        }
    }
}
