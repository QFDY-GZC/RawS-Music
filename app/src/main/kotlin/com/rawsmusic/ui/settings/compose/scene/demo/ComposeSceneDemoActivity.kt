package com.rawsmusic.ui.settings.compose.scene.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

/**
 * 纯 Compose 版本的场景系统演示 Activity
 *
 * 展示：
 * 1. 双引擎列表布局
 * 2. 捏合缩放手势
 * 3. 场景切换动画
 * 4. 液态玻璃效果兼容
 */
class ComposeSceneDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ComposeSceneDemo()
                }
            }
        }
    }
}
