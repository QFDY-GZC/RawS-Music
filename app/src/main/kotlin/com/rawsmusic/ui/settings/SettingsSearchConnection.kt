package com.rawsmusic.ui.settings

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Public, platform-independent version of the verified connected-surface precomputation. */
internal object SettingsSearchConnection {
    class Ball(val values: FloatArray)
    fun parameters(first: Ball, second: Ball, density: Float, axis: Int = 0): FloatArray {
        val out = FloatArray(24)
        val a = first.values
        val b = second.values
        if (axis !in 0..1 || !density.isFinite() || density <= 0f ||
            a.size != 8 || b.size != 8 || a.any { !it.isFinite() } || b.any { !it.isFinite() } ||
            minOf(a[2],a[3],b[2],b[3]) < 1f || minOf(a[6],a[7],b[6],b[7]) <= 0f) return out
        val ah = floatArrayOf(a[2]/a[6], a[3]/a[7])
        val bh = floatArrayOf(b[2]/b[6], b[3]/b[7])
        val lo = FloatArray(2) { min(a[it]-ah[it], b[it]-bh[it]) }
        val hi = FloatArray(2) { max(a[it]+ah[it], b[it]+bh[it]) }
        val union = FloatArray(2) { (hi[it]-lo[it])*.5f }
        val common = min(ah[axis],bh[axis])
        val ramp = max(20f,a[4]+b[4])
        val overlap = 2f*(ah[axis]+bh[axis]-union[axis])
        val edgeRadius = max(a[4],b[4])
        val edge = smooth(edgeRadius-ramp,edgeRadius,overlap)
        val contained = 1f-smooth(0f,ramp*.5f,union[axis]-max(ah[axis],bh[axis]))
        val crossDiff = abs(ah[1-axis]-bh[1-axis])
        val mismatch = smooth(0f,ramp*.5f,crossDiff)
        val innerA = ah.copyOf().also { it[axis] -= common }
        val innerB = bh.copyOf().also { it[axis] -= common }
        val alo = FloatArray(2) { a[it]-innerA[it] }
        val ahi = FloatArray(2) { a[it]+innerA[it] }
        val blo = FloatArray(2) { b[it]-innerB[it] }
        val bhi = FloatArray(2) { b[it]+innerB[it] }
        if ((0..1).all { ahi[it] >= blo[it] && bhi[it] >= alo[it] }) {
            out[2] = 1f
        } else {
            out[0] = (min(bhi[0],ahi[0])+max(alo[0],blo[0]))*.5f
            out[1] = (min(bhi[1],ahi[1])+max(alo[1],blo[1]))*.5f
            val delta = when {
                ahi[axis] < blo[axis] -> blo[axis]-ahi[axis]
                bhi[axis] < alo[axis] -> bhi[axis]-alo[axis]
                else -> 0f
            }
            if (abs(delta) > .0001f) out[2+axis] = delta/abs(delta)
        }
        val param = (4f*density).roundToInt().toFloat()
        val paramSum = 2f*param
        val corrected = overlap-2f/min(a[6+axis],b[6+axis])
        val gap = max(0f,-corrected)
        val gate = 1f-smooth(paramSum,paramSum+5f,gap)
        if (gate <= 0f) return FloatArray(24)
        val shape = gate*smooth(common,-paramSum,corrected)
        val adjusted = corrected+paramSum*shape
        val t = smooth(0f,26f,adjusted)
        val g = .5f*(2f*min(t,1f-t)).pow(.3f)
        val gain = if (t < .5f) g else 1f-g
        out[4] = (hi[0]+lo[0])*.5f
        out[5] = (hi[1]+lo[1])*.5f
        out[6] = union[0]
        out[7] = union[1]
        out[8] = min(a[4],b[4])
        val crossCommon = min(ah[1-axis],bh[1-axis])
        out[9] = max(.001f,gap*.5f+param*2f)
        out[10] = max(.001f,crossCommon*1.5f)
        out[11] = param*shape
        out[12] = out[9]
        out[13] = out[10]
        out[14] = out[11]
        out[15] = 1f-.15f*(1f-smooth(0f,1f,abs(-shape*paramSum-corrected)/12.5f))
        out[16] = (edge+(contained-edge)*mismatch)*(1f-smooth(0f,1f,crossDiff/5f))
        out[17] = gain*25f
        out[18] = adjusted
        out[19] = (if (adjusted > 0f) .2f else 0f)+.8f*smooth(0f,1f,adjusted/20f)
        out[20] = shape
        out[21] = 1f
        return out
    }

    internal fun smooth(a: Float, b: Float, x: Float): Float {
        if (a == b) return if (x >= b) 1f else 0f
        val t = ((x-a)/(b-a)).coerceIn(0f,1f)
        return t*t*(3f-2f*t)
    }
}
