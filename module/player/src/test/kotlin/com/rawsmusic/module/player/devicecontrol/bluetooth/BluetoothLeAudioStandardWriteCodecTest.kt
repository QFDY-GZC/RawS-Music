package com.rawsmusic.module.player.devicecontrol.bluetooth

private fun ByteArray.hex() = joinToString(" ") { "%02x".format(it.toInt() and 0xff) }

fun main() {
    check(BluetoothLeAudioStandard.encodeVcsSetAbsoluteVolume(7, 200)?.hex() == "04 07 c8")
    check(BluetoothLeAudioStandard.encodeVcsMute(8, true)?.hex() == "06 08")
    check(BluetoothLeAudioStandard.encodeVcsMute(8, false)?.hex() == "05 08")
    check(BluetoothLeAudioStandard.encodeVocsSetVolumeOffset(9, -255)?.hex() == "01 09 01 ff")
    check(BluetoothLeAudioStandard.encodeAicsSetGain(10, -12)?.hex() == "01 0a f4")
    check(BluetoothLeAudioStandard.encodeAicsMute(11, true)?.hex() == "03 0b")
    check(BluetoothLeAudioStandard.encodeAicsGainMode(12, true)?.hex() == "05 0c")

    val props = BluetoothLeAudioStandard.AicsGainSettingProperties(
        gainSettingUnits = 5,
        minimumGainSetting = -20,
        maximumGainSetting = 20,
    )
    check(props.stepDb == 0.5)
    check(props.dbToRaw(2.4) == 5)
    check(props.dbToRaw(11.0) == null)
    println("BluetoothLeAudioStandardWriteCodecTest: OK")
}
