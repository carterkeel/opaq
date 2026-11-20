package app.opaq.core

import app.opaq.core.model.LocationSample
import app.opaq.core.nostr.RelayReplies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Battery and relay behaviour while nothing is happening: fewer wake-ups, no repeated requests. */
class QuietRunningTest {
    private val now = 1_800_000_000L

    private fun still(t: Long) = LocationSample(41.0, -87.0, 5f, 0f, null, t)

    @Test
    fun stillPhoneWaitsUntilTheNextUpdateIsDue() {
        val p = MotionPolicy()
        p.add(still(now))
        p.markPublished(now)
        assertEquals(30_000L, p.nextCheckMillis(now + 1), "still and nothing due: wait the longest")

        // The wait never runs past the moment the next still update is due, so its timing doesn't change.
        val due = (now..now + 400).first { p.shouldPublish(it) }
        for (t in due - 29..due - 1) {
            assertEquals((due - t) * 1000, p.nextCheckMillis(t), "at ${t - now} s")
        }
        assertEquals(1_000L, p.nextCheckMillis(due), "overdue: check again soon")
    }

    @Test
    fun movingOrAboutToMoveChecksOften() {
        val p = MotionPolicy()
        assertEquals(MotionPolicy.FAST_CHECK_MILLIS, p.nextCheckMillis(now), "no fix yet")
        p.add(still(now))
        assertEquals(MotionPolicy.FAST_CHECK_MILLIS, p.nextCheckMillis(now), "nothing published yet")
        p.markPublished(now)
        p.motionHint(now + 5)
        assertEquals(MotionPolicy.FAST_CHECK_MILLIS, p.nextCheckMillis(now + 5), "motion hint: GPS on")
        p.publishSoon()
        assertEquals(MotionPolicy.FAST_CHECK_MILLIS, p.nextCheckMillis(now + 6), "an update asked for")

        // Driving, then the fixes stop: checks stay quick until the stop has been published.
        val d = MotionPolicy()
        d.add(LocationSample(41.0, -87.0, 5f, 15f, null, now))
        d.add(LocationSample(41.001, -87.0, 5f, 16f, null, now + 5))
        d.markPublished(now + 5)
        assertTrue(d.isMoving(now + 5))
        d.refresh(now + 70)
        assertEquals(MotionPolicy.FAST_CHECK_MILLIS, d.nextCheckMillis(now + 70), "the stop isn't out yet")
        d.markPublished(now + 70)
        assertEquals(30_000L, d.nextCheckMillis(now + 71), "stop published: back to waiting")
    }

    @Test
    fun directMessageFilterOnlyChangesOnTheHour() {
        val hourStart = now - now.mod(3600L)
        val first = Engine.directSince(hourStart)
        for (t in hourStart until hourStart + 3600 step 60) assertEquals(first, Engine.directSince(t))
        assertEquals(first + 3600, Engine.directSince(hourStart + 3600))
        assertTrue(first <= hourStart - 7 * 24 * 3600, "still covers the whole week")
        assertEquals(0L, first.mod(3600L))
    }

    @Test
    fun relayRefusalsAreNotRepeatedStraightAway() {
        assertEquals(RelayReplies.LONG_HOLD_MILLIS, RelayReplies.subscriptionHoldMillis("auth-required: requested filter requires authentication"))
        assertEquals(RelayReplies.LONG_HOLD_MILLIS, RelayReplies.subscriptionHoldMillis("restricted: not allowed"))
        assertEquals(RelayReplies.SHORT_HOLD_MILLIS, RelayReplies.subscriptionHoldMillis("rate-limited: slow down"))
        assertEquals(RelayReplies.SHORT_HOLD_MILLIS, RelayReplies.subscriptionHoldMillis(""))

        assertEquals(RelayReplies.LONG_HOLD_MILLIS, RelayReplies.publishHoldMillis("banned: too many rate-limit violations, try again later"))
        assertEquals(RelayReplies.LONG_HOLD_MILLIS, RelayReplies.publishHoldMillis("blocked: you are not allowed"))
        assertEquals(RelayReplies.SHORT_HOLD_MILLIS, RelayReplies.publishHoldMillis("rate-limited: slow down"))
        assertNull(RelayReplies.publishHoldMillis("duplicate: already have this event"))
        assertNull(RelayReplies.publishHoldMillis("invalid: bad signature"))
    }
}
