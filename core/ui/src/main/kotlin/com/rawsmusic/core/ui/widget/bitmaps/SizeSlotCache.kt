package com.rawsmusic.core.ui.widget.bitmaps

import android.graphics.Bitmap
import android.os.Build

/**
 * Provider-owned album-art wrapper cache.
 *
 * Reference baseline implementation keeps one provider source record per artwork identity and that record exposes
 * exactly two authoritative wrapper slots: lowRes and hiRes. Exact request keys are still retained
 * here so an attached holder can release the precise wrapper it acquired, but a source may expose at
 * most one current low wrapper and one current high wrapper to new consumers.
 *
 * Replacing an authoritative source slot does not recycle an attached old wrapper. The old entry is
 * retired from source lookup and remains byte-accounted until its final holder releases it. This is
 * the provider equivalent of Reference moving a wrapper out of the source record while an ArtworkImageNode
 * still owns a ref. The default byte budget is the derived baseline implementation weighted-capacity heap
 * fraction translated to Raw's real allocation-byte accounting.
 */
class SizeSlotCache(
    private val maxBytes: Int = defaultMaxBytes()
) {
    companion object {
        private val SIZE_SLOTS = intArrayOf(16, 32, 48, 64, 96, 128, 192, 256, 384, 512, 768, 1024, 1536, 2048)

        fun computeBucket(width: Int, height: Int): Int {
            val size = if (width >= 2 * height) width / 2 else maxOf(width, height)
            if (size <= 0) return SIZE_SLOTS[0]
            // 1024 and the build-1026 Increase Resolution / 1536 lane must never collapse onto
            // the same exact provider key. The legacy power-of-two bucketing intentionally groups
            // smaller list sizes, so isolate only the explicit full lane here.
            if (size >= AlbumArtTiers.FULL_RES_SIDE && size < 2048) {
                return AlbumArtTiers.FULL_RES_SIDE
            }
            val powerOf2 = Integer.highestOneBit(size)
            var best = SIZE_SLOTS[0]
            var bestDistance = kotlin.math.abs(best - powerOf2)
            for (index in 1 until SIZE_SLOTS.size) {
                val candidate = SIZE_SLOTS[index]
                val distance = kotlin.math.abs(candidate - powerOf2)
                if (distance < bestDistance || (distance == bestDistance && candidate > best)) {
                    best = candidate
                    bestDistance = distance
                }
            }
            return best
        }

        fun defaultMaxBytes(): Int {
            return ProviderCacheBudgetPolicy.byteBudget(
                maxMemoryBytes = Runtime.getRuntime().maxMemory(),
                sdkInt = Build.VERSION.SDK_INT,
            )
        }
    }

    private enum class SourceLane { LOW, HIGH }

    private data class Entry(
        val key: String,
        val bitmap: Bitmap,
        val bucket: Int,
        val byteCount: Int,
        val sourceKey: String,
        val lane: SourceLane,
        var record: ProviderSourceRecordSlots<Entry>? = null,
        var refs: Int = 0,
        var authoritative: Boolean = true,
    ) {
        fun valid(): Boolean = !bitmap.isRecycled
        fun evictable(): Boolean = refs <= 0
    }

    private val lock = Any()
    // baseline implementation keeps separate low/high insertion-order wrapper chains. The exact-key map stays
    // insertion-ordered as a compact backing store; trim filters that order per lane. A cache hit
    // increments only the wrapper refCount and never promotes the entry to MRU.
    private val map = object : LinkedHashMap<String, Entry>(32, 0.75f, false) {}
    /** baseline implementation type+long identity aliases -> one P-equivalent source record. */
    private val sourceRecords = HashMap<String, ProviderSourceRecordSlots<Entry>>()
    /** baseline implementation decoder source-string map -> one P-equivalent source record. */
    private val sourceStringRecords = HashMap<String, ProviderSourceRecordSlots<Entry>>()
    private var currentBytes: Int = 0

    /**
     * Low-overhead transition snapshot used only at scene-transition boundaries.
     * Keep it aggregate-only so exported diagnostics can separate provider-memory pressure from
     * View/RenderThread pressure without adding per-frame logging.
     */
    fun transitionDiagnosticsSummary(): String = synchronized(lock) {
        var authoritative = 0
        var retired = 0
        var referenced = 0
        var refCount = 0
        var hardware = 0
        var software = 0
        var low = 0
        var high = 0
        map.values.forEach { entry ->
            if (entry.authoritative) authoritative += 1 else retired += 1
            if (entry.refs > 0) referenced += 1
            refCount += entry.refs.coerceAtLeast(0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && entry.bitmap.config == Bitmap.Config.HARDWARE) {
                hardware += 1
            } else {
                software += 1
            }
            when (entry.lane) {
                SourceLane.LOW -> low += 1
                SourceLane.HIGH -> high += 1
            }
        }
        "entries=${map.size} auth=$authoritative retired=$retired referenced=$referenced refs=$refCount " +
            "hardware=$hardware software=$software low=$low high=$high bytes=$currentBytes/$maxBytes " +
            "records=${sourceRecords.size} sourceStrings=${sourceStringRecords.size}"
    }

    fun get(key: String): Bitmap? = synchronized(lock) {
        val entry = map[key] ?: return null
        if (!entry.valid()) {
            removeLocked(key)
            return null
        }
        // A replaced attached wrapper is retained only for the holder that already owns it. It is
        // no longer discoverable by a new exact-key lookup once its source record has moved on.
        if (!entry.authoritative) return null
        entry.bitmap
    }

    fun getAnyForSource(sourceKey: String, minimumSide: Int = 1, allowHighFallback: Boolean = true): Bitmap? = synchronized(lock) {
        selectSourceEntryLocked(sourceKey, minimumSide, allowHighFallback)?.bitmap
    }

    /**
     * baseline implementation decoder callback B.А(request, sourceString): consult the provider source-string
     * map before opening/decoding that source. A hit attaches the current identity alias to the
     * existing P record and returns its usable low/high wrapper without creating a second record.
     */
    fun getForSourceStringAndBind(
        sourceKey: String,
        sourceString: String,
        minimumSide: Int = 1,
    ): Bitmap? = synchronized(lock) {
        if (sourceKey.isBlank() || sourceString.isBlank()) return@synchronized null
        val record = sourceStringRecords[sourceString] ?: return@synchronized null
        val entry = selectRecordEntryLocked(record, minimumSide) ?: return@synchronized null
        bindIdentityLocked(sourceKey, record)
        entry.bitmap
    }

    /** Acquire an exact current provider wrapper. */
    fun acquire(
        key: String,
        surface: ArtworkSurface = ArtworkSurface.Widget
    ): ArtworkHandle? = synchronized(lock) {
        val entry = map[key] ?: return@synchronized null
        if (!entry.valid()) {
            removeLocked(key)
            return@synchronized null
        }
        if (!entry.authoritative) return@synchronized null
        acquireEntryLocked(entry, surface)
    }

    fun acquireAnyForSource(
        sourceKey: String,
        surface: ArtworkSurface = ArtworkSurface.Widget,
        minimumSide: Int = 1
    ): ArtworkHandle? = synchronized(lock) {
        val entry = selectSourceEntryLocked(sourceKey, minimumSide) ?: return@synchronized null
        acquireEntryLocked(entry, surface)
    }

    /**
     * One-lock holder lookup: exact current target first, otherwise the current low/high wrapper from
     * the same provider source record. This mirrors ArtworkProvider.O() consulting P.lowRes/P.hiRes before
     * it creates an asynchronous load request.
     */
    fun acquireBestForSource(
        exactKey: String,
        sourceKey: String,
        surface: ArtworkSurface = ArtworkSurface.Widget,
        minimumFallbackSide: Int = 1,
        allowHighFallback: Boolean = true,
    ): ArtworkHandle? = synchronized(lock) {
        val exact = map[exactKey]
        if (exact != null) {
            if (!exact.valid()) {
                removeLocked(exactKey)
            } else if (exact.authoritative) {
                return@synchronized acquireEntryLocked(exact, surface)
            }
        }

        val fallback = selectSourceEntryLocked(sourceKey, minimumFallbackSide, allowHighFallback)
            ?: return@synchronized null
        acquireEntryLocked(fallback, surface)
    }

    fun put(
        key: String,
        bitmap: Bitmap,
        bucket: Int,
        sourceKey: String,
        sourceString: String = "",
    ) {
        if (bitmap.isRecycled) return
        val bc = safeByteCount(bitmap)
        if (bc <= 0 || bc > maxBytes) return
        synchronized(lock) {
            val record = recordForPublicationLocked(sourceKey, sourceString)
            val sourceOwner = keepExistingSourceOwnerLocked(
                key = key,
                bitmap = bitmap,
                bucket = bucket,
                record = record,
            )
            if (sourceOwner != null) return@synchronized

            val existing = map[key]
            // A retired wrapper can still be held by an artwork holder. Do not reuse its exact-key slot
            // underneath that live lease; the current authoritative source wrapper remains the
            // provider answer until the retired holder returns its ref.
            if (existing != null && !existing.authoritative && existing.refs > 0) {
                return@synchronized
            }
            removeLocked(key)
            val entry = insertLocked(key, bitmap, bucket, bc, sourceKey)
            publishSourceEntryLocked(entry, record, sourceString)
            trimToSize(maxBytes)
        }
    }

    /** Publish and retain one exact current provider wrapper without a put/acquire gap. */
    fun putAndAcquire(
        key: String,
        bitmap: Bitmap,
        bucket: Int,
        sourceKey: String,
        surface: ArtworkSurface,
        sourceString: String = "",
    ): ArtworkHandle? {
        if (bitmap.isRecycled) return null
        val bc = safeByteCount(bitmap)
        if (bc <= 0 || bc > maxBytes) return null
        return synchronized(lock) {
            val record = recordForPublicationLocked(sourceKey, sourceString)
            val sourceOwner = keepExistingSourceOwnerLocked(
                key = key,
                bitmap = bitmap,
                bucket = bucket,
                record = record,
            )
            if (sourceOwner != null) {
                return@synchronized acquireEntryLocked(sourceOwner, surface)
            }

            val existing = map[key]
            if (existing != null && !existing.authoritative && existing.refs > 0) {
                val fallback = selectSourceEntryLocked(sourceKey, 1)
                    ?: return@synchronized null
                return@synchronized acquireEntryLocked(fallback, surface)
            }
            removeLocked(key)
            val entry = insertLocked(key, bitmap, bucket, bc, sourceKey)
            val authoritative = publishSourceEntryLocked(entry, record, sourceString)
            val handle = acquireEntryLocked(authoritative, surface)
            trimToSize(maxBytes)
            handle
        }
    }

    fun remove(key: String) = synchronized(lock) { removeLocked(key) }

    fun removeForSource(sourceKey: String): Int = synchronized(lock) {
        if (sourceKey.isBlank()) return@synchronized 0
        val record = sourceRecords[sourceKey]
        val keys = if (record != null) {
            map.values.asSequence()
                .filter { it.record === record }
                .map { it.key }
                .toList()
        } else {
            // Compatibility fallback for retired/pre-alias entries that are no longer indexed.
            map.values.asSequence()
                .filter { it.sourceKey == sourceKey || it.key.startsWith("${sourceKey}_") }
                .map { it.key }
                .toList()
        }
        keys.forEach(::removeLocked)
        if (record != null && record.isEmpty()) removeRecordIndexesLocked(record)
        keys.size
    }

    fun clear() = synchronized(lock) {
        map.clear()
        sourceRecords.clear()
        sourceStringRecords.clear()
        currentBytes = 0
    }

    val size: Int get() = synchronized(lock) { map.size }
    val bytes: Int get() = synchronized(lock) { currentBytes }
    val maxSizeBytes: Int get() = maxBytes

    /**
     * Exact baseline implementation source lanes are occupied until provider eviction clears them. Before an
     * incoming Raw decode is inserted under an exact cache key, check the authoritative source lane
     * first so an equal/smaller duplicate cannot displace that owner merely by sharing the same key.
     * Raw's variable-size compatibility may still upgrade to a larger wrapper when the request key
     * itself is different.
     */
    private fun keepExistingSourceOwnerLocked(
        key: String,
        bitmap: Bitmap,
        bucket: Int,
        record: ProviderSourceRecordSlots<Entry>,
    ): Entry? {
        val highRes = laneForBucket(bucket) == SourceLane.HIGH
        val current = record.get(highRes) ?: return null
        if (!current.valid()) {
            removeLocked(current.key)
            return null
        }
        if (!current.authoritative) return null

        // Same request identity follows Reference's occupied-lane rule even if a duplicate decode
        // happened to produce a different physical Bitmap instance.
        if (current.key == key) return current

        return current.takeIf { coverageSide(it) >= coverageSide(bitmap, it.sourceKey) }
    }

    private fun selectSourceEntryLocked(sourceKey: String, minimumSide: Int, allowHighFallback: Boolean = true): Entry? {
        if (sourceKey.isBlank()) return null
        val record = sourceRecords[sourceKey] ?: return null
        return selectRecordEntryLocked(record, minimumSide, allowHighFallback)
    }

    private fun selectRecordEntryLocked(
        record: ProviderSourceRecordSlots<Entry>,
        minimumSide: Int,
        allowHighFallback: Boolean = true,
    ): Entry? {
        val required = minimumSide.coerceAtLeast(1)

        fun usable(entry: Entry?): Entry? {
            if (entry == null) return null
            if (!entry.authoritative || !entry.valid()) {
                if (!entry.valid()) removeLocked(entry.key)
                return null
            }
            return entry.takeIf { coverageSide(it) >= required }
        }

        // Low requests consume the low wrapper first, matching P.lowRes. A larger request naturally
        // falls through to P.hiRes when the low wrapper cannot cover the requested minimum side.
        return usable(record.low) ?: if (allowHighFallback) usable(record.high) else null
    }

    private fun acquireEntryLocked(
        entry: Entry,
        surface: ArtworkSurface
    ): ArtworkHandle? {
        if (!entry.valid() || !entry.authoritative) return null
        entry.refs++
        return ArtworkHandle(
            sourceKey = entry.sourceKey,
            tier = tierForBucket(entry.bucket),
            surface = surface,
            bitmap = entry.bitmap
        ) {
            releaseEntry(entry)
        }
    }

    private fun releaseEntry(entry: Entry) {
        synchronized(lock) {
            if (entry.refs > 0) entry.refs--
            // A source-slot replacement can leave the previous wrapper attached to an existing
            // artwork holder. Once that final ref is returned, it has no provider-record owner and must
            // leave the cache regardless of whether the global byte budget is currently exceeded.
            if (entry.refs <= 0 && !entry.authoritative) {
                removeLocked(entry.key)
            }
            trimToSize(maxBytes)
        }
    }

    private fun tierForBucket(bucket: Int): ArtworkTier {
        return when {
            bucket >= AlbumArtTiers.FULL_RES_SIDE -> ArtworkTier.Full
            bucket >= AlbumArtTiers.HI_RES_SIDE -> ArtworkTier.High
            else -> ArtworkTier.Low
        }
    }

    private fun laneForBucket(bucket: Int): SourceLane =
        if (bucket >= AlbumArtTiers.HI_RES_SIDE) SourceLane.HIGH else SourceLane.LOW

    /** Resolve one P-equivalent record by decoder source string first, then by request identity. */
    private fun recordForPublicationLocked(
        sourceKey: String,
        sourceString: String,
    ): ProviderSourceRecordSlots<Entry> {
        val bySourceString = sourceString.takeIf { it.isNotBlank() }?.let(sourceStringRecords::get)
        val byIdentity = sourceRecords[sourceKey]
        val record = bySourceString ?: byIdentity ?: ProviderSourceRecordSlots()
        bindIdentityLocked(sourceKey, record)
        if (sourceString.isNotBlank()) bindSourceStringLocked(sourceString, record)
        return record
    }

    /** Reference А.P(P,type,id): one identity may move from an old P record to the new record. */
    private fun bindIdentityLocked(
        sourceKey: String,
        record: ProviderSourceRecordSlots<Entry>,
    ) {
        if (sourceKey.isBlank()) return
        val previous = sourceRecords.put(sourceKey, record)
        if (previous !== record) previous?.unregisterIdentity(sourceKey)
        record.registerIdentity(sourceKey)
    }

    /**
     * baseline implementation provider.c stores one source-string -> P entry; P itself has no source-string list.
     * Once a record already has an authoritative source string, a later alternate Raw fallback source
     * is not added as a second alias merely because it resolves to the same request identity.
     */
    private fun bindSourceStringLocked(
        sourceString: String,
        record: ProviderSourceRecordSlots<Entry>,
    ) {
        if (sourceString.isBlank()) return
        val current = record.sourceStringOrNull()
        if (current != null) return

        val existing = sourceStringRecords[sourceString]
        if (existing != null && existing !== record) return
        if (!record.registerSourceStringIfAbsent(sourceString)) return
        sourceStringRecords[sourceString] = record
    }

    /** P.lowRes/P.hiRes both empty: remove every identity alias and the one source-string index. */
    private fun removeRecordIndexesLocked(record: ProviderSourceRecordSlots<Entry>) {
        record.identityAliasesSnapshot().forEach { alias ->
            if (sourceRecords[alias] === record) sourceRecords.remove(alias)
        }
        record.sourceStringOrNull()?.let { sourceString ->
            if (sourceStringRecords[sourceString] === record) sourceStringRecords.remove(sourceString)
        }
        record.clearIndexes()
    }

    /**
     * Publish one low/high source wrapper. baseline implementation P.Х never overwrites an occupied lane. Raw
     * currently has variable visual sizes inside its low lane, so preserve that rule for duplicate
     * and smaller results while allowing only a monotonic larger-coverage upgrade. This prevents a
     * late 384px result from replacing an already authoritative 512/large-grid wrapper and forcing
     * a later source re-probe.
     */
    private fun publishSourceEntryLocked(
        entry: Entry,
        initialRecord: ProviderSourceRecordSlots<Entry>,
        sourceString: String,
    ): Entry {
        val highRes = entry.lane == SourceLane.HIGH
        var record = initialRecord
        entry.record = record
        var current = record.get(highRes)

        if (current != null && !current.valid()) {
            removeLocked(current.key)
            record = recordForPublicationLocked(entry.sourceKey, sourceString)
            entry.record = record
            current = record.get(highRes)
        }
        if (current === entry) return entry

        val incomingCoverage = coverageSide(entry)
        val decision = resolveProviderSourceSlotPublication(
            existingCoverageSide = current?.let(::coverageSide),
            incomingCoverageSide = incomingCoverage,
        )

        when (decision) {
            ProviderSourceSlotPublication.INSTALL -> {
                check(record.installIfEmpty(highRes, entry))
                entry.authoritative = true
                return entry
            }

            ProviderSourceSlotPublication.KEEP_EXISTING -> {
                val authoritative = current ?: run {
                    check(record.installIfEmpty(highRes, entry))
                    entry.authoritative = true
                    return entry
                }
                entry.authoritative = false
                if (entry.refs <= 0) removeLocked(entry.key)
                return authoritative
            }

            ProviderSourceSlotPublication.UPGRADE_LARGER -> {
                val previous = current ?: run {
                    check(record.installIfEmpty(highRes, entry))
                    entry.authoritative = true
                    return entry
                }
                check(record.replaceIfSame(highRes, previous, entry))
                entry.authoritative = true
                previous.authoritative = false
                if (previous.refs <= 0) removeLocked(previous.key)
                return entry
            }
        }
    }

    private fun trimToSize(targetBytes: Int) {
        // baseline implementation owns two insertion-order wrapper chains and, while over its weighted capacity,
        // attempts an old unreferenced high wrapper before an old unreferenced low wrapper. Preserve
        // that lane-local order while keeping Raw's existing byte budget as the capacity metric.
        while (currentBytes > targetBytes) {
            var removedAny = false
            if (evictOldestInLaneLocked(SourceLane.HIGH)) removedAny = true
            if (currentBytes <= targetBytes) break
            if (evictOldestInLaneLocked(SourceLane.LOW)) removedAny = true
            if (!removedAny) break
        }
        if (currentBytes < 0) currentBytes = 0
    }

    private fun evictOldestInLaneLocked(lane: SourceLane): Boolean {
        val iter = map.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next().value
            if (entry.lane != lane || !entry.evictable()) continue
            currentBytes -= entry.byteCount
            detachFromSourceRecordLocked(entry)
            entry.authoritative = false
            iter.remove()
            return true
        }
        return false
    }

    private fun removeLocked(key: String) {
        val removed = map.remove(key) ?: return
        detachFromSourceRecordLocked(removed)
        removed.authoritative = false
        currentBytes -= removed.byteCount
        if (currentBytes < 0) currentBytes = 0
    }

    private fun insertLocked(
        key: String,
        bitmap: Bitmap,
        bucket: Int,
        byteCount: Int,
        sourceKey: String
    ): Entry {
        val entry = Entry(
            key = key,
            bitmap = bitmap,
            bucket = bucket,
            byteCount = byteCount,
            sourceKey = sourceKey,
            lane = laneForBucket(bucket),
        )
        map[key] = entry
        currentBytes += byteCount
        return entry
    }

    private fun detachFromSourceRecordLocked(entry: Entry) {
        val record = entry.record ?: return
        val highRes = entry.lane == SourceLane.HIGH
        record.removeIfSame(highRes, entry)
        entry.record = null
        if (record.isEmpty()) removeRecordIndexesLocked(record)
    }

    private fun coverageSide(entry: Entry): Int = coverageSide(entry.bitmap, entry.sourceKey)

    private fun coverageSide(bitmap: Bitmap, sourceKey: String): Int =
        if (isKeepAspectArtworkCacheSourceKey(sourceKey)) {
            maxOf(bitmap.width, bitmap.height).coerceAtLeast(0)
        } else {
            minOf(bitmap.width, bitmap.height).coerceAtLeast(0)
        }

    private fun safeByteCount(bitmap: Bitmap): Int {
        return try { bitmap.allocationByteCount } catch (_: Throwable) { bitmap.byteCount }
    }
}
