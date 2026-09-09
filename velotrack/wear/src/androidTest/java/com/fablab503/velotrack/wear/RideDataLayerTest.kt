package com.fablab503.velotrack.wear

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fablab503.velotrack.sync.WearSync
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.DataMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * Proves the half of the phone-to-watch link that lives on the watch.
 *
 * The radio hop between two devices is Google's code and needs a real pairing, which cannot be set
 * up between two emulators without signing in to a Google account. Everything either side of that
 * hop is ours, and this exercises the watch side for real: a ride payload is written into the Data
 * Layer **on this device**, read back out of it, and decoded by the same [toRide] the phone's
 * payload goes through. The watch cannot tell the difference between an item that arrived over
 * Bluetooth and one written locally - by the time [toRide] sees it, it is the same DataMap.
 *
 * What this does NOT prove: that Play Services actually delivers the item from a phone. That is
 * stated plainly in the changelog rather than implied by a passing test.
 */
@RunWith(AndroidJUnit4::class)
class RideDataLayerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aRidePayloadSurvivesTheDataLayerAndDecodesUnchanged() {
        val request = PutDataMapRequest.create(WearSync.PATH_RIDE).apply {
            dataMap.putString(WearSync.KEY_STATUS, WearSync.STATUS_RECORDING)
            dataMap.putFloat(WearSync.KEY_SPEED_MPS, 7.5f)
            dataMap.putDouble(WearSync.KEY_DISTANCE_M, 12_400.0)
            dataMap.putLong(WearSync.KEY_ELAPSED_MS, 2_472_000L)
            dataMap.putLong(WearSync.KEY_MOVING_MS, 2_400_000L)
            dataMap.putBoolean(WearSync.KEY_HAS_FIX, true)
            dataMap.putInt(WearSync.KEY_SATELLITES, 9)
            dataMap.putBoolean(WearSync.KEY_IMPERIAL, false)
            dataMap.putLong(WearSync.KEY_UPDATED_AT, System.currentTimeMillis())
        }

        val client = Wearable.getDataClient(context)
        Tasks.await(client.putDataItem(request.asPutDataRequest().setUrgent()), 20, TimeUnit.SECONDS)

        val items = Tasks.await(client.dataItems, 20, TimeUnit.SECONDS)
        val stored = items.firstOrNull { it.uri.path == WearSync.PATH_RIDE }
        assertTrue("the ride item was not stored in the Data Layer", stored != null)

        val ride = DataMapItem.fromDataItem(stored!!).dataMap.toRide()
        items.release()

        assertEquals(WearSync.STATUS_RECORDING, ride.status)
        assertEquals(7.5f, ride.speedMps, 0.001f)
        assertEquals(12_400.0, ride.distanceM, 0.001)
        assertEquals(2_472_000L, ride.elapsedMs)
        assertEquals(9, ride.satellites)
        assertTrue(ride.hasFix)
        assertTrue("a recording ride must count as active", ride.isActive)
    }

    @Test
    fun theValuesTheWatchWouldShowAreTheOnesThePhoneSent() {
        // The numbers above, formatted exactly as the screen formats them. This is what catches a
        // unit mix-up, which is the failure a rider would actually notice.
        val ride = DataMap().apply {
            putString(WearSync.KEY_STATUS, WearSync.STATUS_RECORDING)
            putFloat(WearSync.KEY_SPEED_MPS, 7.5f)
            putDouble(WearSync.KEY_DISTANCE_M, 12_400.0)
            putLong(WearSync.KEY_ELAPSED_MS, 2_472_000L)
            putBoolean(WearSync.KEY_IMPERIAL, false)
        }.toRide()

        assertEquals("27.0", WearFormat.speed(ride.speedMps, ride.imperial))
        assertEquals("12.40", WearFormat.distance(ride.distanceM, ride.imperial))
        assertEquals("41:12", WearFormat.duration(ride.elapsedMs))
    }

    @Test
    fun anEmptyPayloadDecodesToAnIdleRideRatherThanThrowing() {
        // A payload from an older or newer phone must not crash the watch mid-ride.
        val ride = DataMap().toRide()
        assertEquals(WearSync.STATUS_IDLE, ride.status)
        assertEquals(0f, ride.speedMps, 0.0001f)
        assertTrue("an empty payload must not read as an active ride", !ride.isActive)
    }
}
