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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.res.stringResource
import com.rawsmusic.R
import com.rawsmusic.core.ui.widget.predictiveDialogMotion
import com.rawsmusic.core.ui.widget.rememberPredictiveDialogProgress

class DialogHelper(
    private val onVisibilityChanged: (Boolean) -> Unit = {}
) {
    enum class DialogKind {
        QQ_GROUP
    }

    var activeDialog by mutableStateOf<DialogKind?>(null)
        private set

    val isShowing: Boolean
        get() = activeDialog != null

    fun showQqGroupInfo() {
        activeDialog = DialogKind.QQ_GROUP
        onVisibilityChanged(true)
    }

    fun dismiss() {
        if (activeDialog == null) return
        activeDialog = null
        onVisibilityChanged(false)
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
            text = stringResource(R.string.dialog_qq_title),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.dialog_qq_description),
            color = Color.White.copy(alpha = 0.78f),
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(20.dp))
        DialogTextButton(text = stringResource(R.string.dialog_confirm), modifier = Modifier.align(Alignment.End)) {
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
