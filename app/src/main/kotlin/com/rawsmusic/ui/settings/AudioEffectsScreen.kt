package com.rawsmusic.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rawsmusic.R
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.dsp.BassBoostController
import com.rawsmusic.module.player.dsp.CompressorController
import com.rawsmusic.module.player.dsp.FftConvolverController
import com.rawsmusic.module.player.dsp.GraphicEQController
import com.rawsmusic.module.player.dsp.ExperimentalGainController
import com.rawsmusic.module.player.dsp.LoudnessBalanceController
import com.rawsmusic.module.player.dsp.MonoBassController
import com.rawsmusic.module.player.dsp.DynamicEqController
import com.rawsmusic.module.player.dsp.MoogLadderController
import com.rawsmusic.module.player.dsp.Panoramic360Controller
import com.rawsmusic.module.player.dsp.ParametricEQController
import com.rawsmusic.module.player.dsp.Surround360Controller
import com.rawsmusic.module.player.dsp.TrebleBoostController
import com.rawsmusic.module.player.dsp.SpeakerOutputElasticityController
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiquidGlassAudioEffectsScreen(
    onNavigateToPEQ: () -> Unit = {},
    onTogglePEQ: (Boolean) -> Unit = {},
    peqController: ParametricEQController? = null,
    graphicEqController: GraphicEQController? = null,
    experimentalGainController: ExperimentalGainController? = null,
    loudnessBalanceController: LoudnessBalanceController? = null,
    monoBassController: MonoBassController? = null,
    dynamicEqController: DynamicEqController? = null,
    moogLadderController: MoogLadderController? = null,
    fftConvolverController: FftConvolverController? = null,
    compressorController: CompressorController? = null,
    bassBoostController: BassBoostController? = null,
    trebleBoostController: TrebleBoostController? = null,
    speakerOutputElasticityController: SpeakerOutputElasticityController? = null,
    surround360Controller: Surround360Controller? = null,
    panoramic360Controller: Panoramic360Controller? = null,
    onExportPeqToFile: (String) -> Unit = {},
    onImportPeqFromFile: () -> Unit = {},
    importedPeqFileContent: String? = null,
    onImportedPeqFileContentConsumed: () -> Unit = {},
    onBack: () -> Unit
) {
    var fallbackPeqEnabled by remember { mutableStateOf(AppPreferences.PEQ.isEnabled) }
    val peqEnabled = peqController?.isEnabled?.collectAsState()?.value ?: fallbackPeqEnabled

    val dimensions = AudioEffectDimension.entries
    val pagerState = rememberPagerState(pageCount = { dimensions.size })
    val pagerScope = rememberCoroutineScope()

    val parametricListState = rememberLazyListState()
    val graphicListState = rememberLazyListState()
    val spatialListState = rememberLazyListState()
    val advancedListState = rememberLazyListState()

    AudioEffectsWorkspacePage(
        title = stringResource(R.string.settings_audio_effects_title),
        onBack = onBack
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AudioEffectDimensionTabs(
                selectedIndex = pagerState.currentPage,
                onSelected = { targetPage ->
                    if (targetPage != pagerState.currentPage) {
                        pagerScope.launch { pagerState.scrollToPage(targetPage) }
                    }
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                userScrollEnabled = true,
                beyondViewportPageCount = 0
            ) { page ->
                when (dimensions[page]) {
                    AudioEffectDimension.PARAMETRIC_EQ -> AudioEffectsDimensionList(
                        state = parametricListState
                    ) {
                        if (peqController != null) {
                            ParametricEqualizerSettingsContent(
                                controller = peqController,
                                onExportToFile = onExportPeqToFile,
                                onImportFromFile = onImportPeqFromFile,
                                importedFileContent = importedPeqFileContent,
                                onImportedFileContentConsumed = onImportedPeqFileContentConsumed,
                                showSectionHeader = false
                            )
                        } else {
                            SettingsSection(stringResource(R.string.settings_effects_eq)) {
                                SwitchRow(
                                    label = stringResource(R.string.settings_effects_enable_peq),
                                    checked = peqEnabled
                                ) { checked ->
                                    fallbackPeqEnabled = checked
                                    onTogglePEQ(checked)
                                }
                                SettingsNavigationEntry(
                                    title = stringResource(R.string.settings_effects_peq_title),
                                    description = stringResource(R.string.settings_effects_peq_desc),
                                    onClick = onNavigateToPEQ
                                )
                            }
                        }
                    }

                    AudioEffectDimension.GRAPHIC_EQ -> AudioEffectsDimensionList(
                        state = graphicListState
                    ) {
                        GraphicEqualizerSettingsContent(
                            controller = graphicEqController,
                            showSectionHeader = false
                        )
                    }

                    AudioEffectDimension.SPATIAL_REVERB -> AudioEffectsDimensionList(
                        state = spatialListState
                    ) {
                        SectionHeader(stringResource(R.string.settings_effects_convolution))
                        FftConvolverSettingsCard(controller = fftConvolverController)
                        SpatialSoundSettingsContent()
                        Surround360SettingsContent(controller = surround360Controller)
                        Panoramic360SettingsContent(controller = panoramic360Controller)
                    }

                    AudioEffectDimension.ADVANCED -> AudioEffectsDimensionList(
                        state = advancedListState
                    ) {
                        CoreAudioEnhancementSettingsContent(
                            loudnessBalanceController = loudnessBalanceController,
                            monoBassController = monoBassController,
                            dynamicEqController = dynamicEqController
                        )
                        MoogLadderSettingsCard(controller = moogLadderController)
                        SpeakerOutputElasticitySettingsContent(
                            controller = speakerOutputElasticityController
                        )
                        CompressorSettingsContent(controller = compressorController)
                        BassTrebleBoostSettingsContent(
                            bassController = bassBoostController,
                            trebleController = trebleBoostController
                        )
                        ExperimentalGainSettingsCard(controller = experimentalGainController)
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioEffectsDimensionList(
    state: androidx.compose.foundation.lazy.LazyListState,
    content: @Composable () -> Unit
) {
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            top = 4.dp,
            end = 12.dp,
            bottom = 180.dp
        ),
        verticalArrangement = Arrangement.Top
    ) {
        item { Column { content() } }
    }
}
