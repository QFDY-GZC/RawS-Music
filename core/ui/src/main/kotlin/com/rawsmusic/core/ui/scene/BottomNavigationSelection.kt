package com.rawsmusic.core.ui.scene

/**
 * Resolves the visual bottom-tab selection independently from the committed content scene.
 * During an interactive/programmatic back transition, [backPreviewScene] is the page already
 * being revealed behind the outgoing content.
 *
 * SEARCH is a transient destination when it is not present in the user's customized bottom bar.
 * In that case the indicator stays on the nearest owning entry in [backStack] instead of falling
 * back to HOME. This prevents an external visual synchronization from looking like, or being
 * mistaken for, a navigation back to the main page.
 */
internal fun resolveBottomNavigationSelectedIndex(
    tabScenes: List<NavScene>,
    currentScene: NavScene,
    backPreviewScene: NavScene?,
    backStack: List<NavScene> = emptyList(),
): Int {
    val visualScene = backPreviewScene ?: currentScene
    val rootScene = visualScene.bottomNavigationRoot()
    val exactIndex = tabScenes.indexOf(rootScene)
    if (exactIndex >= 0) return exactIndex

    if (visualScene == NavScene.SEARCH) {
        val searchIndex = backStack.lastIndexOf(NavScene.SEARCH)
        val ownerCandidates = when {
            searchIndex > 0 -> backStack.subList(0, searchIndex).asReversed()
            else -> backStack.asReversed().filterNot { it == NavScene.SEARCH }
        }
        ownerCandidates.forEach { candidate ->
            val ownerIndex = tabScenes.indexOf(candidate.bottomNavigationRoot())
            if (ownerIndex >= 0) return ownerIndex
        }
    }

    return tabScenes.indexOf(NavScene.HOME).coerceAtLeast(0)
}
