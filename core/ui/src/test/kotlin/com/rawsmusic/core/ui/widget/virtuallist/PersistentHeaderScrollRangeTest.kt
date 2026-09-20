package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertEquals
import org.junit.Test

class PersistentHeaderScrollRangeTest {
    @Test
    fun underfilledDetailKeepsRealContentRangeInsteadOfInventingHeroRunway() {
        assertEquals(
            180,
            maxScrollForPersistentHeaderContent(180),
        )
    }

    @Test
    fun naturallyScrollableShortDetailKeepsItsNaturalRange() {
        assertEquals(
            1450,
            maxScrollForPersistentHeaderContent(1450),
        )
    }

    @Test
    fun denseGridDoesNotClampNaturalRangeToHeaderHeight() {
        assertEquals(
            1500,
            maxScrollForPersistentHeaderContent(1500),
        )
    }

    @Test
    fun longCollectionKeepsNaturalScrollRange() {
        assertEquals(
            4200,
            maxScrollForPersistentHeaderContent(4200),
        )
    }

    @Test
    fun emptyCollectionDoesNotInventScroll() {
        assertEquals(
            0,
            maxScrollForPersistentHeaderContent(0),
        )
    }

    @Test
    fun underfilledLaneIsBasedOnRealContentRangeNotOnlyOneRow() {
        assertEquals(true, usesUnderfilledPersistentHeaderLane(0, 1200, itemCount = 1))
        assertEquals(true, usesUnderfilledPersistentHeaderLane(180, 1200, itemCount = 3))
        assertEquals(false, usesUnderfilledPersistentHeaderLane(1200, 1200, itemCount = 3))
        assertEquals(false, usesUnderfilledPersistentHeaderLane(4200, 1200, itemCount = 30))
        assertEquals(false, usesUnderfilledPersistentHeaderLane(0, 0, itemCount = 1))
        assertEquals(false, usesUnderfilledPersistentHeaderLane(0, 1200, itemCount = 0))
    }

    @Test
    fun oneRowConsumesUpwardRemainderAtTerminalEndOnLegacyPath() {
        assertEquals(
            -80f,
            consumedScrollDeltaWithTerminalPersistentHeader(
                deltaPx = -80f,
                oldScrollPx = 1200f,
                newScrollPx = 1200f,
                maxScrollPx = 1200f,
                persistentHeaderHeightPx = 1200,
                itemCount = 1,
                columns = 1,
            ),
            0.001f,
        )
    }

    @Test
    fun crossingTerminalEndConsumesWholeUpwardGestureStepOnLegacyPath() {
        assertEquals(
            -100f,
            consumedScrollDeltaWithTerminalPersistentHeader(
                deltaPx = -100f,
                oldScrollPx = 1150f,
                newScrollPx = 1200f,
                maxScrollPx = 1200f,
                persistentHeaderHeightPx = 1200,
                itemCount = 1,
                columns = 1,
            ),
            0.001f,
        )
    }

    @Test
    fun oneRowDoesNotConsumeDownwardRemainderAtTopOnLegacyPath() {
        assertEquals(
            0f,
            consumedScrollDeltaWithTerminalPersistentHeader(
                deltaPx = 80f,
                oldScrollPx = 0f,
                newScrollPx = 0f,
                maxScrollPx = 1200f,
                persistentHeaderHeightPx = 1200,
                itemCount = 1,
                columns = 1,
            ),
            0.001f,
        )
    }

    @Test
    fun multiRowListKeepsOrdinaryUnconsumedEndBehaviorOnLegacyPath() {
        assertEquals(
            0f,
            consumedScrollDeltaWithTerminalPersistentHeader(
                deltaPx = -80f,
                oldScrollPx = 4200f,
                newScrollPx = 4200f,
                maxScrollPx = 4200f,
                persistentHeaderHeightPx = 1200,
                itemCount = 8,
                columns = 1,
            ),
            0.001f,
        )
    }

    @Test
    fun underfilledDetailUsesExactHeaderAndBodyScrollLane() {
        assertEquals(true, usesExactPersistentHeaderScrollLane(1200, itemCount = 1, naturalMaxScrollPx = 0))
        assertEquals(true, usesExactPersistentHeaderScrollLane(1200, itemCount = 3, naturalMaxScrollPx = 180))
        assertEquals(false, usesExactPersistentHeaderScrollLane(1200, itemCount = 5, naturalMaxScrollPx = 1200))
        assertEquals(false, usesExactPersistentHeaderScrollLane(0, itemCount = 1, naturalMaxScrollPx = 0))
    }

    @Test
    fun oneRowConsumesResidualUpwardFlingAtTerminalAnchorOnLegacyPath() {
        assertEquals(
            true,
            shouldConsumeTerminalPersistentHeaderFling(
                initialVelocityPxPerSecond = -4200f,
                currentScrollPx = 1200f,
                maxScrollPx = 1200f,
                persistentHeaderHeightPx = 1200,
                itemCount = 1,
                columns = 1,
            ),
        )
    }

    @Test
    fun oneRowDoesNotStealFlingBeforeTerminalAnchorOnLegacyPath() {
        assertEquals(
            false,
            shouldConsumeTerminalPersistentHeaderFling(
                initialVelocityPxPerSecond = -4200f,
                currentScrollPx = 850f,
                maxScrollPx = 1200f,
                persistentHeaderHeightPx = 1200,
                itemCount = 1,
                columns = 1,
            ),
        )
    }

    @Test
    fun oneRowDoesNotStealDownwardFlingOrMultiRowFlingOnLegacyPath() {
        assertEquals(
            false,
            shouldConsumeTerminalPersistentHeaderFling(
                initialVelocityPxPerSecond = 4200f,
                currentScrollPx = 1200f,
                maxScrollPx = 1200f,
                persistentHeaderHeightPx = 1200,
                itemCount = 1,
                columns = 1,
            ),
        )
        assertEquals(
            false,
            shouldConsumeTerminalPersistentHeaderFling(
                initialVelocityPxPerSecond = -4200f,
                currentScrollPx = 4200f,
                maxScrollPx = 4200f,
                persistentHeaderHeightPx = 1200,
                itemCount = 8,
                columns = 1,
            ),
        )
    }
    @Test
    fun underfilledExactLaneDoesNotRebucketBodyByRowStride() {
        assertEquals(
            137,
            persistentHeaderBodyLayoutScrollYPx(
                scrollYPx = 137,
                rowStridePx = 192,
                exactPixelLane = true,
            ),
        )
        assertEquals(
            384,
            persistentHeaderBodyLayoutScrollYPx(
                scrollYPx = 511,
                rowStridePx = 192,
                exactPixelLane = false,
            ),
        )
    }

}
