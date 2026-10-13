package com.rawsmusic.module.player.dsp

import org.junit.Assert.*
import org.junit.Test

class ReverbSettingsTest {
    @Test fun changingSpaceLoadsItsAcousticsAndKeepsTheUserMix() {
        val previous=ReverbSettings(mix=.4f,gainDb=-3f)
        val hall=previous.withSpace(ReverbSpace.HALL)
        val preset=ReverbPresets.builtIn.first { it.settings.space==ReverbSpace.HALL }.settings
        assertEquals(preset.copy(mix=.4f,gainDb=-3f),hall)
        assertEquals(hall,hall.withSpace(ReverbSpace.HALL))
    }
    @Test fun fullPresetCollectionNeverSilentlyDeletesAnOlderPreset() {
        val existing=(0 until 20).map { ReverbPreset("Preset $it",ReverbSettings()) }
        assertNull(storeReverbPreset(existing,"New",ReverbSettings()))
        val replaced=storeReverbPreset(existing,"Preset 4",ReverbSettings(mix=.8f))!!
        assertEquals(20,replaced.size)
        assertEquals(.8f,replaced.first { it.name=="Preset 4" }.settings.mix)
        assertEquals("Preset 0",replaced.first().name)
    }
    @Test fun corruptPreferencesNeverReachNativeFeedback() {
        val p=ReverbSettings(mix=Float.NaN, decaySeconds=Float.POSITIVE_INFINITY,
            roomSize=4f, preDelayMs=-100f, lowCutHz=0f, width=10f).sanitized()
        assertTrue(p.nativeValues().all { it.isFinite() })
        assertEquals(1f,p.roomSize); assertEquals(0f,p.preDelayMs); assertEquals(20f,p.lowCutHz)
        assertEquals(1.5f,p.width)
    }
    @Test fun settingsPersistWithoutChangingParameters() {
        val p=ReverbSettings(space=ReverbSpace.CATHEDRAL,mix=.37f,decaySeconds=4.8f,preDelayMs=43f)
        assertEquals(p,ReverbSettings.decode(p.encode()))
    }
    @Test fun incompleteOrNewerSchemaUsesSafeDefaults() {
        assertEquals(ReverbSettings(),ReverbSettings.decode("v2|0,1,2"))
        assertEquals(ReverbSettings(),ReverbSettings.decode("v1|broken"))
    }
    @Test fun spacesHaveDifferentDecayAndReflectionConfigurations() {
        assertTrue(ReverbPresets.builtIn.size>=15)
        assertEquals(ReverbPresets.builtIn.size,ReverbPresets.builtIn.map { it.name }.distinct().size)
        assertEquals(ReverbPresets.builtIn.size,ReverbPresets.builtIn.map { it.settings }.distinct().size)
        ReverbPresets.builtIn.forEach { assertEquals(it.settings,it.settings.sanitized()) }
        assertTrue(ReverbPresets.builtIn.map { it.settings.decaySeconds }.distinct().size>=4)
        assertTrue(ReverbPresets.builtIn.map { it.settings.space }.distinct().size==5)
    }
}
