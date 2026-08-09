package com.rawsmusic.helper

import android.content.res.Resources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rawsmusic.R
import com.rawsmusic.core.ui.widget.predictiveDialogMotion
import com.rawsmusic.core.ui.widget.rememberPredictiveDialogProgress

class MetadataCardPopupHelper(
    private val resources: Resources,
    private val onVisibilityChanged: (Boolean) -> Unit = {}
) {
    enum class Target {
        PLAY,
        LYRIC
    }

    var visibleTarget by mutableStateOf<Target?>(null)
        private set

    var cardLeftPx by mutableFloatStateOf(0f)
        private set

    var cardTopPx by mutableFloatStateOf(0f)
        private set

    var onMetadataClick: (() -> Unit)? = null

    val isShowing: Boolean
        get() = visibleTarget != null

    fun togglePlayCard() {
        toggle(Target.PLAY)
    }

    fun toggleLyricCard() {
        toggle(Target.LYRIC)
    }

    fun hide() {
        if (visibleTarget == null) return
        visibleTarget = null
        onVisibilityChanged(false)
    }

    fun openMetadata() {
        hide()
        onMetadataClick?.invoke()
    }

    private fun toggle(target: Target) {
        if (visibleTarget == target) {
            hide()
            return
        }
        positionCardAbove()
        visibleTarget = target
        onVisibilityChanged(true)
    }

    private fun positionCardAbove() {
        val cardWidth = resources.getDimensionPixelSize(R.dimen.play_mode_card_width)
        val cardMinHeight = resources.getDimensionPixelSize(R.dimen.play_mode_card_min_height)
        val displayMetrics = resources.displayMetrics
        val anchorCenterX = displayMetrics.widthPixels - 72f * displayMetrics.density
        val anchorTopY = displayMetrics.heightPixels - 96f * displayMetrics.density

        cardLeftPx = anchorCenterX - cardWidth / 2f
        cardTopPx = anchorTopY - cardMinHeight.toFloat()
    }
}

@Composable
fun MetadataCardPopupOverlay(
    helper: MetadataCardPopupHelper,
    modifier: Modifier = Modifier
) {
    val visible = helper.visibleTarget != null
    val dismissProgress = rememberPredictiveDialogProgress(visible, helper::hide)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120)),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { helper.hide() }
                )
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(120)) + scaleIn(
                    animationSpec = tween(180),
                    initialScale = 0.3f,
                    transformOrigin = TransformOrigin(0.5f, 0f)
                ),
                exit = fadeOut(tween(110)) + scaleOut(
                    animationSpec = tween(140),
                    targetScale = 0.3f,
                    transformOrigin = TransformOrigin(0.5f, 0f)
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offsetPx(helper.cardLeftPx, helper.cardTopPx)
                    .predictiveDialogMotion(
                        progress = dismissProgress,
                        translationY = 24.dp,
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    )
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xDC1E1B18))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { helper.openMetadata() }
                        )
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(com.rawsmusic.core.ui.R.drawable.ic_info_green),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(Color(0xFF6CF075)),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

private fun Modifier.offsetPx(x: Float, y: Float): Modifier =
    this.then(
        Modifier.offset {
            IntOffset(x.toInt(), y.toInt())
        }
    )
