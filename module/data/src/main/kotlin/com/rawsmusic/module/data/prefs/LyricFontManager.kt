package com.rawsmusic.module.data.prefs

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object LyricFontManager {

    private const val LYRIC_FONTS_DIR = "lyric_fonts"

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

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
            val displayName = getFileNameFromUri(context, uri)
                ?: "imported_${System.currentTimeMillis()}.ttf"
            val sourceExtension = displayName.substringAfterLast('.', missingDelimiterValue = "")
                .lowercase(Locale.ROOT)
                .takeIf { it in FONT_EXTENSIONS }
                ?: "ttf"
            val sourceBaseName = displayName.substringBeforeLast('.', missingDelimiterValue = displayName)
                .replace(Regex("[\\/:*?\"<>|\u0000-\u001F]"), "_")
                .trim('_', '.', '-')
                .ifBlank { "imported" }
            val dir = getImportedFontsDir(context)
            if (!dir.exists() && !dir.mkdirs()) return null

            // Keep a unique destination. Replacing a file at the same path can leave Android or
            // Compose holding a stale Typeface cache entry after importing a different font.
            val preferredFile = File(dir, "$sourceBaseName.$sourceExtension")
            val destFile = if (preferredFile.exists()) {
                File(dir, "${sourceBaseName}_${System.currentTimeMillis()}.$sourceExtension")
            } else {
                preferredFile
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            } ?: return null

            Typeface.createFromFile(destFile)
            FontInfo(destFile.nameWithoutExtension, destFile.absolutePath, false).also {
                bumpRevision()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun selectFont(font: FontInfo?) {
        AppPreferences.LyricFont.fontPath = font?.path.orEmpty()
        AppPreferences.LyricFont.fontName = font?.name.orEmpty()
        bumpRevision()
    }

    fun setFontWeight(weight: Int) {
        AppPreferences.LyricFont.fontWeight = weight
        bumpRevision()
    }

    fun setFontScale(scale: Int) {
        AppPreferences.LyricFont.fontScale = scale
        bumpRevision()
    }

    fun deleteImportedFont(context: Context, path: String): Boolean {
        val file = File(path)
        val dir = getImportedFontsDir(context)
        if (file.absolutePath.startsWith(dir.absolutePath) && file.exists()) {
            val deleted = file.delete()
            if (deleted) {
                if (AppPreferences.LyricFont.fontPath == path) {
                    AppPreferences.LyricFont.fontPath = ""
                    AppPreferences.LyricFont.fontName = ""
                }
                bumpRevision()
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

    @Synchronized
    private fun bumpRevision() {
        _revision.value = _revision.value + 1L
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
