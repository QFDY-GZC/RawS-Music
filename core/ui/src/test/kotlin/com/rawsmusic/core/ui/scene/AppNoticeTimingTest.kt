package com.rawsmusic.core.ui.scene

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNoticeTimingTest {
    @Test fun staticNoticeKeepsExistingDwell() = assertEquals(3500L, noticeVisibleDurationMs(null))
    @Test fun shortAnimationDoesNotShortenNotice() = assertEquals(3500L, noticeVisibleDurationMs(3034L))
    @Test fun longAnimationGetsASecondAfterCompletion() = assertEquals(6000L, noticeVisibleDurationMs(5000L))
    @Test fun invalidDurationFallsBack() = assertEquals(3500L, noticeVisibleDurationMs(-1L))
}
