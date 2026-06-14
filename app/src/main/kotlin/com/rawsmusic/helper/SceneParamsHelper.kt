package com.rawsmusic.helper

import android.content.res.Resources
import android.graphics.RectF
import android.view.View
import androidx.navigation.NavController
import com.rawsmusic.R
import com.rawsmusic.core.ui.widget.UnifiedPlayerContainer
import com.rawsmusic.core.ui.widget.powerlist.ListZoomLevels
import com.rawsmusic.core.ui.widget.powerlist.ListZoomIndex
import com.rawsmusic.databinding.ActivityMainBinding

/**
 * 场景参数注册函数族
 * 封装 PLAYER↔MAIN、PLAYER↔LYRIC、沉浸模式等场景过渡的 SceneParams 注册逻辑
 */
class SceneParamsHelper(
    private val unifiedContainer: UnifiedPlayerContainer,
    private val binding: ActivityMainBinding,
    private val resources: Resources,
    private val navController: NavController,
    private val getPlayCoverTargetRect: () -> RectF,
    private val getListCoverPosition: () -> Quad<Float, Float, Float, Float>?,
    private val getAlbumDetailCoverRect: () -> RectF?,
    private val findSongsPowerListView: () -> Any? = { null }
) {

    fun registerCoverCollapseParams(
        prePlayerWasInFragmentMode: Boolean,
        prePlayerFragmentDest: Int?,
        savedAlbumDetailCoverRect: RectF?
    ) {
        val density = resources.displayMetrics.density
        val cover = binding.ivPlayCover

        cover.pivotX = cover.width / 2f
        cover.pivotY = cover.height / 2f

        binding.playTitleGroup.pivotX = binding.playTitleGroup.width / 2f
        binding.playTitleGroup.pivotY = binding.playTitleGroup.height / 2f

        val targetRect = getPlayCoverTargetRect()
        val targetW = if (targetRect.width() > 0f) targetRect.width() else cover.width.toFloat()
        val targetH = if (targetRect.height() > 0f) targetRect.height() else cover.height.toFloat()

        val shouldUseAlbumDetailCover = navController.currentDestination?.id == R.id.nav_album_detail ||
                (prePlayerWasInFragmentMode && prePlayerFragmentDest == R.id.nav_album_detail)
        val albumDetailPos = if (shouldUseAlbumDetailCover) {
            getAlbumDetailCoverRect()?.let { rect ->
                Quad(rect.left, rect.top, rect.width(), rect.height())
            } ?: savedAlbumDetailCoverRect?.let { rect ->
                Quad(rect.left, rect.top, rect.width(), rect.height())
            }
        } else null
        val listPos = if (albumDetailPos == null) getListCoverPosition() else null

        val coverPos = albumDetailPos ?: listPos
        if (coverPos != null) {
            val sourceCornerDp = if (albumDetailPos != null) 22f
            else (ListZoomLevels.params[ListZoomIndex.NORMAL]?.cornerRadiusTracksDp ?: 18f)
            registerCoverCollapseParamsWithSourcePos(coverPos, targetRect, targetW, targetH, cover, density, sourceCornerDp)
        } else {
            unifiedContainer.registerSceneParams(
                R.id.ivPlayCover,
                UnifiedPlayerContainer.Scene.MAIN,
                UnifiedPlayerContainer.SceneParams(
                    scene = UnifiedPlayerContainer.Scene.MAIN,
                    alpha = 0f,
                    visibility = View.GONE
                )
            )
        }

        unifiedContainer.registerSceneParams(
            R.id.nav_host_fragment,
            UnifiedPlayerContainer.Scene.MAIN,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                translationY = 0f,
                scaleX = 1f,
                scaleY = 1f,
                visibility = View.GONE
            )
        )
        // unifiedMainContainer: MAIN 场景 VISIBLE，PLAYER 场景 GONE
        // 返回时闪现问题通过 onPreparePlayerToMain 中的 hideAllPagesExcept 解决
        unifiedContainer.registerSceneParams(
            R.id.unifiedMainContainer,
            UnifiedPlayerContainer.Scene.MAIN,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 1f,
                translationY = 0f,
                scaleX = 1f,
                scaleY = 1f,
                visibility = View.VISIBLE
            )
        )
        unifiedContainer.registerSceneParams(
            R.id.unifiedMainContainer,
            UnifiedPlayerContainer.Scene.PLAYER,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 0f,
                visibility = View.GONE
            )
        )

        listOf(R.id.playBottomPanel, R.id.btnMore).forEach { viewId ->
            unifiedContainer.registerSceneParams(
                viewId,
                UnifiedPlayerContainer.Scene.MAIN,
                UnifiedPlayerContainer.SceneParams(
                    scene = UnifiedPlayerContainer.Scene.MAIN,
                    alpha = 0f,
                    visibility = View.GONE
                )
            )
        }
        unifiedContainer.registerSceneParams(
            R.id.playTitleGroup,
            UnifiedPlayerContainer.Scene.MAIN,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                scaleX = 0.5f,
                scaleY = 0.5f,
                translationY = resources.getDimension(R.dimen.scene_translation_y_large),
                visibility = View.GONE
            )
        )
        unifiedContainer.registerSceneParams(
            R.id.playTitleGroup,
            UnifiedPlayerContainer.Scene.PLAYER,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 1f,
                scaleX = 1f,
                scaleY = 1f,
                translationX = 0f,
                translationY = 0f,
                visibility = View.VISIBLE
            )
        )
        unifiedContainer.registerSceneParams(
            R.id.playBgView,
            UnifiedPlayerContainer.Scene.MAIN,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                visibility = View.GONE
            )
        )

        binding.playBgScrim?.let { scrim ->
            if (scrim.id != 0) {
                unifiedContainer.registerSceneParams(
                    scrim.id,
                    UnifiedPlayerContainer.Scene.MAIN,
                    UnifiedPlayerContainer.SceneParams(
                        scene = UnifiedPlayerContainer.Scene.MAIN,
                        alpha = 0f,
                        visibility = View.GONE
                    )
                )
                unifiedContainer.registerSceneParams(
                    scrim.id,
                    UnifiedPlayerContainer.Scene.PLAYER,
                    UnifiedPlayerContainer.SceneParams(
                        scene = UnifiedPlayerContainer.Scene.PLAYER,
                        alpha = 1f,
                        visibility = View.VISIBLE
                    )
                )
            }
        }

        // miniPlayerBar 已移至 Compose 层，不再需要 View 场景参数
    }

    /**
     * 将屏幕坐标转换为容器逻辑坐标（补偿容器 scale 变换）
     * getLocationOnScreen 返回逻辑坐标不含 scale，需要用视觉逆变换修正
     */
    private fun screenToContainerX(screenX: Float): Float {
        val mv = FloatArray(9)
        unifiedContainer.matrix.getValues(mv)
        val scaleX = mv[android.graphics.Matrix.MSCALE_X]
        val pivotX = unifiedContainer.pivotX
        val loc = IntArray(2)
        unifiedContainer.getLocationOnScreen(loc)
        val visualOffsetX = pivotX * (1f - scaleX)
        return (screenX - loc[0] - visualOffsetX) / scaleX
    }

    private fun screenToContainerY(screenY: Float): Float {
        val mv = FloatArray(9)
        unifiedContainer.matrix.getValues(mv)
        val scaleY = mv[android.graphics.Matrix.MSCALE_Y]
        val pivotY = unifiedContainer.pivotY
        val loc = IntArray(2)
        unifiedContainer.getLocationOnScreen(loc)
        val visualOffsetY = pivotY * (1f - scaleY)
        return (screenY - loc[1] - visualOffsetY) / scaleY
    }

    fun registerCoverCollapseParamsWithSourcePos(
        sourcePos: Quad<Float, Float, Float, Float>,
        targetRect: RectF,
        targetW: Float,
        targetH: Float,
        cover: View,
        density: Float,
        sourceCornerDp: Float = 18f
    ) {
        val (srcX, srcY, srcW, srcH) = sourcePos

        // 对标 Poweramp: layout 恒定，用 translation/scale 控制视觉位置
        // 封面的 layout (left/top/width/height) 在 MAIN 和 PLAYER 场景必须一致，
        // 否则 switchToSceneSilent 会改变 layout 导致封面跳到错误位置。
        // 使用 PLAYER 场景的 layout 尺寸作为 MAIN 场景的 layout 尺寸。
        val playerLayoutW = cover.width.toFloat()
        val playerLayoutH = cover.height.toFloat()
        val playerLayoutCenterX = cover.left + playerLayoutW / 2f
        val playerLayoutCenterY = cover.top + playerLayoutH / 2f

        // scale = 列表封面尺寸 / PLAYER layout 尺寸（视觉缩放）
        val targetScaleX = (srcW / playerLayoutW).coerceIn(0.1f, 1f)
        val targetScaleY = (srcH / playerLayoutH).coerceIn(0.1f, 1f)

        // 源位置（列表封面屏幕坐标）→ 屏幕中心点
        val srcCenterScreenX = srcX + srcW / 2f
        val srcCenterScreenY = srcY + srcH / 2f

        // 容器视觉左上角（含 scale 偏移）
        val mv = FloatArray(9)
        unifiedContainer.matrix.getValues(mv)
        val cScaleX = mv[android.graphics.Matrix.MSCALE_X]
        val cScaleY = mv[android.graphics.Matrix.MSCALE_Y]
        val cLoc = IntArray(2)
        unifiedContainer.getLocationOnScreen(cLoc)
        val cVisualLeft = cLoc[0] + unifiedContainer.pivotX * (1f - cScaleX)
        val cVisualTop = cLoc[1] + unifiedContainer.pivotY * (1f - cScaleY)

        // translation = (目标屏幕位置 - 容器视觉左上角) / 容器scale - PLAYER layout 中心
        // 使用 PLAYER layout 中心而非 targetRect.centerX()，确保 layout 不变时公式正确
        val translatedX = (srcCenterScreenX - cVisualLeft) / cScaleX - playerLayoutCenterX
        val translatedY = (srcCenterScreenY - cVisualTop) / cScaleY - playerLayoutCenterY

        android.util.Log.d("CoverAnim", "RETURN srcScreen=($srcX,$srcY,$srcW,$srcH) " +
            "targetScale=($targetScaleX,$targetScaleY) " +
            "playerLayout=(${cover.left},${cover.top},$playerLayoutW,$playerLayoutH) " +
            "playerLayoutCenter=($playerLayoutCenterX,$playerLayoutCenterY) " +
            "cScale=$cScaleX cVisualLeft=$cVisualLeft " +
            "srcCenter=($srcCenterScreenX,$srcCenterScreenY) " +
            "coverTrans=(${cover.translationX},${cover.translationY}) " +
            "coverScale=(${cover.scaleX},${cover.scaleY}) " +
            "targetRect=$targetRect " +
            "translated=($translatedX,$translatedY) " +
            "containerW=${unifiedContainer.width} containerH=${unifiedContainer.height}")

        val baseCornerRadius = sourceCornerDp * density
        val mainCornerRadius = if (targetScaleX > 0.01f) baseCornerRadius / targetScaleX else baseCornerRadius

        // MAIN 场景：封面飞到列表位置，layout 保持 PLAYER 尺寸不变
        unifiedContainer.registerSceneParams(
            R.id.ivPlayCover,
            UnifiedPlayerContainer.Scene.MAIN,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 1f,
                scaleX = targetScaleX,
                scaleY = targetScaleY,
                translationX = translatedX,
                translationY = translatedY,
                visibility = View.VISIBLE,
                cornerRadius = mainCornerRadius
            )
        )

        // PLAYER 场景：封面在播放器位置，layout 不变
        unifiedContainer.registerSceneParams(
            R.id.ivPlayCover,
            UnifiedPlayerContainer.Scene.PLAYER,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 1f,
                scaleX = cover.scaleX,
                scaleY = cover.scaleY,
                translationX = cover.translationX,
                translationY = cover.translationY,
                visibility = View.VISIBLE,
                cornerRadius = baseCornerRadius
            )
        )
    }

    fun registerImmersiveCoverCollapseParams() {
        val density = resources.displayMetrics.density
        val containerW = unifiedContainer.width.toFloat()
        val containerH = unifiedContainer.height.toFloat()
        if (containerW <= 0f || containerH <= 0f) return

        val nonCoverFadeIds = listOf(
            R.id.ivPlayCoverMirror,
            R.id.playTitleGroup,
            R.id.playBottomPanel,
            R.id.btnPlayModeContainer,
            R.id.btnMoreActionContainer,
            R.id.audioInfoCapsule,
            R.id.playBgView,
            R.id.playBgScrim
        )
        nonCoverFadeIds.forEach { viewId ->
            unifiedContainer.registerSceneParams(
                viewId,
                UnifiedPlayerContainer.Scene.MAIN,
                UnifiedPlayerContainer.SceneParams(
                    scene = UnifiedPlayerContainer.Scene.MAIN,
                    alpha = 0f,
                    visibility = View.GONE
                )
            )
            unifiedContainer.registerSceneParams(
                viewId,
                UnifiedPlayerContainer.Scene.PLAYER,
                UnifiedPlayerContainer.SceneParams(
                    scene = UnifiedPlayerContainer.Scene.PLAYER,
                    alpha = if (viewId == R.id.ivPlayCoverMirror) 0.55f else 1f,
                    visibility = View.VISIBLE
                )
            )
        }

        // miniPlayerBar 已移至 Compose 层，不再需要 View 场景参数
        unifiedContainer.registerSceneParams(
            R.id.nav_host_fragment,
            UnifiedPlayerContainer.Scene.MAIN,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                translationY = 0f,
                scaleX = 1f,
                scaleY = 1f,
                visibility = View.GONE
            )
        )
    }

    fun registerCoverLyricParams() {
        val density = resources.displayMetrics.density
        val baseCornerRadius = (ListZoomLevels.params[ListZoomIndex.NORMAL]?.cornerRadiusTracksDp ?: 18f) * density
        val isImmersive = unifiedContainer.isImmersiveEnabled
        val coverAlpha = if (isImmersive) 0f else 1f
        val coverVisibility = if (isImmersive) View.INVISIBLE else View.VISIBLE

        unifiedContainer.registerSceneParams(
            R.id.ivPlayCover,
            UnifiedPlayerContainer.Scene.LYRIC,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = coverAlpha,
                scaleX = 0.3f,
                scaleY = 0.3f,
                translationX = 0f,
                translationY = 0f,
                visibility = coverVisibility,
                cornerRadius = baseCornerRadius
            )
        )

        unifiedContainer.registerSceneParams(
            R.id.ivPlayCover,
            UnifiedPlayerContainer.Scene.PLAYER,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = coverAlpha,
                scaleX = 1f,
                scaleY = 1f,
                translationX = 0f,
                translationY = 0f,
                visibility = coverVisibility,
                cornerRadius = baseCornerRadius
            )
        )

        listOf(R.id.ivPlayCoverMirror).forEach { viewId ->
            unifiedContainer.registerSceneParams(
                viewId,
                UnifiedPlayerContainer.Scene.LYRIC,
                UnifiedPlayerContainer.SceneParams(
                    scene = UnifiedPlayerContainer.Scene.LYRIC,
                    alpha = 0f,
                    visibility = View.GONE
                )
            )
        }

        unifiedContainer.registerSceneParams(
            R.id.playBottomPanel,
            UnifiedPlayerContainer.Scene.LYRIC,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            )
        )
        unifiedContainer.registerSceneParams(
            R.id.playBottomPanel,
            UnifiedPlayerContainer.Scene.PLAYER,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 1f,
                visibility = View.VISIBLE
            )
        )

        unifiedContainer.registerSceneParams(
            R.id.btnMore,
            UnifiedPlayerContainer.Scene.LYRIC,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            )
        )
        unifiedContainer.registerSceneParams(
            R.id.btnMore,
            UnifiedPlayerContainer.Scene.PLAYER,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 0f,
                visibility = View.GONE
            )
        )

        val coverLayoutParams = binding.ivPlayCover.layoutParams as? android.widget.FrameLayout.LayoutParams
        if (coverLayoutParams == null) return
        val coverLeft = binding.ivPlayCover.left.toFloat()
        val coverWidth = binding.ivPlayCover.width.toFloat()
        val titleWidth = binding.playTitleGroup.width.toFloat()

        if (coverWidth <= 0f || titleWidth <= 0f) {
            binding.playTitleGroup.pivotX = 0f
            binding.playTitleGroup.pivotY = 0f
            unifiedContainer.registerSceneParams(
                R.id.playTitleGroup,
                UnifiedPlayerContainer.Scene.LYRIC,
                UnifiedPlayerContainer.SceneParams(
                    scene = UnifiedPlayerContainer.Scene.LYRIC,
                    alpha = 1f,
                    visibility = View.VISIBLE,
                    scaleX = 0.98f,
                    scaleY = 0.98f,
                    translationX = 0f,
                    translationY = 0f
                )
            )
            unifiedContainer.registerSceneParams(
                R.id.playTitleGroup,
                UnifiedPlayerContainer.Scene.PLAYER,
                UnifiedPlayerContainer.SceneParams(
                    scene = UnifiedPlayerContainer.Scene.PLAYER,
                    alpha = 1f,
                    visibility = View.VISIBLE,
                    scaleX = 1f,
                    scaleY = 1f,
                    translationX = 0f,
                    translationY = 0f
                )
            )
            return
        }

        val smallCoverRight = coverLeft + coverWidth * 0.3f
        val titleLayoutParams = binding.playTitleGroup.layoutParams as? android.widget.FrameLayout.LayoutParams
        if (titleLayoutParams == null) return
        val titleCurrentLeft = titleLayoutParams.marginStart.toFloat()
        val titleCurrentTop = titleLayoutParams.topMargin.toFloat()
        val targetX = smallCoverRight + resources.getDimension(R.dimen.spacing_md)
        val isLandscapeLyric = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val targetY = if (isLandscapeLyric) {
            val containerH = unifiedContainer.height.toFloat()
            val coverSize = minOf(unifiedContainer.width * 0.35f, containerH - resources.getDimension(R.dimen.cover_min_top_offset))
            ((containerH - coverSize) / 2f)
        } else {
            resources.getDimension(R.dimen.cover_top_margin)
        }
        binding.playTitleGroup.pivotX = binding.playTitleGroup.width / 2f
        binding.playTitleGroup.pivotY = binding.playTitleGroup.height / 2f
        unifiedContainer.registerSceneParams(
            R.id.playTitleGroup,
            UnifiedPlayerContainer.Scene.LYRIC,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 1f,
                visibility = View.VISIBLE,
                scaleX = 0.98f,
                scaleY = 0.98f,
                translationX = targetX - titleCurrentLeft,
                translationY = targetY - titleCurrentTop
            )
        )
        unifiedContainer.registerSceneParams(
            R.id.playTitleGroup,
            UnifiedPlayerContainer.Scene.PLAYER,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 1f,
                visibility = View.VISIBLE,
                scaleX = 1f,
                scaleY = 1f,
                translationX = 0f,
                translationY = 0f
            )
        )
    }

    fun restoreDefaultSceneParams(
        prePlayerWasInFragmentMode: Boolean,
        unifiedMainContainer: View?
    ) {
        val density = resources.displayMetrics.density
        val isImmersive = unifiedContainer.isImmersiveEnabled
        // 注意：不在此处覆盖 ivPlayCover 的 MAIN 场景参数。
        // 返回动画的 onPreparePlayerToMain 已注册正确的封面位置参数，
        // 此处覆盖为 GONE 会导致封面在动画结束后消失。
        // 封面的 MAIN 参数由 registerCoverCollapseParams 统一管理。
        unifiedContainer.registerSceneParams(
            R.id.ivPlayCover,
            UnifiedPlayerContainer.Scene.PLAYER,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = if (isImmersive) 0f else 1f,
                visibility = if (isImmersive) View.INVISIBLE else View.VISIBLE,
                translationX = 0f,
                translationY = 0f,
                scaleX = 1f,
                scaleY = 1f,
                cornerRadius = (ListZoomLevels.params[ListZoomIndex.NORMAL]?.cornerRadiusTracksDp ?: 18f) * density
            )
        )
        unifiedContainer.registerSceneParams(
            R.id.ivPlayCover,
            UnifiedPlayerContainer.Scene.LYRIC,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = if (isImmersive) 0f else 1f,
                visibility = if (isImmersive) View.INVISIBLE else View.VISIBLE,
                scaleX = 0.3f,
                scaleY = 0.3f,
                translationX = 0f,
                translationY = 0f,
                cornerRadius = (ListZoomLevels.params[ListZoomIndex.NORMAL]?.cornerRadiusTracksDp ?: 18f) * density
            )
        )
        unifiedContainer.registerSceneParams(
            R.id.nav_host_fragment,
            UnifiedPlayerContainer.Scene.MAIN,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                translationY = 0f,
                scaleX = 1f,
                scaleY = 1f,
                visibility = View.GONE
            )
        )
        unifiedContainer.registerSceneParams(
            R.id.unifiedMainContainer,
            UnifiedPlayerContainer.Scene.MAIN,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 1f,
                translationY = 0f,
                scaleX = 1f,
                scaleY = 1f,
                visibility = View.VISIBLE
            )
        )
        listOf(R.id.playBottomPanel, R.id.btnMore).forEach { viewId ->
            unifiedContainer.registerSceneParams(
                viewId,
                UnifiedPlayerContainer.Scene.MAIN,
                UnifiedPlayerContainer.SceneParams(
                    scene = UnifiedPlayerContainer.Scene.MAIN,
                    alpha = 0f,
                    visibility = View.GONE
                )
            )
        }
        unifiedContainer.registerSceneParams(
            R.id.playTitleGroup,
            UnifiedPlayerContainer.Scene.MAIN,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                visibility = View.GONE
            )
        )
        unifiedContainer.registerSceneParams(
            R.id.playBgView,
            UnifiedPlayerContainer.Scene.MAIN,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                visibility = View.GONE
            )
        )
        binding.playBgScrim?.let { scrim ->
            if (scrim.id != 0) {
                unifiedContainer.registerSceneParams(
                    scrim.id,
                    UnifiedPlayerContainer.Scene.MAIN,
                    UnifiedPlayerContainer.SceneParams(
                        scene = UnifiedPlayerContainer.Scene.MAIN,
                        alpha = 0f,
                        visibility = View.GONE
                    )
                )
            }
        }

        if (unifiedContainer.currentScene == UnifiedPlayerContainer.Scene.MAIN) {
            if (prePlayerWasInFragmentMode) {
                binding.navHostFragment.apply {
                    alpha = 1f
                    translationY = 0f
                    scaleX = 1f
                    scaleY = 1f
                    visibility = View.VISIBLE
                }
            } else {
                if (unifiedMainContainer != null) {
                    binding.navHostFragment.visibility = View.GONE
                } else {
                    binding.navHostFragment.apply {
                        alpha = 1f
                        translationY = 0f
                        scaleX = 1f
                        scaleY = 1f
                        visibility = View.VISIBLE
                    }
                }
            }
            binding.playBottomPanel?.apply {
                visibility = View.GONE
                alpha = 0f
            }
            binding.playTitleGroup.apply {
                visibility = View.GONE
                alpha = 0f
            }
            binding.btnMore?.apply {
                visibility = View.GONE
                alpha = 0f
            }
            binding.playMetadataCard.apply {
                visibility = View.GONE
                alpha = 0f
            }
            binding.playBgView.apply {
                visibility = View.GONE
                alpha = 0f
            }
            binding.mainBgScrim?.apply {
                visibility = View.VISIBLE
                alpha = 1f
            }
        }

        unifiedContainer.applyImmersiveSceneParams()
    }
}

/** 四元组，用于传递封面位置 (x, y, w, h) */
data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
