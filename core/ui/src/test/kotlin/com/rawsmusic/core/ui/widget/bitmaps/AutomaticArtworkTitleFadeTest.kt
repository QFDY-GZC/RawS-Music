package com.rawsmusic.core.ui.widget.bitmaps

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticArtworkTitleFadeTest {
    @Test fun titleUsesSameArtworkClockAndAsymmetricAlpha() {
        for (role in PlayerArtworkItemRole.entries) {
            for (step in 0..12) {
                val p = step / 12f
                val title = playerArtworkTitleTransform(PlayerArtworkAnimationStyle.entries.first(), role,
                    PlayerArtworkDirection.Next, p, 900f, 3f, fadeOnly = true, automaticCrossfade = true)
                val alpha = if (role == PlayerArtworkItemRole.Current)
                    automaticCrossfadeArtworkOutAlpha(p) else automaticCrossfadeArtworkInAlpha(p)
                assertEquals(alpha, title.alpha, 0.00001f)
                assertEquals(0f, title.translationX, 0.00001f)
                assertEquals(1f, title.scaleX, 0.00001f)
            }
        }
    }
}
