package com.rawsmusic.lyric

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SuperIslandCompatibilityTest {
    @Test fun firstFocusStartStillPublishesExplicitUpdate() {
        val calls = mutableListOf<String>()
        assertTrue(SuperIslandPublication.publish(false,
            { calls += "focus"; true }, { calls += "warm"; true }, { calls += "notify" }))
        assertEquals(listOf("focus", "notify"), calls)
    }

    @Test fun rejectedFocusUsesWarmStartThenUpdate() {
        val calls = mutableListOf<String>()
        assertTrue(SuperIslandPublication.publish(false,
            { calls += "focus"; false }, { calls += "warm"; true }, { calls += "notify" }))
        assertEquals(listOf("focus", "warm", "notify"), calls)
    }

    @Test fun failedForegroundDoesNotPublishOrdinaryFocus() {
        var notified = false
        assertFalse(SuperIslandPublication.publish(false, { false }, { false }, { notified = true }))
        assertFalse(notified)
    }

    @Test fun existingForegroundOnlyUpdatesNotification() {
        var notified = false
        assertTrue(SuperIslandPublication.publish(true,
            { error("already foreground") }, { error("already foreground") }, { notified = true }))
        assertTrue(notified)
    }

    @Test fun markerIsDurableBeforeDenyAndKeptAfterFailure() = runBlocking {
        val calls = mutableListOf<String>()
        assertFalse(XmsfNetworkRecovery.apply(false,
            { calls += "marker=$it"; true }, { calls += "deny"; false }))
        assertEquals(listOf("marker=true", "deny"), calls)
    }

    @Test fun failedRestoreRetainsMarkerAndSuccessfulRestoreClearsIt() = runBlocking {
        var blocked = true
        assertFalse(XmsfNetworkRecovery.apply(true, { blocked = it; true }, { false }))
        assertTrue(blocked)
        assertTrue(XmsfNetworkRecovery.apply(true, { blocked = it; true }, { true }))
        assertFalse(blocked)
    }

    @Test fun failedDiskWriteDoesNotApplyDeny() = runBlocking {
        var applied = false
        assertFalse(XmsfNetworkRecovery.apply(false, { false }, { applied = true; true }))
        assertFalse(applied)
    }
}
