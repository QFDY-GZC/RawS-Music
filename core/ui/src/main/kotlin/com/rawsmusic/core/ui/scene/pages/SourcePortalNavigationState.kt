package com.rawsmusic.core.ui.scene.pages

import androidx.compose.runtime.saveable.Saver

internal data class SourcePortalNavigationState(
    val tabStackNames: List<String> = listOf(SourcePortalTab.Sources.name),
    val tabTransition: SourcePortalTabTransition? = null,
    val route: SourcePortalRoute = SourcePortalRoute.Browse,
    val routeTransition: SourcePortalRouteTransition? = null,
) {
    val tabStack: List<SourcePortalTab>
        get() = SourcePortalTabHistory.decode(tabStackNames)

    val selectedTab: SourcePortalTab
        get() = tabStack.lastOrNull() ?: SourcePortalTab.Sources
}

internal val SourcePortalNavigationStateSaver = Saver<SourcePortalNavigationState, List<String>>(
    save = { state ->
        buildList {
            add(state.route.name)
            addAll(state.tabStackNames)
        }
    },
    restore = { saved ->
        val route = saved.firstOrNull()
            ?.let { runCatching { SourcePortalRoute.valueOf(it) }.getOrNull() }
            ?: SourcePortalRoute.Browse
        val stack = saved.drop(1).ifEmpty { listOf(SourcePortalTab.Sources.name) }
        SourcePortalNavigationState(
            tabStackNames = SourcePortalTabHistory.decode(stack).map { it.name },
            route = route,
        )
    },
)
