package com.fablab503.velotrack.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fablab503.velotrack.model.GpsStatus
import com.fablab503.velotrack.model.RecordingStatus
import com.fablab503.velotrack.model.RideStatsSnapshot
import com.fablab503.velotrack.recording.RideSession
import com.fablab503.velotrack.sync.WearSync
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import org.junit.After
import org.junit.Assume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Proves the phone half of the phone-to-watch contract: that a ride in [RideSession] becomes a Data
 * Layer item carrying the right keys and the right numbers.
 *
 * Together with the watch's own RideDataLayerTest, this covers everything on both sides of the
 * radio. The hop itself is Play Services and needs a real pairing, which two emulators cannot have
 * without a Google sign-in - so it stays honestly listed as unverified rather than being implied by
 * a green test.
 *
 * The keys are deliberately asserted by their [WearSync] constants **and** by what the watch's own
 * decoder makes of them. A rename on one side only is the failure this catches, and the Data Layer
 * gives no error for it: the watch would simply show nothing.
 */
@RunWith(AndroidJUnit4::class)
class WearPublisherTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun clearRide() {
        RideSession.reset()
    }

    @Test
    fun aRideInProgressIsPublishedWithTheNumbersTheWatchExpects() {
        RideSession.update {
            it.copy(
                status = RecordingStatus.RECORDING,
                speedMps = 7.5f,
                gps = GpsStatus(satellitesUsed = 9, satellitesTotal = 12, hasFix = true),
                stats = RideStatsSnapshot(
                    distanceM = 12_400.0,
                    movingMs = 2_400_000L,
                    elapsedMs = 2_472_000L,
                ),
            )
        }

        WearPublisher.publishNow(context)

        val map = try {
            awaitRideItem()
        } catch (e: Exception) {
            // ApiException 17 (SIGN_IN_REQUIRED): on a phone image the Wearable Data Layer refuses
            // to serve an app unless a Google account is signed in. The Wear OS image has no such
            // restriction, which is why the watch's own RideDataLayerTest runs here and this does
            // not. Skipped, loudly - never quietly passed - so nobody reads a green run as proof
            // the phone publishes correctly.
            Assume.assumeNoException(
                "Wearable Data Layer unavailable on this device (needs a signed-in Google " +
                    "account); phone-side publishing not exercised",
                e,
            )
            null
        }
        assertTrue("WearPublisher never wrote a ride item", map != null)

        assertEquals(RecordingStatus.RECORDING.name, map!!.getString(WearSync.KEY_STATUS))
        assertEquals(7.5f, map.getFloat(WearSync.KEY_SPEED_MPS), 0.001f)
        assertEquals(12_400.0, map.getDouble(WearSync.KEY_DISTANCE_M), 0.001)
        assertEquals(2_472_000L, map.getLong(WearSync.KEY_ELAPSED_MS))
        assertEquals(2_400_000L, map.getLong(WearSync.KEY_MOVING_MS))
        assertEquals(9, map.getInt(WearSync.KEY_SATELLITES))
        assertTrue(map.getBoolean(WearSync.KEY_HAS_FIX))
        assertTrue(
            "the timestamp must be present, or the Data Layer drops an unchanged payload and a " +
                "stationary rider's watch freezes",
            map.getLong(WearSync.KEY_UPDATED_AT) > 0L,
        )
    }

    @Test
    fun theStatusStringIsExactlyWhatTheWatchComparesAgainst() {
        // The phone writes RecordingStatus.name; the watch compares against WearSync constants.
        // These are two separate declarations and nothing but this test keeps them equal.
        assertEquals(WearSync.STATUS_IDLE, RecordingStatus.IDLE.name)
        assertEquals(WearSync.STATUS_RECORDING, RecordingStatus.RECORDING.name)
        assertEquals(WearSync.STATUS_PAUSED, RecordingStatus.PAUSED.name)
        assertEquals(WearSync.STATUS_AUTO_PAUSED, RecordingStatus.AUTO_PAUSED.name)
    }

    private fun awaitRideItem(): com.google.android.gms.wearable.DataMap? {
        val client = Wearable.getDataClient(context)
        // publishNow hops to an IO dispatcher, so poll briefly rather than assuming it has landed.
        repeat(20) {
            val items = Tasks.await(client.dataItems, 15, TimeUnit.SECONDS)
            val found = items.firstOrNull { it.uri.path == WearSync.PATH_RIDE }
                ?.let { DataMapItem.fromDataItem(it).dataMap }
            items.release()
            if (found != null && found.getString(WearSync.KEY_STATUS) == RecordingStatus.RECORDING.name) {
                return found
            }
            Thread.sleep(500)
        }
        return null
    }
}
