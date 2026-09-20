package com.rawsmusic.module.player.devicecontrol.bluetooth

/** Stable snapshot of Android's current Bluetooth audio route candidate. */
data class BluetoothAudioRouteSnapshot(
    val audioDeviceId: Int,
    val audioDeviceType: Int,
    val productName: String?,
    /** AudioDeviceInfo address is device-specific and is not assumed to always be a Bluetooth MAC. */
    val routeAddress: String?,
)

data class BluetoothKnownDeviceIdentity(
    val address: String,
    val name: String?,
)

sealed interface BluetoothRouteIdentityMatch {
    data class Matched(
        val device: BluetoothKnownDeviceIdentity,
        val confidence: Confidence,
    ) : BluetoothRouteIdentityMatch

    data class Unresolved(val reason: String) : BluetoothRouteIdentityMatch

    enum class Confidence {
        ROUTE_ADDRESS,
        UNIQUE_BONDED_NAME,
    }
}

/**
 * Matches a media route to a Bluetooth device without guessing through ambiguous names.
 * A route address wins only when it is a syntactically valid Bluetooth address known to the caller.
 */
internal object BluetoothRouteIdentityMatcher {
    fun match(
        route: BluetoothAudioRouteSnapshot,
        knownDevices: List<BluetoothKnownDeviceIdentity>,
        isValidBluetoothAddress: (String) -> Boolean,
    ): BluetoothRouteIdentityMatch {
        val routeAddress = route.routeAddress?.trim().orEmpty()
        if (routeAddress.isNotEmpty() && isValidBluetoothAddress(routeAddress)) {
            knownDevices.firstOrNull { it.address.equals(routeAddress, ignoreCase = true) }?.let { device ->
                return BluetoothRouteIdentityMatch.Matched(
                    device = device,
                    confidence = BluetoothRouteIdentityMatch.Confidence.ROUTE_ADDRESS,
                )
            }
        }

        val routeName = route.productName?.trim()?.takeIf { it.isNotEmpty() }
            ?: return BluetoothRouteIdentityMatch.Unresolved("route_has_no_resolvable_address_or_name")
        val byName = knownDevices.filter { it.name?.trim()?.equals(routeName, ignoreCase = true) == true }
        return when (byName.size) {
            1 -> BluetoothRouteIdentityMatch.Matched(
                device = byName.single(),
                confidence = BluetoothRouteIdentityMatch.Confidence.UNIQUE_BONDED_NAME,
            )
            0 -> BluetoothRouteIdentityMatch.Unresolved("no_bonded_device_matches_route")
            else -> BluetoothRouteIdentityMatch.Unresolved("ambiguous_bonded_device_name")
        }
    }
}
