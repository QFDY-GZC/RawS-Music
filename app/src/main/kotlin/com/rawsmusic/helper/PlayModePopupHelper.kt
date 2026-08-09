package com.rawsmusic.helper

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.ui.widget.predictiveDialogMotion
import com.rawsmusic.core.ui.widget.rememberPredictiveDialogProgress
import com.rawsmusic.module.player.PlayerController

class PlayModePopupHelper(
    private val context: Context,
    private val getPlayerController: () -> PlayerController?,
    private val onVisibilityChanged: (Boolean) -> Unit = {}
) {
    var isShowing by mutableStateOf(false)
        private set

    fun show() {
        isShowing = true
        onVisibilityChanged(true)
    }

    fun hide() {
        isShowing = false
        onVisibilityChanged(false)
    }

    fun select(mode: PlayMode) {
        getPlayerController()?.setPlayMode(mode)
        updatePlayModeIcon(mode)
        hide()
    }

    fun currentMode(): PlayMode? = getPlayerController()?.playMode?.value

    fun updatePlayModeIcon(playMode: PlayMode) {
        // Playback mode icon is rendered by ComposePlayerContainer.
    }
}

@Composable
fun PlayModePopupOverlay(
    helper: PlayModePopupHelper,
    modifier: Modifier = Modifier
) {
    val dismissProgress = rememberPredictiveDialogProgress(helper.isShowing, helper::hide)
    AnimatedVisibility(
        visible = helper.isShowing,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120)),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = { helper.hide() })
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        helper.hide()
                    }
                }
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .predictiveDialogMotion(
                        progress = dismissProgress,
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                    )
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xEB12100E))
                    .padding(vertical = 8.dp)
            ) {
                PlayMode.entries.forEach { mode ->
                    PlayModeRow(
                        mode = mode,
                        selected = mode == helper.currentMode(),
                        onClick = { helper.select(mode) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayModeRow(mode: PlayMode, selected: Boolean, onClick: () -> Unit) {
    val label = when (mode) {
        PlayMode.SEQUENTIAL -> "顺序播放"
        PlayMode.SHUFFLE_ALL -> "全部随机"
        PlayMode.SHUFFLE_ONCE -> "随机播放"
        PlayMode.REPEAT_ONE -> "单曲循环"
    }
    val iconRes = when (mode) {
        PlayMode.SEQUENTIAL -> R.drawable.ic_order_play_fill
        PlayMode.SHUFFLE_ALL,
        PlayMode.SHUFFLE_ONCE -> com.rawsmusic.core.ui.R.drawable.ic_shuffle_custom
        PlayMode.REPEAT_ONE -> R.drawable.ic_repeat_one_fill
    }
    val textColor = if (selected) Color.White else Color.White.copy(alpha = 0.25f)
    val iconColor = if (mode == PlayMode.SEQUENTIAL && !selected) Color.White.copy(alpha = 0.4f) else Color.White
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(iconColor),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Text(text = "✓", color = Color.White, fontSize = 14.sp)
        }
    }
}
