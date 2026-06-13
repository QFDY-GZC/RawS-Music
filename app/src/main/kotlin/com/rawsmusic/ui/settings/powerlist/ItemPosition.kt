package com.rawsmusic.ui.settings.powerlist

/**
 * 1:1 equivalent to Poweramp's s.java (com.maxmpz.widget.list.s).
 *
 * Represents the position, transform, and animation state of a list item.
 * Two instances per ViewHolder: slot 0 = source (current layout), slot 1 = target (destination).
 *
 * Field mapping to Poweramp s.java:
 *   f4239 → left      (Int) - also used as translationX offset via m2999/m3001
 *   B     → top       (Int) - also used as translationY offset via m2999/m3001
 *   f4238 → right     (Int)
 *   A     → bottom    (Int)
 *   f4242 → scaleX    (Float)
 *   X     → scaleY    (Float)
 *   f4240 → alpha     (Float)
 *   x     → rotation  (Float)
 *   y     → rotationX (Float)
 *   f4243 → rotationY (Float)
 *   f4244 → sceneId   (Int)
 *   K     → dirtyBits (Int) - bitmask for selective property updates in B.m1209()
 */
class ItemPosition {
    // --- Rect fields (Int, matching Poweramp) ---
    var left: Int = 0       // f4239
    var top: Int = 0        // B
    var right: Int = 0      // f4238
    var bottom: Int = 0     // A

    // --- Transform fields (Float, matching Poweramp) ---
    @JvmField var scaleX: Float = 1.0f    // f4242
    @JvmField var scaleY: Float = 1.0f    // X
    @JvmField var alpha: Float = 1.0f     // f4240
    @JvmField var rotation: Float = 0.0f  // x
    @JvmField var rotationX: Float = 0.0f // y
    @JvmField var rotationY: Float = 0.0f // f4243

    // --- Scene + dirty tracking (Int, matching Poweramp) ---
    /** Scene ID for this position (Poweramp's f4244). Set via H(). */
    @JvmField var sceneId: Int = 0        // f4244

    /**
     * Dirty bitmask (Poweramp's K).
     * Each bit marks a property as changed, enabling selective updates in B.m1209().
     * Bit 0 (1)       = scaleX changed
     * Bit 1 (2)       = scaleY changed
     * Bit 2 (4)       = rotation changed
     * Bit 3 (8)       = rotationX changed
     * Bit 4 (16)      = rotationY changed
     * Bit 5 (32)      = alpha changed
     * Bit 7 (128)    = sceneId changed (FLAG_TITLE_FONT_BOLD in Poweramp)
     */
    var dirtyBits: Int = ALL_DIRTY

    companion object {
        /** All bits dirty — initial state, forces full update on first render. */
        const val ALL_DIRTY = 0xFFFFF // 1048575 = (1 << 20) - 1

        // Individual dirty bit constants
        const val DIRTY_SCALE_X = 1
        const val DIRTY_SCALE_Y = 2
        const val DIRTY_ROTATION = 4
        const val DIRTY_ROTATION_X = 8
        const val DIRTY_ROTATION_Y = 16
        const val DIRTY_ALPHA = 32
        const val DIRTY_SIZE = 64
        const val DIRTY_SCENE = 128     // FLAG_TITLE_FONT_BOLD = 0x80
    }

    // --- Rect accessors (matching Poweramp s.java methods) ---

    /** Width: right - left (Poweramp's m2998()) */
    fun width(): Int = right - left

    /** Height: bottom - top (Poweramp's B()) */
    fun height(): Int = bottom - top

    /** Set rect (Poweramp's m3000(l, t, r, b)). Does not modify dirty bits. */
    fun set(left: Int, top: Int, right: Int, bottom: Int) {
        if (width() != (right - left) || height() != (bottom - top)) {
            dirtyBits = dirtyBits or DIRTY_SIZE
        }
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    /** Offset rect by dx, dy (Poweramp's m2999(dx, dy)) */
    fun offset(dx: Int, dy: Int) {
        left += dx
        top += dy
        right += dx
        bottom += dy
    }

    /** Reposition rect to new left/top, keeping width/height (Poweramp's m3001(left, top)) */
    fun moveTo(newLeft: Int, newTop: Int) {
        val w = right - left
        val h = bottom - top
        left = newLeft
        top = newTop
        right = newLeft + w
        bottom = newTop + h
    }

    // --- Transform setters with dirty tracking (matching Poweramp s.java) ---

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

    /** Set sceneId with dirty tracking (Poweramp's H(int)) */
    fun H(value: Int) {
        if (sceneId != value) {
            sceneId = value
            dirtyBits = dirtyBits or DIRTY_SCENE
        }
    }
    // Keep Kotlin-friendly alias
    fun setSceneId(value: Int) = H(value)

    /**
     * Set scale + scene in one call (Poweramp's y(alpha, scale, scene)).
     * Resets rotation to 0.
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

    // --- State queries ---

    /**
     * Check if this slot is invalid (Poweramp's A()).
     * Poweramp tests left and right against Integer.MIN_VALUE.
     */
    fun isTranslationZero(): Boolean = left == Int.MIN_VALUE && right == Int.MIN_VALUE

    // --- Copy and reset ---

    /** Reset to zero/identity with all dirty bits (Poweramp's X()) */
    fun reset() {
        left = 0
        top = 0
        right = 0
        bottom = 0
        scaleX = 1.0f
        scaleY = 1.0f
        rotation = 0.0f
        rotationX = 0.0f
        rotationY = 0.0f
        sceneId = 0
        alpha = 1.0f
        dirtyBits = ALL_DIRTY
    }

    /** Copy all fields from another slot (Poweramp's s.m2995()). */
    fun copyFrom(other: ItemPosition) {
        left = other.left
        top = other.top
        right = other.right
        bottom = other.bottom
        scaleX = other.scaleX
        scaleY = other.scaleY
        rotation = other.rotation
        rotationX = other.rotationX
        rotationY = other.rotationY
        alpha = other.alpha
        sceneId = other.sceneId
        dirtyBits = other.dirtyBits
    }

    /** Invalidate to MIN_VALUE (Poweramp's m2994()) */
    fun invalidate() {
        left = Int.MIN_VALUE
        top = Int.MIN_VALUE
        right = Int.MIN_VALUE
        bottom = Int.MIN_VALUE
        dirtyBits = ALL_DIRTY
    }

    override fun toString(): String {
        return "ItemPosition@${Integer.toHexString(hashCode())} " +
            "dirtyBits=0x${Integer.toHexString(dirtyBits)} " +
            "rect=($left,$top,$right,$bottom) w=${right-left} h=${bottom-top} " +
            "alpha=$alpha scaleX=$scaleX scaleY=$scaleY " +
            "rot=$rotation rotX=$rotationX rotY=$rotationY " +
            "scene=0x${Integer.toHexString(sceneId)}"
    }
}
