package com.rawsmusic.ui.settings

import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.rawsmusic.module.player.dsp.GraphicEQController
import com.rawsmusic.module.player.dsp.GraphicEQPreset
import com.rawsmusic.module.player.dsp.PEQFilter
import com.rawsmusic.ui.effects.graphiceq.GraphicEQBandUi
import com.rawsmusic.ui.effects.graphiceq.GraphicEQPresetUi
import com.rawsmusic.ui.effects.graphiceq.GraphicEQScreen

class GraphicEQActivity : BaseSettingsActivity() {

    private var geqController: GraphicEQController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val controller = try {
            playerController?.ensurePEQConnected()
            val peq = playerController?.peqController ?: throw IllegalStateException("PEQ controller null")
            GraphicEQController(peq)
        } catch (e: Exception) {
            Log.e("GraphicEQActivity", "Failed to get GEQ controller", e)
            null
        }
        geqController = controller

        setContent {
            if (controller != null) {
                val isEnabled by controller.isEnabled.collectAsState()
                val bandCount by controller.bandCount.collectAsState()
                val preamp by controller.preamp.collectAsState()
                val gains by controller.gains.collectAsState()
                val presetName by controller.presetName.collectAsState()
                val presets by controller.presets.collectAsState()

                val freqs = PEQFilter.defaultFreqsForCount(bandCount)
                val bands = gains.mapIndexed { i, gain ->
                    GraphicEQBandUi(
                        frequency = freqs.getOrElse(i) { 1000f },
                        gainDB = gain
                    )
                }

                val presetUis = presets.map { p ->
                    GraphicEQPresetUi(name = p.name, gains = p.gains)
                }

                GraphicEQScreen(
                    enabled = isEnabled,
                    bandCount = bandCount,
                    preampDB = preamp,
                    bands = bands,
                    presetName = presetName,
                    presets = presetUis,
                    onBack = { finish() },
                    onEnabledChange = { controller.setEnabled(it) },
                    onPreampChange = { controller.setPreamp(it) },
                    onBandGainChange = { index, gain -> controller.setGain(index, gain) },
                    onBandReset = { index -> controller.resetBand(index) },
                    onBandCountChange = { controller.setBandCount(it) },
                    onPresetClick = { ui ->
                        val preset = GraphicEQPreset(
                            name = ui.name,
                            bandCount = ui.gains.size,
                            gains = ui.gains
                        )
                        controller.applyPreset(preset)
                    },
                    onResetAll = { controller.resetToFlat() },
                    onSavePreset = {
                        android.widget.Toast.makeText(this, "保存预设功能待实现", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onApply = { finish() }
                )
            }
        }
    }
}
