package com.rawsmusic.helper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.core.ui.widget.predictiveDialogMotion
import com.rawsmusic.core.ui.widget.rememberPredictiveDialogProgress

class DialogHelper(
    private val onVisibilityChanged: (Boolean) -> Unit = {}
) {
    enum class DialogKind {
        QQ_GROUP,
        SLEEP_TIMER
    }

    var activeDialog by mutableStateOf<DialogKind?>(null)
        private set

    var sleepOptions by mutableStateOf<List<String>>(emptyList())
        private set

    var checkedSleepIndex by mutableIntStateOf(-1)
        private set

    private var sleepController: PlayerController? = null

    val isShowing: Boolean
        get() = activeDialog != null

    fun showQqGroupInfo() {
        activeDialog = DialogKind.QQ_GROUP
        onVisibilityChanged(true)
    }

    fun showSleepTimer(playerController: PlayerController?) {
        val controller = playerController ?: return
        sleepController = controller
        sleepOptions = SLEEP_TIMER_OPTIONS.toList()
        checkedSleepIndex = currentSleepTimerIndex(controller)
        activeDialog = DialogKind.SLEEP_TIMER
        onVisibilityChanged(true)
    }

    fun selectSleepTimer(index: Int) {
        val controller = sleepController ?: return
        when (index) {
            0 -> controller.cancelSleepTimer()
            1 -> controller.startSleepTimer(10)
            2 -> controller.startSleepTimer(15)
            3 -> controller.startSleepTimer(20)
            4 -> controller.startSleepTimer(30)
            5 -> controller.startSleepTimer(45)
            6 -> controller.startSleepTimer(60)
            7 -> controller.startSleepTimer(90)
            8 -> controller.enableStopAfterCurrent()
            9 -> controller.startSleepTimerSongs(3)
            10 -> controller.startSleepTimerSongs(5)
        }
        dismiss()
    }

    fun dismiss() {
        if (activeDialog == null) return
        activeDialog = null
        sleepController = null
        onVisibilityChanged(false)
    }

    private fun currentSleepTimerIndex(controller: PlayerController): Int {
        return when (controller.getSleepTimerMode()) {
            1 -> {
                when (AppPreferences.Player.sleepTimerMinutes) {
                    10 -> 1
                    15 -> 2
                    20 -> 3
                    30 -> 4
                    45 -> 5
                    60 -> 6
                    90 -> 7
                    else -> 4
                }
            }
            3 -> 8
            2 -> {
                val songs = if (controller.isSleepTimerActive()) 3 else 0
                when (songs) {
                    3 -> 9
                    5 -> 10
                    else -> -1
                }
            }
            else -> 0
        }
    }

    companion object {
        private val SLEEP_TIMER_OPTIONS = arrayOf(
            "关闭",
            "10 分钟",
            "15 分钟",
            "20 分钟",
            "30 分钟",
            "45 分钟",
            "60 分钟",
            "90 分钟",
            "播完当前",
            "播完 3 首后",
            "播完 5 首后"
        )
    }
}

@Composable
fun DialogOverlay(
    helper: DialogHelper,
    modifier: Modifier = Modifier
) {
    val visible = helper.activeDialog != null
    val dismissProgress = rememberPredictiveDialogProgress(visible, helper::dismiss)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(130)),
        exit = fadeOut(tween(120)),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { helper.dismiss() }
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(140)) + scaleIn(tween(180), initialScale = 0.94f),
                exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.96f),
                modifier = Modifier.predictiveDialogMotion(dismissProgress)
            ) {
                when (helper.activeDialog) {
                    DialogHelper.DialogKind.QQ_GROUP -> QqGroupDialog(helper)
                    DialogHelper.DialogKind.SLEEP_TIMER -> SleepTimerDialog(helper)
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun QqGroupDialog(helper: DialogHelper) {
    DialogCard {
        Text(
            text = "QQ群号",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "QQ群号1093312333，欢迎大家进群讨论。",
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(20.dp))
        DialogTextButton(text = "确定", modifier = Modifier.align(Alignment.End)) {
            helper.dismiss()
        }
    }
}

@Composable
private fun SleepTimerDialog(helper: DialogHelper) {
    DialogCard {
        Text(
            text = "睡眠定时",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(10.dp))
        helper.sleepOptions.forEachIndexed { index, option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { helper.selectSleepTimer(index) }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(
                            if (helper.checkedSleepIndex == index) Color(0xFF4CAF50)
                            else Color.White.copy(alpha = 0.18f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (helper.checkedSleepIndex == index) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                    }
                }
                Text(
                    text = option,
                    color = Color.White.copy(alpha = if (helper.checkedSleepIndex == index) 0.95f else 0.72f),
                    fontSize = 14.sp,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        DialogTextButton(text = "取消", modifier = Modifier.align(Alignment.End)) {
            helper.dismiss()
        }
    }
}

@Composable
private fun DialogCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.86f)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xF21B1816))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .padding(horizontal = 22.dp, vertical = 20.dp),
        content = content
    )
}

@Composable
private fun DialogTextButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color(0xFF4CAF50),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
