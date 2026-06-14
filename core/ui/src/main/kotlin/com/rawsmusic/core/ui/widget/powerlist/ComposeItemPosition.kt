package com.rawsmusic.core.ui.widget.powerlist

/**
 * Compose 版本的 ItemPosition
 * 1:1 等价于 Poweramp 的 s.java (ItemPosition)
 *
 * 表示列表项的位置、变换和动画状态。
 * 每个 ViewHolder 两个实例：slot 0 = 源（当前布局），slot 1 = 目标（目标布局）。
 *
 * 字段映射到 Poweramp s.java:
 *   f4239 → left      (Int) - 也用作 translationX 偏移
 *   B     → top       (Int) - 也用作 translationY 偏移
 *   f4238 → right     (Int)
 *   A     → bottom    (Int)
 *   f4242 → scaleX    (Float)
 *   X     → scaleY    (Float)
 *   f4240 → alpha     (Float)
 *   x     → rotation  (Float)
 *   y     → rotationX (Float)
 *   f4243 → rotationY (Float)
 *   f4244 → sceneId   (Int)
 *   K     → dirtyBits (Int) - 位掩码，用于 B.m1209() 中的选择性属性更新
 */
class ComposeItemPosition(
    // --- 矩形字段 (Int, 对应 Poweramp) ---
    var left: Int = 0,       // f4239
    var top: Int = 0,        // B
    var right: Int = 0,      // f4238
    var bottom: Int = 0,     // A

    // --- 变换字段 (Float, 对应 Poweramp) ---
    @JvmField var scaleX: Float = 1.0f,    // f4242
    @JvmField var scaleY: Float = 1.0f,    // X
    @JvmField var alpha: Float = 1.0f,     // f4240
    @JvmField var rotation: Float = 0.0f,  // x
    @JvmField var rotationX: Float = 0.0f, // y
    @JvmField var rotationY: Float = 0.0f, // f4243

    // --- 场景 + 脏标记 (Int, 对应 Poweramp) ---
    /** 场景 ID (Poweramp 的 f4244)。通过 H() 设置。 */
    @JvmField var sceneId: Int = 0,        // f4244

    /**
     * 脏位掩码 (Poweramp 的 K)。
     * 每个位标记一个属性已更改，用于 B.m1209() 中的选择性更新。
     * 位 0 (1)       = scaleX 已更改
     * 位 1 (2)       = scaleY 已更改
     * 位 2 (4)       = rotation 已更改
     * 位 3 (8)       = rotationX 已更改
     * 位 4 (16)      = rotationY 已更改
     * 位 5 (32)      = alpha 已更改
     * 位 7 (128)    = sceneId 已更改
     */
    var dirtyBits: Int = ALL_DIRTY
) {
    companion object {
        /** 所有位都脏 — 初始状态，首次渲染时强制完整更新。 */
        const val ALL_DIRTY = 0xFFFFF // 1048575 = (1 << 20) - 1

        // 单个脏位常量
        const val DIRTY_SCALE_X = 1
        const val DIRTY_SCALE_Y = 2
        const val DIRTY_ROTATION = 4
        const val DIRTY_ROTATION_X = 8
        const val DIRTY_ROTATION_Y = 16
        const val DIRTY_ALPHA = 32
        const val DIRTY_SIZE = 64
        const val DIRTY_SCENE = 128
    }

    // --- 矩形访问器 (对应 Poweramp s.java 方法) ---

    /** 宽度: right - left (Poweramp 的 m2998()) */
    fun width(): Int = right - left

    /** 高度: bottom - top (Poweramp 的 B()) */
    fun height(): Int = bottom - top

    /** 设置矩形 (Poweramp 的 m3000(l, t, r, b))。不修改脏位。 */
    fun set(left: Int, top: Int, right: Int, bottom: Int) {
        if (width() != (right - left) || height() != (bottom - top)) {
            dirtyBits = dirtyBits or DIRTY_SIZE
        }
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    /** 偏移矩形 dx, dy (Poweramp 的 m2999(dx, dy)) */
    fun offset(dx: Int, dy: Int) {
        left += dx
        top += dy
        right += dx
        bottom += dy
    }

    /** 移动矩形到新的 left/top，保持宽度/高度 (Poweramp 的 m3001(left, top)) */
    fun moveTo(newLeft: Int, newTop: Int) {
        val w = right - left
        val h = bottom - top
        left = newLeft
        top = newTop
        right = newLeft + w
        bottom = newTop + h
    }

    // --- 带脏标记的变换设置器 (对应 Poweramp s.java) ---

    fun setScaleX(value: Float) {
        if (scaleX == value) return
        if (Math.abs(value) <= Float.MAX_VALUE && value >= 0.0f) {
            scaleX = value
            dirtyBits = dirtyBits or DIRTY_SCALE_X
        }
    }

    fun setScaleY(value: Float) {
        if (scaleY == value) return
        if (Math.abs(value) <= Float.MAX_VALUE && value >= 0.0f) {
            scaleY = value
            dirtyBits = dirtyBits or DIRTY_SCALE_Y
        }
    }

    fun setAlpha(value: Float) {
        if (alpha == value) return
        alpha = value
        dirtyBits = dirtyBits or DIRTY_ALPHA
    }

    /** 设置 sceneId 并标记脏位 (Poweramp 的 H(int)) */
    fun H(value: Int) {
        if (sceneId != value) {
            sceneId = value
            dirtyBits = dirtyBits or DIRTY_SCENE
        }
    }
    fun setSceneId(value: Int) = H(value)

    /**
     * 一次性设置 scale + scene (Poweramp 的 y(alpha, scale, scene))。
     * 重置 rotation 为 0。
     */
    fun setTransform(alpha: Float, scale: Float, scene: Int) {
        var bits = dirtyBits
        if (sceneId != scene) { sceneId = scene; bits = bits or DIRTY_SCENE }
        if (this.alpha != alpha) { this.alpha = alpha; bits = bits or DIRTY_ALPHA }
        if (scaleY != scale) { scaleY = scale; bits = bits or DIRTY_SCALE_Y }
        if (scaleX != scale) { scaleX = scale; bits = bits or DIRTY_SCALE_X }
        if (rotation != 0.0f) { rotation = 0.0f; bits = bits or DIRTY_ROTATION }
        if (rotationX != 0.0f) { rotationX = 0.0f; bits = bits or DIRTY_ROTATION_X }
        if (rotationY != 0.0f) { rotationY = 0.0f; bits = bits or DIRTY_ROTATION_Y }
        dirtyBits = bits
    }

    // --- 状态查询 ---

    /**
     * 检查此槽位是否无效 (Poweramp 的 A())。
     * Poweramp 测试 left 和 right 是否为 Integer.MIN_VALUE。
     */
    fun isTranslationZero(): Boolean = left == Int.MIN_VALUE && right == Int.MIN_VALUE

    // --- 复制和重置 ---

    /** 重置为零/单位值，所有脏位 (Poweramp 的 X()) */
    fun reset() {
        left = 0; top = 0; right = 0; bottom = 0
        scaleX = 1.0f; scaleY = 1.0f
        rotation = 0.0f; rotationX = 0.0f; rotationY = 0.0f
        sceneId = 0; alpha = 1.0f
        dirtyBits = ALL_DIRTY
    }

    /** 从另一个槽位复制所有字段 (Poweramp 的 s.m2995())。 */
    fun copyFrom(other: ComposeItemPosition) {
        left = other.left; top = other.top; right = other.right; bottom = other.bottom
        scaleX = other.scaleX; scaleY = other.scaleY
        rotation = other.rotation; rotationX = other.rotationX; rotationY = other.rotationY
        alpha = other.alpha; sceneId = other.sceneId
        dirtyBits = other.dirtyBits
    }

    /** 使无效为 MIN_VALUE (Poweramp 的 m2994()) */
    fun invalidate() {
        left = Int.MIN_VALUE; top = Int.MIN_VALUE
        right = Int.MIN_VALUE; bottom = Int.MIN_VALUE
        dirtyBits = ALL_DIRTY
    }
}
