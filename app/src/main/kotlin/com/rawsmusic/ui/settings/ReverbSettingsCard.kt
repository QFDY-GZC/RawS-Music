package com.rawsmusic.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rawsmusic.R
import com.rawsmusic.core.ui.widget.ActivityOverlayBackOwner
import com.rawsmusic.core.ui.widget.RawWindowDropdownPreference
import com.rawsmusic.core.ui.scene.LocalRawWindowLayoutProfile
import com.rawsmusic.module.player.dsp.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.Locale

@Composable private fun spaceName(space: ReverbSpace): String = stringResource(when(space) {
    ReverbSpace.SMALL_ROOM->R.string.reverb_small_room
    ReverbSpace.STUDIO->R.string.reverb_studio
    ReverbSpace.CHAMBER->R.string.reverb_chamber
    ReverbSpace.HALL->R.string.reverb_hall
    ReverbSpace.CATHEDRAL->R.string.reverb_cathedral
})

@Composable private fun presetName(preset: ReverbPreset): String = when(preset.name) {
    "small_room","studio","chamber","hall","cathedral" -> spaceName(preset.settings.space)
    else -> stringResource(when(preset.name) {
        "vocal"->R.string.reverb_vocal
        "warm_vocal"->R.string.reverb_warm_vocal
        "acoustic"->R.string.reverb_acoustic
        "piano"->R.string.reverb_piano
        "jazz_club"->R.string.reverb_jazz_club
        "live_stage"->R.string.reverb_live_stage
        "auditorium"->R.string.reverb_auditorium
        "arena"->R.string.reverb_arena
        "ambient"->R.string.reverb_ambient
        else->R.string.reverb_cinematic
    })
}

@Composable internal fun ReverbSettingsCard(controller: ReverbController?) {
    if(controller==null){SettingsCard { SwitchPreference(title=stringResource(R.string.reverb_title),
        summary=stringResource(R.string.settings_fft_convolver_engine_unavailable),checked=false,enabled=false,onCheckedChange={}) };return}
    val enabled by controller.isEnabled.collectAsState()
    val params by controller.settings.collectAsState()
    val custom by controller.customPresets.collectAsState()
    var advanced by rememberSaveable { mutableStateOf(false) }
    var saveOpen by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val selectedBuiltIn=ReverbPresets.builtIn.firstOrNull { it.settings==params }
    val selectedName=selectedBuiltIn?.let { presetName(it) }
        ?: custom.firstOrNull { it.settings==params }?.name ?: stringResource(R.string.reverb_custom)
    val presetEntries=ReverbSpace.entries.map { space ->
        DropdownItem(text=spaceName(space),selected=selectedBuiltIn?.settings?.space==space,
            children=ReverbPresets.builtIn.filter { it.settings.space==space }.map { preset ->
                DropdownItem(text=presetName(preset),selected=preset.settings==params,
                    onClick={controller.selectPreset(preset)})
            })
    } + if(custom.isEmpty()) emptyList() else listOf(
        DropdownItem(text=stringResource(R.string.reverb_custom),
            selected=selectedBuiltIn==null && custom.any { it.settings==params },
            children=custom.map { preset -> DropdownItem(text=preset.name,selected=preset.settings==params,
                onClick={controller.selectPreset(preset)}) }))
    SettingsCard {
        SwitchPreference(title=stringResource(R.string.reverb_title),summary=stringResource(R.string.reverb_summary),
            checked=enabled,onCheckedChange=controller::setEnabled)
        if(enabled) {
            RawWindowDropdownPreference(title=stringResource(R.string.reverb_preset),
                entry=DropdownEntry(items=presetEntries),valueOverride=selectedName,maxHeight=520.dp)
            RawWindowDropdownPreference(title=stringResource(R.string.reverb_space),
                entry=DropdownEntry(items=ReverbSpace.entries.map { space ->
                    DropdownItem(text=spaceName(space),selected=space==params.space,
                        onClick={controller.updateSettings(params.withSpace(space))})
                }),maxHeight=520.dp)
            ReverbSlider(R.string.reverb_mix,params.mix,0f..1f,"%",100f){controller.updateSettings(params.copy(mix=it))}
            ReverbSlider(R.string.reverb_size,params.roomSize,0f..1f,"%",100f){controller.updateSettings(params.copy(roomSize=it))}
            ReverbSlider(R.string.reverb_decay,params.decaySeconds,.15f..12f," s",1f,2){controller.updateSettings(params.copy(decaySeconds=it))}
            ReverbSlider(R.string.reverb_pre_delay,params.preDelayMs,0f..200f," ms"){controller.updateSettings(params.copy(preDelayMs=it))}
            ReverbSlider(R.string.reverb_damping,params.damping,0f..1f,"%",100f){controller.updateSettings(params.copy(damping=it))}
            ReverbSlider(R.string.reverb_diffusion,params.diffusion,0f..1f,"%",100f){controller.updateSettings(params.copy(diffusion=it))}
            ReverbSlider(R.string.reverb_width,params.width,0f..1.5f,"%",100f){controller.updateSettings(params.copy(width=it))}
            SwitchPreference(title=stringResource(R.string.reverb_advanced),checked=advanced,onCheckedChange={advanced=it})
            if(advanced) {
                ReverbSlider(R.string.reverb_low_cut,params.lowCutHz,20f..1000f," Hz"){controller.updateSettings(params.copy(lowCutHz=it))}
                ReverbSlider(R.string.reverb_high_cut,params.highCutHz,1000f..20000f," Hz"){controller.updateSettings(params.copy(highCutHz=it))}
                ReverbSlider(R.string.reverb_early_late,params.earlyLate,0f..1f,"%",100f){controller.updateSettings(params.copy(earlyLate=it))}
                ReverbSlider(R.string.reverb_gain,params.gainDb,-12f..6f," dB",1f,1){controller.updateSettings(params.copy(gainDb=it))}
            }
            ArrowPreference(title=stringResource(R.string.reverb_save),
                summary=if(custom.size>=20)stringResource(R.string.reverb_preset_limit) else null,
                onClick={name="";saveOpen=true})
            if(custom.isNotEmpty()) {
                RawWindowDropdownPreference(title=stringResource(R.string.reverb_delete_saved),
                    entry=DropdownEntry(items=custom.map { preset ->
                        DropdownItem(text=preset.name,onClick={controller.deletePreset(preset.name)})
                    }),showValue=false,maxHeight=520.dp)
            }
            ArrowPreference(title=stringResource(R.string.reverb_reset),onClick=controller::resetDefaults)
        }
    }
    ActivityOverlayBackOwner(active=saveOpen)
    WindowDialog(show=saveOpen,title=stringResource(R.string.reverb_save),
        modifier=Modifier.widthIn(max=LocalRawWindowLayoutProfile.current.dialogMaxWidth),
        onDismissRequest={saveOpen=false}) {
        Column(Modifier.fillMaxWidth().heightIn(max=400.dp).verticalScroll(rememberScrollState())) {
            TextField(value=name,onValueChange={name=it.take(40)},singleLine=true,
                modifier=Modifier.fillMaxWidth(),label=stringResource(R.string.reverb_name))
            if(custom.size>=20)Text(stringResource(R.string.reverb_preset_limit))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                TextButton(modifier=Modifier.weight(1f),text=stringResource(R.string.reverb_close),onClick={saveOpen=false})
                TextButton(modifier=Modifier.weight(1f),text=stringResource(R.string.reverb_save),enabled=name.isNotBlank() &&
                    (custom.size<20 || custom.any { it.name==name.trim() }),onClick={controller.savePreset(name);saveOpen=false})
            }
        }
    }
}

@Composable private fun ReverbSlider(title: Int,value: Float,range: ClosedFloatingPointRange<Float>,
    unit: String,scale: Float=1f,decimals: Int=0,onChange: (Float)->Unit) {
    SliderPreference(title=stringResource(title),value=value.coerceIn(range),valueRange=range,
        valueText=String.format(Locale.getDefault(),"%.${decimals}f%s",value*scale,unit),onValueChange=onChange)
}
