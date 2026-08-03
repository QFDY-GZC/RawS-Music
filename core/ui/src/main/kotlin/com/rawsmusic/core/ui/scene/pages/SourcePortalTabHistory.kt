package com.rawsmusic.core.ui.scene.pages

/** Pure tab-history reducer used by both click and drag-stop navigation events. */
internal object SourcePortalTabHistory {
    private const val MAX_HISTORY = 32

    fun decode(names: List<String>): List<SourcePortalTab> = names
        .mapNotNull { name -> SourcePortalTab.entries.firstOrNull { it.name == name } }
        .ifEmpty { listOf(SourcePortalTab.Sources) }

    fun current(names: List<String>): SourcePortalTab =
        decode(names).lastOrNull() ?: SourcePortalTab.Sources

    /**
     * Bottom navigation behaves like browser history: every real destination change is appended.
     * A duplicate callback for the already-current tab is idempotent.
     */
    fun append(names: List<String>, tab: SourcePortalTab): List<String> {
        val latest = decode(names)
        if (latest.lastOrNull() == tab) return latest.map { it.name }
        val appended = latest + tab
        val bounded = if (appended.size <= MAX_HISTORY) appended
        else listOf(SourcePortalTab.Sources) + appended.takeLast(MAX_HISTORY - 1)
        return bounded.map { it.name }
    }

    fun pop(names: List<String>): List<String> {
        val latest = decode(names)
        return latest.dropLast(1)
            .ifEmpty { listOf(SourcePortalTab.Sources) }
            .map { it.name }
    }

    fun previous(names: List<String>): SourcePortalTab? =
        decode(names).let { history -> history.getOrNull(history.lastIndex - 1) }
}
