/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

@file:Suppress("unused", "MemberVisibilityCanBePrivate")
@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package io.github.proify.lyricon.provider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.os.Parcelable
import androidx.annotation.DrawableRes
import androidx.annotation.Px
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toBitmap
import io.github.proify.lyricon.provider.ProviderLogo.Companion.TYPE_BITMAP
import io.github.proify.lyricon.provider.ProviderLogo.Companion.TYPE_SVG
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import kotlin.io.encoding.Base64

@Serializable
@Parcelize
data class ProviderLogo(
    val data: ByteArray,
    val type: Int,
    val colorful: Boolean = false
) : Parcelable {

    fun toBitmap(): Bitmap? = if (type == TYPE_BITMAP) {
        runCatching {
            BitmapFactory.decodeByteArray(
                data, 0, data.size,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Config.ARGB_8888
                }
            )
        }.getOrNull()
    } else null

    fun toSvg(): String? = if (type == TYPE_SVG) data.toString(Charsets.UTF_8) else null

    companion object {
        const val TYPE_BITMAP: Int = 0
        const val TYPE_SVG: Int = 1

        fun fromBitmap(bitmap: Bitmap, recycle: Boolean = true): ProviderLogo =
            ProviderLogo(bitmap.toPngBytes(recycle), TYPE_BITMAP)

        fun fromDrawable(
            drawable: Drawable,
            @Px width: Int = drawable.intrinsicWidth,
            @Px height: Int = drawable.intrinsicHeight,
            config: Config? = null,
        ): ProviderLogo =
            fromBitmap(drawable.toBitmap(width, height, config))

        fun fromDrawable(
            context: Context,
            @DrawableRes id: Int,
            @Px width: Int = -1,
            @Px height: Int = -1,
            config: Config? = null,
        ): ProviderLogo {
            val drawable = AppCompatResources.getDrawable(context, id)
            require(drawable != null) { "Drawable not found" }
            return if (width > 0 && height > 0) fromBitmap(drawable.toBitmap(width, height, config))
            else fromBitmap(drawable.toBitmap(config = config))
        }

        fun fromSvg(svg: String): ProviderLogo =
            ProviderLogo(svg.toByteArray(Charsets.UTF_8), TYPE_SVG)

        fun fromBase64(base64: String): ProviderLogo =
            ProviderLogo(Base64.decode(base64), TYPE_BITMAP)

        private fun Bitmap.toPngBytes(recycle: Boolean): ByteArray =
            ByteArrayOutputStream().use { out ->
                compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }.also { if (recycle) recycle() }

        internal fun typeName(type: Int): String =
            when (type) {
                TYPE_BITMAP -> "Bitmap"
                TYPE_SVG -> "SVG"
                else -> "Unknown"
            }
    }

    override fun toString(): String =
        "ProviderLogo(type=${typeName(type)}, colorful=$colorful, data=${data.size} bytes)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProviderLogo

        if (type != other.type) return false
        if (colorful != other.colorful) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + colorful.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
