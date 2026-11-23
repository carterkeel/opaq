package app.opaq.core

import app.opaq.core.nostr.RelayPool
import kotlin.test.Test
import kotlin.test.assertEquals

class RelayBackoffTest {
    private fun waits(othersConnected: Boolean, tries: Int): List<Long> =
        generateSequence(2_000L) { RelayPool.nextBackoff(it, othersConnected) }.take(tries).toList()

    @Test
    fun aloneItStaysWithinFiveMinutes() {
        // With no other relay up, updates have nowhere else to go, so keep trying every 5 minutes.
        assertEquals(5 * 60_000L, waits(othersConnected = false, tries = 20).last())
    }

    @Test
    fun withOthersUpADeadRelayIsLeftForUpToAnHour() {
        assertEquals(60 * 60_000L, waits(othersConnected = true, tries = 20).last())
    }

    @Test
    fun aDeadRelayCostsFarFewerTriesOverANight() {
        val night = 8 * 60 * 60_000L
        fun triesIn(othersConnected: Boolean): Int =
            waits(othersConnected, tries = 500).runningFold(0L) { t, w -> t + w }.takeWhile { it < night }.count()
        // Last night: one try every 5 minutes, each holding the network for a minute.
        assertEquals(103, triesIn(othersConnected = false))
        assertEquals(18, triesIn(othersConnected = true))
    }
}
