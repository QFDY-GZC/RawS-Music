package com.rawsmusic.ui.settings.powerlist

import android.view.View

/** Core view positioning equivalent to Poweramp's B.m1209(). */
object ViewPositioner {

    @JvmStatic
    fun applyPowerampTransform(
        holder: ViewHolder,
        f: Float,
        z: Boolean,
        slot: Int,
        engine: GridListEngine?,
        z2: Boolean,
        z3: Boolean,
        otherSlot: Int = if (slot == 0) 1 else 0
    ) {
        val primary = holder.getPositionData(slot) ?: return
        if (primary.isTranslationZero()) return
        val view = holder.view ?: return
        val other = if (z3) holder.getPositionData(otherSlot) else null
        val f2 = if (z2) f else 1f - f

        if (other != null && !other.isTranslationZero()) {
            applyDualSlot(view, primary, other, f, f2, z2)
            return
        }

        val renderPrimary = ItemPosition().also { it.copyFrom(primary) }
        engine?.computeTransitionTransform(renderPrimary, holder, f2, z2 == z)
        applySingleSlot(view, renderPrimary)
        primary.dirtyBits = 0
    }

    @JvmStatic
    fun applyItemTransform(
        holder: ViewHolder,
        f: Float,
        z: Boolean,
        slot: Int,
        interpolateSlots: Boolean,
        isTargetPrimary: Boolean,
        otherSlot: Int = if (slot == 0) 1 else 0
    ) {
        val primary = holder.getPositionData(slot) ?: return
        if (primary.isTranslationZero()) return
        val view = holder.view ?: return
        val other = if (interpolateSlots) holder.getPositionData(otherSlot) else null
        val f2 = if (isTargetPrimary) f else 1f - f

        if (other != null && !other.isTranslationZero()) {
            applyDualSlot(view, primary, other, f, f2, isTargetPrimary)
        } else {
            applySingleSlot(view, primary)
        }
    }

    private fun applyDualSlot(
        view: View,
        primary: ItemPosition,
        other: ItemPosition,
        sceneProgress: Float,
        f2: Float,
        z2: Boolean
    ) {
        val sceneItem = view as? PowerListSceneItem
        val sceneChanged = primary.sceneId != other.sceneId
        if (sceneChanged && sceneItem != null) {
            val primaryScene = if (z2) primary.sceneId else other.sceneId
            val sceneToPrepare = if (z2) other.sceneId else primary.sceneId
            val sceneW = if (z2) other.width() else primary.width()
            val sceneH = if (z2) other.height() else primary.height()
            sceneItem.syncPrimaryScene(primaryScene)
            if (sceneItem.L() != sceneToPrepare) {
                sceneItem.G0(sceneToPrepare, sceneW, sceneH)
            }
            primary.dirtyBits = primary.dirtyBits and ItemPosition.DIRTY_SCENE.inv()
        }

        val w = lerpInt(f2, primary.width(), other.width()).coerceAtLeast(1)
        val h = lerpInt(f2, primary.height(), other.height()).coerceAtLeast(1)
        if (sceneChanged && !(view.width == 0 && view.height == 0)) {
            setViewBounds(view, 0, 0, w, h, relayoutChildren = false)
        } else {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            setViewBounds(view, 0, 0, w, h)
        }

        if (sceneChanged && sceneItem != null) {
            sceneItem.mo2931(sceneProgress)
        }

        // Poweramp B.m1209 uses Utils.x(int,int,int) for translation, i.e. integer interpolation.
        view.translationX = lerpInt(f2, primary.left, other.left).toFloat()
        view.translationY = lerpInt(f2, primary.top, other.top).toFloat()
        view.scaleX = lerpFloat(f2, primary.scaleX, other.scaleX)
        view.scaleY = lerpFloat(f2, primary.scaleY, other.scaleY)
        view.rotation = lerpFloat(f2, primary.rotation, other.rotation)
        view.rotationX = lerpFloat(f2, primary.rotationX, other.rotationX)
        view.rotationY = lerpFloat(f2, primary.rotationY, other.rotationY)
        view.alpha = lerpFloat(f2, primary.alpha, other.alpha)
    }

    private fun applySingleSlot(view: View, primary: ItemPosition) {
        val bits = primary.dirtyBits
        val w = primary.width().coerceAtLeast(1)
        val h = primary.height().coerceAtLeast(1)

        val sceneItem = view as? PowerListSceneItem
        if ((bits and ItemPosition.DIRTY_SCENE) != 0 && sceneItem != null) {
            sceneItem.R0(primary.sceneId, w, h)
        }
        if ((bits and (ItemPosition.DIRTY_SIZE or ItemPosition.DIRTY_SCENE)) != 0 || view.width <= 0 || view.height <= 0) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            setViewBounds(view, 0, 0, w, h)
        }

        view.translationX = primary.left.toFloat()
        view.translationY = primary.top.toFloat()
        // The same physical View may have been rendered with a temporary one-sided
        // transition slot in the previous frame. Apply transform fields unconditionally
        // so the normal/final slot restores alpha/scale even when its dirty bits are clean.
        view.scaleX = primary.scaleX
        view.scaleY = primary.scaleY
        view.rotation = primary.rotation
        view.rotationX = primary.rotationX
        view.rotationY = primary.rotationY
        view.alpha = primary.alpha
        primary.dirtyBits = 0
    }

    private fun setViewBounds(view: View, l: Int, t: Int, r: Int, b: Int, relayoutChildren: Boolean = true) {
        if (!relayoutChildren && android.os.Build.VERSION.SDK_INT >= 22) {
            view.setLeftTopRightBottom(l, t, r, b)
            return
        }
        // AAItemView is a real ViewGroup: when no scene transition owns the children,
        // use layout() so content is placed after each measured size change.
        view.layout(l, t, r, b)
    }

    private fun lerpInt(f: Float, from: Int, to: Int): Int {
        return Math.round(f * (to - from)) + from
    }

    private fun lerpFloat(f: Float, from: Float, to: Float): Float {
        return ((to - from) * f) + from
    }
}
