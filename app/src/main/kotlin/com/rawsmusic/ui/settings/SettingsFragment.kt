package com.rawsmusic.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.FontManager

class SettingsFragment : Fragment() {

    private val folderPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}

        val realPath = extractRealPathFromUri(uri)
        if (realPath.isNullOrBlank()) {
            Toast.makeText(requireContext(), "无法识别该文件夹路径", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        val current = AppPreferences.UI.scanPaths.toMutableList()
        if (realPath !in current) {
            current.add(realPath)
            AppPreferences.UI.scanPaths = current
        }
    }

    private val fontPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {}

        val realPath = extractRealPathFromUri(uri)
        if (realPath.isNullOrBlank()) {
            val fileName = uri.lastPathSegment?.substringAfterLast("/") ?: "未知字体"
            AppPreferences.UI.customFontPath = fileName
            FontManager.init(requireContext())
            Toast.makeText(requireContext(), "已选择字体: $fileName", Toast.LENGTH_SHORT).show()
        } else {
            AppPreferences.UI.customFontPath = realPath
            FontManager.init(requireContext())
            Toast.makeText(requireContext(), "已选择字体: ${realPath.substringAfterLast("/")}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LiquidGlassSettingsScreen(
                    onNavigateToAudioSettings = {
                        try {
                            androidx.navigation.fragment.NavHostFragment.findNavController(this@SettingsFragment)
                                .navigate(com.rawsmusic.R.id.nav_audio_settings)
                        } catch (_: Exception) {}
                    },
                    onPickFolder = {
                        folderPicker.launch(null)
                    },
                    onPickFont = {
                        fontPicker.launch(arrayOf("*/*"))
                    }
                )
            }
        }
    }

    private fun extractRealPathFromUri(uri: android.net.Uri): String? {
        val encodedPath = uri.encodedPath ?: uri.path
        if (encodedPath != null) {
            val segments = encodedPath.split("/").filter { it.isNotBlank() }
            val treeIdx = segments.indexOf("tree")
            if (treeIdx >= 0 && treeIdx + 1 < segments.size) {
                val part = java.net.URLDecoder.decode(segments[treeIdx + 1], "UTF-8")
                if (part.contains(":")) {
                    val colonIdx = part.indexOf(":")
                    val storage = part.substring(0, colonIdx)
                    val folder = part.substring(colonIdx + 1)
                    return when (storage) {
                        "primary" -> "/storage/emulated/0/$folder".trimEnd('/')
                        else -> "/storage/$storage/$folder".trimEnd('/')
                    }
                }
                return "/$part".trimEnd('/')
            }
        }

        try {
            val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(requireContext(), uri)
            if (docFile != null && docFile.exists()) {
                return docFile.name?.let { "/storage/emulated/0/$it" }
            }
        } catch (_: Exception) {}

        return null
    }
}
