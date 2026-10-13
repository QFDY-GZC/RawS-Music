package com.rawsmusic.ui.settings

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawPlainBackdrop
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt

/** One translucent surface, so neither the cancel bubble nor the neck gets a second tint. */
@Composable
internal fun searchMaterialModifier(
    cancelWidthPx: Int,
    capsuleProgress: Float,
    cancelProgress: Float,
    color: Color,
    backdrop: Backdrop? = null,
    isDark: Boolean = false,
    decorateSearch: Boolean = true,
): Modifier {
    val materialShader = remember {
        if (Build.VERSION.SDK_INT >= 33) runCatching {
            android.graphics.RuntimeShader(SettingsSearchSurfaceShader.source)
        }.onFailure {
            android.util.Log.w("SettingsSearchSurface", "Public material shader unavailable", it)
        }.getOrNull() else null
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var quadOrigin by remember { mutableStateOf(Offset.Zero) }
    if (Build.VERSION.SDK_INT >= 33 && materialShader != null && backdrop != null) {
        val filter1 = remember(isDark) {
            android.graphics.BlendModeColorFilter(
                if (isDark) 0xCC262626.toInt() else 0x66333333,
                if (isDark) android.graphics.BlendMode.SRC_OVER else android.graphics.BlendMode.COLOR_DODGE,
            )
        }
        val filter2 = remember(isDark) {
            android.graphics.BlendModeColorFilter(
                if (isDark) 0xB37F7F7F.toInt() else 0x99F5F5F5.toInt(),
                if (isDark) android.graphics.BlendMode.OVERLAY else android.graphics.BlendMode.LUMINOSITY,
            )
        }
        val colourEffect = remember(filter1, filter2) {
            android.graphics.RenderEffect.createColorFilterEffect(filter2,
                android.graphics.RenderEffect.createColorFilterEffect(filter1))
        }
        return Modifier.onGloballyPositioned {
            val p = it.positionInWindow()
            if (p != quadOrigin) quadOrigin = p
        }.drawPlainBackdrop(
            backdrop = backdrop,
            shape = { RectangleShape },
            effects = effects@{
                if (!size.width.isFinite() || !size.height.isFinite() || size.width <= 0f || size.height <= 0f)
                    return@effects
                val height = 40f*density
                val cy = size.height*.5f
                val right = SettingsSearchMaterial.searchWidth(size.width.roundToInt(),cancelWidthPx,
                    (12f*density).roundToInt(),capsuleProgress).toFloat()
                val scale = SettingsSearchMotion.cancelScale(cancelProgress).coerceAtLeast(.001f)
                val buttonLeft = SettingsSearchMaterial.cancelLeft(size.width,cancelWidthPx.toFloat(),cancelProgress)
                val firstX = if (rtl) size.width-right*.5f else right*.5f
                val secondX = buttonLeft+cancelWidthPx*.5f
                val first = floatArrayOf(firstX,cy,right*.5f,height*.5f,height*.5f,1f,1f,1f)
                val second = floatArrayOf(if (rtl) size.width-secondX else secondX,cy,
                    cancelWidthPx*.5f,height*.5f,height*.5f,1f,1f/scale,1f/scale)
                val connection = SettingsSearchConnection.parameters(
                    SettingsSearchConnection.Ball(first),SettingsSearchConnection.Ball(second),density)
                materialShader.setFloatUniform("first",first)
                materialShader.setFloatUniform("second",second)
                materialShader.setFloatUniform("connection",connection)
                materialShader.setFloatUniform("quadOrigin",quadOrigin.x,quadOrigin.y)
                materialShader.setFloatUniform("viewportHeight",size.height)
                materialShader.setFloatUniform("cancelOpacity",SettingsSearchMotion.cancelAlpha(cancelProgress))
                materialShader.setFloatUniform("edgeWidth",2.2f*density)
                materialShader.setFloatUniform("edgeAlpha",if (isDark) .6f else .2f)
                materialShader.setFloatUniform("fadeAlpha",if (isDark) .005f else .05f)
                materialShader.setFloatUniform("fadeWidth",if (isDark) 1f else 1.15f)
                materialShader.setFloatUniform("searchDecoration",if (decorateSearch) 1f else 0f)
                // RenderEffect snapshots the shader's current uniforms. Reusing an effect
                // created before these updates freezes the mask at its initial geometry.
                renderEffect = android.graphics.RenderEffect.createChainEffect(
                    android.graphics.RenderEffect.createRuntimeShaderEffect(materialShader,"background"),
                    colourEffect,
                )
            },
        )
    }
    // Compile once, not on each spring frame. Older Android uses the cached union path.
    val shader = remember {
        if (Build.VERSION.SDK_INT >= 33) runCatching {
            android.graphics.RuntimeShader(SettingsSearchMaterial.shader)
        }.getOrNull() else null
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val path = remember { Path() }
    val part = remember { Path() }
    return Modifier.drawWithCache {
        val height = 40f * density
        val cy = size.height / 2f
        val right = SettingsSearchMaterial.searchWidth(
            size.width.roundToInt(), cancelWidthPx, (12f * density).roundToInt(), capsuleProgress,
        ).toFloat()
        val scale = SettingsSearchMotion.cancelScale(cancelProgress).coerceAtLeast(0f)
        val buttonLeft = SettingsSearchMaterial.cancelLeft(size.width, cancelWidthPx.toFloat(), cancelProgress)
        val cancelLeft = buttonLeft + cancelWidthPx * (1f - scale) / 2f
        val cancelRight = cancelLeft + cancelWidthPx * scale
        val cancelTop = cy - height * scale / 2f
        val cancelBottom = cy + height * scale / 2f
        val opacity = SettingsSearchMotion.cancelAlpha(cancelProgress)
        paint.color = color.toArgb()
        if (shader != null && Build.VERSION.SDK_INT >= 33) {
            shader.setFloatUniform("searchBounds", 0f, cy - height / 2f, right, cy + height / 2f)
            shader.setFloatUniform("cancelBounds", cancelLeft, cancelTop, cancelRight, cancelBottom)
            shader.setFloatUniform("smoothing", SettingsSearchMaterial.connectionRangeDp * density)
            shader.setFloatUniform("cancelOpacity", opacity)
            shader.setColorUniform("material", color.toArgb())
            paint.color = android.graphics.Color.WHITE
            paint.shader = shader
        } else {
            paint.shader = null
            path.reset()
            path.addRoundRect(RectF(0f, cy - height / 2f, right, cy + height / 2f),
                height / 2f, height / 2f, Path.Direction.CW)
            if (opacity > 0f) {
                part.reset()
                part.addRoundRect(RectF(cancelLeft, cancelTop, cancelRight, cancelBottom),
                    height * scale / 2f, height * scale / 2f, Path.Direction.CW)
                path.op(part, Path.Op.UNION)
                val gap = cancelLeft - right
                val k = SettingsSearchMaterial.connectionRangeDp * density
                val radius = min(height / 2f, height * scale / 2f)
                if (gap < k / 2f && radius > 0f && opacity >= 1f) {
                    val search = SettingsSearchMaterial.Capsule(0f, cy - height / 2f, right, cy + height / 2f)
                    val cancel = SettingsSearchMaterial.Capsule(cancelLeft, cancelTop, cancelRight, cancelBottom)
                    // Trace the SAME zero contour as AGSL, including overlap. A separate
                    // Bezier bridge gave older APIs different neck curvature and snapping.
                    val start = max(0f, min(right - height / 2f, cancelLeft))
                    val end = min(size.width, max(right, cancelLeft + height * scale / 2f))
                    val steps = ((end - start) / density).toInt().coerceIn(2, 192)
                    val contour = FloatArray(steps + 1) { index ->
                        val x = start + (end - start) * index / steps
                        var low = 0f
                        var high = height / 2f + k
                        repeat(12) {
                            val y = (low + high) / 2f
                            if (SettingsSearchMaterial.distance(search, cancel, x, cy + y, density) < 0f)
                                low = y else high = y
                        }
                        low
                    }
                    part.reset()
                    part.moveTo(start, cy - contour[0])
                    for (index in 1..steps) part.lineTo(start + (end - start) * index / steps, cy - contour[index])
                    for (index in steps downTo 0) part.lineTo(start + (end - start) * index / steps, cy + contour[index])
                    part.close()
                    path.op(part, Path.Op.UNION)
                }
            }
        }
        onDrawBehind {
            drawIntoCanvas { canvas ->
                if (shader != null) canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
                else canvas.nativeCanvas.drawPath(path, paint)
            }
        }
    }
}
