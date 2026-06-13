package com.rawsmusic.ui.settings.compose.scene.constant

/**
 * Compose 常量
 * 对应原版的常量
 *
 * 定义各种常量
 */
object ComposeConstants {
    // ==================== 动画常量 ====================

    /** 默认动画时长 (ms) */
    const val DEFAULT_ANIMATION_DURATION = 300

    /** 快速动画时长 (ms) */
    const val FAST_ANIMATION_DURATION = 150

    /** 慢速动画时长 (ms) */
    const val SLOW_ANIMATION_DURATION = 500

    /** Snap 动画时长 (ms) */
    const val SNAP_ANIMATION_DURATION = 500

    /** 边界弹性动画时长 (ms) */
    const val BOUNDARY_ELASTIC_DURATION = 350

    /** 淡入淡出动画时长 (ms) */
    const val FADE_ANIMATION_DURATION = 200

    // ==================== 手势常量 ====================

    /** 速度阈值 (dp/s) */
    const val VELOCITY_THRESHOLD = 500f

    /** 位置阈值 */
    const val POSITION_THRESHOLD = 0.3f

    /** 触摸斜坡 (px) */
    const val TOUCH_SLOP = 8f

    /** 最小滑动距离 (px) */
    const val MIN_SWIPE_DISTANCE = 100f

    /** 最小捏合距离 (px) */
    const val MIN_PINCH_DISTANCE = 10f

    // ==================== 布局常量 ====================

    /** 默认列数 */
    const val DEFAULT_COLUMNS = 1

    /** 最小列数 */
    const val MIN_COLUMNS = 1

    /** 最大列数 */
    const val MAX_COLUMNS = 4

    /** 默认行高 (dp) */
    const val DEFAULT_ROW_HEIGHT_DP = 96

    /** 默认封面大小 (dp) */
    const val DEFAULT_COVER_SIZE_DP = 80

    /** 默认圆角 (dp) */
    const val DEFAULT_CORNER_RADIUS_DP = 18

    /** 默认文本缩放 */
    const val DEFAULT_TEXT_SCALE = 0.9f

    // ==================== 缩放常量 ====================

    /** SMALL 封面大小 (dp) */
    const val SMALL_COVER_SIZE_DP = 32

    /** SMALL 行高 (dp) */
    const val SMALL_ROW_HEIGHT_DP = 55

    /** SMALL 文本缩放 */
    const val SMALL_TEXT_SCALE = 0.85f

    /** SMALL 圆角 (dp) */
    const val SMALL_CORNER_RADIUS_DP = 8

    /** NORMAL 封面大小 (dp) */
    const val NORMAL_COVER_SIZE_DP = 80

    /** NORMAL 行高 (dp) */
    const val NORMAL_ROW_HEIGHT_DP = 96

    /** NORMAL 文本缩放 */
    const val NORMAL_TEXT_SCALE = 0.9f

    /** NORMAL 圆角 (dp) */
    const val NORMAL_CORNER_RADIUS_DP = 18

    /** ZOOMED 封面大小 (dp) */
    const val ZOOMED_COVER_SIZE_DP = 120

    /** ZOOMED 行高 (dp) */
    const val ZOOMED_ROW_HEIGHT_DP = 137

    /** ZOOMED 文本缩放 */
    const val ZOOMED_TEXT_SCALE = 1.0f

    /** ZOOMED 圆角 (dp) */
    const val ZOOMED_CORNER_RADIUS_DP = 24

    // ==================== 网格常量 ====================

    /** 网格封面边距 (dp) */
    const val GRID_COVER_MARGIN_DP = 8

    /** 网格标签边距 (dp) */
    const val GRID_LABEL_MARGIN_DP = 18

    /** 网格标签高度 (dp) */
    const val GRID_LABEL_HEIGHT_DP = 46

    /** 网格圆角 (dp) */
    const val GRID_CORNER_RADIUS_DP = 16

    /** 网格文本缩放 */
    const val GRID_TEXT_SCALE = 0.65f

    /** 网格元数据缩放 */
    const val GRID_META_SCALE = 0.85f

    // ==================== 文本常量 ====================

    /** 标题基础大小 (sp) */
    const val TITLE_BASE_SP = 22f

    /** 第二行基础大小 (sp) */
    const val LINE2_BASE_SP = 18.25f

    /** 元数据基础大小 (sp) */
    const val META_BASE_SP = 13.5f

    // ==================== 颜色常量 ====================

    /** 背景色 */
    const val BACKGROUND_COLOR = 0xFF121010

    /** 表面色 */
    const val SURFACE_COLOR = 0xFF1A1C1E

    /** 主色 */
    const val PRIMARY_COLOR = 0xFF8AB4F8

    /** 文本主色 */
    const val TEXT_PRIMARY_COLOR = 0xFFFFFFFF

    /** 文本次色 */
    const val TEXT_SECONDARY_COLOR = 0xB3FFFFFF

    /** 文本第三色 */
    const val TEXT_TERTIARY_COLOR = 0x80FFFFFF

    /** 边框色 */
    const val BORDER_COLOR = 0x33FFFFFF

    /** 分隔线色 */
    const val DIVIDER_COLOR = 0x0FFFFFFF

    // ==================== 渐变常量 ====================

    /** 渐变起始色 */
    const val GRADIENT_START_COLOR = 0xFF8B5E3C

    /** 渐变结束色 */
    const val GRADIENT_END_COLOR = 0xFF3C5E8B

    /** 渐变紫色 */
    const val GRADIENT_PURPLE_COLOR = 0xFF5E3C8B

    /** 渐变绿色 */
    const val GRADIENT_GREEN_COLOR = 0xFF3C8B5E

    // ==================== 场景色常量 ====================

    /** 主场景色 */
    const val SCENE_MAIN_COLOR = 0xFF1A1A2E

    /** 播放器场景色 */
    const val SCENE_PLAYER_COLOR = 0xFF1A1A2E

    /** 歌词场景色 */
    const val SCENE_LYRIC_COLOR = 0xFF0F3460

    /** 队列场景色 */
    const val SCENE_QUEUE_COLOR = 0xFF16213E

    /** 专辑详情场景色 */
    const val SCENE_ALBUM_DETAIL_COLOR = 0xFF1A1A2E

    /** 音效场景色 */
    const val SCENE_EFFECTS_COLOR = 0xFF0F3460

    // ==================== 弹性常量 ====================

    /** 弹性最小缩放 */
    const val ELASTIC_MIN_SCALE = 0.85f

    /** 弹性最大缩放 */
    const val ELASTIC_MAX_SCALE = 1.15f

    /** 弹性阻尼比 */
    const val ELASTIC_DAMPING_RATIO = 0.5f

    /** 弹性刚度 */
    const val ELASTIC_STIFFNESS = 300f

    // ==================== 缓存常量 ====================

    /** 默认缓存大小 */
    const val DEFAULT_CACHE_SIZE = 100

    /** 最小缓存大小 */
    const val MIN_CACHE_SIZE = 10

    /** 最大缓存大小 */
    const val MAX_CACHE_SIZE = 1000

    /** 默认预加载数量 */
    const val DEFAULT_PRELOAD_COUNT = 10

    /** 最小预加载数量 */
    const val MIN_PRELOAD_COUNT = 1

    /** 最大预加载数量 */
    const val MAX_PRELOAD_COUNT = 50
}
