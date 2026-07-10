package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger

object PlayerRuntimeRegistry {
    private const val TAG = "PlayerRuntimeRegistry"

    @Volatile
    private var controllerRef: PlayerController? = null

    @Volatile
    private var controllerOwner: String = ""

    fun attachController(controller: PlayerController, owner: String) {
        val previous = controllerRef
        if (previous === controller && controllerOwner == owner) return
        controllerRef = controller
        controllerOwner = owner
        AppLogger.i(
            TAG,
            "attachController: owner=$owner controller=${System.identityHashCode(controller)} " +
                "previous=${previous?.let { System.identityHashCode(it) } ?: -1}"
        )
    }

    fun currentControllerOrNull(): PlayerController? = controllerRef

    fun detachController(controller: PlayerController, owner: String) {
        if (controllerRef !== controller) return
        controllerRef = null
        controllerOwner = ""
        AppLogger.i(
            TAG,
            "detachController: owner=$owner controller=${System.identityHashCode(controller)}"
        )
    }
}
