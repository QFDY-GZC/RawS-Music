package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualListArtworkLifecyclePolicyTest {
    @Test
    fun unresolvedArtworkUsesColorEvenWhenDefaultArtworkIsEnabled() {
        assertEquals(
            VirtualListArtworkPlaceholder.LoadingColor,
            resolveVirtualListArtworkPlaceholder(
                terminalNoArt = false,
                defaultArtworkEnabled = true,
            ),
        )
    }

    @Test
    fun terminalNoArtUsesConfiguredDefaultOrColor() {
        assertEquals(
            VirtualListArtworkPlaceholder.TerminalDefaultArtwork,
            resolveVirtualListArtworkPlaceholder(
                terminalNoArt = true,
                defaultArtworkEnabled = true,
            ),
        )
        assertEquals(
            VirtualListArtworkPlaceholder.TerminalColor,
            resolveVirtualListArtworkPlaceholder(
                terminalNoArt = true,
                defaultArtworkEnabled = false,
            ),
        )
    }

    @Test
    fun stableFirstWrapperRevealsButGeometryOwnedOrDeferredHolderSnaps() {
        assertEquals(
            VirtualListArtworkAdmissionAnimation.Reveal,
            resolveVirtualListArtworkAdmissionAnimation(
                hadRealBitmap = false,
                sameTier = false,
                deferLoad = false,
                animateChanges = true,
            ),
        )
        assertEquals(
            VirtualListArtworkAdmissionAnimation.None,
            resolveVirtualListArtworkAdmissionAnimation(
                hadRealBitmap = false,
                sameTier = false,
                deferLoad = true,
                animateChanges = true,
            ),
        )
        assertEquals(
            VirtualListArtworkAdmissionAnimation.None,
            resolveVirtualListArtworkAdmissionAnimation(
                hadRealBitmap = false,
                sameTier = false,
                deferLoad = false,
                animateChanges = false,
            ),
        )
    }

    @Test
    fun sameTierRewriteFadesButQualityUpgradeDoesNot() {
        assertEquals(
            VirtualListArtworkAdmissionAnimation.Replacement,
            resolveVirtualListArtworkAdmissionAnimation(
                hadRealBitmap = true,
                sameTier = true,
                deferLoad = false,
                animateChanges = true,
            ),
        )
        assertEquals(
            VirtualListArtworkAdmissionAnimation.None,
            resolveVirtualListArtworkAdmissionAnimation(
                hadRealBitmap = true,
                sameTier = false,
                deferLoad = false,
                animateChanges = true,
            ),
        )
    }

    @Test
    fun synchronousCacheReattachNeverReplaysReveal() {
        assertEquals(
            false,
            shouldAnimateVirtualListProviderAdmission(
                callbackDeliveredSynchronously = true,
                isAttached = true,
                deferLoad = false,
                animateChanges = true,
            ),
        )
        assertEquals(
            true,
            shouldAnimateVirtualListProviderAdmission(
                callbackDeliveredSynchronously = false,
                isAttached = true,
                deferLoad = false,
                animateChanges = true,
            ),
        )
        assertEquals(
            false,
            shouldAnimateVirtualListProviderAdmission(
                callbackDeliveredSynchronously = false,
                isAttached = false,
                deferLoad = false,
                animateChanges = true,
            ),
        )
    }

    @Test
    fun temporaryReattachIsDistinctFromFirstOrContinuousAttach() {
        assertEquals(false, isVirtualListTemporaryArtworkReattach(0, 0))
        assertEquals(false, isVirtualListTemporaryArtworkReattach(12, 12))
        assertEquals(true, isVirtualListTemporaryArtworkReattach(9, 12))
    }

    @Test
    fun previouslyPresentedIdentityRetriesWhenItsLeaseIsGone() {
        assertEquals(true, shouldRetryPresentedArtworkAfterLeaseLoss(true, false, false))
        assertEquals(true, shouldRetryPresentedArtworkAfterLeaseLoss(true, true, false))
        assertEquals(false, shouldRetryPresentedArtworkAfterLeaseLoss(true, true, true))
        assertEquals(false, shouldRetryPresentedArtworkAfterLeaseLoss(false, false, false))
    }

    @Test
    fun providerArtworkRequiresLiveWrapperNotJustPixels() {
        assertEquals(true, isVirtualListProviderArtworkSatisfied(true, true, true))
        assertEquals(false, isVirtualListProviderArtworkSatisfied(true, true, false))
        assertEquals(false, isVirtualListProviderArtworkSatisfied(true, false, true))
        assertEquals(false, isVirtualListProviderArtworkSatisfied(false, true, true))
        assertEquals(false, isVirtualListProviderArtworkSatisfied(true, true, true, hasRequestedTier = false))
        assertEquals(true, isVirtualListProviderArtworkSatisfied(true, true, true, hasRequestedTier = true))
    }

    @Test
    fun terminalNoArtCannotOverrideCurrentProviderWrapper() {
        assertEquals(false, shouldAcceptVirtualListTerminalNoArt(true, true, true))
        assertEquals(true, shouldAcceptVirtualListTerminalNoArt(true, false, false))
        assertEquals(true, shouldAcceptVirtualListTerminalNoArt(true, true, false))
        assertEquals(false, shouldAcceptVirtualListTerminalNoArt(false, false, false))
    }

    @Test
    fun visualReentryRefreshesEvenWhenIdentityAndPixelSizeStayTheSame() {
        assertEquals(true, shouldRefreshVirtualListArtworkOnVisualReentry(true, false))
        assertEquals(false, shouldRefreshVirtualListArtworkOnVisualReentry(false, false))
        assertEquals(false, shouldRefreshVirtualListArtworkOnVisualReentry(true, true))
    }
}
