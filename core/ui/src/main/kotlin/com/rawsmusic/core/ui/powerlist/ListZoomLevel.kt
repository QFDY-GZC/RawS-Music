package com.rawsmusic.core.ui.widget.powerlist

/**
 * List zoom system for single-column mode.
 *
 * Three zoom levels:
 *   SMALL  (zoom 0): 32dp cover, compact row, line2/meta invisible
 *   NORMAL (zoom 3): 80dp cover, standard row, all text visible
 *   ZOOMED (zoom 4): 120dp cover, large row, all text visible
 */

// ==================== Zoom Level Data ====================

data class ListZoomParams(
    /** Album art cover size in dp. -1 = MATCH_PARENT (cover height = rowHeight - margins) */
    val coverSizeDp: Float,
    /** Row height: positive = dp, negative = sp */
    val rowHeightValue: Float,
    /** Whether rowHeight is sp (true) or dp (false) */
    val rowHeightIsSp: Boolean,
    /** Cover left margin in dp */
    val coverMarginLeftDp: Float,
    /** Cover top margin in dp */
    val coverMarginTopDp: Float,
    /** Cover bottom margin in dp */
    val coverMarginBottomDp: Float,
    /** Corner radius for tracks in dp */
    val cornerRadiusTracksDp: Float,
    /** Corner radius for albums in dp */
    val cornerRadiusAlbumsDp: Float,
    /** Text left margin from cover end in dp */
    val textMarginLeftDp: Float,
    /** Text right margin in dp */
    val textMarginRightDp: Float,
    /** Whether line2 (artist/album) is visible */
    val line2Visible: Boolean,
    /** Whether meta (bitrate/duration) is visible */
    val metaVisible: Boolean,
    /** Title top offset from cover top in dp */
    val titleTopOffsetDp: Float,
    /** Meta inline interpolation: 0.0 = stacked below line2 (NORMAL/ZOOMED), 1.0 = inline right of line2 (SMALL) */
    val metaInlineFraction: Float = if (metaVisible) 0f else 1f,
    /** Text scale factor. Applied to base text sizes: title=22sp, line2=18.25sp, meta=13.5sp */
    val textScale: Float = 0.9f
)

/** Zoom level enum */
enum class ListZoomIndex(val zoomInt: Int) {
    SMALL(0),   // scene_small
    NORMAL(3),  // scene_1
    ZOOMED(4);  // scene_1_zoomed

    companion object {
        fun fromZoomInt(zoom: Int): ListZoomIndex = when (zoom) {
            0 -> SMALL
            3 -> NORMAL
            4 -> ZOOMED
            else -> NORMAL
        }
    }
}

// ==================== Constants ====================

object ListZoomLevels {
    val params: Map<ListZoomIndex, ListZoomParams> = mapOf(
        ListZoomIndex.SMALL to ListZoomParams(
            coverSizeDp = 32f,
            rowHeightValue = 55f,
            rowHeightIsSp = false,
            coverMarginLeftDp = 16f,
            coverMarginTopDp = 0f,
            coverMarginBottomDp = 0f,
            cornerRadiusTracksDp = 8f,   // corners_aa_tracks_small = 8dp (rounded theme)
            cornerRadiusAlbumsDp = 8f,   // corners_aa_albums_small = 8dp
            textMarginLeftDp = 18f,
            textMarginRightDp = 22f,
            line2Visible = true,
            metaVisible = true,
            titleTopOffsetDp = 0f,
            metaInlineFraction = 1f,
            textScale = 0.85f
        ),
        ListZoomIndex.NORMAL to ListZoomParams(
            coverSizeDp = 80f,  // 240x240 / 3 density = 80dp
            rowHeightValue = 96f,  // cover(80dp) + topMargin(8dp) + bottomMargin(8dp) = 96dp
            rowHeightIsSp = false,
            coverMarginLeftDp = 12f,
            coverMarginTopDp = 8f,
            coverMarginBottomDp = 8f,
            cornerRadiusTracksDp = 18f,  // corners_aa_tracks = 18dp (rounded theme)
            cornerRadiusAlbumsDp = 8f,   // corners_aa_albums = 8dp (rounded theme)
            textMarginLeftDp = 20f,
            textMarginRightDp = 20f,
            line2Visible = false,
            metaVisible = true,
            titleTopOffsetDp = 8f,
            metaInlineFraction = 0f,
            textScale = 0.9f
        ),
        ListZoomIndex.ZOOMED to ListZoomParams(
            coverSizeDp = 120f,
            rowHeightValue = 137f,
            rowHeightIsSp = false,
            coverMarginLeftDp = 12f,
            coverMarginTopDp = 8f,
            coverMarginBottomDp = 9f,
            cornerRadiusTracksDp = 24f,  // corners_aa_tracks_zoomed = 24dp (rounded theme)
            cornerRadiusAlbumsDp = 12f,  // corners_aa_albums_zoomed = 12dp (rounded theme)
            textMarginLeftDp = 20f,
            textMarginRightDp = 20f,
            line2Visible = true,
            metaVisible = true,
            titleTopOffsetDp = 8f,
            metaInlineFraction = 0f,
            textScale = 1.0f
        )
    )

    /** Ordered list for interpolation: SMALL -> NORMAL -> ZOOMED */
    val order: List<ListZoomIndex> = listOf(ListZoomIndex.SMALL, ListZoomIndex.NORMAL, ListZoomIndex.ZOOMED)

    fun sceneIdForParams(params: ListZoomParams): Int = when {
        params.coverSizeDp <= 32f -> PowerListSceneItem.SCENE_SMALL
        params.coverSizeDp >= 120f -> PowerListSceneItem.SCENE_ZOOMED
        else -> PowerListSceneItem.SCENE_NORMAL
    }
}

// ==================== Interpolation ====================

/**
 * Interpolates between two ListZoomParams.
 * @param from Source params
 * @param to Target params
 * @param fraction 0.0 = from, 1.0 = to
 */
fun lerpZoomParams(from: ListZoomParams, to: ListZoomParams, fraction: Float): ListZoomParams {
    val f = fraction.coerceIn(0f, 1f)
    return ListZoomParams(
        coverSizeDp = lerp(from.coverSizeDp, to.coverSizeDp, f),
        rowHeightValue = lerp(from.rowHeightValue, to.rowHeightValue, f),
        rowHeightIsSp = if (f < 0.5f) from.rowHeightIsSp else to.rowHeightIsSp,
        coverMarginLeftDp = lerp(from.coverMarginLeftDp, to.coverMarginLeftDp, f),
        coverMarginTopDp = lerp(from.coverMarginTopDp, to.coverMarginTopDp, f),
        coverMarginBottomDp = lerp(from.coverMarginBottomDp, to.coverMarginBottomDp, f),
        cornerRadiusTracksDp = lerp(from.cornerRadiusTracksDp, to.cornerRadiusTracksDp, f),
        cornerRadiusAlbumsDp = lerp(from.cornerRadiusAlbumsDp, to.cornerRadiusAlbumsDp, f),
        textMarginLeftDp = lerp(from.textMarginLeftDp, to.textMarginLeftDp, f),
        textMarginRightDp = lerp(from.textMarginRightDp, to.textMarginRightDp, f),
        line2Visible = if (f < 0.5f) from.line2Visible else to.line2Visible,
        metaVisible = if (f < 0.5f) from.metaVisible else to.metaVisible,
        titleTopOffsetDp = lerp(from.titleTopOffsetDp, to.titleTopOffsetDp, f),
        metaInlineFraction = lerp(from.metaInlineFraction, to.metaInlineFraction, f),
        textScale = lerp(from.textScale, to.textScale, f)
    )
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
