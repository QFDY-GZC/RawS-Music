package com.rawsmusic.ui.settings.powerlist

import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import com.rawsmusic.core.ui.R

/**
 * Scene helper that manages layout switching between list and grid modes.
 * Similar to Poweramp's ItemSceneFastLayout + e4 scene engine.
 *
 * Uses ConstraintLayout's ConstraintSet to define two scenes:
 * - Scene 0 (List): Cover on left, text to the right
 * - Scene 1 (Grid): Cover on top, text below
 *
 * View IDs match Poweramp's item_track.xml:
 *   aa_image  = album art
 *   title     = song title
 *   line2     = artist/album
 *   meta      = metadata (bitrate, duration, etc.)
 *   select_box = selection checkbox
 */
object SceneHelper {

    const val SCENE_LIST = PowerListSceneItem.SCENE_NORMAL
    const val SCENE_GRID = PowerListSceneItem.SCENE_GRID

    /**
     * Apply list scene constraints to a ConstraintLayout.
     * Cover: coverSizeDp x coverSizeDp, start-aligned, vertically centered.
     * Poweramp uses layout_matchDimension=widthToHeight (cover is always square).
     *
     * @param coverSizeDp Cover size in dp from ListZoomParams.coverSizeDp.
     *   SMALL=32dp, NORMAL=80dp, ZOOMED=120dp.
     * @param coverMarginLeftDp Cover left margin from ListZoomParams.coverMarginLeftDp.
     * @param coverMarginTopDp Cover top margin from ListZoomParams.coverMarginTopDp.
     * @param coverMarginBottomDp Cover bottom margin from ListZoomParams.coverMarginBottomDp.
     * @param textMarginLeftDp Text left margin from cover end (ListZoomParams.textMarginLeftDp).
     */
    fun applyListScene(
        root: ConstraintLayout,
        coverSizeDp: Int = 80,
        coverMarginLeftDp: Int = 12,
        coverMarginTopDp: Int = 8,
        coverMarginBottomDp: Int = 8,
        textMarginLeftDp: Int = 20
    ) {
        val constraintSet = ConstraintSet()
        constraintSet.clone(root)

        // Cover: start-aligned, vertically centered, square (layout_matchDimension=widthToHeight)
        constraintSet.setMargin(R.id.aa_image, ConstraintSet.START, coverMarginLeftDp.dp(root))
        constraintSet.setMargin(R.id.aa_image, ConstraintSet.TOP, coverMarginTopDp.dp(root))
        constraintSet.setMargin(R.id.aa_image, ConstraintSet.BOTTOM, coverMarginBottomDp.dp(root))

        if (coverSizeDp < 0) {
            // MATCH_PARENT: cover height = row height - margins, width = height (square)
            // Poweramp: layout_height=-1, layout_matchDimension=widthToHeight
            constraintSet.constrainHeight(R.id.aa_image, 0) // match_constraint
            constraintSet.connect(R.id.aa_image, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, coverMarginTopDp.dp(root))
            constraintSet.connect(R.id.aa_image, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, coverMarginBottomDp.dp(root))
            constraintSet.constrainWidth(R.id.aa_image, 0) // match_constraint
            constraintSet.connect(R.id.aa_image, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, coverMarginLeftDp.dp(root))
            constraintSet.setDimensionRatio(R.id.aa_image, "1:1") // square
        } else {
            constraintSet.constrainWidth(R.id.aa_image, coverSizeDp.dp(root))
            constraintSet.constrainHeight(R.id.aa_image, coverSizeDp.dp(root))
            constraintSet.connect(R.id.aa_image, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, coverMarginLeftDp.dp(root))
            constraintSet.connect(R.id.aa_image, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, coverMarginTopDp.dp(root))
            constraintSet.connect(R.id.aa_image, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, coverMarginBottomDp.dp(root))
        }
        constraintSet.clear(R.id.aa_image, ConstraintSet.END)

        // Title: to the right of cover, top of parent
        constraintSet.connect(R.id.title, ConstraintSet.START, R.id.aa_image, ConstraintSet.END, textMarginLeftDp.dp(root))
        constraintSet.connect(R.id.title, ConstraintSet.END, R.id.select_box, ConstraintSet.START, 8.dp(root))
        constraintSet.connect(R.id.title, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
        constraintSet.connect(R.id.title, ConstraintSet.BOTTOM, R.id.line2, ConstraintSet.TOP)
        constraintSet.setVerticalChainStyle(R.id.title, ConstraintSet.CHAIN_PACKED)
        constraintSet.clear(R.id.title, ConstraintSet.BOTTOM)

        // Line2 (artist): below title (Poweramp: layout_attachTop=@id/line2, marginTop=6dp)
        constraintSet.connect(R.id.line2, ConstraintSet.START, R.id.aa_image, ConstraintSet.END, textMarginLeftDp.dp(root))
        constraintSet.connect(R.id.line2, ConstraintSet.END, R.id.select_box, ConstraintSet.START, 8.dp(root))
        constraintSet.connect(R.id.line2, ConstraintSet.TOP, R.id.title, ConstraintSet.BOTTOM, 1.dp(root))
        constraintSet.connect(R.id.line2, ConstraintSet.BOTTOM, R.id.meta, ConstraintSet.TOP)

        // Meta: below line2, bottom of parent (Poweramp: layout_attachTop=@id/line2, marginTop=5dp)
        constraintSet.connect(R.id.meta, ConstraintSet.START, R.id.aa_image, ConstraintSet.END, textMarginLeftDp.dp(root))
        constraintSet.connect(R.id.meta, ConstraintSet.END, R.id.select_box, ConstraintSet.START, 8.dp(root))
        constraintSet.connect(R.id.meta, ConstraintSet.TOP, R.id.line2, ConstraintSet.BOTTOM, 1.dp(root))
        constraintSet.connect(R.id.meta, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)

        constraintSet.applyTo(root)
    }

    /**
     * Apply grid scene constraints to a ConstraintLayout.
     * Cover: full width, 1:1 aspect ratio
     * Text: below cover, full width
     */
    fun applyGridScene(root: ConstraintLayout) {
        val constraintSet = ConstraintSet()
        constraintSet.clone(root)

        root.findViewById<View>(R.id.title)?.visibility = View.VISIBLE
        root.findViewById<View>(R.id.line2)?.visibility = View.VISIBLE
        root.findViewById<View>(R.id.meta)?.visibility = View.INVISIBLE

        // In grid mode, Poweramp uses absolute positioning via FastLayout.
        // We use ConstraintLayout constraints to set the INTERNAL layout of each item,
        // while PowerListView handles the item's POSITION via translationX/Y.
        // The item is laid out at (0,0) with full width, and translationX/Y moves it.

        // Cover: ItemTrackAAImage_scene_grid uses match width/height with 8dp margins
        // and layout_matchDimension=heightToWidth.
        val aaMargin = 8.dp(root)
        constraintSet.constrainWidth(R.id.aa_image, 0) // match_constraint
        constraintSet.constrainHeight(R.id.aa_image, 0) // match_constraint
        constraintSet.connect(R.id.aa_image, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, aaMargin)
        constraintSet.connect(R.id.aa_image, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, aaMargin)
        constraintSet.connect(R.id.aa_image, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP, aaMargin)
        constraintSet.clear(R.id.aa_image, ConstraintSet.BOTTOM)
        constraintSet.setDimensionRatio(R.id.aa_image, "1:1")

        // Poweramp grid labels are anchored to the bottom of the cell over the cover.
        val labelMargin = 18.dp(root)
        constraintSet.connect(R.id.line2, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, labelMargin)
        constraintSet.connect(R.id.line2, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, labelMargin)
        constraintSet.connect(R.id.line2, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, 10.dp(root))
        constraintSet.clear(R.id.line2, ConstraintSet.TOP)

        constraintSet.connect(R.id.title, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, labelMargin)
        constraintSet.connect(R.id.title, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END, labelMargin)
        constraintSet.connect(R.id.title, ConstraintSet.BOTTOM, R.id.line2, ConstraintSet.TOP, 1.dp(root))
        constraintSet.clear(R.id.title, ConstraintSet.TOP)

        // Base ItemTrackMeta_scene_grid is INVISIBLE, attached left/bottom with margins.
        constraintSet.connect(R.id.meta, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START, 10.dp(root))
        constraintSet.clear(R.id.meta, ConstraintSet.END)
        constraintSet.clear(R.id.meta, ConstraintSet.TOP)
        constraintSet.connect(R.id.meta, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM, (-8).dp(root))

        constraintSet.applyTo(root)
    }

    /**
     * Get the current scene based on column count.
     */
    fun getSceneForColumnCount(columnCount: Int): Int {
        return if (columnCount > 1) SCENE_GRID else SCENE_LIST
    }

    /**
     * Apply scene based on column count and zoom params.
     * @param zoomParams Current zoom params for cover size/margins (null = use defaults).
     */
    fun applyScene(root: ConstraintLayout, columnCount: Int, zoomParams: ListZoomParams? = null) {
        when (getSceneForColumnCount(columnCount)) {
            SCENE_LIST -> {
                if (zoomParams != null) {
                    applyListScene(
                        root,
                        coverSizeDp = zoomParams.coverSizeDp.toInt(),
                        coverMarginLeftDp = zoomParams.coverMarginLeftDp.toInt(),
                        coverMarginTopDp = zoomParams.coverMarginTopDp.toInt(),
                        coverMarginBottomDp = zoomParams.coverMarginBottomDp.toInt(),
                        textMarginLeftDp = zoomParams.textMarginLeftDp.toInt()
                    )
                } else {
                    applyListScene(root)
                }
            }
            SCENE_GRID -> applyGridScene(root)
        }
    }

    /**
     * Extension function to convert dp to pixels.
     */
    private fun Int.dp(view: View): Int {
        return (this * view.context.resources.displayMetrics.density).toInt()
    }
}
