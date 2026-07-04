package com.rawsmusic.ui.settings

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rawsmusic.module.player.dsp.ParametricEQController
import java.io.BufferedReader
import java.io.InputStreamReader

class PEQActivity : BaseSettingsActivity() {

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
        uri?.let { importedFileContent = readJsonFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = try {
            playerController?.ensurePEQConnected()
            playerController?.peqController
        } catch (e: Exception) {
            Log.e("PEQActivity", "Failed to get PEQ controller", e)
            null
        }
        peqController = controller

        setContent {
            if (controller != null) {
                LiquidGlassPEQScreen(
                    peqController = controller,
                    onBack = { finish() },
                    onExportToFile = { json ->
                        pendingExportJson = json
                        exportLauncher.launch("PEQ_preset_${System.currentTimeMillis()}.peq.json")
                    },
                    onImportFromFile = {
                        importLauncher.launch(arrayOf("application/json", "*/*"))
                    },
                    importedFileContent = importedFileContent,
                    onImportedFileContentConsumed = { importedFileContent = null }
                )
            }
        }
    }

    private fun writeJsonToUri(uri: Uri, json: String) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            android.widget.Toast.makeText(this, "预设已保存", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("PEQActivity", "Failed to export", e)
            android.widget.Toast.makeText(this, "保存失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun readJsonFromUri(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            }
        } catch (e: Exception) {
            Log.e("PEQActivity", "Failed to import", e)
            android.widget.Toast.makeText(this, "读取失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            null
        }
    }
}
