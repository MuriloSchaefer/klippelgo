package org.klippelgo.chain

import org.klippelgo.core.Coordinate
import kotlin.test.Test
import kotlin.test.assertEquals

/** How often a phone says a message again, and how it tells near requests from far ones. Neither needs a chain. */
class AnnouncingTest {
    /** The moments a [Cadence] fires, on a loop ticking every `tick` ms for `span` ms. */
    private fun sends(most: Long, span: Long, tick: Long): List<Long> {
        val cadence = Cadence(most, 0)
        return (tick..span step tick).filter { cadence.due(it) }
    }

    @Test
    fun eachGapIsTwiceTheLastUpToTheMost() {
        assertEquals(listOf(2_500L, 7_500L, 17_500L, 37_500L, 77_500L, 137_500L), sends(60_000, 140_000, 500))
    }

    @Test
    fun aSlowLoopSendsNoFasterThanTheCadence() {
        // On the two-second refresh loop, as a phone runs it: the first send waits for the tick after 2.5 s.
        assertEquals(listOf(4_000L, 10_000L, 16_000L), sends(5_000, 20_000, 2_000))
    }

    @Test
    fun aRouteThatIsNeverAcknowledgedGoesOutAFewTimesAMinute() {
        // Everything else in a request's ten minutes: 232 sends at the old 2.5 s.
        assertEquals(13, sends(60_000, 580_000, 500).size)
    }

    @Test
    fun nearerIsALowerBandAndNoPositionMeansNoOrder() {
        val here = Coordinate(-249_500_000, -534_500_000)
        assertEquals(0, Matchmaker.distanceBand(here, here))
        assertEquals(1, Matchmaker.distanceBand(here, Coordinate(here.latE7 + 200_000, here.lonE7)))
        assertEquals(3, Matchmaker.distanceBand(here, Coordinate(here.latE7 + 100_000, here.lonE7 - 600_000)))
        assertEquals(0, Matchmaker.distanceBand(null, here))
        assertEquals(0, Matchmaker.distanceBand(here, null))
    }
}
