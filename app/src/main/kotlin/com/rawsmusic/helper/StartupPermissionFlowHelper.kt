package com.rawsmusic.helper

class StartupPermissionFlowHelper(
    private val audioPermissionHelper: AudioPermissionHelper,
    private val launchPermissions: (Array<String>) -> Unit,
    private val startScan: () -> Unit
) {
    fun request() {
        val permissions = audioPermissionHelper.requiredStartupPermissions()
        if (audioPermissionHelper.areGranted(permissions)) {
            startScan()
        } else {
            launchPermissions(permissions)
        }
    }
}
