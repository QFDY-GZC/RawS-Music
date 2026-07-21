package com.rawsmusic.core.ui.widget

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/**
 * The actual shared material stack used by both fixed bottom surfaces.
 *
 * Keeping this as one composable is intentional: sharing constants while running a
 * different effect pipeline still produces visibly different glass (the Step 4 bug).
 */
@Composable
internal fun BottomChromeGlassSurface(
    backdrop: Backdrop?,
    shape: Shape,
    isLight: Boolean,
    accent: Color,
    performanceMode: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val glassSupported = backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val lensSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val profile = BottomChromeGlassMaterial.profile(performanceMode)
    val palette = BottomChromeGlassMaterial.palette(isLight, accent, performanceMode)
    val accentBackdrop = rememberLayerBackdrop()
    val combinedBackdrop = rememberCombinedBackdrop(backdrop ?: emptyBackdrop(), accentBackdrop)
    val edgePaint = remember { Paint() }

    Box(
        modifier = modifier.clip(shape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (glassSupported) {
                        Modifier.drawBackdrop(
                            backdrop = requireNotNull(backdrop),
                            shape = { shape },
                            effects = {
                                blur(profile.bodyBlurDp.dp.toPx())
                                if (
                                    lensSupported &&
                                    profile.bodyLensHeightDp > 0f &&
                                    profile.bodyLensAmountDp > 0f
                                ) {
                                    lens(
                                        profile.bodyLensHeightDp.dp.toPx(),
                                        profile.bodyLensAmountDp.dp.toPx(),
                                    )
                                }
                            },
                            highlight = {
                                Highlight.Default.copy(alpha = palette.mainHighlightAlpha)
                            },
                            shadow = {
                                Shadow.Default.copy(color = palette.shadowColor)
                            },
                            onDrawSurface = { drawRect(palette.neutralSurface) },
                        )
                    } else {
                        Modifier.background(palette.fallbackSurface, shape)
                    }
                ),
        )

        // The accent energy is identical for mini player and navigation body.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(accentBackdrop)
                .background(palette.accentWash, shape),
        )

        if (glassSupported && profile.combinedLayerEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBackdrop(
                        backdrop = combinedBackdrop,
                        shape = { shape },
                        effects = {
                            blur(profile.combinedBlurDp.dp.toPx())
                            if (lensSupported) {
                                lens(
                                    profile.combinedLensHeightDp.dp.toPx(),
                                    profile.combinedLensAmountDp.dp.toPx(),
                                    depthEffect = true,
                                )
                            }
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = palette.combinedHighlightAlpha)
                        },
                        shadow = { null },
                        onDrawSurface = { drawRect(palette.combinedSurface) },
                    ),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    val outline = shape.createOutline(size, layoutDirection, this)
                    edgePaint.color = palette.edgeColor
                    edgePaint.style = PaintingStyle.Stroke
                    edgePaint.strokeWidth = BottomChromeGlassMaterial.EDGE_WIDTH_DP.dp.toPx()
                    edgePaint.isAntiAlias = true
                    drawContext.canvas.drawOutline(outline, edgePaint)
                },
        )

        content()
    }
}
