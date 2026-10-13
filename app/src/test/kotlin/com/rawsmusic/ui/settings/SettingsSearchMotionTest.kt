package com.rawsmusic.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchMotionTest {
    @Test fun cancelMaterialAppearsBeforeItsTextAndKeepsTheFunctionalButtonScale() {
        assertEquals(0.5f, SettingsSearchMotion.cancelAlpha(0.1f), 0.00001f)
        assertEquals(1f, SettingsSearchMotion.cancelAlpha(0.2f), 0f)
        assertEquals(0f, SettingsSearchMotion.cancelAlpha(-0.01f), 0f)
        assertEquals(1f, SettingsSearchMotion.cancelAlpha(1.01f), 0f)
        assertEquals(0.5f, SettingsSearchMotion.cancelScale(0f), 0f)
        assertEquals(0.75f, SettingsSearchMotion.cancelScale(0.5f), 0f)
        assertEquals(1f, SettingsSearchMotion.cancelScale(1f), 0f)
    }
    @Test fun parentOnlyMatchDoesNotFloodResultsWithEveryChildControl() {
        val text = SettingsSearchText("播放界面", "禁止滑动切歌", "手势", "swipe gesture")
        assertEquals(false, text.matches("播放界面"))
        assertTrue(text.matches("播放界面 切歌"))
    }
    @Test fun enteringSearchCoordinatesTitleAndResultsWithoutChangingSavedHomeOffset() {
        val half = SettingsSearchMotion.at(0.5f, entering = true)
        assertEquals(0.125f, half.homeAlpha, 0f)
        assertEquals(0.825f, half.titleScale, 0.00001f)
        assertEquals(-100f, half.titleOffsetPx, 0f)
        assertEquals(0.5f, half.resultAlpha, 0f)
        assertEquals(-78f, SettingsSearchMotion.headerOffset(-40f, 116f, 0.5f), 0f)
        assertEquals(-40f, SettingsSearchMotion.headerOffset(-40f, 116f, 0f), 0f)
        assertEquals(-116f, SettingsSearchMotion.headerOffset(-40f, 116f, 1f), 0f)
    }

    @Test fun leavingSearchRestoresHomeAndDoesNotUseTheEntryCubicFade() {
        assertEquals(0.5f, SettingsSearchMotion.at(0.5f, entering = false).homeAlpha, 0f)
        assertEquals(1f, SettingsSearchMotion.at(0f, entering = false).homeAlpha, 0f)
        assertEquals(0f, SettingsSearchMotion.at(1f, entering = true).homeAlpha, 0f)
    }

    @Test fun historyKeepsRecentDistinctNonemptyQueriesAndBoundsStorage() {
        assertEquals(listOf("HDR", "歌词"), updateSettingsSearchHistory(listOf("hdr", "歌词"), " HDR "))
        assertEquals(listOf("HDR"), updateSettingsSearchHistory(listOf("HDR"), "  "))
        assertEquals(6, updateSettingsSearchHistory((1..8).map { "query$it" }, "new").size)
    }

    @Test fun multiwordSearchRequiresEveryTokenAndExactTitlesRankFirst() {
        val text = SettingsSearchText("播放界面", "禁止滑动切歌", "手势", "swipe gesture")
        assertTrue(text.matches("禁止 切歌"))
        assertTrue(text.matches("SWIPE,gesture"))
        assertEquals(false, text.matches("禁止 扫描"))
        assertEquals(0, text.score("禁止滑动切歌"))
        assertEquals(2, text.score("切歌"))
    }
}
