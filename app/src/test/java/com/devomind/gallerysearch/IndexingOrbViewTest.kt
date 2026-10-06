package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The breath math the orb is drawn from, held apart from the view because the failure it exists to
 * prevent is invisible: an Animator inherits the system "Animator duration scale", so a device with
 * that at 0 renders a permanently still orb while every lifecycle check in the class still passes.
 * Deriving the phase from a clock makes the animation's existence a property of these functions, and
 * these functions are testable without a screen.
 */
class IndexingOrbViewTest {

    private val loop = IndexingOrbView.LOOP_DURATION_MS

    @Test
    fun thePhaseComesFromTheClockAndWrapsWithoutDrifting() {
        assertEquals(0f, IndexingOrbView.phaseAt(0L), 0f)
        assertEquals(0.5f, IndexingOrbView.phaseAt(loop / 2), 1e-6f)
        assertEquals(0f, IndexingOrbView.phaseAt(loop), 1e-6f)
        // Hours into a charging pass the reading is large; the loop must still be inside itself.
        assertEquals(
            IndexingOrbView.phaseAt(45_000L),
            IndexingOrbView.phaseAt(45_000L + 7 * loop),
            1e-6f
        )
        for (millis in listOf(1L, loop - 1, loop * 100 + 3, Long.MAX_VALUE / 2)) {
            val phase = IndexingOrbView.phaseAt(millis)
            assertTrue("phase should stay in the loop at $millis: $phase", phase >= 0f && phase < 1f)
        }
    }

    @Test
    fun theBreathClosesSeamlesslyAndPeaksMidLoop() {
        assertEquals(0f, IndexingOrbView.waveAt(0f), 1e-6f)
        assertEquals(1f, IndexingOrbView.waveAt(0.5f), 1e-6f)
        assertEquals(0f, IndexingOrbView.waveAt(1f), 1e-6f)
        // The seam, where a linear sawtooth would snap back: both sides of 0/1 agree.
        assertTrue(
            "no jump at the seam",
            abs(IndexingOrbView.waveAt(0.999f) - IndexingOrbView.waveAt(0.001f)) < 1e-3f
        )
        for (phase in listOf(0.1f, 0.2f, 0.3f, 0.4f)) {
            assertTrue(
                "the wave should climb to its peak, not dwell: $phase",
                IndexingOrbView.waveAt(phase + 0.05f) > IndexingOrbView.waveAt(phase)
            )
        }
    }

    @Test
    fun theRingAndDiscBreatheInOppositeDirections() {
        val ring = IndexingOrbView.scaleAt(1f, IndexingOrbView.RING_PEAK_SCALE)
        val disc = IndexingOrbView.scaleAt(1f, IndexingOrbView.DISC_MIN_SCALE)
        assertTrue("at the peak the ring is at its widest", ring > 1f)
        assertTrue("at the peak the disc is at its smallest", disc < 1f)
        assertEquals(1f, IndexingOrbView.scaleAt(0f, IndexingOrbView.RING_PEAK_SCALE), 0f)
        assertEquals(1f, IndexingOrbView.scaleAt(0f, IndexingOrbView.DISC_MIN_SCALE), 0f)
    }

    /**
     * The clip this view was rewritten to avoid: the ring's widest frame has to stay inside the bounds,
     * so the breath reads as a pulse instead of as a shape being cut into a square.
     */
    @Test
    fun theRingsWidestPointStaysInsideTheView() {
        val outerEdge = IndexingOrbView.RING_RADIUS * IndexingOrbView.RING_PEAK_SCALE +
            IndexingOrbView.RING_STROKE / 2f
        assertTrue("the ring would be clipped at its peak: $outerEdge", outerEdge <= 0.5f)
    }

    @Test
    fun theSwingIsWideEnoughToReadAsMotionAtTwentyTwoDp() {
        // Radius alone moves the edge by a couple of pixels here; alpha is what makes it visible, and
        // a floor set a few percent under full would silently put the frozen look back.
        assertTrue(
            "the ring must dim enough to be seen breathing",
            IndexingOrbView.FULL_ALPHA - IndexingOrbView.RING_DIM_ALPHA >= 100
        )
        assertTrue(
            "the disc must brighten enough to be seen breathing",
            IndexingOrbView.FULL_ALPHA - IndexingOrbView.DISC_DIM_ALPHA >= 60
        )
        assertEquals(
            IndexingOrbView.FULL_ALPHA,
            IndexingOrbView.ringAlphaAt(0f)
        )
        assertEquals(IndexingOrbView.RING_DIM_ALPHA, IndexingOrbView.ringAlphaAt(1f))
        assertEquals(IndexingOrbView.DISC_DIM_ALPHA, IndexingOrbView.discAlphaAt(0f))
        assertEquals(IndexingOrbView.FULL_ALPHA, IndexingOrbView.discAlphaAt(1f))
    }
}
