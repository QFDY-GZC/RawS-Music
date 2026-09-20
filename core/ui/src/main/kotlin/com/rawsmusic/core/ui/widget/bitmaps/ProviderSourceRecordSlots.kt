package com.rawsmusic.core.ui.widget.bitmaps

/**
 * Reference baseline implementation P-equivalent slot pair.
 *
 * The provider source record owns one authoritative lowRes wrapper and one authoritative hiRes
 * wrapper. baseline implementation P.Х(wrapper, hiRes) only installs into an empty lane; an occupied lane is
 * not overwritten by a later duplicate result. Raw keeps variable low-tier sizes, so SizeSlotCache
 * may perform one explicit monotonic upgrade after proving the incoming wrapper covers more pixels.
 */
internal class ProviderSourceRecordSlots<T> {
    private val identityAliases = LinkedHashSet<String>()
    private var sourceString: String? = null

    var low: T? = null
        private set
    var high: T? = null
        private set

    fun get(highRes: Boolean): T? = if (highRes) high else low

    /** baseline implementation P keeps every type+long identity that currently resolves to this source record. */
    fun registerIdentity(identity: String) {
        if (identity.isNotBlank()) identityAliases += identity
    }

    fun unregisterIdentity(identity: String) {
        identityAliases -= identity
    }

    /**
     * baseline implementation P itself has no source-string alias collection. The provider owns one
     * source-string -> P map entry derived from the record's accepted wrapper source.
     */
    fun registerSourceStringIfAbsent(value: String): Boolean {
        if (value.isBlank()) return false
        val current = sourceString
        if (current != null) return current == value
        sourceString = value
        return true
    }

    fun sourceStringOrNull(): String? = sourceString

    fun identityAliasesSnapshot(): List<String> = identityAliases.toList()

    fun clearIndexes() {
        identityAliases.clear()
        sourceString = null
    }

    /** Exact baseline implementation P.Х behaviour: publish only when the requested lane is empty. */
    fun installIfEmpty(highRes: Boolean, value: T): Boolean {
        if (get(highRes) != null) return false
        if (highRes) high = value else low = value
        return true
    }

    /**
     * Raw-only compatibility bridge for variable-size wrappers inside one lane. The replacement is
     * legal only if the caller is still looking at the same authoritative object under one lock.
     */
    fun replaceIfSame(highRes: Boolean, expected: T, replacement: T): Boolean {
        if (get(highRes) !== expected) return false
        if (highRes) high = replacement else low = replacement
        return true
    }

    fun removeIfSame(highRes: Boolean, value: T) {
        if (highRes) {
            if (high === value) high = null
        } else if (low === value) {
            low = null
        }
    }

    /** Low-res is the normal artwork request lane; hi-res is the fallback/upgrade lane. */
    inline fun firstUsable(predicate: (T) -> Boolean): T? {
        low?.let { if (predicate(it)) return it }
        high?.let { if (predicate(it)) return it }
        return null
    }

    fun isEmpty(): Boolean = low == null && high == null
}

internal enum class ProviderSourceSlotPublication {
    INSTALL,
    KEEP_EXISTING,
    UPGRADE_LARGER,
}

/**
 * baseline implementation never overwrites an occupied P.lowRes/P.hiRes lane. Raw's low lane can currently carry
 * more than one visual size (for example 384/512/large-grid), so the only permitted deviation is a
 * monotonic coverage upgrade. Equal or smaller duplicate results keep the existing provider owner.
 */
internal fun resolveProviderSourceSlotPublication(
    existingCoverageSide: Int?,
    incomingCoverageSide: Int,
): ProviderSourceSlotPublication {
    if (existingCoverageSide == null || existingCoverageSide <= 0) {
        return ProviderSourceSlotPublication.INSTALL
    }
    return if (incomingCoverageSide > existingCoverageSide) {
        ProviderSourceSlotPublication.UPGRADE_LARGER
    } else {
        ProviderSourceSlotPublication.KEEP_EXISTING
    }
}
