package com.rawsmusic.core.ui.widget.virtuallist

import kotlin.math.roundToInt

internal fun composeArtworkItemSceneRects(
    position: ComposeItemPosition,
    mode: ComposeVirtualListDisplayMode,
    params: ListZoomParams,
    density: Float,
    artworkRadiusType: VirtualListArtworkRadiusType = VirtualListArtworkRadiusType.TRACK,
): ComposeTransitionRects {
    return if (mode.isGrid) {
        composeArtworkGridSceneRects(position, mode, density, artworkRadiusType)
    } else {
        composeArtworkListSceneRects(position, mode, params, density, artworkRadiusType)
    }
}

private fun composeArtworkGridSceneRects(
    position: ComposeItemPosition,
    mode: ComposeVirtualListDisplayMode,
    density: Float,
    artworkRadiusType: VirtualListArtworkRadiusType,
): ComposeTransitionRects {
    val artworkMargin = (8f * density).roundToInt()
    val coverSize = (position.width - artworkMargin * 2).coerceAtLeast(1)
    val labelMargin = (18f * density).roundToInt()
    val titleTop = artworkMargin + coverSize + (5f * density).roundToInt()
    val titleFontSp = 22f * 0.65f
    val subtitleFontSp = 18.25f * 0.65f
    val titleHeight = (18f * density).roundToInt().coerceAtLeast(1)
    val line2Top = titleTop + titleHeight + (3f * density).roundToInt()
    val line2Height = (16f * density).roundToInt().coerceAtLeast(1)
    val metaHeight = (14f * density).roundToInt().coerceAtLeast(1)
    val metaBottom = position.height + (8f * density).roundToInt()
    return ComposeTransitionRects(
        cover = ComposeItemRect(
            left = artworkMargin,
            top = artworkMargin,
            width = coverSize,
            height = coverSize
        ),
        title = ComposeItemRect(
            left = labelMargin,
            top = titleTop,
            width = (position.width - labelMargin * 2).coerceAtLeast(1),
            height = titleHeight,
            fontSizeSp = titleFontSp
        ),
        subtitle = ComposeItemRect(
            left = labelMargin,
            top = line2Top,
            width = (position.width - labelMargin * 2).coerceAtLeast(1),
            height = line2Height,
            fontSizeSp = subtitleFontSp
        ),
        meta = ComposeItemRect(
            left = (10f * density).roundToInt(),
            top = metaBottom - metaHeight,
            width = (position.width - (22f * density).roundToInt()).coerceAtLeast(1),
            height = metaHeight,
            alpha = 0f,
            fontSizeSp = 13.5f * 0.85f
        ),
        coverRadiusDp = resolveArtworkRadiusDp(
            mode = mode,
            params = null,
            artworkRadiusType = artworkRadiusType,
            coverSideDp = coverSize / density,
        )
    )
}

private fun composeArtworkListSceneRects(
    position: ComposeItemPosition,
    mode: ComposeVirtualListDisplayMode,
    params: ListZoomParams,
    density: Float,
    artworkRadiusType: VirtualListArtworkRadiusType,
): ComposeTransitionRects {
    val coverSize = (params.coverSizeDp * density).roundToInt().coerceAtLeast(1)
    val coverLeft = (params.coverMarginLeftDp * density).roundToInt()
    val coverTop = ((position.height - coverSize) / 2).coerceAtLeast(0)
    val textLeft = coverLeft + coverSize + (params.textMarginLeftDp * density).roundToInt()
    val textRight = position.width - (params.textMarginRightDp * density).roundToInt()
    val textWidth = (textRight - textLeft).coerceAtLeast(1)
    val titleHeight = (22f * params.textScale * density).roundToInt().coerceAtLeast(1)
    val subtitleHeight = (18.25f * params.textScale * density).roundToInt().coerceAtLeast(1)
    val metaHeight = (13.5f * params.textScale * density).roundToInt().coerceAtLeast(1)
    val titleFontSp = 22f * params.textScale
    val subtitleFontSp = 18.25f * params.textScale
    val metaFontSp = 13.5f * params.textScale

    val title: ComposeItemRect
    val subtitle: ComposeItemRect
    val meta: ComposeItemRect
    if (!params.line2Visible) {
        val centerY = position.height / 2
        val titleBottom = centerY - (4f * density).roundToInt()
        val metaTop = centerY + (4f * density).roundToInt()
        title = ComposeItemRect(textLeft, titleBottom - titleHeight, textWidth, titleHeight, fontSizeSp = titleFontSp)
        subtitle = ComposeItemRect(textLeft, metaTop, textWidth, 1, alpha = 0f, fontSizeSp = subtitleFontSp)
        meta = ComposeItemRect(textLeft, metaTop, textWidth, metaHeight, alpha = if (params.metaVisible) 1f else 0f, fontSizeSp = metaFontSp)
    } else if (params.metaInlineFraction >= 1f) {
        val textGap = (2f * density).roundToInt()
        var titleTop = coverTop
        var line2Top = (coverTop + coverSize - subtitleHeight).coerceAtLeast(coverTop)
        if (titleTop + titleHeight + textGap > line2Top) {
            val totalTextHeight = titleHeight + textGap + subtitleHeight
            titleTop = (position.height / 2 - totalTextHeight / 2).coerceAtLeast(0)
            line2Top = titleTop + titleHeight + textGap
        }
        val metaWidth = (textWidth * 0.4f).roundToInt().coerceIn(
            (24f * density).roundToInt(),
            (textWidth - (60f * density).roundToInt()).coerceAtLeast((24f * density).roundToInt())
        )
        val line2Width = (textWidth - metaWidth - (8f * density).roundToInt()).coerceAtLeast((48f * density).roundToInt())
        val metaLeft = (textLeft + line2Width + (8f * density).roundToInt()).coerceAtMost(textRight - metaWidth)
        title = ComposeItemRect(textLeft, titleTop, textWidth, titleHeight, fontSizeSp = titleFontSp)
        subtitle = ComposeItemRect(textLeft, line2Top, line2Width, subtitleHeight, fontSizeSp = subtitleFontSp)
        meta = ComposeItemRect(metaLeft, line2Top, metaWidth, metaHeight, alpha = if (params.metaVisible) 1f else 0f, fontSizeSp = metaFontSp)
    } else {
        var y = (params.coverMarginTopDp * density).roundToInt() +
            (params.titleTopOffsetDp * density).roundToInt()
        title = ComposeItemRect(textLeft, y, textWidth, titleHeight, fontSizeSp = titleFontSp)
        y += titleHeight + (6f * density).roundToInt()
        subtitle = ComposeItemRect(textLeft, y, textWidth, subtitleHeight, alpha = if (params.line2Visible) 1f else 0f, fontSizeSp = subtitleFontSp)
        y += subtitleHeight + (5f * density).roundToInt()
        meta = ComposeItemRect(textLeft, y, textWidth, metaHeight, alpha = if (params.metaVisible) 1f else 0f, fontSizeSp = metaFontSp)
    }

    return ComposeTransitionRects(
        cover = ComposeItemRect(coverLeft, coverTop, coverSize, coverSize),
        title = title,
        subtitle = subtitle,
        meta = meta,
        coverRadiusDp = resolveArtworkRadiusDp(
            mode = mode,
            params = params,
            artworkRadiusType = artworkRadiusType,
            coverSideDp = coverSize / density,
        )
    )
}

/**
 * reference implementation artwork view has three independent corner-radius slots. The selected slot is part of the
 * bound artwork identity (track / album-like collection / other-human collection), while the slot
 * values themselves change with the active list/grid scene. Keep the complete matrix here so every
 * settled and transitional holder resolves the exact same endpoint radius.
 *
 * `OTHER` uses reference implementation's `matchHeight` for every scene except scene_small. Because the artwork is
 * square, half the live cover side is a circle. During pinch transitions both cover side and radius
 * are linearly interpolated, so a human/artist cover stays circular for the entire gesture instead
 * of snapping to a circle at the committed endpoint.
 */
internal fun resolveArtworkRadiusDp(
    mode: ComposeVirtualListDisplayMode,
    params: ListZoomParams?,
    artworkRadiusType: VirtualListArtworkRadiusType,
    coverSideDp: Float,
): Float {
    val safeCoverSide = coverSideDp.coerceAtLeast(0f)
    return when (mode) {
        ComposeVirtualListDisplayMode.LIST_SMALL -> when (artworkRadiusType) {
            VirtualListArtworkRadiusType.TRACK -> params?.cornerRadiusTracksDp ?: 8f
            VirtualListArtworkRadiusType.ALBUM -> params?.cornerRadiusAlbumsDp ?: 8f
            VirtualListArtworkRadiusType.OTHER -> 8f
        }
        ComposeVirtualListDisplayMode.LIST_NORMAL,
        ComposeVirtualListDisplayMode.LIST_ZOOMED -> when (artworkRadiusType) {
            VirtualListArtworkRadiusType.TRACK -> params?.cornerRadiusTracksDp
                ?: if (mode == ComposeVirtualListDisplayMode.LIST_ZOOMED) 24f else 18f
            VirtualListArtworkRadiusType.ALBUM -> params?.cornerRadiusAlbumsDp
                ?: if (mode == ComposeVirtualListDisplayMode.LIST_ZOOMED) 12f else 8f
            VirtualListArtworkRadiusType.OTHER -> safeCoverSide * 0.5f
        }
        ComposeVirtualListDisplayMode.GRID_4,
        ComposeVirtualListDisplayMode.GRID_3 -> when (artworkRadiusType) {
            VirtualListArtworkRadiusType.TRACK -> 16f
            VirtualListArtworkRadiusType.ALBUM -> 8f
            VirtualListArtworkRadiusType.OTHER -> safeCoverSide * 0.5f
        }
        ComposeVirtualListDisplayMode.GRID_2 -> when (artworkRadiusType) {
            VirtualListArtworkRadiusType.TRACK -> 24f
            VirtualListArtworkRadiusType.ALBUM -> 16f
            VirtualListArtworkRadiusType.OTHER -> safeCoverSide * 0.5f
        }
    }.coerceAtMost(safeCoverSide * 0.5f)
}

internal fun interpolateArtworkRadiusDp(
    sourceRadiusDp: Float,
    targetRadiusDp: Float,
    progress: Float,
): Float {
    val p = progress.coerceIn(0f, 1f)
    return sourceRadiusDp + (targetRadiusDp - sourceRadiusDp) * p
}

internal fun lerpComposeItemRect(
    source: ComposeItemRect,
    target: ComposeItemRect,
    fraction: Float
): ComposeItemRect {
    val f = fraction.coerceIn(0f, 1f)
    return ComposeItemRect(
        left = lerpIntLocal(source.left, target.left, f),
        top = lerpIntLocal(source.top, target.top, f),
        width = lerpIntLocal(source.width, target.width, f).coerceAtLeast(1),
        height = lerpIntLocal(source.height, target.height, f).coerceAtLeast(1),
        alpha = source.alpha + (target.alpha - source.alpha) * f,
        fontSizeSp = source.fontSizeSp + (target.fontSizeSp - source.fontSizeSp) * f
    )
}

private fun lerpIntLocal(from: Int, to: Int, fraction: Float): Int {
    val f = fraction.coerceIn(0f, 1f)
    return (from + (to - from) * f).roundToInt()
}

