package com.rawsmusic.core.ui.animation

object AnimParams {

    const val PAGE_TRANSITION_DURATION = 300L
    const val BUTTON_PRESS_DURATION = 120L
    const val BUTTON_RELEASE_DURATION = 200L
    const val RIPPLE_DURATION = 350L
    const val FADE_DURATION = 300L
    const val COVER_TRANSITION_DURATION = 250L
    const val LYRIC_AUTO_SCROLL_DELAY = 300L
    const val MODE_SWITCH_DURATION = 400L
    const val MENU_POP_DURATION = 280L
    const val SEEK_BAR_BOUNCE_DURATION = 180L

    // 页面转场采用 2.0f 的减速因子（对应 Poweramp 的 DecelerateInterpolator(2.0f)）
    const val PAGE_DECELERATE = 2.0f

    // 场景动画默认时长
    const val SCENE_ANIM_DURATION = 300L

    // 按钮弹跳：轻微减速
    const val BUTTON_DECELERATE = 1.5f

    // 场景快切：无减速
    const val SCENE_SNAP = 1.0f

    const val SWIPE_THRESHOLD_RATIO = 0.18f
    const val BUTTON_PRESS_SCALE = 0.85f
    const val BUTTON_RELEASE_OVERSHOOT = 1.05f
    const val PLAY_BUTTON_PRESS_SCALE = 0.9f
    const val SECONDARY_PRESS_SCALE = 0.92f
    const val SECONDARY_PRESS_ALPHA = 0.7f
    const val SEEK_THUMB_SCALE = 1.2f
    const val HOVER_Y_SHIFT = -3f
    const val HOVER_ALPHA = 1.0f
    const val NORMAL_ALPHA = 0.6f

    const val SPRING_DAMPING = 0.75f
    const val SPRING_STIFFNESS = 0.15f
    const val COVER_SHIFT_OFFSET = 30f
    const val ICON_SHIFT_OFFSET = 5f

    const val PLAY_ICON_ROTATION = 10f

    const val CONTROL_FADE_DURATION = 150L
    const val SHAPE_MORPH_DURATION = 200L
    const val PAGE_LAYER_FADE_DURATION = 250L

    const val HOME_DIM_ALPHA = 0.6f
    const val PLAY_COVER_ALPHA = 0.75f
    const val LYRIC_CIRCLE_COVER_SIZE_RATIO = 0.12f
    const val PLAY_COVER_SIZE_RATIO = 0.65f
    const val MINI_COVER_SIZE_DP = 40f

    const val GESTURE_TOUCH_SLOP = 20f
    const val CIRCLE_MORPH_START = 0.15f
    const val CIRCLE_MORPH_END = 0.30f

    const val ALBUM_ROTATION_DURATION = 20000L
}
