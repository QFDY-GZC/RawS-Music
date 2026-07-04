package com.rawsmusic.helper

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.ContextCompat
import com.rawsmusic.R

fun createShuffleIconWithDots(
    context: Context,
    size: Int,
    showVerticalDots: Boolean,
    showHorizontalDots: Boolean,
    density: Float
): BitmapDrawable {
    val resources = context.resources
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.white_full) }

    val iconSize = (size * 0.8f).toInt()
    val shuffleDrawable = ContextCompat.getDrawable(context, R.drawable.ic_shuffle)!!
    shuffleDrawable.setTint(ContextCompat.getColor(context, R.color.white_full))

    val offsetX = if (showVerticalDots) size * 0.06f else 0f
    val offsetY = if (showHorizontalDots) -size * 0.03f else 0f

    val left = (size - iconSize) / 2f + offsetX
    val top = (size - iconSize) / 2f + offsetY
    shuffleDrawable.setBounds(left.toInt(), top.toInt(), (left + iconSize).toInt(), (top + iconSize).toInt())
    shuffleDrawable.draw(canvas)

    if (showVerticalDots) {
        paint.style = Paint.Style.FILL
        val dotRadius = resources.getDimension(R.dimen.dot_radius)
        val dotSpacing = resources.getDimension(R.dimen.dot_spacing)
        val dotStartY = size / 2f - dotSpacing
        val dotX = resources.getDimension(R.dimen.dot_start_offset) + dotRadius
        for (i in 0..2) {
            canvas.drawCircle(dotX, dotStartY + i * dotSpacing, dotRadius, paint)
        }
    }

    if (showHorizontalDots) {
        paint.style = Paint.Style.FILL
        val dotRadius = resources.getDimension(R.dimen.dot_radius)
        val dotSpacing = resources.getDimension(R.dimen.dot_spacing)
        val dotStartX = size / 2f - dotSpacing
        val dotY = size - resources.getDimension(R.dimen.dot_start_offset) - dotRadius
        for (i in 0..2) {
            canvas.drawCircle(dotStartX + i * dotSpacing, dotY, dotRadius, paint)
        }
    }

    return BitmapDrawable(resources, bitmap)
}
