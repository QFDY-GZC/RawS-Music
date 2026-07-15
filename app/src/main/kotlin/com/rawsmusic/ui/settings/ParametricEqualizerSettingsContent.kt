package com.rawsmusic.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.tween
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rawsmusic.R
import com.rawsmusic.core.ui.widget.PEQCurveColors
import com.rawsmusic.core.ui.widget.PEQCurveView
import com.rawsmusic.module.player.dsp.AutoEqCacheManager
import com.rawsmusic.module.player.dsp.AutoEqPreset
import com.rawsmusic.module.player.dsp.AutoEqRepository
import com.rawsmusic.module.player.dsp.AutoEqSearchResult
import com.rawsmusic.module.player.dsp.FilterType
import com.rawsmusic.module.player.dsp.PEQFilter
import com.rawsmusic.module.player.dsp.ParametricEQController
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private val peqExpandEnter = expandVertically() + fadeIn()
private val peqExpandExit = shrinkVertically() + fadeOut()
private val peqSuggestedBandCounts = listOf(10, 15, 20, 31, 40)
private val peqGson = Gson()

private data class ParsedPeqImport(
    val name: String,
    val preamp: Float,
    val filters: List<PEQFilter>,
    val sourceBandCount: Int,
    val sourceLabel: String
)

private enum class PeqImportBandAction {
    KEEP_CURRENT,
    SWITCH_TO_SOURCE
}

/**
 * Inline MIUIX parametric equalizer workspace.
 *
 * Filters and downloaded presets are horizontal mini cards. All edits continue
 * through ParametricEQController, including its native sync, persistence and
 * smart source-count to target-count conversion.
 */
@Composable
internal fun ParametricEqualizerSettingsContent(
    controller: ParametricEQController?,
    onExportToFile: (String) -> Unit = {},
    onImportFromFile: () -> Unit = {},
    importedFileContent: String? = null,
    onImportedFileContentConsumed: () -> Unit = {},
    showSectionHeader: Boolean = false
) {
    val context = LocalContext.current

    if (showSectionHeader) {
        SectionHeader(stringResource(R.string.settings_peq_title))
    }

    if (controller == null) {
        SettingsCard {
            SwitchPreference(
                title = stringResource(R.string.settings_peq_title),
                summary = stringResource(R.string.settings_peq_engine_unavailable),
                checked = false,
                enabled = false,
                onCheckedChange = {}
            )
        }
        return
    }

    val enabled by controller.isEnabled.collectAsState()
    val filters by controller.filters.collectAsState()
    val bandCount by controller.bandCount.collectAsState()
    val preamp by controller.preamp.collectAsState()
    val frequencyResponse by controller.frequencyResponse.collectAsState()

    val cacheManager = remember(context) { AutoEqCacheManager(context.applicationContext) }
    var cachedPresets by remember { mutableStateOf(cacheManager.loadAll()) }
    var showAutoEqDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<ParsedPeqImport?>(null) }
    var showBandChoice by remember { mutableStateOf(false) }

    LaunchedEffect(showAutoEqDialog) {
        if (!showAutoEqDialog) cachedPresets = cacheManager.loadAll()
    }
    LaunchedEffect(importedFileContent) {
        if (!importedFileContent.isNullOrBlank()) showImportDialog = true
    }

    fun applyImported(parsed: ParsedPeqImport, switchToSourceCount: Boolean) {
        val targetCount = parsed.sourceBandCount.coerceIn(
            PEQFilter.MIN_FILTERS,
            PEQFilter.MAX_FILTERS
        )
        if (switchToSourceCount && targetCount != controller.bandCount.value) {
            controller.setBandCount(targetCount)
        }
        controller.setPreamp(parsed.preamp)
        controller.importFilters(parsed.filters, parsed.name)
        val finalCount = controller.bandCount.value
        Toast.makeText(
            context,
            context.getString(
                R.string.settings_peq_import_applied_detail,
                parsed.sourceLabel,
                parsed.filters.size,
                finalCount
            ),
            Toast.LENGTH_SHORT
        ).show()
    }

    SettingsCard {
        SwitchPreference(
            title = stringResource(R.string.settings_peq_title),
            summary = stringResource(
                R.string.settings_peq_workspace_summary,
                bandCount,
                filters.take(bandCount).count { it.enabled }
            ),
            checked = enabled,
            onCheckedChange = controller::setEnabled
        )

        AnimatedVisibility(
            visible = enabled,
            enter = peqExpandEnter,
            exit = peqExpandExit
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                val frequencies = remember(frequencyResponse) {
                    if (frequencyResponse.size > 1) {
                        FloatArray(frequencyResponse.size) { i ->
                            20f * (1000.0.pow(i.toDouble() / (frequencyResponse.size - 1))).toFloat()
                        }
                    } else {
                        FloatArray(0)
                    }
                }
                val colors = MiuixTheme.colorScheme
                key(frequencyResponse.contentHashCode()) {
                    PEQCurveView(
                        frequencies = frequencies,
                        magnitudes = frequencyResponse,
                        colors = PEQCurveColors(
                            background = colors.surfaceContainerHigh,
                            gridLine = colors.outline.copy(alpha = 0.16f),
                            zeroLine = colors.outline.copy(alpha = 0.42f),
                            curveLine = colors.primary,
                            curveFill = Brush.verticalGradient(
                                listOf(
                                    colors.primary.copy(alpha = 0.24f),
                                    Color.Transparent
                                )
                            ),
                            labelColor = colors.onSurfaceVariantSummary,
                            filterDot = colors.primary
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                SliderPreference(
                    title = stringResource(R.string.settings_peq_preamp),
                    summary = stringResource(R.string.settings_peq_preamp_summary),
                    valueText = stringResource(R.string.settings_db_value_signed_one_decimal, preamp),
                    value = preamp.coerceIn(-12f, 12f),
                    onValueChange = controller::setPreamp,
                    valueRange = -12f..12f,
                    steps = 47,
                    hapticEffect = SliderDefaults.SliderHapticEffect.Step
                )

                PeqDropdownPreference(
                    title = stringResource(R.string.settings_peq_band_count),
                    summary = stringResource(R.string.settings_peq_band_layout_hint),
                    entries = (peqSuggestedBandCounts + bandCount)
                        .distinct()
                        .sorted()
                        .map { count ->
                            PeqDropdownEntry(
                                key = "bands:$count",
                                title = stringResource(R.string.settings_peq_band_count_value, count),
                                summary = if (bandCount == count) {
                                    stringResource(R.string.settings_graphic_eq_current_choice)
                                } else {
                                    stringResource(R.string.settings_peq_band_layout_hint)
                                },
                                selected = bandCount == count,
                                onClick = { controller.setBandCount(count) }
                            )
                        }
                )

                PeqDropdownPreference(
                    title = stringResource(R.string.settings_peq_presets_and_tools),
                    summary = stringResource(R.string.settings_peq_tools_dropdown_summary),
                    entries = buildList {
                        add(
                            PeqDropdownEntry(
                                key = "autoeq",
                                title = context.getString(R.string.settings_peq_autoeq),
                                summary = context.getString(R.string.settings_peq_autoeq_card_summary),
                                onClick = { showAutoEqDialog = true }
                            )
                        )
                        add(
                            PeqDropdownEntry(
                                key = "import",
                                title = context.getString(R.string.settings_import_preset),
                                summary = context.getString(R.string.settings_peq_import_formats),
                                onClick = { showImportDialog = true }
                            )
                        )
                        add(
                            PeqDropdownEntry(
                                key = "export",
                                title = context.getString(R.string.settings_export_preset),
                                summary = context.getString(R.string.settings_peq_export_card_summary),
                                onClick = { showExportDialog = true }
                            )
                        )
                        add(
                            PeqDropdownEntry(
                                key = "reset",
                                title = context.getString(R.string.settings_reset_default),
                                summary = context.getString(R.string.settings_peq_reset_card_summary),
                                onClick = controller::resetToDefault
                            )
                        )
                        cachedPresets.forEach { preset ->
                            add(
                                PeqDropdownEntry(
                                    key = "cached:${preset.name}",
                                    title = preset.name,
                                    summary = context.getString(
                                        R.string.settings_peq_cached_preset_summary,
                                        preset.filters.size,
                                        preset.source.ifBlank { "AutoEq" }
                                    ),
                                    onClick = {
                                        controller.importFromAutoEq(preset)
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.settings_peq_cached_preset_applied,
                                                preset.name
                                            ),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                            )
                        }
                    }
                )

                SectionHeader(stringResource(R.string.settings_peq_filters))
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    itemsIndexed(
                        items = filters.take(bandCount),
                        key = { index, _ -> index }
                    ) { index, filter ->
                        ParametricFilterMiuixCard(
                            index = index,
                            filter = filter,
                            onToggle = { controller.toggleFilter(index) },
                            onUpdate = { controller.updateFilter(index, it) },
                            onReset = {
                                val fallbackFrequency = PEQFilter.defaultFreqsForCount(bandCount)
                                    .getOrElse(index) { filter.frequency }
                                controller.updateFilter(
                                    index,
                                    PEQFilter(
                                        type = FilterType.PEAK,
                                        frequency = fallbackFrequency,
                                        gainDB = 0f,
                                        Q = 1.414f,
                                        enabled = false
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }


    if (showAutoEqDialog) {
        PeqAutoEqDialog(
            controller = controller,
            cacheManager = cacheManager,
            onCacheChanged = { cachedPresets = cacheManager.loadAll() },
            onDismiss = { showAutoEqDialog = false }
        )
    }

    if (showExportDialog) {
        PeqExportDialog(
            controller = controller,
            onExportToFile = onExportToFile,
            onDismiss = { showExportDialog = false }
        )
    }

    if (showImportDialog) {
        PeqImportDialog(
            initialText = importedFileContent.orEmpty(),
            onImportFromFile = onImportFromFile,
            onParsed = { parsed ->
                onImportedFileContentConsumed()
                showImportDialog = false
                val suggested = parsed.sourceBandCount.coerceIn(
                    PEQFilter.MIN_FILTERS,
                    PEQFilter.MAX_FILTERS
                )
                if (suggested != controller.bandCount.value) {
                    pendingImport = parsed
                    showBandChoice = true
                } else {
                    applyImported(parsed, switchToSourceCount = false)
                }
            },
            onDismiss = {
                onImportedFileContentConsumed()
                showImportDialog = false
            }
        )
    }

    if (showBandChoice && pendingImport != null) {
        val parsed = pendingImport!!
        val suggested = parsed.sourceBandCount.coerceIn(
            PEQFilter.MIN_FILTERS,
            PEQFilter.MAX_FILTERS
        )
        MiuixChoiceDialog(
            visible = true,
            title = stringResource(R.string.settings_peq_band_count_mismatch),
            items = listOf(
                MiuixChoiceItem(
                    value = PeqImportBandAction.KEEP_CURRENT,
                    title = stringResource(
                        R.string.settings_peq_keep_current_bands,
                        controller.bandCount.value
                    ),
                    summary = stringResource(R.string.settings_peq_import_smart_adapt_summary)
                ),
                MiuixChoiceItem(
                    value = PeqImportBandAction.SWITCH_TO_SOURCE,
                    title = stringResource(
                        R.string.settings_peq_switch_to_preset_bands,
                        suggested
                    ),
                    summary = stringResource(
                        R.string.settings_peq_import_source_count_summary,
                        parsed.filters.size
                    )
                )
            ),
            selectedValue = PeqImportBandAction.KEEP_CURRENT,
            onSelect = { action ->
                applyImported(
                    parsed,
                    switchToSourceCount = action == PeqImportBandAction.SWITCH_TO_SOURCE
                )
                pendingImport = null
            },
            onDismiss = {
                showBandChoice = false
                pendingImport = null
            }
        )
    }
}

private data class PeqDropdownEntry(
    val key: String,
    val title: String,
    val summary: String? = null,
    val selected: Boolean = false,
    val onClick: () -> Unit
)

@Composable
private fun PeqDropdownPreference(
    title: String,
    summary: String,
    entries: List<PeqDropdownEntry>
) {
    val dropdownEntry = remember(entries) {
        DropdownEntry(
            items = entries.map { item ->
                DropdownItem(
                    text = item.title,
                    summary = item.summary,
                    selected = item.selected,
                    onClick = item.onClick
                )
            }
        )
    }

    WindowDropdownPreference(
        entry = dropdownEntry,
        title = title,
        summary = summary,
        enabled = entries.isNotEmpty(),
        showValue = entries.any { it.selected },
        maxHeight = 430.dp,
        collapseOnSelection = true
    )
}

@Composable
private fun ParametricFilterMiuixCard(
    index: Int,
    filter: PEQFilter,
    onToggle: () -> Unit,
    onUpdate: (PEQFilter) -> Unit,
    onReset: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val safe = filter.sanitized()
    val supportsGain = safe.type != FilterType.LOW_PASS && safe.type != FilterType.HIGH_PASS
    var selectingType by remember(index) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .width(292.dp)
            .background(colors.surfaceContainerHigh, shape)
            .border(1.dp, colors.outline.copy(alpha = 0.14f), shape)
            .padding(vertical = 6.dp)
    ) {
        SwitchPreference(
            title = stringResource(R.string.settings_peq_filter_card_title, index + 1),
            summary = "${FilterType.displayName(safe.type)} · ${peqFrequencyText(safe.frequency)}",
            checked = safe.enabled,
            onCheckedChange = { onToggle() }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_peq_type),
                    color = colors.onBackground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.settings_peq_type_inline_hint),
                    color = colors.onSurfaceVariantSummary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Button(
                onClick = { selectingType = !selectingType },
                minWidth = 118.dp,
                minHeight = 42.dp,
                cornerRadius = 16.dp,
                colors = if (selectingType) {
                    ButtonDefaults.buttonColorsPrimary()
                } else {
                    ButtonDefaults.buttonColors()
                },
                insideMargin = PaddingValues(horizontal = 13.dp, vertical = 9.dp)
            ) {
                Text(
                    text = FilterType.displayName(safe.type),
                    color = if (selectingType) colors.onPrimary else colors.onSecondaryVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = if (selectingType) "⌃" else "⌄",
                    color = if (selectingType) colors.onPrimary else colors.onSecondaryVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        AnimatedContent(
            targetState = selectingType,
            transitionSpec = {
                fadeIn(animationSpec = tween(140)) togetherWith
                    fadeOut(animationSpec = tween(90))
            },
            label = "peq-filter-type-${index + 1}"
        ) { choosingType ->
            if (choosingType) {
                PeqInlineFilterTypePicker(
                    selected = safe.type,
                    onSelect = { type ->
                        onUpdate(safe.copy(type = type))
                        selectingType = false
                    }
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SliderPreference(
                        title = stringResource(R.string.settings_peq_frequency),
                        summary = stringResource(R.string.settings_peq_frequency_slider_summary),
                        valueText = peqFrequencyText(safe.frequency),
                        value = frequencyToSlider(safe.frequency),
                        onValueChange = { normalized ->
                            onUpdate(safe.copy(frequency = sliderToFrequency(normalized)))
                        },
                        valueRange = 0f..1f,
                    )

                    if (supportsGain) {
                        SliderPreference(
                            title = stringResource(R.string.settings_peq_gain),
                            summary = stringResource(R.string.settings_peq_gain_slider_summary),
                            valueText = stringResource(
                                R.string.settings_db_value_signed_one_decimal,
                                safe.gainDB
                            ),
                            value = safe.gainDB.coerceIn(PEQFilter.GAIN_RANGE),
                            onValueChange = { gain -> onUpdate(safe.copy(gainDB = gain)) },
                            valueRange = PEQFilter.GAIN_RANGE,
                            steps = 47,
                            hapticEffect = SliderDefaults.SliderHapticEffect.Step
                        )
                    }

                    SliderPreference(
                        title = stringResource(R.string.settings_peq_q_value),
                        summary = stringResource(R.string.settings_peq_q_slider_summary),
                        valueText = String.format("%.2f", safe.Q),
                        value = safe.Q.coerceIn(PEQFilter.Q_RANGE),
                        onValueChange = { q -> onUpdate(safe.copy(Q = q)) },
                        valueRange = PEQFilter.Q_RANGE,
                        steps = 98,
                        hapticEffect = SliderDefaults.SliderHapticEffect.Step
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        MiuixTextButton(
                            text = stringResource(R.string.settings_peq_reset_filter),
                            onClick = onReset
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PeqInlineFilterTypePicker(
    selected: FilterType,
    onSelect: (FilterType) -> Unit
) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterType.entries.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pair.forEach { type ->
                    val isSelected = type == selected
                    Button(
                        onClick = { onSelect(type) },
                        modifier = Modifier.weight(1f),
                        minWidth = 0.dp,
                        minHeight = 44.dp,
                        cornerRadius = 16.dp,
                        colors = if (isSelected) {
                            ButtonDefaults.buttonColorsPrimary()
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 9.dp)
                    ) {
                        Text(
                            text = if (isSelected) {
                                "${FilterType.displayName(type)}  ✓"
                            } else {
                                FilterType.displayName(type)
                            },
                            color = if (isSelected) colors.onPrimary else colors.onSecondaryVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PeqExportDialog(
    controller: ParametricEQController,
    onExportToFile: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val filters by controller.filters.collectAsState()
    val preamp by controller.preamp.collectAsState()
    val bandCount by controller.bandCount.collectAsState()
    val preset = remember(filters, preamp, bandCount) {
        PEQPreset(
            name = context.getString(
                R.string.settings_peq_default_preset_name,
                System.currentTimeMillis()
            ),
            preamp = preamp,
            filters = filters.take(bandCount),
            bandCount = bandCount
        )
    }
    val json = remember(preset) { preset.toJson() }

    PeqDialogShell(
        title = stringResource(R.string.settings_export_preset),
        onDismiss = onDismiss
    ) {
        Text(
            text = stringResource(R.string.settings_peq_export_summary, filters.size, preamp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp
        )
        OutlinedTextField(
            value = json,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .padding(top = 10.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.End
        ) {
            MiuixTextButton(
                text = stringResource(R.string.settings_copy),
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(context.getString(R.string.settings_peq_clip_label), json)
                    )
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_copied_to_clipboard),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
            Spacer(Modifier.width(8.dp))
            MiuixTextButton(
                text = stringResource(R.string.settings_save_to_file),
                onClick = {
                    onExportToFile(json)
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun PeqImportDialog(
    initialText: String,
    onImportFromFile: () -> Unit,
    onParsed: (ParsedPeqImport) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(initialText) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialText) {
        if (initialText.isNotBlank()) {
            text = initialText
            error = null
        }
    }

    PeqDialogShell(
        title = stringResource(R.string.settings_import_preset),
        onDismiss = onDismiss
    ) {
        Text(
            text = stringResource(R.string.settings_peq_import_formats),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp
        )
        MiuixTextButton(
            text = stringResource(R.string.settings_import_from_file),
            onClick = onImportFromFile,
            modifier = Modifier.padding(top = 8.dp)
        )
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            placeholder = { Text(stringResource(R.string.settings_peq_import_desc)) },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(top = 8.dp)
        )
        if (!error.isNullOrBlank()) {
            Text(
                text = error.orEmpty(),
                color = Color(0xFFE5484D),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.End
        ) {
            MiuixTextButton(
                text = stringResource(R.string.settings_import),
                onClick = {
                    val parsed = parsePeqImport(text, context)
                    if (parsed == null) {
                        error = context.getString(R.string.settings_peq_error_invalid_preset)
                    } else {
                        onParsed(parsed)
                    }
                }
            )
        }
    }
}

@Composable
private fun PeqAutoEqDialog(
    controller: ParametricEQController,
    cacheManager: AutoEqCacheManager,
    onCacheChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { AutoEqRepository() }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<AutoEqSearchResult>>(emptyList()) }
    var cached by remember { mutableStateOf(cacheManager.loadAll()) }
    var searching by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun applyPreset(preset: AutoEqPreset) {
        controller.importFromAutoEq(preset)
        Toast.makeText(
            context,
            context.getString(R.string.settings_peq_cached_preset_applied, preset.name),
            Toast.LENGTH_SHORT
        ).show()
        onDismiss()
    }

    PeqDialogShell(
        title = stringResource(R.string.settings_autoeq_presets),
        onDismiss = onDismiss
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.settings_autoeq_search_hint)) },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            MiuixTextButton(
                text = stringResource(R.string.settings_search),
                onClick = {
                    if (query.isBlank()) return@MiuixTextButton
                    scope.launch {
                        searching = true
                        error = null
                        searchResults = try {
                            repository.search(query.trim())
                        } catch (throwable: Throwable) {
                            error = throwable.message
                            emptyList()
                        }
                        if (searchResults.isEmpty() && error == null) {
                            error = context.getString(R.string.settings_autoeq_error_not_found)
                        }
                        searching = false
                    }
                }
            )
        }

        if (searching) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MiuixTheme.colorScheme.primary,
                    strokeWidth = 2.dp
                )
            }
        }
        if (!error.isNullOrBlank()) {
            Text(
                text = error.orEmpty(),
                color = Color(0xFFE5484D),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 430.dp)
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (searchResults.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.settings_search_results),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                items(searchResults, key = { it.path }) { result ->
                    val isDownloading = downloading == result.path
                    PeqAutoEqResultRow(
                        title = result.headphoneName,
                        summary = stringResource(
                            R.string.settings_autoeq_result_source,
                            result.source,
                            result.deviceType
                        ),
                        trailing = if (cacheManager.exists(result.headphoneName)) {
                            stringResource(R.string.settings_downloaded)
                        } else {
                            stringResource(R.string.settings_download)
                        },
                        busy = isDownloading,
                        onClick = {
                            val local = cacheManager.load(result.headphoneName)
                            if (local != null) {
                                applyPreset(local)
                            } else if (!isDownloading) {
                                scope.launch {
                                    downloading = result.path
                                    error = null
                                    val preset = try {
                                        repository.download(result)
                                    } catch (throwable: Throwable) {
                                        error = throwable.message
                                        null
                                    }
                                    if (preset != null) {
                                        cacheManager.save(preset)
                                        cached = cacheManager.loadAll()
                                        onCacheChanged()
                                        applyPreset(preset)
                                    } else if (error == null) {
                                        error = context.getString(R.string.settings_autoeq_error_download_failed)
                                    }
                                    downloading = null
                                }
                            }
                        }
                    )
                }
            }

            if (cached.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.settings_downloaded),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                items(cached, key = { "cached:${it.name}" }) { preset ->
                    PeqAutoEqResultRow(
                        title = preset.name,
                        summary = stringResource(
                            R.string.settings_peq_cached_preset_summary,
                            preset.filters.size,
                            preset.source.ifBlank { "AutoEq" }
                        ),
                        trailing = stringResource(R.string.settings_peq_apply),
                        busy = false,
                        onClick = { applyPreset(preset) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PeqAutoEqResultRow(
    title: String,
    summary: String,
    trailing: String,
    busy: Boolean,
    onClick: () -> Unit
) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerHigh, shape)
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.onBackground,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = summary,
                color = colors.onSurfaceVariantSummary,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = colors.primary,
                strokeWidth = 2.dp
            )
        } else {
            Text(
                text = trailing,
                color = colors.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PeqDialogShell(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 24.dp, vertical = 30.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 720.dp)
                    .clickable(onClick = {}),
                color = MiuixTheme.colorScheme.surface,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            color = MiuixTheme.colorScheme.onBackground,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        MiuixTextButton(
                            text = stringResource(R.string.settings_close),
                            onClick = onDismiss
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    content()
                }
            }
        }
    }
}

private fun parsePeqImport(text: String, context: Context): ParsedPeqImport? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null

    if (trimmed.contains("Filter", ignoreCase = true) &&
        trimmed.contains("Fc", ignoreCase = true)
    ) {
        val autoEq = AutoEqPreset.parse(
            name = context.getString(R.string.settings_peq_imported_autoeq_name),
            source = "file",
            text = trimmed
        ) ?: return null
        val filters = autoEq.toPEQFilters()
        return ParsedPeqImport(
            name = autoEq.name,
            preamp = autoEq.safePreamp,
            filters = filters,
            sourceBandCount = filters.size,
            sourceLabel = "AutoEq"
        )
    }

    if (trimmed.contains("\"fc\"") && trimmed.contains("\"q\"")) {
        AutoEqPreset.fromJson(trimmed)?.let { preset ->
            val filters = preset.toPEQFilters()
            if (filters.isNotEmpty()) {
                return ParsedPeqImport(
                    name = preset.name,
                    preamp = preset.safePreamp,
                    filters = filters,
                    sourceBandCount = filters.size,
                    sourceLabel = "AutoEq JSON"
                )
            }
        }
    }

    PEQPreset.fromJson(trimmed)?.let { preset ->
        if (preset.filters.isNotEmpty()) {
            return ParsedPeqImport(
                name = preset.name,
                preamp = preset.preamp.coerceIn(-12f, 12f),
                filters = preset.filters.map { it.sanitized() },
                sourceBandCount = preset.bandCount,
                sourceLabel = "RawSMusic JSON"
            )
        }
    }

    return try {
        val type = object : TypeToken<List<PEQFilter>>() {}.type
        val filters = peqGson.fromJson<List<PEQFilter>>(trimmed, type)
            .orEmpty()
            .map { it.sanitized() }
            .filter { it.frequency in PEQFilter.FREQUENCY_RANGE }
        if (filters.isEmpty()) null else ParsedPeqImport(
            name = context.getString(R.string.settings_peq_imported_list_name),
            preamp = 0f,
            filters = filters,
            sourceBandCount = filters.size,
            sourceLabel = "Filter JSON"
        )
    } catch (_: Throwable) {
        null
    }
}

private fun frequencyToSlider(frequency: Float): Float {
    val safe = frequency.coerceIn(20f, 20000f)
    return (ln(safe / 20f) / ln(1000f)).coerceIn(0f, 1f)
}

private fun sliderToFrequency(value: Float): Float {
    return (20.0 * 1000.0.pow(value.coerceIn(0f, 1f).toDouble()))
        .toFloat()
        .coerceIn(20f, 20000f)
}

private fun peqFrequencyText(frequency: Float): String {
    return when {
        frequency >= 10000f -> String.format("%.1f kHz", frequency / 1000f)
        frequency >= 1000f -> String.format("%.2f kHz", frequency / 1000f)
        else -> "${frequency.roundToInt()} Hz"
    }
}
