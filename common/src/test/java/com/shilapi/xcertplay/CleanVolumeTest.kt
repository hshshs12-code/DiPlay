package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class CleanVolumeTest {
    @Test fun curveIsMonotonicMutesAtZeroAndReachesUnity() {
        var previous = -1f
        for (i in 0..30) {
            val g = CleanVolume.gainFor(i, 30)
            assert(g > previous) { "step $i gain $g not above $previous" }
            previous = g
        }
        assertEquals(0f, CleanVolume.gainFor(0, 30), 0f)
        assertEquals(1f, CleanVolume.gainFor(30, 30), 0f)
        assertEquals(1f, CleanVolume.gainFor(40, 30), 0f)
        assertEquals(0.25f, CleanVolume.gainFor(15, 30), 1e-6f)   // −12 dB at half the range
        assertEquals(0f, CleanVolume.gainFor(5, 0), 0f)
    }

    @Test fun knobMovementsFoldIntoTheVirtualLevelAroundTheParkedStep() {
        assertEquals(21, CleanVolume.nextIndex(20, 21, 20, 30))
        assertEquals(19, CleanVolume.nextIndex(20, 19, 20, 30))
        assertEquals(27, CleanVolume.nextIndex(25, 22, 20, 30))   // a fast turn delivers two steps at once
        assertEquals(30, CleanVolume.nextIndex(30, 21, 20, 30))
        assertEquals(0, CleanVolume.nextIndex(0, 19, 20, 30))
    }
}
