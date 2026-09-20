package com.rawsmusic.core.ui.scene

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * VirtualList 场景转场期间由宿主绘制固定背景，页面根布局不要再绘制整屏背景。
 */
val LocalSceneBackgroundFrozen = staticCompositionLocalOf { false }

/**
 * Whether this composed subtree may own recurring frame/vsync animation work.
 *
 * Some Raw surfaces intentionally remain composed while completely offstage so their first visible
 * frame is already measured/prepared. Composition retention must not also keep Choreographer or
 * infinite-animation consumers alive. Visible surfaces keep the default `true`; hidden prewarm and
 * parked owners provide `false` without changing any visible animation timing or rendering effect.
 */
val LocalUiFrameAnimationActive = staticCompositionLocalOf { true }

/**
 * True only for the two visible retained scene owners while HOME/category geometry is in flight.
 * Hidden spare/preparation scenes keep this false so they may pre-bind holders and start artwork
 * requests before ownership is promoted to the visible transition.
 */
val LocalRetainedSceneMotionActive = staticCompositionLocalOf { false }

/**
 * Preflight ownership for HOME/category navigation.
 *
 * This is intentionally NOT a visual scene-motion flag. It exists only so the currently visible
 * VirtualList can cancel a fling / reject a new scroll gesture before PivotTransition starts, without
 * switching holder windows, artwork policy, playing-row identity or any rendered geometry one frame
 * early. Conflating this with [LocalRetainedSceneMotionActive] caused HOME to visibly snap/rebind
 * before the forward transition began.
 */
val LocalRetainedScenePreflightOwnership = staticCompositionLocalOf { false }

/**
 * Reference PivotTransition stores the transform on every attached holder LayoutRes.  The pivot is a
 * page-space point shared by those holders, while [scaleProvider] is sampled from each holder's
 * graphics layer.  Keeping the pivot immutable and the scalar behind a provider lets frame ticks
 * invalidate holder layers without recomposing/re-laying out the list.
 */
data class RetainedSceneItemTransform(
    val pivotX: Float,
    val pivotY: Float,
    val scaleProvider: () -> Float,
    /**
     * Reference PivotTransition keeps alpha on the retained holder LayoutRes alongside scale.
     * Read this provider from draw/layer phase so a gesture tick never recomposes the page.
     */
    val alphaProvider: () -> Float = { 1f },
)

/** Null means no outer HOME/category PivotTransition is active. */
val LocalRetainedSceneItemTransform =
    staticCompositionLocalOf<RetainedSceneItemTransform?> { null }

/**
 * Long-lived library transform supplied by a parent scene owner (currently MAIN <-> PLAYER).
 *
 * This is deliberately separate from [LocalRetainedSceneItemTransform]: HOME/category
 * PivotTransition temporarily owns that lane. A player transition must therefore survive outside the
 * route-transition composition without replacing or multiplying the active VirtualList transform.
 * Consumers use this only when no route-local retained transform is active.
 */
val LocalExternalRetainedSceneItemTransform =
    staticCompositionLocalOf<RetainedSceneItemTransform?> { null }

/**
 * Alpha gate owned outside ordinary library navigation (currently MAIN <-> PLAYER).
 *
 * Keep this as a provider so a frame tick is consumed from graphicsLayer/draw phase instead of
 * recomposing every page which happens to contain an alphabet index. retained-view implementation keeps one
 * index scroller beside VirtualList and changes its drawable alpha independently from provider
 * composition; this is the Compose equivalent of that external view property.
 */
val LocalExternalAlphabetIndexAlphaProvider =
    staticCompositionLocalOf<(() -> Float)?> { null }

data class SceneChromeAlpha(
    val alphabetIndex: Float = 1f,
    /** Optional draw-phase owner for indexer alpha. Keeps index scroller-style fades out of composition. */
    val alphabetIndexProvider: (() -> Float)? = null,
    val topMenu: Float = 1f,
    /** Optional draw-phase owner for top chrome alpha. Predictive gestures must not recompose SceneTransitionHost per frame. */
    val topMenuProvider: (() -> Float)? = null,
    /**
     * Normalized 0 -> 1 scene clock for top-chrome handoff.  [topMenuExitSceneId] consumes it as
     * 1 -> 0 visibility while [topMenuEnterSceneId] consumes it as 0 -> 1 visibility.  Keeping the
     * scalar behind one draw-phase provider makes source exit and destination entry stop at the
     * exact same predictive-gesture position without recomposing the scene host per frame.
     */
    val topMenuExitProgressProvider: (() -> Float)? = null,
    /** Scene id of the visible/outgoing TopNav child set for the current handoff. */
    val topMenuExitSceneId: String = "",
    /** Scene id of the incoming TopNav child set for the current handoff. */
    val topMenuEnterSceneId: String = "",
    /** Normalized vertical motion of the persistent top action host. */
    val topMenuTranslationProgress: Float = 0f,
    val detachAlphabetIndex: Boolean = false,
)

/**
 * 返回主界面时，列表浮层独立于页面缩放收尾，避免跟随根布局突然消失。
 */
val LocalSceneChromeAlpha = staticCompositionLocalOf { SceneChromeAlpha() }

/**
 * Reference library transition owner.
 *
 * VirtualListGenericPivotTransitionBase owns one VirtualList and switches the current/retained layout
 * state inside that object. SceneTransitionEngine therefore publishes only these layout roles; the
 * persistent library host decides which provider/layout content occupies them. It must never ask
 * SceneTransitionEngine to compose two library SceneContent roots.
 */
data class ReferenceLibraryLayoutFrame(
    val active: Boolean = false,
    val retainedScene: NavScene? = null,
    val currentScene: NavScene? = null,
    /** Concrete provider identity for the retained role (album key, artist key, folder path...). */
    val retainedProviderIdentity: String = "",
    /** Concrete provider identity for the current role. */
    val currentProviderIdentity: String = "",
    /** Scene/controller whose settled presentation must remain pixel-identical at the motion origin. */
    val presentationScene: NavScene? = null,
    /** Concrete provider identity for [presentationScene]. */
    val presentationProviderIdentity: String = "",
    val preparingScene: NavScene? = null,
    /** Concrete provider identity for [preparingScene]. */
    val preparingProviderIdentity: String = "",
    val retainedTransform: RetainedSceneItemTransform? = null,
    val currentTransform: RetainedSceneItemTransform? = null,
)

val LocalReferenceLibraryLayoutFrame =
    staticCompositionLocalOf { ReferenceLibraryLayoutFrame() }

/**
 * Full destination-scene preflight for the persistent HOME/category owner.
 *
 * reference player binds the destination retained scene layout before GenericPivot starts, including its chrome
 * and layout metadata.  Raw normally uses provider-only publication to avoid a second visible list;
 * this flag allows the host to keep one invisible destination composition alive until the visible
 * owner has completed the handoff.  It must never be used as a visual transition flag.
 */
val LocalReferenceLibraryFullScenePreflight = staticCompositionLocalOf { false }
