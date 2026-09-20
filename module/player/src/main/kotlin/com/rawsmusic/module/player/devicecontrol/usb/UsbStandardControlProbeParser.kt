package com.rawsmusic.module.player.devicecontrol.usb

import com.rawsmusic.module.player.devicecontrol.DeviceConnectionKind
import com.rawsmusic.module.player.devicecontrol.DeviceControlAccess
import com.rawsmusic.module.player.devicecontrol.DeviceControlBackendAddress
import com.rawsmusic.module.player.devicecontrol.DeviceControlCapability
import com.rawsmusic.module.player.devicecontrol.DeviceControlDevice
import com.rawsmusic.module.player.devicecontrol.DeviceControlId
import com.rawsmusic.module.player.devicecontrol.DeviceControlSnapshot
import com.rawsmusic.module.player.devicecontrol.DeviceNumericControl
import com.rawsmusic.module.player.devicecontrol.DeviceNumericRange
import com.rawsmusic.module.player.devicecontrol.DeviceToggleControl
import com.rawsmusic.module.player.devicecontrol.GraphicEqBand
import com.rawsmusic.module.player.devicecontrol.ParametricEqBand
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Maps the native read-only UAC probe into the transport-neutral Hardware Device Control model. */
object UsbStandardControlProbeParser {
    fun parse(json: String?, generation: Long): DeviceControlSnapshot? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(json)
            val deviceJson = root.optJSONObject("device") ?: JSONObject()
            val vendorId = deviceJson.optInt("vendorId", 0).takeIf { it != 0 }
            val productId = deviceJson.optInt("productId", 0).takeIf { it != 0 }
            val rawName = deviceJson.optString("name", "").trim()
            val displayName = rawName.ifEmpty { "USB Audio Device" }
            val stableId = buildString {
                append("usb:")
                append(vendorId?.toString(16)?.padStart(4, '0') ?: "unknown")
                append(':')
                append(productId?.toString(16)?.padStart(4, '0') ?: "unknown")
            }
            val device = DeviceControlDevice(
                stableId = stableId,
                displayName = displayName,
                connectionKind = DeviceConnectionKind.USB,
                vendorId = vendorId,
                productId = productId,
            )

            val capabilities = buildList {
                addAll(parseFeatureControls(
                    root.optJSONArray("featureNumeric"),
                    root.optJSONArray("featureToggles"),
                ))
                addAll(parseGraphicEq(root.optJSONArray("graphicEq")))
                parseParametricEq(root.optJSONArray("parametricEq"))?.let(::add)
                addAll(parseDynamics(root.optJSONArray("dynamics")))
            }
            val diagnostics = root.optJSONArray("diagnostics").stringList()
            DeviceControlSnapshot(
                generation = generation,
                device = device,
                capabilities = capabilities,
                probeState = if (capabilities.isEmpty()) {
                    DeviceControlSnapshot.ProbeState.UNSUPPORTED
                } else {
                    DeviceControlSnapshot.ProbeState.READY
                },
                message = diagnostics.takeIf { it.isNotEmpty() }?.joinToString("; "),
            )
        }.getOrNull()
    }

    private data class ParsedFeatureNumeric(
        val kind: String,
        val iface: Int,
        val entity: Int,
        val channel: Int,
        val control: DeviceNumericControl,
    )

    private data class ParsedFeatureToggle(
        val kind: String,
        val iface: Int,
        val entity: Int,
        val channel: Int,
        val control: DeviceToggleControl,
    )

    private fun parseFeatureControls(
        numericArray: JSONArray?,
        toggleArray: JSONArray?,
    ): List<DeviceControlCapability> {
        val numeric = buildList {
            if (numericArray != null) for (i in 0 until numericArray.length()) {
                val item = numericArray.optJSONObject(i) ?: continue
                val iface = item.optInt("interface", -1)
                val entity = item.optInt("entityId", -1)
                val channel = item.optInt("channel", 0)
                val kind = item.optString("kind", "")
                if (iface < 0 || entity < 0 || kind.isBlank()) continue
                val label = featureLabel(kind)
                val prefix = "usb:uac:fu:$entity:ch:$channel:$kind"
                val control = parseNumeric(item.optJSONObject("control"), prefix, label, iface, entity, channel)
                    ?: continue
                add(ParsedFeatureNumeric(kind, iface, entity, channel, control))
            }
        }
        val toggles = buildList {
            if (toggleArray != null) for (i in 0 until toggleArray.length()) {
                val item = toggleArray.optJSONObject(i) ?: continue
                val iface = item.optInt("interface", -1)
                val entity = item.optInt("entityId", -1)
                val channel = item.optInt("channel", 0)
                val kind = item.optString("kind", "")
                if (iface < 0 || entity < 0 || kind.isBlank()) continue
                val label = featureLabel(kind)
                val prefix = "usb:uac:fu:$entity:ch:$channel:$kind"
                val control = parseToggle(item.optJSONObject("control"), prefix, label, iface, entity, channel)
                    ?: continue
                add(ParsedFeatureToggle(kind, iface, entity, channel, control))
            }
        }

        return buildList {
            val featureUnits = (numeric.map { it.iface to it.entity } + toggles.map { it.iface to it.entity }).distinct()
            for ((_, entity) in featureUnits) {
                val volumes = numeric.filter { it.entity == entity && it.kind == "volume" }
                    .sortedBy { it.channel }
                    .map { entry -> entry.control.copy(label = channelLabel(entry.channel)) }
                val mutes = toggles.filter { it.entity == entity && it.kind == "mute" }
                    .sortedBy { it.channel }
                    .map { entry -> entry.control.copy(label = channelLabel(entry.channel)) }
                if (volumes.isNotEmpty() || mutes.isNotEmpty()) {
                    add(
                        DeviceControlCapability.Volume(
                            id = DeviceControlId("usb:uac:fu:$entity:volume"),
                            label = "Hardware Volume",
                            channels = volumes,
                            mutes = mutes,
                        )
                    )
                }
            }

            numeric.filter { it.kind != "volume" }.forEach { entry ->
                val label = featureLabel(entry.kind).withChannel(entry.channel)
                add(
                    DeviceControlCapability.Range(
                        id = DeviceControlId("usb:uac:fu:${entry.entity}:ch:${entry.channel}:${entry.kind}"),
                        label = label,
                        control = entry.control.copy(label = label),
                    )
                )
            }
            toggles.filter { it.kind != "mute" }.forEach { entry ->
                val label = featureLabel(entry.kind).withChannel(entry.channel)
                add(
                    DeviceControlCapability.Toggle(
                        id = DeviceControlId("usb:uac:fu:${entry.entity}:ch:${entry.channel}:${entry.kind}"),
                        label = label,
                        control = entry.control.copy(label = label),
                    )
                )
            }
        }
    }

    private fun featureLabel(kind: String): String = when (kind) {
        "volume" -> "Volume"
        "mute" -> "Mute"
        "bass" -> "Bass"
        "mid" -> "Mid"
        "treble" -> "Treble"
        "automatic_gain" -> "Automatic Gain Control"
        "delay" -> "Delay"
        "bass_boost" -> "Bass Boost"
        "loudness" -> "Loudness"
        "input_gain" -> "Input Gain"
        "input_gain_pad" -> "Input Gain Pad"
        "phase_inverter" -> "Phase Inverter"
        "high_pass_filter" -> "High-Pass Filter"
        else -> kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun channelLabel(channel: Int): String = if (channel == 0) "Master" else "CH$channel"

    private fun String.withChannel(channel: Int): String = if (channel == 0) this else "$this CH$channel"

    private fun parseGraphicEq(array: JSONArray?): List<DeviceControlCapability.GraphicEq> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val eq = array.optJSONObject(i) ?: continue
                val iface = eq.optInt("interface", -1)
                val entity = eq.optInt("entityId", -1)
                val channel = eq.optInt("channel", 0)
                val selector = eq.optInt("selector", 6)
                if (iface < 0 || entity < 0) continue
                val access = parseAccess(eq.optString("access"))
                if (!access.isPresent) continue
                val prefix = "usb:uac:fu:$entity:ch:$channel:graphic_eq"
                val bandsJson = eq.optJSONArray("bands") ?: continue
                val bands = buildList {
                    for (b in 0 until bandsJson.length()) {
                        val band = bandsJson.optJSONObject(b) ?: continue
                        val bandNumber = band.optInt("bandNumber", -1)
                        if (bandNumber < 0) continue
                        val frequency = band.nullableDouble("frequencyHz")
                        val id = DeviceControlId("$prefix:band:$bandNumber")
                        add(
                            GraphicEqBand(
                                id = id,
                                frequencyHz = frequency,
                                label = frequency?.let(::formatFrequency),
                                gain = DeviceNumericControl(
                                    id = id,
                                    label = frequency?.let(::formatFrequency) ?: "Band $bandNumber",
                                    unit = "dB",
                                    access = access,
                                    current = band.nullableDouble("currentDb"),
                                    ranges = parseRanges(band.optJSONArray("ranges")),
                                    address = DeviceControlBackendAddress.UsbAudioClass(
                                        interfaceNumber = iface,
                                        entityId = entity,
                                        selector = selector,
                                        channel = channel,
                                        elementIndex = bandNumber,
                                    ),
                                ),
                            )
                        )
                    }
                }
                if (bands.isNotEmpty()) {
                    add(
                        DeviceControlCapability.GraphicEq(
                            id = DeviceControlId(prefix),
                            label = if (channel == 0) "Graphic EQ" else "Graphic EQ CH$channel",
                            bands = bands,
                        )
                    )
                }
            }
        }
    }

    private fun parseParametricEq(array: JSONArray?): DeviceControlCapability.ParametricEq? {
        if (array == null || array.length() == 0) return null
        val bands = buildList {
            for (i in 0 until array.length()) {
                val section = array.optJSONObject(i) ?: continue
                val iface = section.optInt("interface", -1)
                val entity = section.optInt("entityId", -1)
                val channel = section.optInt("channel", 0)
                if (iface < 0 || entity < 0) continue
                val prefix = "usb:uac:effect:$entity:ch:$channel:peq"
                val enabled = parseToggle(section.optJSONObject("enabled"), prefix, "Enabled", iface, entity, channel)
                val frequency = parseNumeric(section.optJSONObject("centerFrequency"), prefix, "Frequency", iface, entity, channel)
                val q = parseNumeric(section.optJSONObject("q"), prefix, "Q", iface, entity, channel)
                val gain = parseNumeric(section.optJSONObject("gain"), prefix, "Gain", iface, entity, channel)
                if (enabled == null && frequency == null && q == null && gain == null) continue
                add(
                    ParametricEqBand(
                        id = DeviceControlId(prefix),
                        label = "Band ${size + 1}",
                        enabled = enabled,
                        frequency = frequency,
                        q = q,
                        gain = gain,
                    )
                )
            }
        }
        if (bands.isEmpty()) return null
        return DeviceControlCapability.ParametricEq(
            id = DeviceControlId("usb:uac:parametric_eq"),
            label = "Parametric EQ",
            bands = bands,
        )
    }

    private fun parseDynamics(array: JSONArray?): List<DeviceControlCapability.Dynamics> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val iface = item.optInt("interface", -1)
                val entity = item.optInt("entityId", -1)
                val channel = item.optInt("channel", 0)
                if (iface < 0 || entity < 0) continue
                val prefix = "usb:uac:effect:$entity:ch:$channel:dynamics"
                val capability = DeviceControlCapability.Dynamics(
                    id = DeviceControlId(prefix),
                    label = if (channel == 0) "Dynamic Range Compressor" else "DRC CH$channel",
                    enabled = parseToggle(item.optJSONObject("enabled"), prefix, "Enabled", iface, entity, channel),
                    ratio = parseNumeric(item.optJSONObject("compressionRatio"), prefix, "Ratio", iface, entity, channel),
                    maxAmplitude = parseNumeric(item.optJSONObject("maxAmplitude"), prefix, "Max amplitude", iface, entity, channel),
                    threshold = parseNumeric(item.optJSONObject("threshold"), prefix, "Threshold", iface, entity, channel),
                    attack = parseNumeric(item.optJSONObject("attack"), prefix, "Attack", iface, entity, channel),
                    release = parseNumeric(item.optJSONObject("release"), prefix, "Release", iface, entity, channel),
                )
                if (listOf(capability.enabled, capability.ratio, capability.maxAmplitude,
                        capability.threshold, capability.attack, capability.release).any { it != null }) {
                    add(capability)
                }
            }
        }
    }

    private fun parseNumeric(
        json: JSONObject?,
        prefix: String,
        label: String,
        iface: Int,
        entity: Int,
        channel: Int,
    ): DeviceNumericControl? {
        json ?: return null
        val access = parseAccess(json.optString("access"))
        if (!access.isPresent) return null
        val selector = json.optInt("selector", -1)
        if (selector < 0) return null
        val id = DeviceControlId("$prefix:selector:$selector")
        return DeviceNumericControl(
            id = id,
            label = label,
            unit = json.optString("unit", "").takeIf { it.isNotBlank() },
            access = access,
            current = json.nullableDouble("current"),
            ranges = parseRanges(json.optJSONArray("ranges")),
            address = DeviceControlBackendAddress.UsbAudioClass(iface, entity, selector, channel),
        )
    }

    private fun parseToggle(
        json: JSONObject?,
        prefix: String,
        label: String,
        iface: Int,
        entity: Int,
        channel: Int,
    ): DeviceToggleControl? {
        json ?: return null
        val access = parseAccess(json.optString("access"))
        if (!access.isPresent) return null
        val selector = json.optInt("selector", -1)
        if (selector < 0) return null
        return DeviceToggleControl(
            id = DeviceControlId("$prefix:selector:$selector"),
            label = label,
            access = access,
            current = if (json.isNull("current")) null else json.optBoolean("current"),
            address = DeviceControlBackendAddress.UsbAudioClass(iface, entity, selector, channel),
        )
    }

    private fun parseRanges(array: JSONArray?): List<DeviceNumericRange> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val range = array.optJSONObject(i) ?: continue
                val min = range.nullableDouble("min") ?: continue
                val max = range.nullableDouble("max") ?: continue
                if (max < min) continue
                val step = range.nullableDouble("step")?.takeIf { it > 0.0 }
                add(DeviceNumericRange(min = min, max = max, step = step))
            }
        }
    }

    private fun parseAccess(raw: String): DeviceControlAccess = when (raw) {
        "read_only" -> DeviceControlAccess.READ_ONLY
        "read_write" -> DeviceControlAccess.READ_WRITE
        else -> DeviceControlAccess.ABSENT
    }

    private fun JSONObject.nullableDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key).takeIf { it.isFinite() }
    }

    private fun JSONArray?.stringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) optString(i).takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun formatFrequency(hz: Double): String = when {
        hz >= 1000.0 -> String.format(Locale.US, if (hz % 1000.0 == 0.0) "%.0f kHz" else "%.1f kHz", hz / 1000.0)
        hz % 1.0 == 0.0 -> String.format(Locale.US, "%.0f Hz", hz)
        else -> String.format(Locale.US, "%.1f Hz", hz)
    }
}
