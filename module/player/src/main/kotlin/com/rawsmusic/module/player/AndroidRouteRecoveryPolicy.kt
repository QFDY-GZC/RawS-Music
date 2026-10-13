package com.rawsmusic.module.player

/** Keep explicit route intent only while it still describes the active, available output. */
internal fun recoveryOutputDeviceId(
    forcedId: Int,
    preferredId: Int,
    routedId: Int,
    availableIds: Set<Int>,
): Int {
    if (forcedId > 0) return forcedId.takeIf { it in availableIds } ?: 0
    if (preferredId <= 0 || preferredId !in availableIds) return 0
    if (routedId > 0 && routedId != preferredId) return 0
    return preferredId
}
