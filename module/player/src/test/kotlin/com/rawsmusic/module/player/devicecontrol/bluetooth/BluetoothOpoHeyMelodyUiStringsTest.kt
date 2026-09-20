package com.rawsmusic.module.player.devicecontrol.bluetooth

import kotlin.test.Test
import kotlin.test.assertEquals

class BluetoothOpoHeyMelodyUiStringsTest {
    @Test
    fun free4AndSharedModesUseHeyMelodyZhCnNames() {
        assertEquals("关闭", BluetoothOpoHeyMelodyUiStrings.ancMode(1, "fallback"))
        assertEquals("强降噪", BluetoothOpoHeyMelodyUiStrings.ancMode(4, "fallback"))
        assertEquals("自适应", BluetoothOpoHeyMelodyUiStrings.ancMode(10, "fallback"))
        assertEquals("至臻原音", BluetoothOpoHeyMelodyUiStrings.eqMode(26, "fallback"))
        assertEquals("纯享人声", BluetoothOpoHeyMelodyUiStrings.eqMode(28, "fallback"))
        assertEquals("澎湃低音", BluetoothOpoHeyMelodyUiStrings.eqMode(29, "fallback"))
        assertEquals("活力动感", BluetoothOpoHeyMelodyUiStrings.eqMode(34, "fallback"))
        assertEquals("佩戴检测", BluetoothOpoHeyMelodyUiStrings.feature(4, "fallback"))
        assertEquals("入睡暂停音乐播放", BluetoothOpoHeyMelodyUiStrings.feature(58, "fallback"))
        assertEquals("打开", BluetoothOpoHeyMelodyUiStrings.spatialType(1, listOf(0, 1)))
        assertEquals("固定", BluetoothOpoHeyMelodyUiStrings.spatialType(1, listOf(0, 1, 2)))
        assertEquals("头部跟踪", BluetoothOpoHeyMelodyUiStrings.spatialType(2, listOf(0, 1, 2)))
    }
}
