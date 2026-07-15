package com.rawsmusic.ui.settings

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.module.player.dsp.FftConvolverController
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * MIUIX-style inline card for the native FFT convolver.
 *
 * The card intentionally expands only after the main switch is enabled. File
 * picking and WAV decoding are asynchronous; realtime processing remains in the
 * standalone native convolver files.
 */
@Composable
internal fun FftConvolverSettingsCard(
    controller: FftConvolverController?
) {
    if (controller == null) {
        SettingsCard {
            SwitchPreference(
                title = stringResource(R.string.settings_fft_convolver_title),
                summary = stringResource(R.string.settings_fft_convolver_engine_unavailable),
                checked = false,
                enabled = false,
                onCheckedChange = {}
            )
        }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val enabled by controller.isEnabled.collectAsState()
    val ready by controller.isReady.collectAsState()
    val wet by controller.wet.collectAsState()
    val dry by controller.dry.collectAsState()
    val gainDb by controller.gainDb.collectAsState()
    val preDelayMs by controller.preDelayMs.collectAsState()
    val latencyFrames by controller.latencyFrames.collectAsState()
    val outputSampleRate by controller.outputSampleRate.collectAsState()
    val loadState by controller.loadState.collectAsState()
    val loadError by controller.loadError.collectAsState()
    val irName by controller.irDisplayName.collectAsState()
    val irSampleRate by controller.irSourceSampleRate.collectAsState()
    val irChannels by controller.irChannels.collectAsState()
    val irFrames by controller.irFrames.collectAsState()

    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val displayName = queryDisplayName(context, uri)
        scope.launch {
            val loaded = controller.loadIrFromUri(context, uri, displayName)
            Toast.makeText(
                context,
                if (loaded) {
                    context.getString(R.string.settings_fft_convolver_load_success)
                } else {
                    context.getString(R.string.settings_fft_convolver_load_failed)
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val summary = when (loadState) {
        FftConvolverController.LoadState.LOADING ->
            stringResource(R.string.settings_fft_convolver_loading)

        FftConvolverController.LoadState.ERROR ->
            stringResource(R.string.settings_fft_convolver_error_summary)

        FftConvolverController.LoadState.READY ->
            if (ready && irName.isNotBlank()) {
                stringResource(R.string.settings_fft_convolver_loaded_summary, irName)
            } else {
                stringResource(R.string.settings_fft_convolver_waiting_engine)
            }

        FftConvolverController.LoadState.EMPTY ->
            stringResource(R.string.settings_fft_convolver_summary)
    }

    SettingsCard {
        SwitchPreference(
            title = stringResource(R.string.settings_fft_convolver_title),
            summary = summary,
            checked = enabled,
            onCheckedChange = controller::setEnabled
        )

        AnimatedVisibility(
            visible = enabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                ArrowPreference(
                    title = stringResource(R.string.settings_fft_convolver_select_ir),
                    summary = if (irName.isBlank()) {
                        stringResource(R.string.settings_fft_convolver_select_ir_multichannel_desc)
                    } else {
                        irName
                    },
                    onClick = {
                        documentLauncher.launch(
                            arrayOf(
                                "audio/wav",
                                "audio/x-wav",
                                "audio/wave",
                                "audio/vnd.wave",
                                "application/octet-stream"
                            )
                        )
                    }
                )

                SliderPreference(
                    title = stringResource(R.string.settings_fft_convolver_wet),
                    summary = stringResource(R.string.settings_fft_convolver_wet_desc),
                    valueText = stringResource(
                        R.string.settings_fft_convolver_percent,
                        (wet * 100f).roundToInt()
                    ),
                    value = wet.coerceIn(0f, 1f),
                    onValueChange = { controller.setWetDry(it, dry) },
                    valueRange = 0f..1f,
                    steps = 19,
                    hapticEffect = SliderDefaults.SliderHapticEffect.Step
                )

                SliderPreference(
                    title = stringResource(R.string.settings_fft_convolver_dry),
                    summary = stringResource(R.string.settings_fft_convolver_dry_desc),
                    valueText = stringResource(
                        R.string.settings_fft_convolver_percent,
                        (dry * 100f).roundToInt()
                    ),
                    value = dry.coerceIn(0f, 1f),
                    onValueChange = { controller.setWetDry(wet, it) },
                    valueRange = 0f..1f,
                    steps = 19,
                    hapticEffect = SliderDefaults.SliderHapticEffect.Step
                )

                SliderPreference(
                    title = stringResource(R.string.settings_fft_convolver_gain),
                    summary = stringResource(R.string.settings_fft_convolver_gain_desc),
                    valueText = stringResource(R.string.settings_fft_convolver_db, gainDb),
                    value = gainDb.coerceIn(-24f, 12f),
                    onValueChange = controller::setGainDb,
                    valueRange = -24f..12f,
                    steps = 71,
                    hapticEffect = SliderDefaults.SliderHapticEffect.Step
                )

                SliderPreference(
                    title = stringResource(R.string.settings_fft_convolver_pre_delay),
                    summary = stringResource(R.string.settings_fft_convolver_pre_delay_desc),
                    valueText = stringResource(
                        R.string.settings_fft_convolver_ms,
                        preDelayMs.roundToInt()
                    ),
                    value = preDelayMs.coerceIn(0f, 500f),
                    onValueChange = { raw ->
                        controller.setPreDelayMs((raw / 5f).roundToInt() * 5f)
                    },
                    valueRange = 0f..500f,
                    steps = 99,
                    hapticEffect = SliderDefaults.SliderHapticEffect.Step
                )

                val latencyMs = if (outputSampleRate > 0) {
                    latencyFrames * 1000f / outputSampleRate
                } else {
                    0f
                }
                val channelLabel = when (irChannels) {
                    1 -> stringResource(R.string.settings_fft_convolver_mono)
                    2 -> stringResource(R.string.settings_fft_convolver_stereo)
                    else -> stringResource(R.string.settings_fft_convolver_channels, irChannels)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_fft_convolver_status),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (irName.isBlank()) {
                            stringResource(R.string.settings_fft_convolver_no_ir)
                        } else {
                            stringResource(
                                R.string.settings_fft_convolver_ir_info,
                                irSampleRate,
                                channelLabel,
                                irFrames
                            )
                        },
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_fft_convolver_latency,
                            latencyFrames,
                            latencyMs
                        ),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                    if (loadState == FftConvolverController.LoadState.ERROR && loadError.isNotBlank()) {
                        Text(
                            text = loadError,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                if (irName.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            text = stringResource(R.string.settings_fft_convolver_clear_ir),
                            onClick = controller::clearIr
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_fft_convolver_resources_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_fft_convolver_resources_desc),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }

                ArrowPreference(
                    title = stringResource(R.string.settings_fft_convolver_resource_openair),
                    summary = stringResource(R.string.settings_fft_convolver_resource_openair_desc),
                    onClick = { openExternalIrResource(context, OPENAIR_URL) }
                )

                ArrowPreference(
                    title = stringResource(R.string.settings_fft_convolver_resource_echothief),
                    summary = stringResource(R.string.settings_fft_convolver_resource_echothief_desc),
                    onClick = { openExternalIrResource(context, ECHOTHIEF_URL) }
                )

                ArrowPreference(
                    title = stringResource(R.string.settings_fft_convolver_resource_waves),
                    summary = stringResource(R.string.settings_fft_convolver_resource_waves_desc),
                    onClick = { openExternalIrResource(context, WAVES_IR_URL) }
                )

                ArrowPreference(
                    title = stringResource(R.string.settings_fft_convolver_resource_autoeq),
                    summary = stringResource(R.string.settings_fft_convolver_resource_autoeq_desc),
                    onClick = { openExternalIrResource(context, AUTOEQ_URL) }
                )
            }
        }
    }
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String {
    var cursor: Cursor? = null
    return try {
        cursor = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )
        val index = cursor?.getColumnIndex(OpenableColumns.DISPLAY_NAME) ?: -1
        if (cursor != null && cursor.moveToFirst() && index >= 0) {
            cursor.getString(index).orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }
        } else {
            uri.lastPathSegment.orEmpty().ifBlank { "Impulse response.wav" }
        }
    } catch (_: Throwable) {
        uri.lastPathSegment.orEmpty().ifBlank { "Impulse response.wav" }
    } finally {
        cursor?.close()
    }
}

private const val OPENAIR_URL = "https://www.openair.hosted.york.ac.uk/"
private const val ECHOTHIEF_URL = "https://www.echothief.com/downloads/"
private const val WAVES_IR_URL = "https://www.waves.com/downloads/ir-convolution-reverb-library"
private const val AUTOEQ_URL = "https://autoeq.app/"

private fun openExternalIrResource(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
        )
    }.onFailure {
        Toast.makeText(
            context,
            context.getString(R.string.settings_fft_convolver_resource_open_failed),
            Toast.LENGTH_SHORT
        ).show()
    }
}

