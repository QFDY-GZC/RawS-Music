package com.rawsmusic.core.ui.widget.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.systemui.rawStableNavigationBarsPadding
import com.rawsmusic.core.ui.widget.ActivityOverlayBackOwner
import com.rawsmusic.core.ui.widget.predictiveBottomSheetMotion
import com.rawsmusic.core.ui.widget.predictiveBottomSheetScrim
import com.rawsmusic.core.ui.widget.rememberPredictiveDialogProgress
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Single-owner sleep-timer editor. Fixed presets and custom duration are different backend modes;
 * selecting either side therefore always clears the visual selection on the other side.
 */
@Composable
fun SleepTimerBottomSheet(
    show: Boolean,
    mode: Int,
    remainingMs: Long,
    fixedSelection: Int,
    customDurationMs: Long,
    exitWhenFinished: Boolean,
    onDismiss: () -> Unit,
    onCancelTimer: () -> Unit,
    onFixedSelection: (Int) -> Unit,
    onCustomDuration: (Long) -> Unit,
    onExitWhenFinishedChange: (Boolean) -> Unit,
) {
    ActivityOverlayBackOwner(show)
    val scheme = MiuixTheme.colorScheme
    val dismissProgress = rememberPredictiveDialogProgress(
        enabled = show,
        onDismissRequest = onDismiss,
    )
    val customMode = mode == SLEEP_TIMER_MODE_CUSTOM
    var sliderSeconds by rememberSaveable(show, customDurationMs) {
        mutableIntStateOf((customDurationMs / 1_000L).toInt().coerceIn(0, MAX_CUSTOM_SECONDS))
    }
    var exactEditorVisible by rememberSaveable(show) { mutableStateOf(false) }
    var exactMinutes by rememberSaveable(show) {
        mutableStateOf((sliderSeconds / 60).toString())
    }
    var exactSeconds by rememberSaveable(show) {
        mutableStateOf((sliderSeconds % 60).toString())
    }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(show) {
        if (!show) {
            exactEditorVisible = false
            focusManager.clearFocus(force = true)
        }
    }

    AnimatedVisibility(
        visible = show,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(140)),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .predictiveBottomSheetScrim(dismissProgress)
                    .background(scheme.windowDimming)
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )

            AnimatedVisibility(
                visible = show,
                enter = slideInVertically(tween(220)) { it },
                exit = slideOutVertically(tween(180)) { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = DialogDefaults.MaxWidth)
                        .rawStableNavigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .predictiveBottomSheetMotion(dismissProgress)
                        .squircleSurface(
                            color = DialogDefaults.backgroundColor(),
                            cornerRadius = 32.dp,
                        )
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(38.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(scheme.onSurface.copy(alpha = 0.18f))
                            .align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.player_sleep_timer),
                                color = scheme.onSurface,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (remainingMs > 0L) {
                                    stringResource(
                                        R.string.player_sleep_timer_remaining,
                                        formatSleepDuration(remainingMs),
                                    )
                                } else {
                                    stringResource(R.string.player_sleep_timer_not_running)
                                },
                                color = scheme.onSurfaceVariantSummary,
                                fontSize = 13.sp,
                            )
                        }
                        TextButton(
                            text = stringResource(R.string.sleep_timer_off),
                            enabled = mode != SLEEP_TIMER_MODE_OFF,
                            onClick = onCancelTimer,
                        )
                    }

                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = stringResource(R.string.player_sleep_timer_fixed_title),
                        color = scheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        fixedSleepTimerItems().forEach { (index, labelRes) ->
                            val selected = !customMode && fixedSelection == index
                            TextButton(
                                text = if (selected) {
                                    "✓ ${stringResource(labelRes)}"
                                } else {
                                    stringResource(labelRes)
                                },
                                onClick = { onFixedSelection(index) },
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.player_sleep_timer_custom_title),
                        color = scheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                    )
                    Text(
                        text = formatSleepDuration(sliderSeconds * 1_000L),
                        color = if (customMode) scheme.primary else scheme.onSurface,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    exactMinutes = (sliderSeconds / 60).toString()
                                    exactSeconds = (sliderSeconds % 60).toString()
                                    exactEditorVisible = true
                                },
                            )
                            .padding(horizontal = 18.dp, vertical = 6.dp),
                    )
                    Text(
                        text = stringResource(R.string.player_sleep_timer_long_press_hint),
                        color = scheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Slider(
                        value = sleepTimerSecondsToSliderPosition(sliderSeconds),
                        onValueChange = { position ->
                            sliderSeconds = sliderPositionToSleepTimerSeconds(position)
                        },
                        onValueChangeFinished = {
                            if (sliderSeconds <= 0) {
                                onCancelTimer()
                            } else {
                                onCustomDuration(sliderSeconds * 1_000L)
                            }
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("0", color = scheme.onSurfaceVariantSummary, fontSize = 12.sp)
                        Text("600 min", color = scheme.onSurfaceVariantSummary, fontSize = 12.sp)
                    }

                    if (exactEditorVisible) {
                        Spacer(Modifier.height(12.dp))
                        ExactDurationEditor(
                            minutes = exactMinutes,
                            seconds = exactSeconds,
                            onMinutesChange = { text ->
                                exactMinutes = text.filter(Char::isDigit).take(3)
                            },
                            onSecondsChange = { text ->
                                exactSeconds = text.filter(Char::isDigit).take(2)
                            },
                            onCancel = {
                                exactEditorVisible = false
                                focusManager.clearFocus(force = true)
                            },
                            onApply = {
                                val minutes = exactMinutes.toIntOrNull()?.coerceIn(0, 600) ?: 0
                                val seconds = exactSeconds.toIntOrNull()?.coerceIn(0, 59) ?: 0
                                val total = if (minutes >= 600) {
                                    MAX_CUSTOM_SECONDS
                                } else {
                                    (minutes * 60 + seconds).coerceIn(0, MAX_CUSTOM_SECONDS)
                                }
                                sliderSeconds = total
                                exactEditorVisible = false
                                focusManager.clearFocus(force = true)
                                if (total == 0) onCancelTimer() else onCustomDuration(total * 1_000L)
                            },
                        )
                    }

                    Spacer(Modifier.height(18.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(scheme.surfaceContainerHigh.copy(alpha = 0.55f))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.player_sleep_timer_exit_title),
                                color = scheme.onSurface,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.player_sleep_timer_exit_summary),
                                color = scheme.onSurfaceVariantSummary,
                                fontSize = 12.sp,
                            )
                        }
                        Switch(
                            checked = exitWhenFinished,
                            onCheckedChange = onExitWhenFinishedChange,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExactDurationEditor(
    minutes: String,
    seconds: String,
    onMinutesChange: (String) -> Unit,
    onSecondsChange: (String) -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val minutesFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        minutesFocusRequester.requestFocus()
        keyboardController?.show()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.55f))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NumericDurationField(
                value = minutes,
                onValueChange = onMinutesChange,
                suffix = stringResource(R.string.player_sleep_timer_minutes_unit),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(minutesFocusRequester),
            )
            NumericDurationField(
                value = seconds,
                onValueChange = onSecondsChange,
                suffix = stringResource(R.string.player_sleep_timer_seconds_unit),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(text = stringResource(R.string.common_cancel), onClick = onCancel)
            Spacer(Modifier.width(8.dp))
            TextButton(text = stringResource(R.string.common_confirm), onClick = onApply)
        }
    }
}

@Composable
private fun NumericDurationField(
    value: String,
    onValueChange: (String) -> Unit,
    suffix: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.background.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(
                color = scheme.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            cursorBrush = SolidColor(scheme.primary),
            modifier = Modifier.weight(1f),
        )
        Text(suffix, color = scheme.onSurfaceVariantSummary, fontSize = 13.sp)
    }
}

private fun fixedSleepTimerItems(): List<Pair<Int, Int>> = listOf(
    1 to R.string.sleep_timer_10,
    2 to R.string.sleep_timer_15,
    3 to R.string.sleep_timer_20,
    4 to R.string.sleep_timer_30,
    5 to R.string.sleep_timer_45,
    6 to R.string.sleep_timer_60,
    7 to R.string.sleep_timer_90,
    8 to R.string.sleep_timer_current,
    9 to R.string.sleep_timer_3_songs,
    10 to R.string.sleep_timer_5_songs,
)

private fun formatSleepDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1_000L).coerceAtMost(MAX_CUSTOM_SECONDS.toLong())
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "%d:%02d:%02d".format(hours, minutes, seconds)
        minutes > 0L -> "%d:%02d".format(minutes, seconds)
        else -> "${seconds}s"
    }
}

/**
 * The visible slider is deliberately non-linear. The first quarter is 0..60 seconds so 5/10/30
 * second choices are physically selectable with a finger, the middle section covers 1..60 minutes,
 * and the final section covers 60..600 minutes. The persisted duration is always exact seconds.
 */
private fun sliderPositionToSleepTimerSeconds(position: Float): Int {
    val p = position.coerceIn(0f, 1f)
    return when {
        p <= 0.25f -> ((p / 0.25f) * 60f).roundToInt()
        p <= 0.55f -> {
            val fraction = (p - 0.25f) / 0.30f
            (60f + fraction * (3_600f - 60f)).roundToInt()
        }
        else -> {
            val fraction = (p - 0.55f) / 0.45f
            (3_600f + fraction * (MAX_CUSTOM_SECONDS - 3_600f)).roundToInt()
        }
    }.coerceIn(0, MAX_CUSTOM_SECONDS)
}

private fun sleepTimerSecondsToSliderPosition(seconds: Int): Float {
    val value = seconds.coerceIn(0, MAX_CUSTOM_SECONDS).toFloat()
    return when {
        value <= 60f -> (value / 60f) * 0.25f
        value <= 3_600f -> 0.25f + ((value - 60f) / (3_600f - 60f)) * 0.30f
        else -> 0.55f + ((value - 3_600f) / (MAX_CUSTOM_SECONDS - 3_600f)) * 0.45f
    }.coerceIn(0f, 1f)
}

private const val SLEEP_TIMER_MODE_OFF = 0
private const val SLEEP_TIMER_MODE_CUSTOM = 4
private const val MAX_CUSTOM_SECONDS = 600 * 60
