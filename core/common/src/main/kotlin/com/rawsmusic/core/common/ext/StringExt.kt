package com.rawsmusic.core.common.ext

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Long.formatDate(pattern: String = "yyyy-MM-dd"): String {
    val sdf = SimpleDateFormat(pattern, Locale.getDefault())
    return sdf.format(Date(this))
}

fun Long.formatDateTime(pattern: String = "yyyy-MM-dd HH:mm:ss"): String {
    val sdf = SimpleDateFormat(pattern, Locale.getDefault())
    return sdf.format(Date(this))
}

fun String?.isNotNullOrBlank(): Boolean = !this.isNullOrBlank()

fun String?.isNotNullOrEmpty(): Boolean = !this.isNullOrEmpty()
