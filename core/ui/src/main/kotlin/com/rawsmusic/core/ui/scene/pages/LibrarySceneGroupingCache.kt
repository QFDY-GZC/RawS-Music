package com.rawsmusic.core.ui.scene.pages

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.perf.TransitionPerfStage
import com.rawsmusic.core.ui.perf.TransitionPerfTrace
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.widget.index.RawAlphabetIndexCache
import com.rawsmusic.core.ui.widget.index.RawAlphabetIndexData
import com.rawsmusic.core.ui.widget.index.RawIndexMode
import com.rawsmusic.core.ui.widget.index.buildAdaptiveAlphabetIndexData
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Provider metadata cache for the library scenes.
 *
 * Reference enters a list with provider metadata already owned outside the View holder tree. Raw
 * publishes the song snapshot in O(1), while each category provider is built on the serialized
 * low-priority lane when that destination is actually requested. This keeps cold start from
 * eagerly constructing every category twice (the repository already owns its own indexes).
 */
object LibrarySceneGroupingWarmup {
    private val lock = Any()
    private val metaExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LibraryProviderMetaWorker").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }
    private val metaDispatcher = metaExecutor.asCoroutineDispatcher()

    private suspend fun <T> withMetaTrace(block: suspend () -> T): T = withContext(metaDispatcher) {
        val startedNs = if (TransitionPerfTrace.isActive()) System.nanoTime() else 0L
        try {
            block()
        } finally {
            if (startedNs != 0L) {
                TransitionPerfTrace.recordDuration(
                    TransitionPerfStage.LIBRARY_PROVIDER_META,
                    System.nanoTime() - startedNs,
                )
            }
        }
    }

    @Volatile
    private var source: List<AudioFile>? = null
    private var albumGroups: List<AlbumGroupUi>? = null
    private var folderGroups: List<FolderGroupUi>? = null
    private var artistGroups: List<ArtistGroupUi>? = null
    private var genreGroups: List<CategoryGroupUi>? = null
    private var yearGroups: List<CategoryGroupUi>? = null
    private var composerGroups: List<CategoryGroupUi>? = null
    private var homeStatistics: HomeCardStatisticsSnapshot? = null
    private var songIndexData: RawAlphabetIndexData? = null
    private var albumIndexData: RawAlphabetIndexData? = null
    private var folderIndexData: RawAlphabetIndexData? = null
    private var artistIndexData: RawAlphabetIndexData? = null
    private var genreIndexData: RawAlphabetIndexData? = null
    private var composerIndexData: RawAlphabetIndexData? = null
    private var yearIndexData: RawAlphabetIndexData? = null
    private var indexLocaleTag: String = ""

    /** Publish the provider snapshot only. Root metadata can then be warmed off the UI thread. */
    fun onLibrarySnapshot(songs: List<AudioFile>) {
        synchronized(lock) { ensureSourceLocked(songs) }
    }

    fun warm(songs: List<AudioFile>) = onLibrarySnapshot(songs)

    /**
     * Whether the root provider has authoritative metadata for this exact library snapshot.
     *
     * Provider publication and metadata readiness are deliberately separate. A publication-only
     * destination composition is allowed to exist while its low-priority grouping job is running,
     * but PivotTransition must not promote that temporary empty population to NEXT. This mirrors
     * retained-view implementation starting its layout transition only after the destination adapter/layout is bound.
     */
    internal fun isRootProviderReady(scene: NavScene, songs: List<AudioFile>): Boolean = synchronized(lock) {
        ensureSourceLocked(songs)
        when (scene) {
            NavScene.ALBUMS -> albumGroups != null
            NavScene.FOLDERS -> folderGroups != null
            NavScene.ARTISTS -> artistGroups != null
            NavScene.GENRE -> genreGroups != null
            NavScene.YEAR -> yearGroups != null
            NavScene.COMPOSER -> composerGroups != null
            // SONGS has no grouping stage. HOME owns heterogeneous cards rather than one root
            // grouping list; the remaining persistent scenes either own their own data source or
            // are not gated by this root-song metadata cache.
            else -> true
        }
    }

    /**
     * Build the root-library provider metadata before a PivotTransition needs it.
     *
     * retained-view implementation enters PivotTransition with the destination provider/LayoutRes already available; it
     * does not let album/folder/artist grouping replace the complete holder population halfway
     * through the 250 ms motion. Raw used to start these jobs only when the destination page was
     * composed in the hidden preflight slot, so a cold navigation could publish an empty provider
     * first and then rebuild it during the transition. Keep the work on the existing low-priority
     * metadata lane, but move it to the stable library-snapshot lifetime instead of the click path.
     */
    suspend fun prewarmRootProviders(songs: List<AudioFile>) {
        onLibrarySnapshot(songs)
        if (songs.isEmpty()) return

        loadHomeStatistics(songs)
        // Songs itself has no grouping step, but its adaptive index is provider metadata too.
        loadSongsIndex(songs)
        loadAlbums(songs)
        loadFolders(songs)
        loadArtists(songs)
        loadGenres(songs)
        loadYears(songs)
        loadComposers(songs)
    }

    internal fun homeStatistics(songs: List<AudioFile>): HomeCardStatisticsSnapshot? = synchronized(lock) {
        ensureSourceLocked(songs)
        homeStatistics
    }

    internal suspend fun loadHomeStatistics(songs: List<AudioFile>): HomeCardStatisticsSnapshot = withMetaTrace {
        synchronized(lock) {
            ensureSourceLocked(songs)
            homeStatistics
        }?.let { return@withMetaTrace it }

        val snapshot = buildHomeCardStatisticsSnapshot(songs)
        synchronized(lock) {
            if (source === songs) homeStatistics = snapshot
        }
        snapshot
    }

    internal fun albums(songs: List<AudioFile>): List<AlbumGroupUi> = synchronized(lock) {
        ensureSourceLocked(songs)
        albumGroups.orEmpty()
    }

    internal suspend fun loadAlbums(songs: List<AudioFile>): List<AlbumGroupUi> = withMetaTrace {
        synchronized(lock) {
            ensureSourceLocked(songs)
            albumGroups
        }?.let { return@withMetaTrace it }

        val groups = songs.toAlbumGroups()
        val locale = Locale.getDefault()
        val index = buildAdaptiveAlphabetIndexData(groups, RawIndexMode.AUTO, locale) { it.name }
        synchronized(lock) {
            if (source === songs) {
                albumGroups = groups
                albumIndexData = index
                indexLocaleTag = locale.toLanguageTag()
            }
        }
        groups
    }

    internal fun folders(songs: List<AudioFile>): List<FolderGroupUi> = synchronized(lock) {
        ensureSourceLocked(songs)
        folderGroups.orEmpty()
    }

    internal suspend fun loadFolders(songs: List<AudioFile>): List<FolderGroupUi> = withMetaTrace {
        synchronized(lock) {
            ensureSourceLocked(songs)
            folderGroups
        }?.let { return@withMetaTrace it }

        val groups = songs.toFolderGroups()
        val locale = Locale.getDefault()
        val index = buildAdaptiveAlphabetIndexData(groups, RawIndexMode.AUTO, locale) { it.name }
        synchronized(lock) {
            if (source === songs) {
                folderGroups = groups
                folderIndexData = index
                indexLocaleTag = locale.toLanguageTag()
            }
        }
        groups
    }

    internal fun artists(songs: List<AudioFile>): List<ArtistGroupUi> = synchronized(lock) {
        ensureSourceLocked(songs)
        artistGroups.orEmpty()
    }

    internal suspend fun loadArtists(songs: List<AudioFile>): List<ArtistGroupUi> = withMetaTrace {
        synchronized(lock) {
            ensureSourceLocked(songs)
            artistGroups
        }?.let { return@withMetaTrace it }

        val groups = songs.toArtistGroups()
        val locale = Locale.getDefault()
        val index = buildAdaptiveAlphabetIndexData(groups, RawIndexMode.AUTO, locale) { it.name }
        synchronized(lock) {
            if (source === songs) {
                artistGroups = groups
                artistIndexData = index
                indexLocaleTag = locale.toLanguageTag()
            }
        }
        groups
    }

    internal fun genres(songs: List<AudioFile>): List<CategoryGroupUi> = synchronized(lock) {
        ensureSourceLocked(songs)
        genreGroups.orEmpty()
    }

    internal suspend fun loadGenres(songs: List<AudioFile>): List<CategoryGroupUi> = withMetaTrace {
        synchronized(lock) {
            ensureSourceLocked(songs)
            genreGroups
        }?.let { return@withMetaTrace it }

        val groups = songs.toGenreGroups()
        val locale = Locale.getDefault()
        val index = buildAdaptiveAlphabetIndexData(groups, RawIndexMode.AUTO, locale) { it.name }
        synchronized(lock) {
            if (source === songs) {
                genreGroups = groups
                genreIndexData = index
                indexLocaleTag = locale.toLanguageTag()
            }
        }
        groups
    }

    internal fun years(songs: List<AudioFile>): List<CategoryGroupUi> = synchronized(lock) {
        ensureSourceLocked(songs)
        yearGroups.orEmpty()
    }

    internal suspend fun loadYears(songs: List<AudioFile>): List<CategoryGroupUi> = withMetaTrace {
        synchronized(lock) {
            ensureSourceLocked(songs)
            yearGroups
        }?.let { return@withMetaTrace it }

        val groups = songs.toYearGroups()
        val index = buildYearIndexData(groups)
        synchronized(lock) {
            if (source === songs) {
                yearGroups = groups
                yearIndexData = index
            }
        }
        groups
    }

    internal fun composers(songs: List<AudioFile>): List<CategoryGroupUi> = synchronized(lock) {
        ensureSourceLocked(songs)
        composerGroups.orEmpty()
    }

    internal suspend fun loadComposers(songs: List<AudioFile>): List<CategoryGroupUi> = withMetaTrace {
        synchronized(lock) {
            ensureSourceLocked(songs)
            composerGroups
        }?.let { return@withMetaTrace it }

        val groups = songs.toComposerGroups()
        val locale = Locale.getDefault()
        val index = buildAdaptiveAlphabetIndexData(groups, RawIndexMode.AUTO, locale) { it.name }
        synchronized(lock) {
            if (source === songs) {
                composerGroups = groups
                composerIndexData = index
                indexLocaleTag = locale.toLanguageTag()
            }
        }
        groups
    }

    internal fun songsIndex(songs: List<AudioFile>): RawAlphabetIndexData? = synchronized(lock) {
        ensureSourceLocked(songs)
        songIndexData.takeIf { indexLocaleTag == Locale.getDefault().toLanguageTag() }
    }

    internal suspend fun loadSongsIndex(songs: List<AudioFile>): RawAlphabetIndexData = withMetaTrace {
        val locale = Locale.getDefault()
        synchronized(lock) {
            ensureSourceLocked(songs)
            songIndexData?.takeIf { indexLocaleTag == locale.toLanguageTag() }
        }?.let { return@withMetaTrace it }

        val index = buildAdaptiveAlphabetIndexData(
            items = songs,
            requestedMode = RawIndexMode.AUTO,
            locale = locale,
        ) { it.displayName }
        val cacheKey = RawAlphabetIndexCache.keyForSongs(
            songs = songs,
            query = "",
            mode = RawIndexMode.AUTO,
            locale = locale,
        )
        RawAlphabetIndexCache.put(cacheKey, index)
        synchronized(lock) {
            if (source === songs) {
                songIndexData = index
                indexLocaleTag = locale.toLanguageTag()
            }
        }
        index
    }

    internal fun albumsIndex(groups: List<AlbumGroupUi>): RawAlphabetIndexData? = synchronized(lock) {
        albumIndexData.takeIf { albumGroups === groups && indexLocaleTag == Locale.getDefault().toLanguageTag() }
    }

    internal fun foldersIndex(groups: List<FolderGroupUi>): RawAlphabetIndexData? = synchronized(lock) {
        folderIndexData.takeIf { folderGroups === groups && indexLocaleTag == Locale.getDefault().toLanguageTag() }
    }

    internal fun artistsIndex(groups: List<ArtistGroupUi>): RawAlphabetIndexData? = synchronized(lock) {
        artistIndexData.takeIf { artistGroups === groups && indexLocaleTag == Locale.getDefault().toLanguageTag() }
    }

    internal fun genresIndex(groups: List<CategoryGroupUi>): RawAlphabetIndexData? = synchronized(lock) {
        genreIndexData.takeIf { genreGroups === groups && indexLocaleTag == Locale.getDefault().toLanguageTag() }
    }

    internal fun composersIndex(groups: List<CategoryGroupUi>): RawAlphabetIndexData? = synchronized(lock) {
        composerIndexData.takeIf { composerGroups === groups && indexLocaleTag == Locale.getDefault().toLanguageTag() }
    }

    internal fun yearsIndex(groups: List<CategoryGroupUi>): RawAlphabetIndexData? = synchronized(lock) {
        yearIndexData.takeIf { yearGroups === groups }
    }

    private fun ensureSourceLocked(songs: List<AudioFile>): Boolean {
        if (source === songs) return false
        source = songs
        albumGroups = null
        folderGroups = null
        artistGroups = null
        genreGroups = null
        yearGroups = null
        composerGroups = null
        homeStatistics = null
        songIndexData = null
        albumIndexData = null
        folderIndexData = null
        artistIndexData = null
        genreIndexData = null
        composerIndexData = null
        yearIndexData = null
        indexLocaleTag = ""
        return true
    }
}
