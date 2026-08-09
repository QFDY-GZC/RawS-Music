package com.rawsmusic.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rawsmusic.R
import com.rawsmusic.module.data.prefs.AudioFocusPreferences
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun AudioFocusSettingsScreen(
    onBack: () -> Unit,
    onSettingsChanged: () -> Unit
) {
    val context = LocalContext.current
    var resumeAfterCall by remember { mutableStateOf(AudioFocusPreferences.resumeAfterCall) }
    var resumeOnStart by remember { mutableStateOf(AudioFocusPreferences.resumeOnStart) }
    var resumeOnResume by remember { mutableStateOf(AudioFocusPreferences.resumeOnResume) }
    var handleTransient by remember { mutableStateOf(AudioFocusPreferences.handleTransientChangesAndCalls) }
    var resumeOnFocusGain by remember { mutableStateOf(AudioFocusPreferences.resumeOnFocusGain) }
    var allowDuck by remember { mutableStateOf(AudioFocusPreferences.allowDuck) }
    var pauseOnPermanentLoss by remember { mutableStateOf(AudioFocusPreferences.pauseOnPermanentLoss) }

    fun hasPhonePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    val phonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        onSettingsChanged()
    }

    fun requestPhonePermissionIfNeeded() {
        if (!hasPhonePermission()) {
            AudioFocusPreferences.phonePermissionPrompted = true
            phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }

    LaunchedEffect(Unit) {
        if (!AudioFocusPreferences.phonePermissionPrompted &&
            (resumeAfterCall || handleTransient) &&
            !hasPhonePermission()
        ) {
            AudioFocusPreferences.phonePermissionPrompted = true
            phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }

    SettingsPage(title = stringResource(R.string.settings_audio_focus_title), onBack = onBack) {
        SettingsCard {
            SectionHeader(stringResource(R.string.settings_audio_focus_resume_section))
            Spacer(Modifier.height(4.dp))

            SwitchPreference(
                title = stringResource(R.string.settings_audio_focus_resume_after_call),
                summary = stringResource(R.string.settings_audio_focus_resume_after_call_summary),
                checked = resumeAfterCall,
                onCheckedChange = { checked ->
                    resumeAfterCall = checked
                    AudioFocusPreferences.resumeAfterCall = checked
                    if (checked) requestPhonePermissionIfNeeded()
                    onSettingsChanged()
                }
            )
            SwitchPreference(
                title = stringResource(R.string.settings_audio_focus_resume_on_start),
                summary = stringResource(R.string.settings_audio_focus_resume_on_start_summary),
                checked = resumeOnStart,
                onCheckedChange = { checked ->
                    resumeOnStart = checked
                    AudioFocusPreferences.resumeOnStart = checked
                    onSettingsChanged()
                }
            )
            SwitchPreference(
                title = stringResource(R.string.settings_audio_focus_resume_on_resume),
                summary = stringResource(R.string.settings_audio_focus_resume_on_resume_summary),
                checked = resumeOnResume,
                onCheckedChange = { checked ->
                    resumeOnResume = checked
                    AudioFocusPreferences.resumeOnResume = checked
                    onSettingsChanged()
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader(stringResource(R.string.settings_audio_focus_changes_section))
            Spacer(Modifier.height(4.dp))

            SwitchPreference(
                title = stringResource(R.string.settings_audio_focus_transient),
                summary = stringResource(R.string.settings_audio_focus_transient_summary),
                checked = handleTransient,
                onCheckedChange = { checked ->
                    handleTransient = checked
                    AudioFocusPreferences.handleTransientChangesAndCalls = checked
                    if (checked) requestPhonePermissionIfNeeded()
                    onSettingsChanged()
                }
            )
            SwitchPreference(
                title = stringResource(R.string.settings_audio_focus_resume_on_gain),
                summary = stringResource(R.string.settings_audio_focus_resume_on_gain_summary),
                checked = resumeOnFocusGain,
                onCheckedChange = { checked ->
                    resumeOnFocusGain = checked
                    AudioFocusPreferences.resumeOnFocusGain = checked
                    onSettingsChanged()
                }
            )
            SwitchPreference(
                title = stringResource(R.string.settings_audio_focus_allow_duck),
                summary = stringResource(R.string.settings_audio_focus_allow_duck_summary),
                checked = allowDuck,
                onCheckedChange = { checked ->
                    allowDuck = checked
                    AudioFocusPreferences.allowDuck = checked
                    onSettingsChanged()
                }
            )
            SwitchPreference(
                title = stringResource(R.string.settings_audio_focus_permanent_loss),
                summary = stringResource(R.string.settings_audio_focus_permanent_loss_summary),
                checked = pauseOnPermanentLoss,
                onCheckedChange = { checked ->
                    pauseOnPermanentLoss = checked
                    AudioFocusPreferences.pauseOnPermanentLoss = checked
                    onSettingsChanged()
                }
            )
        }
    }
}
