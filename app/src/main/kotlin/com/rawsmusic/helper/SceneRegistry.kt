package com.rawsmusic.helper

import android.content.res.Resources
import android.view.View
import com.rawsmusic.R
import com.rawsmusic.core.ui.widget.UnifiedPlayerContainer
import com.rawsmusic.core.ui.widget.UnifiedPlayerContainer.Scene as S
import com.rawsmusic.core.ui.widget.UnifiedPlayerContainer.SceneParams as P

/**
 * 场景参数注册中心
 * 负责为 UnifiedPlayerContainer 中的各个 View 注册场景切换参数
 */
class SceneRegistry(
    private val unifiedContainer: UnifiedPlayerContainer,
    private val resources: Resources
) {

    /**
     * 注册所有场景参数
     */
    fun registerAll() {
        registerNavHostFragment()
        registerUnifiedMainContainer()
        registerMainBgScrim()
        registerPlayerTouchBlocker()
        registerPlayCover()
        registerLyricContentContainer()
        registerBtnPlayModeContainer()
        registerBtnMoreActionContainer()
        registerIvHiresSmall()
        registerPlayTitleGroup()
        registerAudioInfoCapsule()
        registerPlayCoverMirror()
        registerPlayBgView()
        registerPlayBgScrim()
        registerPlayBottomPanel()
        registerAudioVisualizer()
        registerPlayMetadataCard()
        registerMiniPlayerBar()
        registerLyricBgView()
        registerLyricMainLayer()
        registerBackgroundView()
        registerLyricMetadataCard()
        registerQueuePageContainer()
        registerAlbumDetailContainer()
        registerEffectsPanel()
        registerPlayViewQueueAlbumOverrides()
        registerEffectsFadeOutOverrides()

        unifiedContainer.applyImmersiveSceneParams()
    }

    private fun registerNavHostFragment() {
        // 歌曲列表已迁移到容器模式，nav_host_fragment 在 MAIN 场景始终隐藏
        unifiedContainer.registerViewScenes(
            R.id.nav_host_fragment,
            S.MAIN to P(
                scene = S.MAIN,
                alpha = 0f,
                translationY = 0f,
                scaleX = 1f,
                scaleY = 1f,
                visibility = View.GONE
            ),
            S.PLAYER to P(
                scene = S.PLAYER,
                alpha = 0f,
                translationY = -resources.getDimension(R.dimen.scene_translation_y),
                scaleX = 0.92f,
                scaleY = 0.92f,
                visibility = View.GONE
            ),
            S.LYRIC to P(
                scene = S.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.QUEUE to P(
                scene = S.QUEUE,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS,
                alpha = 0f,
                visibility = View.GONE
            )
        )
    }

    private fun registerUnifiedMainContainer() {
        // 主界面内容容器：歌曲列表已迁移到容器模式，MAIN 场景始终 VISIBLE
        unifiedContainer.registerViewScenes(
            R.id.unifiedMainContainer,
            S.MAIN to P(
                scene = S.MAIN,
                alpha = 1f,
                translationY = 0f,
                scaleX = 1f,
                scaleY = 1f,
                visibility = View.VISIBLE
            ),
            S.PLAYER to P(
                scene = S.PLAYER,
                alpha = 0f,
                translationY = -resources.getDimension(R.dimen.scene_translation_y),
                scaleX = 0.92f,
                scaleY = 0.92f,
                visibility = View.INVISIBLE
            ),
            S.LYRIC to P(
                scene = S.LYRIC,
                alpha = 0f,
                visibility = View.INVISIBLE
            ),
            S.QUEUE to P(
                scene = S.QUEUE,
                alpha = 0f,
                visibility = View.INVISIBLE
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL,
                alpha = 0f,
                visibility = View.INVISIBLE
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS,
                alpha = 0f,
                visibility = View.INVISIBLE
            )
        )
    }

    private fun registerMainBgScrim() {
        // 主界面背景遮罩：与主界面容器同步淡出
        unifiedContainer.registerViewScenes(
            R.id.mainBgScrim,
            S.MAIN to P(
                scene = S.MAIN,
                alpha = 1f,
                visibility = View.VISIBLE
            ),
            S.PLAYER to P(
                scene = S.PLAYER,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.LYRIC to P(
                scene = S.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.QUEUE to P(
                scene = S.QUEUE,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS,
                alpha = 0f,
                visibility = View.GONE
            )
        )
    }

    private fun registerPlayerTouchBlocker() {
        // 触摸拦截层：非 MAIN 场景时立即可见，阻止穿透到底层列表
        unifiedContainer.registerViewScenes(
            R.id.playerTouchBlocker,
            S.MAIN to P(
                scene = S.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.PLAYER to P(
                scene = S.PLAYER,
                alpha = 1f,
                visibility = View.VISIBLE
            ),
            S.LYRIC to P(
                scene = S.LYRIC,
                alpha = 1f,
                visibility = View.VISIBLE
            ),
            S.QUEUE to P(
                scene = S.QUEUE,
                alpha = 1f,
                visibility = View.VISIBLE
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL,
                alpha = 1f,
                visibility = View.VISIBLE
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS,
                alpha = 1f,
                visibility = View.VISIBLE
            )
        )
    }

    private fun registerPlayCover() {
        val isImmersive = unifiedContainer.isImmersiveEnabled
        val coverAlpha = if (isImmersive) 0f else 1f
        unifiedContainer.registerViewScenes(
            R.id.ivPlayCover,
            S.MAIN to P(
                scene = S.MAIN,
                alpha = 0f,
                visibility = View.INVISIBLE
            ),
            S.PLAYER to P(
                scene = S.PLAYER,
                alpha = coverAlpha,
                visibility = View.INVISIBLE,
                translationX = 0f,
                translationY = 0f,
            ),
            S.LYRIC to P(
                scene = S.LYRIC,
                alpha = coverAlpha,
                visibility = View.INVISIBLE,
                scaleX = 0.3f,
                scaleY = 0.3f,
                translationX = 0f,
                translationY = 0f,
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS,
                alpha = coverAlpha,
                visibility = View.INVISIBLE,
                translationX = 0f,
                translationY = 0f,
            )
        )
    }

    private fun registerLyricContentContainer() {
        unifiedContainer.registerViewScenes(
            R.id.lyricContentContainer,
            S.MAIN to P(
                scene = S.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.PLAYER to P(
                scene = S.PLAYER,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.LYRIC to P(
                scene = S.LYRIC,
                alpha = 1f,
                visibility = View.VISIBLE
            ),
            S.QUEUE to P(
                scene = S.QUEUE,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS,
                alpha = 0f,
                visibility = View.GONE
            )
        )
    }

    private fun registerBtnPlayModeContainer() {
        unifiedContainer.registerViewScenes(
            R.id.btnPlayModeContainer,
            S.MAIN to P(
                scene = S.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.PLAYER to P(
                scene = S.PLAYER,
                alpha = 1f,
                visibility = View.VISIBLE
            ),
            S.LYRIC to P(
                scene = S.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.QUEUE to P(
                scene = S.QUEUE,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS,
                alpha = 0f,
                visibility = View.GONE
            )
        )
    }

    private fun registerBtnMoreActionContainer() {
        unifiedContainer.registerViewScenes(
            R.id.btnMoreActionContainer,
            S.MAIN to P(
                scene = S.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.PLAYER to P(
                scene = S.PLAYER,
                alpha = 1f,
                visibility = View.VISIBLE
            ),
            S.LYRIC to P(
                scene = S.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.QUEUE to P(
                scene = S.QUEUE,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS,
                alpha = 0f,
                visibility = View.GONE
            )
        )
    }

    private fun registerIvHiresSmall() {
        unifiedContainer.registerViewScenes(
            R.id.ivHiresSmall,
            S.MAIN to P(
                scene = S.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.PLAYER to P(
                scene = S.PLAYER,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.LYRIC to P(
                scene = S.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.QUEUE to P(
                scene = S.QUEUE,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS,
                alpha = 0f,
                visibility = View.GONE
            )
        )
    }

    private fun registerPlayTitleGroup() {
        unifiedContainer.registerViewScenes(
            R.id.playTitleGroup,
            S.MAIN to P(
                scene = S.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.PLAYER to P(
                scene = S.PLAYER,
                alpha = 1f,
                visibility = View.VISIBLE,
                scaleX = 1f,
                scaleY = 1f,
                translationX = 0f,
                translationY = 0f
            ),
            S.LYRIC to P(
                scene = S.LYRIC,
                alpha = 1f,
                visibility = View.VISIBLE,
                scaleX = 0.98f,
                scaleY = 0.98f
            ),
            S.QUEUE to P(
                scene = S.QUEUE,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS,
                alpha = 0f,
                visibility = View.GONE
            )
        )
    }

    private fun registerAudioInfoCapsule() {
        unifiedContainer.registerViewScenes(
            R.id.audioInfoCapsule,
            S.MAIN to P(
                scene = S.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.PLAYER to P(
                scene = S.PLAYER,
                alpha = 1f,
                visibility = View.VISIBLE
            ),
            S.LYRIC to P(
                scene = S.LYRIC,
                alpha = 1f,
                visibility = View.VISIBLE
            ),
            S.QUEUE to P(
                scene = S.QUEUE,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS,
                alpha = 0f,
                visibility = View.GONE
            )
        )
    }

    private fun registerPlayCoverMirror() {
        // ===== 沉浸式封面设置=====
        unifiedContainer.registerViewScenes(
            R.id.ivPlayCoverMirror,
            S.MAIN to P(
                scene = S.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.PLAYER to P(
                scene = S.PLAYER,
            ),
            S.LYRIC to P(
                scene = S.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.QUEUE to P(
                scene = S.QUEUE,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS,
                alpha = 0f,
                visibility = View.GONE
            )
        )
    }

    private fun registerPlayBgView() {
        val isImmersive = unifiedContainer.isImmersiveEnabled
        unifiedContainer.registerViewScenes(
            R.id.playBgView,
            S.MAIN to P(
                scene = S.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.PLAYER to P(
                scene = S.PLAYER,
                alpha = if (isImmersive) 1f else 0f,
                visibility = if (isImmersive) View.VISIBLE else View.INVISIBLE
            ),
            S.LYRIC to P(
                scene = S.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.QUEUE to P(
                scene = S.QUEUE,
                alpha = if (isImmersive) 1f else 0f,
                visibility = if (isImmersive) View.VISIBLE else View.INVISIBLE
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL,
                alpha = if (isImmersive) 1f else 0f,
                visibility = if (isImmersive) View.VISIBLE else View.INVISIBLE
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS,
                alpha = if (isImmersive) 1f else 0f,
                visibility = if (isImmersive) View.VISIBLE else View.INVISIBLE
            )
        )
    }

    private fun registerPlayBgScrim() {
        val isImmersive = unifiedContainer.isImmersiveEnabled
        val scrimAlpha = if (isImmersive) 0f else 1f
        val scrimVisibility = if (isImmersive) View.INVISIBLE else View.VISIBLE
        unifiedContainer.registerViewScenes(
            R.id.playBgScrim,
            S.MAIN to P(
                scene = S.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.PLAYER to P(
                scene = S.PLAYER,
                alpha = scrimAlpha,
                visibility = scrimVisibility
            ),
            S.LYRIC to P(
                scene = S.LYRIC,
                alpha = scrimAlpha,
                visibility = scrimVisibility
            ),
            S.QUEUE to P(
                scene = S.QUEUE,
                alpha = scrimAlpha,
                visibility = scrimVisibility
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL,
                alpha = scrimAlpha,
                visibility = scrimVisibility
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS,
                alpha = scrimAlpha,
                visibility = scrimVisibility
            )
        )
    }

    private fun registerPlayBottomPanel() {
        unifiedContainer.registerViewScenes(
            R.id.playBottomPanel,
            S.MAIN to P(
                scene = S.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.PLAYER to P(
                scene = S.PLAYER,
                alpha = 1f,
                visibility = View.VISIBLE
            ),
            S.LYRIC to P(
                scene = S.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.QUEUE to P(
                scene = S.QUEUE,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS,
                alpha = 0f,
                visibility = View.GONE
            )
        )
    }

    private fun registerAudioVisualizer() {
        // audioVisualizer 场景切换
        unifiedContainer.registerViewScenes(
            R.id.audioVisualizer,
            S.MAIN to P(
                scene = S.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.PLAYER to P(
                scene = S.PLAYER,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.LYRIC to P(
                scene = S.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.QUEUE to P(
                scene = S.QUEUE,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS,
                alpha = 0f,
                visibility = View.GONE
            )
        )
    }

    private fun registerPlayMetadataCard() {
        unifiedContainer.registerViewScenes(
            R.id.playMetadataCard,
            S.MAIN to P(
                scene = S.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.PLAYER to P(
                scene = S.PLAYER,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.LYRIC to P(
                scene = S.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.QUEUE to P(
                scene = S.QUEUE,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS,
                alpha = 0f,
                visibility = View.GONE
            )
        )
    }

    private fun registerMiniPlayerBar() {
        // miniPlayerBar 已移至 Compose 层，不再需要 View 场景注册
    }

    private fun registerLyricBgView() {
        // ===== 歌词背景场景参数=====
        unifiedContainer.registerViewScenes(
            R.id.lyricBgView,
            S.MAIN to P(
                scene = S.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.PLAYER to P(
                scene = S.PLAYER,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.LYRIC to P(
                scene = S.LYRIC,
                alpha = 1f,
                visibility = View.VISIBLE
            ),
            S.QUEUE to P(
                scene = S.QUEUE,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS,
                alpha = 0f,
                visibility = View.GONE
            )
        )
    }

    private fun registerLyricMainLayer() {
        unifiedContainer.registerViewScenes(
            R.id.lyricMainLayer,
            S.MAIN to P(
                scene = S.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.PLAYER to P(
                scene = S.PLAYER,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.LYRIC to P(
                scene = S.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.QUEUE to P(
                scene = S.QUEUE,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS,
                alpha = 0f,
                visibility = View.GONE
            )
        )
    }

    private fun registerBackgroundView() {
        val isImmersive = unifiedContainer.isImmersiveEnabled
        val bgPlayerAlpha = if (isImmersive) 0f else 1f
        unifiedContainer.registerViewScenes(
            R.id.backgroundView,
            S.MAIN to P(
                scene = S.MAIN,
                alpha = 1f,
                visibility = View.VISIBLE
            ),
            S.PLAYER to P(
                scene = S.PLAYER,
                alpha = bgPlayerAlpha,
                visibility = View.VISIBLE
            ),
            S.LYRIC to P(
                scene = S.LYRIC,
                alpha = bgPlayerAlpha,
                visibility = View.VISIBLE
            ),
            S.QUEUE to P(
                scene = S.QUEUE,
                alpha = 0f,
                visibility = View.VISIBLE
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL,
                alpha = 0f,
                visibility = View.VISIBLE
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS,
                alpha = bgPlayerAlpha,
                visibility = View.VISIBLE
            )
        )
    }

    private fun registerLyricMetadataCard() {
        // ===== Lyric metadata card =====
        unifiedContainer.registerViewScenes(
            R.id.lyricMetadataCard,
            S.MAIN to P(
                scene = S.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.PLAYER to P(
                scene = S.PLAYER,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.LYRIC to P(
                scene = S.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.QUEUE to P(
                scene = S.QUEUE,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL,
                alpha = 0f,
                visibility = View.GONE
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS,
                alpha = 0f,
                visibility = View.GONE
            )
        )
    }

    private fun registerQueuePageContainer() {
        unifiedContainer.registerViewScenes(
            R.id.queuePageContainer,
            S.MAIN to P(
                scene = S.MAIN, alpha = 0f, visibility = View.GONE
            ),
            S.PLAYER to P(
                scene = S.PLAYER, alpha = 0f, visibility = View.VISIBLE, translationY = unifiedContainer.height.toFloat()
            ),
            S.LYRIC to P(
                scene = S.LYRIC, alpha = 0f, visibility = View.GONE
            ),
            S.QUEUE to P(
                scene = S.QUEUE, alpha = 1f, visibility = View.VISIBLE, translationY = 0f
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL, alpha = 0f, visibility = View.GONE
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS, alpha = 0f, visibility = View.GONE
            )
        )
    }

    private fun registerAlbumDetailContainer() {
        unifiedContainer.registerViewScenes(
            R.id.albumDetailContainer,
            S.MAIN to P(
                scene = S.MAIN, alpha = 0f, visibility = View.GONE
            ),
            S.PLAYER to P(
                scene = S.PLAYER, alpha = 0f, visibility = View.VISIBLE, translationX = unifiedContainer.width.toFloat()
            ),
            S.LYRIC to P(
                scene = S.LYRIC, alpha = 0f, visibility = View.GONE
            ),
            S.QUEUE to P(
                scene = S.QUEUE, alpha = 0f, visibility = View.GONE
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL, alpha = 1f, visibility = View.VISIBLE, translationX = 0f
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS, alpha = 0f, visibility = View.GONE
            )
        )
    }

    private fun registerEffectsPanel() {
        unifiedContainer.registerViewScenes(
            R.id.effectsPanel,
            S.MAIN to P(
                scene = S.MAIN, alpha = 0f, visibility = View.GONE
            ),
            S.PLAYER to P(
                scene = S.PLAYER, alpha = 0f, visibility = View.GONE
            ),
            S.LYRIC to P(
                scene = S.LYRIC, alpha = 0f, visibility = View.GONE
            ),
            S.QUEUE to P(
                scene = S.QUEUE, alpha = 0f, visibility = View.GONE
            ),
            S.ALBUM_DETAIL to P(
                scene = S.ALBUM_DETAIL, alpha = 0f, visibility = View.GONE
            ),
            S.EFFECTS to P(
                scene = S.EFFECTS, alpha = 1f, visibility = View.VISIBLE
            )
        )
    }

    private fun registerPlayViewQueueAlbumOverrides() {
        val playViewIds = listOf(
            R.id.ivPlayCover,
            R.id.playTitleGroup,
            R.id.playBottomPanel,
            R.id.btnPlayModeContainer,
            R.id.btnMoreActionContainer,
            R.id.audioInfoCapsule,
            R.id.ivPlayCoverMirror,
            R.id.audioVisualizer
        )

        val ch = unifiedContainer.height.toFloat()
        val cw = unifiedContainer.width.toFloat()
        for (vid in playViewIds) {
            unifiedContainer.registerSceneParams(vid, S.QUEUE,
                P(scene = S.QUEUE, alpha = 0f, translationY = -ch, visibility = View.GONE))
            unifiedContainer.registerSceneParams(vid, S.ALBUM_DETAIL,
                P(scene = S.ALBUM_DETAIL, alpha = 0f, translationX = cw, visibility = View.GONE))
        }
    }

    private fun registerEffectsFadeOutOverrides() {
        val effectsFadeOutIds = listOf(
            R.id.playTitleGroup,
            R.id.playBottomPanel,
            R.id.btnPlayModeContainer,
            R.id.btnMoreActionContainer,
            R.id.audioInfoCapsule,
            R.id.ivHiresSmall
        )
        for (vid in effectsFadeOutIds) {
            unifiedContainer.registerSceneParams(vid, S.EFFECTS,
                P(scene = S.EFFECTS, alpha = 0f, visibility = View.GONE))
        }
    }
}
