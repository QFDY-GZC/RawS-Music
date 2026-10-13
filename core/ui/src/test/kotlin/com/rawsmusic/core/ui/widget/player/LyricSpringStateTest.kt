package com.rawsmusic.core.ui.widget.player
import org.junit.Assert.*
import org.junit.Test

class LyricSpringStateTest {
    @Test fun responseMatchesTheDerivedIosEquationAcrossFrameRates() {
        val a = LyricSpringState(300f)
        val b = LyricSpringState(300f)
        repeat(60) { a.advance(1f / 60f) }
        repeat(120) { b.advance(1f / 120f) }
        assertEquals(a.value, b.value, .002f)
        assertEquals(a.velocity, b.velocity, .01f)
    }
    @Test fun changingTheTargetDoesNotDiscardMomentum() {
        val spring = LyricSpringState(200f)
        spring.advance(.2f)
        val velocity = spring.velocity
        val position = spring.value
        spring.value += 180f
        assertEquals(position + 180f, spring.value, .001f)
        assertEquals(velocity, spring.velocity, .001f)
        spring.advance(.008f)
        assertTrue(spring.velocity < 0f)
    }
    @Test fun auxiliarySpringsStayFiniteAndSettleWithoutBounce() {
        for ((stiffness, damping) in listOf(150f to 1.2247449f, 130f to 1.315587f)) {
            val spring = LyricSpringState(100f)
            var previous = spring.value
            repeat(240) {
                spring.advance(1f / 120f, stiffness, damping)
                assertTrue(spring.value.isFinite())
                assertTrue(spring.value >= 0f && spring.value <= previous)
                previous = spring.value
            }
            assertFalse(spring.running)
        }
    }
    @Test fun quickForwardAndReverseChangesPreserveEveryDisplayedRow() {
        val engine = LyricPullEngine()
        val rows = listOf(10, 11, 12, 13)
        engine.beginForwardPull(10, 220f, 1000f, rows, 1000)
        for (time in 1016L..1192L step 16L) engine.advance(time, rows)
        val before = engine.advance(1200, rows)
        engine.beginTransition(11, -120f, 1000f, rows, 1200, false)
        val after = engine.advance(1200, rows)
        for (row in rows) assertEquals(before.stackOffsetPx + before.offsetsPx.getValue(row),
            after.stackOffsetPx + after.offsetsPx.getValue(row) + 120f, .01f)
    }
}
