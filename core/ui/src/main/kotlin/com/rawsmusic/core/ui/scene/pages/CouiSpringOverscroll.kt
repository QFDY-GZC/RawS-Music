package com.rawsmusic.core.ui.scene.pages

import android.view.ViewConfiguration
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * 1:1 port of the vertical list physics of COUIRecyclerView (ColorOS Settings 17 smali):
 *   androidx/recyclerview/widget/COUIRecyclerView (+ $ViewFlinger)
 *   com/coui/appcompat/scroll/SpringOverScroller (+ $ReboundOverScroller, $ReboundConfig)
 *   com/coui/appcompat/animation/COUIPhysicalAnimationUtil, COUISpringInterpolator
 *
 * Sign convention inside this file is the COUI one: scrollY < 0 means the top is pulled down,
 * scrollY > 0 means the bottom is pushed up; a positive scroll delta / velocity moves the content up.
 * Quirks of the original (pushing back uses scrollX, RK4's first slope without friction and
 * with 0.167 instead of 1/6, int truncation per event) are reproduced on purpose.
 */
internal object CouiScrollPhysics {
    /** RK4 weight: (double) 0.167f. */
    const val RK4_WEIGHT = 0.16699999570846558

    /** ReboundConfig.tensionFromOrigamiValue(mSpringBackTensionMultiple 2.15f * 16f). */
    val SpringBackTension: Double = ((2.15f * 16.0f - 30f) * 3.62f + 194f).toDouble()
    /** ReboundConfig.frictionFromOrigamiValue(SpringOverScroller.mSpringBackFriction 12.19f). */
    val SpringBackFriction: Double = ((12.19f - 8f) * 3f + 25f).toDouble()
    /** frictionFromOrigamiValue(sCouiFlingFrictionNormal 0.2f) — animation level 1 / unset. */
    val FlingFriction: Double = ((0.2f - 8f) * 3f + 25f).toDouble()
    /** sMidFlingBaseFriction / sSlowFlingBaseFriction, assigned without origami conversion. */
    const val FLING_FRICTION_SLOW_MID = 2.5

    const val REVERSE_FLING_VELOCITY = 6000f
    const val OVERFLING_SCREEN_FACTOR = 0.3731f

    fun calcRealOverScrollDist(d: Int, scroll: Int, range: Int): Int =
        (d.toFloat() * (1f - min(abs(scroll) * 1.0f / range.toFloat(), 1f)) / 5f * 2f).toInt()

    fun calcOverFlingDecelerateDist(base: Int, p: Int, range: Int): Int {
        val l = range.toFloat() * OVERFLING_SCREEN_FACTOR
        val num = (p.toFloat() * l).toDouble()
        val den = sqrt(((p * p).toFloat() + l * l).toDouble())
        return base + (num / den).toInt()
    }

    /** ReboundOverScroller.calculateRefreshTime. */
    fun refreshTime(frameDeltaSeconds: Float): Float {
        val dt = max(0.008f, frameDeltaSeconds)
        return if (dt > 0.025f) 0.008f else dt
    }

    fun distanceScale(v: Float): Float = when {
        v <= 2000f -> 1.0f
        v <= 6000f -> 1f - (v - 2000f) / 4000f * 0.19999999f
        v <= 10000f -> 0.8f
        v <= 20000f -> 0.8f - (v - 10000f) / 10000f * 0.4f
        else -> 0.4f
    }

    fun durationScale(v: Float): Float = when {
        v <= 2000f -> 1.3f
        v <= 6000f -> 1.3f - (v - 2000f) / 4000f * 0.49999994f
        v <= 10000f -> 0.8f
        v <= 20000f -> 0.8f - (v - 10000f) / 10000f * 0.3f
        else -> 0.5f
    }
}

/** ReboundOverScroller PhysicsState (current + temp) and its RK4 step. */
internal class CouiPhysicsState {
    var pos = 0.0
    var vel = 0.0
    var tempPos = 0.0
    var tempVel = 0.0

    fun rk4(target: Double, k: Double, c: Double, h: Double) {
        val x = pos
        val v = vel
        val a1 = (target - tempPos) * k
        val x2 = x + v * h * 0.5
        val v2 = v + a1 * h * 0.5
        val a2 = (target - x2) * k - c * v2
        val x3 = x + v2 * h * 0.5
        val v3 = v + a2 * h * 0.5
        val a3 = (target - x3) * k - c * v3
        val x4 = x + v3 * h
        val v4 = v + a3 * h
        val a4 = (target - x4) * k - c * v4
        pos = x + (v + 2.0 * (v2 + v3) + v4) * CouiScrollPhysics.RK4_WEIGHT * h
        vel = v + h * (a1 + 2.0 * (a2 + a3) + a4) * CouiScrollPhysics.RK4_WEIGHT
        tempPos = x4
        tempVel = v4
    }
}

/** ReboundOverScroller spring-back branch. */
internal class CouiSpringBack {
    val state = CouiPhysicsState()
    private var endValue = 0.0
    private var tensionAdjusted = false
    private var isSpringBack = false

    fun isAtRest(): Boolean =
        abs(state.vel) <= 20.0 && abs(endValue - state.pos) <= 0.05

    /** springBack(start, 0, 0): setCurrentValue keeps the velocity; temp position resets to 0. */
    fun start(startPos: Double): Boolean {
        state.pos = startPos
        state.tempPos = 0.0
        if (startPos == 0.0) return false
        endValue = 0.0
        isSpringBack = true
        return true
    }

    /** setAtRest. */
    fun abort() {
        endValue = state.pos
        state.tempPos = state.pos
        state.vel = 0.0
        isSpringBack = false
    }

    /** One frame of ReboundOverScroller.update() for the spring-back branch. */
    fun update(h: Double): Boolean {
        if (isAtRest()) return false
        val p0 = state.pos
        val displacement = abs(endValue - p0)
        if (!tensionAdjusted && displacement < 180.0) {
            tensionAdjusted = true
        } else if (displacement < 0.25) {
            state.pos = endValue
            tensionAdjusted = false
            isSpringBack = false
            return false
        }
        var guard = 0
        do {
            state.rk4(endValue, CouiScrollPhysics.SpringBackTension, CouiScrollPhysics.SpringBackFriction, h)
            guard++
        } while (abs(p0 - state.pos) <= 0.5 && isSpringBack && !isAtRest() && guard < 10000)
        return true
    }
}

/** COUISpringInterpolator(40.0, 1.15, velocity, 1f, 15000f, isFling = true). */
internal class CouiSpringInterpolator(velocity: Double) {
    private val w0 = sqrt(40.0)
    private val zeta = 1.15
    private val iv = min(abs(velocity), 20000.0) / 15000f.toDouble()
    private val cut = 1.0f
    private val wd = w0 * sqrt(zeta * zeta - 1.0)
    private var finalValue = -1f

    private fun origin(t: Float): Float {
        val x = (max(t, 0f) * cut).toDouble()
        val e = exp(-zeta * w0 * x) / wd
        val s = (-iv + zeta * w0) * sinh(zeta * x) + wd * cosh(zeta * x)
        return (1.0 - e * s).toFloat()
    }

    fun getInterpolation(t: Float): Float {
        if (finalValue == -1f) {
            val f = origin(1f)
            finalValue = if (f == 0f || f.isNaN()) 1f else f
        }
        return origin(t) / finalValue
    }

    fun getSpeed(t: Float): Float {
        val x = max(t, 0f).toDouble()
        val c = cut.toDouble()
        val e = exp(-c * zeta * w0 * x)
        val a = c * (wd * wd + iv * zeta * w0 - zeta * zeta * w0 * w0)
        val b = c * zeta * (zeta * w0 - iv - w0 * wd)
        return abs(e / wd * (a * sinh(c * zeta * x) + b * cosh(c * zeta * x))).toFloat()
    }
}

/**
 * ReboundOverScroller.fling: RK4 friction simulation → (distance, duration), scaled by the
 * velocity tables, then played back through COUISpringInterpolator.
 */
internal class CouiFlingPlan private constructor(
    private val start: Double,
    private val velocity: Int,
    private val distance: Int,
    private val duration: Int,
    val endValue: Int,
    private val interpolator: CouiSpringInterpolator,
) {
    var pos: Double = start
        private set
    var vel: Double = velocity.toDouble()
        private set

    /** ReboundOverScroller.update() fling branch; [elapsedMs] = now − mStartTime. */
    fun advance(elapsedMs: Float): Boolean {
        if (abs(vel) <= 20.0) return false
        val prev = pos
        val t = max(elapsedMs, 0f) / duration.toFloat()
        pos = (distance.toFloat() * interpolator.getInterpolation(t)).toDouble() + start
        vel = (interpolator.getSpeed(t) * distance.toFloat() / duration.toFloat() * 1000f).toDouble()
        if (abs(vel) < 5.0) return false
        if ((prev - endValue) * (pos - endValue) <= 0.0) {
            pos = endValue.toDouble()
            return false
        }
        if (pos.isNaN() || vel.isNaN()) return false
        return true
    }

    companion object {
        fun create(start: Int, velocity: Int, frameIntervalSeconds: Float): CouiFlingPlan? {
            val av = abs(velocity).toFloat()
            val minDelta = when {
                av <= 5000f -> 0.2
                av <= 8000f -> 0.3
                else -> 0.35
            }
            val interpolator = CouiSpringInterpolator(velocity.toDouble())
            val startD = start.toDouble()
            val dt = frameIntervalSeconds

            // calculateFinalPosition(edge, interp = false): friction-only RK4 (tension 0).
            val sim = CouiPhysicsState().apply { pos = startD; vel = velocity.toDouble() }
            var friction = CouiScrollPhysics.FlingFriction
            var count = 1
            var guard = 0
            while (guard++ < 100000) {
                val prev = sim.pos
                if (count == 1) {
                    // adjustFrictionByStartVelocity
                    if (av > 4000f && av < 10000f) {
                        friction = CouiScrollPhysics.FLING_FRICTION_SLOW_MID
                    } else if (av <= 4000f) {
                        friction = CouiScrollPhysics.FLING_FRICTION_SLOW_MID
                    }
                }
                sim.rk4(0.0, 0.0, friction, dt.toDouble())
                count++
                if (abs(sim.vel) < 5.0) break
                if (abs(sim.pos - prev) < minDelta) break
            }
            val p1 = sim.pos.toInt()
            val d1 = (count.toFloat() * dt * 1000f).toInt()
            val distance = ((p1 - startD).toFloat() * CouiScrollPhysics.distanceScale(av)).toInt()
            val duration = (d1.toFloat() * CouiScrollPhysics.durationScale(av)).toInt()
            if (duration <= 0) return null

            // calculateFinalPosition(edge, interp = true): the interpolated playback's end.
            var pos = startD
            count = 1
            guard = 0
            while (guard++ < 100000) {
                val prev = pos
                val t = count.toFloat() * dt * 1000f / duration.toFloat()
                pos = (distance.toFloat() * interpolator.getInterpolation(t)).toDouble() + startD
                val vel = (interpolator.getSpeed(t) * distance.toFloat() / duration.toFloat() * 1000f).toDouble()
                count++
                if (abs(vel) < 5.0) break
                if (abs(pos - prev) < minDelta) break
            }
            return CouiFlingPlan(startD, velocity, distance, duration, pos.toInt(), interpolator)
        }
    }
}

@Stable
class CouiSpringOverscrollState internal constructor(
    private val scope: CoroutineScope,
    /** mOverscrollDistance = mOverflingDistance = mScreenHeight = DisplayMetrics.heightPixels. */
    private val range: Int,
    private val frameIntervalSeconds: Float,
) {
    /** RecyclerView mScrollY in COUI sign. */
    var scrollY by mutableIntStateOf(0)
        private set
    /** 0 = none, 2 = overscroll (drag / spring-back), 3 = overfling (edge reached during fling). */
    private var scrollType = 0
    private val spring = CouiSpringBack()
    private var animation: Job? = null
    // Compose delivers float deltas; COUI works on int touch coordinates without losing remainders.
    private var preRemainder = 0f
    private var postRemainder = 0f

    /** Content offset for the scroll viewport, in px (positive = moved down). */
    val offsetPx: Float get() = -scrollY.toFloat()

    val isOverscrolled: Boolean get() = scrollY != 0

    /** COUIRecyclerView.overScrollBy override: clamp a sign change to 0, then onOverScrolled. */
    private fun overScrollBy(deltaY: Int) {
        val sy = scrollY
        var newY = deltaY + sy
        if ((sy < 0 && newY > 0) || (sy > 0 && newY < 0)) newY = 0
        onOverScrolled(newY)
    }

    /** COUIRecyclerView.onOverScrolled: overfling maps the raw value through the decelerate curve. */
    private fun onOverScrolled(rawNewY: Int) {
        if (rawNewY == scrollY) return
        val newY = if (scrollType == 3) {
            CouiScrollPhysics.calcOverFlingDecelerateDist(0, rawNewY, range)
        } else {
            rawNewY
        }
        scrollY = newY
    }

    /** DOWN / new drag: abortAnimation (setAtRest). */
    private fun stopAnimation() {
        val running = animation
        if (running != null) {
            running.cancel()
            animation = null
            spring.abort()
        }
    }

    /** performSpringBack → SpringOverScroller.springBack(0, scrollY, …), driven by computeScroll. */
    private fun springBack() {
        animation?.cancel()
        if (!spring.start(scrollY.toDouble())) {
            animation = null
            return
        }
        animation = scope.launch {
            var last = System.nanoTime()
            while (true) {
                val frame = withFrameNanos { it }
                val h = CouiScrollPhysics.refreshTime((frame - last) / 1e9f)
                last = frame
                val running = spring.update(h.toDouble())
                val currY = Math.round(spring.state.pos).toInt()
                overScrollBy(currY - scrollY)
                if (!running) break
            }
            spring.abort()
            scrollType = 0
            animation = null
        }
    }

    /** ViewFlinger: fling reached an edge with [unconsumed] px left and [velocity] (COUI sign). */
    internal fun onEdgeReached(unconsumed: Int, velocity: Double) {
        stopAnimation()
        scrollType = 3
        overScrollBy(unconsumed)
        // notifyEdgeReached(pos = unY, temp = (0, vel)) + springBack(unY, 0, 0) keeping the velocity.
        spring.state.vel = velocity
        spring.state.pos = unconsumed.toDouble()
        animation?.cancel()
        if (!spring.start(unconsumed.toDouble())) return
        animation = scope.launch {
            var last = System.nanoTime()
            while (true) {
                val frame = withFrameNanos { it }
                val h = CouiScrollPhysics.refreshTime((frame - last) / 1e9f)
                last = frame
                val running = spring.update(h.toDouble())
                val raw = Math.round(spring.state.pos).toInt()
                // computeScroll: overScrollBy(currY - sy) → newY = raw (sign change → 0), mapped.
                val sy = scrollY
                var newY = raw
                if ((sy < 0 && newY > 0) || (sy > 0 && newY < 0)) newY = 0
                onOverScrolled(newY)
                if (!running) break
            }
            spring.abort()
            scrollType = 0
            animation = null
        }
    }

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
            val sy = scrollY
            if (sy == 0) return Offset.Zero
            val dyFloat = -available.y
            val pushingBack = (sy < 0 && dyFloat > 0f) || (sy > 0 && dyFloat < 0f)
            if (!pushingBack) return Offset.Zero
            stopAnimation()
            preRemainder += dyFloat
            val dy = preRemainder.toInt()
            preRemainder -= dy
            scrollType = 2
            // scrollByInternal: scrollStep is skipped entirely; the original passes scrollX (0 for a
            // vertical list) as the damping position, so pushing back is a constant (int)(0.4·dy).
            val oy = CouiScrollPhysics.calcRealOverScrollDist(dy, 0, range)
            if (oy != 0) overScrollBy(oy)
            return Offset(0f, available.y)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
            stopAnimation()
            postRemainder += -available.y
            val unY = postRemainder.toInt()
            postRemainder -= unY
            scrollType = 2
            val oy = CouiScrollPhysics.calcRealOverScrollDist(unY, scrollY, range)
            if (oy != 0) overScrollBy(oy)
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            preRemainder = 0f
            postRemainder = 0f
            val sy = scrollY
            if (sy == 0) return Velocity.Zero
            // dispatchTouchEvent UP: overfling becomes overscroll.
            if (scrollType == 3) scrollType = 2
            scrollType = 2
            val vy = -available.y
            var reverse = false
            if (abs(vy) > CouiScrollPhysics.REVERSE_FLING_VELOCITY) {
                // setCurrVelocityY: the spring-back starts with the release velocity.
                spring.state.vel = vy.toDouble()
                if (sy.toFloat() * vy < 0f) reverse = true
            } else {
                spring.state.vel = 0.0
            }
            if (!reverse) {
                springBack()
                return available
            }
            // performOverScrollingReverseFling: fling from scrollY; once it reaches 0 the remaining
            // velocity is handed to a normal list fling.
            val remaining = reverseFling(sy, vy.toInt())
            return Velocity(0f, available.y + remaining)
        }
    }

    private suspend fun reverseFling(start: Int, velocity: Int): Float {
        animation?.cancel()
        animation = null
        val plan = CouiFlingPlan.create(start, velocity, frameIntervalSeconds) ?: run {
            springBack()
            return 0f
        }
        val startNanos = System.nanoTime()
        while (true) {
            val frame = withFrameNanos { it }
            val running = plan.advance(((frame - startNanos) / 1_000_000L).toFloat())
            val currY = Math.round(plan.pos).toInt()
            overScrollBy(currY - scrollY)
            if (scrollY == 0) {
                scrollType = 0
                return plan.vel.toInt().toFloat()
            }
            if (!running) {
                springBack()
                return 0f
            }
        }
    }
}

/**
 * COUIRecyclerView.fling → ViewFlinger → SpringOverScroller.fling. A fling reaching the edge
 * hands its unconsumed pixels and current velocity to [overscroll] (mScrollType 3).
 */
class CouiFlingBehavior internal constructor(
    private val overscroll: CouiSpringOverscrollState?,
    private val frameIntervalSeconds: Float,
    private val minFlingVelocity: Float,
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (abs(initialVelocity) < minFlingVelocity) return 0f
        val velocity = initialVelocity.toInt()
        val plan = CouiFlingPlan.create(0, velocity, frameIntervalSeconds) ?: return 0f
        val startNanos = System.nanoTime()
        var lastCurr = 0
        while (true) {
            val frame = withFrameNanos { it }
            val running = plan.advance(((frame - startNanos) / 1_000_000L).toFloat())
            val curr = Math.round(plan.pos).toInt()
            val delta = curr - lastCurr
            lastCurr = curr
            if (delta != 0) {
                val consumed = scrollBy(delta.toFloat()).roundToInt()
                val unconsumed = delta - consumed
                if (unconsumed != 0) {
                    overscroll?.onEdgeReached(unconsumed, plan.vel)
                    return 0f
                }
            }
            if (!running || curr == plan.endValue) return 0f
        }
    }
}

@Composable
fun rememberCouiSpringOverscrollState(): CouiSpringOverscrollState {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val range = context.resources.displayMetrics.heightPixels
    val frameInterval = rememberFrameIntervalSeconds()
    return remember(scope, range, frameInterval) {
        CouiSpringOverscrollState(scope, range, frameInterval)
    }
}

@Composable
fun rememberCouiFlingBehavior(overscroll: CouiSpringOverscrollState?): FlingBehavior {
    val context = LocalContext.current
    val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    val frameInterval = rememberFrameIntervalSeconds()
    return remember(overscroll, minFling, frameInterval) {
        CouiFlingBehavior(overscroll, frameInterval, minFling)
    }
}

/** Choreographer.getFrameIntervalNanos() / 1e6 / 1000, from the display refresh rate. */
@Composable
private fun rememberFrameIntervalSeconds(): Float {
    val refresh = LocalView.current.display?.refreshRate?.takeIf { it > 0f } ?: 60f
    return remember(refresh) { 1f / refresh }
}

/** Moves the scroll viewport by the rubber-band distance (the RecyclerView's own mScrollY). */
fun Modifier.couiOverscrollOffset(state: CouiSpringOverscrollState): Modifier =
    offset { IntOffset(0, (-state.scrollY)) }

/** Disables the platform stretch/glow so only the COUI rubber band is visible. */
@Composable
fun WithoutPlatformOverscroll(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOverscrollFactory provides null, content = content)
}
