package com.rawsmusic.module.data.prefs

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

object LyricFontManager {

    private const val LYRIC_FONTS_DIR = "lyric_fonts"

    private val SYSTEM_FONT_DIRS = listOf(
        "/system/fonts",
        "/product/fonts",
        "/system_ext/fonts",
        "/vendor/fonts"
    )

    private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")

    data class FontInfo(
        val name: String,
        val path: String,
        val isSystem: Boolean
    )

    fun getSystemFonts(): List<FontInfo> {
        val fonts = mutableListOf<FontInfo>()
        val seen = mutableSetOf<String>()

        for (dir in SYSTEM_FONT_DIRS) {
            val folder = File(dir)
            if (!folder.exists() || !folder.isDirectory) continue

            folder.listFiles()?.forEach { file ->
                val ext = file.extension.lowercase(Locale.ROOT)
                if (ext in FONT_EXTENSIONS) {
                    val name = file.nameWithoutExtension
                    if (name !in seen) {
                        seen.add(name)
                        fonts.add(FontInfo(name, file.absolutePath, true))
                    }
                }
            }
        }

        return fonts.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun getImportedFonts(context: Context): List<FontInfo> {
        val dir = getImportedFontsDir(context)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        return dir.listFiles()
            ?.filter { it.extension.lowercase(Locale.ROOT) in FONT_EXTENSIONS }
            ?.map { FontInfo(it.nameWithoutExtension, it.absolutePath, false) }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            ?: emptyList()
    }

    fun importFont(context: Context, uri: Uri): FontInfo? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val displayName = getFileNameFromUri(context, uri) ?: "imported_${System.currentTimeMillis()}.ttf"
            val sanitizedName = displayName.replace(Regex("[^a-zA-Z0-9_.\\-]"), "_")
            val dir = getImportedFontsDir(context)
            if (!dir.exists()) dir.mkdirs()

            val destFile = File(dir, sanitizedName)
            if (destFile.exists()) destFile.delete()

            FileOutputStream(destFile).use { out ->
                inputStream.copyTo(out)
            }
            inputStream.close()

            Typeface.createFromFile(destFile)

            FontInfo(destFile.nameWithoutExtension, destFile.absolutePath, false)
        } catch (e: Exception) {
            null
        }
    }

    fun deleteImportedFont(context: Context, path: String): Boolean {
        val file = File(path)
        val dir = getImportedFontsDir(context)
        if (file.absolutePath.startsWith(dir.absolutePath) && file.exists()) {
            val deleted = file.delete()
            if (deleted && AppPreferences.LyricFont.fontPath == path) {
                AppPreferences.LyricFont.fontPath = ""
                AppPreferences.LyricFont.fontName = ""
            }
            return deleted
        }
        return false
    }

    fun getTypefaceForPath(path: String): Typeface? {
        if (path.isBlank()) return null
        return try {
            Typeface.createFromFile(path)
        } catch (_: Exception) {
            null
        }
    }

    fun getLyricTypeface(): Typeface? {
        val path = AppPreferences.LyricFont.fontPath
        return getTypefaceForPath(path)
    }

    fun getSelectedFontInfo(): FontInfo? {
        val path = AppPreferences.LyricFont.fontPath
        val name = AppPreferences.LyricFont.fontName
        if (path.isBlank()) return null
        return FontInfo(name, path, !path.contains(LYRIC_FONTS_DIR))
    }

    private fun getImportedFontsDir(context: Context): File {
        return File(context.filesDir, LYRIC_FONTS_DIR)
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var name: String? = null
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    name = it.getString(nameIndex)
                }
            }
        }
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment?.substringAfterLast("/")
        }
        return name
    }
}
