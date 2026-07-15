package com.rawsmusic.ui.settings

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rawsmusic.module.player.dsp.GraphicEQController
import com.rawsmusic.module.player.dsp.NativeDSPEngine
import com.rawsmusic.module.player.dsp.ParametricEQController
import com.rawsmusic.module.player.dsp.SpeakerOutputElasticityController
import java.io.BufferedReader
import java.io.InputStreamReader

class AudioEffectsActivity : BaseSettingsActivity() {

    private var pendingPeqExportJson by mutableStateOf<String?>(null)
    private var importedPeqFileContent by mutableStateOf<String?>(null)

    /**
     * The UI must not freeze the speaker card in a disabled state when the player
     * controller is attached a little later than this Activity is created.
     *
     * A disconnected controller is used only as a preference-backed fallback. It
     * keeps the controls usable and stores every value. As soon as the runtime
     * PlayerController is available, onResume() replaces it with the live controller.
     */
    private var speakerOutputElasticityController
        by mutableStateOf<SpeakerOutputElasticityController?>(null)

    private var disconnectedSpeakerController: SpeakerOutputElasticityController? = null

    private val peqExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { writeJsonToUri(it, pendingPeqExportJson.orEmpty()) }
        pendingPeqExportJson = null
    }

    private val peqImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importedPeqFileContent = readJsonFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val peqController: ParametricEQController? = try {
            playerController?.ensurePEQConnected()
            playerController?.peqController
        } catch (_: Exception) {
            null
        }
        val graphicEqController = try {
            peqController?.let(::GraphicEQController)
        } catch (_: Exception) {
            null
        }
        val fftConvolverController = try {
            playerController?.ensureFftConvolverConnected()
            playerController?.fftConvolverController
        } catch (_: Exception) {
            null
        }
        val compressorController = try {
            playerController?.ensureCompressorConnected()
            playerController?.compressorController
        } catch (_: Exception) {
            null
        }
        val bassBoostController = try {
            playerController?.ensureBassBoostConnected()
            playerController?.bassBoostController
        } catch (_: Exception) {
            null
        }
        val trebleBoostController = try {
            playerController?.ensureTrebleBoostConnected()
            playerController?.trebleBoostController
        } catch (_: Exception) {
            null
        }
        refreshSpeakerOutputElasticityController()
        val surround360Controller = try {
            playerController?.ensureSurround360Connected()
            playerController?.surround360Controller
        } catch (_: Exception) {
            null
        }
        val panoramic360Controller = try {
            playerController?.ensurePanoramic360Connected()
            playerController?.panoramic360Controller
        } catch (_: Exception) {
            null
        }

        setContent {
            LiquidGlassAudioEffectsScreen(
                onNavigateToPEQ = { navigateToSettings(PEQActivity::class.java) },
                onTogglePEQ = { enabled -> peqController?.setEnabled(enabled) },
                peqController = peqController,
                graphicEqController = graphicEqController,
                fftConvolverController = fftConvolverController,
                compressorController = compressorController,
                bassBoostController = bassBoostController,
                trebleBoostController = trebleBoostController,
                speakerOutputElasticityController = speakerOutputElasticityController,
                surround360Controller = surround360Controller,
                panoramic360Controller = panoramic360Controller,
                onExportPeqToFile = { json ->
                    pendingPeqExportJson = json
                    peqExportLauncher.launch("PEQ_preset_${System.currentTimeMillis()}.peq.json")
                },
                onImportPeqFromFile = {
                    peqImportLauncher.launch(
                        arrayOf("application/json", "text/plain", "*/*")
                    )
                },
                importedPeqFileContent = importedPeqFileContent,
                onImportedPeqFileContentConsumed = { importedPeqFileContent = null },
                onBack = { finish() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSpeakerOutputElasticityController()
    }

    private fun refreshSpeakerOutputElasticityController() {
        val runtimePlayerController = playerController

        if (runtimePlayerController != null) {
            val liveController = runCatching {
                runtimePlayerController.ensureSpeakerOutputElasticityConnected()
                runtimePlayerController.speakerOutputElasticityController
            }.onFailure { error ->
                Log.e(
                    "AudioEffectsActivity",
                    "Unable to connect speaker-output elasticity controller",
                    error
                )
            }.getOrNull()

            if (liveController != null) {
                speakerOutputElasticityController = liveController
                disconnectedSpeakerController = null
                return
            }
        }

        // Keep the card interactive even when the audio engine is not created yet.
        // This controller has a zero native handle, so it only updates StateFlow and
        // AppPreferences. A newly connected live controller reloads those values.
        if (speakerOutputElasticityController == null) {
            val fallback = disconnectedSpeakerController ?: runCatching {
                SpeakerOutputElasticityController(NativeDSPEngine())
            }.onFailure { error ->
                Log.e(
                    "AudioEffectsActivity",
                    "Unable to create disconnected speaker-output controller",
                    error
                )
            }.getOrNull()?.also { disconnectedSpeakerController = it }

            speakerOutputElasticityController = fallback
        }
    }

    private fun writeJsonToUri(uri: Uri, json: String) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            android.widget.Toast.makeText(this, "预设已保存", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("AudioEffectsActivity", "Failed to export PEQ", e)
            android.widget.Toast.makeText(
                this,
                "保存失败: ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun readJsonFromUri(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            }
        } catch (e: Exception) {
            Log.e("AudioEffectsActivity", "Failed to import PEQ", e)
            android.widget.Toast.makeText(
                this,
                "读取失败: ${e.message}",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            null
        }
    }
}
