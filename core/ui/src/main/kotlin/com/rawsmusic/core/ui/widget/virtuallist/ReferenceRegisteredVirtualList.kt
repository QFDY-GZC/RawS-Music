package com.rawsmusic.core.ui.widget.virtuallist

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.CoverTransitionTarget
import com.rawsmusic.core.ui.scene.NavScene

/**
 * Live provider/layout description published by a root library page.
 *
 * Page composables keep their chrome/dialog/selection state, but the physical list body is rendered
 * exactly once by ReferenceLibraryVirtualListHost.  This mirrors Reference: Activities/fragments can own
 * controls, while one VirtualList owns provider + current/retained layout + physical holders.
 */
@Stable
internal class ReferenceRegisteredVirtualList(
    val scene: NavScene,
) {
    /** Concrete route/provider identity for detail scenes (album key, artist key, folder path...). */
    var providerIdentity: String = ""
    /**
     * Exact physical provider identity identity for the currently published route argument.
     *
     * Root providers intentionally retain one token for their lifetime. Detail scenes reuse one
     * controller object in Raw, but endpoint preflight binds a concrete NEXT provider for the exact
     * album/artist/folder item; a different argument must therefore not inherit the previous
     * detail's holder bank/holder slot holder pool merely because the NavScene class is the same.
     */
    private var physicalPoolIdentityState: Any = Any()
    internal val physicalPoolIdentity: Any get() = physicalPoolIdentityState

    internal fun rebindProviderIdentity(next: String) {
        if (providerIdentity == next) return
        providerIdentity = next
        if (scene.isDetail()) physicalPoolIdentityState = Any()
    }
    var songs: List<AudioFile> = emptyList()
    var state: ComposeVirtualListState? = null
    var playingSongId: Long = -1L
    var currentPlayingIndex: Int = -1
    var selectedPositions: Set<Int> = emptySet()
    var revealIndexRequest: Int = -1
    var hidePlayingCover: Boolean = false
    var contentTopPadding: Dp = 0.dp
    var presentationTopClipInset: Dp = 0.dp
    var persistentHeaderHeight: Dp = 0.dp
    var persistentHeaderVisibilityHeight: Dp = 0.dp
    var persistentHeaderSceneItemVisibilityHeight: Dp = 0.dp
    var persistentHeaderSceneItemId: String = ""
    var virtualListEdgeEnabled: Boolean = false
    var contentBottomPadding: Dp? = null
    var sectionHeaders: List<VirtualListSectionHeader> = emptyList()
    var sectionHeaderHeight: Dp = 54.dp
    var pinchEnabled: Boolean = true
    var sharedCoverSceneId: String = ""
    var customProvider: VirtualListCustomProvider? = null
    var providerNavigationActive: Boolean by mutableStateOf(false)

    // Callback/lambda fields intentionally are ordinary vars. Their owner page remains composed and
    // updates them every frame; changing a callback identity is not a provider/layout invalidation.
    var persistentHeaderContent: @Composable (Boolean, Boolean) -> Unit = { _, _ -> }
    var sectionHeaderContent: @Composable (VirtualListSectionHeader) -> Unit = {}
    var sharedCoverElementIdProvider: (AudioFile, Int) -> String = { _, _ -> "" }
    var onPlayingCoverBoundsChanged: (RectF?) -> Unit = {}
    var onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit = {}
    var onSharedCoverTargetChanged: (AudioFile, Int, CoverTransitionTarget?) -> Unit = { _, _, _ -> }
    var onRevealCoverTargetResolved: (CoverTransitionTarget?) -> Unit = {}
    var onScrollActiveChanged: (Boolean) -> Unit = {}
    var onSongClick: (AudioFile, Int) -> Unit = { _, _ -> }
    var onSongLongClick: (AudioFile, Int) -> Unit = { _, _ -> }

    // Provider fields are intentionally plain vars so publishing does not create a snapshot write
    // per callback/layout field. Keep two clocks: only structural changes invalidate prepared
    // holder/LayoutRes populations, while callback/playing/content rebinding merely wakes the fixed
    // body so it can consume the latest live behavior. This mirrors reference player's stable provider identity provider
    // identity: changing a listener does not make NEXT a different physical provider population.
    private val publicationRevision = VirtualListPublicationRevision()
    private var revisionState by mutableIntStateOf(0)
    private var behaviorRevisionState by mutableIntStateOf(0)
    val revision: Int get() = revisionState
    val behaviorRevision: Int get() = behaviorRevisionState

    fun markStructuralPublicationChanged() {
        publicationRevision.markStructureChanged()
        revisionState = publicationRevision.value
    }

    fun markBehaviorRebound() {
        publicationRevision.markBehaviorRebound()
        behaviorRevisionState++
    }
}

@Stable
internal class ReferenceLibraryProviderRegistry {
    private val providers = LinkedHashMap<NavScene, ReferenceRegisteredVirtualList>()
    private data class BoundProviderKey(
        val scene: NavScene,
        val providerIdentity: String,
        val structuralRevision: Int,
    )
    private val boundProviders = LinkedHashMap<BoundProviderKey, Int>()

    /**
     * Root library providers are persistent controller identities, not one-composition snapshots.
     * reference player keeps the same provider identity provider object across repeated CURRENT<->NEXT visits, so its holder bank
     * holder pool remains reusable after returning to HOME and entering the same category again.
     */
    fun getOrCreate(scene: NavScene): ReferenceRegisteredVirtualList =
        providers.getOrPut(scene) { ReferenceRegisteredVirtualList(scene) }
    private var outerBindGenerationState by mutableIntStateOf(0)

    val outerBindGeneration: Int get() = outerBindGenerationState

    fun register(provider: ReferenceRegisteredVirtualList) {
        if (providers[provider.scene] !== provider) providers[provider.scene] = provider
    }

    /**
     * Reference keeps the previous provider behind the retained layout after its controller/page is
     * no longer current.  Do not unregister on composition disposal; the next composition of the
     * same scene atomically replaces this snapshot.
     */
    fun retain(scene: NavScene, provider: ReferenceRegisteredVirtualList) {
        if (providers[scene] !== provider) providers[scene] = provider
    }

    /**
     * A root HOME/category transaction is ready only after the persistent runtime has bound the
     * CURRENT/RETAINED LayoutRes pair and completed holder preflight. Provider prewarm alone is not
     * enough: that can finish one composition before [VirtualListSettledNodeRuntime.publishOuterFrame].
     */
    fun markOuterPopulationBound(vararg providers: ReferenceRegisteredVirtualList?) {
        outerBindGenerationState++
        val generation = outerBindGenerationState
        providers.filterNotNull().forEach { provider ->
            recordBoundProvider(provider, generation)
        }
    }

    /**
     * Exact NEXT-preflight ACK. This is fired only after the permanent Android presentation host
     * owns the detached prepared child Views, i.e. Raw's endpoint-preflight bind finishing
     * NEXT provider identity/LayoutRes/holder slot binding before ItemToHeader starts.
     */
    fun markPreparedProviderMaterialized(provider: ReferenceRegisteredVirtualList) {
        outerBindGenerationState++
        recordBoundProvider(provider, outerBindGenerationState)
    }

    private fun recordBoundProvider(provider: ReferenceRegisteredVirtualList, generation: Int) {
        boundProviders[
            BoundProviderKey(
                scene = provider.scene,
                providerIdentity = provider.providerIdentity,
                structuralRevision = provider.revision,
            )
        ] = generation
    }

    fun isExactProviderOuterBound(
        scene: NavScene,
        providerIdentity: String,
        minGenerationExclusive: Int = Int.MIN_VALUE,
    ): Boolean {
        val provider = providers[scene] ?: return false
        if (provider.providerIdentity != providerIdentity) return false
        val generation = boundProviders[
            BoundProviderKey(scene, providerIdentity, provider.revision)
        ] ?: return false
        return generation > minGenerationExclusive
    }

    operator fun get(scene: NavScene?): ReferenceRegisteredVirtualList? = scene?.let(providers::get)
}

/** Non-null only while a root library page is publishing into the one persistent VirtualList owner. */
internal val LocalReferenceLibraryProviderRegistry =
    staticCompositionLocalOf<ReferenceLibraryProviderRegistry?> { null }

/** Scene whose page/controller is currently publishing a provider. */
internal val LocalReferenceLibraryProviderScene =
    staticCompositionLocalOf<NavScene?> { null }

/** Concrete provider identity. Root categories use an empty identity. */
internal val LocalReferenceLibraryProviderIdentity = staticCompositionLocalOf { "" }

/** True only for the single body call made by ReferenceLibraryVirtualListHost itself. */
internal val LocalReferenceLibraryBodyRender = staticCompositionLocalOf { false }

/**
 * True only for the transient destination-preparation composition. The page/controller is allowed
 * to publish its provider identity and callbacks, but must not create a second physical VirtualList
 * body. This is the Compose equivalent of Reference binding the destination adapter before the
 * GenericPivot layout starts.
 */
internal val LocalReferenceLibraryProviderPublicationOnly = staticCompositionLocalOf { false }

/** Previous provider/layout snapshot consumed by the one ComposeVirtualList body during GenericPivot. */
internal val LocalReferenceRetainedVirtualListProvider =
    staticCompositionLocalOf<ReferenceRegisteredVirtualList?> { null }
