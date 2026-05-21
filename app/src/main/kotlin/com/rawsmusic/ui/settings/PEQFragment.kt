package com.rawsmusic.ui.settings

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.rawsmusic.module.player.dsp.ParametricEQController
import java.io.BufferedReader
import java.io.InputStreamReader

class PEQFragment : Fragment() {

    private var peqController: ParametricEQController? = null
    private var pendingExportJson by mutableStateOf<String?>(null)
    private var importedFileContent by mutableStateOf<String?>(null)

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { writeJsonToUri(it, pendingExportJson ?: "") }
        pendingExportJson = null
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { content ->
            importedFileContent = readJsonFromUri(content)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val controller = try {
            val activity = requireActivity() as? com.rawsmusic.MainActivity
            val playerController = activity?.playerController
            playerController?.ensurePEQConnected()
            playerController?.peqController
        } catch (e: Exception) {
            Log.e("PEQFragment", "Failed to get PEQ controller", e)
            null
        }
        peqController = controller

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                if (controller != null) {
                    LiquidGlassPEQScreen(
                        peqController = controller,
                        onBack = {
                            try {
                                NavHostFragment.findNavController(this@PEQFragment).navigateUp()
                            } catch (_: Exception) {}
                        },
                        onExportToFile = { json ->
                            pendingExportJson = json
                            val timestamp = System.currentTimeMillis()
                            exportLauncher.launch("PEQ_preset_$timestamp.peq.json")
                        },
                        onImportFromFile = {
                            importLauncher.launch(arrayOf("application/json", "*/*"))
                        },
                        importedFileContent = importedFileContent,
                        onImportedFileContentConsumed = {
                            importedFileContent = null
                        }
                    )
                }
            }
        }
    }

    private fun writeJsonToUri(uri: Uri, json: String) {
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { os ->
                os.write(json.toByteArray(Charsets.UTF_8))
            }
            android.widget.Toast.makeText(
                requireContext(), "预设已保存", android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Log.e("PEQFragment", "Failed to write preset file", e)
            android.widget.Toast.makeText(
                requireContext(), "保存失败: ${e.message}", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun readJsonFromUri(uri: Uri): String? {
        return try {
            requireContext().contentResolver.openInputStream(uri)?.use { is_ ->
                BufferedReader(InputStreamReader(is_, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }
        } catch (e: Exception) {
            Log.e("PEQFragment", "Failed to read preset file", e)
            android.widget.Toast.makeText(
                requireContext(), "读取失败: ${e.message}", android.widget.Toast.LENGTH_SHORT
            ).show()
            null
        }
    }
}
